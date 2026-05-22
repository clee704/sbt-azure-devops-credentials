// Copyright (C) 2024 the original author or authors.
// See the LICENCE.txt file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package dev.chungmin.sbt

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import java.net.{InetSocketAddress, Socket}

import javax.net.ssl.SSLSocketFactory

import scala.util.control.NonFatal
import scala.xml.XML

import sbt._
import sbt.util.Logger
import Keys._

import lmcoursier.CoursierConfiguration
import lmcoursier.definitions.Authentication

import com.azure.core.credential.{TokenCredential, TokenRequestContext}
import com.azure.identity.{
  AzureCliCredentialBuilder,
  AzurePowerShellCredentialBuilder,
  ChainedTokenCredentialBuilder,
  EnvironmentCredentialBuilder,
  ManagedIdentityCredentialBuilder,
  WorkloadIdentityCredentialBuilder
}

object AzureDevOpsCredentialsPlugin extends AutoPlugin {
  override def trigger = allRequirements

  // NOTE on the package-private modifier used throughout this file:
  // `private[chungmin]` (not `private[sbt]`) is deliberate. The plugin's
  // package is `dev.chungmin.sbt`, so Scala's enclosing-package resolution
  // for `private[X]` matches the leaf segment of that package — i.e.
  // `private[sbt]` here would be `private[`dev.chungmin.sbt`]`, NOT
  // package-private to the global `sbt` ecosystem (which is not an
  // enclosing scope). `private[chungmin]` resolves unambiguously to
  // `dev.chungmin.*` — a strictly clearer way to express "visible to test
  // code in this package but not part of the user-facing API" without the
  // parse hazard of looking exposed to all of sbt.

  /** Azure DevOps resource scope for access tokens. */
  private[chungmin] val AzureDevOpsScope = "499b84ac-1321-427f-aa17-267ca6975798/.default"

  /** System property to control Azure Identity SDK logging.
    * Set to "off" during token acquisition to suppress expected [ERROR] messages
    * from ChainedTokenCredential trying each provider in sequence. */
  private val AzureIdentityLogProperty = "org.slf4j.simpleLogger.log.com.azure.identity"

  /** Set [[AzureIdentityLogProperty]] to `"off"` at plugin classloading if it
    * isn't already set. Defends against the SLF4J SimpleLogger caching
    * pattern: SimpleLogger reads the per-logger level once at logger
    * instantiation time and caches it for the JVM's life. A later
    * `System.setProperty(prop, "off")` only affects loggers that haven't
    * been initialized yet; loggers already cached at INFO stay at INFO
    * regardless. If anything else in the JVM (another sbt plugin pulling
    * in azure-* transitively, an sbt server pre-classloading dependencies,
    * a `console` invocation, a `Class.forName` in some diagnostic helper)
    * touches a `com.azure.identity` logger BEFORE the plugin's first
    * `getTokenImpl()` call, the per-call counted-set suppression becomes a
    * silent no-op for the rest of the run.
    *
    * Running this in the plugin object's static-init block makes the
    * suppression effective for the typical case "plugin classloads before
    * azure-identity" — which holds in sbt because the AutoPlugin discovery
    * generally precedes any user setting that touches azure-* directly.
    * The case "azure-identity classloads before the plugin" (e.g. an
    * upstream plugin already runs an `az` call from a setting) is
    * unfixable from inside the plugin; the user should set
    * `-Dorg.slf4j.simpleLogger.log.com.azure.identity=off` via JVM args.
    *
    * Respects a pre-existing user value so a developer who explicitly sets
    * the property to `"debug"` for diagnostics sees their override. */
  private[chungmin] def initializeAzureIdentityLogSuppression(): Unit = {
    if (System.getProperty(AzureIdentityLogProperty) == null) {
      System.setProperty(AzureIdentityLogProperty, "off")
    }
  }

  initializeAzureIdentityLogSuppression()

  // Reference-counted suppression of the Azure-identity log-level system
  // property. The first acquirer saves the previous value and sets the
  // property to "off"; the last releaser restores it. Counted-set pattern
  // so that multiple in-flight CredentialsBuilder instances share one
  // suppression window without holding any lock during the (slow) Azure
  // SDK call — only the brief save+set / restore boundaries serialize.
  //
  // Synchronized on this private monitor, not on the plugin object itself,
  // so the lock can't be observed (or coincidentally taken) by outside
  // code.
  //
  // Important contract: the counted-set only flips the PROPERTY VALUE.
  // SimpleLogger caches the level at logger init, so this is only fully
  // effective if no com.azure.identity logger has been initialized yet
  // when the first acquire runs. The static initializer above
  // ([[initializeAzureIdentityLogSuppression]]) makes that precondition
  // hold in the typical case (plugin classloads before azure-identity).
  private val AzureIdentityLogSuppressionLock = new Object
  private var suppressionCount: Int = 0
  private var savedAzureIdentityLogLevel: Option[String] = None

  private[chungmin] def acquireAzureIdentityLogSuppression(): Unit =
    AzureIdentityLogSuppressionLock.synchronized {
      if (suppressionCount == 0) {
        savedAzureIdentityLogLevel = Option(System.getProperty(AzureIdentityLogProperty))
        System.setProperty(AzureIdentityLogProperty, "off")
      }
      suppressionCount += 1
    }

  private[chungmin] def releaseAzureIdentityLogSuppression(): Unit =
    AzureIdentityLogSuppressionLock.synchronized {
      // Fail fast at the actual bad caller: an unmatched release would
      // drop the counter to -1, and the next acquire would see
      // suppressionCount == 0 is false (it's -1) and skip the save+set,
      // silently disabling the suppression for the rest of the JVM run.
      // The drift looks healthy from the outside (count returns to 0
      // again after one more acquire/release pair), so without this
      // require() it'd go undetected in production.
      require(
        suppressionCount > 0,
        "releaseAzureIdentityLogSuppression called without a matching acquire")
      suppressionCount -= 1
      if (suppressionCount == 0) {
        savedAzureIdentityLogLevel match {
          case Some(level) => System.setProperty(AzureIdentityLogProperty, level)
          case None => System.clearProperty(AzureIdentityLogProperty)
        }
        savedAzureIdentityLogLevel = None
      }
    }

  /** Snapshot of the suppression counter for test-isolation assertions.
    * The counted-set state ([[suppressionCount]] and
    * [[savedAzureIdentityLogLevel]]) is JVM-global and persists across tests
    * within the same JVM. A future test (or a refactor of an existing one)
    * that leaks an unmatched acquire would otherwise manifest as a confusing
    * failure in a downstream test — the symptom (a wrong property value)
    * surfaces several tests away from the leak source. Exposed as
    * `private[chungmin]` so test code can assert `count == 0` between tests
    * and pin any future leak to its actual source. */
  private[chungmin] def suppressionCountForTesting: Int =
    AzureIdentityLogSuppressionLock.synchronized { suppressionCount }

  /** Normalize an env-var value: trim surrounding whitespace and return
    * `Some(trimmed)` when the result is non-empty; `None` for absent, empty,
    * or whitespace-only values. Extracted as a pure helper so the trim+filter
    * behavior is directly unit-testable (verifying via [[createCredential]]
    * alone would be tautological — the SDK builder accepts padded values, so
    * a chain-assembly assertion doesn't prove the trim ran). */
  private[chungmin] def envValue(env: Map[String, String], key: String): Option[String] =
    env.get(key).map(_.trim).filter(_.nonEmpty)

  /** Build the ordered list of credential providers that make up the
    * Azure DevOps token chain. Order: AzureCli → AzurePowerShell →
    * Environment → WorkloadIdentity (when its env vars are present) →
    * ManagedIdentity. AzureCli first is the v0.0.8 motivation — on dev
    * workstations with both `az login` and an ambient ManagedIdentity
    * (e.g. inside an Azure VM), the user's `az login` session should win.
    *
    * Exposed for testability so the chain *order* is directly assertable;
    * [[ChainedTokenCredential]] doesn't expose its provider list, so a
    * test on [[createCredential]]'s return value alone could only verify
    * "the chain doesn't throw", not "AzureCli runs first" — which is the
    * actual invariant the v0.0.8 fix relies on.
    *
    * WorkloadIdentityCredentialBuilder.build() validates eagerly and
    * throws IllegalArgumentException unless its clientId / tenantId /
    * tokenFilePath are all set to non-null AND non-empty values. Unlike
    * DefaultAzureCredential, it does NOT auto-read AZURE_CLIENT_ID /
    * AZURE_TENANT_ID / AZURE_FEDERATED_TOKEN_FILE from the environment,
    * so we populate them ourselves via [[envValue]]. When those env vars
    * are absent, empty, or whitespace-only (the common case on dev
    * workstations) we skip the credential entirely; otherwise the chain
    * assembly would fail before AzureCli is ever tried. Padded values
    * (e.g. a trailing newline from a broken env-template substitution)
    * are trimmed by [[envValue]] before reaching the SDK, since the SDK
    * accepts them at build time but they'd fail at getToken time and add
    * noise to the chain.
    *
    * The env parameter exists for testability — production callers should
    * use the default. */
  private[chungmin] def credentialProviders(
      env: Map[String, String] = sys.env): Seq[TokenCredential] = {
    val providers = scala.collection.mutable.ListBuffer.empty[TokenCredential]
    providers += new AzureCliCredentialBuilder().build()
    providers += new AzurePowerShellCredentialBuilder().build()
    providers += new EnvironmentCredentialBuilder().build()
    for {
      clientId  <- envValue(env, "AZURE_CLIENT_ID")
      tenantId  <- envValue(env, "AZURE_TENANT_ID")
      tokenFile <- envValue(env, "AZURE_FEDERATED_TOKEN_FILE")
    } {
      providers += new WorkloadIdentityCredentialBuilder()
        .clientId(clientId)
        .tenantId(tenantId)
        .tokenFilePath(tokenFile)
        .build()
    }
    providers += new ManagedIdentityCredentialBuilder().build()
    providers.toList
  }

  /** Create the [[ChainedTokenCredential]] used at token-acquisition time.
    * Thin wrapper over [[credentialProviders]] that wires the seq into a
    * [[ChainedTokenCredentialBuilder]]. */
  private[chungmin] def createCredential(env: Map[String, String] = sys.env): TokenCredential = {
    val builder = new ChainedTokenCredentialBuilder()
    credentialProviders(env).foreach(builder.addLast)
    builder.build()
  }

  /** Extract organization name from Azure DevOps URL. Exposed for testing. */
  private[chungmin] def getOrganization(uri: URI): Option[String] = {
    val host = uri.getHost
    if (host == null) {
      None
    } else if (host.endsWith("pkgs.visualstudio.com")) {
      Some(host.split("\\.").head)
    } else if (host == "pkgs.dev.azure.com") {
      // Path is like "/myorg/myproject/..." - split and filter empty strings
      val pathFragments = uri.getPath.split("/").filter(_.nonEmpty)
      pathFragments.headOption
    } else {
      None
    }
  }

  /** Check if host is an Azure DevOps package feed. Exposed for testing. */
  private[chungmin] def isAzureDevOpsHost(host: String): Boolean = {
    host != null && (host.endsWith("pkgs.visualstudio.com") || host == "pkgs.dev.azure.com")
  }

  /** Return the explicit port from `uri`, or the scheme default (443 for `https`,
    * 80 for everything else). Extracted as a pure helper for direct unit testing —
    * exercising it through [[headRequestHeaders]] would require an actual socket
    * round-trip and only verifies behavior tautologically (any port choice fails
    * the same way against an unresponsive address). */
  private[chungmin] def defaultPort(uri: URI): Int =
    if (uri.getPort >= 0) uri.getPort
    else if (uri.getScheme == "https") 443
    else 80

  /** Send an unauthenticated HEAD request and return the response headers.
    *
    * Uses a raw socket instead of HttpURLConnection to avoid triggering
    * sbt's global IvyAuthenticator, which logs spurious "Unable to find
    * credentials" error messages when the server responds with 401. */
  private[chungmin] def headRequestHeaders(uri: URI): Seq[(String, String)] = {
    val host = uri.getHost
    val port = defaultPort(uri)
    val path = Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/")
    val rawSocket = new Socket()
    try {
      rawSocket.connect(new InetSocketAddress(host, port), 10000)
      rawSocket.setSoTimeout(10000)
      val socket = if (uri.getScheme == "https") {
        SSLSocketFactory.getDefault.asInstanceOf[SSLSocketFactory]
          .createSocket(rawSocket, host, port, /* autoClose = */ true)
      } else {
        rawSocket
      }
      try {
        val writer = new BufferedWriter(
          new OutputStreamWriter(socket.getOutputStream, "UTF-8"))
        writer.write(
          s"HEAD $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n")
        writer.flush()
        val reader = new BufferedReader(
          new InputStreamReader(socket.getInputStream, "UTF-8"))
        val headers = Seq.newBuilder[(String, String)]
        reader.readLine() // skip status line
        var line = reader.readLine()
        while (line != null && line.nonEmpty) {
          val colon = line.indexOf(':')
          if (colon > 0) {
            headers += (line.substring(0, colon).trim -> line.substring(colon + 1).trim)
          }
          line = reader.readLine()
        }
        headers.result()
      } finally {
        socket.close()
      }
    } catch {
      case NonFatal(e) =>
        rawSocket.close()
        throw e
    }
  }

  // $COVERAGE-OFF$ sbt task wiring — exercised only inside a real sbt build, not unit-testable
  override lazy val projectSettings = Seq(
    credentials ++= new CredentialsBuilder(streams.value.log)
      .buildCredentials(credentials.value, externalResolvers.value),

    // Fix for https://github.com/coursier/coursier/issues/1649
    csrConfiguration := updateCoursierConf(
        csrConfiguration.value, csrResolvers.value, credentials.value),
    updateClassifiers / csrConfiguration :=
        csrConfiguration.value.withClassifiers(Vector("sources")).withHasClassifiers(true)
  )
  // $COVERAGE-ON$

  class CredentialsBuilder(log: Logger) {
    def buildCredentials(
        existingCredentials: Seq[Credentials],
        resolvers: Seq[Resolver]): Seq[Credentials] = {
      val credentialsFromMavenSettings = buildCredentialsFromMavenSettings(resolvers)
      val generatedCredentials = buildCredentialsWithAccessToken(
        existingCredentials ++ credentialsFromMavenSettings,
        resolvers)
      credentialsFromMavenSettings ++ generatedCredentials
    }

    /** Location of the Maven settings.xml file. Overridable for testing. */
    protected def mavenSettingsFile: File = new File(sbt.io.Path.userHome, ".m2/settings.xml")

    /** Create the TokenCredential chain used by [[getTokenImpl]]. Overridable
      * for testing so the Azure SDK does not have to be exercised. */
    protected def newCredential(): TokenCredential = AzureDevOpsCredentialsPlugin.createCredential()

    private def buildCredentialsWithAccessToken(
        existingCredentials: Seq[Credentials],
        resolvers: Seq[Resolver]): Seq[Credentials] = {
      val credMap = existingCredentials.collect {
        case credential: DirectCredentials =>
          credential.host -> credential
      }.toMap
      log.debug(s"existing DirectCredentials: $credMap")
      val credentials = resolvers.collect {
        case repo: MavenRepo =>
          log.debug(s"found a MavenRepo $repo from resolvers")
          val uri = new URI(repo.root)
          val host = uri.getHost
          if (!credMap.contains(host)) {
            getOrganizationWithLog(uri).flatMap { org =>
              for {
                realm <- getRealm(uri)
                token <- getToken()
              } yield {
                log.debug(s"creating credentials for realm=$realm, host=$host, user=$org")
                Credentials(realm, host, org, token)
              }
            }
          } else {
            log.debug(s"$host has existing credentials")
            None
          }
      }.flatten
      log.debug(s"created ${credentials.size} credentials with access token")
      credentials
    }

    private def getOrganizationWithLog(uri: URI): Option[String] = {
      val maybeOrg = AzureDevOpsCredentialsPlugin.getOrganization(uri)
      log.debug(s"found org=$maybeOrg from URI=$uri")
      maybeOrg
    }

    /** Discover the BASIC auth realm advertised by the feed. Overridable for testing. */
    protected def getRealm(uri: URI): Option[String] = {
      try {
        val headers = AzureDevOpsCredentialsPlugin.headRequestHeaders(uri)
        val realm = headers.collectFirst {
          case (name, value)
              if name.equalsIgnoreCase("WWW-Authenticate") &&
                 value.startsWith("Basic realm=") =>
            "Basic realm=\"([^\"]*)\"".r.findFirstMatchIn(value).map(_.group(1))
        }.flatten
        log.debug(s"found realm=$realm for URI=$uri")
        realm
      } catch {
        case NonFatal(e) =>
          log.warn(s"failed to get realm for host ${uri.getHost}: $e")
          None
      }
    }

    private var cachedToken: Option[Option[String]] = None

    /** Acquire and cache an Azure DevOps access token. Overridable for testing. */
    protected def getToken(): Option[String] = synchronized {
      if (!cachedToken.isDefined) {
        cachedToken = Some(getTokenImpl())
      }
      cachedToken.get
    }

    private def getTokenImpl(): Option[String] = {
      log.debug("trying to create access token")
      // Suppress Azure Identity logging during token acquisition. ChainedTokenCredential
      // logs [ERROR] for each provider that fails, which are expected failures — not all
      // providers are available in all environments.
      //
      // The suppression is a JVM-wide system property (`AzureIdentityLogProperty`), so
      // we coordinate via a counted-set on the plugin object: the first acquirer saves
      // the previous value and sets the property to "off"; the last releaser restores
      // it. The brief save+set / restore boundaries serialize across threads, but the
      // SDK call itself runs without any plugin-level lock, so multi-project sbt builds
      // can fetch tokens in parallel.
      AzureDevOpsCredentialsPlugin.acquireAzureIdentityLogSuppression()
      try {
        val credential = newCredential()
        val request = new TokenRequestContext().addScopes(AzureDevOpsScope)
        val token = credential.getToken(request).block()
        if (token != null) {
          log.debug("access token created")
          Some(token.getToken())
        } else {
          log.warn(s"failed to get access token (getToken() returned null)")
          None
        }
      } catch {
        case NonFatal(e) =>
          log.warn(s"failed to get access token. Did you forget to run `az login`?")
          log.debug(e.toString())
          None
      } finally {
        AzureDevOpsCredentialsPlugin.releaseAzureIdentityLogSuppression()
      }
    }

    private def buildCredentialsFromMavenSettings(resolvers: Seq[Resolver]): Seq[Credentials] = {
      // TODO: support M2_HOME
      val settingsFile = mavenSettingsFile
      log.debug(s"trying to load credentials from $settingsFile")
      if (!settingsFile.exists) {
        log.debug(s"file not found: $settingsFile")
        return Nil
      }
      val xml = XML.loadFile(settingsFile)
      val servers = (xml \ "servers" \ "server").map { server =>
        val id = (server \ "id").text
        val username = (server \ "username").text
        val password = (server \ "password").text
        (id -> (username, password))
      }.toMap
      log.debug(s"loaded $settingsFile")
      val credentials = resolvers.collect {
        case repo: MavenRepo =>
          log.debug(s"found a MavenRepo $repo from resolvers")
          val maybeServer = servers.get(repo.name)
          maybeServer.map { server =>
            log.debug(s"found a matching <server> for $repo")
            val uri = new URI(repo.root)
            val host = uri.getHost
            val (user, password) = server
            getRealm(uri).map { realm =>
              log.debug(s"creating credentials for realm=$realm, host=$host, user=$user")
              Credentials(realm, host, user, password)
            }
          }.getOrElse {
            log.debug("matching <server> not found")
            None
          }
      }.flatten
      log.debug(s"created ${credentials.size} credentials from settings.xml")
      credentials
    }
  }

  // Fix for https://github.com/coursier/coursier/issues/1649
  private[chungmin] def updateCoursierConf(
      conf: CoursierConfiguration,
      resolvers: Seq[Resolver],
      credentials: Seq[Credentials]) = {
    val credMap = credentials.collect {
      case credential: DirectCredentials =>
        credential.host -> credential
    }.toMap
    val auths = resolvers.collect {
      case repo: MavenRepo =>
        val uri = new URI(repo.root)
        val host = uri.getHost
        credMap.get(host).map { credential =>
          repo.name -> Authentication(credential.userName, credential.passwd)
        }
    }.flatten
    auths.foldLeft(conf) { case (conf, (repoId, auth)) =>
      val authenticationByRepositoryId = conf.authenticationByRepositoryId :+ (repoId, auth)
      conf.withAuthenticationByRepositoryId(authenticationByRepositoryId)
    }
  }
}
