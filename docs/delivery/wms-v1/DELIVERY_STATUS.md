# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S4-06（XXL 只监控 TRIED/TC，不发二阶段）。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s4-06。S4-05 已在 remote main `dc70294`。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：仓级 `tccReservationWatch`、全局 `allocationRecoverySweep`、只读 `TcStatusPort`、测试与文档、快进 main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、XXL admin 正式触发、XXL 集群/分片、真实 TC DB 审计、Confirm/Cancel。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S4-05 远程 verify `#34652310445` 运行中；S4-06 本地 inventory/fulfillment verify 已通过 |
| EG-02 TC组合/唯一TM | running | 默认 `UnavailableTcStatusPort` 不查询 Seata；stub 端口只写观察副本 |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | 等 S4-05 main CI 结束后快进 S4-06 |

## 本轮已实现（S4-06）

- `tccReservationWatch` 只读 TRIED/CONFIRMED，先 `RootContext.unbind()`，禁止 TTL 释放与 Confirm/Cancel。
- `allocationRecoverySweep` 对缺证据的已绑 XID 读 `TcStatusPort`，再复用 `recoverReadyBarriers` 补齐 ALLOCATED/Outbox。
- Handler 带 `@XxlJob`，未注册 `XxlJobSpringExecutor`，smoke 不连 admin。
- fulfillment POM 仍无 Seata。

## 未完成

- 发布 S4-06。S4-07…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
