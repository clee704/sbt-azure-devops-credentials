#!/bin/bash
set -eux
version=$(sbt 'print version' | tail -n 1)
bundle_name="sbt-azure-devops-credentials_2.12_1.0-$version.zip"
GPG_TTY=$(tty) sbt 'set publishTo := Some(Resolver.mavenLocal)' publishSigned
pushd "$HOME/.m2/repository"
zip -r "$bundle_name" "dev/chungmin/sbt-azure-devops-credentials_2.12_1.0/$version"
rm -rf "dev/chungmin/sbt-azure-devops-credentials_2.12_1.0/$version"
popd
mv "$HOME/.m2/repository/$bundle_name" .
echo "$bundle_name"
