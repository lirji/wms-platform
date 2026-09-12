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

## 最新检查点 2026-09-12 22:20（R13仍在实施）

- R15三个实际handler已独立提交 `bab3e7b`；当前共6个本地任务提交，均未push。R15余下serial/reconcile/count/archive仍明确失败等待接线，未完成。
- 当前工作树**R13消息基础+库存投影实际链路，未提交**。新增runtime/messaging：KafkaSettings（隐藏JAAS）、KafkaMessagePublisher（真实confirm）、KafkaInboxConsumer（durable receiver后offset；重连/关闭有界）、RuntimeMessage（严格版本/范围/原时刻/规范化内容摘要）、RuntimeInbox（本库claim epoch/retry8次/毒消息隔离）、MessageWorker（单线程固定延迟）、KafkaDependencyHealth（真实broker请求5秒缓存）；RuntimeDependencyCheck接入已有wmsReadiness。
- RuntimeInbox的Mapper XML在wms-runtime/src/main/resources/com/lrj/wms/runtime/messaging/persistence；四服务Persistence已注册。四库追加同表DDL：inventoryV024、inboundV008、outboundV010、fulfillmentV009。runtime增加MyBatis与Kafka运行依赖、TC Kafka test。**共享Mapper仅操作各自物理库的通用Inbox，不跨服务业务表**。
- RuntimeInbox.persist严格校验Topic→source来源、event身份内容；同事件不同payload隔离，格式错误隔离；原始offset唯一。worker领取后本库事务内应用业务+回执Outbox+DONE。首次RuntimeInboxIT暴露“回调直接JDBC写入、MyBatis dirty=false时close会重置autoCommit而误提交”的问题，已显式session.rollback(true)/commit(true)。`/tmp/wms-durable-inbox-it.log`重跑BUILD SUCCESS（真实迁移/Mapper、业务效果回滚、恢复、重复/篡改/畸形隔离），不是仅Mock。
- 库存新增 `messaging/InventoryMessagingConfiguration`、`InventoryEventTransport`：wms.messaging.enabled=true时实际Outbox worker→Kafka topic `<prefix>.inventory.events`→本库runtime inbox→InventoryProjectionService。默认false仍未启用来源链路。不要在配置类加ConditionalOnBean（可能解析早于Persistence）；已去掉，启用却无DB直接装配失败。当前consumer只处理InventoryBalanceChanged，已知ReservationConfirmed对投影无作用；其他事件隔离。
- OutboxRecord新增occurredAt，Mapper取原created_at；传输补不可变bucket owner/location/sku/lot/quality，查库后释放锁再发送。ledger Outbox payload已改JSON库序列化并在原事务固定requestId，历史无requestId用eventId稳定关联，重发不生成新内容。OutboxPublisher遇线程中断停止剩余批次，未处理领取等待租约恢复。
- KafkaDependencyHealth参与wmsReadiness：真实Admin describeCluster超时1s，5秒缓存，检查消费者已加入组及两个worker状态；无分区正常副本以memberId判断已加入组。DB连接检查完成关闭连接后才检查MQ，不在网络探针时占DB连接。
- **真实生产Bean装配 InventoryMessagingIT 已通过**：权威库存写7+2→Outbox→真实Kafka→durableInbox→projection9，原asOf不改成发送时间；随后暂停专属broker，落库1单位Outbox不标PUBLISHED，readiness变DOWN；解除暂停自动追到10且ledger仅3条。`/tmp/wms-mq-recovery-it.log` BUILD SUCCESS，OutboxPublisherIT/OutboxCrashRecoveryIT也通过。非inbound→inventory闭环，R13整体还没完成。
- 依赖安全核查发现旧kafka-clients3.8.0**仅test scope**如今不能直接进运行：官方CVE-2026-35554（buffer race误投Topic）/33558（DEBUG泄漏），修复3.9.2。已向用户说明并把root kafka.version改3.9.2，broker保持apache/kafka:3.8.0，不改共享组件；VERSION_LOCK记录官方公告 https://kafka.apache.org/community/cve-list/ 与兼容文档。需要更新SBOM/OSV/许可证，不能把旧3.8测试结果冒充新客户端通过。
- **当前唯一Maven：session64648，日志 `/tmp/wms-kafka392-it.log`**，选择KafkaMessagingIT,RuntimeInboxIT,InventoryMessagingIT,OutboxPublisherIT,OutboxCrashRecoveryIT，全单元随跑。不要并发Maven。
- 下一步继续R13来源上下文/过账/回执：新T1信封必须持久化owner/sku/库位/lot/质量/原动作身份/actor/执行id/数量/单位；旧minimal payload不可猜桶。入库按已批准设计先HOLD，质量事件移HOLD→GOOD/REJECTED，现有QualityQualificationService只写资格没搬桶。Putaway须move+posting+回执同事务。SourceProtocol.consumeResult还需终态CAS保证不同回执eventId不重复增加posted。出库Pick/Ship需权威allocation/attempt与源/暂存桶。入出库service适配器、source_outbox真正publisher和结果consumer尚无；fulfillment/serial/R14真实TM/TC还没接。
- 还须配置compose/.env中的Kafka/topic/ACL、安全/保留/恢复说明、可靠回执和隔离重放入口、消息指标；SBOM；R15余下任务；最后R22 UTC兼容既有数据、R23putaway actor/重放。当前实现不可宣称全部24项/50AC完成。完成后全profiles、CI、普通合并pushmain，不等待“继续”。


## 最新检查点 2026-09-12 22:34

- 仍在 fix/backend-review-remediation，6个已提交任务提交尚未push。本次工作树R13基础/库存投影+来源回执硬化+R23提前修复均未提交；无用户无关改动。
- Kafka3.9.2新客户端 `/tmp/wms-kafka392-it.log` 已 BUILD SUCCESS：真实KafkaInbox/RuntimeInbox/InventoryMessaging（暂停broker恢复）/OutboxPublisher/OutboxCrashRecovery。不要再写“新客户端回归中”。VERSION_LOCK已更新。SBOM仍待重新生成；既有Tomcat/fastjson漏洞不隐瞒。
- V025删除stock_command全局外部key主键CHECK，技术UUID替代，新增StockCommandIT两仓同key独立posting/ledger测试已通过。
- 两来源SourceMapper.commandFact核验动作+事实行，T3用例先锁业务行再效果；consumeResult检查APPLIED原数量/凭证/active_command，不同eventId终态重放consumed=false，旧尝试迟到永久拒绝。updateEffectApplied只有APPLIED设置applied_command_id，非APPLIED数量必须0。SAFE_CLOSED但尚未换新attempt的迟到APPLIED是否拒绝仍需复核。
- R23因消息原始身份依赖提前处理：putaway签名末尾新增commandId/actorId，HTTP传Idempotency-Key+JWT subject，所有内部test显式actor。新增ReceiptMapper.lockPutawayTask，先校验task/document/line/target/qty再恢复重放，只有首次命令增加实物；已有PLANNED任务可完成，已完成/取消/已部分完成或别人的任务拒绝新命令。满额重放/换键复用/换target冲突/保留原actor真实库已验证。
- `/tmp/wms-callback-putaway-it.log` BUILD SUCCESS：InboundProtocol2、OutboundProtocol2、InboundReceipt4、StockCommand2、OutboundPick5、InboundHttp1、OutboxPublisher1、OutboxCrashRecovery1及全部单元。此前两次失败只是修改期间编译资源版本错配、测试仍按旧TASK-P1命令键统计；已修复再跑成功，后续Maven运行期间不要修改Java/XML。
- 最新只增加 RuntimeMessage.parse 拒绝数字requestId和RuntimeMessageTest，以及InboundHttpIT直接查actor_id必须JWT subject。**唯一正在Maven session50409 `/tmp/wms-actor-http-it.log`**（InboundHttpIT+所有单元）。不要并发Maven；通过后 `scripts/generate-sbom.sh` 更新新增运行Kafka依赖/许可证/OSV。
- Compose库存专属消息开关defaultfalse、topicPrefix/env已补；本项目kafka-init创建inventory.events（开发一分区一副本7天/256MiB上限），旧outboxtopics未删除。文档新 docs/implementation/MESSAGING_RUNTIME.md 明确范围/ACL/恢复与待办；check-docs/verify-contracts通过。尚未运行compose栈、不触共享组件。
- scripts/required-its-default.txt新增消息3tests/StockCommand跨仓/R23主流程；检查时旧reports可能尚在，最终必须完整run。OutboxPublisher.finish返回CAS成功才计published，失去epoch不虚报。
- 下一步先等待actorHTTP，SBOM，复核后拆分提交消息基础与来源回执/上架逻辑。随后继续R13完整来源命令上下文/发送/库存T2/结果Outbox/T3，不能停在基础闭环。source_outbox仍minimal qty payload，无可靠publisher；receive请求缺显式location/lot，按设计RECEIVE必须HOLD，质量资格目前没有实际HOLD转GOOD；StockCommandService无Putaway。
- 余项R14真实TM/TC/serial、R15serial/reconcile/count/archive（3handler已提交其余仍fail）、R21异步积压指标、R22UTC与旧DATETIME兼容、全profiles/CI/main合并推送。OQ03/真实WCS/签署容量与50AC未具备，不虚构验收。

- 22:38更新：actorHTTP+RuntimeMessage严格requestId单测已通过。首次SBOM发现新运行lz4-java1.10.1命中GHSA-xx22-p4ch-683r，依据维护者公告锁1.11.1；`/tmp/wms-lz4-it.log`真实LZ4压缩消息+Inbox回滚测试通过，再生SBOM172组件/162purl/304许可证，OSV只剩既有Tomcat/fastjson2组件命中。
- 最后复核补充：两来源SAFE_CLOSED但未换新尝试时也拒绝APPLIED回执；T3 Received/Putaway/Pick累计检查影响行数，失败不能把命令当APPLIED。正在唯一Maven session（见最新tool）`/tmp/wms-source-callback-final-it.log`：InboundProtocolIT,OutboundProtocolIT,InboundReceiptIT,OutboundPickIT。通过后拆分提交消息基础与来源回执/上架。不要并发Maven或在本次编译期间修改Java/XML。

- 22:39：消息基础已提交 `8e013c2`（42文件），仅库存投影链路。最后来源回执保护 `/tmp/wms-source-callback-final-it.log` BUILD SUCCESS（InboundProtocol2/OutboundProtocol2/InboundReceipt4/OutboundPick5+全部单元）；required 46项结构门禁通过，仍需最终组合全跑。当前无Maven。正在提交来源回执与R23及库存命令跨仓身份，随后继续R13主链。


## 最新检查点 2026-09-12 22:43（继续R13来源上下文）

- 新提交 `61ffb80`：来源回执校验/R23上架审计/StockCommand跨仓key（22文件）；加上消息基础`8e013c2`，现在8个本地任务提交未push。上一批工作树已干净后才开始本批。
- 本批未提交：公开契约 wms-contract/src/main/java/com/lrj/wms/contract/messaging/StockPostingContext.java（owner/SKU/baseUnit/source+targetLocation/lot/quality/allocation+attempt，按RECEIVE/PUTAWAY/PICK/SHIP校验）；wms-runtime新增对wms-contract依赖（不新引第三方）。
- runtime/messaging/SourceCommandContextStore+MissingCommandContextException、persistence/SourceContextMapper Java/XML，只访问当前来源服务的source_command/source_outbox；第一次T1绑定payload.postingContext+canonical digest+schemaVersion1+requestId，命令与Outbox必须同时影响1行，重试保持原维度；历史无上下文重放返回专属409，禁止本次请求猜填历史事实。未改payload_digest旧版本定义。
- 入库Persistence注册SourceContextMapper；ReceiptMapper.lockLine补base_unit；ReceiptService.bindReceiveContext从本库order/line派生owner/SKU/unit、质量HOLD，用户只传库位/批次；receiveObserved最终replayed准确反映复用原命令（防止给旧minimal命令补猜context）。
- HTTP ReceiveRequest追加locationId/lotId optional但成组；Controller构造增加@Value消息flag，flag=true必须提供两者，flag=false且传了也持久化上下文。原HTTP行为两者均缺且消息关闭时兼容。边界单测构造已传false。OpenAPI生成器+生成产物已更新72paths；verify-contracts脚本因产物未提交而diff返回1，这是预期未提交改动，不是生成不一致；后续暂存后再复现。
- InboundReceiptIT新增postingContextIsAtomicImmutableAndCannotBeGuessedForLegacyReplay，检查首次T1相同payload、真实owner/unit/HOLD、满额换key重放、改库位冲突、actor保留、旧minimal不能补猜。
- 当前唯一Maven **session83759 `/tmp/wms-source-context-it.log`**：InboundReceiptIT,ReceiptObservationIT,InboundHttpIT+全单元。不要并发Maven/改Java/XML。通过后继续来源Outbox可靠publisher/T2处理/可靠回执，不停在上下文绑定。
- 下一实现建议：source_outbox追加lease/claim_epoch/error/published_at，通用协议publisher只访问本库元数据（source_command + source_effect + source_execution），保留原事件id/time/actor/attempt，从持久化postingContext生成完整信封；旧minimal隔离。库存订阅可信inbound.commands/outbound.commands；按权威SKU精度/unit/lot/owner/location校验，RECEIVE先HOLD；T2效果+posting+resultOutbox同TX；库存StockCommandMapper需要postingByCommand查询回执；InventoryEventTransport按目标来源路由results；SourceT3读取commandFact校验行并调用现有consumeReceive/Putaway/Pick/Ship。
- 仍无真正来源publisher、T2消息适配、结果consumer；入库质量资格需要实际HOLD→GOOD/REJECTED成对流水，StockCommandService缺Putaway。出库新增上下文/绑定allocation与attempt、取消协议亦待。R14/R15剩余/R21积压/R22/全profiles与CI/main交付仍待，OQ03/真实WCS/签署容量不假定完成。

- 22:46：`/tmp/wms-source-context-it.log` BUILD SUCCESS（InboundReceipt5、ReceiptObservation2、InboundHttp1及全部单元）。随后继续来源发布器，仍本批未提交。
- StockPostingContext现第一字段增加documentId（receiptSession不等于order，不能猜），bindReceiveContext传权威orderId。runtime新增SourceOutboxPublisher、SourceOutboxMapper Java/XML；inbound V009/outbound V011追加source_outbox领取代际/租约/发布时间/错误码（新列中文注释）。两来源Persistence注册SourceOutboxMapper；inbound已注册SourceContextMapper，outbound还没上下文绑定。
- SourceOutboxPublisher每次领取1条独立TX、每轮<=32、领取30秒、最多8次实际发送后隔离，网络等待不占DB连接；确认后CAS成功才计数。查询本库source_command+effect+execution补action/fact父分批行/actor/sourceExecution/previous，使用原outbox.created_at（当前兼容JVM墙钟，R22待统一）；RuntimeMessage.aggregateId为来源business_effect_key（让同事实重试保持分区键），payload.commandId保留外部键；校验持久化postingContext摘要/动作/活动尝试/安全关闭，旧minimal隔离。
- **当前唯一Maven session41842 `/tmp/wms-source-publisher-it.log`**：SourceOutboxIT、InboundReceiptIT、ReceiptObservationIT、InboundHttpIT+全单元。SourceOutboxIT专属MySQL/Kafka验证原始actor/fact/HOLD/document/asOf与旧minimal隔离；inbound POM新增TC Kafka test依赖，非运行中间件。运行中不要改Java/XML或并发Maven。
- 注意发布器目前只是可复用实际类，尚无来源Spring beans；接下来还要InboundMessagingConfiguration（producer/sourceOutbox worker + inventory.results inbox/consumer/worker/readiness）及InventoryMessagingConfiguration消费inbound.commands的T2适配、result Outbox和按目标来源路由。不要把本测试当完整T1/T2/T3。
- 下一个库存适配可复用MasterdataHttpMapper.getSku（base_unit/quantity_scale/lot_enabled/serial_enabled/state）与MasterdataMapper.getLocation；getLot现未返回owner_id，须新增权威scope校验查询。StockCommandMapper需postingByCommand（表已有enterprise/warehouse/source/command唯一索引）；结果写Outbox与T2/DONE同TX，发送结果按来源results topic。数量解析用Quantity按权威SKU精度，RECEIVE=HOLD，序列号SKU缺显式serial不能静默普通入账。错误契约隔离，不以重试补猜未知原始事实。

- 22:47：来源发布器集成 `/tmp/wms-source-publisher-it.log` BUILD SUCCESS，SourceOutboxIT1、InboundReceipt5、ReceiptObservation2、InboundHttp1+全部单元；当前无Maven。正在独立提交T1上下文/发布器，再接完整收货T2/回执。


## 最新检查点 2026-09-12 22:59（收货双进程闭环已通过）

- T1上下文/发布器已提交 **d811386**（28文件），目前9个本地任务提交未push。当前工作树本批RECEIVE T2/T3接线，均本任务改动。
- 新 `inbound/messaging/InboundMessagingConfiguration`：enabled=true时producer/sourceOutbox worker + RuntimeInbox(inbound.results受信inventory)/Kafka consumer + T3 worker + KafkaDependencyHealth。T3只从本库commandFact取得行与动作，不信消息提供任意行；结果qty/postingId类型验证。SOURCE_CONFIG目前只实际支持RECEIVE（PUTAWAY回执分支已备好，但命令上下文/库存原语未补）。
- 新 `inventory/messaging/StockCommandMessageHandler`：仅inbound StockCommandRequested RECEIVE；按本库MasterdataHttpMapper.getWarehouse/getSku/getLocation/getLot核验ACTIVE、baseUnit、qty精度、lot开关/owner/SKU/warehouse；serial_enabled缺观察集合永久隔离，不能普通过账。调用StockCommandService.applyReceive，原sourceExecution/actor/document随消息入账；新增StockCommandMapper.postingByCommand，从真实posting取qty/id；OutboxMapper.insertCommandResult稳定eventID(source/ent/wh/command/state)唯一，无重复结果。
- InventoryMessagingConfiguration同时订阅inbound.commands+inventory.events；InventoryEventTransport构造现在第三参是**topicPrefix**，结果按payload.recipientService路由<source>.results，其余inventory.events；payload.requestId为null时用eventID稳定关联。新版InventoryMessagingIT补建inbound.commands测试Topic。
- 新 **ReceiveMessagingProcessesIT** 启动已构建inbound/inventory两个独立可执行Jar、两个MySQL、Kafka、本地真实RSA JWKS+JWT。建单/无上下文400；暂停专属Kafka再HTTP收货202/PENDING；解除暂停自动T1→T2→结果→T3；换key重试和不同eventID同回执后source posted3、HOLD3、ledger/posting各1、actor原JWT。Jar路径按0.1.0-SNAPSHOT，进程stdout在wms-inventory/target/receive-messaging-processes/{inbound,inventory}.log（无令牌/密码命令行）。正常测试先停进程再关组件。
- `/tmp/wms-receive-processes-it.log`初次因RSAKey import歧义编译失败，已显式RSA接口导入，随后完整通过。最后把KafkaMessagePublisher.retries=3 / retry.backoff.ms=100、总delivery5秒并改正常退出顺序，再跑 **/tmp/wms-receive-processes-final-it.log BUILD SUCCESS**：ReceiveMessagingProcessesIT1、InventoryMessagingIT1+全部单元。当前无运行Maven。
- SourceOutboxPublisher增加outbox payload.commandId必须和本库row.commandId一致校验。Compose/.env有inbound与inventory分别默认false消息开关；kafka-init建立inventory.events/inbound.commands/inbound.results（只改配置未启动栈），docs/MESSAGING_RUNTIME已改为RECEIVE闭环实际范围。
- **用户问题待答（必要业务范围，不能按超时默认）**：通过request_user_input_async询问“质检按每次收货分批（推荐）还是整条入库行（需分摊各批次）”。因为一行可多lot/location，仅行级accepted/rejected无法推断每桶HOLD→GOOD/REJECTED分配。等答前继续独立R15/R14/R21/R22，不能擅自选整行分摊。此前OQ03/真实WCS/签署容量仍未解决。
- 接下来先记录/提交当前RECEIVE批次，再做独立整改；R13仍缺质量/PUTAWAY/PICK/SHIP/CANCEL、serial观察、消息人工重放与积压指标。R14真实TM/TC/serial服务未接，R15余下serial/reconcile/count/archive仍fail，R22时间兼容未做；R23已完成。最后必须全profiles/CI/普通merge push main，无生产部署。
- R15只读发现：CountService.applyLine审批后按plan→line→balance锁、已APPLIED/ZERO重放，serial registry默认Unavailable；当前直接insertLedger**没有Outbox**，恢复接线时必须补投影事件且处理失败状态。StockInternalReconcile.execute固定首100余额、无界ledger/reserved/serial查询，不能机械调用宣称完整。历史DATETIME按JVM墙钟恢复，R22不可直接把旧值当UTC。


## 最新检查点 2026-09-12 23:08

- RECEIVE双进程闭环已提交 `af6f2a7`，目前10个本地任务提交未push；本批R15盘点恢复准备独立提交，全部本任务改动。
- 新CountApplyRecovery：明确ent,wh,approvedPlan，每轮20行，每行领取先独立提交、30秒租约、epoch防旧写、8次预算（崩溃也消耗），业务逐行独立事务。失败另TX写稳定错误码+指数退避/抖动，不伪造审批、不自动解冻；serial registry缺失仍明确失败。
- inventory V026追加count_line recovery_attempts/epoch/lease/next/error和索引；查询响应显示失败/耗尽。CountService补未观察拒绝、关键CAS影响行数检查；抽取InventoryLedgerWriter供原库存应用与盘点共用，余额/流水/Outbox同TX，避免盘点投影遗漏。
- `/tmp/wms-count-recovery-it.log` BUILD SUCCESS：CountIT2（23行分页、冲突不饿死、旧epoch失败被拒、8次预算）、CountSerialIT2、CountFreezeRaceIT1、InventoryMessagingIT1和全单元。补充真实XXL handler+回滚后 `/tmp/wms-count-handler-it.log` BUILD SUCCESS：CountIT3+全单元。无运行Maven。
- pending用户质检粒度问题仍无答；不能按超时假定。继续独立R21消息积压指标/人工恢复、R15余下任务、R14真实TM/TC/serial、R22固定时区兼容。R13仍只有RECEIVE和投影完整，其他动作未接通。
- R22只读官方核实：https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html 与 connector-j-connp-props-datetime-types-processing.html。仅HTTP转Z不足，当前RuntimeDataSources无明确connectionTimeZone、map DATETIME为LocalDateTime并使用JVM默认。必须保存旧库时区语义，不能直接重读旧值当UTC；尚无R22修改。
- 下一步R21建议本库Inbox/Outbox有界指标采样（每状态最多1001行，created_at索引取最旧、5秒快照、采样stale可见，不在metrics HTTP中查询DB），低基数queue/state标签；随后审计重放必须保留claim_epoch，新增retry_base/独立预算，不能重置epoch。完成全部后全profiles/CI与普通merge/push main。OQ03/真实WCS/签署容量仍未验收。


## 最新检查点 2026-09-12 23:14

- R15盘点恢复已提交 **cd29555**，本分支共11个本地任务提交未push。本批R21消息积压指标/告警检查准备独立提交。
- runtime新MessageQueueMetrics + Mapper XML：本库INBOX/INVENTORY_OUTBOX/SOURCE_OUTBOX静态选择，PENDING/CLAIMED/ISOLATED各最多1001行计数、索引最旧年龄；每查询1秒预算，5秒后台不可变快照，不在metricsHTTP查库；sample.age/available显示失效，标签只有queue/state。
- inbound/inventory消息配置实际注册metrics+worker；四Persistence注册Mapper，追加索引迁移inboundV010/inventoryV027/outboundV012/fulfillmentV010。未给尚未接线的outbound/fulfillment伪造业务指标。
- `scripts/check-message-backlog.py`：带observability.read令牌从env读取/禁重定向/响应64KiB/单请求2秒和全轮30秒截止；阈值显式输入，0正常、1隔离或积压、2未知/鉴权/采样失败，JSON无敏感数据，不主动通知第三方。Python告警2+容量2共4项通过。
- `/tmp/wms-queue-metrics-it.log` **BUILD SUCCESS**：MessageQueueMetricsIT1真库1005→1001封顶/最旧/隔离/采样中途失败保留旧值/恢复、ReceiveMessagingProcessesIT1、InventoryMessagingIT1+全单元。初次SimpleMeterRegistry非AutoCloseable编译失败已修复，当前无Maven。required52项通过（现有报告检查，非最终全组合）。
- 附带R21真实缺口：OperationScopeFilter 403正文原另造UUID，已改复用HTTP requestId；新增嵌套真实filter单测，`/tmp/wms-scope-correlation-test.log`相关模块全部单测通过。
- 下一步R13人工受审计重放：必须按ent/wh/queue/id校验，禁止改payload，原事件身份保留；新增retry_base_epoch或独立预算，绝不重置claim_epoch。RuntimeInbox原processNext仅persist验证trusted来源，重放时还应复验topic→source/eventkey/hash；event_key空的畸形/不可信隔离绝不盲重放。来源旧minimal缺postingContext不可凭当前请求猜填。库存Outbox需用epoch-base做retry预算，保持旧workerfence。
- pending质检粒度必要业务问题仍无答；继续独立事项。R14真实TM/TC/serial，R15serial/reconcile/archive，R22固定时区旧库兼容仍未实施；R13质量/PUTAWAY/PICK/SHIP/CANCEL未闭环。最终全profiles/CI/普通merge/pushmain尚未做，无生产部署。OQ03/真实WCS/签署容量与50AC保持未验收。
