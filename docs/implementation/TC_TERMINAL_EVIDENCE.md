# TC终态证据候选验证

## 状态与边界

S0-09隔离POC，尚未批准为生产方案。沿用跨仓Seata TCC及“固定分支全部CONFIRMED + TC可靠全局成功证据”门禁；实验使用一个RM进程注册两个仓资源，连接两个独立物理数据源；尚非两个独立库存服务进程，不替代S0-05a或AC-12/42完整验收。

`getStatus(xid)=Finished`只表示当前没有可返回的会话状态，不能区分提交/回滚。TM的`commit()`返回及进程内`getLocalStatus()`也不替代跨进程持久化证据。

## 候选实现

使用Seata 2.6.0 DB store及其官方MySQL schema，在隔离TC数据库增加`terminal_evidence`和两个触发器。仅接受状态9（Committed）、11（Rollbacked）、13（TimeoutRollbacked）；更新终态或删除已终态会话时，同一个MySQL事务追加证据。相同XID不同终态拒绝，已存在相同终态保留首次记录。非终态清理不合成证据。

这属于TC存储审计扩展，不创建业务决定权，不由XXL-JOB决定Confirm/Cancel，也不允许库存服务直接修改TC业务表。正式实现的证据查询应通过受控适配接口，只读审计数据；业务服务不得持有TC写权限。

恢复放行还必须校验环境/集群作用域、绑定XID、TM应用/事务组、当前attempt/launchEpoch及固定分支清单。当前表仅保存TC原始身份与状态，尚未实现这些业务关联校验。缺失、身份不匹配、回滚、失败终态、查询超时一律不能得到“允许出库”。

## 真实验证入口

```bash
./mvnw -B -ntp -Ptc-it verify
```

`TcTerminalEvidenceIT`保留文件存储查询限制回归；`TcDatabaseEvidenceIT`使用独立MySQL8.4.11与TC2.6.0。测试类分别启动JVM，避免Seata静态客户端沿用上一容器地址。Docker默认bridge仅连接测试拥有的容器IP，不删除已有网络；容器退出后回收。

DB探针断言事务begin后真实出现在`global_table`，以排除配置没有生效。随后检查提交/回滚终态证据与会话清理、TC重启后查询恢复、缺失XID无记录，以及审计写失败后的TC重试恢复。追加TwoWarehouseTccProbe验证真实TC二阶段回调、Fence与MyBatis同物理事务、第二仓失败及恢复、双仓Cancel；仍显式调用branchRegister/prepareFence，未验证正式HTTP/代理Try重试。测试SQL只存在于`src/test/resources/db/tc-probe`，不会被三个业务服务自动执行。

Seata DB模式使用延迟恢复路径；初次30秒回滚等待失败后，源码定位到`server.retryDeadThreshold`，探针将其设为1000毫秒以有界验证。该值不是生产推荐，证据可见延迟、后台负载与实际参数需单独压测。失败记录保留在QA报告，不把延长等待等同于解决可靠性问题。

## 纳入正式方案前的必要证据

- 两个独立库存RM进程及ShardingSphere组合；当前仅单RM双资源、ContextDataSource直连两库。还需RM/TM崩溃、超时回滚、重复/乱序二阶段回调。
- 审计不可写时的业务放行阻断、恢复时重发业务Outbox；TM已经死亡也能以同attempt/XID恢复。
- TC HA/主从切换、DB恢复与证据恢复同一数据点；禁止独立恢复审计表制造状态错配。
- 正式迁移和最小权限：迁移账号创建触发器，TC运行账号不授DDL，查询账号仅SELECT审计；触发器definer、备份和恢复必须受治理。
- 升级前核对TC状态码/schema/清理路径；MySQL触发器额外写放大、死锁、清理积压和容量验证。
- 证据保留期覆盖业务恢复/重放/对账窗口，由业务及运维确认；不能随TC会话TTL自动删除。无法证明旧XID结案时不删除对应证据。

## 官方依据

- [Seata 2.6.0 MySQL schema](https://github.com/apache/incubator-seata/blob/v2.6.0/script/server/db/mysql.sql)：探针V001保留Apache许可头，补齐表/字段中文注释。
- [SessionHelper](https://github.com/apache/incubator-seata/blob/v2.6.0/server/src/main/java/org/apache/seata/server/session/SessionHelper.java)：DB模式延迟终态处理；成功返回与持久化不是同一时刻。
- [ConfigurationKeys](https://github.com/apache/incubator-seata/blob/v2.6.0/common/src/main/java/org/apache/seata/common/ConfigurationKeys.java)：`server.retryDeadThreshold`配置键。

实际结果以[QA报告](../delivery/wms-v1/QA_REPORT.md)为准；文档和测试存在不等于门禁通过。

## 二阶段路由约束

Seata 2.6.0 `TCCResourceManager`会先设置BusinessActionContext，再进入Fence；其before/after hook异常会被捕获记录，因此禁止将“hook抛异常”作为唯一拒绝条件。探针在DataSource取连接时检查持久化仓键与注册resourceId匹配，不提供默认仓；缺失上下文直接失败。正式实现还需校验租户、Cell映射版本、业务所有权与可信RPC来源，不能允许调用者任意指定物理库。

探针使用SqlSessionTemplate、SpringManagedTransactionFactory和与Fence相同的路由DataSource；第二仓先插入效果再抛异常，验证效果零记录且Fence仍TRIED。由TC继续二阶段重试，禁止业务任务自行改全局决定。该路径暂未把ShardingSphere串入Fence的数据源，不把此前分片探针自动合并为组合通过。

重启用例覆盖“一仓CONFIRMED、另一仓CommitRetrying”窗口，保留相同XID和持久化branch上下文。Seata客户端原生首次重连调度延迟60秒，后续间隔10秒；初版30秒等待因此失败，探针仅将该恢复断言上限改为90秒，不修改客户端行为，不据此承诺业务RTO。正式恢复SLO需覆盖进程刚启动即断连的情况。

源码依据：[TCCResourceManager](https://github.com/apache/incubator-seata/blob/v2.6.0/tcc/src/main/java/org/apache/seata/rm/tcc/TCCResourceManager.java)、[AbstractNettyRemotingClient](https://github.com/apache/incubator-seata/blob/v2.6.0/core/src/main/java/org/apache/seata/core/rpc/netty/AbstractNettyRemotingClient.java)。
