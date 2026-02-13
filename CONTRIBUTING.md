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
