# S0技术探针QA报告

## 环境与范围

2026-09-10，macOS arm64、Microsoft JDK21.0.11、Docker29.7.2；MySQL8.4.11与Seata2.6.0均使用Testcontainers专属容器。未操作共享数据库/消息/TC或生产环境。当前仅启动骨架及技术探针，业务AC整体仍planned。

## 实际验证

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `./mvnw -B -ntp verify` | 构建成功 | Maven多模块及三服务可编译打包 |
| `python3 scripts/smoke-services.py` | 三进程健康UP，业务路径401/403 | 独立进程与默认拒绝；未接业务数据库 |
| `./mvnw -B -ntp -Pwarehouse-it verify` | 10项，失败0、错误0、跳过0 | 原6项分片/Fence保留；新增 Kafka 生产消费不 bind XID、线程池泄漏/清理、XXL handler 无当前全局事务、三服务 POM 无 AT/XA |
| `./mvnw -B -ntp -Ptc-it verify` | 2项，失败0、错误0、跳过0 | 文件模式Finished限制；DB审计、HTTP Try、业务屏障探针 |
| `./mvnw -B -ntp -Pfailure-it verify` | 1项，失败0、错误0、跳过0，18.27秒 | 只kill本任务TC；缺证据PENDING；已落盘证据在TC宕机后仍ALLOW |
| `./mvnw -B -ntp -Ptc-it -Dit.test=TcDatabaseEvidenceIT verify` | 1项，失败0、错误0、跳过0，126.6秒 | 原审计/双仓/独立RM/CAS/重复Try保留；新增 HTTP 网关 Try |
| Python/POM/CI YAML语法 | 通过 | 本地语法；远程CI未运行 |

## 修复与限制

- JDBC基础依赖缺少分片/MySQL/authority SPI：显式加入同版插件后修复。
- Seata传递ANTLR4.8与ShardingSphere生成版本4.13.2冲突：父POM固定4.13.2后SQL测试通过；AT路径不启用、不宣称兼容。
- Fence测试直接绑定一个物理数据源；不等同于多仓RM动态路由和真实TC二阶段故障恢复。
- TC探针揭示现有getStatus恢复路径不足，不能将探针成功当作EG-02完成。还需终态证据可靠保存/读取与TM宕机窗口验证。
- Kafka 生产/消费与线程池 XID 隔离已有 warehouse-it 探针；HTTP 网关 Try 已有 tc-it 探针。官方 XXL admin 真实触发见 2026-09-11 `XxlAdminTriggerIT`（不是集群/分片）。正式履约全链路、外部设备/UI/对账/容量均未验收。

## S0-04隔离本地编排

已写入 `deploy/compose.local.yml` 与根目录 `.env.example`。`docker compose config` 可解析。本机用独立项目名 `wms-compose-smoke` 拉起后，三套 MySQL、Kafka 预置 topic、Redis、Seata 8091、XXL admin HTTP 302 均可用；应用账号不能读 seata 库；Cell A 账号不能登录 Cell B。验证后 `down -v`，未改动共享 dev-infra。这不是 Kafka 投递、XXL 触发、HTTP Try 或业务 Outbox 验收。

## AC-44上下文隔离切片

`ContextIsolationIT` 使用 `apache/kafka:3.8.0` Testcontainers：生产一条带 xid 字段的消息，消费时 `RootContext` 为空且不 bind。单线程池不 unbind 会把 XID 留给下一任务，finally unbind 后为空。XXL `IJobHandler` 清理后无当前全局事务。三个业务模块 POM 不含 seata/XA。不是正式 Outbox、调度触发或 HTTP Try，AC-44 正式业务验收仍 planned。

## 业务决定与HTTP网关Try

用户确认唯一 TM=`wms-fulfillment`、认证=OIDC（未指定 IdP 产品）、序列号唯一范围=enterprise+SKU+serial。`HttpGatewayTryProbe` 经 Seata Jakarta 拦截器绑定请求头 XID，只接受 `X-Wms-Tm=wms-fulfillment`；同 XID 第二次 HTTP 不增加 branch、不重放 prepareFence；缺 XID 返回 400。定向 `TcDatabaseEvidenceIT` 126.6 秒通过。不是正式 `wms-fulfillment` 模块，AC 仍 planned。

## 结论

本轮技术探针通过；整体S0及项目验收未完成，状态in-progress。测试断言不能降级成允许失败/静默跳过来绕过后续门禁。

## S0-09终态审计切片

真实TC+MySQL证明候选审计可保留提交9/回滚11；TC会话清理且重启恢复查询后证据仍在。注入审计INSERT失败3.5秒后，TC仍保留非终态会话且审计零记录；撤销故障后TC自行落盘提交证据并清理。没有业务RM，不能证明业务放行已经实现或EG-02完成。详见[候选方案](../../implementation/TC_TERMINAL_EVIDENCE.md)。

初次运行失败原因分别是Docker自定义网络地址池耗尽、DB模式默认回滚恢复阈值超过30秒探针窗口。改用独立容器在默认bridge上的IP直连，并仅在测试将retryDeadThreshold设为1000毫秒后通过。未删除共享网络、未调整共享TC。最终源码定向复验37.970秒；完整tc-it前序运行77秒。耗时是本机测试值，不是业务SLO。

SQL注释检查器修复多行表选项误报和反引号字段漏检；正/负例检查通过。结构检查20份文档、51条仓库链接、50项AC、64个唯一任务，仍只证明结构。

## S0-05a单RM双仓与TC在途重启补充

最终命令`./mvnw -B -ntp -Ptc-it -Dit.test=TcDatabaseEvidenceIT verify`通过：1项，失败0、错误0、跳过0，构建约99秒；此@Test内追加了TwoWarehouseTccProbe，不增加虚假的测试数量。

- 一个真实RM客户端注册A/B两个资源，分别使用独立库/账号；TC持久化上下文驱动取连接路由，MyBatis与Fence共享Spring事务。
- A确认成功、B写效果后抛异常：A效果1，B效果0且Fence仍TRIED，TC成功审计0。
- 在这个窗口重启测试TC；客户端自行重连后，B完成、A仍一次，两个Fence均COMMITTED，审计最终为提交9。
- 后续新事务两仓各Try30再Cancel，仅释放本次30，原已确认30保持；各一次CANCEL且Fence为ROLLBACKED。
- 缺少路由上下文取连接直接失败；未配置默认仓。

初次在途重启验证30秒超时，源码定位原生客户端首次重连调度延迟60秒、后续10秒；仅把对应断言窗口设90秒后通过，没有强制重连或放宽业务断言。最终日志观察RM重新注册与后续真实TC回调，不能把这个耗时当作已满足业务RTO。

限制：TM/RM同一测试JVM；未用正式HTTP/代理Try；未验证两个RM独立进程、RM/TM宕机、ShardingSphere接入该Fence事务、启动CAS或业务放行Outbox。既有warehouse-it与文件TC回归未改语义，复用此前证据；最终定向命令覆盖本次变化。整体EG-02仍running。

## 独立RM/片内ShardingSphere切片

本轮`./mvnw -B -ntp -Ptc-it verify`两项通过（失败/错误/跳过0，约115秒）；补Try不足/空回滚断言后，最终`./mvnw -B -ntp -Ptc-it -Dit.test=TcDatabaseEvidenceIT verify`通过（1项，失败/错误/跳过0，用例107.2秒）。没有增加虚假的@Test数量，新增场景位于IndependentRmProbe。

- 两个独立RM JVM，A/B数据库账号互相跨库SELECT拒绝。各进程使用片内单Cell ShardingSphere数据源，库存/Fence/效果同Spring事务。
- B确认写效果后失败，效果0且Fence TRIED；强制终止仅本测试B进程，A仍运行。新B PID恢复相同XID/branch，不重新Try，A/B效果各1且原Fence COMMITTED。
- 双仓新Try/Cancel仅释放新占用，原确认占用30保持。
- 将A探针可用量设零后Try返回明确库存不足；TC已有1个branch但库存库Fence为0，证明本地Try回滚。TC随后空Cancel生成状态4 Fence、无CANCEL业务效果，原占用30保持。
- 子进程日志`wms-test-support/target/failsafe-reports/rm-*.log`，由现有CI报告规则上传。只操作测试创建的数据库/容器/进程，未重启dev-infra。

未覆盖：单RM跨多物理库的本地事务、生产Cell迁移、真实HTTP网关Try、TM宕机后的attempt绑定及Outbox屏障。依赖版本未变，旧warehouse-it/进程smoke复用已有同输入证据，远程CI将重跑基础检查。Git远程main已存在，旧基线a37477b的CI run34426596804成功；本轮提交的CI必须另核验。

## S0-07启动CAS与重复Try

最终`./mvnw -B -ntp -Ptc-it verify`两项通过（失败/错误/跳过0；`TcDatabaseEvidenceIT` 106.6秒，`TcTerminalEvidenceIT` 7.4秒）。定向` -Dit.test=TcDatabaseEvidenceIT` 亦通过（1项，108.6秒）。没有增加虚假的@Test数量。

- `LaunchBindingProbe`：16个候选并发只激活一个attempt；两执行器争抢启动权仅一人领取；begin后解绑且租约过期时，仅`entry_protocol_version=1`可提升代际；旧epoch无法绑定已隔离XID；绑定响应丢失通过权威读恢复同一XID；活动槽与尝试插入失败同事务回滚；无入口证明的过期租约不能重开。
- `DuplicateTryProbe`：同一xid两次`branchRegister`得到不同branchId；新branch与新XID的Try均因业务键所有者冲突失败，reserved保持30；外键空Cancel不释放原预占；原事务Cancel后库存与预占行归零。
- 实测Seata 2.6对同xid/branch再次`prepareFence`抛出DuplicateKey，日志写成“already rollbacked”，并`addToLogCleanQueue`异步删除Tried记录。若在仍需Cancel的事务上重放Try，后续Cancel会走空回滚、业务释放不执行。夹具因此禁止在活动分支上重放prepareFence；这不是HTTP网关验收。

AC-45/46正式业务验收仍planned。业务Outbox屏障已有S0探针，不是正式履约服务。

## 业务屏障与failure-it

最终`./mvnw -B -ntp -Ptc-it verify`两项通过（失败/错误/跳过0；`TcDatabaseEvidenceIT` 120.7秒，`TcTerminalEvidenceIT` 8.7秒）。`./mvnw -B -ntp -Pfailure-it verify`一项通过（失败/错误/跳过0，18.27秒）。没有增加虚假的 tc-it `@Test` 数量；屏障场景位于`BusinessBarrierProbe`，故障隔离是单独 profile。

- 只读审计账号 INSERT `terminal_evidence` 被数据库拒绝。
- 提交前、缺XID、空参与者、代际不匹配、证据TM身份与attempt期望不一致：均为`RECOVERY_PENDING`且Outbox为0。
- 提交9且Fence COMMITTED后`ALLOW_ALLOCATED`并写入一行ALLOCATED；回滚11为`DENIED`。
- XXL handler 清理上下文后调用二阶段入口抛`XXL_MUST_NOT_CONFIRM_OR_CANCEL`。
- `FailureIsolationIT` 拒绝非本任务/共享dev-infra容器ID；只kill已登记TC。未提交attempt在TC被杀后仍PENDING；已落盘证据在TC宕机后仍ALLOW且不重复写Outbox。
- 本机共享`dev-infra-marketing` ClickHouse 会自行重启，不能把其StartedAt变化当成failure-it破坏；隔离断言改为操作集合必须等于owned。

不是正式`wms-fulfillment`、生产审计权限、TC HA或50项业务AC。EG-02仍running。

## S1-01/S1-03 主数据与 OpenAPI

环境：2026-09-10，macOS arm64、Microsoft JDK21、Docker 29.7.2、Testcontainers MySQL 8.4.11。未操作共享 dev-infra 或生产。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `python3 scripts/check-docs.py` | 结构通过（工作树修正相对路径忽略 `.local` 后才会计数文档） | 结构，不是业务验收 |
| `./mvnw -B -ntp -pl wms-contract,wms-inventory -am verify` | contract 4 项、SkuPolicy 10 项、MasterdataMigrationIT 4 项 12.32s，失败/错误/跳过 0 | 契约解析；领域规则；真实库迁移/注释/CHECK/唯一键 |
| 根 `./mvnw -B -ntp verify` 与 `python3 scripts/smoke-services.py` | BUILD SUCCESS 约 16.7s；三进程 health UP，业务路径 401/403 | 默认构建含契约与主数据 IT；进程仍不接库 |

领域侧：序列号分数精度拒绝；未知状态不回落 ACTIVE/OPEN；1/3 换算拒绝截断；无批次只用 NO_LOT；启用效期可保留源日期且不生成 UTC 日界。库侧：缺列注释为 0；成对容量/序列号精度/NO_LOT 主键被 CHECK 拒绝；仓编码唯一。

OpenAPI：3.1.0、OIDC 无 client_secret、写接口 Idempotency-Key、Quantity 为 string、列表 CursorPage、无 `/tcc/prepare`。这不是 HTTP 业务实现，AC-01/02/31 仍 planned。

## S1-02/S1-04 种子与 OIDC

环境：2026-09-10，macOS arm64、Microsoft JDK21、Docker 29.7.2、Testcontainers MySQL 8.4.11。未操作共享 dev-infra 或生产。未开始 `wms-console/`。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `SeedLocalIsolationTest` | 拒绝 43306/`dev-infra`，要求 `wms_inventory` | 脚本防护，不是连真实隔离 compose |
| `SeedReplayIT` | 复跑计数 2 仓 / 5 SKU / 6 lot / 8 grant 不变 | 幂等种子；单库双仓，不是双 Cell 物理隔离验收 |
| `OidcDisabledWebIT` | health 200，`/warehouses` 403 | issuer 空不得免认证 |
| `MasterdataHttpIT` | 无令牌 401；WH-A 只见本仓；跨仓 locations 403 `WAREHOUSE_FORBIDDEN`；库位/SKU 来自种子 | 测试 RSA JWT，不是现场 Casdoor |
| `WmsJwtAuthoritiesTest` | 2 项 | 声明解析 |
| `MasterdataMigrationIT` | 7 张表中文注释；CHECK/唯一键 | 含 `operator_grant` |

根 `./mvnw -B -ntp verify` BUILD SUCCESS 37.255s；`python3 scripts/smoke-services.py` 三进程 health UP，业务路径 401/403。smoke 不设 JDBC/issuer。本机 Casdoor `:8000` 未响应，开通脚本已落地但现场身份未创建。远程 CI verify #34489970301 成功（6m44s），含 warehouse-it/tc-it/failure-it。

AC-01/02/31 仍 planned。OQ-03 未确认，种子临期/过期批次使用显式 UTC Instant，`expiry_rule_version=0`。

## S1-05 越权/单位/效期/种子复跑

环境：2026-09-10，macOS arm64、Microsoft JDK21、Docker 29.7.2、Testcontainers MySQL 8.4.11、本机 Casdoor `:8000`。未操作共享 dev-infra 或生产。未开始 `wms-console/`。未创建隔离 compose `.env`，未对 Cell A/B 灌种子。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `MasterdataHttpIT` 9 项 | 无令牌 401；denied 仓列表空且 locations 403；ops CSV 见两仓；WH-A 读 WH-B lots 403；LOT-NEAR `2026-09-17T13:00:00Z`、LOT-EXP `2026-09-09T13:00:00Z`、无 `2026-09-10T00:00:00Z`；SKU-LOT units CS/`12`/`1`；缺 SKU 404 | 测试 RSA JWT，不是 Casdoor JWT 打 inventory 进程 |
| `SeedReplayIT` | 复跑 2/5/6/6/8（仓/SKU/单位/批/授权）；CS 12:1；固定钟 2026-09-10T13:00:00Z 的近/过期时刻 | 单库双仓幂等，不是双 Cell 物理隔离 |
| `SkuPolicyTest` 11 项 | 含 1 箱 CS=12 EA 精确换算 | 领域，不是 HTTP |
| `OpenApiContractTest` 4 项 | GET units/lots 路径存在 | 契约形状 |
| `python3 scripts/check-docs.py` | PASS documents=20 | 结构 |
| `./mvnw -B -ntp verify` | BUILD SUCCESS 39.016s；inventory failsafe 15 项 0 失败 | 默认构建，不含 warehouse-it/tc-it/failure-it |
| `python3 scripts/smoke-services.py` | 三进程 health UP，业务路径拒绝 | 无 JDBC/issuer |
| Casdoor `wms-platform-provision.py` | 开通完成；issuer/client 打印；口令在 0600 凭据文件 | 身份已创建；未用该 JWT 打 inventory |
| 远程 CI `verify` #34492571104 | success 6m51s，含 warehouse-it/tc-it/failure-it | 远程 runner；不能替代生产部署 |

结论：S1-05 测试身份切片 pass；Casdoor 身份开通 pass；Casdoor JWT × 隔离库存 HTTP blocked（无 compose `.env`）。50 项 AC 仍 planned。

## S1-06 效果身份与重授权

环境：2026-09-10，macOS arm64、Microsoft JDK21、Docker 29.7.2、Testcontainers MySQL 8.4.11。未操作共享 dev-infra 或生产。未开始 `wms-console/`。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `RequestDigestTest` 4 项 | v2 摘要≠v1；用 v1 重放含数量字段仍等于原 v1；未知版本/动作拒绝；事实不全禁止随机身份 | 领域，不是过账 |
| `EffectHttpIT` 3 项 | 换键复用 effectId；同键异内容 409；越仓 403；OPEN/STARTED 409；UNKNOWN 202 不发新号；SAFE_CLOSED 后下一尝试；APPLIED 409 | 测试 RSA JWT；不是 Casdoor；不是 permit/余额 |
| `MasterdataMigrationIT` | 10 张表均有中文注释 | 含 V003 三表 |
| `python3 scripts/check-docs.py` | PASS documents=20 | 结构 |
| `./mvnw -B -ntp verify` | BUILD SUCCESS 47.010s | 默认构建 |
| `python3 scripts/smoke-services.py` | 三进程 health UP，业务路径拒绝 | 无 JDBC/issuer |

结论：S1-06 身份切片本地 pass。AC-47..50 仍 planned。S2 未开始。

## S2-01 库存领域类型

环境：2026-09-10，macOS arm64、Microsoft JDK21。未操作共享 dev-infra 或生产。未开始 `wms-console/`。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `InventoryDomainTest` 7 项 | 超精度拒绝；科学计数法拒绝；未知质量/HELD 拒绝；CONFIRMED 不能 TCC Cancel；HOLD/冻结可用量为 0；QUIESCING 新预占 DENY、在途 DRAIN；FROZEN 迟到事实 ISOLATE；FIFO/FEFO 无默认 | 领域，不是余额表/过账 |
| `python3 scripts/check-docs.py` | PASS documents=20 | 结构 |
| `./mvnw -B -ntp verify` | BUILD SUCCESS 46.642s | 默认构建 |
| `python3 scripts/smoke-services.py` | 三进程 health UP，业务路径拒绝 | 无 JDBC/issuer |

结论：S2-01 领域切片本地 pass。AC-03 仍 planned。S2-02 未开始。

## S2-02 余额预占流水 Mapper

环境：2026-09-10，macOS arm64、Microsoft JDK21、Docker 29.7.2、Testcontainers MySQL 8.4.11。未操作共享 dev-infra 或生产。未开始 `wms-console/`。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `InventoryTransactionIT` 3 项 | 空桶唯一键复用原 id；GOOD 预占不足 0 行；HOLD 不能走 GOOD 预占；占用超过实物 CHECK 拒绝；流水桶版本唯一；同 XID 所有者唯一；明细 requested 守恒 | Mapper/约束，不是并发过账原语 |
| `MasterdataMigrationIT` | 14 张表均有中文注释 | 含 V004 四表 |
| `python3 scripts/check-docs.py` | PASS documents=20 | 结构 |
| `./mvnw -B -ntp verify` | BUILD SUCCESS 01:00 min | 默认构建 |
| `python3 scripts/smoke-services.py` | 三进程 health UP，业务路径拒绝 | 无 JDBC/issuer |

结论：S2-02 本地 pass。AC-03 仍 planned。S2-03 未开始。

## S2-03 库存原语与同事务 Outbox

环境：2026-09-11，macOS arm64、Microsoft JDK21、Docker 29.7.2、Testcontainers MySQL 8.4.11。未操作共享 dev-infra 或生产。未开始 `wms-console/`。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `InventoryApplicationIT` 3 项 | 收货/预占/Cancel 重放不二次加量；冻结回滚 outbox=0；移库 2 条 PENDING、发运 1 条；超发 0 条 | 同会话流水+Outbox，不是领取/发布 |
| `InventoryTransactionIT` 3 项 | V005 迁移后原 Mapper 断言仍通过 | 余额约束未回归 |
| `MasterdataMigrationIT` | 15 张表均有中文注释 | 含 `outbox_event` |
| `python3 scripts/check-docs.py` | PASS documents=20 | 结构 |
| `./mvnw -B -ntp verify` | BUILD SUCCESS 01:19 min | 默认构建 |
| `python3 scripts/smoke-services.py` | 三进程 health UP，业务路径拒绝 | 无 JDBC/issuer |

结论：S2-03 本地 pass。AC-03/AC-05 仍 planned。S2-04 未开始。

## S2-04 Outbox 领取与 command_dedup

环境：2026-09-11，macOS arm64、Microsoft JDK21、Docker 29.7.2、Testcontainers MySQL 8.4.11。未操作共享 dev-infra 或生产。未开始 `wms-console/`。未配置 Kafka。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `OutboxPublisherIT` 1 项 | 成功 PUBLISHED；broker 失败后到期重试；毒消息 ISOLATED；过期 CLAIMED 可再领取 | 本库领取/结案，不是真实 broker |
| `InventoryApplicationIT` | 同键重放 1 条 dedup；同键异内容 COMMAND_CONFLICT | 与业务同事务 |
| `MasterdataMigrationIT` | 16 张表均有中文注释 | 含 `command_dedup` |
| `python3 scripts/check-docs.py` | PASS documents=20 | 结构 |
| `./mvnw -B -ntp verify` | BUILD SUCCESS 01:50 min | 默认构建 |
| `python3 scripts/smoke-services.py` | 三进程 health UP，业务路径拒绝 | 无 JDBC/issuer |

结论：S2-04 定向 IT pass。AC-03/AC-05 仍 planned。S2-04a 未开始。

## S2-04a 命令凭证与三服务协议

环境：2026-09-11，macOS arm64、Microsoft JDK21、Docker 29.7.2、Testcontainers MySQL 8.4.11。三个独立库。未操作共享 dev-infra。未开始 `wms-console/`。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `StockCommandIT` | 收货重放不加量；已过账取消保持 APPLIED；墓碑后晚到 0 流水 | T2，不是并发 |
| `InboundProtocolIT` | T1 重放；T3 同 event 不二次 posted | 入库本库 |
| `OutboundProtocolIT` | T1 发运命令；T3 记 CANCELLED | 出库本库 |
| `ThreeServiceProtocolIT` | 三容器：入库 T1→库存 T2 APPLIED→入库 T3；出库 T1→库存墓碑→出库 CANCELLED | 协议闭环，不是 Kafka |
| `MasterdataMigrationIT` | 20 张表中文注释 | 含 V007 |
| `python3 scripts/check-docs.py` | PASS documents=20 | 结构 |
| `./mvnw -B -ntp verify` | BUILD SUCCESS 02:05 min | 默认构建含入出库 failsafe |
| `python3 scripts/smoke-services.py` | 三进程 health UP，业务路径拒绝 | 无 JDBC/issuer |

结论：S2-04a 定向 IT pass。AC-03/AC-05 仍 planned。S2-05 未开始。

## S2-05 并发预占与崩溃恢复

环境：2026-09-11，macOS arm64、Microsoft JDK21、Docker 29.7.2、Testcontainers MySQL 8.4.11。未操作共享 dev-infra 或生产。未开始 `wms-console/`。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `InventoryConcurrencyIT` | 20 线程预占 10，10 胜 10 不足；on_hand=100 reserved=100 claim=0；10 条 TRIED | 真实 MySQL 并发，不是单线程 Mapper |
| `IdempotencyRecoveryIT` | 同键重放 ledger/dedup=1；异内容 COMMAND_CONFLICT | 提交后丢响应再试 |
| `OutboxCrashRecoveryIT` | 触发器 SIGNAL 后 receive 抛错；ledger/outbox/dedup=0 | 同事务回滚；需 root 开 `log_bin_trust_function_creators` |
| `python3 scripts/check-docs.py` | PASS documents=20 | 结构 |
| `./mvnw -B -ntp verify` | BUILD SUCCESS 02:45 min | 默认构建含三新 IT |
| `python3 scripts/smoke-services.py` | 三进程 health UP，业务路径拒绝 | 无 JDBC/issuer |

结论：S2-05 本地 pass。AC-03/04/05 仍 planned（缺正式黑盒与来源 PENDING 恢复）。S2-07 未开始。

## S2-07 效果锁与 posting 唯一

环境：2026-09-11，macOS arm64、Microsoft JDK21、Docker 29.7.2、Testcontainers MySQL 8.4.11。未操作共享 dev-infra 或生产。未开始 `wms-console/`。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `EffectCommandUniquenessIT` | 同事实换键复用 CMD-A；第二 part 新 posting；取消后换键仍墓碑；safeClose 后下一尝试 APPLIED；补偿换键复用；同效果第二 posting DuplicateKey | AC-47..50 基础，不是 S3/S5 黑盒 |
| `InboundProtocolIT` 2 项 | 原 T1/T3 仍过；同事实换键复用；safeClose 后 attempt_no=2 | 入库本库 |
| `StockCommandIT` / `ThreeServiceProtocolIT` / `OutboundProtocolIT` | 回归通过 | 未破坏 T2/三服务/出库 T1 |
| `python3 scripts/check-docs.py` | PASS documents=20 | 结构 |
| `./mvnw -B -ntp verify` | BUILD SUCCESS 02:53 min | 默认构建含 V008/V002 |
| `python3 scripts/smoke-services.py` | 三进程 health UP，业务路径拒绝 | 无 JDBC/issuer |

结论：S2-07 定向 IT pass。AC-47..50 仍 planned。S3 未开始。

## S3-01 入库单与质量资格

环境：2026-09-11，macOS arm64、Microsoft JDK21、Docker 29.7.2、Testcontainers MySQL 8.4.11。未操作共享 dev-infra。未开始 `wms-console/`。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `InboundReceiptIT` | 收 6/10；超收 OVER_RECEIVE；T3 重放 posted 仍 6；质检 ACCEPTED；上架 physical/posted=6 | 入库本库，不是跨库存端到端过账 |
| `QualityQualificationIT` | v2 生效；v1 乱序保持 ACCEPTED；v3 覆盖为 REJECTED | 库存资格，不改质量桶 |
| `MasterdataMigrationIT` | 21 张表中文注释 | 含 V009 |
| `InboundProtocolIT` | 回归通过 | 协议未破坏 |
| `python3 scripts/check-docs.py` | PASS documents=20 | 结构 |
| `./mvnw -B -ntp verify` | BUILD SUCCESS 03:05 min | 默认构建含 V003/V009 |
| `python3 scripts/smoke-services.py` | 三进程 health UP，业务路径拒绝 | 无 JDBC/issuer |

结论：S3-01 本地 pass。AC-07 仍 planned。S3-02 未开始。

## S3-02 序列号登记身份

环境：2026-09-11，macOS arm64、Microsoft JDK21、Docker 29.7.2、Testcontainers MySQL 8.4.11。未操作共享 dev-infra。未开始 `wms-console/`。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `SerialRegistryIT` | 两仓并发 1 胜 1 `SERIAL_ALREADY_CLAIMED`；表行=1；规范化 `sn-1`→`SN-1` 重放 | 登记库唯一性，不是库存 HOLD/激活 |
| `./mvnw -B -ntp -pl wms-serial-registry -am verify` | BUILD SUCCESS 14s | 新模块 |
| `python3 scripts/check-docs.py` | PASS documents=20 | 结构 |
| `./mvnw -B -ntp verify` | BUILD SUCCESS 03:16 min | 默认构建含新模块 |
| `python3 scripts/smoke-services.py` | 三进程 health UP | 仍只三进程，未拉登记服务 |

结论：S3-02 定向 IT pass。AC-08 仍 planned。S3-03 未开始。

## S3-03 HOLD 收货与登记激活

环境：2026-09-11，macOS arm64、Microsoft JDK21、Docker 29.7.2、Testcontainers MySQL 8.4.11。未操作共享 dev-infra。未开始 `wms-console/`。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `SerialRegistryActivateIT` | CLAIMED→ACTIVE；重放保持 ACTIVE；他仓 `SERIAL_OWNER_MISMATCH` | 登记库，不是库存放行 |
| `SerialReceiptIT` | HOLD on_hand=1 且 AUTHORIZED；GOOD 桶预占 `STOCK_INSUFFICIENT`；登记 down 保留 EXCEPTION+HOLD；恢复后 AUTHORIZED 质量仍 HOLD | 库存+内存登记端口，不是两库真实 HTTP |
| `MasterdataMigrationIT` | 22 张表中文注释 | 含 V010 |
| `./mvnw -B -ntp -pl wms-inventory,wms-serial-registry -am verify -Dit.test=SerialReceiptIT,SerialRegistryActivateIT,MasterdataMigrationIT` | BUILD SUCCESS 39s | 定向 |
| `python3 scripts/check-docs.py` | PASS documents=20 | 结构 |
| `./mvnw -B -ntp verify` | BUILD SUCCESS 03:25 min | 默认构建含 V010/activate |
| `python3 scripts/smoke-services.py` | 三进程 health UP，业务路径拒绝 | 仍只三进程，未拉登记服务 |

结论：S3-03 本地 pass。AC-08/09 仍 planned。S3-04 未开始。

## S3-04 FEFO 与入库负例

环境：2026-09-11，macOS arm64、Microsoft JDK21、Docker 29.7.2、Testcontainers MySQL 8.4.11。未操作共享 dev-infra。未开始 `wms-console/`。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `FefoCandidateIT` | 候选 NEAR→FAR，过期批次不入列；预占过期 `LOT_EXPIRED`；发运位 `INVALID_PUTAWAY_LOCATION` | 库存本库 |
| `SerialReceiptIT` 重复/两仓 | 同仓第二操作 `SERIAL_ALREADY_RECEIVED`；两仓并发 1 AUTHORIZED / 1 EXCEPTION，两仓均留 HOLD | 内存登记端口 |
| `InboundReceiptIT` 质检/库位 | 未质检 `QC_REQUIRED`；全拒 `QC_REJECTED`；SHIPPING `INVALID_PUTAWAY_LOCATION`；putaway 实物仍 0 | 入库本库 |
| `InventoryDomainTest` 效期 | 左闭右开：相等时刻不满足 | 单测 |
| 定向 `verify` | BUILD SUCCESS 41s | 上述用例 |
| `python3 scripts/check-docs.py` | PASS documents=20 | 结构 |
| `./mvnw -B -ntp verify` | BUILD SUCCESS 04:00 min | 默认构建含 FEFO/上架校验 |
| `python3 scripts/smoke-services.py` | 三进程 health UP，业务路径拒绝 | 仍只三进程 |

结论：S3-04 本地 verify pass。AC-08/09/15 仍 planned。S3-05 未开始。

## S3-05 收货观察与分批额度

环境：2026-09-11，macOS arm64、Microsoft JDK21、Docker 29.7.2、Testcontainers MySQL 8.4.11。未操作共享 dev-infra。未开始 `wms-console/`。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `ReceiptObservationIT` 重放/第二批 | 同序号换命令键仍 `CMD-O1`；第二批累计 physical=10；第三批 `OVER_RECEIVE`；`CMD-O1B` 不落命令 | 入库本库，不是库存过账 |
| `ReceiptObservationIT` 隔离/重置会话 | 空设备 `AMBIGUOUS_OBSERVATION`；异数量 `OBSERVATION_CONFLICT`；新会话复用 `CMD-A1`，实物仍 3 | 入库本库 |
| `InboundReceiptIT` / `InboundProtocolIT` | 回归通过 | 未破坏质检上架与 T1 换键 |
| 定向 inbound `verify` | BUILD SUCCESS | 上述用例 |
| `python3 scripts/check-docs.py` | PASS documents=20 | 结构 |
| `./mvnw -B -ntp verify` | BUILD SUCCESS 04:38 min | 默认构建含 V004 |
| `python3 scripts/smoke-services.py` | 三进程 health UP，业务路径拒绝 | 仍只三进程 |

结论：S3-05 本地 pass。AC-47 仍 planned。S4 未开始。

## S4-01 履约 attempt/XID/participant 映射

环境：2026-09-11，macOS arm64、Microsoft JDK21、Docker 29.7.2、Testcontainers MySQL 8.4.11。未操作共享 dev-infra。未开始 `wms-console/`。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `FulfillmentMappingIT` 重放/冲突 | 同源同摘要复用单头单行；异摘要 `ORDER_CONFLICT` | 履约本库 |
| `FulfillmentMappingIT` XID/证据 | 绑定一次；改绑 `XID_ALREADY_BOUND`；仓分支改绑 `BRANCH_ALREADY_BOUND`；缺证据/未确认仓拒绝 ALLOCATED | 不是真实 TC/RM |
| `FulfillmentMappingIT` 并发启动 | 同 XID 第二 attempt `XID_CONFLICT`；两执行器 claim 1 胜 1 `LAUNCH_CAS_LOST` | 履约本库 CAS |
| `python3 scripts/check-docs.py` | PASS documents=20 | 结构 |
| `./mvnw -B -ntp verify` | BUILD SUCCESS 04:22 min；fulfillment 3 项 0 失败 | 默认构建，不含 warehouse-it/tc-it/failure-it |
| `python3 scripts/smoke-services.py` | inbound/outbound/inventory/fulfillment health UP，业务路径拒绝 | 独立进程；未接履约 JDBC |

结论：S4-01 本地 verify pass。AC-10/12/41 仍 planned。S4-02 未开始。

## S0 剩余：XXL admin 真实触发与候选 SBOM

环境：2026-09-11，macOS arm64、Microsoft JDK21、Docker 29.7.2。官方 `xuxueli/xxl-job-admin:3.4.2` 为 linux/amd64，本机经模拟。未操作共享 dev-infra。未开始 `wms-console/`。未发明 OQ-03。

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `./mvnw -B -ntp -pl wms-test-support -am -Pwarehouse-it -Dsurefire.skip=true -Dit.test=XxlAdminTriggerIT verify` | 1 项通过；日志 `XXL_ADMIN_TRIGGER: official 3.4.2 admin dispatched BEAN handler; executor did not hold TCC` | 官方 admin `/auth/doLogin` + `/jobinfo/trigger`；执行器清理 TCC。不是集群/分片 |
| `./mvnw -B -ntp -pl wms-test-support -am -Pwarehouse-it -Dsurefire.skip=true verify` | 11 项，失败 0，01:24 | warehouse-it 含上述触发；其余分片/Fence/Kafka/线程池回归 |
| `./scripts/generate-sbom.sh` 产物 | CycloneDX 75 组件；THIRD-PARTY 285 条；OSV 67 purl / 1 命中 | 候选快照，不是生产锁 |
| `python3 scripts/check-docs.py` | PASS documents=21 | 结构 |
| `./mvnw -B -ntp verify` | BUILD SUCCESS 04:05 min | 默认构建未激活 `-Psbom` |

限制：Tomcat embed 11.0.24 有 3 条 GHSA，未 bump Spring Boot。`xxl-job-core` 许可证记为 GPL-3。ST4 4.3 许可证未知。probe 不是生产 TC 或履约交付。
