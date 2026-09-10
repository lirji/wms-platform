# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S2库存事务内核（S2-04a 本轮本地定向已通过，待完整 verify 后快进 main）。
- 用户 `/goal` 要求按唯一计划做到整个项目完成；50 项 AC 与 S9 仍未完成，目标保持完整。未开始`wms-console/`。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s2-04a（WMS）。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；`/goal` 连续做到整个项目；持续 Git 发布授权。
- 本轮允许：S2-04a stock_command/posting/permit、取消墓碑、inbound/outbound source 协议与最小 T1/T2/T3、测试文档、快进 remote main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、编造 Kafka 成功、S2-05 并发 IT 未在本轮冒充通过。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S2-04 main #34541603485 发布时 pending。S0/SBOM 未关 |
| EG-02 TC组合/唯一TM | running | 正式`wms-fulfillment`仍是S4 |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5退出必选 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S2-04a 待完整 verify 后快进；无生产部署 |

## 本轮已实现（S2-04a）

- `V007`：stock_command/posting/execution_permit/claim。
- `StockCommandService`：收货 T2 过账、同命令重放、取消墓碑、晚到不入账、恢复查询。
- inbound/outbound `V001__source_protocol`：source_effect/command/execution/inbox/outbox；T1/T3。
- `ThreeServiceProtocolIT`：三独立 MySQL，入库 T1→库存 T2→入库 T3；出库 T1→库存墓碑→出库 T3。

## 未完成

- S2-05 并发/崩溃 IT。S2-07 及 S3…S9。50 项 AC。`wms-console/`。OQ-03。

无生产部署。
