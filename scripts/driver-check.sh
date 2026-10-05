#!/usr/bin/env bash
# 检查 execution Driver（cursor-local / codex-local）；不引入大型 CLI 框架。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
CMD="${1:-check}"
shift || true
ARGS="$CMD"
if [[ $# -gt 0 ]]; then
  ARGS="$CMD $*"
fi
exec mvn -pl wms-execution-driver -q -DskipTests compile exec:java \
  -Dexec.classpathScope=compile \
  -Dexec.workingDirectory="$ROOT" \
  -Dwms.driver.workspace="$ROOT" \
  -Dexec.args="$ARGS"
