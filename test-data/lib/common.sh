# 由 init/cleanup/verify 共同加载。不单独执行。
# 连接信息只来自仓库 .env / test-data.env / 进程环境，拒绝共享 dev-infra。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ENTERPRISE_ID="ENT-DEMO"
OWNER_ID="OWNER-SELF"
WAREHOUSE_A="WH-A"
WAREHOUSE_B="WH-B"
TEST_PREFIX="TESTFL-"

# 只接受 KEY=VALUE。等号后全部当值，避免 .env 里未加引号的中文/空格被 bash source 执行。
load_env_file() {
  local file="$1"
  [[ -f "${file}" ]] || return 0
  local line key val
  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="${line%$'\r'}"
    [[ -z "${line}" || "${line}" =~ ^[[:space:]]*# ]] && continue
    [[ "${line}" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    val="${BASH_REMATCH[2]}"
    if [[ "${val}" =~ ^\"(.*)\"$ ]]; then
      val="${BASH_REMATCH[1]}"
    elif [[ "${val}" =~ ^\'(.*)\'$ ]]; then
      val="${BASH_REMATCH[1]}"
    fi
    printf -v "${key}" '%s' "${val}"
    export "${key}"
  done <"${file}"
}

load_config() {
  load_env_file "${PROJECT_ROOT}/.env"
  load_env_file "${SCRIPT_DIR}/config/test-data.env"

  WMS_BIND_ADDRESS="${WMS_BIND_ADDRESS:-127.0.0.1}"
  MYSQL_APPS_HOST="${MYSQL_APPS_HOST:-${WMS_BIND_ADDRESS}}"
  MYSQL_APPS_PORT="${MYSQL_APPS_PORT:-${WMS_MYSQL_APPS_HOST_PORT:-18306}}"
  MYSQL_CELL_A_HOST="${MYSQL_CELL_A_HOST:-${WMS_BIND_ADDRESS}}"
  MYSQL_CELL_A_PORT="${MYSQL_CELL_A_PORT:-${WMS_MYSQL_CELL_A_HOST_PORT:-18307}}"
  MYSQL_CELL_B_HOST="${MYSQL_CELL_B_HOST:-${WMS_BIND_ADDRESS}}"
  MYSQL_CELL_B_PORT="${MYSQL_CELL_B_PORT:-${WMS_MYSQL_CELL_B_HOST_PORT:-18308}}"

  WMS_INBOUND_DB_USER="${WMS_INBOUND_DB_USER:-wms_inbound}"
  WMS_OUTBOUND_DB_USER="${WMS_OUTBOUND_DB_USER:-wms_outbound}"
  WMS_FULFILLMENT_DB_USER="${WMS_FULFILLMENT_DB_USER:-wms_fulfillment}"
  WMS_INVENTORY_A_DB_USER="${WMS_INVENTORY_A_DB_USER:-wms_inventory_a}"
  WMS_INVENTORY_B_DB_USER="${WMS_INVENTORY_B_DB_USER:-wms_inventory_b}"

  WMS_INBOUND_JDBC_URL="${WMS_INBOUND_JDBC_URL:-jdbc:mysql://${MYSQL_APPS_HOST}:${MYSQL_APPS_PORT}/wms_inbound}"
  WMS_OUTBOUND_JDBC_URL="${WMS_OUTBOUND_JDBC_URL:-jdbc:mysql://${MYSQL_APPS_HOST}:${MYSQL_APPS_PORT}/wms_outbound}"
  WMS_FULFILLMENT_JDBC_URL="${WMS_FULFILLMENT_JDBC_URL:-jdbc:mysql://${MYSQL_APPS_HOST}:${MYSQL_APPS_PORT}/wms_fulfillment}"
  WMS_INVENTORY_A_JDBC_URL="${WMS_INVENTORY_A_JDBC_URL:-jdbc:mysql://${MYSQL_CELL_A_HOST}:${MYSQL_CELL_A_PORT}/wms_inventory}"
  WMS_INVENTORY_B_JDBC_URL="${WMS_INVENTORY_B_JDBC_URL:-jdbc:mysql://${MYSQL_CELL_B_HOST}:${MYSQL_CELL_B_PORT}/wms_inventory}"

  WMS_INBOUND_BASE_URL="${WMS_INBOUND_BASE_URL:-http://${WMS_BIND_ADDRESS}:${WMS_INBOUND_HOST_PORT:-18181}}"
  WMS_OUTBOUND_BASE_URL="${WMS_OUTBOUND_BASE_URL:-http://${WMS_BIND_ADDRESS}:${WMS_OUTBOUND_HOST_PORT:-18182}}"
  WMS_INVENTORY_BASE_URL="${WMS_INVENTORY_BASE_URL:-http://${WMS_BIND_ADDRESS}:${WMS_INVENTORY_HOST_PORT:-18183}}"
  WMS_FULFILLMENT_BASE_URL="${WMS_FULFILLMENT_BASE_URL:-http://${WMS_BIND_ADDRESS}:${WMS_FULFILLMENT_HOST_PORT:-18185}}"
  WMS_TEST_WAREHOUSE_ID="${WMS_TEST_WAREHOUSE_ID:-${WAREHOUSE_A}}"

  export WMS_INBOUND_JDBC_URL WMS_INBOUND_DB_USER WMS_INBOUND_DB_PASSWORD
  export WMS_OUTBOUND_JDBC_URL WMS_OUTBOUND_DB_USER WMS_OUTBOUND_DB_PASSWORD
  export WMS_FULFILLMENT_JDBC_URL WMS_FULFILLMENT_DB_USER WMS_FULFILLMENT_DB_PASSWORD
  export WMS_INVENTORY_A_JDBC_URL WMS_INVENTORY_A_DB_USER WMS_INVENTORY_A_DB_PASSWORD
  export WMS_INVENTORY_B_JDBC_URL WMS_INVENTORY_B_DB_USER WMS_INVENTORY_B_DB_PASSWORD
}

require_var() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "缺少 ${name}。从仓库根目录 .env 或 test-data/config/test-data.env 注入，拒绝默认口令。" >&2
    exit 1
  fi
}

reject_shared_jdbc() {
  local jdbc="$1"
  local db="$2"
  local lower
  lower="$(printf '%s' "${jdbc}" | tr '[:upper:]' '[:lower:]')"
  if [[ "${lower}" == *43306* || "${lower}" == *dev-infra* || "${lower}" == *dev_infra* ]]; then
    echo "拒绝共享 dev-infra 数据库: ${jdbc}" >&2
    exit 1
  fi
  if [[ "${lower}" != *"${db}"* ]]; then
    echo "连接串必须显式指向 ${db}: ${jdbc}" >&2
    exit 1
  fi
}

require_isolated_targets() {
  require_var WMS_INBOUND_DB_PASSWORD
  require_var WMS_OUTBOUND_DB_PASSWORD
  require_var WMS_FULFILLMENT_DB_PASSWORD
  require_var WMS_INVENTORY_A_DB_PASSWORD
  require_var WMS_INVENTORY_B_DB_PASSWORD
  reject_shared_jdbc "${WMS_INBOUND_JDBC_URL}" "wms_inbound"
  reject_shared_jdbc "${WMS_OUTBOUND_JDBC_URL}" "wms_outbound"
  reject_shared_jdbc "${WMS_FULFILLMENT_JDBC_URL}" "wms_fulfillment"
  reject_shared_jdbc "${WMS_INVENTORY_A_JDBC_URL}" "wms_inventory"
  reject_shared_jdbc "${WMS_INVENTORY_B_JDBC_URL}" "wms_inventory"
}

compose_cmd() {
  if [[ -f "${PROJECT_ROOT}/.env" ]]; then
    docker compose -p wms-local -f "${PROJECT_ROOT}/deploy/compose.local.yml" --env-file "${PROJECT_ROOT}/.env" "$@"
  else
    docker compose -p wms-local -f "${PROJECT_ROOT}/deploy/compose.local.yml" "$@"
  fi
}

mysql_via_docker() {
  local service="$1"
  shift
  if ! command -v docker >/dev/null 2>&1; then
    return 1
  fi
  compose_cmd exec -T -e "MYSQL_PWD=${MYSQL_PWD:-}" "${service}" mysql --protocol=tcp -h127.0.0.1 -P3306 "$@"
}

run_mysql() {
  local role="$1"
  shift
  local host port user password database service
  case "${role}" in
    inbound)
      host="${MYSQL_APPS_HOST}"; port="${MYSQL_APPS_PORT}"
      user="${WMS_INBOUND_DB_USER}"; password="${WMS_INBOUND_DB_PASSWORD}"
      database="wms_inbound"; service="mysql-apps"
      ;;
    outbound)
      host="${MYSQL_APPS_HOST}"; port="${MYSQL_APPS_PORT}"
      user="${WMS_OUTBOUND_DB_USER}"; password="${WMS_OUTBOUND_DB_PASSWORD}"
      database="wms_outbound"; service="mysql-apps"
      ;;
    fulfillment)
      host="${MYSQL_APPS_HOST}"; port="${MYSQL_APPS_PORT}"
      user="${WMS_FULFILLMENT_DB_USER}"; password="${WMS_FULFILLMENT_DB_PASSWORD}"
      database="wms_fulfillment"; service="mysql-apps"
      ;;
    inventory-a)
      host="${MYSQL_CELL_A_HOST}"; port="${MYSQL_CELL_A_PORT}"
      user="${WMS_INVENTORY_A_DB_USER}"; password="${WMS_INVENTORY_A_DB_PASSWORD}"
      database="wms_inventory"; service="mysql-cell-a"
      ;;
    inventory-b)
      host="${MYSQL_CELL_B_HOST}"; port="${MYSQL_CELL_B_PORT}"
      user="${WMS_INVENTORY_B_DB_USER}"; password="${WMS_INVENTORY_B_DB_PASSWORD}"
      database="wms_inventory"; service="mysql-cell-b"
      ;;
    *)
      echo "未知 MySQL 角色: ${role}" >&2
      exit 1
      ;;
  esac

  # 项目运行方式优先：Compose exec > 宿主机 mysql。
  if MYSQL_PWD="${password}" mysql_via_docker "${service}" -u"${user}" "${database}" "$@"; then
    return
  fi
  if command -v mysql >/dev/null 2>&1; then
    MYSQL_PWD="${password}" mysql --protocol=tcp -h"${host}" -P"${port}" -u"${user}" "${database}" "$@"
    return
  fi
  echo "无法通过 deploy/compose.local.yml exec ${service}，宿主机也没有 mysql。先启动隔离中间件或安装客户端。" >&2
  exit 1
}

apply_sql_file() {
  local role="$1"
  local file="$2"
  echo "  SQL ${role} <- $(basename "${file}")"
  run_mysql "${role}" <"${file}"
}

mysql_scalar() {
  local role="$1"
  local sql="$2"
  run_mysql "${role}" -N -s -e "${sql}"
}

require_mysql_ready() {
  echo "[check] MySQL 隔离库连通"
  mysql_scalar inbound "SELECT 1" >/dev/null
  mysql_scalar outbound "SELECT 1" >/dev/null
  mysql_scalar fulfillment "SELECT 1" >/dev/null
  mysql_scalar inventory-a "SELECT 1" >/dev/null
  mysql_scalar inventory-b "SELECT 1" >/dev/null
}

# 只统计 ENT-DEMO / TESTFL，打印实测条数，不写估算。
print_volume_summary() {
  local warehouses skus locations lots balances plans
  local inbound_orders inbound_lines inbound_testfl
  local outbound_orders outbound_lines outbound_testfl
  local fulfillments attempts transfers fulfill_testfl
  warehouses="$(mysql_scalar inventory-a "SELECT COUNT(*) FROM warehouse WHERE enterprise_id='ENT-DEMO'")"
  skus="$(mysql_scalar inventory-a "SELECT COUNT(*) FROM sku WHERE enterprise_id='ENT-DEMO'")"
  locations="$(mysql_scalar inventory-a "SELECT COUNT(*) FROM location WHERE enterprise_id='ENT-DEMO'")"
  lots="$(mysql_scalar inventory-a "SELECT COUNT(*) FROM lot WHERE enterprise_id='ENT-DEMO'")"
  balances="$(mysql_scalar inventory-a "SELECT COUNT(*) FROM stock_balance WHERE enterprise_id='ENT-DEMO'")"
  plans="$(mysql_scalar inventory-a "SELECT COUNT(*) FROM count_plan WHERE enterprise_id='ENT-DEMO'")"
  inbound_orders="$(mysql_scalar inbound "SELECT COUNT(*) FROM inbound_order WHERE enterprise_id='ENT-DEMO'")"
  inbound_lines="$(mysql_scalar inbound "SELECT COUNT(*) FROM inbound_line WHERE enterprise_id='ENT-DEMO'")"
  inbound_testfl="$(mysql_scalar inbound "SELECT COUNT(*) FROM inbound_order WHERE id LIKE 'TESTFL-%'")"
  outbound_orders="$(mysql_scalar outbound "SELECT COUNT(*) FROM outbound_order WHERE enterprise_id='ENT-DEMO'")"
  outbound_lines="$(mysql_scalar outbound "SELECT COUNT(*) FROM outbound_line WHERE enterprise_id='ENT-DEMO'")"
  outbound_testfl="$(mysql_scalar outbound "SELECT COUNT(*) FROM outbound_order WHERE id LIKE 'TESTFL-%'")"
  fulfillments="$(mysql_scalar fulfillment "SELECT COUNT(*) FROM fulfillment_order WHERE enterprise_id='ENT-DEMO'")"
  attempts="$(mysql_scalar fulfillment "SELECT COUNT(*) FROM allocation_attempt WHERE enterprise_id='ENT-DEMO'")"
  transfers="$(mysql_scalar fulfillment "SELECT COUNT(*) FROM transfer_order WHERE enterprise_id='ENT-DEMO'")"
  fulfill_testfl="$(mysql_scalar fulfillment "SELECT COUNT(*) FROM fulfillment_order WHERE id LIKE 'TESTFL-%'")"
  local business=$((warehouses + skus + inbound_orders + outbound_orders + fulfillments + transfers + balances + plans))

  echo "========================================"
  echo " Test Data Summary"
  echo "========================================"
  echo "Warehouses:        ${warehouses}  (Cell A ENT-DEMO)"
  echo "SKUs:              ${skus}"
  echo "Locations:         ${locations}"
  echo "Lots:              ${lots}"
  echo "Stock balances:    ${balances}"
  echo "Count plans:       ${plans}"
  echo "Inbound orders:    ${inbound_orders}  (lines ${inbound_lines}, TESTFL ${inbound_testfl})"
  echo "Outbound orders:   ${outbound_orders}  (lines ${outbound_lines}, TESTFL ${outbound_testfl})"
  echo "Fulfillments:      ${fulfillments}  (attempts ${attempts}, TESTFL ${fulfill_testfl})"
  echo "Transfers:         ${transfers}"
  echo
  echo "Business records:  ${business}"
  echo "  (仓+SKU+入库单+出库单+履约单+调拨单+余额+盘点；不含库位/批次/行/授权映射)"
  echo "Scenarios:         8"
  echo "========================================"
}
