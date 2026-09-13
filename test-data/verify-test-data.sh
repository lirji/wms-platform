#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib/common.sh"
load_config
require_isolated_targets

echo "======================================"
echo " Verifying Full-Link Test Data"
echo "======================================"

require_mysql_ready

run_checks() {
  local role="$1"
  local file="$2"
  local output
  output="$(run_mysql "${role}" -N -s <"${file}")"
  if [[ -z "${output}" ]]; then
    echo "  FAIL  ${file} 无结果" >&2
    return 1
  fi
  local failed=0
  while IFS=$'\t' read -r check_id result; do
    [[ -z "${check_id}" ]] && continue
    if [[ "${result}" == "PASS" ]]; then
      echo "  PASS  ${check_id}"
    else
      echo "  FAIL  ${check_id}" >&2
      failed=1
    fi
  done <<<"${output}"
  return "${failed}"
}

data_verify=0
run_checks inventory-a "${SCRIPT_DIR}/mysql/verify-inventory.sql" || data_verify=1
run_checks inbound "${SCRIPT_DIR}/mysql/verify-inbound.sql" || data_verify=1
run_checks outbound "${SCRIPT_DIR}/mysql/verify-outbound.sql" || data_verify=1
run_checks fulfillment "${SCRIPT_DIR}/mysql/verify-fulfillment.sql" || data_verify=1

if [[ "${data_verify}" -ne 0 ]]; then
  echo "DATA_VERIFY = FAIL" >&2
  exit 1
fi
echo "DATA_VERIFY = SUCCESS"

app_verify="SKIPPED"
if command -v curl >/dev/null 2>&1; then
  live=0
  for url in \
    "${WMS_INBOUND_BASE_URL}/actuator/health/liveness" \
    "${WMS_OUTBOUND_BASE_URL}/actuator/health/liveness" \
    "${WMS_INVENTORY_BASE_URL}/actuator/health/liveness" \
    "${WMS_FULFILLMENT_BASE_URL}/actuator/health/liveness"; do
    if curl -fsS --max-time 2 "${url}" >/dev/null 2>&1; then
      live=$((live + 1))
    fi
  done
  if [[ "${live}" -eq 0 ]]; then
    echo "APP_VERIFY = SKIPPED (应用未启动)"
  elif [[ -z "${WMS_TEST_BEARER_TOKEN:-}" ]]; then
    echo "APP_VERIFY = SKIPPED (应用存活 ${live}/4，缺 WMS_TEST_BEARER_TOKEN，业务接口拒绝匿名)"
  else
    warehouse="${WMS_TEST_WAREHOUSE_ID}"
    auth_header="Authorization: Bearer ${WMS_TEST_BEARER_TOKEN}"
    curl -fsS --max-time 5 -H "${auth_header}" \
      "${WMS_INBOUND_BASE_URL}/api/wms/v1/warehouses/${warehouse}/inbound-orders/INB-DEMO-OPEN-A" >/dev/null
    curl -fsS --max-time 5 -H "${auth_header}" \
      "${WMS_OUTBOUND_BASE_URL}/api/wms/v1/warehouses/${warehouse}/outbound-orders/OB-DEMO-ALLOC-A" >/dev/null
    curl -fsS --max-time 5 -H "${auth_header}" \
      "${WMS_INVENTORY_BASE_URL}/api/wms/v1/skus/SKU-STD" >/dev/null
    curl -fsS --max-time 5 -H "${auth_header}" \
      "${WMS_FULFILLMENT_BASE_URL}/api/wms/v1/fulfillments/FF-DEMO-PLANNED" >/dev/null
    echo "APP_VERIFY = SUCCESS"
    app_verify="SUCCESS"
  fi
else
  echo "APP_VERIFY = SKIPPED (无 curl)"
fi

echo "Verification:"
echo "  Data Integrity       PASS"
echo "  Business Conditions  PASS"
echo "  Application API      ${app_verify}"
echo "======================================"
