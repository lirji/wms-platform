# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S1基础资料与安全（S1-05 本轮实现；S1-01..04 已在 main；S0 工程门禁仍 running）。
- 用户已批准补齐执行门禁并推进业务开发，沿用[唯一计划](DELIVERY_PLAN.md)。本会话要求继续，从 S1-05 未完成门禁执行。前后端均由当前实施者负责；未开始`wms-console/`。
- 仅操作 wms-platform 隔离工作树 `.local/s1-masterdata` 与 auth-platform 隔离工作树 `.local/auth-wms-oidc`；不部署生产，不修改共享 dev-infra，不触碰原目录 ADR-11 脏文件与 auth-platform 未提交 IAM 改动。
- 分支：feat/wms-s1-05（WMS）。auth-platform 开通脚本沿用已发布 main，本轮只运行不开新提交。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；用户「继续」从 S1-05 执行；先前「直接做 S1-04 种子，OIDC你自己找auth-platform处理」；持续 Git 发布授权。
- 本轮允许：S1-05 测试身份越权/单位/效期/种子复跑可观测接口与测试、本机 Casdoor 开通（不改 auth-platform 源码）、相关文档、任务分支提交并快进远程 main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11；本机 Casdoor `:8000`。
- 排除：生产部署、共享 dev-infra、SpiceDB/ReBAC、`wms-console/`、ADR-11 实现、正式 fulfillment 模块、编造 OQ-03 生产默认值、无 `.env` 时强行起隔离 compose。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | 本轮远程 CI verify #34489970301 成功（6m44s）。S0 组合门禁与 SBOM 仍未关闭 |
| EG-02 TC组合/唯一TM | running | 沿用 S0 探针；正式`wms-fulfillment`仍是S4 |
| EG-03 业务决定 | running | 本地测试 IdP=auth-platform Casdoor；生产 IdP 未锁。OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5退出必选，尚未实现 |
| EG-05 外部与非功能 | pending | S8/S9执行 |
| Git发布 | running | S1-05 待提交 `feat/wms-s1-05` 并快进远程 main；无生产部署 |

## 本轮已实现（S1-05）

- GET `/api/wms/v1/warehouses/{warehouseId}/lots`：JWT 仓范围，越仓 403 `WAREHOUSE_FORBIDDEN`；临期/过期批次返回显式 RFC3339 UTC，不出现编造的 `2026-09-10T00:00:00Z`。
- GET `/api/wms/v1/skus/{skuId}/units`：企业范围；SKU-LOT 含 CS/`12`/`1`；缺失 SKU 404 `SKU_NOT_FOUND`。
- 空仓范围身份：仓库列表不含 WH-A/WH-B，读库位 403。ops CSV `WH-A,WH-B` 可见两仓。
- 种子复跑：2 仓 / 5 SKU / 6 sku_unit / 6 lot / 8 grant；CS 12:1；LOT-NEAR `2026-09-17T13:00:00Z`，LOT-EXP `2026-09-09T13:00:00Z`，LOT-STD 无生产/失效时刻。
- 本机 Casdoor 开通完成（issuer `http://localhost:8000`，client `wms-platform`）。未起隔离 compose，故 Casdoor JWT 打 inventory 进程仍 blocked。

## 先前已实现（S1-02 / S1-04）

- `scripts/seed-local.sh --profile isolated-wms`：必须显式 Cell A/B JDBC；拒绝 43306/`dev-infra`；WH-A→A、WH-B→B，SKU 种子两边都写。
- `V002__operator_grant.sql`：种子权限映射，运行时仍以 JWT 仓范围为准。S2 库存事务迁移改用 V003。
- `wms-security`：issuer 空 denyAll；issuer 非空校验 JWT。`server.max-http-request-header-size: 64KB`。
- inventory 仅当 `WMS_INVENTORY_JDBC_URL` 非空时接库并 Flyway；GET warehouses/skus/locations；越仓 403 `WAREHOUSE_FORBIDDEN`。
- auth-platform `deploy/wms-platform-provision.py`：org/app `wms-platform`，用户 `wms-wh-a`/`wms-wh-b`/`wms-ops`/`wms-denied`，凭据 0600 文件。

## 未完成

- S1-06 事实身份运行时。隔离 compose 双 Cell 种子 + Casdoor JWT 打 inventory HTTP。
- AC-01/02/31 正式业务验收仍 planned。本轮有测试 JWT HTTP 与 Casdoor 开通证据，不是 50 项 AC 通过。
- 50 项业务 AC、S0 XXL 真触发、SBOM/CVE、`wms-console/`。

HTTP 写接口未交付。种子脚本不等于控制台。Casdoor 开通不等于隔离库存库已灌种子。

S1-04 发布合并 `a7f90c8` / 文档 `b617355` 已在远程 main；远程 CI verify #34489970301 成功。S1-05 Git 发布见本轮收尾。无生产部署。
