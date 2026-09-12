# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC。当前已完成本地 S7-03…S7-05。未发明 OQ-03。未到 S8 不创建 `wms-console/`。

## 已完成

- S0、S4-01…S7-02 在 remote main `a57f366`。
- 计划 S7-03/S7-04 已提交并推任务分支。
- 计划 S7-05 中断续跑、旧 worker 拒绝、Outbox 有界恢复本地通过。

## 已修改文件（本轮）

- `JobRunService` 领取返回 `cursorKey`
- `JobInterruptRecoveryIT` / `OutboxRecoveryLoadIT`
- `MasterdataMigrationIT` 不再写死表数量

## 未完成

- merge 发布 remote main。S8–S9。50 项 AC。OQ-03。

## 下一步建议

1. 提交 S7-05，在 `.local/main-integration` merge `origin/main` 后推 main。
2. 立刻开始 S8（先 S8-01 WarehouseQuantityFact，S8-04 才建 wms-console）。

## 恢复 Prompt

读取 CODEX_PROGRESS.md。从第一个未完成切片继续。不要发明 OQ-03。未到 S8 不要创建 wms-console。
