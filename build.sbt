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

// Serialize test suite execution. Multiple suites mutate the JVM-global
// `org.slf4j.simpleLogger.log.com.azure.identity` system property — both
// the plugin's own counted-set acquire/release in `CredentialsBuilderSpec`
// and direct save/restore in `AzureDevOpsCredentialsPluginSpec`'s
// `initializeAzureIdentityLogSuppression` tests. sbt's default
// (parallelExecution := true) runs different suites in parallel, which
// opens a cross-suite race window where one suite's clearProperty fires
// between another suite's setProperty and acquire, leaving the acquire's
// first-acquirer save snapshotting None instead of the real value. The
// race window is small (Mono.just resolves in microseconds) but real,
// and on a ~66-test suite that finishes in ~1s the cost of serializing
// is negligible. Future-proofs against any third spec adding similar
// JVM-global-state mutations.
Test / parallelExecution := false

// Coverage gate matches current coverage exactly. Any regression in test
// discipline must be addressed with a new test, or — if a statement is
// genuinely unreachable from unit tests — with a `$COVERAGE-OFF$` marker.
coverageMinimumStmtTotal := 100
coverageMinimumBranchTotal := 100
coverageFailOnMinimum := true
