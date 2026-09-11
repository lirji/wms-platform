# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC。切片完成后自动下一片，不要等「继续」。当前切片 S4-04 已本地通过，待发布后立刻做 S4-05。未发明 OQ-03。未到计划 S8 前不创建 `wms-console/`。

## 已完成

- S0 XXL/SBOM、S4-01、S4-02、S4-03 已在 remote main `2d517f1`。
- S4-04 本地：真实 file-mode TC 恢复、双仓 Fence 路由、TCC 操作键哈希。

## 已修改文件（本轮）

- `ReservationTccAction.phaseOperation` 改为 `CommandDigest.v1Parts`。
- `SeataTccRecoveryIT`、`TccFenceShardingIT`、`InventoryCellRouteAlgorithm`。
- inventory `tc-it` profile 与测试 `registry.conf`/`file.conf`。
- 交付状态 / QA / Review / 本进度文件。

## 未完成

- 发布 S4-04 到 remote main。S4-05 ALLOCATED/出库 Outbox。S4-06…S9。50 项 AC。OQ-03。S8 才做 `wms-console/`。

## 下一步建议

1. 提交并快进 remote main。
2. 立刻做 S4-05，不要等用户说继续。不要把目标缩成只做 S4。

## 恢复 Prompt

读取 CODEX_PROGRESS.md。从第一个未完成切片继续，不要要求反复输入继续。不要发明 OQ-03。未到 S8 不要创建 wms-console。
