# S0本地运行与验证

## 前置条件

JDK21、Docker及网络可用；普通服务端口默认只监听127.0.0.1。测试会创建自己的临时容器和数据库；不会连接共享dev-infra进行故障注入。不要把探针迁移用于生产库存库。

## 隔离本地中间件

`deploy/compose.local.yml` 是 WMS 自有栈：三套 MySQL（应用/Cell A/Cell B）、Kafka 3.8.0、Redis 7、Seata Server 2.6.0、XXL-JOB admin 3.4.2。不加入 sibling `/Users/liruijun/personal/LLM/dev-infra` 网络，不 `depends_on` 共享容器，不修改该仓库。ordinary 共享实例仍在 dev-infra（MySQL 宿主 43306、Redis 46379、Kafka 49092、MinIO 49000/49001）。Seata 不在 dev-infra；XXL 不共用 drools-demo 的 18088 / mysql 3307。本编排不启动 inbound/outbound/inventory JAR，也不创建业务表。

```bash
cp .env.example .env
# 把 change-me 换成仅本机使用的口令后再启动
docker compose -p wms-local -f deploy/compose.local.yml --env-file .env up -d
```

默认只绑 `127.0.0.1`：应用库 18306、Cell A 18307、Cell B 18308、Kafka 18992、Redis 18379、Seata 18091/控制台 17091、XXL admin 18080。避开本机已占用的 Apollo MySQL 13306、dev-infra 43306/46379/49092、drools XXL 18088。宿主机 Java 客户端连这些端口；容器内互访用服务名。`SEATA_IP` 默认 `127.0.0.1`，给本机进程用；若以后把应用放进同一 compose 网络，需改成对容器可达的地址。XXL 空库首次登录为官方引导账号 `admin` / `123456`，登录后立即改密。官方 admin 镜像是 linux/amd64，Apple Silicon 会走模拟。初始化脚本只在空数据卷执行一次。

故障注入必须另起项目名、端口与网段，例如 `COMPOSE_PROJECT_NAME=wms-fault`、`WMS_COMPOSE_SUBNET=10.89.41.0/24` 并使用另一套 `.env`，禁止 `docker kill` / `compose down` 共享 dev-infra。本机 Docker 默认地址池已被其他项目占满，因此本编排固定私有网段，避免创建网络失败。compose 能解析或容器 healthy 不等于 Kafka 投递、XXL 触发、TCC HTTP 网关或业务 Outbox 已验收。CI 仍用 Testcontainers，不把本文件加入流水线 `up`。

## 已创建的命令

```bash
./mvnw -B -ntp verify
python3 scripts/smoke-services.py
./mvnw -B -ntp -Pwarehouse-it verify
./mvnw -B -ntp -Ptc-it verify
```

前两条构建并启动三个独立进程检查健康和访问拒绝。warehouse-it验证真实MySQL/分片/原生Fence局部行为，以及 Kafka/线程池/XXL 执行线程不把 TCC XID 带进非预占链路；tc-it包含原生TC终态查询限制及DB终态审计候选探针，不是完整跨仓事务。两类集成profile分别执行，报告位于wms-test-support/target/failsafe-reports，失败或未发现测试均不能作为通过。完整failure-it、种子和容量脚本尚未实现，不能运行设计中的目标命令冒充交付。

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
