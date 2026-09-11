# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S4-07（owner 严格匹配、失联隔离与空启动清理）。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s4-07。S4-06 已在 remote main `e145e5f`。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：Try 所有者匹配、launch UNKNOWN/隔离/空启动清理、活动 attempt CAS 回归、测试与文档、快进 main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、fulfillment 调用 Confirm/Cancel、真实 TC begin。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S4-06 已推 main；S4-07 本地定向 IT 已通过 |
| EG-02 TC组合/唯一TM | running | 空 XID 清理只写审计，不改 TC |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | 等 S4-06 main CI 结束后快进 S4-07 |

## 本轮已实现（S4-07）

- `reserveTried` 同 attempt 原身份重放不多占；异 digest=`TCC_CONTEXT_MISMATCH`；换 XID/branch=`TCC_OWNER_CONFLICT`；已取消=`TCC_BRANCH_TERMINAL`。
- 租约过期不能单独接管启动权。失联先 `markLaunchUnknown`，证明无仓级 reservation 后才能 `isolateEmptyLaunch`。
- 已知空 XID 记在 launch 行并 `CLEANED`；未知空 XID `WAITING_TIMEOUT`。已绑定 attempt 拒绝清理/替换。

## 未完成

- 发布 S4-07。S5…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
