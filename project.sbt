organization := "dev.chungmin"

name := "sbt-azure-devops-credentials"

sbtPlugin := true

description := "Create Azure DevOps access tokens for Azure DevOps Maven feeds"

homepage := Some(url("https://github.com/clee704/sbt-azure-devops-credentials"))

licenses := List(
  "Apache 2" -> new URI("http://www.apache.org/licenses/LICENSE-2.0.txt").toURL
)

developers := List(
  Developer(
    id = "clee704",
    name = "Chungmin Lee",
    email = "lee@chungmin.dev",
    url = url("https://github.com/clee704")
  )
)
