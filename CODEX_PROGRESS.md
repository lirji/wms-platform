# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC 可观察验收。当前切片 S3-04（定向 IT 已通过，待完整 verify 后快进 main）。不能宣称项目完成。未开始`wms-console/`。

## 已完成

- S3-03 `d88f8a5` 已在 remote main。
- 工作树`.local/s1-masterdata` 分支`feat/wms-s3-04`。
- S3-04 定向：`FefoCandidateIT`、`SerialReceiptIT`、`InboundReceiptIT` 失败 0。

## 已修改文件（S3-04 未发布 main）

- FEFO 候选、预占效期、上架质检/库位校验。

## 未完成

- S3-04 完整 verify/smoke/快进 main。S3-05→S9。50 项 AC。OQ-03。`wms-console/`。

## 下一步建议

1. 完整 verify 后快进 `feat/wms-s3-04`。
2. 立即 S3-05 收货 session/part 映射。
3. 不要把本切片当作 AC 或项目完成。

## 恢复 Prompt

请读取CODEX_PROGRESS.md。S3-04 若已在 main 则从 S3-05 继续。只操作隔离工作树。不要要求反复输入继续。不要把目标缩成只做 S3。
