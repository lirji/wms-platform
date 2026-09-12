#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
python3 scripts/generate-openapi.py
git diff --exit-code -- wms-contract/src/main/resources/openapi/wms-v1.yaml
python3 - <<'PY'
from pathlib import Path
text = Path("wms-contract/src/main/resources/openapi/wms-v1.yaml").read_text()
required = "    ActionEffectRequest:\n      type: object\n      additionalProperties: false\n      required: [factType, factParentId, factPartId, factLineId, action]"
if required not in text:
    raise SystemExit("ActionEffectRequest 必填集必须保持 N-1 兼容，digestVersion 不得变成必填")
if "        digestVersion: { $ref: '#/components/schemas/Version' }" not in text:
    raise SystemExit("digestVersion 必须作为可选字段存在")
print("verify-contracts: OpenAPI 已提交且 ActionEffectRequest 保持 N/N-1 可选扩展")
PY
