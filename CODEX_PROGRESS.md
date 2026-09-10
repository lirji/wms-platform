# Codex Progress

## 任务目标

按已批准的唯一计划`docs/delivery/wms-v1/DELIVERY_PLAN.md`连续推进 WMS，不需用户再说继续。当前切片 S2-04 Outbox 领取/发布与 command_dedup（本地已验证，待发布 main）。不能宣称 50 项 AC 完成。未开始`wms-console/`。

## 已完成

- S2-03 `0bf8d14` 已快进远程 main。工作树`/Users/liruijun/personal/LLM/wms-platform/.local/s1-masterdata` 当前分支`feat/wms-s2-04`。
- S2-04 定向 IT：`InventoryApplicationIT` 3 项、`OutboxPublisherIT` 1 项、`MasterdataMigrationIT` 4 项、`InventoryTransactionIT` 3 项，失败 0。

## 已修改文件（S2-04 未发布 main）

- `V006__command_dedup.sql`、`CommandDedupMapper`、`OutboxPublisher`/`OutboxTransport`、`OutboxMapper` 领取/结案。
- `InventoryApplicationService` 先写 command_dedup 再过账。

## 未完成

- S2-04 快进 remote main。S2-04a command/permit。S2-05 并发 IT。
- 隔离 compose Casdoor JWT。50 项 AC。OQ-03。`wms-console/`。

## 下一步建议

1. 完整 verify/smoke 后提交并快进 `feat/wms-s2-04`。
2. 立即开始 S2-04a。不要把本切片当作 AC-03/AC-05/Kafka 投递通过。

## 恢复 Prompt

请读取CODEX_PROGRESS.md，核对 Git。S2-04 若已在 main 则从 S2-04a 继续。只操作隔离工作树。不要要求反复输入继续。
