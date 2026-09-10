# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S2库存事务内核（S2-03 本轮本地已验证，待快进 remote main；S2-02 及更早已在 main）。
- 用户已批准按计划连续执行后续未完成切片，不再等待「继续」。前后端均由当前实施者负责；未开始`wms-console/`。
- 仅操作 wms-platform 隔离工作树 `.local/s1-masterdata`；不部署生产，不修改共享 dev-infra，不触碰原目录 ADR-11 脏文件与 auth-platform 未提交 IAM 改动。
- 分支：feat/wms-s2-03（WMS）。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；用户 `/goal` 要求当前任务做完并连续执行后续未完成切片；持续 Git 发布授权。
- 本轮允许：S2-03 收货/预占/TCC Cancel/同仓移库/发运原语，流水与 Outbox 同事务 PENDING 落库、测试与文档、任务分支提交并快进远程 main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、SpiceDB/ReBAC、`wms-console/`、ADR-11 实现、正式 fulfillment 模块、编造 OQ-03、Outbox 领取/发布/重试（S2-04）、command/permit（S2-04a）。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | 远程 main 并发组 cancel-in-progress。S2-03 发布后以最新 main run 为准。S0 组合门禁与 SBOM 仍未关闭 |
| EG-02 TC组合/唯一TM | running | 沿用 S0 探针；正式`wms-fulfillment`仍是S4 |
| EG-03 业务决定 | running | 本地测试 IdP=auth-platform Casdoor；生产 IdP 未锁。OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5退出必选，尚未实现 |
| EG-05 外部与非功能 | pending | S8/S9执行 |
| Git发布 | running | S2-03 待推 `feat/wms-s2-03` 并快进远程 main；无生产部署 |

## 本轮已实现（S2-03）

- `InventoryApplicationService`：收货、Try 预占、TCC Cancel、同仓移库（含拣货带 reserved）、发运；先锁门禁再按稳定桶键锁余额。
- `V005__outbox.sql` + `OutboxMapper.insertPending`：每条流水同会话写一条 PENDING `InventoryBalanceChanged`；重放看 ledger 不二次写入。
- 失败/冻结/超发在写流水前抛错；回滚后 outbox 为 0。领取/发布/重试不在本切片。

## 先前已实现（S2-02 / S2-01 / S1-06）

- 余额/预占/流水 Mapper。远程 main `5a7b9fe`。
- Quantity/StockBucketKey/ReservationState/InventoryPolicy。远程 main `3e90882`。
- `V003` 效果身份。远程 main `20dc6a7`。

## 未完成

- S2-04 Outbox 领取/发布/重试与 `command_dedup`。S2-04a command/permit。S2-05 并发 IT。
- 隔离 compose Casdoor JWT。AC-03..06 正式业务验收仍 planned。
- 50 项业务 AC、S0 XXL 真触发、SBOM/CVE、`wms-console/`。

无生产部署。
