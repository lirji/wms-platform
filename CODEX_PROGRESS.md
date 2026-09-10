# Codex Progress

## 任务目标

按已批准的唯一计划`docs/delivery/wms-v1/DELIVERY_PLAN.md`推进 WMS。当前完成 S1-05（测试身份越权/单位/效期/种子复跑 + 本机 Casdoor 开通）。不能宣称 S1 或 50 项 AC 完成。未开始`wms-console/`。

## 已完成

- 任务分支`feat/wms-s1-05`，工作树`/Users/liruijun/personal/LLM/wms-platform/.local/s1-masterdata`。基线`origin/main` `b617355`。
- S1-05：GET lots/sku units；测试 JWT 越权；SKU-LOT CS 12:1；LOT-NEAR/LOT-EXP 显式 UTC；种子复跑含 6 条单位行。
- 本机 Casdoor `:8000` 已开通 org/app `wms-platform`；凭据只在 gitignored `.local/wms-iam-credentials.json`（0600）。未对隔离 compose 灌种子（工作树无 `.env`）。
- 先前 S1-01..04 已在远程 main。原目录 ADR-11 脏文件与 auth-platform 原脏工作区未改。

## 已修改文件

- OpenAPI GET `/skus/{skuId}/units`、`MasterdataQueryController` lots/units、Mapper 查询、`SeedReplayIT`/`MasterdataHttpIT`/`SkuPolicyTest`/`OpenApiContractTest`、交付文档。
- `.idea`、凭据文件、隔离 compose `.env` 不提交。

## 未完成

- S1-06 运行时 effect 身份。
- 隔离 compose 双 Cell 种子 + 用 Casdoor JWT 打正在跑的 inventory HTTP（无 `.env`，未起 compose）。
- 正式`wms-fulfillment`仍是S4。
- 全部50项正式业务AC仍planned。OQ-03 仍待。

## 当前问题

- OQ-03 货权主体/单位/效期规则未确认；种子只用 OWNER-SELF 与显式 UTC 时刻。lot 列为 DATETIME，HTTP 按 JDBC 本地墙钟还原 Instant，与 `Timestamp.toInstant` 一致，不编造 00:00 UTC。
- S2 库存事务迁移将使用 V003。

## 下一步建议

1. 有隔离 compose `.env` 后对 Cell A/B 跑 seed，再用 Casdoor JWT 打 inventory。
2. S1-06 事实身份后再考虑 `wms-console/`。
3. 正式履约服务按S4创建。

## 恢复 Prompt

请读取CODEX_PROGRESS.md、DELIVERY_STATUS.md和QA_REPORT.md，核对当前Git/CI与最终测试报告，从第一个未完成门禁继续。本任务分支是 feat/wms-s1-05。保护用户已有改动（含原工作区 ADR-11 脏文件与 auth-platform 未提交 IAM 文件），只操作隔离工作树。OpenAPI 写路径存在不等于写接口已交付。不要要求反复输入继续。
