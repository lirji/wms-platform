# Codex Progress

## 任务目标

按已批准计划做到 S9 与 50 项 AC；本轮先整改 R16–R20，再 R01–R04、R05–R12、R13–R15/R21/R24、R22–R23。未发明 OQ-03。

## 已完成

- 2026-09-12 控制台 README：本机 Vite `4181` 只给本地开发，能力门户走 Docker `18180`。不是 50 AC。
- 2026-09-12 `verify` 支持提交说明 / PR 标题 `[skip ci]`、`[ci skip]`、`[no ci]` 跳过。不是 50 AC。
- 2026-09-12 演示种子扩围：`seed-local.sh` 幂等写入开账库存/投影、草稿盘点、入出库/履约/调拨演示单（attempt 保持 PLANNED），并写入隔离库 `18306/18307/18308`。已发布远程 main `38ef86e`（含 `9d6d8f1`）。不是 50 AC。
- WMS S0–S7 及 S8-01/S8-04/S9-02/S9-03/S9-04/S9-06 在更早的 `origin/main`。
- 控制台登录与 Docker 编排已在远程 main `1ca61a8`。
- 前端架构重设计 F0–F4（2026-09-12）：单应用、仓写入 `/w/:warehouseId`、PDA `/pda/:warehouseId/receive`，无页面 Mock 表。
- 2026-09-12 现场 Casdoor + 四服务只读联调与 Ant Design 作业台。
- F5（2026-09-12，`feat/console-command-wiring`）：作业详情接到已落地写命令。
- 2026-09-12 AC-26 现场：Casdoor `wms-ops` 走完收货→质检→上架→准备跨仓→拣→部分发→未拣回库；attempt 为 PLANNED 不是 ALLOCATED。见 `docs/delivery/wms-v1/AC26_LIVE_WALK.md`。
- F6（2026-09-12，`feat/console-ops-density`）：建单/作业命令进抽屉；首页 KPI + 入库/出库/任务活队列；401=会话过期，403 才展示仓与 scope；顶栏 Popover 显示令牌权限。Casdoor 已为 `wms-ops` / `wms-wh-a` / `wms-wh-b` 写入 42 个 OpenAPI 作业 scope；`wms-denied` 仍无作业权限。
- F7（2026-09-12，`feat/console-ops-density` `4f63771`）：登录一列；Ant token 单轨；列宽/复制 id；URL `q`/`cutoffId`/`cursor`；无 scope 不画命令；409 不 dump JSON；单据抽屉分页签；路由懒加载。用户已要求合入远程 main。
- F8（2026-09-12，`feat/contract-http-gaps`）：有领域的公开契约缺口已接 HTTP 与页面。主数据写/按 id 读；仓任务按 `taskType` 分 inbound PUTAWAY / outbound PICK|RESTOCK；领取；库存流水与 operationId；效果列表；catalog 建档；jobs 仓任务；stock 流水；recon 次要导出快照。未编造 ALLOCATED。TP99 unverified。
- F9（2026-09-12，`feat/contract-http-gaps`）：补齐先前缺领域的公开路径。同仓移库/库存限制/独立调整有表与内核过账（限制≠盘点冻结，调整≠count_plan）；履约取消只落 `CANCEL_REQUESTED`（202≠成功）；出库执行授权必须核验本库 TCC Committed 证据副本，不发明履约 ALLOCATED。控制台库存台账与履约/出库详情已接线。列表调整无 L1+L2（与现有盘点/库存列表一致）。TP99 unverified。

## 未完成

- S8-05 真实设备。S9-01 签署容量。OQ-03。跨仓 ALLOCATED 真实 TC。50 AC 全量通过。AC-26 仍 open（不是 UI accepted）。
- 出库 TCC 证据副本尚无履约 outbox 消费写入；测试用 JDBC 插入，没有公开“发明证据”接口。

## 下一步

S8-05 / S9-01 / AC-42 保持 blocked。用户已要求取消进行中的 main verify 并推送 `feat/contract-http-gaps` 到远程 main。已登录用户须重新登录拿含 `stock.move`/`stock.hold`/`fulfillment.cancel`/`adjustment.*` 的 JWT。硬刷新 `127.0.0.1:18180`。不把 F9 写成 50 AC 或 AC-26 accepted。


## 当前整改执行

- 用户已批准全部24项，按 R16–R20 → R01–R04 → R05–R12 → R13–R15/R21/R24 → R22–R23 连续完成；不要停在首批或等待“继续”。唯一清单为 DELIVERY_PLAN.md 的后端整改表。
- 分支 `fix/backend-review-remediation`，基于远程 main `db02821`；原工作树干净，首批R16–R20已精确暂存，R01–R04改动保持未暂存；正在首批提交核对。尚未推送。保护其他 worktree。
- R16–R20 已实现：稳定有界游标；主数据 SQL 权限过滤；单仓查询显式拒绝多仓；四服务 HikariCP 与查询/网络超时；验签后租户/全局并发与速率预算及1MiB请求体；355处注解SQL转同名XML；仓迁移XML化/每批200行；43个原HTTP Map请求体转DTO校验；错误分类；主数据L1/L2缓存/回源预算；Outbox类型化退避抖动。尚未验收完成。
- 全模块编译和单元测试通过；定向集成验证通过：DatabaseBudgetIT、QueryCacheIT、BoundedPaginationIT、WarehouseMigrationIT、IsolatedRestoreIT。新增 MapperXmlBindingTest 拦截XML返回类型问题；InboundRequestBoundaryTest证明非法输入不访问DB且依赖故障503/真实重复409；ConfigurationBindingTest校验环境变量实际绑定。
- 默认完整回归已结束，日志 `/tmp/wms-full-verify.log`，session24558已结束；唯一失败是FulfillmentHttpIT的targetLotId旧可选请求被误设必填。已恢复可选；`/tmp/wms-fulfillment-followup.log` BUILD SUCCESS（2项HTTP）。`/tmp/wms-batch-followup.log` 中生产装配事务1项、迁移2项通过；当次后续HTTP因新增scope检查拒绝旧测试令牌，补齐read scope后重跑通过。定向日志 `/tmp/wms-focused-it.log` 为 BUILD SUCCESS。
- 测试只操作专属 Testcontainers（MySQL8.4.11、Redis7-alpine）；没有改共享数据或生产部署。一次误用浮动 mysql:8.4 的下载已中断，新增测试已改回项目锁定8.4.11。
- 下一批源码只读已确认：R01 用例手动SqlSession需要JdbcTransactionFactory，TCC保留同数据源SpringManaged+SqlSessionTemplate；R04新版授权入口验证证据，但createFromAllocation仍接受任意非空authorizationId，requireAuthorization仍只判非空。R02还发现scope字符串格式未正确拆解，需要兼容标准JWT字符串scope并限制仅操作scope/permissions可授权（不能以组名碰撞冒充scope）。现已实现R01双事务工厂与生产装配IT（通过），R02标准scope字符串/组名隔离和79条公开契约权限过滤（单测通过），R03调拨SQL先权限后分页和详情参与仓强制校验（HTTP测试通过）；R04源码正在修改，未验证。

## 已修改文件

- pom.xml 与各后端模块pom；新增 wms-runtime（分页、池、入站预算、缓存、错误边界与回归测试）。
- 各服务 Mapper.java 与对应 resources/com/**/*.xml；四服务 Persistence.java。
- 入出库、履约、库存 Controllers 与新增 *Requests.java/RemediationRequest.java；仓迁移 infrastructure；OutboxBudget/Publisher。
- 四服务追加 *bounded_query_indexes.sql；generate-openapi.py 与生成的 wms-v1.yaml。
- compose.yaml、deploy/compose.local.yml、deploy/app.Dockerfile、.env.example；required-its 检查脚本/清单。
- CODEX_PROGRESS.md、DELIVERY_PLAN.md、DELIVERY_STATUS.md；新增 docs/delivery/wms-v1/BACKEND_REMEDIATION.md。

## 下一步建议

1. 跟踪完整 verify 日志，修复真实回归，不能将失败标完成；不要并发启动另一次Maven写同target。
2. 首批仍需确认全部HTTP/既有流程测试、OpenAPI与DTO实际字段差异同步、缓存指标接线和环境配置；生成更新SBOM/许可证/OSV；执行smoke、契约/文档检查；更新首批证据并按完整逻辑单元提交。
3. 按已授权顺序实施 R01–R04（生产装配事务测试、所有公开路由scope测试、调拨仓范围、可信出库授权），再完成其余R项，不等待用户继续。
4. 最终按 task-git-delivery 完成必要CI、普通合并推送main；不强推、不覆盖已有改动、不部署生产。

## 恢复 Prompt

请读取 CODEX_PROGRESS.md、docs/delivery/wms-v1/DELIVERY_PLAN.md 和 BACKEND_REMEDIATION.md，继续已批准的全部24项整改。先完成当前 R16–R20 验证与提交，再按既定顺序连续执行；不要只做首批就结束，不要要求输入“继续”。保持OQ-03、真实WCS设备与容量签署边界，不虚构验收。

## 当前 Git 分批边界（恢复时务必注意）

- 暂存区是已完成本地验证的R16–R20（140余文件），后续R01–R04刻意未暂存。`git diff --cached` 与 `git diff` 是不同批次，不能全量add混在一起。
- R19兼容修复targetLotId已同步暂存DTO、暂存生成器及OpenAPI；生成器/OpenAPI工作树又含R02新增14入口/权限表，故通过hash-object/update-index精确更新首批版本，工作树后续改动保留。
- R01新增 ProductionTransactionsIT，R02新增 OperationScopeFilter/TSV/完整路由校验单测，R03新增FulfillmentHttpIT第二测试；均为下一提交。
- R04已修改OrderService拒绝裸授权（建单始终PENDING，执行查授权与Committed证据联结）、AuthorizationService全字段重放校验与只允许PENDING绑定、Mapper加入证据查询；旧测试尚须改为可信证据夹具，未跑R04测试。
- SBOM生成已完成（/tmp/wms-sbom.log BUILD SUCCESS）：168组件/158 purl，Tomcat与fastjson既有依赖命中。产物与说明归入首批；随后提交首批。不要并发启动Maven写同target。
- 四服务smoke日志/tmp/wms-smoke.log；控制台npm ci后build及33测试通过（/tmp/wms-console-build.log、/tmp/wms-console-test.log）。


## 最新检查点 2026-09-12 21:28

- R16–R20已提交 `ee255e0`，143文件（含SBOM），尚未push。暂存区现在为空，工作树剩下R01–R04相关改动，不再需要前述index分批技巧。
- `/tmp/wms-authorization-it.log` BUILD SUCCESS（02:22）：OutboundPickIT2、OutboundDispatchIT1、OutboundHttpIT1、OutboundExecutionBlackBoxIT2、ClosedLoopBlackBoxIT1，以及全模块单元测试。R04测试夹具已改为先插明确的本地测试TC证据再调用实际authorize，取消裸字符串自动授权。
- 随后又让设备dispatch共用requireExecutable、TCC action显式依赖Fence Bean，并给原HTTP测试补齐它们真正使用的scope。正在session25893执行 `/tmp/wms-security-http-it.log`：InboundHttpIT,MasterdataHttpIT,EffectHttpIT,DomainHttpIT,SnapshotHttpIT,OutboundDispatchIT,ProductionTransactionsIT。不要并发Maven。
- R01–R04成功后更新证据并独立提交，再R05–R12；全体整改完成后跑完整default/warehouse-it/tc-it/failure-it和CI、普通合并推送main。
- 新增依赖许可证和OSV已核对并提交；既有Tomcat与fastjson命中，不能宣称生产安全门禁通过。

- 2026-09-12 21:31：session25893结束 BUILD SUCCESS。R01–R04所有定向测试通过，正在独立提交。下一步直接R05–R12，当前没有运行中的Maven。全部整改仍未完成/尚未推送，不停在本批。


## 最新检查点 2026-09-12 21:40（R05–R12实施中）

- 第二批已提交 `967ad0d`（R01–R04）；工作树现在是第三批，暂存区为空。两个提交尚未push。
- R05/R06/R07/R08已实现主体：来源提交返回replayed，核验动作/效果/精确数量；收货/观察先恢复重放再检查剩余额度，仅首次累加；拣货和发运支持独立分批身份（DTO增加pickPartId/shipmentPartId，缺省命令键，拣货身份含task）；拣货/派发统一订单→任务锁序；跨单入库行拒绝；整单终态汇总所有行；取消命令可重放且取消未完成拣货任务。
- CommandReplay/CommandKeys/CommandConflictException/InvalidCommandKeyException 新增在wms-runtime/command，RuntimeErrors映射409/400；HTTP里简单clientOperationId与header必须一致。含targetClientOperationId的授权协议未机械替换。
- 来源命令主键原有CHECK(id=command_id)与企业/仓幂等范围冲突。新代码用UUID技术主键，追加inbound V007/outbound V008迁移删除该CHECK并改字段注释；旧数据不回填，旧查询仍用command_id。第一次R05测试因此CHECK导致失败，已补迁移后通过（见下一条）。
- `/tmp/wms-r05-regression.log` BUILD SUCCESS：InboundProtocolIT2、ReceiptObservationIT2、InboundReceiptIT3（新增同事实并发换键/满额重试/跨单/篡改数量）、OutboundProtocolIT、OutboundPickIT4（新增部分执行与全行汇总）；随后又补了insertCommand影响行数/下一尝试数量一致性、取消重放、R12规划持久化和header一致性。
- R12实现：outbound V009为outbound_task追加planning_command_id、唯一约束及查询索引；planPickTask带命令键重放复用任务，并扣除未完成PICK任务数量；HTTP传稳定key。新增OutboundPickIT.planningIsIdempotentAndAccountsForOutstandingTasks。
- 正在Maven session74238、日志 `/tmp/wms-command-regression.log`，选择InboundReceiptIT,ReceiptObservationIT,OutboundPickIT,OutboundProtocolIT,InboundProtocolIT,OutboundDispatchIT（以及全模块单测）。不要并发Maven。最新新增规划测试可能要确认是否在本次编译之前写入；若报告OutboundPickIT只有4项则需重跑5项。
- 第三批未完成：R05–R08/R12最后回归/HTTP同步/跨企业同command键测试、R09完整有界快照导出、R10cutoff历史重建、R11严格JSON兼容门禁。来源消息payload已改JSON库转义；CompatibilityGate与Projection仍是旧手写解析，尚未修改。
- 之后仍有R13–R15/R21/R24、R22–R23，全配置集成/CI/合并推main；不得停在第三批。恢复先看当前日志与git diff，不重做前两批。

## 最新检查点 2026-09-12 21:50

- 第三批 R05–R12 已本地验证：`/tmp/wms-command-regression.log` BUILD SUCCESS，OutboundPickIT5项；`/tmp/wms-third-batch-it.log` BUILD SUCCESS，InboundReceiptIT4、ReceiptObservationIT2、OutboundHttpIT1、SnapshotExportIT2、InventoryProjectionIT2、CompatibilityMatrixIT2，以及全模块单元测试。必需门禁37项通过。当前无运行中的Maven。
- R09快照每次最多100行，分段和游标同事务，重复同cutoff请求续跑，末段才COMPLETE；GET一段+nextPartNo作为下次afterPart。R10按截止前ledger最后版本重建，251桶截止后全部再收货仍导出原量；拒绝未来cutoff/改水位/缺流水与单位。**三方水位仍是调用方关闭声明，不是跨服务关闭证明**。控制台提示已同步续跑语义。R11严格JSON重复字段/完整对象/整数版本/数量校验，兼容旧有效无版本事件。
- 第三批正在独立提交。下一批已有**未验证交付的工作树改动**：scripts/run-capacity.sh、scripts/capacity-runner.py、scripts/tests/test_capacity_runner.py（R24执行器），不能混入第三批提交。执行器专属HTTP夹具2项通过，未对WMS做签署容量压测。
- R13消息运行闭环、R14真实TM/TC/序列号、R15实际恢复任务、R21就绪观测仍待实施；R24要补输入/运行说明及CI；最后R22 UTC、R23操作人。现有出库证据夹具不是R14完成。入库putaway当前仍SYSTEM/先加量后协议，R23时一起校正幂等。
- 全部整改之后才完整default/warehouse-it/tc-it/failure-it、CI、正常合并推main。目前ee255e0/967ad0d均未push，不停在第三批。

- 21:51：第三批已提交 `9d32f8d`（41文件），契约复现/文档检查通过。工作树仅第四批R24执行器/CI/说明改动；Python测试2项通过，不是WMS容量实测。三个任务提交尚未推送。

## 最新检查点 2026-09-12 21:58

- R24执行器独立提交 `5b28597`：真实有界HTTP负载、最终只读业务断言、报告/CSV、无签署/无环境则非零；专属HTTP夹具2项通过，加入CI。不是WMS实际容量验收。当前四个任务提交均未push。
- **当前工作树 R21（未提交）**：wms-runtime新增RuntimeReadiness、RequestCorrelationFilter，复用Boot4.1.1 actuator依赖；四服务显式readiness包含DB/OIDC检查、liveness分离、HTTP p95/p99指标；metrics需observability.read操作scope；错误正文/日志/响应头共用关联ID；smoke改测无配置readiness503。
- `/tmp/wms-readiness-it.log` BUILD SUCCESS：DatabaseBudgetIT1（池耗尽DOWN/恢复UP）、OidcDisabledWebIT1、MasterdataHttpIT11及全单元；四服务 `/tmp/wms-readiness-smoke.log` PASS。随后补齐HttpJson/MasterdataHttp/DomainHttp/TenantAdmissionFilter错误ID，正在 **Maven session70926** `/tmp/wms-correlation-it.log`（MasterdataHttpIT + 所有单元）。不要并发Maven。通过后更新证据，独立提交R21核心；MQ指标/异步传播待R13/R15。
- R13/R14/R15 尚未实施。只读确认Source T1 payload仍只有commandId/qty；要建立版本化消息契约与权威业务上下文，不能把Kafka发通就写完成。入库receive请求缺location/lot/quality；outbound行缺lot/quality（只有SKU/owner/order，task有source/target）；要显式补桶上下文并由inventory校验，不得猜DEFAULT桶或GOOD。StockCommandService有applyReceive/Pick/Ship，无applyPutaway；后者需与move/效果协议一起补。现有runtime无Kafka依赖，但根BOM已锁kafka-clients3.8.0，test-support已使用。
- R14 AllocationRecoverySweep类存在但无Bean；TC terminal adapter在test-support/src/test/java/com/lrj/wms/probe/TerminalEvidenceAdapter.java。serial-registry服务仍无datasource/controller。TM归属wms-fulfillment已由用户确认（设计OQ01已关），OQ03只涉及单位/效期默认，不阻止用显式基础单位/UTC值做集成。
- R15 InventoryCatalogJobs除TccWatch外都是inspectOnly。已有可复用ExpiryEligibilitySweep、JobRunService.reclaimExpired、SnapshotExportService分段、StockInternalReconcile、CountService.applyLine、SerialTransferLocalService；但Expiry/listReconcile仍固定首100，不能机械接线宣称全量恢复。档案清理需真实保留策略，不编造期限。
- 后面 R22 UTC仍须考虑既有DATETIME按JVM墙钟写入（旧review明确记录，不能盲改读取把旧数据偏移）；R23putaway仍SYSTEM且先物理量后协议，需一起修复重放。
- 完成剩余后全default/warehouse-it/tc-it/failure-it及CI、普通合并pushmain，禁止把局部测试当50AC或实测容量达标。下一步先等session70926，再继续R13/R14/R15。

## 最新检查点 2026-09-12 22:08

- R21核心已提交 `ca36322`，最后correlation回归BUILD SUCCESS，必需门禁40项通过。现有5个任务提交仍未push。
- **R15部分已实现，正在独立提交**：InventoryCatalogJobs实际调用ExpiryEligibilitySweep（ent,wh,window）、JobRunService.reclaimExpired（ent,wh）、SnapshotExportService继续最旧EXPORTING一段（ent,wh）。Expiry查询NOT EXISTS同窗口notice，101取100，Report加hasMore；notice作为事务进度不再永远首100。其余4个handler（serial/reconcile/count/archive）现在明确失败，仍待真正实现，不能当R15整体完成。
- `/tmp/wms-job-wiring-it.log` BUILD SUCCESS（原Expiry1、JobLeasePreempt、Snapshot2）；新增**实际handler201批次100/100/1**在 `/tmp/wms-messaging-base-it.log` BUILD SUCCESS，ExpiryEligibilityIT2。当次也执行所有模块单元测试。当前无运行中的Maven。
- **同时工作树有R13未接线的消息基础**：wms-runtime/pom.xml新增根BOM已有kafka-clients3.8.0及testcontainers-kafka test；新增messaging/KafkaSettings、KafkaMessagePublisher、KafkaInboxConsumer、KafkaMessagingIT。publisher acks=all+幂等、5sdelivery/3srequest/1smetadata、256KiB消息、4MiB缓冲；消费者autoCommit=false/read_committed/maxPoll32、先提交DurableReceiver再单分区提交位点；失败seek原位点、跳过本批同分区后续消息、恢复重连；业务重试应在持久化Inbox执行，不在Kafka位点处阻塞依赖事件。关闭有界、配置toString隐藏JAAS。**尚无SpringBean业务接线、真实Inbox Mapper或Outbox适配，不能当R13完成。**
- `KafkaMessagingIT`真实专属apache/kafka:3.8.0+mysql:8.4.11通过：第一次接收模拟提交前断连，重投后写库，重复消息唯一行，停止consumer。完整日志 `/tmp/wms-messaging-base-it.log` BUILD SUCCESS。查过Kafka3.8官方producer_config/consumer_config（https://kafka.apache.org/38/generated/producer_config.html 和 consumer_config.html），后续文档引用这些配置依据。
- 下一步R13必须先补版本化业务上下文并接线：建议共享纯基础设施Inbox Mapper/worker（每服务本库，先durable入箱再ack，后台重试/隔离），service-specific应用适配；源码现有来源payload只有qty/key不能直接过账。入库RECEIVE按设计02-domain第4节必须先HOLD；质检移HOLD→GOOD并成对流水，不能给缺批次/库位的旧命令猜GOOD/DEFAULT桶。QualityQualificationService目前只记录资格，不实际搬质量桶，需一起补；StockCommandService只有Receive/Pick/Ship，Putaway须接move+posting同事务。库存原OutboxRecord缺事件时刻/桶身份，要从已提交outbox created_at与不可变余额维度补，不能以发送时刻伪造asOf。
- R14真实TM/TC/serial服务、R15余下任务、R22旧数据UTC兼容、R23putawayactor与重放仍待。任务全部完成后再全profile/CI及普通合并pushmain。保持OQ03/真实设备/签署容量边界。
