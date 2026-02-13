# Contributing

## Development

### Using the Dev Container (recommended)

Open this project in VS Code with the [Dev Containers extension](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers). The container includes JDK, sbt, and Azure CLI pre-configured.

### Manual Setup

Requirements:
- JDK 8 or higher
- sbt

## Testing

Run unit tests:
```bash
sbt test
```

Run integration tests (requires Azure login and a real Azure DevOps feed):
```bash
az login
export AZURE_DEVOPS_TEST_URL=https://pkgs.dev.azure.com/myorg/myproject/_packaging/feed/maven/v1
sbt test
```

## Coding Conventions

- Follow Scala 2.12 conventions
- Keep dependencies minimal
- Log levels: avoid verbose logging at `info` or above—reserve `info` for things users truly need to know. Use `debug` for implementation details useful to plugin developers. Use `warn` when credential retrieval fails.

## Updating Documentation

- Update [CONTRIBUTING.md](CONTRIBUTING.md) when codifying knowledge useful to both humans and AI (coding conventions, project decisions, etc.)
- Update [.github/copilot-instructions.md](.github/copilot-instructions.md) for AI-specific guidance only

## Commit Messages

This project follows [Conventional Commits](https://www.conventionalcommits.org/). Format:

```
<type>: <description>

[optional body]

[optional footer]
```

Types:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation only
- `refactor`: Code change that neither fixes a bug nor adds a feature
- `chore`: Maintenance tasks (build, CI, dependencies)
- `test`: Adding or updating tests

Examples:
```
feat: add support for Azure DevOps Server
fix: handle missing realm in HTTP response
docs: add troubleshooting section to README
refactor: change info logs to debug level
```

## Releasing

### Prerequisites

- GPG key for signing artifacts
- Sonatype credentials (set `SONATYPE_USERNAME` and `SONATYPE_PASSWORD` environment variables)

### Release Steps

1. Run the release script:
   ```bash
   ./release.sh 0.0.6
   ```
   This updates version files, commits, tags, and creates a signed bundle.
   If Sonatype credentials are set, it also uploads the bundle automatically.

2. Go to [Sonatype Central Portal](https://central.sonatype.com/publishing/deployments), verify the deployment, and click "Publish".

3. Finish the release (bumps to next snapshot and pushes):
   ```bash
   ./release.sh --finish 0.0.6
   ```
