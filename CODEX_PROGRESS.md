# Codex Progress

## 任务目标

按已批准的唯一计划`docs/delivery/wms-v1/DELIVERY_PLAN.md`补执行门禁后推进WMS业务开发。保留独立入库/出库/库存服务、跨仓Seata TCC、其他本地事务+可靠消息。当前S0进行中，不能宣称业务交付完成。

## 已完成

- 当前分支`feat/wms-s0-foundation`；初始工程5d986dd、文档a37477b、TC审计7fd3830、单RM双仓/TC恢复216fd95。
- 三个服务启动骨架与默认拒绝业务访问、Maven Wrapper、CI及候选版本记录；构建和三进程smoke通过。
- warehouse-it六项真实MySQL/分片/Fence局部验证通过；修复ShardingSphere插件装配和ANTLR4.8/4.13.2冲突。
- TC会话清理后提交/回滚均为Finished已实测；DB终态审计候选验证提交/回滚区分、TC重启及审计写失败恢复。
- 单RM双仓真实回调验证局部回滚、部分确认后TC重启、双仓Cancel；记录原生首次重连调度60秒，探针恢复断言90秒有界。
- 新增独立RM JVM夹具：两个RM分别持有一个Cell账号，库存/Fence/效果通过片内ShardingSphere；B进程重启恢复原XID/branch且不重Try，A效果一次，双仓Cancel及账号互相拒读已通过。
- 已重新核对远程：origin/main存在且包含a37477b，旧基线GitHub CI成功（run34426596804）。此前空远程/首次创建阻塞已解除，沿用持续授权正常快进发布，不再询问首次main。

- Try库存不足时ShardingSphere/Fence共同回滚及空Cancel不释放已有库存已通过最终定向复验；日志`.local/tc-independent-verified.log`，1项失败/错误/跳过0，用例107.2秒；此前完整tc-it两项亦通过。

## 已修改文件

- `wms-test-support/src/test/java/com/lrj/wms/probe/CellFenceAlgorithm.java`
- `wms-test-support/src/test/java/com/lrj/wms/probe/WarehouseRmProcess.java`
- `wms-test-support/src/test/java/com/lrj/wms/probe/IndependentRmProbe.java`
- `wms-test-support/src/test/java/com/lrj/wms/probe/TcDatabaseEvidenceIT.java`
- `README.md`、`docs/implementation/TC_TERMINAL_EVIDENCE.md`、`S0_RUNBOOK.md`、`VERSION_LOCK.md`及唯一计划/状态/QA/评审证据。
- `.idea`及其他项目文件不修改、不提交。

## 未完成

- 本轮完成后正常提交、推送任务分支和main，并检查新提交CI。恢复时先检查真实Git refs和CI，避免按此中间记录重复发布或询问。
- S0仍缺实际HTTP/代理Try重试、启动attempt/XID/epoch CAS故障、正式终态与业务屏障绑定、Kafka/XXL联调、依赖安全及容量/恢复等后续门禁。
- 全部50项正式业务AC仍planned；S1..S9、设备/对账/UI等未完成。

## 当前问题

- TM归属（建议wms-fulfillment）、序列号范围（建议企业+SKU+serial）、认证接入仍待用户决定；不将“继续”解释成选定这些互斥选项。
- 本次ShardingSphere组合是每RM固定单Cell、片内单物理数据源，不证明单RM跨库本地原子性或Cell迁移/大规模分表。
- TC审计只为S0候选，未授权部署生产TC；缺证据/Finished均不放行业务。
- 原生TccHook会吞异常，不能仅靠hook异常拒绝错误路由。正式RPC与所有权校验未验收。

## 下一步建议

1. 最终定向tc-it已通过；先检查当前HEAD与origin/main及对应CI，完成剩余发布核验再继续S0。
2. 核对origin/main为祖先后正常推送任务分支与main；无生产部署。核对新提交GitHub CI，不用本地通过替代远程结果。
3. 继续剩余S0协议故障与基础组件门禁；业务决定到达后更新对应OQ，不重新规划项目或重复生成已通过探针。

## 恢复 Prompt

请读取CODEX_PROGRESS.md、DELIVERY_STATUS.md和QA_REPORT.md，核对当前Git/CI与最终测试报告，从第一个未完成门禁继续。远程main已存在，不再重复询问首次创建。保护用户已有改动，只操作wms-platform和自建测试资源；已通过的局部证据不等于全部S0/业务AC验收。不要要求反复输入继续。
