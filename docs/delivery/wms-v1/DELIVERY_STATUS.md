# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S2库存事务内核（S2-04 本轮本地已验证，待快进 remote main；S2-03 `0bf8d14` 已在 main）。
- 用户已批准按计划连续执行后续未完成切片，不再等待「继续」。前后端均由当前实施者负责；未开始`wms-console/`。
- 仅操作 wms-platform 隔离工作树 `.local/s1-masterdata`；不部署生产，不修改共享 dev-infra，不触碰原目录 ADR-11 脏文件与 auth-platform 未提交 IAM 改动。
- 分支：feat/wms-s2-04（WMS）。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；用户 `/goal` 要求当前任务做完并连续执行后续未完成切片；持续 Git 发布授权。
- 本轮允许：S2-04 Outbox 领取/发布/重试/隔离、`command_dedup` 与业务同事务、测试与文档、任务分支提交并快进远程 main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、SpiceDB/ReBAC、`wms-console/`、ADR-11 实现、正式 fulfillment 模块、编造 OQ-03、编造 Kafka 成功投递、command/permit（S2-04a）。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S2-03 main run #34541003086 与 feat #34540978748 发布时 in_progress。S0 组合门禁与 SBOM 仍未关闭 |
| EG-02 TC组合/唯一TM | running | 沿用 S0 探针；正式`wms-fulfillment`仍是S4 |
| EG-03 业务决定 | running | 本地测试 IdP=auth-platform Casdoor；生产 IdP 未锁。OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5退出必选，尚未实现 |
| EG-05 外部与非功能 | pending | S8/S9执行 |
| Git发布 | running | S2-04 待推 `feat/wms-s2-04` 并快进远程 main；无生产部署 |

## 本轮已实现（S2-04）

- `OutboxPublisher`：按物理库 `FOR UPDATE SKIP LOCKED` 领取，CAS `claim_epoch`，成功 `PUBLISHED`，可重试回 `PENDING`，毒消息/`claim_epoch>=8` 为 `ISOLATED`。未接入 Kafka。
- `V006__command_dedup.sql`：仓级唯一客户端键；同键同摘要重放，同键异内容 `COMMAND_CONFLICT`；与流水/Outbox 同会话。

## 先前已实现

- S2-03 原语+PENDING Outbox。远程 main `0bf8d14`。
- S2-02 Mapper。远程 main `5a7b9fe`。S2-01 `3e90882`。S1-06 `20dc6a7`。

## 未完成

- S2-04a command/permit。S2-05 并发/崩溃 IT。隔离 compose Casdoor JWT。
- AC-03..06 正式业务验收仍 planned。
- 50 项业务 AC、S0 XXL 真触发、SBOM/CVE、`wms-console/`。

无生产部署。
