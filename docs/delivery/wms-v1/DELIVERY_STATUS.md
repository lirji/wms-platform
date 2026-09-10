# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S1基础资料与安全（S1-02/S1-04 本轮实现；S1-01/S1-03 已在 main；S0 工程门禁仍 running）。
- 用户已批准补齐执行门禁并推进业务开发，沿用[唯一计划](DELIVERY_PLAN.md)。本会话要求直接做 S1-04 种子，并自行对接 auth-platform 处理 OIDC。前后端均由当前实施者负责；未开始`wms-console/`。
- 仅操作 wms-platform 隔离工作树 `.local/s1-masterdata` 与 auth-platform 隔离工作树 `.local/auth-wms-oidc`；不部署生产，不修改共享 dev-infra，不触碰原目录 ADR-11 脏文件与 auth-platform 未提交 IAM 改动。
- 分支：feat/wms-s1-seed（WMS）；feat/wms-oidc-provision（auth-platform）。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；用户「直接做 S1-04 种子，OIDC你自己找auth-platform处理」；持续 Git 发布授权。
- 本轮允许：S1-04 隔离种子、S1-02 Casdoor 资源服务器与 auth-platform 开通脚本、主数据只读 HTTP、相关测试与文档、任务分支提交并快进远程 main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11；现场 Casdoor 仅在本机 :8000 可达时开通。
- 排除：生产部署、共享 dev-infra、SpiceDB/ReBAC、`wms-console/`、ADR-11 实现、正式 fulfillment 模块、编造 OQ-03 生产默认值。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | 本轮远程 CI verify #34489970301 成功（6m44s）。S0 组合门禁与 SBOM 仍未关闭 |
| EG-02 TC组合/唯一TM | running | 沿用 S0 探针；正式`wms-fulfillment`仍是S4 |
| EG-03 业务决定 | running | 本地测试 IdP=auth-platform Casdoor；生产 IdP 未锁。OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5退出必选，尚未实现 |
| EG-05 外部与非功能 | pending | S8/S9执行 |
| Git发布 | passed（本轮实现） | `a7f90c8` 已推送任务分支和 main；远程包含性已核对；无生产部署 |

## 本轮已实现（S1-02 / S1-04）

- `scripts/seed-local.sh --profile isolated-wms`：必须显式 Cell A/B JDBC；拒绝 43306/`dev-infra`；WH-A→A、WH-B→B，SKU 种子两边都写。
- `V002__operator_grant.sql`：种子权限映射，运行时仍以 JWT 仓范围为准。S2 库存事务迁移改用 V003。
- `wms-security`：issuer 空 denyAll；issuer 非空校验 JWT。`server.max-http-request-header-size: 64KB`。
- inventory 仅当 `WMS_INVENTORY_JDBC_URL` 非空时接库并 Flyway；GET warehouses/skus/locations；越仓 403 `WAREHOUSE_FORBIDDEN`。
- auth-platform `deploy/wms-platform-provision.py`：org/app `wms-platform`，用户 `wms-wh-a`/`wms-wh-b`/`wms-ops`/`wms-denied`，凭据 0600 文件。

## 未完成

- S1-05 正式黑盒（真实 Casdoor 身份全集）与 S1-06 事实身份运行时。
- AC-01/02/31 正式业务验收仍 planned。本轮有领域/库/HTTP 隔离证据，不是 50 项 AC 通过。
- 50 项业务 AC、S0 XXL 真触发、SBOM/CVE、`wms-console/`。

HTTP 写接口未交付。种子脚本不等于控制台。现场 Casdoor 未跑时开通脚本不能证明身份已创建。

本轮实现提交 `994ebec` / 发布合并 `a7f90c8` 已在任务分支和远程 main。远程 CI：[verify #34489970301](https://github.com/lirji/wms-platform/actions/runs/34489970301) 成功。auth-platform `dbf2ca4` 已在其远程 main；该提交未见新的 Actions run。无生产部署。
