# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S0工程与兼容/恢复验证。
- 用户已批准补齐执行门禁并推进业务开发，沿用[唯一计划](DELIVERY_PLAN.md)。本会话起前后端均由当前实施者负责；S1契约未就绪前不开始`wms-console/`。
- 仅操作wms-platform和明确隔离的测试资源；不部署生产，不修改共享组件配置。
- 分支：feat/wms-s0-foundation。origin/main已存在且包含a37477b，首次main创建不再阻塞；旧基线CI已成功，新提交按持续授权验证后正常发布。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | 三服务构建/smoke、CI配置及旧基线远程CI通过；本轮提交需核对远程CI |
| EG-02 TC组合/唯一TM | running | TM=`wms-fulfillment`已确认；HTTP网关Try与业务屏障探针已有；正式`wms-fulfillment`模块仍是S4 |
| EG-03 业务决定 | running | 认证=OIDC、序列号范围=enterprise+SKU+serial、TM=`wms-fulfillment`已确认；OQ-03单位/效期仍待 |
| EG-04 完整闭环 | pending | S5退出必选，尚未实现 |
| EG-05 外部与非功能 | pending | S8/S9执行 |
| Git发布 | passed（本轮实现） | 7c435c3已推送任务分支和main，远程包含性已核对；无生产部署 |

## 已实现与已验证范围

- Maven五模块骨架、inbound/outbound/inventory独立进程，仅开放健康接口；没有正式业务API或业务数据库接入。
- warehouse-it六项真实MySQL/分片/Fence技术探针通过。Seata+ShardingSphere实际依赖冲突已修复，候选版本仍不等同于生产锁定。
- TC文件模式的Finished歧义、DB终态审计候选、审计写失败与TC重启恢复已实测。
- 单RM双仓ContextDataSource回归保留；新增两个独立RM JVM，各经自己Cell的ShardingSphere访问库存/Fence/效果。第二仓崩溃后用原XID/branch恢复，第一仓不重复；取消数量及数据库账号隔离通过。
- 新增Try不足/空Cancel用例最终定向复验通过，结果见[QA报告](QA_REPORT.md)和实际报告为准。
- S0-07启动CAS与重复Try：并发只激活一个attempt；begin后失联仅在受控入口证明下提升代际；已绑定XID不可覆盖。真实`branchRegister`重试产生新branchId；新所有者无法接管；外键Cancel不释放原预占。Seata 2.6对同身份再次prepareFence会DuplicateKey并异步删除Tried记录，因此禁止盲目重放Try。
- S0-04：已增加隔离 `deploy/compose.local.yml` 与根目录 `.env.example`（仅占位口令）。三套 MySQL、Kafka、Redis、Seata DB 模式、自有 XXL admin；不启动业务 JAR，不加入 dev-infra 网络。
- S0 AC-44 局部：warehouse-it 增加 Kafka 3.8.0 生产/消费、线程池 ThreadLocal 泄漏/清理、XXL handler 不得持有当前全局事务；三服务 POM 无 Seata AT/XA。不是正式 Outbox 或 XXL 集群验收。
- 用户已确认：唯一 TM=`wms-fulfillment`；认证=OIDC（issuer 实施时配置，未指定 IdP 产品）；序列号唯一范围=enterprise+SKU+serial。
- HTTP 网关 Try 探针：Seata Jakarta 拦截器绑定 `TX_XID`，只接受 `X-Wms-Tm=wms-fulfillment`；同 XID 重试不新注册 branch、不重放 prepareFence；缺 XID 拒绝。不是正式 `wms-fulfillment` 服务。
- 业务屏障探针：只读账号查 `terminal_evidence`，校验 attempt/XID/epoch/TM分组/参与者Fence；缺证据或身份不匹配=`RECOVERY_PENDING`，回滚=`DENIED`，仅提交证据允许写 ALLOCATED Outbox；XXL 路径禁止 Confirm/Cancel。
- `failure-it`：`FailureIsolationIT` 只 kill 本测试登记的 MySQL/TC；共享 dev-infra 拒绝操作；未提交 attempt 在 TC 被杀后仍不得放行，已落盘证据在 TC 宕机后仍 ALLOW。

## 边界与后续工作

不再把下面这些当成待评定的产品选项。TCC、TM=`wms-fulfillment`、OIDC、序列号范围已经确认；缺的是实现与证据。OQ-03（单位/效期）仍待，不挡 S1 主数据表结构。

**S0 挡 S4 的两项已有隔离证据**（不是正式履约服务或生产权限模型）

1. 终态/业务屏障探针已把 `terminal_evidence` 接到 attempt/XID/epoch/参与者。
2. `failure-it` 只操作本任务容器，禁止动共享 dev-infra。

**S0 工程补全（不挡 S1 主数据/OpenAPI）**

- XXL 对自有 admin 做一次真实触发；集群验收仍可后置到 S7。
- VERSION_LOCK 补许可证/SBOM/CVE；没有签署前不得声称生产依赖锁定。
- HTTP 网关 Try 的 S0 探针已通过。正式 `wms-fulfillment` 模块仍是 S4，不必再评路径。

**按阶段做，不要提前冒充**

- `seed-local`、OpenAPI 契约：S1。`run-capacity`：S9。
- 单 RM 跨物理库原子性、Cell 迁移、生产 TC、50 项业务 AC、`wms-console/`：范围边界，不是本阶段待评项。

HTTP 网关探针不等于正式履约服务。局部探针与本地编排通过不代表 S0 或全项目完成。

[候选说明](../../implementation/TC_TERMINAL_EVIDENCE.md)、[本地手册](../../implementation/S0_RUNBOOK.md)、[版本记录](../../implementation/VERSION_LOCK.md)记录实际机制和限制；Git/CI最终结果按本分支提交及远程运行核验，已有普通发布授权不重复询问。

本轮实现提交`7c435c3`已正常快进发布到main，同时包含8c41356/7fd3830/216fd95；保留任务分支和main集成工作树。远程CI执行结果见[main流水线](https://github.com/lirji/wms-platform/actions/workflows/verify.yml?query=branch%3Amain)，应匹配实际提交，不根据本文件更新时间推断通过。
