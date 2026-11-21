#!/bin/sh
set -eu
DEX_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DEX_MAVEN_VERSION=3.9.11
DEX_MAVEN_HOME="$DEX_ROOT/.local/apache-maven-$DEX_MAVEN_VERSION"
if [ ! -x "$DEX_MAVEN_HOME/bin/mvn" ]; then
  mkdir -p "$DEX_ROOT/.local"
  DEX_ARCHIVE="$DEX_ROOT/.local/apache-maven.tar.gz"
  curl --fail --location --proto '=https' --tlsv1.2 "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$DEX_MAVEN_VERSION/apache-maven-$DEX_MAVEN_VERSION-bin.tar.gz" -o "$DEX_ARCHIVE"
  DEX_ACTUAL=$(shasum -a 512 "$DEX_ARCHIVE" | cut -d ' ' -f 1)
  DEX_EXPECTED=$(tr -d ' \r\n' < "$DEX_ROOT/.mvn/maven.sha512")
  if [ "$DEX_ACTUAL" != "$DEX_EXPECTED" ]; then echo 'Maven checksum mismatch; refusing to execute.' >&2; exit 1; fi
  tar -xzf "$DEX_ARCHIVE" -C "$DEX_ROOT/.local"
  rm "$DEX_ARCHIVE"
fi
exec "$DEX_MAVEN_HOME/bin/mvn" "-Dmaven.repo.local=$DEX_ROOT/.local/m2" "$@"
