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

import javax.net.ssl.{SSLSocket, SSLSocketFactory}

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

  object autoImport {
    /** Controls how `~/.m2/settings.xml` `<server>` entries are validated
      * against the feed before being trusted.
      *
      * Values: `"auto"` (default), `"always"`, `"never"`. See
      * [[AzureDevOpsCredentialsPlugin.ValidateExistingCredentialsProperty]]
      * for full semantics. Defaults to the value of
      * `-Ddev.chungmin.azure.validateExistingCredentials=...` at task
      * evaluation time, and overrides that property for the project it's
      * scoped to — per-project, not JVM-global. In a multi-project build,
      * two projects can have different validation modes via this setting
      * (impossible via the `-D` property alone). */
    val azureDevOpsValidateExistingCredentials =
      settingKey[String]("Validate ~/.m2/settings.xml entries against feeds: auto|always|never")
  }
  import autoImport._

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

  /** System property controlling whether existing `~/.m2/settings.xml`
    * `<server>` entries are probed against the feed before being trusted.
    *
    * Values (case-insensitive):
    *
    *   - `"auto"` (default): probe each entry with a HEAD request; if the
    *     feed returns 401 AND Entra acquisition succeeds, drop the entry and
    *     fall through to the Entra path. If Entra is unreachable, keep the
    *     stale entry and log an INFO line — same outcome as the legacy
    *     behavior, but with diagnostic signal.
    *   - `"always"`: probe; on 401, drop unconditionally (Entra's failure
    *     becomes the user-visible error if it can't acquire either).
    *   - `"never"`: legacy behavior — trust settings.xml entries
    *     unconditionally.
    *
    * Configurable as `-D` JVM property, in `.jvmopts`, or via `SBT_OPTS`.
    * The `build.sbt` setting [[autoImport.azureDevOpsValidateExistingCredentials]]
    * defaults to this property's value and is passed directly into
    * [[CredentialsBuilder]] at task evaluation time — taking precedence over
    * this property for the project it's scoped to. The setting deliberately
    * does NOT write back to the system property (which is JVM-global) so
    * per-project overrides don't leak across projects in a multi-module
    * build. */
  private[chungmin] val ValidateExistingCredentialsProperty =
    "dev.chungmin.azure.validateExistingCredentials"

  /** Legal values for [[ValidateExistingCredentialsProperty]]. */
  private[chungmin] val ValidateAuto = "auto"
  private[chungmin] val ValidateAlways = "always"
  private[chungmin] val ValidateNever = "never"

  /** System property to control Azure Identity SDK logging.
    * Set to "off" during token acquisition to suppress expected [ERROR] messages
    * from ChainedTokenCredential trying each provider in sequence.
    *
    * Exposed as `private[chungmin]` (not pure `private`) so test sites can
    * reference the property name through the constant instead of re-declaring
    * the string literal in every test that touches the property. Keeping the
    * literal in one place means an Azure-SDK-driven rename (or an SLF4J
    * binding-key change) wouldn't silently leave tests asserting against a
    * stale property name. */
  private[chungmin] val AzureIdentityLogProperty = "org.slf4j.simpleLogger.log.com.azure.identity"

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

  /** Return the effective HTTP/HTTPS port for `uri`: the explicit port when
    * set, otherwise 443 for `https` and 80 for everything else.
    *
    * Despite the general-sounding name, this is intentionally an http/https
    * helper, not a scheme-aware lookup. It exists only because
    * [[headRequestHeaders]] needs a port to open a socket against MavenRepo
    * resolvers, which the rest of the plugin filters to http/https-only URIs
    * via [[isAzureDevOpsHost]] (no `ftp:`/`file:`/`ssh:` resolver reaches
    * here in practice). The `else 80` branch is the gate-coverage tail that
    * keeps the helper total — not a canonical default for `ftp` or any
    * other non-http(s) scheme, where 80 is wrong (ftp's canonical port is
    * 21, ssh's is 22, etc.).
    *
    * Extracted as a pure helper for direct unit testing — exercising it
    * through [[headRequestHeaders]] would require an actual socket
    * round-trip and only verifies behavior tautologically (any port choice
    * fails the same way against an unresponsive address). */
  private[chungmin] def defaultPort(uri: URI): Int =
    if (uri.getPort >= 0) uri.getPort
    else if (uri.getScheme == "https") 443
    else 80

  /** Build the HTTP/1.1 HEAD request line + headers as a single string,
    * ready to write to the socket. Pure helper (no I/O) so the request
    * shape is directly unit-testable — verifying via [[headRequest]]
    * end-to-end would require an actual round-trip, and the auth-on-wire
    * positive case can't run over plain TCP after the
    * `https`-required guard on [[headRequest]].
    *
    * Format: `HEAD <path> HTTP/1.1\r\nHost: <host>\r\n[Authorization: <auth>\r\n]Connection: close\r\n\r\n`.
    * The Authorization line is included iff `authHeader` is `Some(...)`. */
  private[chungmin] def buildHeadRequestLine(
      host: String, path: String, authHeader: Option[String]): String = {
    val authLine = authHeader.map(h => s"Authorization: $h\r\n").getOrElse("")
    s"HEAD $path HTTP/1.1\r\nHost: $host\r\n${authLine}Connection: close\r\n\r\n"
  }

  /** Send a HEAD request with optional credentials and return both the
    * response status code (parsed from the first line) and the response
    * headers.
    *
    * Uses a raw socket instead of HttpURLConnection to avoid triggering
    * sbt's global IvyAuthenticator (which logs spurious "Unable to find
    * credentials" errors on 401) AND to keep the Authorization header
    * from being logged by Aether/sbt's HTTP transport layer — a
    * non-trivial PAT-leakage hazard since 0.0.9 added the probe path.
    *
    * The authorization header (if provided) is set exactly once and never
    * re-sent on a redirect (we don't follow them — the response line is
    * read and returned verbatim).
    *
    * **Defense-in-depth on cleartext credentials.** When `authHeader` is
    * `Some(...)` and `uri.getScheme` is not `"https"`, throws
    * `IllegalArgumentException` before opening the socket — preventing
    * any future caller (refactor, new probe helper, etc.) from accidentally
    * writing the Authorization header over a plain-TCP wire. The
    * `isStaleSettingsEntry` entry-point guard at the call site short-
    * circuits the same case for the production probe path; this guard is
    * the second gate, so the comment claim "defense-in-depth" is literally
    * two gates instead of one. */
  private[chungmin] def headRequest(
      uri: URI, authHeader: Option[String]): (Option[Int], Seq[(String, String)]) = {
    require(
      authHeader.isEmpty || uri.getScheme == "https",
      s"headRequest with an Authorization header requires an https URI; got ${uri.getScheme}://${uri.getHost} " +
        "(refusing to write credentials over a plain-TCP wire)")
    val host = uri.getHost
    val port = defaultPort(uri)
    val path = Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/")
    val rawSocket = new Socket()
    try {
      rawSocket.connect(new InetSocketAddress(host, port), 10000)
      rawSocket.setSoTimeout(10000)
      val socket = if (uri.getScheme == "https") {
        val sslSocket = SSLSocketFactory.getDefault.asInstanceOf[SSLSocketFactory]
          .createSocket(rawSocket, host, port, /* autoClose = */ true)
          .asInstanceOf[SSLSocket]
        // Enable HTTPS endpoint identification: verifies the server cert's
        // CN/SAN matches the URI's host. Raw SSLSocket does cert-chain
        // validation by default but skips hostname matching unless we opt in
        // here (documented JDK behavior through 21+). Critical for the v0.0.10
        // authenticated-probe path: we now put PATs (Basic) and Entra tokens
        // (Bearer) on the wire via this helper, so a misissued cert from a
        // publicly-trusted CA + DNS hijack would otherwise read credentials
        // off the wire. Pre-v0.0.10 the helper only sent unauthenticated
        // realm-detection probes, so the gap was harmless then.
        configureSslEndpointIdentification(sslSocket)
      } else {
        rawSocket
      }
      try {
        val writer = new BufferedWriter(
          new OutputStreamWriter(socket.getOutputStream, "UTF-8"))
        writer.write(buildHeadRequestLine(host, path, authHeader))
        writer.flush()
        val reader = new BufferedReader(
          new InputStreamReader(socket.getInputStream, "UTF-8"))
        val headers = Seq.newBuilder[(String, String)]
        // Parse the status line: "HTTP/1.1 401 Unauthorized" -> Some(401).
        // Tolerant parse: any malformed status line returns None.
        val statusLine = reader.readLine()
        val status: Option[Int] = Option(statusLine).flatMap { line =>
          val parts = line.split(" ", 3)
          if (parts.length >= 2) scala.util.Try(parts(1).toInt).toOption else None
        }
        var line = reader.readLine()
        while (line != null && line.nonEmpty) {
          val colon = line.indexOf(':')
          if (colon > 0) {
            headers += (line.substring(0, colon).trim -> line.substring(colon + 1).trim)
          }
          line = reader.readLine()
        }
        (status, headers.result())
      } finally {
        socket.close()
      }
    } catch {
      case NonFatal(e) =>
        rawSocket.close()
        throw e
    }
  }

  /** Send an unauthenticated HEAD request and return the response headers.
    *
    * Thin wrapper over [[headRequest]] for callers that only need headers
    * (e.g. realm detection). Status-code consumers should use [[headRequest]]
    * directly. */
  private[chungmin] def headRequestHeaders(uri: URI): Seq[(String, String)] =
    headRequest(uri, authHeader = None)._2

  /** Enable HTTPS endpoint identification on a freshly-created
    * [[javax.net.ssl.SSLSocket]] so the TLS handshake verifies the server's
    * cert CN/SAN matches the URI's host. Returns the same socket reference
    * for chaining.
    *
    * Extracted as a testable helper because a unit test for the underlying
    * intent ("the socket asks for hostname verification") is much cleaner
    * than spinning up a self-signed-cert HTTPS test server just to observe
    * the resulting [[javax.net.ssl.SSLHandshakeException]] — the JDK already
    * enforces the algorithm once it's set; we only need to assert the call
    * site sets it. */
  private[chungmin] def configureSslEndpointIdentification(socket: SSLSocket): SSLSocket = {
    val params = socket.getSSLParameters
    params.setEndpointIdentificationAlgorithm("HTTPS")
    socket.setSSLParameters(params)
    socket
  }

  /** Build the Basic auth header value (`"Basic <base64(user:pass)>"`)
    * from a username/password pair. Pure helper, no I/O. */
  private[chungmin] def basicAuthHeader(user: String, pass: String): String = {
    val raw = s"$user:$pass".getBytes("UTF-8")
    "Basic " + java.util.Base64.getEncoder.encodeToString(raw)
  }

  /** Normalize a raw mode string (from `-D`, sys.props, or the setting key) to
    * one of the canonical lower-case values. Unknown / null / empty input
    * falls back to the default (`"auto"`).
    *
    * Centralizes the lower-case + canonical-match logic so both the
    * property-reader [[validateExistingCredentialsMode]] AND the
    * [[CredentialsBuilder]] two-arg constructor go through the same
    * normalization — preventing the case-sensitivity regression where the
    * production wiring would silently degrade `-D...=NEVER` or
    * `:= "Always"` to the auto branch. */
  private[chungmin] def normalizeMode(value: String): String =
    Option(value).map(_.toLowerCase) match {
      case Some(ValidateAlways) => ValidateAlways
      case Some(ValidateNever) => ValidateNever
      case _ => ValidateAuto
    }

  /** Read [[ValidateExistingCredentialsProperty]] and normalize the value to
    * one of `"auto" | "always" | "never"`. Unknown / null values fall back
    * to the default (`"auto"`).
    *
    * Exposed for testability so the property-precedence logic is directly
    * assertable; the setting key in [[autoImport]] writes through to this
    * property, so a unit test that sets the property exercises the same
    * code path the setting-key wiring uses. */
  private[chungmin] def validateExistingCredentialsMode(): String =
    normalizeMode(System.getProperty(ValidateExistingCredentialsProperty))


  // $COVERAGE-OFF$ sbt task wiring — exercised only inside a real sbt build, not unit-testable
  override lazy val projectSettings = Seq(
    // Route through validateExistingCredentialsMode so the default is
    // normalized (lowercased, unknown → "auto"). Critical: without this,
    // `-D...=NEVER` would land as raw "NEVER" in the setting value, pass
    // straight into CredentialsBuilder(log, mode), and silently degrade to
    // auto because the constants are lowercase.
    azureDevOpsValidateExistingCredentials := validateExistingCredentialsMode(),

    credentials ++= {
      // Pass the mode VALUE directly into CredentialsBuilder. Using a JVM-wide
      // System property as the bridge would race across the per-project credentials
      // tasks in a multi-project build (sbt may evaluate them in any order, and the
      // System property is global). Direct argument-passing gives each project's
      // CredentialsBuilder the mode that was actually scoped to it.
      //
      // CredentialsBuilder's constructor re-normalizes mode so a user who sets
      // `azureDevOpsValidateExistingCredentials := "ALWAYS"` directly in build.sbt
      // (bypassing the projectSettings default above) still gets correct behavior.
      val mode = azureDevOpsValidateExistingCredentials.value
      new CredentialsBuilder(streams.value.log, mode)
        .buildCredentials(credentials.value, externalResolvers.value)
    },

    // Fix for https://github.com/coursier/coursier/issues/1649
    csrConfiguration := updateCoursierConf(
        csrConfiguration.value, csrResolvers.value, credentials.value),
    updateClassifiers / csrConfiguration :=
        csrConfiguration.value.withClassifiers(Vector("sources")).withHasClassifiers(true)
  )
  // $COVERAGE-ON$

  class CredentialsBuilder(log: Logger, rawMode: String) {
    // Normalize at construction time so all internal call sites can compare
    // with `==` against the canonical lowercase constants without re-normalizing.
    private val mode: String = AzureDevOpsCredentialsPlugin.normalizeMode(rawMode)

    /** Backward-compat overload: reads the mode from
      * [[ValidateExistingCredentialsProperty]] at construction time (preserves
      * the v0.0.10 internal-API shape used by tests). */
    def this(log: Logger) =
      this(log, AzureDevOpsCredentialsPlugin.validateExistingCredentialsMode())

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
            if (isStaleSettingsEntry(uri, host, user, password)) {
              // Probe verdict + mode policy says drop. Returning None here
              // means buildCredentialsWithAccessToken will see no entry for
              // this host and generate a fresh Entra credential. The INFO
              // log lives in [[isStaleSettingsEntry]] so the user sees
              // exactly which entry was overridden, with the actionable
              // remediation if Entra also fails.
              None
            } else {
              getRealm(uri).map { realm =>
                log.debug(s"creating credentials for realm=$realm, host=$host, user=$user")
                Credentials(realm, host, user, password)
              }
            }
          }.getOrElse {
            log.debug("matching <server> not found")
            None
          }
      }.flatten
      log.debug(s"created ${credentials.size} credentials from settings.xml")
      credentials
    }

    // Per-builder cache of probe verdicts, keyed by the FULL feed URI string.
    // Keying by URI (not just host) is required for correctness: ADO serves
    // every org and every feed at the same host (`pkgs.dev.azure.com`, or
    // multiple feeds under `<org>.pkgs.visualstudio.com/<project>/_packaging/`)
    // so a host-keyed cache would let the first feed's verdict bleed into
    // every other feed on the same host — silently trusting stale entries
    // for feed B if feed A's PAT happened to be valid (the bug this PR is
    // designed to fix), or silently dropping valid entries for feed A if
    // feed B's PAT happened to be stale. Per-URI keeps the optimization
    // (one probe per distinct feed URI within a single credentials-task
    // evaluation — i.e. deduping when multiple resolvers in one project
    // resolve to the same URI, and deduping concurrent first-touch when
    // sbt evaluates the credentials task in parallel within that project)
    // while making the verdict per-credential. ConcurrentHashMap +
    // `computeIfAbsent` provides the concurrent-first-touch atomicity.
    //
    // The cache scope is per-builder, and `projectSettings` instantiates
    // one builder per project's `credentials` task. An N-module sbt build
    // that shares one feed across all modules will probe N times across
    // the build (once per module) — sharing the cache across modules
    // would require build-scope mutable state we deliberately don't
    // introduce.
    private val probeCache =
      new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()

    /** Decide whether the settings.xml entry for `(uri, user, password)`
      * should be dropped (returns `true` = "drop, fall through to Entra")
      * or trusted (`false`).
      *
      * Honors [[ValidateExistingCredentialsProperty]]:
      *   - `never` → always trust (returns `false` without probing)
      *   - `always` → probe; drop on 401, trust otherwise
      *   - `auto` (default) → probe; on 401 try Entra. If Entra works,
      *     drop. If Entra unreachable, keep the entry but log an INFO line
      *     telling the user what happened so the eventual 401 isn't a
      *     mystery.
      *
      * Probe results are cached per URI for the builder's lifetime (see
      * [[probeCache]] doc for why URI and not host).
      * Non-ADO hosts and resolvers without a host (somehow) are never
      * probed — trust them. Network errors during the probe are also
      * trusted (assume transient, don't punish the user). */
    private[chungmin] def isStaleSettingsEntry(
        uri: URI, host: String, user: String, password: String): Boolean = {
      if (mode == ValidateNever) return false
      if (host == null || !AzureDevOpsCredentialsPlugin.isAzureDevOpsHost(host)) return false
      // Defense-in-depth: never put PAT/Bearer on a cleartext wire. The TLS
      // endpoint-identification fix (configureSslEndpointIdentification) only
      // protects the `if scheme == "https"` branch in headRequest; if the
      // resolver URI is `http://...`, the `else { rawSocket }` branch
      // bypasses TLS entirely and Authorization headers would go on the wire
      // in plaintext. ADO only serves HTTPS, so an `http://pkgs.dev.azure.com`
      // resolver is almost certainly a typo — but the cost of defending is
      // one line, and we'd rather trust the entry (legacy v0.0.9 behavior)
      // than expose credentials. Skipping here keeps the probe path clean
      // without affecting whatever the user's Aether/sbt fetch eventually
      // does over the same `http://` URL.
      if (uri.getScheme != "https") return false
      probeCache.computeIfAbsent(uri.toString, _ =>
        java.lang.Boolean.valueOf(probeAndDecide(uri, host, user, password, mode))
      ).booleanValue()
    }

    private def probeAndDecide(
        uri: URI, host: String, user: String, password: String, mode: String): Boolean =
      probeAndDecideImpl(uri, host, user, password, mode)

    /** Test seam for [[probeAndDecide]]. Production binds to a thin proxy of
      * the real implementation; tests override this to inject specific probe
      * outcomes and assert call counts (e.g. cache-hit verification).
      *
      * `mode` is passed explicitly as an argument (not read from the enclosing
      * builder's `mode` field) so a test can override `probeAndDecideImpl`
      * and assert decision-tree behaviour against an arbitrary mode without
      * constructing a new builder per mode. Production's only call site
      * (in [[isStaleSettingsEntry]]) passes the builder's `mode` field
      * verbatim — do not collapse the parameter into a `this.mode` read
      * without removing the test-injection seam, or the indirection silently
      * stops mattering. */
    protected[chungmin] def probeAndDecideImpl(
        uri: URI, host: String, user: String, password: String, mode: String): Boolean = {
      val status = probeWithBasic(uri, host, user, password)
      status match {
        case Some(401) =>
          if (mode == ValidateAlways) {
            log.info(
              s"$host: settings.xml credentials returned 401; " +
                "falling through to Entra token acquisition.")
            true
          } else {
            // auto mode. Step 1: can we even acquire an Entra token?
            getToken() match {
              case None =>
                log.info(
                  s"$host: settings.xml credentials returned 401, but Entra is " +
                    "also unreachable. Build will likely fail with 401. " +
                    "Run `az login` or configure AZURE_CLIENT_ID to enable " +
                    "automatic credential refresh.")
                false
              case Some(token) =>
                // Step 2: does that token actually work against this feed? On
                // hosts where Managed Identity is available (Azure VMs, App
                // Service), getToken() succeeds even when AzureCli is
                // unavailable — but the MI may not have access to the feed
                // the user's PAT was scoped to. Overriding the user's stale
                // PAT with a no-access MI token would leave the build still
                // failing with 401, with a misleading "fell through to Entra"
                // log line. Verify before override.
                val entraStatus = probeWithBearer(uri, host, token)
                if (entraStatus.contains(401)) {
                  log.info(
                    s"$host: settings.xml credentials returned 401, AND a fresh " +
                      "Entra token also returned 401 against this feed. The " +
                      "Entra identity in scope (Azure CLI / env vars / Managed " +
                      "Identity) may not have access to this feed. Try `az login` " +
                      "as a user with feed access, or check role assignments.")
                  false
                } else {
                  log.info(
                    s"$host: settings.xml credentials returned 401; " +
                      "falling through to Entra token acquisition.")
                  true
                }
            }
          }
        case _ =>
          // 200 / 404 / network error / unparseable status: trust the entry.
          // (Most ADO feed roots return 404 to a HEAD with valid Entra
          // bearer — we cannot distinguish "auth worked" from "feed deleted"
          // by status alone, so we don't try; this feature's scope is
          // specifically the stale-credential case.)
          false
      }
    }

    /** Probe `uri` with HTTP Basic auth derived from `user`/`password` and
      * return the response status. Exposed as a test seam: overriding this
      * lets a test inject specific Basic-probe outcomes (e.g. 401, 200,
      * `None` for network error) without driving a real socket through
      * [[headRequest]] — important because [[headRequest]] now refuses
      * Authorization headers on non-https URIs, so the easy fake-server
      * pattern of `http://localhost:$port/` can no longer hit the probe
      * path end-to-end. */
    protected[chungmin] def probeWithBasic(
        uri: URI, host: String, user: String, password: String): Option[Int] = {
      val auth = AzureDevOpsCredentialsPlugin.basicAuthHeader(user, password)
      try AzureDevOpsCredentialsPlugin.headRequest(uri, Some(auth))._1
      catch {
        case NonFatal(e) =>
          log.debug(s"probe of $host (Basic) failed with $e; trusting existing credentials")
          None
      }
    }

    /** Probe `uri` with HTTP Bearer auth and return the response status.
      * Same test-seam rationale as [[probeWithBasic]]. */
    protected[chungmin] def probeWithBearer(uri: URI, host: String, token: String): Option[Int] = {
      try AzureDevOpsCredentialsPlugin.headRequest(uri, Some(s"Bearer $token"))._1
      catch {
        case NonFatal(e) =>
          log.debug(s"verification probe of $host (Bearer) failed with $e; assuming token works")
          None
      }
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
