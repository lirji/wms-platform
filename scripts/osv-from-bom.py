#!/usr/bin/env python3
"""从 CycloneDX BOM 查询 OSV；记录发现，不把无 CVE 当成生产签署。"""
import json
import urllib.request
from collections import Counter
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BOM = ROOT / "docs/implementation/sbom/wms-platform.json"
OUT = ROOT / "docs/implementation/sbom/osv-findings.md"


def main():
    bom = json.loads(BOM.read_text())
    components = bom.get("components") or []
    licenses = Counter()
    queries = []
    for component in components:
        name = component.get("name")
        version = component.get("version")
        purl = component.get("purl")
        for license_block in component.get("licenses") or []:
            license_id = (license_block.get("license") or {}).get("id") or (license_block.get("license") or {}).get("name")
            if license_id:
                licenses[license_id] += 1
        if purl and version and "SNAPSHOT" not in version and not purl.startswith("pkg:maven/com.lrj.wms/"):
            queries.append({"purl": purl})
    unique = []
    seen = set()
    for query in queries:
        if query["purl"] in seen:
            continue
        seen.add(query["purl"])
        unique.append(query)
    vulns = []
    for start in range(0, len(unique), 80):
        batch = unique[start:start + 80]
        request = urllib.request.Request(
            "https://api.osv.dev/v1/querybatch",
            data=json.dumps({"queries": [{"package": {"purl": item["purl"]}} for item in batch]}).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = json.loads(response.read().decode())
        except urllib.error.HTTPError as error:
            lines_error = error.read().decode()[:500]
            OUT.write_text(
                "# OSV 扫描快照\n\n"
                "BOM 已生成，但 OSV querybatch 返回 "
                f"{error.code}。错误摘要：{lines_error}\n"
                "不能把本次失败当成无漏洞。请重跑 `python3 scripts/osv-from-bom.py`。\n"
            )
            raise SystemExit(f"OSV query failed: {error.code}")
        for query, result in zip(batch, payload.get("results") or []):
            hits = result.get("vulns") or []
            if hits:
                vulns.append((query["purl"], [item.get("id") for item in hits]))
    lines = [
        "# OSV 扫描快照",
        "",
        "由 `scripts/generate-sbom.sh` 根据 CycloneDX BOM 查询 [OSV](https://osv.dev)。",
        "这是一次有日期的只读快照，不是生产漏洞签署，也不自动升级依赖。",
        "",
        f"- 扫描日期：{date.today().isoformat()}",
        f"- 组件数：{len(components)}",
        f"- 查询 purl 数：{len(unique)}",
        f"- 命中记录：{len(vulns)}",
        "",
        "## 许可证计数（BOM 声明）",
        "",
    ]
    for name, count in licenses.most_common():
        lines.append(f"- {name}: {count}")
    lines.extend(["", "## 命中", ""])
    if not vulns:
        lines.append("本次查询没有返回 OSV 命中。不能据此声称供应链无风险或已锁定生产版本。")
    else:
        for purl, ids in vulns:
            lines.append(f"- `{purl}`: {', '.join(ids)}")
    OUT.write_text("\n".join(lines) + "\n")
    print(f"wrote {OUT} licenses={len(licenses)} vulns={len(vulns)}")


if __name__ == "__main__":
    main()
