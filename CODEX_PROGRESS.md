# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC。切片完成后自动下一片，不要等「继续」。当前切片 S6-02 本地已通过，等待 S5-06 main CI 后按序发布 S6-01、S6-01a、S6-02。未发明 OQ-03。未到计划 S8 前不创建 `wms-console/`。

## 已完成

- S0、S4-01…S5-06 已在 remote main `97e35fa`。
- S6-01 `01eeeaa`、S6-01a `29f8181` 已推任务分支。
- S6-02 本地：登记转移状态机、源仓 SEALED、旧 epoch 拒绝、目的重放。

## 已修改文件（本轮）

- `wms-serial-registry` V002 / `SerialRegistryService` / `SerialTransferMapper` / `SerialTransferIT`
- `wms-inventory` V012 / `SerialTransferLocalService` / `SerialSealIT`

## 未完成

- 按序发布 S6-01…S6-02。S6-03…S9。50 项 AC。OQ-03。S8 才做 `wms-console/`。

## 下一步建议

1. 等 S5-06 CI 成功后快进 S6-01 → 等 CI → S6-01a → 等 CI → S6-02。
2. 立刻做 S6-03 盘点冻结。不要把目标缩成只做 S6-02。

## 恢复 Prompt

读取 CODEX_PROGRESS.md。从第一个未完成切片继续，不要要求反复输入继续。不要发明 OQ-03。未到 S8 不要创建 wms-console。
