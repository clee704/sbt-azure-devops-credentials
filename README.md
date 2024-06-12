# How to use

Requirements:
- SBT 1.9.0 or higher.
- Azure CLI

Add the following lines to `project/plugins.sbt`:

```scala
addSbtPlugin("dev.chungmin" % "sbt-maven-settings-credentials" % "0.0.2")
addSbtPlugin("dev.chungmin" % "sbt-azure-devops-credentials" % "0.0.4")
```

You should log in to Azure by using `az login`. Once you're logged in, the plugin will
create access tokens for the Maven feeds from Azure DevOps.

This plugin uses another SBT plugin `sbt-maven-settings-credentials` to first check if the credentials are
already in `~/.m2/settings.xml`. If credentials are found in the Maven settings file, token creation can be skipped.

# Example

```scala
// build.sbt
resolvers += "InternalMaven" at "https://myorg.pkgs.visualstudio.com/myproject/_packaging/InternalMaven/maven/v1"
```

The plugin looks at `resolvers` and check if it's an Azure DevOps Maven feed. If that is the case and no credentials are already provided for the feed, the plugin creates an access token for it using the [Azure Identity client library](https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/identity/azure-identity/README.md).

You can run `sbt 'show credentials'` to check if the credentials are populated correctly.
