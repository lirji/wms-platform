# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC 可观察验收。当前切片 S2-05（本地完整 verify 已通过，待快进 main）。不能宣称项目完成。未开始`wms-console/`。

## 已完成

- S2-04a `ad81584` 已在 remote main。
- 工作树`.local/s1-masterdata` 分支`feat/wms-s2-05`。
- S2-05：`InventoryConcurrencyIT`、`IdempotencyRecoveryIT`、`OutboxCrashRecoveryIT` 失败 0；`./mvnw -B -ntp verify` BUILD SUCCESS 02:45。

## 已修改文件（S2-05 未发布 main）

- `InventoryApplicationService.reserve` 版本重试。
- 三个库存 IT。
- 交付 STATUS/QA/REVIEW。

## 未完成

- S2-05 快进 main。S2-07→S9。50 项 AC。OQ-03。`wms-console/`。

## 下一步建议

1. smoke 后快进 `feat/wms-s2-05`。
2. 立即 S2-07：统一 effect 锁、替换旧 effect+action 命令唯一键、posting 效果唯一、尝试字段。
3. 不要把本切片当作 AC-03/04/05 正式黑盒或项目完成。

## 恢复 Prompt

请读取CODEX_PROGRESS.md。S2-05 若已在 main 则从 S2-07 继续。只操作隔离工作树。不要要求反复输入继续。不要把目标缩成只做 S2。
