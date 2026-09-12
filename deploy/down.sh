#!/usr/bin/env bash
# 停止本仓库隔离编排。默认保留数据卷；--volumes 才删除本地卷。
set -Eeuo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
cd "${ROOT}"

if ! command -v docker >/dev/null 2>&1; then
  echo "需要 docker" >&2
  exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "需要 docker compose" >&2
  exit 1
fi

ENV_FILE="${ROOT}/.env"
COMPOSE=(docker compose -f compose.yaml)
if [[ -f "${ENV_FILE}" ]]; then
  COMPOSE+=(--env-file "${ENV_FILE}")
fi

if [[ "${1:-}" == "--volumes" ]]; then
  echo "警告：将删除本编排本地数据卷，不影响共享 dev-infra。" >&2
  "${COMPOSE[@]}" down --volumes
else
  "${COMPOSE[@]}" down
fi
