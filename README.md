# How to use

Requirements:
- SBT 1.9.9 or higher.
- Azure CLI

Add the following line to `project/plugins.sbt`:

```scala
addSbtPlugin("dev.chungmin" % "sbt-azure-devops-credentials" % "0.0.1-SNAPSHOT")
libraryDependencies += "com.azure" % "azure-identity" % "1.12.1"
```

You should log in to Azure by using `az login`. Once you're logged in, the plugin will
create access tokens for the Maven feeds from Azure DevOps.

# Example

```scala
// build.sbt
resolvers += "InternalMaven" at "https://myorg.pkgs.visualstudio.com/myproject/_packaging/InternalMaven/maven/v1"
```

You can run `sbt 'show credentials'` to check if the credentials are populated correctly.

# Known limitations

- The realm value might be parsed incorrectly if it contains escape sequences.
- It requires SBT 1.9.9 or higher.

None of these are too difficult to fix, but the current code just works for me so I didn't bother to invest more time. Contributions are welcome.
