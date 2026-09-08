#!/usr/bin/env bash
# Resolve outside the producer project, with no user settings or cached artifacts.
set -euo pipefail
version=${1:?Usage: verify-packages-consumer.sh VERSION [REPOSITORY_URL]}
repository_url=${2:-https://packages.fluxzero.io/maven}
root=$(cd "$(dirname "$0")/../.." && pwd)
consumer=$(mktemp -d)
trap 'rm -rf "$consumer"' EXIT
cat > "$consumer/settings.xml" <<'XML'
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>
XML
cat > "$consumer/pom.xml" <<XML
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>consumer</groupId><artifactId>release-smoke</artifactId><version>1</version>
  <repositories><repository><id>fluxzero</id><url>$repository_url</url></repository></repositories>
</project>
XML
for suffix in 'pom' 'jar' 'jar:standalone' 'jar:sources' 'jar:javadoc'; do
  "$root/mvnw" -B -ntp -U -s "$consumer/settings.xml" -gs "$consumer/settings.xml" \
    -f "$consumer/pom.xml" -Dmaven.repo.local="$consumer/repository" \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:get \
    -Dartifact="io.fluxzero.tools:fluxzero-dev-server:$version:$suffix" -Dtransitive=false
done
artifact_dir="$consumer/repository/io/fluxzero/tools/fluxzero-dev-server/$version"
for suffix in .pom .jar -standalone.jar -sources.jar -javadoc.jar; do
  file="fluxzero-dev-server-$version$suffix"
  grep -Fx "$file>fluxzero=" "$artifact_dir/_remote.repositories"
  test -s "$artifact_dir/$file"
done
python3 "$root/.github/scripts/verify-standalone.py" \
  "$artifact_dir/fluxzero-dev-server-$version-standalone.jar" "$version"
