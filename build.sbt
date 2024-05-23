organization := "dev.chungmin"
name := "sbt-azure-devops-credentials"

enablePlugins(SbtPlugin)
pluginCrossBuild / sbtVersion := {
  scalaBinaryVersion.value match {
    case "2.12" => "1.9.9"
  }
}

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

libraryDependencies += "com.azure" % "azure-identity" % "1.12.1"

// Publish config
credentials += Credentials(
  "Sonatype Nexus Repository Manager",
  "oss.sonatype.org",
  sys.env.getOrElse("SONATYPE_USERNAME", ""),
  sys.env.getOrElse("SONATYPE_PASSWORD", "")
)
scmInfo := Some(
  ScmInfo(
    url("https://github.com/clee704/sbt-azure-devops-credentials"),
    "scm:git@github.com:clee704/sbt-azure-devops-credentials.git"
  )
)
developers := List(
  Developer(
    id = "clee704",
    name = "Chungmin Lee",
    email = "lee@chungmin.dev",
    url = url("https://github.com/clee704")
  )
)
description := "Create Azure DevOps access tokens for Azure DevOps Maven feeds"
licenses := List(
  "Apache 2" -> new URI("http://www.apache.org/licenses/LICENSE-2.0.txt").toURL
)
homepage := Some(url("https://github.com/clee704/sbt-azure-devops-credentials"))
pomIncludeRepository := { _ => false }
publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
publishMavenStyle := true
