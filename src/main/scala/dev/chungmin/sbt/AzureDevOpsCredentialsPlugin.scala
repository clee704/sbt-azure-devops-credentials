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

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import sbt._
import sbt.internal.util.ManagedLogger
import Keys._

import com.azure.core.credential.TokenRequestContext
import com.azure.identity.DefaultAzureCredentialBuilder

object AzureDevOpsCredentialsPlugin extends AutoPlugin {
  override def trigger = allRequirements

  override lazy val projectSettings = Seq(
    credentials ++= buildCredentials(resolvers.value, streams.value.log)
  )

  private def buildCredentials(resolvers: Seq[Resolver], log: ManagedLogger): Seq[Credentials] = {
    val helper = new AzureDevOpsCredentialHelper(log)
    resolvers.map {
      case resolver: MavenRepo =>
        val uri = new URI(resolver.root)
        helper.getOrganization(uri).flatMap { org =>
          helper.getRealm(uri).flatMap { realm =>
            helper.token.map { token =>
              Credentials(realm, uri.getHost, org, token)
            }
          }
        }
      case _ => None
    }.flatten
  }

  class AzureDevOpsCredentialHelper(log: ManagedLogger) {
    def getOrganization(uri: URI): Option[String] = {
      val host = uri.getHost
      if (host.endsWith("pkgs.visualstudio.com")) {
        Some(host.split("\\.").head)
      } else if (host == "pkgs.dev.azure.com") {
        val pathFragments = uri.getPath.split("/")
        if (pathFragments.size > 0) {
          Some(pathFragments.head)
        } else {
          None
        }
      } else {
        None
      }
    }

    def getRealm(uri: URI): Option[String] = {
      try {
        val conn = uri.toURL.openConnection()
        conn.getHeaderFields.asScala.get("WWW-Authenticate").flatMap { values =>
          values.asScala.find(_.startsWith("Basic realm=")).flatMap { value =>
            // TODO proper parsing
            "Basic realm=\"([^\"]*)\"".r.findFirstMatchIn(value).map { m =>
              m.group(1)
            }
          }
        }
      } catch {
        case NonFatal(e) =>
          log.warn(s"Failed to get realm for host ${uri.getHost}: $e")
          None
      }
    }

    lazy val token: Option[String] = {
      val credential = new DefaultAzureCredentialBuilder().build()
      // Restrict token to Azure DevOps
      val request = new TokenRequestContext().addScopes("499b84ac-1321-427f-aa17-267ca6975798")
      try {
        val token = credential.getToken(request).block()
        if (token != null) {
          log.info("Azure access token created")
          Some(token.getToken())
        } else {
          log.warn(s"Failed to get access token")
          None
        }
      } catch {
        case NonFatal(e) =>
          log.warn(s"Failed to get access token: $e")
          None
      }
    }
  }
}
