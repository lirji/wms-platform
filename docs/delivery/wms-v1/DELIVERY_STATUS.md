# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：计划 S7-03…S7-05 本地已通过，准备合入 remote main。
- `origin/main`=`a57f366`。main verify `34668132832` 失败：`MasterdataMigrationIT` 仍断言 23 张表，实际 30。本分支已改为只断言表/列注释。
- 用户工作区 `main` 未切换。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产。
- 当前分支：`feat/wms-s7-05-recovery`。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | `a57f366` verify 失败（表计数）；修复后随 S7-03…S7-05 再推 main |
| EG-02 TC配置/唯一TM | running | 巡检清理 XID |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | local-pass | S5-06 已在 main |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S7-03=`8d911d7`、S7-04=`8834a04` 已推任务分支；S7-05 待提交后 merge |

## 本轮本地证据

- S7-03 `InventoryProjectionIT`：2 tests, BUILD SUCCESS。
- S7-04 `StockInternalReconcileIT`：2 tests, BUILD SUCCESS。
- S7-05 `JobInterruptRecoveryIT` + `OutboxRecoveryLoadIT` + `MasterdataMigrationIT`：BUILD SUCCESS。

## 未完成

- 发布 S7-03…S7-05 到 remote main（与 `a57f366` 分叉，需 merge）。
- S8–S9。50 项 AC。OQ-03。S8 才做 `wms-console/`。

无生产部署。
