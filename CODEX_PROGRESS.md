# Codex Progress

## 任务目标

按已批准的唯一计划`docs/delivery/wms-v1/DELIVERY_PLAN.md`推进 WMS。当前做 S1-04 种子与 S1-02 OIDC（本地 Casdoor）。不能宣称 S1 或业务交付完成。未开始`wms-console/`。

## 已完成

- 任务分支`feat/wms-s1-seed`，工作树`/Users/liruijun/personal/LLM/wms-platform/.local/s1-masterdata`。S1-01/S1-03 已在`origin/main`（`4bcec83` / `eeed0ab`）。
- S1-04：`scripts/seed-local.sh --profile isolated-wms` 幂等写入 2 仓、5 类 SKU、基础库位与 `operator_grant`；拒绝 43306/`dev-infra`。
- S1-02：`wms-security` OIDC 资源服务器；issuer 为空 denyAll。inventory 主数据只读 HTTP 按 JWT 仓范围过滤。
- auth-platform 隔离工作树`feat/wms-oidc-provision` 增加 `deploy/wms-platform-provision.py`（层①+②，无 SpiceDB）。
- 原目录`feat/wms-s0-foundation`上未提交的 ADR-11 文档未纳入本任务。auth-platform 原脏工作区未改。

## 已修改文件

- `wms-security`、三服务接入、inventory 可选 JDBC/Flyway、`V002__operator_grant.sql`、`SeedLocal`/`scripts/seed-local.sh`、主数据 GET、OIDC/种子测试、`.env.example` 与交付文档。
- auth-platform：`deploy/wms-platform-provision.py` 与 README 指针。
- `.idea`及其他项目文件不修改、不提交。

## 未完成

- S1-05 完整越权/单位/效期/种子复跑业务验收仍 planned（本轮有隔离测试证据，不是 50 项 AC 通过）。
- S1-06 运行时 effect 身份。
- 正式`wms-fulfillment`仍是S4。
- 全部50项正式业务AC仍planned。OQ-03 单位/效期仍待。
- 现场 Casdoor 开通依赖本机 auth-platform 是否在跑；脚本落地不等于身份已在共享 Casdoor 创建。

## 当前问题

- OQ-03 货权主体/单位/效期规则未确认；种子只用 OWNER-SELF 与显式 UTC 时刻，不编造生产默认换算。
- S2 库存事务迁移将使用 V003，因为 S1 已占用 V002 给 `operator_grant`。

## 下一步建议

1. 在本机 Casdoor 跑 `WMS_IAM_CREDENTIALS=... python3 deploy/wms-platform-provision.py`，再对隔离 compose 执行 seed。
2. 完成 S1-05 剩余黑盒（含真实 Casdoor 身份）后再考虑 `wms-console/`。
3. 正式履约服务按S4创建。

## 恢复 Prompt

请读取CODEX_PROGRESS.md、DELIVERY_STATUS.md和QA_REPORT.md，核对当前Git/CI与最终测试报告，从第一个未完成门禁继续。本任务分支是 feat/wms-s1-seed。保护用户已有改动（含原工作区 ADR-11 脏文件与 auth-platform 未提交 IAM 文件），只操作隔离工作树。OpenAPI 存在不等于写接口已交付。不要要求反复输入继续。
