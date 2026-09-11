# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC。切片完成后自动下一片，不要等「继续」。当前切片 S5-01 本地已通过，等待 S4-07 main CI 后发布。未发明 OQ-03。未到计划 S8 前不创建 `wms-console/`。

## 已完成

- S0、S4-01…S4-07 已在 remote main `4d8ba86`。
- S5-01 实现与本地 IT：部分拣货/posted 重放/包装/取消，不写库存表。

## 已修改文件（本轮）

- `V003__outbound.sql`
- `OutboundOrderService` / `OutboundOrderMapper` / `OutboundPickIT`
- `SourceProtocolService.submitPick` / `submitCancel`
- `DELIVERY_PLAN.md`（V001→V003 路径证据）
- `DELIVERY_STATUS.md` / `QA_REPORT.md` / `REVIEW_REPORT.md`

## 未完成

- 发布 S5-01（先等 S4-07 main CI，`cancel-in-progress: true`）。S5-02 WCS 端口。S5-03…S9。50 项 AC。OQ-03。S8 才做 `wms-console/`。

## 下一步建议

1. 等 S4-07 CI 成功后快进 main。
2. 立刻做 S5-02。不要把目标缩成只做 S5-01。

## 恢复 Prompt

读取 CODEX_PROGRESS.md。从第一个未完成切片继续，不要要求反复输入继续。不要发明 OQ-03。未到 S8 不要创建 wms-console。
