# How to use

Requirements:
- SBT 1.3.0 or higher.
- Azure CLI

Add the following line to `project/plugins.sbt`:

```scala
addSbtPlugin("dev.chungmin" % "sbt-azure-devops-credentials" % "0.0.1-SNAPSHOT")
```

You should log in to Azure by using `az login`. Once you're logged in, the plugin will
create access tokens for the Maven feeds from Azure DevOps.

This plugin uses another SBT plugin, `sbt-maven-settings-credentials` to first check if the credentials are
already in `~/.m2/settings.xml`. If credentials are found in the Maven settings file, token creation can be skipped.

# Example

```scala
// build.sbt
resolvers += "InternalMaven" at "https://myorg.pkgs.visualstudio.com/myproject/_packaging/InternalMaven/maven/v1"
```

You can run `sbt 'show credentials'` to check if the credentials are populated correctly.
