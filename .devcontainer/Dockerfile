FROM mcr.microsoft.com/devcontainers/java:21

# Install Coursier and Scala tools (sbt, scala-cli, etc.) as vscode user
USER vscode
RUN curl -fL https://github.com/coursier/coursier/releases/latest/download/cs-x86_64-pc-linux.gz | gzip -d > /tmp/cs \
    && chmod +x /tmp/cs \
    && /tmp/cs setup -y \
    && rm /tmp/cs
USER root

ENV PATH="/home/vscode/.local/share/coursier/bin:${PATH}"
