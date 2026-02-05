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
}
