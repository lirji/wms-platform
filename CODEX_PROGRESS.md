# Codex Progress

## 任务目标

按已批准的唯一计划`docs/delivery/wms-v1/DELIVERY_PLAN.md`连续推进 WMS，不需用户再说继续。当前切片 S2-02 余额/预占/流水 Mapper 与 V004。不能宣称 50 项 AC 完成。未开始`wms-console/`。

## 已完成

- S2-01 已快进远程 main：`3e90882`。工作树`/Users/liruijun/personal/LLM/wms-platform/.local/s1-masterdata`。
- 任务分支`feat/wms-s2-02`：`V004__inventory_transactions.sql`、`InventoryMapper`、约束与条件更新 IT。
- S1-01..06 已在远程 main。原目录 ADR-11 脏文件与 auth-platform 原脏工作区未改。

## 已修改文件

- `wms-inventory` V004、InventoryMapper、InventoryPersistence、InventoryTransactionIT、MasterdataMigrationIT 表计数、交付文档。
- `.idea`、凭据、隔离 compose `.env` 不提交。

## 未完成

- S2-03 收货/预占/释放/移动/发运原语。S2-04 Outbox。S2-04a command/permit。
- 隔离 compose 双 Cell 种子 + Casdoor JWT。
- 正式`wms-fulfillment`仍是S4。50 项 AC 仍 planned。OQ-03 仍待。
- S1-06 远程 CI #34494563824 与 S2-01 #34495175702 尚未核验通过。

## 当前问题

- OQ-03 未确认。S2-02 不实现过账应用服务。
- 计划中的 `V002__inventory_transactions.sql` 实际文件为 V004。

## 下一步建议

1. 发布 S2-02 后立即做 S2-03 InventoryApplicationService。
2. 有隔离 compose `.env` 后再灌种子并用 Casdoor JWT 打 HTTP。
3. 未完成库存写接口前不开始 `wms-console/`。

## 恢复 Prompt

请读取CODEX_PROGRESS.md、DELIVERY_STATUS.md和QA_REPORT.md，核对当前Git/CI，从第一个未完成门禁继续。本任务分支是 feat/wms-s2-02。保护用户已有改动，只操作隔离工作树。不要要求反复输入继续。
