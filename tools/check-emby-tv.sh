#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_tv_classes="$(mktemp -d)"
trap 'rm -rf "$task_tv_classes"' EXIT
test -n "${JSON_JAR:-}" && test -f "$JSON_JAR"
task_json_hash="$(shasum -a 256 "$JSON_JAR" | cut -d ' ' -f 1)"
test "$task_json_hash" = 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -cp "$JSON_JAR" -d "$task_tv_classes" \
  app/src/io/freemytv/EmbyAccountPolicy.java app/src/io/freemytv/EmbyTvAccountPolicy.java \
  app/src/io/freemytv/EmbyPlayerSession.java tests/EmbyTvPlayerTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$task_tv_classes:$JSON_JAR" io.freemytv.EmbyTvPlayerTest
