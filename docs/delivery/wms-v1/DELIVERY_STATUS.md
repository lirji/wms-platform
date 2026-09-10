# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S0工程与兼容/恢复验证。
- 用户已批准补齐执行门禁并推进业务开发，沿用[唯一计划](DELIVERY_PLAN.md)。
- 仅操作wms-platform和明确隔离的测试资源；不部署生产，不修改共享组件配置，前端交Cursor。
- 分支：feat/wms-s0-foundation。origin/main已存在且包含a37477b，首次main创建不再阻塞；旧基线CI已成功，新提交按持续授权验证后正常发布。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | 三服务构建/smoke、CI配置及旧基线远程CI通过；本轮提交需核对远程CI |
| EG-02 TC组合/唯一TM | running | 局部数据库/分片/Fence、终态审计、TC重启、独立RM/片内ShardingSphere恢复已有证据；正式启动协议、业务屏障及TM决定未闭合 |
| EG-03 业务决定 | pending | 认证、序列号范围及TM归属待决定 |
| EG-04 完整闭环 | pending | S5退出必选，尚未实现 |
| EG-05 外部与非功能 | pending | S8/S9执行 |
| Git发布 | passed（本轮实现） | 8c41356已推送任务分支和main，远程包含性已核对；无生产部署 |

## 已实现与已验证范围

- Maven五模块骨架、inbound/outbound/inventory独立进程，仅开放健康接口；没有正式业务API或业务数据库接入。
- warehouse-it六项真实MySQL/分片/Fence技术探针通过。Seata+ShardingSphere实际依赖冲突已修复，候选版本仍不等同于生产锁定。
- TC文件模式的Finished歧义、DB终态审计候选、审计写失败与TC重启恢复已实测。
- 单RM双仓ContextDataSource回归保留；新增两个独立RM JVM，各经自己Cell的ShardingSphere访问库存/Fence/效果。第二仓崩溃后用原XID/branch恢复，第一仓不重复；取消数量及数据库账号隔离通过。
- 新增Try不足/空Cancel用例最终定向复验通过，结果见[QA报告](QA_REPORT.md)和实际报告为准。

## 边界与后续工作

这是每RM固定单Cell、片内单物理数据库的组合验证，不承诺单RM跨库本地原子性或生产Cell迁移。终态审计为候选，尚未接attempt/分支屏障及业务Outbox，不能直接部署生产TC。

继续S0实际代理/HTTP重试、启动CAS/RPC故障、Kafka/XXL与依赖治理；TM、认证、序列号决定仍待回复。全部50项正式业务AC仍planned，局部探针通过不代表S0或全项目完成。

[候选说明](../../implementation/TC_TERMINAL_EVIDENCE.md)、[本地手册](../../implementation/S0_RUNBOOK.md)、[版本记录](../../implementation/VERSION_LOCK.md)记录实际机制和限制；Git/CI最终结果按本分支提交及远程运行核验，已有普通发布授权不重复询问。

本轮实现提交`8c41356`已正常快进发布到main，同时包含7fd3830/216fd95；保留任务分支和main集成工作树。远程CI执行结果见[main流水线](https://github.com/lirji/wms-platform/actions/workflows/verify.yml?query=branch%3Amain)，应匹配实际提交，不根据本文件更新时间推断通过。
