#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_classes="$(mktemp -d)"
trap 'rm -rf "$task_classes"' EXIT
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d "$task_classes" \
  app/src/io/freemytv/HisenseHomePolicy.java tests/HisenseHomePolicyTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$task_classes" io.freemytv.HisenseHomePolicyTest
