# Codex Progress

## 任务目标

按已批准的唯一计划`docs/delivery/wms-v1/DELIVERY_PLAN.md`补执行门禁后推进WMS业务开发。保留独立入库/出库/库存服务、跨仓Seata TCC、其他本地事务+可靠消息。当前S0进行中，不能宣称业务交付完成。本会话起前后端均由当前实施者负责；S1契约未就绪前不开始`wms-console/`。

## 已完成

- 当前分支`feat/wms-s0-foundation`。
- S0业务屏障探针：只读`terminal_evidence`绑定attempt/XID/epoch/参与者Fence；缺证据=`RECOVERY_PENDING`，不得写ALLOCATED；XXL禁Confirm/Cancel。
- `failure-it` profile与`FailureIsolationIT`：只操作本测试容器，拒绝共享dev-infra。
- 本地`./mvnw -B -ntp -Ptc-it verify`两项通过（120.7s+8.7s）；`./mvnw -B -ntp -Pfailure-it verify`一项通过（18.27s）。
- 用户已确认：唯一 TM=`wms-fulfillment`；认证=OIDC；序列号唯一范围=enterprise+SKU+serial。OQ-03 单位/效期仍待。

## 已修改文件

- `wms-test-support`屏障/故障隔离探针、`pom.xml` failure-it profile、CI、S0手册与TC证据文档、交付状态/QA、本文件。
- `.idea`及其他项目文件不修改、不提交。

## 未完成

- 正式`wms-fulfillment`模块仍是S4。
- S0不挡S1：XXL真触发、SBOM/CVE。
- 全部50项正式业务AC仍planned。OQ-03 单位/效期仍待。

## 当前问题

- OQ-03 货权主体/单位/效期规则未确认；不编造生产默认值。
- 本次ShardingSphere组合是每RM固定单Cell、片内单物理数据源。
- Seata客户端在kill TC后首次重连约60秒；failure-it改为杀TC后读审计库，不把TM重连当成本切片门禁。

## 下一步建议

1. S1主数据/OpenAPI可并行准备；OIDC登录等具体issuer。
2. XXL对自有admin真触发与VERSION_LOCK SBOM不挡S1。
3. 正式履约服务按S4创建，不要把本轮探针写成生产模块已交付。

## 恢复 Prompt

请读取CODEX_PROGRESS.md、DELIVERY_STATUS.md和QA_REPORT.md，核对当前Git/CI与最终测试报告，从第一个未完成门禁继续。远程main已存在，不再重复询问首次创建。保护用户已有改动，只操作wms-platform和自建测试资源；已通过的局部证据不等于全部S0/业务AC验收。前后端均可实施，但S1前不要用页面写死Mock。不要要求反复输入继续。
