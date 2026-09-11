# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC 可观察验收。当前切片 S4-01（定向 IT 与默认 verify 已通过，待快进 main）。不能宣称项目完成。未开始`wms-console/`。

## 已完成

- S3-05 `59f18df` 已在 remote main。
- 工作树`.local/s1-masterdata` 分支`feat/wms-s4-01`。
- S4-01 定向：`FulfillmentMappingIT` 3 项失败 0；默认 `./mvnw -B -ntp verify` 04:22；smoke 四进程拒绝业务路径。

## 已修改文件（S4-01 未发布 main）

- `wms-fulfillment` 模块、`V001__allocation_tracking.sql`、attempt/XID/participant 映射。

## 未完成

- S4-01 快进 remote main。S4-02→S9。50 项 AC。OQ-03。`wms-console/`。

## 下一步建议

1. 快进 `feat/wms-s4-01` 到 remote main。
2. 立即 S4-02 固定参与者/数量/摘要与显式 XID 传播。
3. 不要把本切片当作 AC-10/12 或真实 TCC Try。

## 恢复 Prompt

请读取CODEX_PROGRESS.md。S4-01 若已在 main 则从 S4-02 继续。只操作隔离工作树。不要要求反复输入继续。不要把目标缩成只做 S4。
