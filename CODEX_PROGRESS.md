# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC。切片完成后自动下一片，不要等「继续」。当前切片 S6-04 本地已通过。等待 timeout-fix main CI 后按 merge（不能 FF）发布 S6-01…S6-04。未发明 OQ-03。未到计划 S8 前不创建 `wms-console/`。

## 已完成

- S0、S4-01…S5-06 已在 remote main `97e35fa`；timeout-fix `8d4ca21` 已在 main。
- S6-01…S6-03a 已推任务分支。S6-03a `53d3185`。
- S6-04 本地：源仓封闭扣量、冻结竞态、转移恢复、两库守恒。

## 已修改文件（本轮）

- `SerialTransferLocalService.sealSource` 扣 on_hand
- `TransferStockService`
- `CountFreezeRaceIT` / `SerialTransferRecoveryIT` / `TransferConservationIT`

## 未完成

- 等 CI 后 merge 发布 S6-01…S6-04。S7…S9。50 项 AC。OQ-03。S8 才做 `wms-console/`。

## 下一步建议

1. 等 timeout-fix CI 成功后，在 `.local/main-integration` 从 `origin/main` merge `feat/wms-s6-01`（不要 FF）。
2. 立刻做 S7-01 XXL-JOB 注册。不要把目标缩成只做 S6-04。

## 恢复 Prompt

读取 CODEX_PROGRESS.md。从第一个未完成切片继续，不要要求反复输入继续。不要发明 OQ-03。未到 S8 不要创建 wms-console。
