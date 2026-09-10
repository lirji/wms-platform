# Codex Progress

## 任务目标

按已批准的唯一计划`docs/delivery/wms-v1/DELIVERY_PLAN.md`连续推进 WMS，不需用户再说继续。当前切片 S2-01 库存领域类型。不能宣称 S1 或 50 项 AC 完成。未开始`wms-console/`。

## 已完成

- S1-06 已快进远程 main：`20dc6a7`。工作树`/Users/liruijun/personal/LLM/wms-platform/.local/s1-masterdata`。
- 任务分支`feat/wms-s2-01`：Quantity、StockBucketKey、ReservationState、InventoryPolicy；封闭集合；数量精度显式校验。
- S1-01..05 已在远程 main。原目录 ADR-11 脏文件与 auth-platform 原脏工作区未改。

## 已修改文件

- `wms-inventory/.../inventory/domain` 及 `InventoryDomainTest`。
- 交付文档。`.idea`、凭据、隔离 compose `.env` 不提交。

## 未完成

- S2-02 起余额/预占/流水 Mapper 与 **V004** 迁移（计划文件名 V002 已被占用）。
- 隔离 compose 双 Cell 种子 + Casdoor JWT 打 inventory。
- 正式`wms-fulfillment`仍是S4。50 项 AC 仍 planned。OQ-03 仍待。
- S1-06 远程 CI `verify` #34494563824 仍 running，不能用本地 verify 替代。

## 当前问题

- OQ-03 未确认。S2-01 不过账、不写库存表。
- FIFO/FEFO 必须显式配置，不设默认策略。

## 下一步建议

1. 发布 S2-01 后立即做 S2-02 Mapper 与 V004。
2. 有隔离 compose `.env` 后再灌种子并用 Casdoor JWT 打 HTTP。
3. 未完成库存写接口前不开始 `wms-console/`。

## 恢复 Prompt

请读取CODEX_PROGRESS.md、DELIVERY_STATUS.md和QA_REPORT.md，核对当前Git/CI，从第一个未完成门禁继续。本任务分支是 feat/wms-s2-01。保护用户已有改动，只操作隔离工作树。不要要求反复输入继续。
