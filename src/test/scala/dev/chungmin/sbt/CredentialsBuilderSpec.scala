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

import java.io.{BufferedWriter, File, OutputStreamWriter, PrintWriter}
import java.net.{ServerSocket, URI}
import java.nio.file.Files
import java.time.{Duration, OffsetDateTime}
import java.util.concurrent.CountDownLatch
import javax.net.ssl.SSLException

import scala.util.control.NonFatal

import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import sbt._
import sbt.util.Logger
import lmcoursier.CoursierConfiguration
import lmcoursier.definitions.Authentication
import com.azure.core.credential.{AccessToken, TokenCredential, TokenRequestContext}
import reactor.core.publisher.Mono

class CredentialsBuilderSpec extends AnyFlatSpec with Matchers with BeforeAndAfter {

  // Defense in depth for the JVM-global suppression counter. `before` catches
  // contamination from another spec running in the same JVM; `after` pins any
  // leak in *this* spec to the exact test that caused it (otherwise a leak
  // surfaces as a confusing assertion failure in the next test that reads
  // the Azure-identity log-level property — see the nested-suppression test
  // for the canonical "wrong saved value" symptom that would result). We
  // assert rather than reset so leaks remain visible instead of being masked.
  before { AzureDevOpsCredentialsPlugin.suppressionCountForTesting shouldBe 0 }
  after  { AzureDevOpsCredentialsPlugin.suppressionCountForTesting shouldBe 0 }

  // ─── Test fixtures ─────────────────────────────────────────────────────

  /** No-op logger so tests don't pollute stdout. Evaluates by-name message
    * args (then discards them) so that scoverage sees the string-construction
    * statements being executed; otherwise every log.debug(s"…") call would be
    * counted as uncovered. */
  private val nullLog: Logger = new Logger {
    override def trace(t: => Throwable): Unit = { val _ = t }
    override def success(message: => String): Unit = { val _ = message }
    override def log(level: sbt.util.Level.Value, message: => String): Unit = { val _ = message }
  }

  /** Realistic ADO realm — exactly what `pkgs.dev.azure.com` returns in the
    * `WWW-Authenticate: Basic realm="..."` header of its 401 responses. */
  private val AdoRealm = "https://pkgsprodsu3weu.pkgs.visualstudio.com/"

  private val AdoFeedUrl = "https://pkgs.dev.azure.com/myorg/myproject/_packaging/myfeed/maven/v1"
  private val LegacyAdoFeedUrl = "https://myorg.pkgs.visualstudio.com/myproject/_packaging/myfeed/maven/v1"

  /** Build a fake TokenCredential whose `getToken(...)` returns the given Mono. */
  private def fakeCredential(mono: Mono[AccessToken]): TokenCredential =
    new TokenCredential {
      override def getToken(request: TokenRequestContext): Mono[AccessToken] = mono
    }

  /** Convenience: TokenCredential that returns a specific token string. */
  private def credentialReturning(token: String): TokenCredential =
    fakeCredential(Mono.just(new AccessToken(token, OffsetDateTime.now().plusHours(1))))

  /** Construct a CredentialsBuilder with all external dependencies stubbed. */
  private def testBuilder(
      realm: URI => Option[String] = _ => Some(AdoRealm),
      token: () => Option[String] = () => Some("test-pat"),
      settings: File = new File("/this/path/does/not/exist"),
      stale: (URI, String, String, String) => Boolean = (_, _, _, _) => false
  ): AzureDevOpsCredentialsPlugin.CredentialsBuilder = {
    new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
      override protected def getRealm(uri: URI): Option[String] = realm(uri)
      override protected def getToken(): Option[String] = token()
      override protected def mavenSettingsFile: File = settings
      // Default the probe verdict to "not stale" so existing tests preserve
      // their pre-probe semantics ("settings.xml entries are always trusted")
      // without depending on JVM-global system properties or actual network
      // I/O. Probe-specific tests pass an explicit stale function.
      override private[chungmin] def isStaleSettingsEntry(
          uri: URI, host: String, user: String, password: String): Boolean =
        stale(uri, host, user, password)
    }
  }

  /** Write a settings.xml to a temp file. */
  private def writeSettings(content: String): File = {
    val f = Files.createTempFile("settings-", ".xml").toFile
    f.deleteOnExit()
    val w = new PrintWriter(f, "UTF-8")
    try w.write(content) finally w.close()
    f
  }

  // ─── buildCredentials ───────────────────────────────────────────────────

  "CredentialsBuilder.buildCredentials" should
      "generate access-token credentials for a pkgs.dev.azure.com MavenRepo" in {
    val resolver = MavenRepository("AzureDevOps", AdoFeedUrl)
    val result = testBuilder().buildCredentials(Seq.empty, Seq(resolver))
    result should have size 1
    val cred = result.head.asInstanceOf[DirectCredentials]
    cred.realm shouldBe AdoRealm
    cred.host shouldBe "pkgs.dev.azure.com"
    cred.userName shouldBe "myorg"
    cred.passwd shouldBe "test-pat"
  }

  it should "generate access-token credentials for legacy *.pkgs.visualstudio.com feeds" in {
    val resolver = MavenRepository("AzureDevOps", LegacyAdoFeedUrl)
    val result = testBuilder().buildCredentials(Seq.empty, Seq(resolver))
    result should have size 1
    val cred = result.head.asInstanceOf[DirectCredentials]
    cred.host shouldBe "myorg.pkgs.visualstudio.com"
    cred.userName shouldBe "myorg"
  }

  it should "skip MavenRepos that aren't Azure DevOps" in {
    val resolver = MavenRepository("Maven Central", "https://repo1.maven.org/maven2/")
    testBuilder().buildCredentials(Seq.empty, Seq(resolver)) shouldBe empty
  }

  it should "skip when realm cannot be determined" in {
    val resolver = MavenRepository("AzureDevOps", AdoFeedUrl)
    testBuilder(realm = _ => None).buildCredentials(Seq.empty, Seq(resolver)) shouldBe empty
  }

  it should "skip when token cannot be acquired" in {
    val resolver = MavenRepository("AzureDevOps", AdoFeedUrl)
    testBuilder(token = () => None).buildCredentials(Seq.empty, Seq(resolver)) shouldBe empty
  }

  it should "skip when the host already has existing DirectCredentials" in {
    val resolver = MavenRepository("AzureDevOps", AdoFeedUrl)
    val existing = Credentials("preset-realm", "pkgs.dev.azure.com", "preset-user", "preset-pat")
    testBuilder().buildCredentials(Seq(existing), Seq(resolver)) shouldBe empty
  }

  it should "ignore non-MavenRepo resolvers" in {
    val resolver: Resolver = Resolver.file("local-ivy", new File("/tmp"))
    testBuilder().buildCredentials(Seq.empty, Seq(resolver)) shouldBe empty
  }

  it should "process multiple resolvers independently" in {
    val r1 = MavenRepository("Ado1", "https://pkgs.dev.azure.com/orgA/_packaging/feed/maven/v1")
    val r2 = MavenRepository("Ado2", "https://pkgs.dev.azure.com/orgB/_packaging/feed/maven/v1")
    val r3 = MavenRepository("Central", "https://repo1.maven.org/maven2/")
    val result = testBuilder().buildCredentials(Seq.empty, Seq(r1, r2, r3))
    val users = result.collect { case c: DirectCredentials => c.userName }
    users should contain theSameElementsAs Seq("orgA", "orgB")
  }

  // ─── buildCredentialsFromMavenSettings (exercised via buildCredentials) ─

  "buildCredentialsFromMavenSettings" should
      "load credentials from settings.xml when <server id> matches resolver name" in {
    val settings = writeSettings(
      """<?xml version="1.0"?>
        |<settings>
        |  <servers>
        |    <server>
        |      <id>AzureDevOps</id>
        |      <username>maven-user</username>
        |      <password>maven-token</password>
        |    </server>
        |  </servers>
        |</settings>""".stripMargin)
    val resolver = MavenRepository("AzureDevOps", AdoFeedUrl)
    val builder = testBuilder(settings = settings)
    val result = builder.buildCredentials(Seq.empty, Seq(resolver))
    // We get one settings-derived cred (maven-user) — the access-token path is
    // skipped because the settings-derived cred already populates the host.
    result should have size 1
    val cred = result.head.asInstanceOf[DirectCredentials]
    cred.realm shouldBe AdoRealm
    cred.host shouldBe "pkgs.dev.azure.com"
    cred.userName shouldBe "maven-user"
    cred.passwd shouldBe "maven-token"
  }

  it should "return empty when the settings.xml file does not exist" in {
    val resolver = MavenRepository("AzureDevOps", AdoFeedUrl)
    val builder = testBuilder(settings = new File("/no/such/settings.xml"))
    val result = builder.buildCredentials(Seq.empty, Seq(resolver))
    // No settings creds; only the access-token cred.
    val users = result.collect { case c: DirectCredentials => c.userName }
    users shouldBe Seq("myorg")
  }

  it should "skip when no <server> matches the resolver name" in {
    val settings = writeSettings(
      """<settings>
        |  <servers>
        |    <server>
        |      <id>SomeOtherRepo</id>
        |      <username>u</username>
        |      <password>p</password>
        |    </server>
        |  </servers>
        |</settings>""".stripMargin)
    val resolver = MavenRepository("AzureDevOps", AdoFeedUrl)
    val builder = testBuilder(settings = settings)
    val result = builder.buildCredentials(Seq.empty, Seq(resolver))
    val settingsCreds = result.collect {
      case c: DirectCredentials if c.userName == "u" => c
    }
    settingsCreds shouldBe empty
  }

  it should "skip a matching <server> when realm cannot be determined" in {
    val settings = writeSettings(
      """<settings>
        |  <servers>
        |    <server>
        |      <id>AzureDevOps</id>
        |      <username>u</username>
        |      <password>p</password>
        |    </server>
        |  </servers>
        |</settings>""".stripMargin)
    val resolver = MavenRepository("AzureDevOps", AdoFeedUrl)
    val builder = testBuilder(settings = settings, realm = _ => None)
    val result = builder.buildCredentials(Seq.empty, Seq(resolver))
    result shouldBe empty
  }

  // ─── getRealm (real implementation against local HTTP server) ───────────

  /** Test subclass exposing the protected getRealm for direct testing.
    *
    * The behaviour mirrors a real Azure DevOps Maven feed: when an
    * unauthenticated client hits the feed root, ADO returns 401 with a
    * `WWW-Authenticate: Basic realm="..."` header (along with Bearer +
    * TFS-Federated challenges, which the plugin must skip past). */
  private class RealmProbe extends AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
    def probe(uri: URI): Option[String] = getRealm(uri)
  }

  private def withHttpServer(rawResponse: String)(test: URI => Unit): Unit = {
    val server = new ServerSocket(0)
    try {
      val t = new Thread(() => {
        try {
          val client = server.accept()
          try {
            val buf = new Array[Byte](2048)
            client.getInputStream.read(buf)
            val w = new BufferedWriter(new OutputStreamWriter(client.getOutputStream, "UTF-8"))
            w.write(rawResponse)
            w.flush()
          } finally client.close()
        } catch { case NonFatal(_) => () }
      })
      t.setDaemon(true)
      t.start()
      val uri = new URI(s"http://localhost:${server.getLocalPort}/myproject/_packaging/myfeed/maven/v1")
      test(uri)
      t.join(5000)
    } finally server.close()
  }

  "getRealm" should "extract Basic realm from a realistic ADO 401 response" in {
    // Real ADO response shape — multiple WWW-Authenticate challenges, Basic
    // is not first. The plugin must specifically pick the Basic one.
    val response =
      "HTTP/1.1 401 Unauthorized\r\n" +
      "Content-Length: 0\r\n" +
      "WWW-Authenticate: Bearer authorization_uri=https://login.microsoftonline.com/72f988bf-86f1-41af-91ab-2d7cd011db47\r\n" +
      "WWW-Authenticate: Basic realm=\"https://pkgsprodsu3weu.pkgs.visualstudio.com/\"\r\n" +
      "WWW-Authenticate: TFS-Federated\r\n" +
      "X-TFS-ProcessId: 11111111-1111-1111-1111-111111111111\r\n" +
      "Connection: close\r\n\r\n"
    withHttpServer(response) { uri =>
      new RealmProbe().probe(uri) shouldBe Some("https://pkgsprodsu3weu.pkgs.visualstudio.com/")
    }
  }

  it should "return None when no Basic challenge is present" in {
    val response =
      "HTTP/1.1 401 Unauthorized\r\n" +
      "WWW-Authenticate: Bearer authorization_uri=https://login.microsoftonline.com/...\r\n" +
      "WWW-Authenticate: TFS-Federated\r\n\r\n"
    withHttpServer(response) { uri =>
      new RealmProbe().probe(uri) shouldBe None
    }
  }

  it should "match the WWW-Authenticate header case-insensitively" in {
    // Some servers/proxies normalise header names — the plugin should still match.
    val response =
      "HTTP/1.1 401 Unauthorized\r\n" +
      "www-authenticate: Basic realm=\"https://lowercase.example/\"\r\n\r\n"
    withHttpServer(response) { uri =>
      new RealmProbe().probe(uri) shouldBe Some("https://lowercase.example/")
    }
  }

  it should "return None when the HEAD request itself fails" in {
    // Point at a port nothing is listening on. The probe catches the IOException
    // and returns None.
    val probe = new RealmProbe()
    val uri = new URI("http://localhost:1/never-routable")
    probe.probe(uri) shouldBe None
  }

  // ─── getToken / getTokenImpl (real implementation, fake TokenCredential) ─

  /** Test subclass that injects a fake TokenCredential and exposes getToken. */
  private class TokenProbe(cred: TokenCredential)
      extends AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
    override protected def newCredential(): TokenCredential = cred
    def fetch(): Option[String] = getToken()
  }

  "getToken" should "return Some(token) when the credential resolves successfully" in {
    new TokenProbe(credentialReturning("ya29.fake-token")).fetch() shouldBe Some("ya29.fake-token")
  }

  it should "return None when the credential resolves to empty (null AccessToken)" in {
    new TokenProbe(fakeCredential(Mono.empty[AccessToken]())).fetch() shouldBe None
  }

  it should "return None when the credential throws" in {
    val boom = fakeCredential(Mono.error[AccessToken](new RuntimeException("simulated az failure")))
    new TokenProbe(boom).fetch() shouldBe None
  }

  it should "cache the result across calls (newCredential invoked at most once)" in {
    var calls = 0
    val probe = new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
      override protected def newCredential(): TokenCredential = {
        calls += 1
        credentialReturning(s"call-$calls")
      }
      def fetch(): Option[String] = getToken()
    }
    probe.fetch() shouldBe Some("call-1")
    probe.fetch() shouldBe Some("call-1") // cached
    probe.fetch() shouldBe Some("call-1") // still cached
    calls shouldBe 1
  }

  it should "cache the None result and not re-try on subsequent calls" in {
    // Companion to the Some-caching test above: `cachedToken` is
    // Option[Option[String]] precisely to distinguish "not yet attempted"
    // from "attempted, no token". Without this assertion, a future refactor
    // to Option[String] semantics (treating None as "not yet attempted")
    // would silently re-trigger the full chain on every resolver in a
    // misconfigured sbt build — exactly what the cache exists to prevent.
    var calls = 0
    val probe = new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
      override protected def newCredential(): TokenCredential = {
        calls += 1
        fakeCredential(Mono.error[AccessToken](new RuntimeException("boom")))
      }
      def fetch(): Option[String] = getToken()
    }
    probe.fetch() shouldBe None
    probe.fetch() shouldBe None
    probe.fetch() shouldBe None
    calls shouldBe 1
  }

  it should "restore the previous Azure-identity log level after token acquisition" in {
    val prop = AzureDevOpsCredentialsPlugin.AzureIdentityLogProperty
    val original = Option(System.getProperty(prop))
    try {
      System.setProperty(prop, "debug")
      new TokenProbe(credentialReturning("x")).fetch()
      System.getProperty(prop) shouldBe "debug"
    } finally {
      original match {
        case Some(v) => System.setProperty(prop, v)
        case None => System.clearProperty(prop)
      }
    }
  }

  it should "clear the Azure-identity log level when none was set before" in {
    val prop = AzureDevOpsCredentialsPlugin.AzureIdentityLogProperty
    val original = Option(System.getProperty(prop))
    System.clearProperty(prop)
    try {
      new TokenProbe(credentialReturning("x")).fetch()
      Option(System.getProperty(prop)) shouldBe None
    } finally {
      original.foreach(System.setProperty(prop, _))
    }
  }

  it should "restore the Azure-identity log level when concurrent CredentialsBuilder instances acquire tokens" in {
    // Regression guard for the save/set/restore race: without a JVM-wide
    // counted-set, two builders could observe each other's "off" mutation and
    // leave the property pinned to "off" after both threads finish.
    //
    // The pre-fix code synchronized only on the CredentialsBuilder instance,
    // so concurrent instances' save/set/restore blocks could interleave.
    // The race window is the time between save (read property as X) and
    // restore (write property back to X). To actually exercise that window,
    // (a) all 8 threads gate on a CountDownLatch so they enter the
    // acquire/SDK/release path within nanoseconds of each other, and
    // (b) the credential's Mono is delayed by 50ms so the per-thread
    // critical section is long enough for the interleaving to happen.
    // (`Mono.just(...)` would resolve in microseconds — shorter than the
    // cost of starting the next Thread — and the threads would typically
    // run sequentially, neutering the regression guard.)
    //
    // This is paired with the deterministic counted-set invariant test
    // below ("keep ... pinned to 'off' across nested suppressions"). This
    // 8-thread variant verifies the live integration path through
    // getTokenImpl; the nested-suppression test verifies the counted-set
    // helpers directly.
    val prop = AzureDevOpsCredentialsPlugin.AzureIdentityLogProperty
    val original = Option(System.getProperty(prop))
    System.setProperty(prop, "initial")
    try {
      val barrier = new CountDownLatch(1)
      val threads = (1 to 8).map { i =>
        val t = new Thread(new Runnable {
          def run(): Unit = {
            val delayedCred = fakeCredential(
              Mono.just(new AccessToken(s"tok-$i", OffsetDateTime.now().plusHours(1)))
                .delayElement(Duration.ofMillis(50)))
            barrier.await()
            new TokenProbe(delayedCred).fetch()
          }
        })
        t.start()
        t
      }
      barrier.countDown()
      threads.foreach(_.join())
      System.getProperty(prop) shouldBe "initial"
    } finally {
      original match {
        case Some(v) => System.setProperty(prop, v)
        case None => System.clearProperty(prop)
      }
    }
  }

  it should "keep the Azure-identity log level pinned to 'off' across nested suppressions and only restore on the outermost release" in {
    // Directly exercises the counted-set helpers to assert the invariant the
    // 8-thread concurrency test relies on: inner acquire/release does NOT
    // restore the property while an outer suppression is still in flight.
    val prop = AzureDevOpsCredentialsPlugin.AzureIdentityLogProperty
    val original = Option(System.getProperty(prop))
    System.setProperty(prop, "initial")
    try {
      AzureDevOpsCredentialsPlugin.acquireAzureIdentityLogSuppression()
      System.getProperty(prop) shouldBe "off"
      AzureDevOpsCredentialsPlugin.acquireAzureIdentityLogSuppression()
      System.getProperty(prop) shouldBe "off"
      AzureDevOpsCredentialsPlugin.releaseAzureIdentityLogSuppression()
      // Inner release: still suppressed because outer is still in flight.
      System.getProperty(prop) shouldBe "off"
      AzureDevOpsCredentialsPlugin.releaseAzureIdentityLogSuppression()
      // Outer release: property restored to the saved-at-first-acquire value.
      System.getProperty(prop) shouldBe "initial"
    } finally {
      original match {
        case Some(v) => System.setProperty(prop, v)
        case None => System.clearProperty(prop)
      }
    }
  }

  it should "throw IllegalArgumentException when release is called without a matching acquire" in {
    // Regression guard for the unbalanced-release silent-corruption mode:
    // without the `require(suppressionCount > 0, ...)` in release, an
    // unmatched release would decrement the counter to -1; the next acquire
    // would see suppressionCount == 0 is false (it's -1), skip the save+set,
    // and just bump to 0. The suppression then becomes a silent no-op for the
    // rest of the JVM run — counter looks healthy from the outside (returns
    // to 0 again on the next acquire/release pair) but the property is never
    // saved or restored. `require` makes the failure mode loud at the actual
    // bad caller instead.
    an[IllegalArgumentException] should be thrownBy {
      AzureDevOpsCredentialsPlugin.releaseAzureIdentityLogSuppression()
    }
    // Counter remains at 0 because `require` throws before any mutation.
    // (Also cross-checked by the per-test BeforeAndAfter `after` hook.)
    AzureDevOpsCredentialsPlugin.suppressionCountForTesting shouldBe 0
  }

  // ─── updateCoursierConf ─────────────────────────────────────────────────

  "updateCoursierConf" should "add authentication for MavenRepos with matching credentials" in {
    val resolver = MavenRepository(
      "AdoFeed", "https://pkgs.dev.azure.com/myorg/_packaging/myfeed/maven/v1")
    val cred = Credentials("realm", "pkgs.dev.azure.com", "user1", "pat1")
    val conf = CoursierConfiguration()
    val result = AzureDevOpsCredentialsPlugin.updateCoursierConf(conf, Seq(resolver), Seq(cred))
    val auth = result.authenticationByRepositoryId.toMap.get("AdoFeed")
    auth shouldBe Some(Authentication("user1", "pat1"))
  }

  it should "leave the conf unchanged when no credentials match" in {
    val resolver = MavenRepository(
      "AdoFeed", "https://pkgs.dev.azure.com/myorg/_packaging/myfeed/maven/v1")
    val cred = Credentials("realm", "some.other.host", "user1", "pat1")
    val conf = CoursierConfiguration()
    val result = AzureDevOpsCredentialsPlugin.updateCoursierConf(conf, Seq(resolver), Seq(cred))
    result.authenticationByRepositoryId shouldBe conf.authenticationByRepositoryId
  }

  it should "ignore non-MavenRepo resolvers" in {
    val resolver: Resolver = Resolver.file("local-ivy", new File("/tmp"))
    val cred = Credentials("realm", "pkgs.dev.azure.com", "user1", "pat1")
    val conf = CoursierConfiguration()
    val result = AzureDevOpsCredentialsPlugin.updateCoursierConf(conf, Seq(resolver), Seq(cred))
    result.authenticationByRepositoryId shouldBe conf.authenticationByRepositoryId
  }

  it should "ignore non-DirectCredentials entries" in {
    // FileCredentials are an example of a non-DirectCredentials Credentials.
    val resolver = MavenRepository(
      "AdoFeed", "https://pkgs.dev.azure.com/myorg/_packaging/myfeed/maven/v1")
    val fileCred: Credentials = Credentials(new File("/nonexistent/cred"))
    val conf = CoursierConfiguration()
    val result = AzureDevOpsCredentialsPlugin.updateCoursierConf(
      conf, Seq(resolver), Seq(fileCred))
    result.authenticationByRepositoryId shouldBe conf.authenticationByRepositoryId
  }

  it should "add authentication for multiple repositories independently" in {
    val r1 = MavenRepository("R1", "https://pkgs.dev.azure.com/orgA/_packaging/feed/maven/v1")
    val r2 = MavenRepository("R2", "https://pkgs.dev.azure.com/orgB/_packaging/feed/maven/v1")
    val c1 = Credentials("realm", "pkgs.dev.azure.com", "userAB", "patAB")
    val conf = CoursierConfiguration()
    val result = AzureDevOpsCredentialsPlugin.updateCoursierConf(conf, Seq(r1, r2), Seq(c1))
    val byId = result.authenticationByRepositoryId.toMap
    byId.get("R1") shouldBe Some(Authentication("userAB", "patAB"))
    byId.get("R2") shouldBe Some(Authentication("userAB", "patAB"))
  }

  // ─── AutoPlugin metadata ────────────────────────────────────────────────

  "AzureDevOpsCredentialsPlugin" should "trigger on all requirements" in {
    // sbt.plugins.JvmPlugin uses allRequirements too; compare against that.
    AzureDevOpsCredentialsPlugin.trigger shouldBe sbt.plugins.JvmPlugin.trigger
  }

  // ─── Default hook implementations (no overrides) ────────────────────────

  "CredentialsBuilder defaults" should "expose a default Maven settings file at ~/.m2/settings.xml" in {
    val probe = new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
      def file: File = mavenSettingsFile
    }
    probe.file.getPath should endWith (s"${File.separator}.m2${File.separator}settings.xml")
  }

  it should "create a non-null TokenCredential chain by default" in {
    val probe = new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
      def cred: TokenCredential = newCredential()
    }
    probe.cred should not be null
  }

  // ─── headRequestHeaders edge cases ─────────────────────────────────────

  // Reuses the server helper at file-level scope. We send a response where one
  // line is malformed (no colon) — the parser must skip it and keep going.
  private def withRawHeadServer(rawResponse: String)(test: (String, Int) => Unit): Unit = {
    val server = new ServerSocket(0)
    try {
      val port = server.getLocalPort
      val t = new Thread(() => {
        try {
          val client = server.accept()
          try {
            val buf = new Array[Byte](2048)
            client.getInputStream.read(buf)
            val w = new BufferedWriter(new OutputStreamWriter(client.getOutputStream, "UTF-8"))
            w.write(rawResponse)
            w.flush()
          } finally client.close()
        } catch { case NonFatal(_) => () }
      })
      t.setDaemon(true)
      t.start()
      test("localhost", port)
      t.join(5000)
    } finally server.close()
  }

  "headRequestHeaders" should "skip header lines that don't contain a colon" in {
    val response =
      "HTTP/1.1 401 Unauthorized\r\n" +
      "this-line-has-no-colon\r\n" +
      "Good-Header: ok\r\n\r\n"
    withRawHeadServer(response) { (host, port) =>
      val uri = new URI(s"http://$host:$port/")
      val headers = AzureDevOpsCredentialsPlugin.headRequestHeaders(uri)
      headers should contain ("Good-Header" -> "ok")
      headers.map(_._1) should not contain "this-line-has-no-colon"
    }
  }

  it should "wrap the socket with SSLSocketFactory when the scheme is https" in {
    // Point an https URI at a plain-HTTP test server. The TLS handshake fails
    // because the server immediately writes "HTTP/1.1 200 OK\r\n\r\n" — not a
    // valid TLS record. Assert specifically on SSLException (rather than the
    // broader `Exception`) so a future refactor that drops the SSLSocketFactory
    // wrap entirely would surface as the test catching a non-SSL exception
    // (e.g. parse failure on the plain HTTP response) instead of silently
    // passing on whatever generic throwable happened.
    withRawHeadServer("HTTP/1.1 200 OK\r\n\r\n") { (host, port) =>
      val uri = new URI(s"https://$host:$port/")
      an[SSLException] should be thrownBy AzureDevOpsCredentialsPlugin.headRequestHeaders(uri)
    }
  }

  // ─── configureSslEndpointIdentification (TLS hostname verification) ────

  "configureSslEndpointIdentification" should
      "set the SSL endpoint identification algorithm to HTTPS" in {
    // The JDK default for raw SSLSocket leaves endpointIdentificationAlgorithm
    // unset (null) → cert-chain validation happens but the server's CN/SAN is
    // NEVER checked against the URI host. With v0.0.10's authenticated probes
    // putting PATs and Bearer tokens on the wire, that gap becomes a credential-
    // leak vector against misissued CAs (DigiNotar-class) + DNS hijack. This
    // helper opts in to "HTTPS" — the canonical JDK algorithm name — so the
    // TLS handshake fails when the cert hostname doesn't match. Asserting the
    // PARAMETER is set (rather than spinning up a self-signed test server) is
    // sufficient: the JDK enforces the algorithm once we ask for it, and the
    // production code calls this helper on every TLS socket headRequest opens.
    val factory = javax.net.ssl.SSLSocketFactory.getDefault
      .asInstanceOf[javax.net.ssl.SSLSocketFactory]
    // createSocket() with no args returns an unconnected SSLSocket — sufficient
    // for parameter inspection without any network or cert plumbing.
    val socket = factory.createSocket().asInstanceOf[javax.net.ssl.SSLSocket]
    try {
      // Pre-condition: the JDK default really is null/unset.
      // (Sanity-check assertion: if a future JDK changes this default to "HTTPS"
      // out of the box, this test fires and the maintainer can decide whether
      // the helper is still load-bearing or has become a tautology.)
      val defaultAlgo = socket.getSSLParameters.getEndpointIdentificationAlgorithm
      assert(defaultAlgo == null || defaultAlgo.isEmpty,
        s"Expected JDK default endpointIdentificationAlgorithm to be null/empty, got '$defaultAlgo'")
      val configured = AzureDevOpsCredentialsPlugin.configureSslEndpointIdentification(socket)
      configured.getSSLParameters.getEndpointIdentificationAlgorithm shouldBe "HTTPS"
      // The helper returns the same socket (no new instance).
      (configured eq socket) shouldBe true
    } finally socket.close()
  }

  it should "fail the TLS handshake when the cert hostname doesn't match the URI host (end-to-end)" in {
    // Real-network smoke test: connect to a public ADO endpoint via an
    // explicitly-wrong hostname. The JDK's HTTPS algorithm should detect the
    // mismatch during the handshake and throw SSLHandshakeException BEFORE
    // any HTTP bytes (including the Authorization header) reach the wire.
    //
    // We pick `wrong.host.badssl.com` — a public test endpoint maintained
    // by badssl.com that serves a valid cert for `*.badssl.com` (NOT for
    // `wrong.host.badssl.com`), which is the canonical "hostname mismatch"
    // scenario for SSL test infrastructure. If this becomes flaky (badssl
    // could go down), demote to @Slow and rely on the
    // configureSslEndpointIdentification unit test above to lock in behavior.
    val uri = new URI("https://wrong.host.badssl.com/")
    try {
      AzureDevOpsCredentialsPlugin.headRequestHeaders(uri)
      fail("Expected SSLHandshakeException on hostname mismatch, got success")
    } catch {
      case _: javax.net.ssl.SSLHandshakeException => succeed
      case e: SSLException if Option(e.getMessage).exists(_.toLowerCase.contains("host")) =>
        succeed // some JDK variants surface as plain SSLException with a hostname-mismatch message
      case e: java.io.IOException =>
        // Network unreachable / DNS resolution failure — don't fail the test
        // on infrastructure issues. The unit test above still covers behavior.
        cancel(s"Skipping end-to-end SSL hostname check (network/DNS unreachable): $e")
    }
  }

  // ─── defaultPort (pure helper — no socket) ──────────────────────────────

  "defaultPort" should "return the explicit port when set" in {
    AzureDevOpsCredentialsPlugin.defaultPort(new URI("http://example.com:8080/")) shouldBe 8080
    AzureDevOpsCredentialsPlugin.defaultPort(new URI("https://example.com:8443/")) shouldBe 8443
  }

  it should "default to 80 for http URIs with no explicit port" in {
    AzureDevOpsCredentialsPlugin.defaultPort(new URI("http://example.com/")) shouldBe 80
  }

  it should "default to 443 for https URIs with no explicit port" in {
    AzureDevOpsCredentialsPlugin.defaultPort(new URI("https://example.com/")) shouldBe 443
  }

  it should "fall through to 80 for non-http(s) schemes (gate-coverage tail, not a canonical default)" in {
    // Pins the `else 80` branch in defaultPort. No real call site passes a
    // non-http(s) URI here — headRequestHeaders is fed MavenRepo URIs that
    // isAzureDevOpsHost has already filtered to http/https — but the
    // 100% coverage gate requires the else branch be exercised. Using
    // ftp:// (canonical port 21) intentionally: the assertion `shouldBe 80`
    // (NOT `shouldBe 21`) documents that this is a fallback, not a
    // scheme-aware lookup.
    AzureDevOpsCredentialsPlugin.defaultPort(new URI("ftp://example.com/")) shouldBe 80
  }

  // ─── basicAuthHeader (pure helper) ─────────────────────────────────────

  "basicAuthHeader" should "produce Basic <base64(user:pass)>" in {
    // Aladdin:OpenSesame example from RFC 7617 §2.
    AzureDevOpsCredentialsPlugin.basicAuthHeader(
      "Aladdin", "OpenSesame") shouldBe "Basic QWxhZGRpbjpPcGVuU2VzYW1l"
  }

  it should "handle empty username and password without throwing" in {
    AzureDevOpsCredentialsPlugin.basicAuthHeader("", "") shouldBe "Basic Og=="
  }

  // ─── validateExistingCredentialsMode (pure helper) ─────────────────────

  /** Saves the validation-mode property, runs `body`, restores. Pattern
    * matches the existing AzureIdentityLogProperty save/restore in this file. */
  private def withValidationMode[A](value: Option[String])(body: => A): A = {
    val prop = AzureDevOpsCredentialsPlugin.ValidateExistingCredentialsProperty
    val original = Option(System.getProperty(prop))
    value match {
      case Some(v) => System.setProperty(prop, v)
      case None => System.clearProperty(prop)
    }
    try body finally {
      original match {
        case Some(v) => System.setProperty(prop, v)
        case None => System.clearProperty(prop)
      }
    }
  }

  "validateExistingCredentialsMode" should "default to 'auto' when property is unset" in {
    withValidationMode(None) {
      AzureDevOpsCredentialsPlugin.validateExistingCredentialsMode() shouldBe
        AzureDevOpsCredentialsPlugin.ValidateAuto
    }
  }

  it should "return 'always' when property is 'always'" in {
    withValidationMode(Some("always")) {
      AzureDevOpsCredentialsPlugin.validateExistingCredentialsMode() shouldBe
        AzureDevOpsCredentialsPlugin.ValidateAlways
    }
  }

  it should "return 'never' when property is 'never'" in {
    withValidationMode(Some("never")) {
      AzureDevOpsCredentialsPlugin.validateExistingCredentialsMode() shouldBe
        AzureDevOpsCredentialsPlugin.ValidateNever
    }
  }

  it should "fall back to 'auto' for unknown values" in {
    withValidationMode(Some("garbage")) {
      AzureDevOpsCredentialsPlugin.validateExistingCredentialsMode() shouldBe
        AzureDevOpsCredentialsPlugin.ValidateAuto
    }
  }

  it should "be case-insensitive" in {
    withValidationMode(Some("ALWAYS")) {
      AzureDevOpsCredentialsPlugin.validateExistingCredentialsMode() shouldBe
        AzureDevOpsCredentialsPlugin.ValidateAlways
    }
    withValidationMode(Some("Never")) {
      AzureDevOpsCredentialsPlugin.validateExistingCredentialsMode() shouldBe
        AzureDevOpsCredentialsPlugin.ValidateNever
    }
  }

  // ─── headRequest (status code parsing — new in 0.0.10) ─────────────────

  "headRequest" should "parse the status code from the response line" in {
    val response =
      "HTTP/1.1 401 Unauthorized\r\n" +
      "WWW-Authenticate: Basic realm=\"r\"\r\n\r\n"
    withRawHeadServer(response) { (host, port) =>
      val uri = new URI(s"http://$host:$port/")
      val (status, headers) = AzureDevOpsCredentialsPlugin.headRequest(uri, None)
      status shouldBe Some(401)
      headers should contain ("WWW-Authenticate" -> "Basic realm=\"r\"")
    }
  }

  it should "return Some(200) on a successful response" in {
    withRawHeadServer("HTTP/1.1 200 OK\r\n\r\n") { (host, port) =>
      val uri = new URI(s"http://$host:$port/")
      val (status, _) = AzureDevOpsCredentialsPlugin.headRequest(uri, None)
      status shouldBe Some(200)
    }
  }

  it should "throw IllegalArgumentException when authHeader is set but scheme is not https (defense-in-depth)" in {
    // The cleartext-credential guard: future callers that try to send an
    // Authorization header over a non-https URI must hard-fail, not silently
    // leak credentials over plain TCP. The production probe path
    // short-circuits this case earlier via isStaleSettingsEntry's
    // scheme-check, but the guard here is the second gate — so a future
    // refactor that introduces a new caller (or removes the entry-point
    // guard) cannot regress to writing Authorization in cleartext without
    // tripping this require().
    val uri = new URI("http://example.com/")
    val ex = intercept[IllegalArgumentException] {
      AzureDevOpsCredentialsPlugin.headRequest(uri, Some("Basic abcdef"))
    }
    ex.getMessage should include ("https")
  }

  it should "allow Authorization header when scheme is https (require does not fire)" in {
    // Verifies the require's positive branch: https URI + auth is allowed
    // through (whether the actual TLS handshake succeeds is irrelevant —
    // we only care that the require itself does not trip). The connect
    // is expected to fail (host:port unreachable for the bogus URI), which
    // is a non-IllegalArgumentException — exactly the contract we want.
    val uri = new URI("https://localhost:1/")
    intercept[Exception] {
      AzureDevOpsCredentialsPlugin.headRequest(uri, Some("Basic abcdef"))
    } shouldBe a [java.io.IOException]
  }

  "buildHeadRequestLine" should "produce a HEAD line with Host and the Authorization header when provided" in {
    val req = AzureDevOpsCredentialsPlugin.buildHeadRequestLine(
      host = "pkgs.dev.azure.com",
      path = "/myorg/_packaging/myfeed/maven/v1",
      authHeader = Some("Basic QWxhZGRpbjpPcGVuU2VzYW1l"))
    req shouldBe
      "HEAD /myorg/_packaging/myfeed/maven/v1 HTTP/1.1\r\n" +
      "Host: pkgs.dev.azure.com\r\n" +
      "Authorization: Basic QWxhZGRpbjpPcGVuU2VzYW1l\r\n" +
      "Connection: close\r\n\r\n"
  }

  it should "produce a HEAD line WITHOUT the Authorization header when None" in {
    val req = AzureDevOpsCredentialsPlugin.buildHeadRequestLine(
      host = "pkgs.dev.azure.com",
      path = "/",
      authHeader = None)
    req shouldBe
      "HEAD / HTTP/1.1\r\n" +
      "Host: pkgs.dev.azure.com\r\n" +
      "Connection: close\r\n\r\n"
  }

  it should "produce a HEAD line with the Bearer token when provided" in {
    val req = AzureDevOpsCredentialsPlugin.buildHeadRequestLine(
      host = "pkgs.dev.azure.com",
      path = "/myorg/_packaging/myfeed/maven/v1",
      authHeader = Some("Bearer eyJhbGciOiJSUzI1NiJ9.fake.token"))
    req should include ("Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.fake.token\r\n")
    req should startWith ("HEAD /myorg/_packaging/myfeed/maven/v1 HTTP/1.1\r\n")
    req should endWith ("Connection: close\r\n\r\n")
  }

  it should "return None as status when the status line is malformed" in {
    withRawHeadServer("MALFORMED\r\n\r\n") { (host, port) =>
      val uri = new URI(s"http://$host:$port/")
      val (status, _) = AzureDevOpsCredentialsPlugin.headRequest(uri, None)
      status shouldBe None
    }
  }

  it should "return None as status when the status line has no parseable number" in {
    withRawHeadServer("HTTP/1.1 NOT-A-NUMBER\r\n\r\n") { (host, port) =>
      val uri = new URI(s"http://$host:$port/")
      val (status, _) = AzureDevOpsCredentialsPlugin.headRequest(uri, None)
      status shouldBe None
    }
  }

  // ─── isStaleSettingsEntry (the probe decision matrix) ──────────────────

  /** A CredentialsBuilder that lets each test pin individual seams.
    *
    * - `probeStatus` controls what `headRequest` "returns" — we override
    *   `isStaleSettingsEntry` wholesale and let `probeStatus` drive the
    *   simulated probe outcome, so no real network call happens.
    * - `tokenAvailable` controls whether `getToken()` returns Some/None,
    *   exercising the auto-mode fork. */
  private class ProbeBuilder(
      probeStatus: Option[Int],
      tokenAvailable: Boolean = true
  ) extends AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
    override protected def getToken(): Option[String] =
      if (tokenAvailable) Some("entra-token") else None
    // Stub the network call: override isStaleSettingsEntry wholesale so the
    // simulated probeStatus drives the decision, no actual headRequest fires.
    override private[chungmin] def isStaleSettingsEntry(
        uri: URI, host: String, user: String, password: String): Boolean = {
      // Re-implement the decision tree inline rather than calling super, so
      // `probeStatus` drives the simulated probe outcome without any real
      // headRequest / network round-trip. Mirrors enough of the production
      // short-circuit logic (Never/host gates) for the tests below to
      // exercise both the gate-level short-circuits (never mode, non-ADO
      // host, null host) and the inner decision tree (Basic-probe 200/401,
      // auto+token availability, network error) via the return value alone.
      val mode = AzureDevOpsCredentialsPlugin.validateExistingCredentialsMode()
      if (mode == AzureDevOpsCredentialsPlugin.ValidateNever) return false
      if (host == null || !AzureDevOpsCredentialsPlugin.isAzureDevOpsHost(host)) return false
      probeStatus match {
        case Some(401) =>
          if (mode == AzureDevOpsCredentialsPlugin.ValidateAlways) true
          else getToken().isDefined
        case _ => false
      }
    }
  }

  "isStaleSettingsEntry" should "trust unconditionally in 'never' mode" in {
    withValidationMode(Some("never")) {
      val b = new ProbeBuilder(Some(401))
      b.isStaleSettingsEntry(
        new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1"),
        "pkgs.dev.azure.com", "u", "p") shouldBe false
    }
  }

  it should "trust unconditionally for non-ADO hosts" in {
    withValidationMode(Some("always")) {
      val b = new ProbeBuilder(Some(401))
      b.isStaleSettingsEntry(
        new URI("https://repo1.maven.org/maven2/"),
        "repo1.maven.org", "u", "p") shouldBe false
    }
  }

  it should "trust unconditionally when host is null" in {
    withValidationMode(Some("always")) {
      val b = new ProbeBuilder(Some(401))
      b.isStaleSettingsEntry(
        new URI("file:///somewhere"), null, "u", "p") shouldBe false
    }
  }

  it should "drop on 401 in 'always' mode" in {
    withValidationMode(Some("always")) {
      val b = new ProbeBuilder(Some(401))
      b.isStaleSettingsEntry(
        new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1"),
        "pkgs.dev.azure.com", "u", "p") shouldBe true
    }
  }

  it should "drop on 401 in 'auto' mode when Entra is reachable" in {
    withValidationMode(Some("auto")) {
      val b = new ProbeBuilder(Some(401), tokenAvailable = true)
      b.isStaleSettingsEntry(
        new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1"),
        "pkgs.dev.azure.com", "u", "p") shouldBe true
    }
  }

  it should "keep on 401 in 'auto' mode when Entra is unreachable" in {
    withValidationMode(Some("auto")) {
      val b = new ProbeBuilder(Some(401), tokenAvailable = false)
      b.isStaleSettingsEntry(
        new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1"),
        "pkgs.dev.azure.com", "u", "p") shouldBe false
    }
  }

  it should "trust on non-401 (200, 404, etc.)" in {
    withValidationMode(Some("always")) {
      val b200 = new ProbeBuilder(Some(200))
      b200.isStaleSettingsEntry(
        new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1"),
        "pkgs.dev.azure.com", "u", "p") shouldBe false
      val b404 = new ProbeBuilder(Some(404))
      b404.isStaleSettingsEntry(
        new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1"),
        "pkgs.dev.azure.com", "u", "p") shouldBe false
    }
  }

  it should "trust on network error (status = None)" in {
    withValidationMode(Some("always")) {
      val b = new ProbeBuilder(None)
      b.isStaleSettingsEntry(
        new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1"),
        "pkgs.dev.azure.com", "u", "p") shouldBe false
    }
  }

  // ─── buildCredentialsFromMavenSettings probe wiring ────────────────────

  // These tests exercise the wiring that consults isStaleSettingsEntry from
  // inside buildCredentialsFromMavenSettings — verifying that a stale verdict
  // drops the entry and a fresh verdict keeps it. The `stale` callback here
  // (passed into testBuilder) stubs isStaleSettingsEntry wholesale; the real
  // probeAndDecideImpl decision tree has its own dedicated tests below.
  "buildCredentialsFromMavenSettings probe" should
      "drop the settings entry when stale function returns true" in {
    // Use a resolver pointed at a real-but-ADO-shaped host so the wiring
    // accepts it; the override below skips the network. The key is exercising
    // buildCredentialsFromMavenSettings' new probe branch (which calls
    // isStaleSettingsEntry unconditionally — mode handling lives inside that
    // method, so this test does not need to pin a specific validation mode).
    val settings = writeSettings(
      """<?xml version="1.0"?>
        |<settings><servers><server>
        |  <id>AzureDevOps</id><username>u</username><password>bad-pat</password>
        |</server></servers></settings>""".stripMargin)
    val resolver = MavenRepository("AzureDevOps", AdoFeedUrl)
    val builder = testBuilder(
      settings = settings,
      stale = (_, _, _, _) => true)
    val result = builder.buildCredentials(Seq.empty, Seq(resolver))
    // The settings entry was dropped, and buildCredentialsWithAccessToken
    // generated an Entra cred for the host (with userName from URL parsing).
    val users = result.collect { case c: DirectCredentials => c.userName }
    users shouldBe Seq("myorg")
  }

  it should "keep the settings entry when stale function returns false" in {
    val settings = writeSettings(
      """<?xml version="1.0"?>
        |<settings><servers><server>
        |  <id>AzureDevOps</id><username>preserved</username><password>p</password>
        |</server></servers></settings>""".stripMargin)
    val resolver = MavenRepository("AzureDevOps", AdoFeedUrl)
    val builder = testBuilder(settings = settings)  // stale defaults to false
    val result = builder.buildCredentials(Seq.empty, Seq(resolver))
    val users = result.collect { case c: DirectCredentials => c.userName }
    users shouldBe Seq("preserved")
  }

  // ─── probe cache (per-builder, URI-keyed) ──────────────────────────────

  "isStaleSettingsEntry cache" should "actually cache in the real implementation (one probeAndDecideImpl call per URI)" in {
    withValidationMode(Some("always")) {
      var calls = 0
      val builder = new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
        override protected[chungmin] def probeAndDecideImpl(
            uri: URI, host: String, user: String, password: String, mode: String): Boolean = {
          calls += 1
          true
        }
      }
      val uri = new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1")
      builder.isStaleSettingsEntry(uri, "pkgs.dev.azure.com", "u", "p") shouldBe true
      builder.isStaleSettingsEntry(uri, "pkgs.dev.azure.com", "u", "p") shouldBe true
      builder.isStaleSettingsEntry(uri, "pkgs.dev.azure.com", "u", "p") shouldBe true
      calls shouldBe 1
    }
  }

  // ─── Real isStaleSettingsEntry mode + host filtering ───────────────────

  // ProbeBuilder above stubs out the entire isStaleSettingsEntry method, so
  // it doesn't exercise the production decision tree's mode/host gates. These
  // tests use the production CredentialsBuilder (only stubbing probeAndDecideImpl)
  // so the mode == never / host == null / host non-ADO branches are covered.

  "real isStaleSettingsEntry" should "short-circuit to false in 'never' mode without probing" in {
    withValidationMode(Some("never")) {
      var calls = 0
      val builder = new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
        override protected[chungmin] def probeAndDecideImpl(
            uri: URI, host: String, user: String, password: String, mode: String): Boolean = {
          calls += 1
          true
        }
      }
      builder.isStaleSettingsEntry(
        new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1"),
        "pkgs.dev.azure.com", "u", "p") shouldBe false
      calls shouldBe 0
    }
  }

  it should "short-circuit to false when host is null without probing" in {
    withValidationMode(Some("always")) {
      var calls = 0
      val builder = new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
        override protected[chungmin] def probeAndDecideImpl(
            uri: URI, host: String, user: String, password: String, mode: String): Boolean = {
          calls += 1
          true
        }
      }
      builder.isStaleSettingsEntry(
        new URI("file:///somewhere"), null, "u", "p") shouldBe false
      calls shouldBe 0
    }
  }

  it should "short-circuit to false for non-ADO hosts without probing" in {
    withValidationMode(Some("always")) {
      var calls = 0
      val builder = new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
        override protected[chungmin] def probeAndDecideImpl(
            uri: URI, host: String, user: String, password: String, mode: String): Boolean = {
          calls += 1
          true
        }
      }
      builder.isStaleSettingsEntry(
        new URI("https://repo1.maven.org/maven2/"),
        "repo1.maven.org", "u", "p") shouldBe false
      calls shouldBe 0
    }
  }

  it should "short-circuit to false for http:// URIs without probing (no cleartext credentials)" in {
    // Even when the host passes isAzureDevOpsHost (user typed the right
    // ADO hostname), an http:// scheme would cause headRequest to use a plain
    // TCP socket and put the Authorization header on the wire in cleartext.
    // Same vulnerability class as a missing TLS endpoint-ID check
    // (credentials leaking via insecure transport) but a distinct codepath:
    // TLS endpoint-ID verification only protects you GIVEN TLS, not the case
    // of no TLS at all. Trust-the-entry (return false) is the right default
    // for the misconfigured-resolver case: preserves legacy v0.0.9 behavior
    // and guarantees no credentials reach a cleartext wire from the probe path.
    withValidationMode(Some("always")) {
      var calls = 0
      val builder = new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
        override protected[chungmin] def probeAndDecideImpl(
            uri: URI, host: String, user: String, password: String, mode: String): Boolean = {
          calls += 1
          true
        }
      }
      builder.isStaleSettingsEntry(
        new URI("http://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1"),
        "pkgs.dev.azure.com", "u", "p") shouldBe false
      calls shouldBe 0
    }
  }

  // ─── probeAndDecideImpl decision-tree tests ────────────────────────────

  // These exercise probeAndDecideImpl's branching logic directly, using
  // stubbed probeWithBasic / probeWithBearer values. The headRequest
  // wire-format and TLS-endpoint-ID invariants are verified by their
  // dedicated tests above.

  "probeAndDecideImpl" should "return false (trust) when network probe returns 200" in {
    val builder = stubProbeBuilder(
      basicStatus = Some(200), bearerStatus = None, tokenValue = "n/a")
    val uri = new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1")
    builder.probeAndDecideImpl(
      uri, "pkgs.dev.azure.com", "u", "p",
      AzureDevOpsCredentialsPlugin.ValidateAlways) shouldBe false
  }

  it should "return true (drop) when network probe returns 401 in 'always' mode" in {
    val builder = stubProbeBuilder(
      basicStatus = Some(401), bearerStatus = None, tokenValue = "n/a")
    val uri = new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1")
    builder.probeAndDecideImpl(
      uri, "pkgs.dev.azure.com", "u", "p",
      AzureDevOpsCredentialsPlugin.ValidateAlways) shouldBe true
  }

  it should "return false (keep) when probe is 401 + auto + Entra unreachable" in {
    val builder = new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
      override protected def newCredential(): TokenCredential =
        fakeCredential(Mono.error[AccessToken](new RuntimeException("no az")))
      override protected[chungmin] def probeWithBasic(
          uri: URI, host: String, user: String, password: String): Option[Int] = Some(401)
      // probeWithBearer is unreachable here (getToken returns None first); leave default
    }
    val uri = new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1")
    builder.probeAndDecideImpl(
      uri, "pkgs.dev.azure.com", "u", "p",
      AzureDevOpsCredentialsPlugin.ValidateAuto) shouldBe false
  }

  it should "return false (trust) when probe throws a network error" in {
    val builder = stubProbeBuilder(
      basicStatus = None, bearerStatus = None, tokenValue = "n/a")
    val uri = new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1")
    builder.probeAndDecideImpl(
      uri, "pkgs.dev.azure.com", "u", "p",
      AzureDevOpsCredentialsPlugin.ValidateAlways) shouldBe false
  }

  // ─── auto-mode Bearer verification: re-probe with the new Entra token ─────

  /** Test helper: build a CredentialsBuilder whose probeWithBasic /
    * probeWithBearer return canned `Option[Int]` values, and whose
    * newCredential returns a fixed token string. Lets the
    * `probeAndDecideImpl` decision-tree tests — both the simple-decision
    * cases (Basic-probe only: 200, 401-always, network error) and the
    * auto-mode Bearer-verify cases (Basic 401 → re-probe with Entra
    * token: Bearer 200 / Bearer 401 / Bearer network error) — assert the
    * decision logic directly against specific (Basic-probe, Bearer-probe)
    * outcome combinations, without driving any actual network round-trip.
    * `headRequest` now refuses to send Authorization headers over http://
    * URIs, so the previous `withSequentialServers + http://localhost:$port/`
    * pattern can no longer hit the probe path; stubbing the probe helpers
    * (the boundary just above `headRequest`) keeps these tests focused on
    * the decision logic. The `headRequest` wire-format and TLS-endpoint-ID
    * invariants have their own dedicated tests above. Not used by the
    * `CredentialsBuilder(log, mode)` constructor tests — those exercise
    * the mode-injection path, not the probe decision tree. */
  private def stubProbeBuilder(
      basicStatus: Option[Int],
      bearerStatus: Option[Int],
      tokenValue: String
  ): AzureDevOpsCredentialsPlugin.CredentialsBuilder =
    new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
      override protected def newCredential(): TokenCredential = credentialReturning(tokenValue)
      override protected[chungmin] def probeWithBasic(
          uri: URI, host: String, user: String, password: String): Option[Int] = basicStatus
      override protected[chungmin] def probeWithBearer(
          uri: URI, host: String, token: String): Option[Int] = bearerStatus
    }

  "probeAndDecideImpl (auto, Entra works, Bearer probe succeeds)" should
      "return true (drop entry) when the verification probe doesn't get 401" in {
    val builder = stubProbeBuilder(
      basicStatus = Some(401),
      bearerStatus = Some(200),
      tokenValue = "ok-token")
    val uri = new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1")
    builder.probeAndDecideImpl(
      uri, "pkgs.dev.azure.com", "u", "p",
      AzureDevOpsCredentialsPlugin.ValidateAuto) shouldBe true
  }

  "probeAndDecideImpl (auto, Entra works, Bearer probe ALSO 401)" should
      "return false (keep entry) — the Entra identity has no feed access" in {
    // The Entra-identity-lacks-feed-access scenario: stale PAT → 401 on Basic probe; getToken() succeeds
    // via (say) Managed Identity on an Azure VM; but the MI has no access to
    // the user's feed, so Bearer-probe with the new token ALSO returns 401.
    // Overriding the user's PAT with that token would leave the build still
    // failing, with a misleading "fell through to Entra" log line. The fix:
    // verify the new token's access first, fall back to keeping the stale
    // entry with a clearer diagnostic.
    val builder = stubProbeBuilder(
      basicStatus = Some(401),
      bearerStatus = Some(401),
      tokenValue = "no-access-token")
    val uri = new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1")
    builder.probeAndDecideImpl(
      uri, "pkgs.dev.azure.com", "u", "p",
      AzureDevOpsCredentialsPlugin.ValidateAuto) shouldBe false
  }

  "probeAndDecideImpl (auto, Bearer verify network error)" should
      "assume token works (return true, drop entry) — be optimistic on transient blip" in {
    // Basic probe answered with 401 (stale); Bearer-verify fails with a
    // network error (probeWithBearer catches the IOException and returns
    // None). probeAndDecideImpl treats None as "not 401" → take the
    // optimistic success branch. Deliberate UX choice: a transient blip
    // on the verify probe shouldn't re-strand the user with their already-
    // stale PAT.
    val builder = stubProbeBuilder(
      basicStatus = Some(401),
      bearerStatus = None,
      tokenValue = "token")
    val uri = new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1")
    builder.probeAndDecideImpl(
      uri, "pkgs.dev.azure.com", "u", "p",
      AzureDevOpsCredentialsPlugin.ValidateAuto) shouldBe true
  }

  // ─── CredentialsBuilder(log, mode) constructor: per-project mode injection ──

  "CredentialsBuilder(log, mode)" should
      "use the passed-in mode regardless of -D system property (per-project scoping)" in {
    // Pin system property to "never"; build with mode="always". The probe
    // must fire (per the always mode) regardless of the property.
    withValidationMode(Some("never")) {
      val settings = writeSettings(
        """<?xml version="1.0"?>
          |<settings><servers><server>
          |  <id>R</id><username>u</username><password>p</password>
          |</server></servers></settings>""".stripMargin)
      val resolver = MavenRepository("R", AdoFeedUrl)
      val builder = new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog, "always") {
        override protected def mavenSettingsFile: File = settings
        override protected def getRealm(uri: URI): Option[String] = Some(AdoRealm)
        override protected def getToken(): Option[String] = Some("t")
        override protected[chungmin] def probeAndDecideImpl(
            uri: URI, host: String, user: String, password: String, m: String): Boolean = {
          m shouldBe AzureDevOpsCredentialsPlugin.ValidateAlways
          true
        }
      }
      val result = builder.buildCredentials(Seq.empty, Seq(resolver))
      val users = result.collect { case c: DirectCredentials => c.userName }
      users shouldBe Seq("myorg")  // Entra-generated, not "u" from settings
    }
  }

  it should "fall back to the legacy single-arg overload reading the system property" in {
    withValidationMode(Some("never")) {
      val builder = new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
        override protected[chungmin] def probeAndDecideImpl(
            uri: URI, host: String, user: String, password: String, m: String): Boolean = {
          fail(s"probeAndDecideImpl should NOT be called in 'never' mode; got $m")
        }
      }
      builder.isStaleSettingsEntry(
        new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1"),
        "pkgs.dev.azure.com", "u", "p") shouldBe false
    }
  }

  it should "normalize mode case-insensitively when passed in directly (regression guard)" in {
    // Without case-insensitive normalization, `CredentialsBuilder(log, "NEVER")` would compare
    // raw "NEVER" against the lowercase ValidateNever constant, fail equality,
    // and silently fall into the auto branch — defeating users who set
    // `-Ddev.chungmin.azure.validateExistingCredentials=NEVER` (a common shell
    // habit) or `azureDevOpsValidateExistingCredentials := "Never"` in build.sbt.
    withValidationMode(None) {
      // Use "NEVER" upper-case via the 2-arg ctor; assert it short-circuits
      // (never-mode skips probing entirely, so probeAndDecideImpl would not
      // fire — which the override below FAILs the test if it does).
      val builder = new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog, "NEVER") {
        override protected[chungmin] def probeAndDecideImpl(
            uri: URI, host: String, user: String, password: String, m: String): Boolean = {
          fail(s"raw 'NEVER' must be normalized to ValidateNever and short-circuit; got $m")
        }
      }
      builder.isStaleSettingsEntry(
        new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1"),
        "pkgs.dev.azure.com", "u", "p") shouldBe false
    }
  }

  it should "normalize unknown mode values to auto" in {
    // Same regression family: a typo like `"alway"` should fall through to
    // auto, not crash and not pin some other unintended branch.
    val builder = new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog, "alway") {
      override protected[chungmin] def probeAndDecideImpl(
          uri: URI, host: String, user: String, password: String, m: String): Boolean = {
        m shouldBe AzureDevOpsCredentialsPlugin.ValidateAuto
        false
      }
    }
    builder.isStaleSettingsEntry(
      new URI("https://pkgs.dev.azure.com/o/p/_packaging/f/maven/v1"),
      "pkgs.dev.azure.com", "u", "p") shouldBe false
  }

  // ─── probe cache key: per-feed-URI (host-keyed would collide across feeds) ──

  "isStaleSettingsEntry cache" should
      "probe distinct feeds on the same host INDEPENDENTLY (not host-keyed)" in {
    // Bug class: keying the cache by host alone would let two ADO feeds on the same
    // host (e.g. `<org>.pkgs.visualstudio.com/A365/_packaging/FeedA/...` vs
    // `<org>.pkgs.visualstudio.com/A365/_packaging/FeedB/...`, or two orgs on
    // `pkgs.dev.azure.com`) would inherit each other's verdicts — silently
    // trusting a stale entry for FeedB if FeedA's PAT was valid (defeats the
    // PR), or silently dropping a valid entry for FeedA if FeedB's PAT was
    // stale (defeats user intent). Fix keyed cache by URI string.
    var probesByUri = Map.empty[String, Int]
    val builder = new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog, "always") {
      override protected[chungmin] def probeAndDecideImpl(
          uri: URI, host: String, user: String, password: String, m: String): Boolean = {
        probesByUri = probesByUri.updated(
          uri.toString, probesByUri.getOrElse(uri.toString, 0) + 1)
        // Return different verdicts for different URIs to make the bug
        // observable: if cache were host-keyed, the second call would see
        // the cached verdict from the first.
        if (uri.toString.endsWith("/FeedA/maven/v1")) true else false
      }
    }
    val feedA = new URI("https://pkgs.dev.azure.com/myorg/myproject/_packaging/FeedA/maven/v1")
    val feedB = new URI("https://pkgs.dev.azure.com/myorg/myproject/_packaging/FeedB/maven/v1")
    val host = "pkgs.dev.azure.com"
    builder.isStaleSettingsEntry(feedA, host, "u", "p") shouldBe true
    builder.isStaleSettingsEntry(feedB, host, "u", "p") shouldBe false
    // Both URIs must have been probed (one each), not collapsed to one probe
    // on the shared host.
    probesByUri.values.toSeq.sorted shouldBe Seq(1, 1)
    probesByUri.size shouldBe 2
    // Re-probe each URI: cache MUST hit on second call (still 1 probe per URI).
    builder.isStaleSettingsEntry(feedA, host, "u", "p") shouldBe true
    builder.isStaleSettingsEntry(feedB, host, "u", "p") shouldBe false
    probesByUri.values.toSeq.sorted shouldBe Seq(1, 1)
  }
}
