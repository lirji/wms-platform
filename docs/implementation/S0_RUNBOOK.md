# S0本地运行与验证

## 前置条件

JDK21、Docker及网络可用；普通服务端口默认只监听127.0.0.1。测试会创建自己的临时容器和数据库；不会连接共享dev-infra进行故障注入。不要把探针迁移用于生产库存库。

## 已创建的命令

```bash
./mvnw -B -ntp verify
python3 scripts/smoke-services.py
./mvnw -B -ntp -Pwarehouse-it verify
./mvnw -B -ntp -Ptc-it verify
```

前两条构建并启动三个独立进程检查健康和访问拒绝。warehouse-it验证真实MySQL/分片/原生Fence局部行为；tc-it包含原生TC终态查询限制及DB终态审计候选探针，不是完整跨仓事务。两类集成profile分别执行，报告位于wms-test-support/target/failsafe-reports，失败或未发现测试均不能作为通过。完整failure-it、种子和容量脚本尚未实现，不能运行设计中的目标命令冒充交付。

手工启动任一服务：

```bash
java -jar wms-inventory/target/wms-inventory-0.1.0-SNAPSHOT.jar
```

inbound/outbound/inventory默认端口18181/18182/18183，可用WMS_HTTP_PORT覆盖。业务路径在S1认证接入前全部拒绝；不提供默认用户，不记录生成密码。当前服务未接业务数据库，健康状态不证明库存可用。

## CI与发布边界

GitHub Actions运行构建、进程验证、warehouse-it和tc-it，保留测试报告；没有部署步骤。远程main已存在，任务分支正常快进发布，不再有首次创建阻塞。生产部署始终另授权。

终态审计的机制、故障验证与生产限制见[候选验证说明](TC_TERMINAL_EVIDENCE.md)。

独立RM探针使用两个受控子JVM，经各自Cell的ShardingSphere执行Fence与库存事务；包含B故障/进程重启恢复和账号隔离。不是已实现正式入出库业务接口。

启动CAS探针验证活动槽与XID绑定；重复Try探针用真实`branchRegister`证明重试会换branchId，并由业务键拒绝改绑。二者都在`tc-it`的`TcDatabaseEvidenceIT`中执行，不是正式履约服务。
