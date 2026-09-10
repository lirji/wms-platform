# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S1基础资料与安全（S1-01/S1-03进行中，S0工程门禁仍 running）。
- 用户已批准补齐执行门禁并推进业务开发，沿用[唯一计划](DELIVERY_PLAN.md)。本会话确认开始 S1。前后端均由当前实施者负责；种子接口与 OIDC 未就绪前不开始`wms-console/`。
- 仅操作wms-platform和明确隔离的测试资源；不部署生产，不修改共享组件配置。
- 分支：feat/wms-s1-masterdata（独立工作树 `.local/s1-masterdata`，不混入原目录未提交的 ADR-11 文档）。原 feat/wms-s0-foundation 工作区保留用户脏文件。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；用户「开始吧！」启动 S1；持续 Git 发布授权。
- 本轮允许：S1-01 主数据迁移/领域、S1-03 OpenAPI 与契约测试、相关文档、任务分支提交并快进远程 main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、OIDC 产品选型（S1-02）、seed-local（S1-04）、`wms-console/`、ADR-11 实现、正式 fulfillment 模块。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | 本轮本地 contract/inventory 验证已过；远程 CI 以发布提交为准 |
| EG-02 TC组合/唯一TM | running | 沿用 S0 探针；正式`wms-fulfillment`仍是S4 |
| EG-03 业务决定 | running | OIDC/序列号/TM已确认；OQ-03单位/效期仍待，本轮不落生产默认换算 |
| EG-04 完整闭环 | pending | S5退出必选，尚未实现 |
| EG-05 外部与非功能 | pending | S8/S9执行 |
| Git发布 | pending | 本轮提交后核验远程 main 祖先与 CI |

## 本轮已实现（S1-01 / S1-03）

- `wms-inventory` 迁移 `V001__warehouse_masterdata.sql`：warehouse/location/location_gate/sku/sku_unit/lot，表与列中文注释，唯一键与 CHECK。
- 领域 `SkuPolicy`：精度 0..6、序列号整数基础单位、精确换算禁止截断、NO_LOT sentinel、IANA 时区、未知状态拒绝回落；不把源日期默认成 00:00 UTC。
- `MasterdataService` + MyBatis Mapper：仓/库位+OPEN门禁/SKU+基础单位/单位版本/批次写入。进程仍 denyAll，不自动接数据源。
- `wms-contract/src/main/resources/openapi/wms-v1.yaml`：公开/内部路径、幂等头、错误码、cursor 分页、数量字符串、OIDC 安全方案（无密钥）、主数据与 action-effects。无 TCC `/prepare` REST。
- 契约测试 `OpenApiContractTest`；主数据 `SkuPolicyTest` + `MasterdataMigrationIT`（真实 MySQL）。

## 未完成

- S1-02 OIDC 资源服务器（缺 IdP 产品，issuer 用环境配置）。
- S1-04/S1-05 seed-local、越权/种子复跑、HTTP 主数据 API。
- S1-06 事实身份运行时实现（契约路径已列入 OpenAPI）。
- AC-01/02/31 正式业务验收仍 planned。AC-02 仅有领域+库约束证据，无认证 HTTP。
- 50 项业务 AC、S0 XXL 真触发、SBOM/CVE、`wms-console/`。

HTTP 契约文件不等于业务 API 已交付。inventory 迁移不等于服务已接库。
