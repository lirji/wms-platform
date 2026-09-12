# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC。切片完成后自动下一片，不要等「继续」。当前切片 S7-04 本地已通过。`origin/main`=`6b7850d`。未发明 OQ-03。未到计划 S8 前不创建 `wms-console/`。

## 已完成

- S0、S4-01…S7-02 已在 remote main `6b7850d`。
- S7-03/S7-04 本地已通过并推任务分支。

## 已修改文件（本轮）

- `V016__expiry_notice.sql` / `ExpiryEligibilitySweep` / `ExpiryEligibilityIT`

## 未完成

- 等 main CI `34667892739` 后再合 S7-03/S7-04。S7-05…S9。50 项 AC。OQ-03。S8 才做 `wms-console/`。

## 下一步建议

1. 等 `34667892739` 成功后按序 FF S7-03 再 S7-04 到 main。
2. 立刻做 S7-05 serialTransferRecovery。不要把目标缩成只做 S7-04。

## 恢复 Prompt

读取 CODEX_PROGRESS.md。从第一个未完成切片继续，不要要求反复输入继续。不要发明 OQ-03。未到 S8 不要创建 wms-console。
