# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC。切片完成后自动下一片，不要等「继续」。当前切片为计划 S7-04 内部对账，本地已通过。未发明 OQ-03。未到计划 S8 前不创建 `wms-console/`。

## 已完成

- S0、S4-01…S7-02 及注释修复已在 remote main `a57f366`。
- 计划 S7-03 投影已提交 `8d911d7` 并推 `feat/wms-s7-03-projection`。
- 计划 S7-04 对账本地 IT 通过（2 tests）。

## 已修改文件（本轮）

- `V018__reconciliation.sql` / `ReconciliationMapper` / `StockInternalReconcile` / `ReconciliationController` / `StockInternalReconcileIT`

## 未完成

- 提交推送 S7-04。等 main verify `34668132832` 后再 merge 到 remote main。
- 计划 S7-05 恢复与负载。S8–S9。50 项 AC。OQ-03。S8 才做 `wms-console/`。

## 下一步建议

1. 提交 S7-04 并推任务分支；CI 成功后在 `.local/main-integration` merge（与 `a57f366` 分叉，不能 FF）。
2. 立刻做计划 S7-05。不要把目标缩成只做对账。

## 恢复 Prompt

读取 CODEX_PROGRESS.md。从第一个未完成切片继续，不要要求反复输入继续。不要发明 OQ-03。未到 S8 不要创建 wms-console。计划 S7-03 是投影，S7-04 是内部对账，S7-05 是恢复。
