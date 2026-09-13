#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib/common.sh"
load_config
require_isolated_targets

echo "======================================"
echo " Initializing Full-Link Test Data"
echo "======================================"

echo "[1/5] 依赖检查"
if ! command -v mysql >/dev/null 2>&1 && ! command -v docker >/dev/null 2>&1; then
  echo "需要 mysql 客户端或 docker，以便连接隔离 MySQL。" >&2
  exit 1
fi
if [[ ! -x "${PROJECT_ROOT}/mvnw" ]]; then
  echo "缺少 ${PROJECT_ROOT}/mvnw" >&2
  exit 1
fi

echo "[2/5] 检查隔离库"
require_mysql_ready

echo "[3/5] 复用官方 SeedLocal / SeedInbound / SeedOutbound / SeedFulfillment"
# 与 scripts/seed-local.sh 同一入口类。Cell B 若已有表但无时区记录，官方种子会 fail-closed；
# 不编造 legacy-evidence，跳过 Cell B 并记 BLOCKER。运行中的 inventory 只接 Cell A。
echo "  install seed modules"
"${PROJECT_ROOT}/mvnw" -f "${PROJECT_ROOT}/pom.xml" -pl wms-inventory,wms-inbound,wms-outbound,wms-fulfillment -am -q -DskipTests install

seed_exec() {
  local module="$1"
  local main="$2"
  local jdbc="$3"
  local user="$4"
  local password="$5"
  local warehouses="${6:-}"
  echo "  seed ${module} -> ${jdbc%%\?*}"
  if [[ -n "${warehouses}" ]]; then
    WMS_SEED_JDBC_URL="${jdbc}" WMS_SEED_DB_USER="${user}" WMS_SEED_DB_PASSWORD="${password}" \
      WMS_SEED_WAREHOUSES="${warehouses}" \
      "${PROJECT_ROOT}/mvnw" -f "${PROJECT_ROOT}/pom.xml" -pl "${module}" -q -DskipTests exec:java \
        -Dexec.mainClass="${main}"
  else
    WMS_SEED_JDBC_URL="${jdbc}" WMS_SEED_DB_USER="${user}" WMS_SEED_DB_PASSWORD="${password}" \
      "${PROJECT_ROOT}/mvnw" -f "${PROJECT_ROOT}/pom.xml" -pl "${module}" -q -DskipTests exec:java \
        -Dexec.mainClass="${main}"
  fi
}

seed_exec wms-inventory com.lrj.wms.inventory.masterdata.SeedLocal \
  "${WMS_INVENTORY_A_JDBC_URL}" "${WMS_INVENTORY_A_DB_USER}" "${WMS_INVENTORY_A_DB_PASSWORD}" \
  "WH-A"

CELL_B_SEED="SUCCESS"
if ! seed_exec wms-inventory com.lrj.wms.inventory.masterdata.SeedLocal \
  "${WMS_INVENTORY_B_JDBC_URL}" "${WMS_INVENTORY_B_DB_USER}" "${WMS_INVENTORY_B_DB_PASSWORD}" \
  "WH-B"; then
  CELL_B_SEED="BLOCKED"
  echo "BLOCKER  Cell B wms_inventory 已有表但缺少时区来源记录。"
  echo "  文件: wms-runtime/.../DatabaseTimePolicy.java initialize()"
  echo "  原因: 旧库必须已核实的 WMS_RUNTIME_DB_TIME_LEGACY_EVIDENCE，脚本不得编造。"
  echo "  影响: WH-B 主数据/开账库存未写入 Cell B。根 Compose 库存进程只接 Cell A，WH-A 链路不受阻。"
  echo "  建议: 由环境负责人按 DATABASE_TIME.md 补齐 Cell B 时区记录后，再跑 scripts/seed-local.sh。"
fi

seed_exec wms-inbound com.lrj.wms.inbound.seed.SeedInbound \
  "${WMS_INBOUND_JDBC_URL}" "${WMS_INBOUND_DB_USER}" "${WMS_INBOUND_DB_PASSWORD}"
seed_exec wms-outbound com.lrj.wms.outbound.seed.SeedOutbound \
  "${WMS_OUTBOUND_JDBC_URL}" "${WMS_OUTBOUND_DB_USER}" "${WMS_OUTBOUND_DB_PASSWORD}"
seed_exec wms-fulfillment com.lrj.wms.fulfillment.seed.SeedFulfillment \
  "${WMS_FULFILLMENT_JDBC_URL}" "${WMS_FULFILLMENT_DB_USER}" "${WMS_FULFILLMENT_DB_PASSWORD}"
export CELL_B_SEED

echo "[4/5] 写入 TESTFL 补充场景"
apply_sql_file inbound "${SCRIPT_DIR}/mysql/inbound-scenarios.sql"
apply_sql_file outbound "${SCRIPT_DIR}/mysql/outbound-scenarios.sql"
apply_sql_file fulfillment "${SCRIPT_DIR}/mysql/fulfillment-scenarios.sql"

echo "[5/5] 验证"
bash "${SCRIPT_DIR}/verify-test-data.sh"

echo
echo "========================================"
echo " Test Data Initialization Completed"
echo "========================================"
echo
echo "Project:"
echo "  wms-platform"
echo
echo "Detected Stack:"
echo "  Java 21 / Spring Boot / MyBatis / Flyway"
echo "  MySQL 8.4 (apps + inventory cell A/B)"
echo "  Redis / Kafka / Seata / XXL-JOB 已在 compose 中，本脚本不预写缓存或消息"
echo
echo "Initialized:"
echo "  Database Cell A SUCCESS"
echo "  Database Cell B ${CELL_B_SEED:-UNKNOWN}"
echo "  Cache         SKIPPED  (展示缓存由库存读路径生成)"
echo "  MessageQueue  SKIPPED  (默认 WMS_*_MESSAGING_ENABLED=false)"
echo "  Mock          SKIPPED  (OIDC 为外部身份，仓库无 WireMock)"
echo "  SerialRegistry SKIPPED (SeedCatalog 明确不写无登记身份的序列号库存)"
echo
echo "Enterprise: ${ENTERPRISE_ID}"
echo "Owner:      ${OWNER_ID}"
echo "Warehouses: ${WAREHOUSE_A} / ${WAREHOUSE_B}"
echo "SKUs:       SKU-STD, SKU-LOT, SKU-SN, SKU-NEAR, SKU-EXPIRED"
echo
echo "Scenarios:"
echo "  SCENE_001  Standard inbound/outbound/fulfillment demo"
echo "  SCENE_002  Near-expiry + expired HOLD stock"
echo "  SCENE_003  Inbound receiving in progress"
echo "  SCENE_004  Serial SKU with no opening stock + TESTFL ASN"
echo "  SCENE_005  Outbound pending authorization"
echo "  SCENE_006  Fulfillment attempt past deadline"
echo "  SCENE_007  Draft count plan"
echo "  SCENE_008  Idempotent replay of this script"
echo
echo "Key IDs:"
echo "  INB-DEMO-OPEN-A          inbound APPROVED"
echo "  INB-DEMO-RCV-A           inbound RECEIVING (8/20)"
echo "  TESTFL-INB-SN-A          inbound APPROVED SKU-SN"
echo "  TESTFL-INB-LATE-A        inbound expected_at in the past"
echo "  OB-DEMO-ALLOC-A          outbound ALLOCATED (单据状态，非 TCC 成功)"
echo "  TESTFL-OB-PENDING-A      outbound PENDING_AUTHORIZATION"
echo "  FF-DEMO-PLANNED          fulfillment OPEN + attempt PLANNED"
echo "  TESTFL-FF-EXPIRED        fulfillment attempt deadline passed"
echo "  TR-DEMO-AB               transfer OPEN WH-A -> WH-B"
echo "  BAL-WH-A-STD-STO-GOOD    on_hand 120 GOOD"
echo "  CNT-DEMO-DRAFT-WH-A      count DRAFT"
echo
echo "Operators from seed grants:"
echo "  wms-wh-a / wms-wh-b / wms-ops  (JWT subject，不是数据库账号)"
echo
echo "Cleanup:"
echo "  bash test-data/cleanup-test-data.sh"
echo
print_volume_summary
