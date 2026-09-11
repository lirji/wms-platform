# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S4-02（冻结选仓数量摘要、有界 Try、显式 XID 传播）。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s4-02。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：fulfillment 冻结参与者/数量/摘要、截止后拒绝 Try、`TX_XID`+`X-Wms-Tm` 传播头、测试与文档、快进 main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、真实 Seata Try/Confirm（S4-03）、把本切片当 AC-10/12 通过。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S4-02 本地 IT 已通过；发布后核对远程 verify |
| EG-02 TC组合/唯一TM | running | 尚未做真实 TCC Try |
| EG-03 业务决定 | running | OQ-03 仍待；本切片未发明单位/效期默认 |
| EG-04 完整闭环 | pending | S5 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S4-02 待快进 |

## 本轮已实现（S4-02）

- 冻结数量必须等于履约行；`allocation_digest` 覆盖仓+行+SKU+数量+单位。
- 活动 attempt 未知/未终态不得重开（沿用 S4-01）。
- 截止后拒绝 claim/bind/tryHeaders。
- `TryPropagation` 只传播已绑定 XID 与 `wms-fulfillment` TM 头；空上下文拒绝。不是 Seata begin。

## 未完成

- 本轮快进 remote main。S4-03…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
