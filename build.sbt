scalaVersion := "2.12.19"
scalacOptions ++= Seq("-release", "8")
javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

enablePlugins(SbtPlugin)

pluginCrossBuild / sbtVersion := {
  scalaBinaryVersion.value match {
    case "2.12" => "1.3.0"
  }
}

libraryDependencies += "com.azure" % "azure-identity" % "1.12.1"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.18" % Test

// Exclude integration tests (tagged as Slow) by default
Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-l", "org.scalatest.tagobjects.Slow")
