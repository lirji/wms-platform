# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC。切片完成后自动下一片，不要等「继续」。当前切片 S5-05 本地已通过，等待 S5-04 main CI 后发布。未发明 OQ-03。未到计划 S8 前不创建 `wms-console/`。

## 已完成

- S0、S4-01…S5-04 已在 remote main `5865281`。
- S5-05 本地：安全关闭独立证据、executionAttempt 重授权、迟到旧命令不覆盖、补偿 DEFERRED/casePart。

## 已修改文件（本轮）

- `StockCommandService.reject` / `safeClose` / `acceptCommand` / `applyCompensate` / `applyPick` / `applyShip`
- inbound/outbound `SourceProtocolService.safeClose` / `submit*` / `consumeResult`
- `ReauthorizationIT`、`OutboundProtocolIT`、`InboundProtocolIT`

## 未完成

- 发布 S5-05（先等 S5-04 main CI）。S5-06…S9。50 项 AC。OQ-03。S8 才做 `wms-console/`。

## 下一步建议

1. 等 S5-04 CI 成功后快进 main。
2. 立刻做 S5-06。不要把目标缩成只做 S5-05。

## 恢复 Prompt

读取 CODEX_PROGRESS.md。从第一个未完成切片继续，不要要求反复输入继续。不要发明 OQ-03。未到 S8 不要创建 wms-console。
