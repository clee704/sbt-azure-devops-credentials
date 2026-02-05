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

import java.net.URI
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tagobjects.Slow

import com.azure.core.credential.TokenRequestContext
import com.azure.identity.DefaultAzureCredentialBuilder

/**
 * Integration tests that require a real Azure DevOps endpoint and Azure login.
 *
 * To run these tests:
 * 1. Log in to Azure: `az login`
 * 2. Set the environment variable: `export AZURE_DEVOPS_TEST_URL=https://pkgs.dev.azure.com/myorg/myproject/_packaging/feed/maven/v1`
 * 3. Run: `sbt "testOnly *IntegrationSpec"`
 *
 * These tests are tagged as Slow and skipped by default.
 */
class AzureDevOpsCredentialsIntegrationSpec extends AnyFlatSpec with Matchers {

  val testUrl: Option[String] = sys.env.get("AZURE_DEVOPS_TEST_URL")

  "Azure Identity" should "obtain an access token" taggedAs Slow in {
    assume(testUrl.isDefined, "Set AZURE_DEVOPS_TEST_URL to run this test")

    val credential = new DefaultAzureCredentialBuilder().build()
    val request = new TokenRequestContext().addScopes("499b84ac-1321-427f-aa17-267ca6975798")

    val token = credential.getToken(request).block()
    token should not be null
    token.getToken should not be empty
  }

  "getOrganization" should "extract org from user-provided URL" taggedAs Slow in {
    assume(testUrl.isDefined, "Set AZURE_DEVOPS_TEST_URL to run this test")

    val uri = new URI(testUrl.get)
    val org = AzureDevOpsCredentialsPlugin.getOrganization(uri)

    org shouldBe defined
    org.get should not be empty
    println(s"Extracted organization: ${org.get}")
  }

  "isAzureDevOpsHost" should "recognize user-provided URL as Azure DevOps" taggedAs Slow in {
    assume(testUrl.isDefined, "Set AZURE_DEVOPS_TEST_URL to run this test")

    val uri = new URI(testUrl.get)
    val host = uri.getHost

    AzureDevOpsCredentialsPlugin.isAzureDevOpsHost(host) shouldBe true
  }
}
