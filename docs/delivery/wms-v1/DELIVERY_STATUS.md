# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S4-03（inventory `ReservationTccAction` + Fence 同库事务，禁用 AT）。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s4-03。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：inventory TCC Try/Confirm/Cancel、官方 Fence 与库存同物理事务、禁用 AT 数据源代理、测试与文档、快进 main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、真实 TC 二阶段恢复（S4-04）、把本切片当 AC-10/12 通过。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S4-03 本地 verify 已通过；发布后核对远程 verify |
| EG-02 TC组合/唯一TM | running | RM 动作已落地；尚未接真实 TC 恢复 |
| EG-03 业务决定 | running | OQ-03 仍待；本切片未发明单位/效期默认 |
| EG-04 完整闭环 | pending | S5 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S4-03 待快进 |

## 本轮已实现（S4-03）

- `ReservationTccAction`：Try 写 TRIED 并增加 reserved；Confirm 转 CONFIRMED 且 reserved 不变；Cancel 释放 TRIED。
- 官方 `SpringFenceHandler` 与库存物理库同一 `TransactionTemplate`；Try 失败 Fence 与占用一起回滚。
- `seata.enable-auto-data-source-proxy: false`；绑定前拒绝 `DataSourceProxy`。inbound/outbound 仍不依赖 Seata。
- 本切片不 `RMClient.init`、不连接 TC。不是 AC-10/12。

## 未完成

- 本轮快进 remote main。S4-04…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
