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

import lmcoursier.CoursierConfiguration
import lmcoursier.definitions.Authentication

import com.azure.core.credential.TokenRequestContext
import com.azure.identity.DefaultAzureCredentialBuilder

object AzureDevOpsCredentialsPlugin extends AutoPlugin {
  override def requires = MavenSettingsCredentialsPlugin
  override def trigger = allRequirements

  override lazy val projectSettings = Seq(
    credentials ++= buildCredentials(credentials.value, resolvers.value, streams.value.log),

    // Fix for https://github.com/coursier/coursier/issues/1649
    csrConfiguration := updateCoursierConf(
        csrConfiguration.value, csrResolvers.value, credentials.value),
    updateClassifiers / csrConfiguration :=
        csrConfiguration.value.withClassifiers(Vector("sources")).withHasClassifiers(true)
  )

  private def buildCredentials(
      existingCredentials: Seq[Credentials],
      resolvers: Seq[Resolver],
      log: ManagedLogger): Seq[Credentials] = {
    val credMap = existingCredentials.collect {
      case credential: DirectCredentials =>
        (credential.host, credential.userName) -> credential
    }.toMap
    val helper = new AzureDevOpsCredentialHelper(log)
    resolvers.collect {
      case repo: MavenRepo =>
        val uri = new URI(repo.root)
        val host = uri.getHost
        getOrganization(uri).flatMap { org =>
          if (!credMap.contains((host, org))) {
            for {
              realm <- helper.getRealm(uri)
              token <- helper.token
            } yield {
              Credentials(realm, host, org, token)
            }
          } else {
            None
          }
        }
    }.flatten
  }

  private def getOrganization(uri: URI): Option[String] = {
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

  class AzureDevOpsCredentialHelper(log: ManagedLogger) {
    def getRealm(uri: URI): Option[String] = {
      try {
        val conn = uri.toURL.openConnection()
        for {
          values <- conn.getHeaderFields.asScala.get("WWW-Authenticate")
          value <- values.asScala.find(_.startsWith("Basic realm="))
          m <- "Basic realm=\"([^\"]*)\"".r.findFirstMatchIn(value)
        } yield {
          m.group(1)
        }
      } catch {
        case NonFatal(e) =>
          log.warn(s"Failed to get realm for host ${uri.getHost}: $e")
          None
      }
    }

    lazy val token: Option[String] = {
      log.info("Trying to get access token")
      val credential = new DefaultAzureCredentialBuilder().build()
      // Restrict token to Azure DevOps
      val request = new TokenRequestContext().addScopes("499b84ac-1321-427f-aa17-267ca6975798")
      try {
        val token = credential.getToken(request).block()
        if (token != null) {
          log.info("Azure access token created")
          Some(token.getToken())
        } else {
          log.warn(s"Failed to get access token (getToken() returned null)")
          None
        }
      } catch {
        case NonFatal(e) =>
          log.warn(s"Failed to get access token. Did you forget to run `az login`?")
          log.debug(e.toString())
          None
      }
    }
  }

  def updateCoursierConf(
      conf: CoursierConfiguration,
      resolvers: Seq[Resolver],
      credentials: Seq[Credentials]) = {
    val credMap = credentials.collect {
      case credential: DirectCredentials =>
        (credential.host, credential.userName) -> credential
    }.toMap
    val auths = resolvers.collect {
      case repo: MavenRepo =>
        val uri = new URI(repo.root)
        val host = uri.getHost
        for {
          org <- getOrganization(uri)
          credential <- credMap.get((host, org))
        } yield {
          repo.name -> Authentication(credential.userName, credential.passwd)
        }
    }.flatten
    auths.foldLeft(conf) { case (conf, (repoId, auth)) =>
      val authenticationByRepositoryId = conf.authenticationByRepositoryId :+ (repoId, auth)
      conf.withAuthenticationByRepositoryId(authenticationByRepositoryId)
    }
  }
}
