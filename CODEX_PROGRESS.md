# Codex Progress

## 任务目标

按已批准的唯一计划`docs/delivery/wms-v1/DELIVERY_PLAN.md`连续推进 WMS，不需用户再说继续。当前切片 S2-03 库存原语+同事务 Outbox（本地已验证，待发布 main）。不能宣称 50 项 AC 完成。未开始`wms-console/`。

## 已完成

- S1-06 `20dc6a7`、S2-01 `3e90882`、S2-02 `5a7b9fe` 已快进远程 main。
- 工作树`/Users/liruijun/personal/LLM/wms-platform/.local/s1-masterdata` 当前分支`feat/wms-s2-03`。
- S2-03 本地：`python3 scripts/check-docs.py` PASS documents=20；`./mvnw -B -ntp verify` BUILD SUCCESS 01:19；`python3 scripts/smoke-services.py` 三进程 health UP。

## 已修改文件（S2-03 未发布 main）

- `V005__outbox.sql`、`OutboxMapper`、`InventoryApplicationService` 流水与 PENDING Outbox 同会话。
- `InventoryApplicationIT` 断言重放/失败/回滚的 outbox 计数；`MasterdataMigrationIT` 表数 15。

## 未完成

- S2-03 快进 remote main。S2-04 发布器。S2-04a command/permit。S2-05 并发 IT。
- 隔离 compose Casdoor JWT。50 项 AC。OQ-03。`wms-console/`。

## 下一步建议

1. 提交并快进 `feat/wms-s2-03` 到 remote main。
2. 立即开始 S2-04 Outbox 领取/发布/重试与 `command_dedup`。
3. 不要把本切片当作 AC-03/AC-05 通过。

## 恢复 Prompt

请读取CODEX_PROGRESS.md，核对 Git。S2-03 若已在 main 则从 S2-04 继续。只操作隔离工作树。不要要求反复输入继续。
