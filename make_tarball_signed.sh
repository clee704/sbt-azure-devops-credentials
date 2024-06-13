#!/bin/bash
set -eux
version=$(sbt 'print version' | tail -n 1)
tarball_name="sbt-azure-devops-credentials_2.12_1.0-$version.tar.gz"
GPG_TTY=$(tty) sbt 'set publishTo := Some(Resolver.mavenLocal)' publishSigned
pushd "$HOME/.m2/repository"
tar cvzf "$tarball_name" "dev/chungmin/sbt-azure-devops-credentials_2.12_1.0/$version"
rm -rf "dev/chungmin/sbt-azure-devops-credentials_2.12_1.0/$version"
popd
mv "$HOME/.m2/repository/$tarball_name" .
echo "$tarball_name"
