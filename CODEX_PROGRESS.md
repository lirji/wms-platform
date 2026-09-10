# Codex Progress

## 任务目标

按已批准的唯一计划`docs/delivery/wms-v1/DELIVERY_PLAN.md`推进 WMS。用户已要求开始 S1。当前做 S1-01 主数据与 S1-03 OpenAPI；不能宣称 S1 或业务交付完成。种子与 OIDC 未就绪前不开始`wms-console/`。

## 已完成

- 任务分支`feat/wms-s1-masterdata`，工作树`/Users/liruijun/personal/LLM/wms-platform/.local/s1-masterdata`，基线`origin/main` `a7bd1ab`。
- S1-01：`V001__warehouse_masterdata.sql` 与 masterdata 领域/Mapper；OQ-03 未确认，不编造单位/效期生产默认值。
- S1-03：`wms-contract/src/main/resources/openapi/wms-v1.yaml` 与 `OpenApiContractTest`。
- 本地`./mvnw -B -ntp -pl wms-contract,wms-inventory -am verify`：契约 4 项、SkuPolicy 10 项、MasterdataMigrationIT 4 项（约 12.3s）通过。
- 原目录`feat/wms-s0-foundation`上未提交的 ADR-11 文档未纳入本任务。

## 已修改文件

- `wms-inventory` 主数据迁移/领域/Mapper/IT、`wms-contract` OpenAPI 与契约测试、父 POM 管理 mybatis 3.5.19、`scripts/generate-openapi.py`、`scripts/check-docs.py`（按仓库相对路径忽略 `.local`）、交付状态/QA/进度、README/VERSION_LOCK。
- `.idea`及其他项目文件不修改、不提交。

## 未完成

- S1-02 OIDC、S1-04 seed、S1-05 权限/种子复跑、S1-06 运行时 effect 身份。
- 正式`wms-fulfillment`仍是S4。
- 全部50项正式业务AC仍planned。OQ-03 单位/效期仍待。

## 当前问题

- OQ-03 货权主体/单位/效期规则未确认；不编造生产默认值。
- 主数据写入尚未挂 HTTP/OIDC；inventory 进程仍不自动 Flyway。
- 未指定 IdP 产品，不能开始 S1-02 登录集成。

## 下一步建议

1. S1-04 显式测试库幂等 seed（仍不接生产库）。
2. S1-02 在用户给出 issuer/client 后接入 OIDC，禁止共享环境免认证回退。
3. 正式履约服务按S4创建。

## 恢复 Prompt

请读取CODEX_PROGRESS.md、DELIVERY_STATUS.md和QA_REPORT.md，核对当前Git/CI与最终测试报告，从第一个未完成门禁继续。本任务分支是 feat/wms-s1-masterdata。保护用户已有改动（含原工作区 ADR-11 脏文件），只操作wms-platform和自建测试资源。OpenAPI 存在不等于业务 HTTP 已交付。不要要求反复输入继续。
