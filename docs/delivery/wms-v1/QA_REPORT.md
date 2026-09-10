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
- Kafka 生产/消费与线程池 XID 隔离已有 warehouse-it 探针；HTTP 网关 Try 已有 tc-it 探针。XXL admin 触发、正式履约服务、全链路、外部设备/UI/对账/容量均未验收。

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
