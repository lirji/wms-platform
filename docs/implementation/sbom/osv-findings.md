# OSV 扫描快照

由 `scripts/generate-sbom.sh` 根据 CycloneDX BOM 查询 [OSV](https://osv.dev)。
这是一次有日期的只读快照，不是生产漏洞签署，也不自动升级依赖。

- 扫描日期：2026-09-11
- 组件数：75
- 查询 purl 数：67
- 命中记录：1

## 许可证计数（BOM 声明）

- Apache-2.0: 68
- EPL-2.0: 3
- LGPL-2.1-only: 2
- MIT: 2
- GPL-2.0-with-classpath-exception: 1
- CC0-1.0: 1
- BSD-2-Clause: 1
- The GNU General Public License, v2 with Universal FOSS Exception, v1.0: 1

## 命中

- `pkg:maven/org.apache.tomcat.embed/tomcat-embed-core@11.0.24?type=jar`: GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5（上游修复线 11.0.25；本仓库未因此 bump Spring Boot）
