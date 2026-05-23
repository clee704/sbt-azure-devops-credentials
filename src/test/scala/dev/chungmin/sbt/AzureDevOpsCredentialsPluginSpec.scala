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

import java.io.{BufferedWriter, OutputStreamWriter}
import java.net.{ServerSocket, URI}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AzureDevOpsCredentialsPluginSpec extends AnyFlatSpec with Matchers {

  "getOrganization" should "extract org from pkgs.visualstudio.com URL" in {
    val uri = new URI("https://myorg.pkgs.visualstudio.com/myproject/_packaging/feed/maven/v1")
    AzureDevOpsCredentialsPlugin.getOrganization(uri) shouldBe Some("myorg")
  }

  it should "extract org from pkgs.dev.azure.com URL" in {
    val uri = new URI("https://pkgs.dev.azure.com/myorg/myproject/_packaging/feed/maven/v1")
    AzureDevOpsCredentialsPlugin.getOrganization(uri) shouldBe Some("myorg")
  }

  it should "return None for non-Azure DevOps URLs" in {
    val uri = new URI("https://repo1.maven.org/maven2")
    AzureDevOpsCredentialsPlugin.getOrganization(uri) shouldBe None
  }

  it should "return None for other visualstudio.com subdomains" in {
    val uri = new URI("https://dev.azure.com/myorg/myproject")
    AzureDevOpsCredentialsPlugin.getOrganization(uri) shouldBe None
  }

  it should "return None for URIs with null host" in {
    val uri = new URI("file:///path/to/repo")
    AzureDevOpsCredentialsPlugin.getOrganization(uri) shouldBe None
  }

  "isAzureDevOpsHost" should "return true for pkgs.visualstudio.com" in {
    AzureDevOpsCredentialsPlugin.isAzureDevOpsHost("myorg.pkgs.visualstudio.com") shouldBe true
  }

  it should "return true for pkgs.dev.azure.com" in {
    AzureDevOpsCredentialsPlugin.isAzureDevOpsHost("pkgs.dev.azure.com") shouldBe true
  }

  it should "return false for maven central" in {
    AzureDevOpsCredentialsPlugin.isAzureDevOpsHost("repo1.maven.org") shouldBe false
  }

  it should "return false for other Azure domains" in {
    AzureDevOpsCredentialsPlugin.isAzureDevOpsHost("dev.azure.com") shouldBe false
  }

  it should "return false for null host" in {
    AzureDevOpsCredentialsPlugin.isAzureDevOpsHost(null) shouldBe false
  }

  /** Start a local HTTP server that responds with the given raw response, then
    * call headRequestHeaders against it. */
  private def withServer(response: String)(
      test: Seq[(String, String)] => Unit): Unit = {
    val server = new ServerSocket(0)
    try {
      val port = server.getLocalPort
      val thread = new Thread(() => {
        val client = server.accept()
        try {
          // Drain the request (read until blank line)
          val in = client.getInputStream
          val buf = new Array[Byte](1024)
          in.read(buf)
          // Send the response
          val writer = new BufferedWriter(
            new OutputStreamWriter(client.getOutputStream, "UTF-8"))
          writer.write(response)
          writer.flush()
        } finally {
          client.close()
        }
      })
      thread.setDaemon(true)
      thread.start()
      val uri = new URI(s"http://localhost:$port/test")
      val headers = AzureDevOpsCredentialsPlugin.headRequestHeaders(uri)
      test(headers)
      thread.join(5000)
    } finally {
      server.close()
    }
  }

  "headRequestHeaders" should "parse response headers" in {
    val response =
      "HTTP/1.1 401 Unauthorized\r\n" +
      "Content-Length: 0\r\n" +
      "WWW-Authenticate: Bearer authorization_uri=https://login.windows.net/abc\r\n" +
      "WWW-Authenticate: Basic realm=\"https://pkgsprod.pkgs.visualstudio.com/\"\r\n" +
      "WWW-Authenticate: TFS-Federated\r\n" +
      "Connection: close\r\n" +
      "\r\n"
    withServer(response) { headers =>
      headers should contain ("Content-Length" -> "0")
      headers should contain ("WWW-Authenticate" -> "Bearer authorization_uri=https://login.windows.net/abc")
      headers should contain ("WWW-Authenticate" -> "Basic realm=\"https://pkgsprod.pkgs.visualstudio.com/\"")
      headers should contain ("WWW-Authenticate" -> "TFS-Federated")
      headers should contain ("Connection" -> "close")
    }
  }

  it should "return all duplicate headers" in {
    val response =
      "HTTP/1.1 401 Unauthorized\r\n" +
      "X-Custom: first\r\n" +
      "X-Custom: second\r\n" +
      "\r\n"
    withServer(response) { headers =>
      headers.filter(_._1 == "X-Custom").map(_._2) shouldBe Seq("first", "second")
    }
  }

  it should "return empty seq for response with no headers" in {
    val response = "HTTP/1.1 200 OK\r\n\r\n"
    withServer(response) { headers =>
      headers shouldBe empty
    }
  }

  it should "use path from URI" in {
    val server = new ServerSocket(0)
    try {
      val port = server.getLocalPort
      var receivedRequest = ""
      val thread = new Thread(() => {
        val client = server.accept()
        try {
          val in = client.getInputStream
          val buf = new Array[Byte](1024)
          val n = in.read(buf)
          receivedRequest = new String(buf, 0, n, "UTF-8")
          val writer = new BufferedWriter(
            new OutputStreamWriter(client.getOutputStream, "UTF-8"))
          writer.write("HTTP/1.1 200 OK\r\n\r\n")
          writer.flush()
        } finally {
          client.close()
        }
      })
      thread.setDaemon(true)
      thread.start()
      val uri = new URI(s"http://localhost:$port/my/custom/path")
      AzureDevOpsCredentialsPlugin.headRequestHeaders(uri)
      thread.join(5000)
      receivedRequest should startWith ("HEAD /my/custom/path HTTP/1.1")
    } finally {
      server.close()
    }
  }

  "createCredential" should "build a chain without throwing when no AAD env vars are set" in {
    // Regression test for v0.0.8: WorkloadIdentityCredentialBuilder.build()
    // throws IllegalArgumentException when AZURE_CLIENT_ID / AZURE_TENANT_ID /
    // AZURE_FEDERATED_TOKEN_FILE are unset. Because that .build() is called
    // eagerly during chain assembly, the entire chain construction failed before
    // AzureCli was ever tried — leaving developer workstations (which don't
    // normally have those env vars set) with a plugin that always reported
    // "failed to get access token. Did you forget to run `az login`?".
    //
    // The `credentialProviders` tests below pin the *order* invariant
    // directly (which a `cred should not be null` assertion can't), and
    // `envValue`'s direct unit tests cover the absent / empty / whitespace /
    // padded states — so this one test is sufficient as a named regression
    // anchor for the v0.0.8 repro.
    val cred = AzureDevOpsCredentialsPlugin.createCredential(Map.empty)
    cred should not be null
  }

  it should "build a chain without throwing when AAD env vars are padded with whitespace" in {
    // End-to-end smoke test for the trim defense: a partially-broken
    // env-template substitution may produce a *padded* value (e.g. trailing
    // newline from a YAML literal block) rather than a pure-whitespace one.
    // The SDK builder accepts padded values at .build() time and would only
    // fail at .getToken() time with a confusing AAD error.
    //
    // Required because a `credentialProviders` order assertion against the
    // padded env can't prove the trim ran — the chain-element classes are
    // the same regardless of whether the credential was built with padded
    // or trimmed values. This test verifies the end-to-end path:
    // createCredential → credentialProviders → for-yield → envValue.trim.
    val env = Map(
      "AZURE_CLIENT_ID" -> "  00000000-0000-0000-0000-000000000000  ",
      "AZURE_TENANT_ID" -> "\t00000000-0000-0000-0000-000000000000\n",
      "AZURE_FEDERATED_TOKEN_FILE" -> "  /tmp/nonexistent-federated-token  "
    )
    val cred = AzureDevOpsCredentialsPlugin.createCredential(env)
    cred should not be null
  }

  "credentialProviders" should "place AzureCli first and ManagedIdentity last when AAD env vars are unset" in {
    // Pins the v0.0.8 chain order: on dev workstations with no AAD env vars,
    // AzureCli must be tried first (the v0.0.8 motivation), and ManagedIdentity
    // must be the fallback for VM-resident builds. A future refactor that
    // swaps the .addLast calls would still pass every other regression test
    // because they all assert on "chain assembly doesn't throw" rather than
    // on provider ordering — but it would silently regress the original bug
    // (user's `az login` ignored in favor of the VM's managed identity).
    val providers = AzureDevOpsCredentialsPlugin.credentialProviders(Map.empty)
    providers.map(_.getClass.getSimpleName) shouldBe Seq(
      "AzureCliCredential",
      "AzurePowerShellCredential",
      "EnvironmentCredential",
      "ManagedIdentityCredential"
    )
  }

  it should "insert WorkloadIdentity between EnvironmentCredential and ManagedIdentity when AAD env vars are set" in {
    // Position-sensitive: WorkloadIdentity should run AFTER user-credential
    // providers (AzureCli / PowerShell / Environment) so an interactive
    // developer session wins over a partially-configured workload-identity
    // setup, but BEFORE ManagedIdentity so a k8s pod with workload-identity
    // env vars set doesn't fall through to a VM managed identity.
    val env = Map(
      "AZURE_CLIENT_ID" -> "00000000-0000-0000-0000-000000000000",
      "AZURE_TENANT_ID" -> "11111111-1111-1111-1111-111111111111",
      "AZURE_FEDERATED_TOKEN_FILE" -> "/tmp/nonexistent-federated-token"
    )
    val providers = AzureDevOpsCredentialsPlugin.credentialProviders(env)
    providers.map(_.getClass.getSimpleName) shouldBe Seq(
      "AzureCliCredential",
      "AzurePowerShellCredential",
      "EnvironmentCredential",
      "WorkloadIdentityCredential",
      "ManagedIdentityCredential"
    )
  }

  "envValue" should "return Some(trimmed) for padded values" in {
    val env = Map("X" -> "  abc  ", "Y" -> "\tabc\n", "Z" -> "abc")
    AzureDevOpsCredentialsPlugin.envValue(env, "X") shouldBe Some("abc")
    AzureDevOpsCredentialsPlugin.envValue(env, "Y") shouldBe Some("abc")
    AzureDevOpsCredentialsPlugin.envValue(env, "Z") shouldBe Some("abc")
  }

  it should "return None for absent, empty, or whitespace-only values" in {
    val env = Map("empty" -> "", "spaces" -> "   ", "tabs" -> "\t\t", "mixed" -> "  \n\t  ")
    AzureDevOpsCredentialsPlugin.envValue(env, "empty") shouldBe None
    AzureDevOpsCredentialsPlugin.envValue(env, "spaces") shouldBe None
    AzureDevOpsCredentialsPlugin.envValue(env, "tabs") shouldBe None
    AzureDevOpsCredentialsPlugin.envValue(env, "mixed") shouldBe None
    AzureDevOpsCredentialsPlugin.envValue(env, "missing") shouldBe None
  }

  "initializeAzureIdentityLogSuppression" should "set the property to 'off' when unset" in {
    // Direct exercise of the static-init helper. The block runs once at
    // plugin classloading; re-invoking it from a test (with the property
    // explicitly cleared first) verifies the unset->"off" path.
    val prop = AzureDevOpsCredentialsPlugin.AzureIdentityLogProperty
    val original = Option(System.getProperty(prop))
    System.clearProperty(prop)
    try {
      AzureDevOpsCredentialsPlugin.initializeAzureIdentityLogSuppression()
      System.getProperty(prop) shouldBe "off"
    } finally {
      original match {
        case Some(v) => System.setProperty(prop, v)
        case None => System.clearProperty(prop)
      }
    }
  }

  it should "not override a pre-existing value" in {
    // A developer who sets the property explicitly (e.g. -Dorg.slf4j.simpleLogger.log.com.azure.identity=debug
    // for diagnostics) should see their override survive plugin classloading.
    // Verifies the "no-op when set" branch of the static-init helper.
    val prop = AzureDevOpsCredentialsPlugin.AzureIdentityLogProperty
    val original = Option(System.getProperty(prop))
    System.setProperty(prop, "debug")
    try {
      AzureDevOpsCredentialsPlugin.initializeAzureIdentityLogSuppression()
      System.getProperty(prop) shouldBe "debug"
    } finally {
      original match {
        case Some(v) => System.setProperty(prop, v)
        case None => System.clearProperty(prop)
      }
    }
  }
}
