# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S2库存事务内核（S2-02 本轮；S2-01 与 S1 已在 main；S0 工程门禁仍 running）。
- 用户已批准按计划连续执行后续未完成切片，不再等待「继续」。前后端均由当前实施者负责；未开始`wms-console/`。
- 仅操作 wms-platform 隔离工作树 `.local/s1-masterdata`；不部署生产，不修改共享 dev-infra，不触碰原目录 ADR-11 脏文件与 auth-platform 未提交 IAM 改动。
- 分支：feat/wms-s2-02（WMS）。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；用户 `/goal` 要求当前任务做完并连续执行后续未完成切片；持续 Git 发布授权。
- 本轮允许：S2-02 余额/预占/流水/门禁 Mapper、V004 迁移、测试与文档、任务分支提交并快进远程 main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、SpiceDB/ReBAC、`wms-console/`、ADR-11 实现、正式 fulfillment 模块、编造 OQ-03、应用层过账原语（S2-03）、Outbox/permit（S2-04）。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S1-06 远程 CI #34494563824 被 main 并发组取消（S2-01 快进后 cancel-in-progress）。S2-01 #34495175702 覆盖 S1-06 代码，结果待核验。S0 组合门禁与 SBOM 仍未关闭 |
| EG-02 TC组合/唯一TM | running | 沿用 S0 探针；正式`wms-fulfillment`仍是S4 |
| EG-03 业务决定 | running | 本地测试 IdP=auth-platform Casdoor；生产 IdP 未锁。OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5退出必选，尚未实现 |
| EG-05 外部与非功能 | pending | S8/S9执行 |
| Git发布 | running | S2-02 待提交 `feat/wms-s2-02` 并快进远程 main；无生产部署 |

## 本轮已实现（S2-02）

- `V004__inventory_transactions.sql`：`stock_balance`/`stock_ledger`/`reservation`/`reservation_line`，中文表列注释；占用不超过实物；预占明细 `requested=remaining+consumed+released`。
- `InventoryMapper`：门禁 FOR UPDATE、空桶唯一键仲裁、GOOD 预占条件更新、不可变流水、XID 所有者唯一。
- 流水补 `free_execution_claim_*` 列，否则无法记账第三占用。

## 先前已实现（S2-01 / S1-06）

- Quantity/StockBucketKey/ReservationState/InventoryPolicy。远程 main `3e90882`。
- `V003` 效果身份。远程 main `20dc6a7`。

## 未完成

- S2-03 起过账原语、Outbox、command/permit。隔离 compose Casdoor JWT。
- AC-03..06 正式业务验收仍 planned。本轮不是并发预占通过。
- 50 项业务 AC、S0 XXL 真触发、SBOM/CVE、`wms-console/`。

无生产部署。
