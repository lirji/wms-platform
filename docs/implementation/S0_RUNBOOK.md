# S0本地运行与验证

## 前置条件

JDK21、Docker及网络可用；普通服务端口默认只监听127.0.0.1。测试会创建自己的临时容器和数据库；不会连接共享dev-infra进行故障注入。不要把探针迁移用于生产库存库。

## 隔离本地中间件

`deploy/compose.local.yml` 是 WMS 自有中间件：三套 MySQL（应用/Cell A/Cell B）、Kafka 3.8.0、Redis 7、Seata Server 2.6.0、XXL-JOB admin 3.4.2。不加入 sibling `/Users/liruijun/personal/LLM/dev-infra` 网络，不 `depends_on` 共享容器，不修改该仓库。ordinary 共享实例仍在 dev-infra（MySQL 宿主 43306、Redis 46379、Kafka 49092、MinIO 49000/49001）。Seata 不在 dev-infra；XXL 不共用 drools-demo 的 18088 / mysql 3307。该文件只起中间件，不创建业务表。

要在容器内编译并启动 inbound/outbound/inventory/serial-registry/fulfillment 与 console，用根目录 `compose.yaml`（include 上述中间件）或 `./deploy/up.sh`。应用容器监听 `0.0.0.0`，inventory 接 Cell A；健康 UP 不代表业务验收。详见 `deploy/README.md`。

```bash
cp .env.example .env
# 把 change-me 换成仅本机使用的口令后再启动
docker compose -p wms-local -f deploy/compose.local.yml --env-file .env up -d
# 容器内编译并启动应用：
./deploy/up.sh
```

默认只绑 `127.0.0.1`：应用库 18306、Cell A 18307、Cell B 18308、Kafka 18992、Redis 18379、Seata 18091/控制台 17091、XXL admin 18080。避开本机已占用的 Apollo MySQL 13306、dev-infra 43306/46379/49092、drools XXL 18088。宿主机 Java 客户端连这些端口；容器内互访用服务名。`SEATA_IP` 默认 `127.0.0.1`，给本机进程用。`compose.yaml` 里的应用用 `/app/file.conf` 指向 `seata-server:8091`，不改中间件 advertised IP。XXL 空库首次登录为官方引导账号 `admin` / `123456`，登录后立即改密。官方 admin 镜像是 linux/amd64，Apple Silicon 会走模拟。初始化脚本只在空数据卷执行一次。

故障注入必须另起项目名、端口与网段，例如 `COMPOSE_PROJECT_NAME=wms-fault`、`WMS_COMPOSE_SUBNET=10.89.41.0/24` 并使用另一套 `.env`，禁止 `docker kill` / `compose down` 共享 dev-infra。本机 Docker 默认地址池已被其他项目占满，因此本编排固定私有网段，避免创建网络失败。compose 能解析或容器 healthy 不等于 Kafka 投递、TCC HTTP 网关、业务 Outbox 或 XXL 集群/分片已验收。官方 admin 真实触发由 warehouse-it 的 `XxlAdminTriggerIT` 证明，不把 compose 健康检查当作该证据。CI 仍用 Testcontainers，不把本文件加入流水线 `up`。

根目录 [`.gitignore`](../../.gitignore) 排除 Maven `target/`、IntelliJ `.idea/`、本机 `.env`、`.local/`（集成工作树、smoke 日志、CI 报告副本）、`wms-console` 的 `node_modules/`/`dist/`/`.vite/`/`coverage/`，以及崩溃/合并残留。编排口令模板只提交 `.env.example`；不要把 `.idea`、真实口令或前端安装/构建结果加回版本库。

## 已创建的命令

```bash
./mvnw -B -ntp verify
python3 scripts/check-required-its.py --suite default
python3 scripts/smoke-services.py
./mvnw -B -ntp -Pwarehouse-it verify
./mvnw -B -ntp -Ptc-it verify
./mvnw -B -ntp -Pfailure-it verify
python3 scripts/check-required-its.py --suite failure
./scripts/verify-contracts.sh
./scripts/generate-sbom.sh
./scripts/seed-local.sh --profile isolated-wms
./scripts/run-capacity.sh --scenario agreed-peak
./scripts/run-restore-drill.sh
```

前两条构建并启动 inbound/outbound/inventory/fulfillment 独立进程检查健康和访问拒绝。warehouse-it验证真实MySQL/分片/原生Fence局部行为，Kafka/线程池/XXL 执行线程不把 TCC XID 带进非预占链路，以及官方 XXL admin 3.4.2 对隔离执行器的一次真实触发（`XxlAdminTriggerIT`，不是集群/分片）。tc-it包含原生TC终态查询限制、DB终态审计候选、HTTP网关Try，以及attempt/XID/epoch/参与者业务屏障探针，不是完整跨仓事务。failure-it只kill/start本测试登记的MySQL/TC，缺证据保持`RECOVERY_PENDING`且零Outbox，共享dev-infra快照不得变化；Docker不可用或0测试失败。三类集成profile分别执行，报告位于wms-test-support/target/failsafe-reports，失败或未发现测试均不能作为通过。`check-required-its.py` 核对 AC-45..50 与 S9-03/S9-06 名单，缺测、跳过或失败即失败。`verify-contracts.sh` 核对已提交 OpenAPI 与 ActionEffectRequest 的 N/N-1 可选扩展。`generate-sbom.sh` 只在 `-Psbom` 下写候选 BOM/许可证/OSV 快照，不加入默认 verify，不是生产锁。`run-capacity.sh --scenario agreed-peak` 无签署输入则失败，不跑文档合成峰值。`run-restore-drill.sh` 无外部库时只跑 `IsolatedRestoreIT`。设备模拟与真实硬件证据必须分开记录。

`seed-local.sh` 只接受 `--profile isolated-wms`，且必须显式提供 Cell A/B 库存库以及 inbound/outbound/fulfillment 的 JDBC / 用户 / 口令；拒绝 43306 与 `dev-infra`。它会把 WH-A 写入 Cell A、WH-B 写入 Cell B，写入 5 类 SKU、开账余额与投影、草稿盘点，以及入出库/履约/调拨演示单。履约 attempt 只写 `PLANNED`，不发明 TCC ALLOCATED。这不是控制台，也不接生产库。

手工启动任一服务：

```bash
java -jar wms-inventory/target/wms-inventory-0.1.0-SNAPSHOT.jar
```

inbound/outbound/inventory默认端口18181/18182/18183，可用WMS_HTTP_PORT覆盖。`WMS_OIDC_ISSUER` 为空时业务路径 403；配置 issuer 后无令牌为 401，不得免认证回退。Casdoor 本地开通见 sibling auth-platform `deploy/wms-platform-provision.py`（凭据写入 `WMS_IAM_CREDENTIALS`，不进仓库）。inventory 仅在 `WMS_INVENTORY_JDBC_URL` 非空时 Flyway 并提供主数据只读 HTTP。健康状态不证明库存可用。`wms-console/` 已创建；未配置 OIDC 时停在 `/login` 配置态。本地 Vite 默认 `WMS_UI_PORT=4181` 并提供 `/healthz`。handoff 仍为 ready-for-verification，不是 accepted。

## CI与发布边界

GitHub Actions运行构建、进程验证、warehouse-it、tc-it和failure-it，并把三类failsafe报告分别复制到`.local/reports/`后上传；没有部署步骤。提交说明或 PR 标题含 `[skip ci]`、`[ci skip]` 或 `[no ci]` 时跳过本次 verify（java 与 console 一并跳过）。代码改动不要带这些标记。远程main已存在，任务分支正常快进发布，不再有首次创建阻塞。生产部署始终另授权。

终态审计的机制、故障验证与生产限制见[候选验证说明](TC_TERMINAL_EVIDENCE.md)。

独立RM探针使用两个受控子JVM，经各自Cell的ShardingSphere执行Fence与库存事务；包含B故障/进程重启恢复和账号隔离。不是已实现正式入出库业务接口。

启动CAS探针验证活动槽与XID绑定；重复Try探针用真实`branchRegister`证明重试会换branchId，并由业务键拒绝改绑。HTTP网关Try探针用Seata Jakarta拦截器绑定请求头XID，代表已确认的`wms-fulfillment`入口，同XID重试不得新注册分支。业务屏障探针把只读`terminal_evidence`接到attempt/XID/epoch/参与者Fence，缺证据不得写ALLOCATED。上述探针在`tc-it`的`TcDatabaseEvidenceIT`中执行，不是正式履约服务。
