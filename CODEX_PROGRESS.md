# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC 可观察验收。当前切片 S2-07（定向 IT 已通过，待完整 verify 后快进 main）。不能宣称项目完成。未开始`wms-console/`。

## 已完成

- S2-05 `5179777` 已在 remote main。
- 工作树`.local/s1-masterdata` 分支`feat/wms-s2-07`。
- S2-07 定向：`EffectCommandUniquenessIT`、`InboundProtocolIT`、`StockCommandIT`、`ThreeServiceProtocolIT`、`OutboundProtocolIT` 失败 0。

## 已修改文件（S2-07 未发布 main）

- 库存 V008 posting 效果唯一；入出库 V002 尝试字段。
- StockCommandService / SourceProtocol 先锁 effect，换键复用，safeClose 下一尝试，补偿效果。

## 未完成

- S2-07 完整 verify/smoke/快进 main。S3→S9。50 项 AC。OQ-03。`wms-console/`。

## 下一步建议

1. 完整 verify 后快进 `feat/wms-s2-07`。
2. 立即 S3-01 入库收货/质检/上架（V001 文件名已被协议占用，用后续版本）。
3. 不要把本切片当作 AC-47..50 正式黑盒或项目完成。

## 恢复 Prompt

请读取CODEX_PROGRESS.md。S2-07 若已在 main 则从 S3-01 继续。只操作隔离工作树。不要要求反复输入继续。不要把目标缩成只做 S2。
