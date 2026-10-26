#!/usr/bin/env bash
# Pure JVM checks for caller-calibrated standard and Hisense session UID formats.
set -euo pipefail
cd "$(dirname "$0")/.."
task_process_classes="$(mktemp -d)"
trap 'rm -rf "$task_process_classes"' EXIT
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 --release 8 -d "$task_process_classes" \
  app/src/io/freemytv/EmbyProcessIdentity.java tests/EmbyProcessIdentityCheck.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$task_process_classes" io.freemytv.EmbyProcessIdentityCheck
