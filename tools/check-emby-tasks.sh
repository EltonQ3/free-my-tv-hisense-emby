#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_parser_classes="$(mktemp -d)"
trap 'rm -rf "$task_parser_classes"' EXIT
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 --release 8 -d "$task_parser_classes" \
  app/src/io/freemytv/EmbyTaskTree.java tests/EmbyTaskTreeCheck.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$task_parser_classes" io.freemytv.EmbyTaskTreeCheck
