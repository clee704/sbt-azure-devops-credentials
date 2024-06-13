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
import scala.xml.XML

import sbt._
import sbt.internal.util.ManagedLogger
import Keys._

import lmcoursier.CoursierConfiguration
import lmcoursier.definitions.Authentication

import com.azure.core.credential.TokenRequestContext
import com.azure.identity.DefaultAzureCredentialBuilder

object AzureDevOpsCredentialsPlugin extends AutoPlugin {
  override def trigger = allRequirements

  override lazy val projectSettings = Seq(
    credentials ++= new CredentialsBuilder(streams.value.log)
      .buildCredentials(credentials.value, externalResolvers.value),

    // Fix for https://github.com/coursier/coursier/issues/1649
    csrConfiguration := updateCoursierConf(
        csrConfiguration.value, csrResolvers.value, credentials.value),
    updateClassifiers / csrConfiguration :=
        csrConfiguration.value.withClassifiers(Vector("sources")).withHasClassifiers(true)
  )

  class CredentialsBuilder(log: ManagedLogger) {
    def buildCredentials(
        existingCredentials: Seq[Credentials],
        resolvers: Seq[Resolver]): Seq[Credentials] = {
      val credentialsFromMavenSettings = buildCredentialsFromMavenSettings(resolvers)
      val generatedCredentials = buildCredentialsWithAccessToken(
        existingCredentials ++ credentialsFromMavenSettings,
        resolvers)
      credentialsFromMavenSettings ++ generatedCredentials
    }

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
            getOrganization(uri).flatMap { org =>
              for {
                realm <- getRealm(uri)
                token <- getToken()
              } yield {
                log.info(s"creating credentials for realm=$realm, host=$host, user=$org")
                Credentials(realm, host, org, token)
              }
            }
          } else {
            log.debug(s"$host has existing credentials")
            None
          }
      }.flatten
      log.info(s"created ${credentials.size} credentials with access token")
      credentials
    }

    private def getOrganization(uri: URI): Option[String] = {
      val host = uri.getHost
      val maybeOrg = if (host.endsWith("pkgs.visualstudio.com")) {
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
      log.debug(s"found org=$maybeOrg from URI=$uri")
      maybeOrg
    }

    private def getRealm(uri: URI): Option[String] = {
      try {
        val conn = uri.toURL.openConnection()
        // TODO proper parsing
        val realm = for {
          values <- conn.getHeaderFields.asScala.get("WWW-Authenticate")
          value <- values.asScala.find(_.startsWith("Basic realm="))
          m <- "Basic realm=\"([^\"]*)\"".r.findFirstMatchIn(value)
        } yield {
          m.group(1)
        }
        log.debug(s"found realm=$realm for URI=$uri")
        realm
      } catch {
        case NonFatal(e) =>
          log.warn(s"failed to get realm for host ${uri.getHost}: $e")
          None
      }
    }

    private var cachedToken: Option[Option[String]] = None

    private def getToken(): Option[String] = synchronized {
      if (!cachedToken.isDefined) {
        cachedToken = Some(getTokenImpl())
      }
      cachedToken.get
    }

    private def getTokenImpl(): Option[String] = {
      log.info("trying to create access token")
      val credential = new DefaultAzureCredentialBuilder().build()
      // Restrict token to Azure DevOps
      val request = new TokenRequestContext().addScopes("499b84ac-1321-427f-aa17-267ca6975798")
      try {
        val token = credential.getToken(request).block()
        if (token != null) {
          log.info("access token created")
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
      }
    }

    private def buildCredentialsFromMavenSettings(resolvers: Seq[Resolver]): Seq[Credentials] = {
      // TODO: support M2_HOME
      val settingsFile = new File(sbt.io.Path.userHome, ".m2/settings.xml")
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
      log.info(s"loaded $settingsFile")
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
              log.info(s"creating credentials for realm=$realm, host=$host, user=$user")
              Credentials(realm, host, user, password)
            }
          }.getOrElse {
            log.debug("matching <server> not found")
            None
          }
      }.flatten
      log.info(s"created ${credentials.size} credentials from settings.xml")
      credentials
    }
  }

  // Fix for https://github.com/coursier/coursier/issues/1649
  private def updateCoursierConf(
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
