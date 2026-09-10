# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S2库存事务内核（S2-05 本轮本地完整 verify 已通过，待快进 main 后立即 S2-07）。
- 用户 `/goal` 要求按唯一计划做到整个项目完成；50 项 AC 与 S9 仍未完成，目标保持完整。未开始`wms-console/`。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s2-05（WMS）。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；`/goal` 连续做到整个项目；持续 Git 发布授权。
- 本轮允许：S2-05 并发预占/同键恢复/Outbox 崩溃 IT、预占版本重试、测试文档、快进 remote main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、把定向 IT 当作 50 项 AC 黑盒通过、开始 `wms-console/`。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S2-04a main `ad81584` 发布后需另核远程 CI；S0/SBOM 未关 |
| EG-02 TC组合/唯一TM | running | 正式`wms-fulfillment`仍是S4 |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5退出必选 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S2-05 待快进；无生产部署 |

## 本轮已实现（S2-05）

- `InventoryApplicationService.reserve`：CAS 未命中时重锁判断不足 vs 版本冲突，最多 16 次，避免并发预占被误判不足。
- `InventoryConcurrencyIT`：100 件上 20 线程各预占 10，10 胜 10 `STOCK_INSUFFICIENT`；on_hand=100、reserved=100、claim=0、10 条 TRIED。
- `IdempotencyRecoveryIT`：同键重放一次入账，异内容 `COMMAND_CONFLICT`。
- `OutboxCrashRecoveryIT`：MySQL BEFORE INSERT 触发器注入 Outbox 失败，余额/流水/dedup 回滚。容器需 `log_bin_trust_function_creators=1`。

## 未完成

- S2-07 及 S3…S9。50 项 AC 正式黑盒。`wms-console/`。OQ-03。

无生产部署。
