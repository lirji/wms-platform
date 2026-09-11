# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC。切片完成后自动下一片，不要等「继续」。当前切片 S4-06 已本地通过，等 S4-05 main CI 结束后发布。未发明 OQ-03。未到计划 S8 前不创建 `wms-console/`。

## 已完成

- S0、S4-01…S4-05 已在 remote main `dc70294`。
- S4-06 本地：XXL handler 只监控/同步观察并补齐 Outbox，不发二阶段。

## 已修改文件（本轮）

- `TccReservationWatch` / `TccReservationWatchJob` / `TccReservationWatchIT`
- `AllocationRecoverySweep` / `AllocationRecoveryJob` / `TcStatusPort` / `UnavailableTcStatusPort` / `AllocationRecoverySweepIT`
- inventory/fulfillment POM 增加 `xxl-job-core`（无 executor bean）
- `docs/implementation/TC_TERMINAL_EVIDENCE.md`

## 未完成

- 发布 S4-06（先等 main verify `34652310445`）。S4-07 owner/launch CAS。S4-08…S9。50 项 AC。OQ-03。S8 才做 `wms-console/`。

## 下一步建议

1. S4-05 CI 结束后快进 remote main。
2. 立刻做 S4-07。不要把目标缩成只做 S4。

## 恢复 Prompt

读取 CODEX_PROGRESS.md。从第一个未完成切片继续，不要要求反复输入继续。不要发明 OQ-03。未到 S8 不要创建 wms-console。
