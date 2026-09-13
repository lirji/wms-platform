#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib/common.sh"
load_config
require_isolated_targets

echo "======================================"
echo " Cleaning TESTFL Test Data"
echo "======================================"

require_mysql_ready

echo "[cleanup] 按反向依赖删除 TESTFL 前缀"
apply_sql_file fulfillment "${SCRIPT_DIR}/mysql/cleanup-fulfillment.sql"
apply_sql_file outbound "${SCRIPT_DIR}/mysql/cleanup-outbound.sql"
apply_sql_file inbound "${SCRIPT_DIR}/mysql/cleanup-inbound.sql"

left_in="$(mysql_scalar inbound "SELECT COUNT(*) FROM inbound_order WHERE id LIKE 'TESTFL-%'")"
left_ob="$(mysql_scalar outbound "SELECT COUNT(*) FROM outbound_order WHERE id LIKE 'TESTFL-%'")"
left_ff="$(mysql_scalar fulfillment "SELECT COUNT(*) FROM fulfillment_order WHERE id LIKE 'TESTFL-%'")"

if [[ "${left_in}" != "0" || "${left_ob}" != "0" || "${left_ff}" != "0" ]]; then
  echo "TESTFL 清理未完成 inbound=${left_in} outbound=${left_ob} fulfillment=${left_ff}" >&2
  exit 1
fi

echo
echo "已删除 TESTFL 补充场景。"
echo "未删除 scripts/seed-local.sh 写入的 ENT-DEMO 主数据与 DEMO 单据。"
echo "未删除库存余额、仓、SKU。"
echo "======================================"
