# Codex Progress

## 任务目标

按已批准的唯一计划`docs/delivery/wms-v1/DELIVERY_PLAN.md`补执行门禁后推进WMS业务开发。保留独立入库/出库/库存服务、跨仓Seata TCC、其他本地事务+可靠消息。当前S0进行中，不能宣称业务交付完成。本会话起前后端均由当前实施者负责；S1契约未就绪前不开始`wms-console/`。

## 已完成

- 当前分支`feat/wms-s0-foundation`；初始工程5d986dd、文档a37477b、TC审计7fd3830、单RM双仓/TC恢复216fd95、独立RM 8c41356。
- 三个服务启动骨架与默认拒绝业务访问、Maven Wrapper、CI及候选版本记录；构建和三进程smoke通过。
- warehouse-it六项真实MySQL/分片/Fence局部验证通过；修复ShardingSphere插件装配和ANTLR4.8/4.13.2冲突。
- TC会话清理后提交/回滚均为Finished已实测；DB终态审计候选验证提交/回滚区分、TC重启及审计写失败恢复。
- 单RM双仓真实回调验证局部回滚、部分确认后TC重启、双仓Cancel；独立RM JVM+片内ShardingSphere恢复及Try不足/空Cancel通过。
- S0-07：启动CAS（唯一活动attempt、代际隔离、绑定不可覆盖、丢失响应权威读）与真实`branchRegister`重复Try（新branchId不能接管、外键Cancel不释放）已通过`tc-it`并发布`7c435c3`。Seata 2.6同身份prepareFence会DuplicateKey并异步删Tried记录，禁止盲目重放。
- origin/main存在且包含a37477b；沿用持续授权正常快进发布。旧基线CI run34426596804成功，不能冒充本轮。

## 已修改文件

- `wms-test-support/src/test/java/com/lrj/wms/probe/LaunchBindingProbe.java`
- `wms-test-support/src/test/java/com/lrj/wms/probe/LaunchProbeMapper.java`
- `wms-test-support/src/test/java/com/lrj/wms/probe/DuplicateTryProbe.java`
- `wms-test-support/src/test/java/com/lrj/wms/probe/ReservationProbeMapper.java`
- `wms-test-support/src/test/java/com/lrj/wms/probe/TcDatabaseEvidenceIT.java`
- `wms-test-support/src/test/resources/db/launch-probe/V001__launch.sql`
- `wms-test-support/src/test/resources/db/retry-probe/V004__reservation.sql`
- `README.md`、`S0_RUNBOOK.md`、唯一计划/状态/QA/评审/交接及`CODEX_PROGRESS.md`。
- `.idea`及其他项目文件不修改、不提交。

## 未完成

- 本轮提交`7c435c3`已推送任务分支和main；远程CI以该提交对应运行为准，旧基线通过不能冒充本轮。
- S0仍缺HTTP网关Try、正式终态与业务屏障、Kafka/XXL联调、依赖安全及容量/恢复。
- 全部50项正式业务AC仍planned；S1..S9、设备/对账/UI未完成。

## 当前问题

- TM归属（建议wms-fulfillment）、序列号范围（建议企业+SKU+serial）、认证接入仍待用户决定；不将“继续”解释成选定这些互斥选项。
- 本次ShardingSphere组合是每RM固定单Cell、片内单物理数据源，不证明单RM跨库本地原子性或Cell迁移/大规模分表。
- TC审计只为S0候选，未授权部署生产TC；缺证据/Finished均不放行业务。
- Seata 2.6重复prepareFence会清理Tried记录，正式Try入口必须先绑定XID且禁止盲目重试。

## 下一步建议

1. 本轮S0-07已发布`7c435c3`；核对本轮远程CI。若CI回归，优先修复并再次发布。
2. 继续剩余S0：Kafka/XXL、正式屏障与依赖治理。HTTP网关Try尚未实现，不把Seata RM RPC当作HTTP验收。
3. S1契约与种子稳定后并行实施`wms-console/`；业务决定到达后更新对应OQ。

## 恢复 Prompt

请读取CODEX_PROGRESS.md、DELIVERY_STATUS.md和QA_REPORT.md，核对当前Git/CI与最终测试报告，从第一个未完成门禁继续。远程main已存在，不再重复询问首次创建。保护用户已有改动，只操作wms-platform和自建测试资源；已通过的局部证据不等于全部S0/业务AC验收。前后端均可实施，但S1前不要用页面写死Mock。不要要求反复输入继续。
