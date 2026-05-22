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
      settings: File = new File("/this/path/does/not/exist")
  ): AzureDevOpsCredentialsPlugin.CredentialsBuilder = {
    new AzureDevOpsCredentialsPlugin.CredentialsBuilder(nullLog) {
      override protected def getRealm(uri: URI): Option[String] = realm(uri)
      override protected def getToken(): Option[String] = token()
      override protected def mavenSettingsFile: File = settings
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
    val prop = "org.slf4j.simpleLogger.log.com.azure.identity"
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
    val prop = "org.slf4j.simpleLogger.log.com.azure.identity"
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
    val prop = "org.slf4j.simpleLogger.log.com.azure.identity"
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
    val prop = "org.slf4j.simpleLogger.log.com.azure.identity"
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

  it should "default to 80 for non-https schemes with no explicit port" in {
    // The branch is "https -> 443 else 80"; assert the else path on a non-https,
    // non-http scheme (e.g. ftp) actually returns 80, not 443.
    AzureDevOpsCredentialsPlugin.defaultPort(new URI("ftp://example.com/")) shouldBe 80
  }
}
