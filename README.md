# How to use

Requirements:
- SBT 1.9.0 or higher.
- Azure CLI

Add the following lines to `project/plugins.sbt`:

```scala
addSbtPlugin("dev.chungmin" % "sbt-azure-devops-credentials" % "0.0.9")
```

You should log in to Azure by using `az login`. Once you're logged in, the plugin will
create access tokens for the Maven feeds from Azure DevOps.

This plugin first checks if the credentials are already in `~/.m2/settings.xml`.
If credentials are found in the Maven settings file, the plugin probes the feed
with those credentials and falls through to Entra token acquisition only if the
probe returns `401` (i.e., the existing entry is stale). Valid entries are
trusted as before — no extra round-trips on the happy path.

## Configuring credential validation

The probe behavior is controlled by `dev.chungmin.azure.validateExistingCredentials`
(values: `auto` (default), `always`, `never`).

| Mode | 401 + Entra reachable + new token works for feed | 401 + Entra reachable but new token has no feed access | 401 + Entra unreachable | Use case |
|---|---|---|---|---|
| `auto` (default) | Drop entry, use Entra | Keep entry, log INFO about Entra identity scope | Keep entry, log INFO with `az login` remediation | Recover from stale PATs automatically; falls back to trusting settings.xml when Entra also can't help (e.g. Managed Identity without feed access) |
| `always` | Drop entry, use Entra | Drop entry (no Bearer-verify in `always` mode); fetch then 401s and sbt/coursier surfaces the error | Drop entry, Entra error surfaces (`WARN` from `getTokenImpl`) | Force Entra path; surfaces Entra-side failures with actionable errors |
| `never` | Trust entry | Trust entry | Trust entry | Pre-0.0.10 behavior — disables the probe entirely |

Set via any of:

```bash
# CLI
sbt -Ddev.chungmin.azure.validateExistingCredentials=always update

# .jvmopts (per-project, checked in)
echo "-Ddev.chungmin.azure.validateExistingCredentials=always" >> .jvmopts

# SBT_OPTS (per-host or CI)
export SBT_OPTS="$SBT_OPTS -Ddev.chungmin.azure.validateExistingCredentials=always"

# build.sbt (per-project, idiomatic)
azureDevOpsValidateExistingCredentials := "always"
```

# Example

```scala
// build.sbt
resolvers += "InternalMaven" at "https://myorg.pkgs.visualstudio.com/myproject/_packaging/InternalMaven/maven/v1"
```

The plugin looks at `resolvers` and check if it's an Azure DevOps Maven feed. If that is the case and no credentials are already provided for the feed, the plugin creates an access token for it using the [Azure Identity client library](https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/identity/azure-identity/README.md).

You can run `sbt 'show credentials'` to check if the credentials are populated correctly.

# Limitations

This plugin only supports Azure DevOps Services (cloud). Azure DevOps Server (on-premises) is not supported, as it uses custom domains that cannot be auto-detected.

**Multi-feed-per-host with mixed valid + stale `<server>` entries** (tracked: #7). sbt's credential model is host-keyed (not resolver-name-keyed). When two `<server>` entries in `~/.m2/settings.xml` resolve to the same host (e.g., `<server id="FeedA">` and `<server id="FeedB">`, both serving feeds at `pkgs.dev.azure.com/...`), and one PAT is valid while the other is stale, dropping the stale one doesn't produce an Entra fallback for that specific feed — the still-valid entry occupies the host slot in sbt's credential lookup, and the dropped feed inherits the other entry's credentials. Subsequent fetches against the dropped feed will 401 because the valid PAT isn't scoped to it. Workarounds:

- Consolidate so that a host has a single PAT that's scoped to all its feeds you use, OR
- Set `azureDevOpsValidateExistingCredentials := "never"` so the legacy "trust both, let the fetch fail" behavior surfaces, OR
- Use distinct hostnames per feed (legacy `<org>.pkgs.visualstudio.com` form keys orgs into separate hostnames).

# Troubleshooting

To see debug output from the plugin, enable debug logging in sbt:

```bash
sbt --debug
```

Or in the sbt shell:

```
set logLevel := Level.Debug
```
