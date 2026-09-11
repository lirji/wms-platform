# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC。切片完成后自动下一片，不要等「继续」。当前切片 S4-07 已实现，等 S4-06 main CI 结束后发布。未发明 OQ-03。未到计划 S8 前不创建 `wms-console/`。

## 已完成

- S0、S4-01…S4-06 已在 remote main `e145e5f`。
- S4-07 本地：owner 匹配、失联隔离、空启动清理、活动 attempt CAS。

## 已修改文件（本轮）

- `InventoryApplicationService.reserveTried` / `reserve` 委托
- `FulfillmentService` mark/record/isolate/cleanup launch
- `ReservationOwnerIT` / `FulfillmentLaunchIT`

## 未完成

- 发布 S4-07（先等 main 上 S4-06 verify）。S5 出库执行。S6…S9。50 项 AC。OQ-03。S8 才做 `wms-console/`。

## 下一步建议

1. S4-06 CI 结束后快进 remote main。
2. 立刻做 S5。不要把目标缩成只做 S4。

## 恢复 Prompt

读取 CODEX_PROGRESS.md。从第一个未完成切片继续，不要要求反复输入继续。不要发明 OQ-03。未到 S8 不要创建 wms-console。
