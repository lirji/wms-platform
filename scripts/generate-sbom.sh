#!/usr/bin/env bash
# 生成候选 SBOM 与许可证清单。不是生产锁定，不部署。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p docs/implementation/sbom
./mvnw -B -ntp -Psbom -DskipTests -DskipITs verify
python3 scripts/osv-from-bom.py
echo "SBOM artifacts under docs/implementation/sbom/"
