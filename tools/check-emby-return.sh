#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_return_classes="$(mktemp -d)"
trap 'rm -rf "$task_return_classes"' EXIT
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 --release 8 -d "$task_return_classes" \
  app/src/io/freemytv/EmbyReturnPolicy.java tests/EmbyReturnPolicyCheck.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$task_return_classes" io.freemytv.EmbyReturnPolicyCheck
