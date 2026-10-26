#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_back_classes="$(mktemp -d)"
trap 'rm -rf "$task_back_classes"' EXIT
test -n "${JSON_JAR:-}" && test -f "$JSON_JAR"
test "$(shasum -a 256 "$JSON_JAR" | cut -d ' ' -f 1)" = 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -cp "$JSON_JAR" -d "$task_back_classes" \
  tests/jvm/android/content/*.java tests/jvm/io/freemytv/*.java \
  app/src/io/freemytv/EmbyAccountPolicy.java app/src/io/freemytv/EmbyTvPlaybackPolicy.java \
  app/src/io/freemytv/EmbyProgress.java app/src/io/freemytv/EmbyPlayback.java \
  app/src/io/freemytv/EmbyPlayerSession.java app/src/io/freemytv/EmbyPlayer.java app/src/io/freemytv/EmbyHttp.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$task_back_classes:$JSON_JAR" io.freemytv.TvBackHandoffCheck
