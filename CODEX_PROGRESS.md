# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC 可观察验收。当前切片 S2-04a（定向 IT 已通过，待完整 verify 后发布）。不能宣称项目完成。未开始`wms-console/`。

## 已完成

- S2-03 `0bf8d14`、S2-04 `31cd3c3` 已在 remote main。
- 工作树`.local/s1-masterdata` 分支`feat/wms-s2-04a`。
- S2-04a 定向：`StockCommandIT`、`InboundProtocolIT`、`OutboundProtocolIT`、`ThreeServiceProtocolIT`、`MasterdataMigrationIT` 失败 0。

## 已修改文件（S2-04a 未发布 main）

- 库存 T2 命令/凭证/墓碑；入出库来源协议表与 T1/T3；三库闭环 IT。

## 未完成

- S2-04a 完整 verify/smoke/快进 main。S2-05→S9。50 项 AC。OQ-03。`wms-console/`。

## 下一步建议

1. 完整 verify 后快进 `feat/wms-s2-04a`。
2. 立即 S2-05 并发/幂等/Outbox 崩溃 IT。
3. 不要把本切片当作 AC-03/05 或项目完成。

## 恢复 Prompt

请读取CODEX_PROGRESS.md。S2-04a 若已在 main 则从 S2-05 继续。只操作隔离工作树。不要要求反复输入继续。不要把目标缩成只做 S2。
