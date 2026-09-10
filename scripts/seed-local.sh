#!/usr/bin/env bash
# 仅向调用方显式给出的隔离测试库写入幂等种子。拒绝共享 dev-infra，不默认任何 JDBC。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROFILE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      PROFILE="${2:-}"
      shift 2
      ;;
    *)
      echo "未知参数: $1" >&2
      exit 1
      ;;
  esac
done

if [[ "${PROFILE}" != "isolated-wms" ]]; then
  echo "只支持 --profile isolated-wms" >&2
  exit 1
fi

require_var() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "缺少环境变量 ${name}；拒绝默认共享库" >&2
    exit 1
  fi
}

reject_shared() {
  local jdbc="$1"
  local lower
  lower="$(printf '%s' "${jdbc}" | tr '[:upper:]' '[:lower:]')"
  if [[ "${lower}" == *43306* || "${lower}" == *dev-infra* || "${lower}" == *dev_infra* ]]; then
    echo "拒绝共享dev-infra数据库" >&2
    exit 1
  fi
  if [[ "${lower}" != *wms_inventory* ]]; then
    echo "必须显式指向wms_inventory测试库" >&2
    exit 1
  fi
}

require_var WMS_INVENTORY_A_JDBC_URL
require_var WMS_INVENTORY_A_DB_USER
require_var WMS_INVENTORY_A_DB_PASSWORD
require_var WMS_INVENTORY_B_JDBC_URL
require_var WMS_INVENTORY_B_DB_USER
require_var WMS_INVENTORY_B_DB_PASSWORD
reject_shared "${WMS_INVENTORY_A_JDBC_URL}"
reject_shared "${WMS_INVENTORY_B_JDBC_URL}"

seed_cell() {
  local jdbc="$1"
  local user="$2"
  local password="$3"
  local warehouses="$4"
  echo "seed ${warehouses} -> ${jdbc%%\?*}"
  WMS_SEED_JDBC_URL="${jdbc}" \
    WMS_SEED_DB_USER="${user}" \
    WMS_SEED_DB_PASSWORD="${password}" \
    WMS_SEED_WAREHOUSES="${warehouses}" \
    "${ROOT}/mvnw" -f "${ROOT}/pom.xml" -pl wms-inventory -am -q -DskipTests exec:java \
      -Dexec.mainClass=com.lrj.wms.inventory.masterdata.SeedLocal
}

seed_cell "${WMS_INVENTORY_A_JDBC_URL}" "${WMS_INVENTORY_A_DB_USER}" "${WMS_INVENTORY_A_DB_PASSWORD}" "WH-A"
seed_cell "${WMS_INVENTORY_B_JDBC_URL}" "${WMS_INVENTORY_B_DB_USER}" "${WMS_INVENTORY_B_DB_PASSWORD}" "WH-B"
echo "seed-local isolated-wms 完成"
