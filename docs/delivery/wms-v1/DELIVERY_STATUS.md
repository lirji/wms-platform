# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S9-05 证据汇总。route-gate + AC-24 HTTP IT 已在远程 main `4dee112`。
- 任务分支：`feat/contract-http-gaps`（合入远程 main `16b94f0` 后发布）。
- 未发明 OQ-03。S8-05 无授权设备。S9-01 无签署容量输入。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户要求做到 S9 / 50 AC 且不必逐步确认。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、把 simulator 当真实设备、把合成峰值当签署容量。
- 本轮用户要求先停止进行中的 main verify，再把 `feat/contract-http-gaps` 推送到远程 main。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | `4dee112` 任务分支 verify `34672595394` success（含 tc-it）；main `34673286277` 被后续 push 取消；`a5ea7ad` `34674304734` 在 45m timeout 处取消（tc-it 已绿） |
| EG-05 外部与非功能 | running | S9-01 / S8-05 / AC-42 仍 blocked |
| Git发布 | pass（本切片） | 用户要求取消 main verify `34689089236` 后推送 `feat/contract-http-gaps` |
| S9-05 50 AC | fail | 见 [AC_EVIDENCE.md](AC_EVIDENCE.md) / [DELIVERY_REPORT.md](DELIVERY_REPORT.md) |

## 本轮

- 2026-09-12：控制台 README 写明本机 Vite `4181` 与门户 Docker `18180` 分工。不是 50 AC。
- 2026-09-12：`verify.yml` 在提交说明或 PR 标题含 `[skip ci]` / `[ci skip]` / `[no ci]` 时跳过 java 与 console。不是 50 AC。
- 2026-09-12：扩展 `seed-local.sh`，向隔离库存库写开账余额/投影/草稿盘点，向应用库写入库/出库/履约/调拨演示单。已发布远程 main `38ef86e`。未发明 OQ-03，未写 TCC ALLOCATED。不是 50 AC。
- `requireWritable` 不再把分片未声明/缺表伪装成停写；`TccFenceShardingIT` 纳入 `warehouse_route`。
- AC-24 HTTP：ISO cutoff、测试 JWT POST/GET、跨仓 403、数量非金额。
- 本地：`WarehouseRouteGateTest` 1/0；`TccFenceShardingIT` 1/0；`WarehouseMigrationIT` 2/0；`SnapshotHttpIT` 1/0；recon `WmsExportContractTest` 1/0。
- 2026-09-12：根目录 `compose.yaml` 在容器内编译启动五服务与 console，已在远程 main `87a233b`。
- 2026-09-12：控制台登录/`returnTo`/OIDC 已在远程 main。
- 2026-09-12：按 `docs/design/console-frontend/` 重做信息架构并落地作业模块。
- 2026-09-12：Casdoor + inbound/outbound/inventory/fulfillment 只读联调与浏览器新路由已走通；任务页改绑已实现的 `GET /jobs?warehouseId=`。
- 2026-09-12：控制台改为 Ant Design 作业台（侧栏、KPI、密表）。
- 2026-09-12 F5：作业详情接到已落地写命令；跨仓 ALLOCATED 仍要真实 TC。已发布远程 main `f466efc`，Docker 控制台与四服务已按该提交重建。不是 50 AC / AC-26 accepted。
- 2026-09-12 AC-26 现场走查：Casdoor 收货→质检→上架→跨仓准备（PLANNED）→拣→部分发→未拣回库。重复行主键改为 409。证据 [AC26_LIVE_WALK.md](AC26_LIVE_WALK.md)。仍 open。
- 2026-09-12 F6：队列页抽屉建单、单据命令抽屉、首页活队列、401≠403；Casdoor 作业 scope 已补全。需重新登录。不是 50 AC / AC-26 accepted。
- 2026-09-12 前端架构技能复查：不另起 IA。F7 已落地（登录一列、权限按 scope 隐藏、列宽/cursor/错误码、单据 Tabs）。用户要求合入远程 main 并重建 Docker console。不是 50 AC / AC-26 accepted。
- 2026-09-12 F8：公开契约里已有领域的缺口接到 HTTP 与控制台。主数据写、仓任务 list/get/claim、流水/operation/效果、catalog 建档、jobs 仓任务、stock 流水、recon 导出快照。ALLOCATED 不编造。TP99 unverified。不是 50 AC / AC-26 accepted。
- 2026-09-12 F9：补齐缺领域公开路径。移库/限制/独立调整（V021，限制≠location_gate，调整≠count_plan）；履约取消 202 只受理；出库 execution-authorizations 缺 Committed 证据则 409，出库单 ALLOCATED ≠ 履约 ALLOCATED。`DomainHttpIT` / `FulfillmentHttpIT` / `OutboundHttpIT` / `OutboundPickIT` / console 33 测通过。列表调整无 L1+L2；TP99 unverified。不是 50 AC / AC-26 accepted。

## 未完成

- S8-05。S9-01 签署峰值。50 AC 全量证据。OQ-03。AC-26 真实 TC ALLOCATED。AC-42。用户已要求取消进行中的 main verify 并推送本分支。

无生产部署。


## 后端评审整改（当前状态）

用户要求先将当前阶段改动提交、推送任务分支并合入远程 main；剩余整改保持未完成，不将此次 Git 发布等同于完整验收。

- R01–R12、R16–R21、R23–R24：代码已实现并有定向验证；容量执行器通过不代表实际容量达标。
- R13：收货双进程闭环、投影、审计重试已通过；用户已确认按收货分批；分批质检累计版本、守恒转桶、回执及失败恢复双进程验证已通过。分批上架、批次游标已真实双进程验证，控制台已接批次选择；出库等仍待。
- R14：登记服务真实 HTTP/数据库/鉴权定向通过；库存适配与真实 TM/TC 待接。
- R15：六个任务已接通；有界内部对账、归档候选规划真实数据库通过，序列号恢复仍待。归档尚未导出或删除。
- R22：固定数据库偏移、旧库来源门禁、UTC Map/HTTP/消息与游标跨 JVM 验证通过；最终全量验证仍待，未转换共享/生产历史数据。仓迁移允许表清单遗漏另需修复。
- Git：fix/backend-review-remediation 基于 db02821，14 个任务提交至 7e258d0 已推送任务分支；独立 worktree 快进集成无冲突。默认完整回归 99 类/200 用例及 55 必需门禁、四进程 smoke 全部通过。分支 CI 34703330446 的并发测试假设错误已修正并定向通过；本次阶段发布包含该补充修正，目标 origin/main，新提交远程 CI/组合 profiles 待核验。

证据见 [BACKEND_REMEDIATION.md](BACKEND_REMEDIATION.md)。S8-05、S9-01、OQ-03、真实 TC、50 AC 保持未验收；没有生产部署。

## R14 登记转移与盘点恢复入口

新增 MISSING、FOUND 认领/激活、转移准备、源仓释放、目的接收/确认及转移查询 HTTP；统一服务主体、企业、仓范围、scope、幂等审计。准备校验两仓，释放核对真实源仓，查询仅源或目的仓。重放继续核验原始 fromEpoch 和事实引用；补全 FOUND 的 CLAIMED/ACTIVE 丢回执恢复，错误操作不得借 ACTIVE 获得成功。规范化固定 Locale.ROOT。

2026-09-13 01:17:14 定向 registry verify BUILD SUCCESS（SerialRegistryHttpIT 2 用例、SerialRegistryIT、SerialRegistryActivateIT及依赖单元；日志 /tmp/wms-registry-transfer-http-it.log）。真实隔离 MySQL、RSA验签HTTP覆盖早到接收、伪造源仓、epoch不符、转移完成及FOUND重放。OpenAPI更新为85路径；verify-contracts需在产物提交后核验其无diff规则。库存有界HTTP适配、恢复和TM/TC仍未完成。

## R14/R15 有界登记调用与持久化恢复

库存新增真实HTTP端口适配，连接500ms/总请求1500ms、响应64KiB、8全局/2租户并发和32/8每秒预算、固定线程与队列；不隐式重试、不重定向，响应授权必须匹配原操作/归属。外部按企业JWT文件轮换，未配置不假装成功。首次登记ACTIVE现在保存原收货引用，兼容旧记录只在同仓同认领且从未转移时补齐，不能借旧认领重放目的仓授权。

V033持久化原始序列号登记意图与HOLD同事务；stageHold/stageDestination明确先记本地事实。serialTransferRecovery真实执行器在库存事务外调用登记，每次20条/20秒、每条最多12次，失去回执或最后提交失败可从原操作恢复。领取epoch、本地version及共享仓路由锁阻止旧执行器或已停写源仓继续放行；归属授权不改变HOLD质量。V034人工重排与审计原子提交，messaging.read/recover及仓范围、稳定分页、期望epoch和reason必需，重排递增epoch不重置。

本地唯一键继续采用更严格的企业/仓/序列号，重放核对SKU/批次/库存桶并明确拒绝超过64字符。对账序列号数量计入有实物的EXCEPTION/RECEIVING/HOLD，排除已扣量SEALED；真实双库测试验证源SEALED数量0和目的HOLD数量1。部署配置与边界见 [SERIAL_REGISTRY_RUNTIME.md](../../implementation/SERIAL_REGISTRY_RUNTIME.md)。

证据：/tmp/wms-registry-client-test.log 01:20:10，3个真实HTTP传输故障用例；/tmp/wms-serial-ops-final-it.log 01:33:51通过registry HTTP2、库存HTTP12及双库进程；/tmp/wms-serial-route-final-it.log 01:35:57 BUILD SUCCESS，最终代码的库存HTTP12、SerialReceiptIT4、SerialSealIT1、SerialTransferRecoveryIT1、SerialRegistryProcessesIT1及单元。前序对账4/CountSerialIT2于01:32定向组合中通过，该组合新增HTTP异常映射和SKU夹具失败已修复，不能把前序整组写成通过。required默认门禁新增3项，总61。

R13消息侧序列号观察/质量/移位与转移源释放传播尚未接通；盘点旧同步用例须先逐身份持久化进度，避免限流后每次重放整行，当前未接入这种不完整HTTP循环。R14真实TM/TC和出库授权传播仍待。未操作生产或共享库。

## R18/R22 仓迁移补齐与隔离修复

迁移清单从24扩展至47张仓范围表，涵盖盘点、消息接收/恢复、对账/快照、归档候选和序列号恢复；元数据真实测试约束新增仓表不能遗漏，路由及物理数据库时间规则不按业务表覆盖。两库必须已登记相同时间来源；目标只允许对应迁移的COPYING状态。每批200行，逐行验证复制结果，其他范围主键碰撞、唯一身份冲突和不可变审计/流水内容不同均回滚当前批次，不再IGNORE吞差异。

既有冻结/维护不能被迁移覆盖，开放门禁仅限本次WAREHOUSE_MIGRATION；新切流必经计数/库存数量校验，未复制目标不得激活。目标已提交激活而源提交失败时，仅相同源/目标/epoch精确重放完成源收尾，不覆盖目标新写，也不打开后续新冻结。

真实两库证据：/tmp/wms-migration-expanded-it.log 01:39:39通过基础回归；/tmp/wms-migration-gates-final-it.log 01:43:54通过迁移4用例及IsolatedRestoreIT；/tmp/wms-migration-validation-final-it.log最终WarehouseMigrationIT5用例及单元BUILD SUCCESS，覆盖完整仓表目录、恢复代际/正文/微秒值、同批回滚、跨仓目标保护、时间来源冲突、冻结拒绝、目标提交/源失败重放和未复制拒绝。默认required门禁64项。隔离恢复实测仅属于该夹具，不能代替生产RTO/RPO。

整体运行边界仍见 [WAREHOUSE_MIGRATION_LIMITS.md](../../implementation/WAREHOUSE_MIGRATION_LIMITS.md)：共享目录准备、真实TM/RM/Fence回调迁移及所有后台写入排空尚待R13/R14整体验证，未执行生产或共享仓迁移。

## R14 TC只读审计与分配恢复屏障

已实现 [FULFILLMENT_TC_RECOVERY.md](../../implementation/FULFILLMENT_TC_RECOVERY.md)：显式集群/TM/事务组绑定与XID同事务、审计SELECT专用池、真实TC终态读取和受限就绪探针。XXL恢复缺实现不再零项成功；每轮20项/20秒，逐项短事务和跨进程企业游标，TC网络调用不持业务锁，回写复核原XID/启动代际/参与者摘要。

修复分配可仅凭CONFIRMED文本而缺分支身份放行、改绑预占/路由代际、终态证据回退、非当前attempt放行，以及INSERT IGNORE吞掉Outbox约束失败。ALLOCATED和完整屏障Outbox同事务，重复事件核对原操作键和正文；合法字符串的控制字符由JSON库转义。25项跨页续跑、坏历史来源不饿死后续、最后Outbox写失败全回滚、旧代际返回与同集群跨企业XID冲突均进入真实数据库验证。

本切片仍不等于完整R14：正式TM发起/库存RM服务调用、仓确认消息、出库授权传播及序列号剩余运行路径继续实施。没有部署TC触发器到生产或共享库，也未迁移历史未知来源的attempt。真实TC测试的仓级确认来自明确夹具，不把它称作真实库存RM业务验收。

验证：2026-09-13 02:05:32 `/tmp/wms-tc-fulfillment-final-it.log` BUILD SUCCESS，TcAuditRecoveryIT 4、FulfillmentMappingIT 3、FulfillmentBarrierIT 1、AllocationRecoverySweepIT 1、FulfillmentHttpIT 2，及依赖单元全部通过。02:04:03 `/tmp/wms-tc-barrier-combined-it.log` BUILD SUCCESS，库存侧重新编译当前履约源码的 ClosedLoopBlackBoxIT 1 通过。默认必需用例清单68项，尚待最终全量组合。先前测试暴露的INSERT IGNORE吞CHECK问题已修复；端口竞争及测试TC清理延迟已通过隔离夹具修正，未改变生产TC行为。


## R13/R14 库存确认到履约的可靠消费

[FULFILLMENT_CONFIRMATION_MESSAGING.md](../../implementation/FULFILLMENT_CONFIRMATION_MESSAGING.md)记录新确认契约和`${topicPrefix}.fulfillment.results`：库存Confirm同事务保存原分配/attempt/XID/branch/action/route，履约只更新已绑定参与者。缺原Try回执等待，错误身份隔离；确认、ALLOCATED/屏障Outbox与Inbox DONE同事务。重复和晚于截止的原分支回执只读取原绑定，不把CONFIRMED回退TRIED。新未知版本在库存Outbox隔离，旧无版本不猜测补事实。

V014以追加迁移修复历史父目录Inbox漏扫，V015增加原分配确认和Outbox投递预算字段；库存测试依赖履约确保干净reactor先打包真实Jar。新增履约积压指标、受审计恢复及Compose配置，默认不开启消息或TC审计；未启动生产或共享环境。

证据：`/tmp/wms-confirmation-verified-it.log` 2026-09-13 02:21:36 BUILD SUCCESS，FulfillmentInboxMigrationIT 1、FulfillmentHttpIT 2、ReservationTccIT 3和双进程FulfillmentConfirmationProcessesIT 1通过。最后迟到回执修复后，`/tmp/wms-confirmation-replay-final-it.log` 02:24:47 BUILD SUCCESS，FulfillmentMappingIT 3、FulfillmentBarrierIT 1和双进程确认1再次通过。验证旧Inbox原正文/微秒/epoch=7保留、审计重排不降代际、双服务最终Inbox失败全事务回滚与重启恢复、重复/错分支/未知契约处理；Compose静态config及文档/契约检查通过，默认必需用例70项。

Try与TC证据是本消息测试明确提供的夹具，真实TC只读适配已有独立证据，不能拼成真实TM/RM全链。履约屏障Outbox发布器、出库授权消费、PICK/SHIP/CANCEL、序列号观察与盘点逐身份恢复继续实施。

## R13 出库可靠消息与原预占归属

已接通[出库消息](../../implementation/OUTBOUND_MESSAGING.md)：PICK/SHIP/CANCEL经真实Kafka在出库/库存两服务间执行T1/T2/T3。来源冻结实际桶和原业务订单行；库存按原CONFIRMED分配/attempt及订单行消费，不能借用同桶其他行。多次拣货形成的子行可一次发运，每命令最多消费200条，201条可按200+1继续。凭证关联真实sourceExecutionId，完整摘要拒绝同键改绑。

来源桶级额度随PICK回执增加、SHIP首次受理条件扣用，与原命令同事务；不扫描完整历史。CANCEL按桶支持部分数量，只释放预占、不生成虚假RESTOCK，取消posted列收到回执才累计，旧任务取消后不能接新分批。消息关闭时保留旧客户端兼容，启用后必需原库位/批次，页面与87路径OpenAPI同步；Compose补齐出库和履约确认Topic，消息开关默认false。

证据：/tmp/wms-outbound-bucket-final-it.log 2026-09-13 02:49:43 BUILD SUCCESS，出库双进程1、预占身份/并发/201分批真实MySQL3、入库分批双进程1、出库HTTP1/领域5/协议2及单元通过。最终回执信封与状态URL修正后，/tmp/wms-outbound-replay-final-it.log 02:51:39 BUILD SUCCESS，重新构建入库/出库/库存Jar，出库双进程1与HTTP1再通过。测试覆盖最后Outbox/Inbox写失败的全事务回滚、重启恢复、重复/换桶拒绝、错误来源动作/回执信封隔离。控制台33用例/typecheck/build通过，最后数量字段定向测试/build通过。默认必需用例74项。

预占Try/Confirm和TC授权是该消息测试明确提供的夹具，不能据此声称真实履约TM/RM和授权传播完成。序列号PICK/SHIP需后续观察身份链；CANCEL不更改序列号。可信来源水位、盘点逐身份登记及最终组合CI继续实施。

## 2026-09-13 当前阶段组合验证

业务源码c5348d2通过全仓clean verify：108个测试类、223用例，失败/错误/跳过均0，默认必需74项通过；四个实际Jar独立进程smoke通过。failure-it3项及必需门禁通过，warehouse-it12和tc-it2复用同一未变test-support的通过证据，远程CI仍将重跑。Compose出库消息前缀已统一为环境配置，默认和自定义值验证通过。SBOM更新为173组件/163个purl，OSV仍为原有2项命中，未静默升级依赖。

详细范围和日志索引见[阶段组合验证](../../implementation/REMEDIATION_VERIFICATION_2026-09-13.md)。准备正常推送这11个已验证逻辑切片及验证记录到远程main；本地通过不替代远程CI。R22代码与当前组合证据完成；R13/R14/R15剩余序列号观察、逐身份盘点、可信水位、正式TM/RM与授权传播继续执行。未部署生产、未转换共享历史数据，不代表50AC全部验收。

## R14 履约授权可靠投递

[履约授权消息](../../implementation/FULFILLMENT_AUTHORIZATION_MESSAGING.md)已实现明确货主、同源原始事实核对、成功屏障同事务完整快照、逐条有界发布器、出库建单/TC证据/授权/Inbox原子消费。支持授权先到及原消息重放；换货主、改数量、错TC来源、未知版本拒绝。旧NULL来源不能猜测回填；恢复审计保存实际快照，旧最小事件不可直接重排。

03:28:53定向组合通过9个IT用例及单元；03:30:41取消后原授权重放与真实双进程复验通过。03:31:35首次授权前取消及3个快照用例通过；默认必需清单78项，控制台33/typecheck/build及Compose静态检查通过。正式TM/RM调用、序列号观察/盘点逐身份和可信水位继续，不能把本例的明确TC/Try前置夹具称为完整业务链。上一阶段95a1d46的main与任务分支CI均成功；本授权切片bd6adf0已正常快进发布main和任务分支，新CI待核验。

## R14 原生TC分支登记与库存RM

[原生RM与TM适配](../../implementation/RUNTIME_TCC_RM.md)已通过真实TC、两个库存Jar和数据库回归。V036先落登记意图再访问TC，未知结果不重复登记；已知原branch可恢复Try。企业/仓/cell/代际校验覆盖空回滚，与Fence、库存、Outbox在十秒物理事务内提交。应用及资源按cell隔离，HTTP只接受履约服务JWT；TC连接与通告地址分别治理，readiness覆盖真实协议连接。

04:04:52定向组合13项通过（TM审计4、原生进程1、网关2、迁移6），必需清单82项；默认关闭Compose已静态核对。进程重启恢复原分支、TC断连/恢复、数值版本拒绝和迁移终态门禁已验证。履约自动执行器、TC终态通知/资源迁移、序列号观察/逐身份盘点和可信来源水位继续实施；本切片未部署生产。远程main已含bd6adf0授权切片，其CI仍运行中，当前RM准备提交。

### 创建attempt命令回执进展

V017及持久化回执完成，原请求重试保持原attempt及截止时间；最后回执失败整体回滚。04:14:20定向5项通过（AttemptCommandIT 3、FulfillmentHttpIT 2），日志/tmp/wms-attempt-command-it.log；必需清单85。0a1ec85已发布main/任务分支，bd6两路CI成功；新RM CI待结果。履约自动执行器仍在继续，不宣称四项整体完成。

## R14 持久化履约执行器

[自动分配执行](../../implementation/FULFILLMENT_EXECUTION.md)新增V018执行命令/固定Try输入/启动状态/租约代际和原XID恢复；V019保存空事务清理的原TC回滚证据。公开执行入口需要完整原货主/仓/行/桶及权限，默认关闭。旧命令重试不begin，已发提交请求不因超时或晚取消转向回滚；业务放行仍依赖真实TC持久证据和所有仓确认。

04:32:00 /tmp/wms-execution-final-it.log最终10IT通过，含实际fulfillment TM+双库存RM+outbound Jar、TC/Kafka/5库；最后Try回执写失败后重启保持原XID/branch并到达两仓出库授权。必需清单91、公开契约88路径。多cell普通库存消息路由、序列号/逐身份盘点/可信水位、TC资源迁移和晚取消补偿继续执行，不能把这次成功链路当作剩余四项全部完成。

## R13 多cell普通库存消息路由

[路由与恢复规则](../../implementation/INVENTORY_CELL_MESSAGING.md)已实现每cell消费组、完整清单筛选和同事务数据库代际校验；未知仓与陈旧路由隔离。04:42:04真实进程2IT及路由/信封单元通过，四Jar smoke、必需91、契约88、文档及Compose静态检查通过。RM重启readiness修复独立提交7ec2fbc。序列号来源身份、逐身份盘点与可信水位等仍继续。

## R13 按收货批次的序列号观察

[收货身份规则与证据](../../implementation/SERIAL_RECEIPT_BATCH.md)已接通公开可选观察、来源T1固定清单、库存V037与stage-only身份绑定、持久登记恢复意图；每批最多200，数量只入账一次。04:54:18真实MySQL及进程组合13IT通过，04:55:17追加同原命令两身份的真实登记HTTP恢复通过；迁移清单49表含稳定id及完整JSON复制验证。必需95、API88。QUALITY/PUTAWAY/PICK/SHIP序列号仍保持拒绝，继续补明确身份规则，不能把这一切片当作R13/R14/R15全部完成。

## R13 序列号按身份质检

[身份质检规则](../../implementation/SERIAL_RECEIPT_QUALITY.md)已接通原批次累计合格/不合格名单、登记授权与本地身份校验，数量和身份在同一事务转桶；旧命令重放不回退较新身份状态，等量交换也不能绕过预占或已移出收货位的约束。05:03:09真实MySQL及进程组合10IT通过，必需清单98、API88。来源序列上架/出库与逐身份盘点、可信水位仍继续，四项尚未整体关闭。

## R13 序列号分次上架

[逐身份上架](../../implementation/SERIAL_PUTAWAY.md)已接通来源V016的唯一身份占用、当前已生效质量名单校验、所选身份与原任务命令固定；库存同事务移动数量和所选身份，原命令重放不拉回后续已移动的身份。05:12:06组合12IT通过，含来源最后任务写失败及库存最后身份写失败回滚、同SN不同任务重复领取、原任务换SN和质检等量替换已领取身份的拒绝；必需100、API88。序列PICK/SHIP、盘点逐身份恢复、转移来源释放和可信水位继续。

## R14/R15 序列号源释放可靠恢复

[源释放恢复](../../implementation/SERIAL_SOURCE_RELEASE.md)将原转移/epoch/释放引用与源扣减和SEALED同事务记录，恢复在事务外调用真实登记HTTP；原转移历史凭证支持回执丢失后跨下一轮转移重放，不改当前归属。每类10秒领取预算、每条12次上限、租约代际、隔离审计重排与迁移50表已接入。响应凭证显式协商，旧请求字段集合不变；源门禁冻结和桶占用阻止扣减。

首轮05:22:25组合26IT、4HTTP单元通过；最终兼容/门禁复验05:24:43组合6IT及4单元通过，必需102/API88/文档48。公开调拨入口仍待调用序列号事实链，不能把领域事务和后台恢复证据称作公开调拨全链；序列PICK/SHIP、逐身份盘点、可信水位、TC迁移及晚取消补偿继续。

## R14 盘点完整序列观察输入

[盘点观察](../../implementation/COUNT_SERIAL_OBSERVATION.md)新增可选完整身份清单，空集合表示全部未见；V039固定原类型及规范输入，同编号不能等量换SN或降为数量观察。原观察在审批/调整/完成后仍可返回原FOUND/MISSING结果，不从当前身份重算；新编号轮次递增，旧重放不回退最新点数。最终观察子行失败回滚输入及点数，缺原输入的历史序列观察不推测回填。

首轮05:31:37组合28IT通过；最终05:33:53身份/HTTP/迁移组合24IT通过，含JSON小数版本拒绝与空集合复制。必需105、API88。当前单桶完整身份上限200，超过预算明确拒绝，不伪装分段观察已完成；逐身份事务外登记恢复仍在下一切片。
