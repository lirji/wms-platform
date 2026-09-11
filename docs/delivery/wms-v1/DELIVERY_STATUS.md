# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S4-05（ALLOCATED 与出库建单/执行授权 Outbox）。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s4-05。S4-04 已在 remote main `1faa026`。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：fulfillment 屏障写 ALLOCATED、建单/执行授权 Outbox、恢复补齐、测试与文档、快进 main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、TCC 内派发设备、实现 S5 出库单据。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S4-04 远程 verify 运行中；S4-05 本地 fulfillment verify 已通过 |
| EG-02 TC组合/唯一TM | running | 屏障用观察副本，不是 fulfillment 真实 TM begin |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | 等 S4-04 main CI 结束后快进 S4-05 |

## 本轮已实现（S4-05）

- `markAllocated` 在同一本地事务写 `ALLOCATED`、`AllocationCompleted`、每仓 `OutboundOrderRequested` 与 `ExecutionAuthorizationRequested`。
- 已 ALLOCATED 只补齐缺失 Outbox。`recoverReadyBarriers` 扫描 Committed 证据并补齐。
- 缺证据/未全确认仍 0 条 Outbox。不写 outbound/WCS 表，不派发设备。

## 未完成

- 发布 S4-05。S4-06…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
