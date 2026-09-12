#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
scenario="${1:-}"
if [[ "$scenario" != "--scenario" ]]; then
  echo "用法: ./scripts/run-capacity.sh --scenario correctness|agreed-peak" >&2
  exit 2
fi
name="${2:-}"
if [[ "$name" == "correctness" ]]; then
  echo "S9-01 correctness 使用设计矩阵 100 件/200 并发，对应 InventoryConcurrencyIT；不是签署峰值。"
  cd "$root"
  exec ./mvnw -B -ntp -pl wms-inventory -am -Dsurefire.failIfNoSpecifiedTests=false \
    -Dfailsafe.failIfNoSpecifiedTests=false -Dtest=InventoryConcurrencyIT \
    -Dit.test=InventoryConcurrencyIT verify
fi
if [[ "$name" != "agreed-peak" ]]; then
  echo "未知场景: $name" >&2
  exit 2
fi
input="${WMS_CAPACITY_INPUT:-}"
if [[ -z "$input" || ! -f "$input" ]]; then
  echo "AC-27/S9-01 拒绝：缺少签署容量输入。请设置 WMS_CAPACITY_INPUT 指向含 signedBy/signedAt/D/L/P 的文件。不得使用文档合成例子冒充签署峰值。" >&2
  exit 2
fi
output="${WMS_CAPACITY_OUTPUT:-$root/.local/capacity/$(date -u +%Y%m%dT%H%M%SZ)}"
exec python3 "$root/scripts/capacity-runner.py" --input "$input" --output "$output"
