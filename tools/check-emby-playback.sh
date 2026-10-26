#!/usr/bin/env bash
# JVM-only harness: production resolution/transport classes, fake Android context and local mock servers.
# org.json is test-only; the Android app continues to use the SDK's built-in JSON API.
set -euo pipefail
cd "$(dirname "$0")/.."
task_test_dir="$(mktemp -d)"
trap 'rm -rf "$task_test_dir"' EXIT
task_json_hash="3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"
if [ -n "${JSON_JAR:-}" ]; then
  cp "$JSON_JAR" "$task_test_dir/json.jar"
else
  curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
    https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$task_test_dir/json.jar"
fi
if command -v sha256sum >/dev/null; then
  task_actual_hash="$(sha256sum "$task_test_dir/json.jar" | cut -d ' ' -f 1)"
else
  task_actual_hash="$(shasum -a 256 "$task_test_dir/json.jar" | cut -d ' ' -f 1)"
fi
[ "$task_actual_hash" = "$task_json_hash" ] || { echo "Test JSON dependency checksum mismatch" >&2; exit 1; }
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -cp "$task_test_dir/json.jar" -d "$task_test_dir/classes" \
  tests/jvm/android/content/*.java tests/jvm/io/freemytv/*.java \
  app/src/io/freemytv/EmbyAccountPolicy.java app/src/io/freemytv/EmbyTvAccountPolicy.java \
  app/src/io/freemytv/EmbyTvPlaybackPolicy.java \
  app/src/io/freemytv/EmbyProgress.java app/src/io/freemytv/EmbyPlayback.java \
  app/src/io/freemytv/EmbyAccounts.java app/src/io/freemytv/EmbyPlayerSession.java \
  app/src/io/freemytv/EmbyPlayer.java app/src/io/freemytv/EmbyHttp.java \
  tests/EmbyTvPlayerTest.java tests/EmbyDualClientTest.java
for task_test in PlaybackCheck AccountCheck PlayerCheck HttpCheck HandoffCheck TvBackHandoffCheck EmbyTvPlayerTest EmbyDualClientTest DualPlayerHandoffCheck; do
  "${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$task_test_dir/classes:$task_test_dir/json.jar" "io.freemytv.$task_test"
done
