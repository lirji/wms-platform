# Codex Progress

## 任务目标

按已批准的唯一计划`docs/delivery/wms-v1/DELIVERY_PLAN.md`补执行门禁后推进WMS业务开发。保留独立入库/出库/库存服务、跨仓Seata TCC、其他本地事务+可靠消息。当前S0进行中，不能宣称业务交付完成。本会话起前后端均由当前实施者负责；S1契约未就绪前不开始`wms-console/`。

## 已完成

- 当前分支`feat/wms-s0-foundation`。
- 用户已确认：唯一 TM=`wms-fulfillment`；认证=OIDC（issuer/client 在 S1-02 配置，未指定 IdP 产品）；序列号唯一范围=enterprise+SKU+serial。OQ-03 单位/效期仍待。
- S0-08 关闭。HTTP 网关 Try 探针（Seata Jakarta 拦截器 + 禁止盲目重放 Fence）已加入 `tc-it`；不是正式履约模块。
- 先前：三服务骨架、warehouse-it/tc-it 探针、S0-04 compose、AC-44 Kafka/线程池隔离。

## 已修改文件

- `wms-test-support/src/test/java/com/lrj/wms/probe/HttpGatewayTryProbe.java`
- `wms-test-support/src/test/java/com/lrj/wms/probe/TcDatabaseEvidenceIT.java`
- `wms-test-support/pom.xml`
- `docs/design/07-decisions-evidence.md` 及架构/领域/TCC/计划/状态/QA/`CODEX_PROGRESS.md`
- `.idea`及其他项目文件不修改、不提交。

## 未完成

- S0仍缺正式终态与业务屏障、XXL 实际触发、依赖安全及容量/恢复。HTTP 探针不等于 `wms-fulfillment` 已交付。
- 全部50项正式业务AC仍planned；S1..S9、设备/对账/UI未完成。`wms-fulfillment` 按 S4 建模块。

## 当前问题

- OQ-03 货权主体/单位/效期规则未确认；不编造生产默认值。
- 本次ShardingSphere组合是每RM固定单Cell、片内单物理数据源。
- TC审计只为S0候选；Seata 2.6重复prepareFence会清理Tried记录。

## 下一步建议

1. 本轮决定记录与 HTTP Try 探针验证后发布；远程CI以该提交为准，不要追加纯文档提交打断流水线。
2. 继续正式屏障、XXL 触发与依赖治理。S1-02 等具体 OIDC issuer。
3. S1 主数据/OpenAPI 可在无 IdP 产品名的情况下先做契约与表结构；权限集成需要 issuer。

## 恢复 Prompt

请读取CODEX_PROGRESS.md、DELIVERY_STATUS.md和QA_REPORT.md，核对当前Git/CI与最终测试报告，从第一个未完成门禁继续。远程main已存在，不再重复询问首次创建。保护用户已有改动，只操作wms-platform和自建测试资源；已通过的局部证据不等于全部S0/业务AC验收。前后端均可实施，但S1前不要用页面写死Mock。不要要求反复输入继续。
