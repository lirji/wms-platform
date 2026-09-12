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
  local db="$2"
  local lower
  lower="$(printf '%s' "${jdbc}" | tr '[:upper:]' '[:lower:]')"
  if [[ "${lower}" == *43306* || "${lower}" == *dev-infra* || "${lower}" == *dev_infra* ]]; then
    echo "拒绝共享dev-infra数据库" >&2
    exit 1
  fi
  if [[ "${lower}" != *"${db}"* ]]; then
    echo "必须显式指向${db}测试库" >&2
    exit 1
  fi
}

require_var WMS_INVENTORY_A_JDBC_URL
require_var WMS_INVENTORY_A_DB_USER
require_var WMS_INVENTORY_A_DB_PASSWORD
require_var WMS_INVENTORY_B_JDBC_URL
require_var WMS_INVENTORY_B_DB_USER
require_var WMS_INVENTORY_B_DB_PASSWORD
require_var WMS_INBOUND_JDBC_URL
require_var WMS_INBOUND_DB_USER
require_var WMS_INBOUND_DB_PASSWORD
require_var WMS_OUTBOUND_JDBC_URL
require_var WMS_OUTBOUND_DB_USER
require_var WMS_OUTBOUND_DB_PASSWORD
require_var WMS_FULFILLMENT_JDBC_URL
require_var WMS_FULFILLMENT_DB_USER
require_var WMS_FULFILLMENT_DB_PASSWORD
reject_shared "${WMS_INVENTORY_A_JDBC_URL}" "wms_inventory"
reject_shared "${WMS_INVENTORY_B_JDBC_URL}" "wms_inventory"
reject_shared "${WMS_INBOUND_JDBC_URL}" "wms_inbound"
reject_shared "${WMS_OUTBOUND_JDBC_URL}" "wms_outbound"
reject_shared "${WMS_FULFILLMENT_JDBC_URL}" "wms_fulfillment"

# -am exec:java 会在父模块找不到入口类；先装到本地仓库，再按模块 exec。
"${ROOT}/mvnw" -f "${ROOT}/pom.xml" -pl wms-inventory,wms-inbound,wms-outbound,wms-fulfillment -am -q -DskipTests install

seed_exec() {
  local module="$1"
  local main="$2"
  local jdbc="$3"
  local user="$4"
  local password="$5"
  local warehouses="${6:-}"
  echo "seed ${module} -> ${jdbc%%\?*}"
  if [[ -n "${warehouses}" ]]; then
    WMS_SEED_JDBC_URL="${jdbc}" \
      WMS_SEED_DB_USER="${user}" \
      WMS_SEED_DB_PASSWORD="${password}" \
      WMS_SEED_WAREHOUSES="${warehouses}" \
      "${ROOT}/mvnw" -f "${ROOT}/pom.xml" -pl "${module}" -q -DskipTests exec:java \
        -Dexec.mainClass="${main}"
  else
    WMS_SEED_JDBC_URL="${jdbc}" \
      WMS_SEED_DB_USER="${user}" \
      WMS_SEED_DB_PASSWORD="${password}" \
      "${ROOT}/mvnw" -f "${ROOT}/pom.xml" -pl "${module}" -q -DskipTests exec:java \
        -Dexec.mainClass="${main}"
  fi
}

seed_exec wms-inventory com.lrj.wms.inventory.masterdata.SeedLocal \
  "${WMS_INVENTORY_A_JDBC_URL}" "${WMS_INVENTORY_A_DB_USER}" "${WMS_INVENTORY_A_DB_PASSWORD}" \
  "WH-A"
seed_exec wms-inventory com.lrj.wms.inventory.masterdata.SeedLocal \
  "${WMS_INVENTORY_B_JDBC_URL}" "${WMS_INVENTORY_B_DB_USER}" "${WMS_INVENTORY_B_DB_PASSWORD}" \
  "WH-B"
seed_exec wms-inbound com.lrj.wms.inbound.seed.SeedInbound \
  "${WMS_INBOUND_JDBC_URL}" "${WMS_INBOUND_DB_USER}" "${WMS_INBOUND_DB_PASSWORD}"
seed_exec wms-outbound com.lrj.wms.outbound.seed.SeedOutbound \
  "${WMS_OUTBOUND_JDBC_URL}" "${WMS_OUTBOUND_DB_USER}" "${WMS_OUTBOUND_DB_PASSWORD}"
seed_exec wms-fulfillment com.lrj.wms.fulfillment.seed.SeedFulfillment \
  "${WMS_FULFILLMENT_JDBC_URL}" "${WMS_FULFILLMENT_DB_USER}" "${WMS_FULFILLMENT_DB_PASSWORD}"
echo "seed-local isolated-wms 完成"
