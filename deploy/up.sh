#!/usr/bin/env bash
# 用 compose.yaml 在容器内编译并启动本仓库已选定进程；不操作共享 dev-infra。
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
if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少 ${ENV_FILE}。先执行: cp .env.example .env 并替换 change-me" >&2
  exit 1
fi

COMPOSE=(docker compose -f compose.yaml --env-file "${ENV_FILE}")
trap 'echo "中断：容器仍在运行，可用 deploy/down.sh 停止（默认保留数据卷）"' INT TERM

"${COMPOSE[@]}" up -d --build

BIND="$(grep -E '^WMS_BIND_ADDRESS=' "${ENV_FILE}" | cut -d= -f2- || true)"
BIND="${BIND:-127.0.0.1}"
wait_http() {
  local name="$1" url="$2"
  local i=0
  until curl -fsS "${url}" >/dev/null 2>&1; do
    i=$((i + 1))
    if (( i > 90 )); then
      echo "等待 ${name} 超时: ${url}" >&2
      "${COMPOSE[@]}" ps >&2 || true
      exit 1
    fi
    sleep 2
  done
  echo "ready ${name} ${url}"
}

wait_http inbound "http://${BIND}:${WMS_INBOUND_HOST_PORT:-18181}/actuator/health/readiness"
wait_http outbound "http://${BIND}:${WMS_OUTBOUND_HOST_PORT:-18182}/actuator/health/readiness"
wait_http inventory "http://${BIND}:${WMS_INVENTORY_HOST_PORT:-18183}/actuator/health/readiness"
wait_http serial-registry "http://${BIND}:${WMS_SERIAL_HOST_PORT:-18184}/actuator/health/readiness"
wait_http fulfillment "http://${BIND}:${WMS_FULFILLMENT_HOST_PORT:-18185}/actuator/health/readiness"
wait_http console "http://${BIND}:${WMS_CONSOLE_HOST_PORT:-18180}/"

echo
echo "本地入口（仅本机，健康 UP 不代表业务验收）："
echo "  console      http://${BIND}:${WMS_CONSOLE_HOST_PORT:-18180}/"
echo "  inbound      http://${BIND}:${WMS_INBOUND_HOST_PORT:-18181}/actuator/health"
echo "  outbound     http://${BIND}:${WMS_OUTBOUND_HOST_PORT:-18182}/actuator/health"
echo "  inventory    http://${BIND}:${WMS_INVENTORY_HOST_PORT:-18183}/actuator/health"
echo "  serial       http://${BIND}:${WMS_SERIAL_HOST_PORT:-18184}/actuator/health"
echo "  fulfillment  http://${BIND}:${WMS_FULFILLMENT_HOST_PORT:-18185}/actuator/health"
echo "  seata        ${BIND}:${WMS_SEATA_HOST_PORT:-18091}  console ${BIND}:${WMS_SEATA_CONSOLE_HOST_PORT:-17091}"
echo "  xxl-admin    http://${BIND}:${WMS_XXL_ADMIN_HOST_PORT:-18080}/"
echo "OIDC 未配置时业务 HTTP 拒绝，控制台停在登录/配置态。停止：deploy/down.sh"
