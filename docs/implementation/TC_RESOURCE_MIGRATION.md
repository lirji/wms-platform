# TC终态通知与原资源迁移

本项对应[唯一有限验收](../delivery/wms-v1/DELIVERY_PLAN.md)的TC切片。先证明终态来源和回调寻址，再解除现有禁止迁移门禁。

## 约束与实现方向

- TC仍是唯一二阶段决定者；履约只从专用只读审计连接取得原XID、应用、组和集群终态。终态观察及逐仓通知Outbox必须同事务，重复、断连或重启不能丢通知。
- 库存接收可信履约通知时核对原allocation/attempt/XID/资源及本地意图，不由通知调用Confirm/Cancel。只有本地终态、原Fence和TC终态一致，才允许停写；REGISTERING、TRIED、缺证据和冲突均继续阻断。
- 迁移原样保留意图的原cell、routeEpoch、XID、branch和action，不把原资源改成目标资源。Fence按原意图的XID/branch精确复制，不能按缺少仓范围的整张Fence表搬迁。
- 目标需能注册原资源的历史回调处理；历史回调只返回已证明的原终态，不重新执行业务效果。新Try继续使用目标cell的新资源和路由代际。迁移前后的未知二阶段不能靠本地推断终结。
- 复制、校验及目标激活沿现有有界流程；目标启动/资源恢复与TC重启需要真实TC/RM双库证据，不能仅以复制行数通过。

## 当前状态

本地实现和定向验证完成，尚待发布：履约自动执行器和审计恢复扫描在终态观察事务中按原参与仓写`TcTerminalNoticeV1`；通知复用现有Outbox与`tcc.terminals`主题，库存按原cell消息路由接收。库存V048保存原意图/XID/branch/action、TC终态、来源JSON和摘要；只有真实本地终态与Fence一致才存证，不因通知调用二阶段。缺配置/错集群或组拒绝，未收敛保留消息恢复。

迁移清单58张有仓范围表，Fence另按原意图逐批最多200行精确复制。目标非COPYING、原Fence任一字段不一致、缺全局证明或本地未终结继续拒绝。原cell/routeEpoch/action不改写；目标每次启动及健康扫描恢复注册已激活仓的原资源，单进程最多256个历史资源，超限明确不就绪。历史回调核对原上下文和终态后只读返回原结果，不刷新Fence时间或重复写库存。

09:28:41 `.local/tcc-terminal-compile.log` 编译通过。`.local/tcc-terminal-migration-core-it.log`的TC只读审计恢复4项通过；新增迁移测试首次编译将构造器JdbcTemplate参数误传DataSource，已修正并定向验证。尚未宣称真实通知/迁移进程组合通过，也不代表生产TC审计、网络ACL、备份恢复和保留期限已获部署验收。

09:36:18 `.local/tcc-terminal-process-migration-it.log` BUILD SUCCESS：两库原Fence迁移1、真实TC/Kafka/履约/双RM及目标新库迁移重启1通过。先前测试另因未按既有迁移夹具初始化UTC来源被拒绝，已修正；没有移除时间来源门禁。

源码核验发现仅注册旧resource不足：[Seata 2.6.0 AbstractCore](https://raw.githubusercontent.com/apache/incubator-seata/v2.6.0/server/src/main/java/org/apache/seata/server/coordinator/AbstractCore.java)只对AT允许跨应用回退，[ChannelManager](https://raw.githubusercontent.com/apache/incubator-seata/v2.6.0/core/src/main/java/org/apache/seata/core/rpc/netty/ChannelManager.java)按原应用查找回调连接。因此目标通过官方RegisterRM协议补原应用/资源别名，在原生应用登记确认后逐连接恢复；每轮最多8个登记、4秒预算，未确认历史别名保持不就绪。新连接重新确认，不把上次连接成功当成当前TC已登记。

正在验证原应用寻址：在隔离TC停止后，重放此前真实执行保存的原会话/分支，保留真实Committed审计证据，由TC向原XID/branch/应用/资源再次发送Confirm。此为明确的延迟回调故障夹具，不是生产数据库恢复演练，不构造新的业务成功事实。预期旧B已停、目标C处理重复回调且业务/Fence保持原样；结果以`.local/tcc-original-application-replay-it.log`为准。

09:41:10 `.local/tcc-original-application-replay-it.log` BUILD SUCCESS：自动执行器5、真实通知/迁移/旧应用回调重放1、仓迁移6。目标C日志09:40:17明确收到原B资源、XID及branch的TC请求并返回PhaseTwo_Committed；源B已停，目标重启和TC重启后业务效果仍一次，原Fence逐字段不变。

09:43:08 `.local/tcc-terminal-final-guards-it.log` 通过无原RM来源的预占阻断与两库Fence迁移。09:43:56 `.local/tcc-notice-barrier-recovery-it.log` 通过2项恢复测试；显式先形成ALLOCATED，再移除两条授权并加入两条通知，证明通知总数不会掩盖缺失授权。必需IT131、文档结构与Compose静态检查通过。Kafka初始化清单同时补齐transfer.commands和tcc.terminals；不依赖broker自动建主题。
