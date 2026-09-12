# 后端评审整改证据

执行清单仍以 [DELIVERY_PLAN.md](DELIVERY_PLAN.md) 的 R 编号为准。首批基线为 `db02821`。本文记录实现边界与验证，不替代原 50 AC、生产容量签署或设备验收。

## R16–R20 实现与边界

| 编号 | 实现 | 验证 |
| --- | --- | --- |
| R16 | 入出库/履约/调拨/盘点/任务列表按 `created_at DESC,id DESC`；主数据、投影、差异单按主键分页。limit 1–200，查询多取一条生成 nextCursor；范围和投影世代绑定游标。单次库存/对账/快照查询明确限制一个仓库。 | `BoundedPaginationIT`：205 条同时间戳跨页不重不漏、先权限过滤后分页；`RuntimeBudgetTest`：非法大小、错误范围、尾页游标。 |
| R17 | 四服务 HikariCP；每实例默认 max16/min2、获取连接 1s、语句 5s、网络连接 3s/读取10s。验签后按企业限流：全局64/租户16并发、全局200/租户40请求每秒；拒绝返回429及 Retry-After。请求体1MiB，批量请求最多200项。 | `DatabaseBudgetIT`：真实MySQL池耗尽、恢复、语句超时；`RuntimeBudgetTest`：租户公平与释放。阈值是初始预算，不是实测容量结论。 |
| R18 | 355 处原注解 SQL 归入同名 Mapper XML；迁移 SQL 进入基础设施 Mapper。只允许固定迁移表和经元数据验证的列名，值全部绑定；每批200行，目标库分批提交。 | `MapperXmlBindingTest` 检查资源、语句标识和返回类型；`WarehouseMigrationIT` / `IsolatedRestoreIT` 真实两库回归。 |
| R19 | 全部现有 HTTP 请求体使用 DTO，Bean Validation 校验必填/精度/长度/批量上限；协议行显式映射现有应用模型。真正的 MySQL1062 才是重复键；解析错误400，唯一冲突409，数据库故障503且不暴露原始消息。 | `InboundRequestBoundaryTest`：非法数量/缺字段不访问数据库、故障503、重复409。已有领域校验仍执行。 |
| R20 | 主数据展示列表使用 Caffeine L1 + 可选 Redis L2。Compose 接现有隔离Redis；没配置 Redis 的直启环境保留有界L1与回源预算。Outbox 批量/次数/租约/退避类型化，指数退避增加抖动。 | `QueryCacheIT`：跨实例L2、租户key隔离、过期、Redis断连、有界回源；运行配置非法时启动失败。 |

主数据缓存是允许最多5秒陈旧的展示投影，不用于鉴权、门禁、库存扣减、单位换算或其他写入决策。每次读缓存前仍验证JWT与仓范围；key包含资源、企业、仓、身份、仓权限集合、分页条件，采用结构化编码后摘要。撤销权限遵循既有JWT有效期治理；缓存不延长令牌有效期。

L1默认500ms，L2最多5s并随机缩短0–20%；两层携带同一绝对截止时间，从数据库查询开始计时，旧查询回填不能获得新的完整TTL。更旧的并发回填可能暂时覆盖新值，但只能在其原截止时间内展示；不承诺强一致或依赖失效广播。空列表同样有界，不缓存异常。默认L1最多1000项、按每项最低16KiB计重（总权重约16MiB）、单值超过256KiB不缓存。Redis命令100ms，断连拒绝排队并短暂熔断；共享Redis256MiB、volatile-lru。多实例时钟需同步。

回源最多4个并发，超限503，不无限排队，不把过期数据作为成功响应。`wms.query.cache.*`指标记录L1/L2命中、回源/拒绝、Redis错误、驱逐、条数和活跃回源；指标不带租户或业务ID。Redis退化时监控回源、数据库连接使用与429/503，先限制入口或恢复缓存，不能靠盲目放大连接池处理。

运行参数见 `.env.example`。首期继续采用环境变量为唯一配置权威；更改池、缓存和Outbox策略需要重启并审查环境差异，不实现未经需要的动态刷新。任何副本扩容都应汇总连接和请求预算，并重新实测TP99；本轮默认值没有容量达标签署。

新增依赖复用 Spring Boot 4.1.1 BOM：HikariCP7.0.2、Caffeine3.2.4、Lettuce7.5.2.RELEASE；不升级既有中间件。连接参数依据 [HikariCP 官方说明](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)，缓存行为依据 [Caffeine 官方文档](https://github.com/ben-manes/caffeine/wiki)；Redis写入同时设置有效期，参见 [SET 官方文档](https://redis.io/docs/latest/commands/set/)。2026-09-12已重新生成SBOM/许可证/OSV：168组件、158 purl，既有Tomcat11.0.24及fastjson1.2.83命中；新增三项未命中。详见 [OSV快照](../../implementation/sbom/osv-findings.md)。既有依赖风险未因本轮整改消失，生产安全门禁仍未通过。

## 验证状态

2026-09-12 首批本地验证：

- 全模块默认回归运行至履约模块，仅 `FulfillmentHttpIT` 的无目的批次旧请求因 DTO 误设必填失败。恢复可选语义后，履约 HTTP 定向重跑通过（包含正常接收与新增仓隔离）；其余已执行模块均通过，不将最初失败日志写成 BUILD SUCCESS。
- 全模块单元测试通过；后续 `WarehouseMigrationIT` 2项、`ProductionTransactionsIT` 1项通过。较早的 `DatabaseBudgetIT`、`QueryCacheIT`、`BoundedPaginationIT`、`IsolatedRestoreIT` 均通过。迁移 JSON 类型从数据库元数据获取，不按字段名称猜测。
- `check-required-its.py --suite default` 的24项必需测试通过；四服务独立进程 smoke 通过；控制台 build 与17文件33项测试通过。首轮控制台构建因本地尚未安装 Ant Design 失败，执行锁文件 `npm ci` 后重跑通过，没有改依赖版本。
- 首批暂存版本 OpenAPI 可重复生成，ActionEffectRequest 的旧必填集及可选 digestVersion 保留；文档结构检查通过。首批 R16–R20 为独立提交单元，后续安全修复在同任务分支继续。
- warehouse-it/tc-it/failure-it 及远程 CI 待整个整改集成后执行；上述记录不替代这些检查或50 AC验收。

## 后续批次进度

R01 生产装配回滚测试已通过，R02/R03 操作 scope 与调拨仓范围实现和定向测试通过；R04 出库授权正在修改，尚未验收。这些后续改动不属于首批 R16–R20 提交。


## R01–R04 实现与验证

- R01：普通 HTTP/任务 SqlSession 使用 JdbcTransactionFactory，TCC单独用同数据源的 SpringManagedTransactionFactory/SqlSessionTemplate。生产Bean装配测试验证盘点建到一半异常不留计划/范围，Try成功后异常不留Fence/预占/余额变化；已通过。
- R02：OpenAPI与运行时同源生成79条公开路由权限，验签后按HTTP方法和解析路径逐一检查，未登记入口拒绝。scope支持字符串/数组；permissions支持显式作业授权；groups和仓声明不能冒充作业scope。补齐14个已有入口的契约，单测遍历每条路由的缺权、错误scope、组名碰撞和正确scope；契约测试检查实际Controller路径覆盖。已通过；入库、主数据、效果、库存领域、快照HTTP定向回归全部通过。
- R03：调拨列表在SQL内限制至少一个参与仓获授权，再进行分页；游标绑定权限集合；详情省略warehouseId仍检查参与仓。准备分配检查全部参与仓权限。真实HTTP测试证明授权仓作为源/目的均可见，无关仓详情403，不可见行不挤掉分页结果；已通过。
- R04：建单时携带authorizationId不授予执行能力，初始保持PENDING_AUTHORIZATION；人工作业及设备派工必须核验同企业/仓/单/attempt的AUTHORIZED记录，并联结匹配的Committed证据。重放检查XID/证据引用/参与者摘要，绑定仅允许待授权状态；不同键重放不回退PICKING。OutboundHttpIT、OutboundPickIT、OutboundDispatchIT、OutboundExecutionBlackBoxIT与ClosedLoopBlackBoxIT定向通过；设备入口共享检查与生产装配的最后回归也已通过。

上述业务回归中的本地终态证据是明确的测试夹具，不是R14的真实TC到出库同步，也不证明完整MQ运行链路。R13/R14仍待实施。

2026-09-12 21:31：R01–R04 最后 HTTP/生产装配回归 BUILD SUCCESS（`/tmp/wms-security-http-it.log`，19项集成测试及全模块单元测试）。本地检查通过后单独提交；远程组合CI仍待全部整改集成。

## R05–R12 实现与验证（当前批次）

- R05/R06：来源协议先比较作用域、事实、动作和精确数量，再复用命令；仅首次接受才增加实物量。合法分批拣货/发运有独立身份，显式分批号支持换客户端键重试。命令技术主键与企业/仓级幂等键分离，新增迁移保留旧数据。HTTP 正文命令键不能覆盖不同的请求头身份。
- R07/R08：整单终态汇总全部行的发运与取消数量；入库操作必须证明行属于请求单据。拣货及设备派工统一订单→任务锁序。
- R12：拣货规划按企业/仓/命令键持久化，重放返回原任务；源/目标/单/行/数量变化拒绝。可规划量扣除未完成 PICK 任务的剩余量，取消未拣部分同步关闭相关任务。
- R09/R10：每次导出最多100行，分段内容与最后库存桶游标同事务提交；相同 cutoff 请求继续下一段，只有读取到末尾才 COMPLETE。GET 每次返回一段，`nextPartNo` 作为下一次的 `afterPart`，`partCount`/`rowCount` 表示持久化进度。新 manifest 使用汇总计数，分段 hash 随分页内容返回；消费者必须按游标取齐，不能再假定一个响应包含全部内容。旧 COMPLETE 快照内容不重写。
- 截止余额来自 `created_at < cutoff` 的最后不可变流水版本，桶在截止后的变化不会使其消失，也不会混入当前数量。缺流水或主数据单位明确 SOURCE_INCOMPLETE；拒绝未来 cutoff，同一 cutoff 的时刻/水位不能更改。三方水位目前仍为受权调用方提供的关闭声明，本批未建立跨服务关闭证明，不能把此实现当作跨服务强一致快照。
- R11：JSON 库严格解析完整对象，拒绝重复字段、畸形/尾随内容、非整数/未知版本；仅有效对象缺版本走旧版兼容。投影数量支持准确十进制字符串/数字，缺字段或错误类型不再默认为0，快照序列化正确转义。

命令定向回归 `/tmp/wms-command-regression.log` BUILD SUCCESS，OutboundPickIT 5项、入库协议/观察/收货及出库协议/派工通过。JSON单测通过；第一次快照服务/HTTP定向通过。新增251桶跨事务历史导出、跨仓同命令键及兼容/HTTP组合回归 `/tmp/wms-third-batch-it.log` BUILD SUCCESS：SnapshotExportIT2、InboundReceiptIT4、ReceiptObservationIT2、OutboundHttpIT1、InventoryProjectionIT2、CompatibilityMatrixIT2及全部模块单元测试。必需测试门禁37项通过。首次OpenAPI新参数缩进错误已被契约测试拦截，改用参数引用后完整重跑通过；远程组合CI仍待全部批次集成。

## R24 容量执行器

已将签署输入校验后的空成功路径替换为真实有界HTTP负载与最终不变量断言，记录客户端p95/p99、请求CSV和输入摘要；429、业务断言失败、生成器饱和或缺必要输入均非成功退出。只接受显式隔离目标，不跟随重定向、不记录令牌，参数和边界见容量设计第10节。执行器2项专属HTTP夹具测试通过并加入CI；这不是实际WMS容量达标。签署输入/隔离负载环境仍未提供，S9-01/AC-27保持未验收。R24要求的正式事务装配证据由R01 ProductionTransactionsIT覆盖，组合CI尚待最终执行。

## R21 运行探针与请求观测

四服务 readiness 显式包含业务探针：OIDC issuer/client-id 缺失、未配置业务数据源、真实连接校验失败或池获取失败时不就绪；liveness 单独检查进程生命周期，不因数据库故障触发反复重启。探针不暴露连接凭据/异常详情。`/actuator/metrics` 需 JWT 作业权限 `observability.read`，组名不能冒充；HTTP请求指标启用p95/p99/直方图，仍需结合实际负载解释分位数，不作为容量承诺。

请求头仅接受64字符内的安全关联ID，否则生成新ID；响应头、错误正文及日志MDC共用该ID，正常/异常结束恢复线程原上下文。未将租户/业务ID作为指标标签，不记录正文和令牌。异步业务与消息传播及业务积压告警继续随R13/R15补齐，此记录不代表完整链路追踪已经完成。

`/tmp/wms-readiness-it.log` BUILD SUCCESS：DatabaseBudgetIT（真实池耗尽DOWN、释放后UP）、OidcDisabledWebIT（无配置存活UP但就绪503）、MasterdataHttpIT11项（正常就绪/指标权限/请求响应关联）及全部单元测试。四独立进程smoke全部通过，日志 `/tmp/wms-readiness-smoke.log`。最后补齐领域错误/限流响应的同ID后，`/tmp/wms-correlation-it.log` BUILD SUCCESS：MasterdataHttpIT11项（含404正文与响应头一致）及全模块单元测试。

## R15 已接通任务（分阶段）

已接通 `expiryEligibilitySweep`（参数`企业,仓,窗口`）、`jobLeaseRecovery`（`企业,仓`）、`externalReconcileExport`（`企业,仓`）。每次处理有界批次并提交：过期巡检按同窗口已提交通知排除已扫描批次；快照按最久未更新的 EXPORTING 任务续写下一段；租约回收沿用 epoch/fence 保护。缺数据源直接失败，不向调度器伪报成功。

`ExpiryEligibilityIT.actualHandlerCommitsBoundedPagesAndDoesNotStarveLaterLots` 通过真实handler连续处理201个批次（100/100/1），第四次不增加通知；既有试图过期后预占仍被拒绝，巡检不释放TCC预占。`/tmp/wms-job-wiring-it.log` 的Expiry/JobLease/Snapshot定向通过，新增handler测试在 `/tmp/wms-messaging-base-it.log` BUILD SUCCESS（ExpiryEligibilityIT2项）。此处不是官方XXL admin集群验收。

`serialTransferRecovery`、`stockInternalReconcile`、`countApplyRecovery`、`archivePlanner` 尚待接入完整执行器，已由空日志成功改为明确失败；R15整体仍未完成。归档/删除不编造保留期限。消息基础真实Kafka/MySQL测试已经通过，但业务适配尚未接线，R13不能据此标完成。


## R13 消息基础与库存投影运行链路（来源命令主链仍在实施）

使用客户端 Kafka 3.9.2、隔离 broker 3.8.0 和真实 MySQL 验证应用 Bean：权威库存事务产生 Outbox，确认发布后标记 PUBLISHED；消费者先持久化本库 Inbox 再提交位点，业务应用和 DONE 同事务。暂停专属 broker 后 Outbox 保留、就绪探针 DOWN；恢复后自动追平投影，余额/流水没有重复。`/tmp/wms-kafka392-it.log` BUILD SUCCESS，覆盖 KafkaMessagingIT、RuntimeInboxIT、InventoryMessagingIT、OutboxPublisherIT、OutboxCrashRecoveryIT 及全部单元测试。投影 asOf 使用原事件时刻。

Inbox 使用事件身份和规范化内容摘要双重校验；篡改、畸形和不支持的事件隔离；处理失败退避重试，领取代际拒绝旧 worker。提交失败会显式回滚同连接的业务写入，即使业务适配器不是通过 MyBatis 写入。消费者关闭/断连恢复有界，消息中的 requestId 在处理时绑定并在结束后清理。readiness 附加真实 broker 请求和消费线程状态；此探针不代表业务无积压。

当前只接通 inventory.events → 库存查询投影；入出库完整命令上下文、T2 过账和 T3 消费、人工重放、业务积压告警仍待补齐，不能把这一段作为完整 R13 或首个收发闭环验收。配置默认关闭，生产消息权限和数据保留尚未签署。

## 来源回执与 R23 上架审计（依赖 R13 提前处理）

T3 现在核验命令动作、不可变事实行、过账数量和活动尝试，先锁业务行再锁效果，避免与 T1 反向锁序。不同回执 eventId 的同一终态重放不再次累计；迟到旧尝试不能覆盖新尝试。库存命令技术主键和外部命令作用域分离，追加 V025，真实 MySQL 验证同一外部键在两仓独立过账。

上架把 JWT subject 写入来源执行记录；请求 Idempotency-Key 作为命令键。先恢复原事实再检查剩余量，重试校验任务/库位/数量并保留首次操作人；只有首次提交才增加实物和完成任务。取消/完成任务以及他人已领取任务不能作为新命令再执行。为真实消息提供可靠原始执行身份，此依赖提前于原定最后批次完成，不添加 SYSTEM 占位。

`/tmp/wms-callback-putaway-it.log` BUILD SUCCESS：InboundProtocolIT2、OutboundProtocolIT2、InboundReceiptIT4、StockCommandIT2、OutboundPickIT5、InboundHttpIT1、OutboxPublisherIT1、OutboxCrashRecoveryIT1及全部单元测试。覆盖错误行回执、不同事件ID重复回执、满额上架换键重放/更换库位冲突、首次操作人不被重试覆盖、跨仓同键。正式HTTP测试随后增加直接查询 actor_id 的断言并通过：`/tmp/wms-actor-http-it.log` BUILD SUCCESS，JWT subject 为原始操作人。

最后补充的安全关闭后迟到回执与过账累计影响行数检查也通过 `/tmp/wms-source-callback-final-it.log`（两来源协议、入库主流程、出库分批与全部单元）。默认必需报告检查46项通过；尚未把这些定向报告当作本次完整组合CI。

## R13 来源T1上下文和可靠发布器

收货可显式提交locationId/lotId，来源数据库派生document/owner/SKU/baseUnit，固定收货质量HOLD。命令与Outbox同T1绑定相同上下文、摘要和requestId；重放不能修改原始维度或给历史minimal命令补猜。新增source_outbox领取/租约/确认/隔离列；真实发布器一次领取一条并释放DB连接后等待确认，保留原eventId/actor/执行事实/时刻，使用来源效果身份维持重试的分区键，旧minimal消息隔离。

`/tmp/wms-source-publisher-it.log` BUILD SUCCESS：SourceOutboxIT1（真实Kafka/MySQL）、InboundReceiptIT5、ReceiptObservationIT2、InboundHttpIT1及所有单元。当前来源发布器尚未注册Spring运行Bean，库存T2消息适配与结果回传待接线；这不是完整收货闭环验收。

## R13 收货双进程闭环

入库正式配置接通来源发布器、结果Inbox/worker和消息就绪检查；库存配置消费受信inbound.commands，在本库校验主数据并RECEIVE入HOLD桶。stock_posting恢复真实回执数量，结果Outbox与T2及Inbox DONE同事务，按recipientService路由inbound.results。来源按自己命令事实行处理T3，终态重放不再累计。

`/tmp/wms-receive-processes-final-it.log` BUILD SUCCESS：ReceiveMessagingProcessesIT1（两个本次构建的独立服务Jar、JWT HTTP、两库、专属Kafka）和InventoryMessagingIT1及全部单元；测试包括broker暂停/恢复、同事实换键重试、不同事件ID重复真实回执、HOLD桶与单条流水/凭证、原始操作人传递。最后将Kafka单次客户端重试显式限为3次且总截止5秒，重跑通过；正常结束先关闭服务进程再关闭组件。

当前RECEIVE适配对序列号SKU缺观察集合显式隔离；质检、上架、出库、人工隔离重放与积压指标仍待，不能关闭整个R13。质检按收货分批还是按整条入库行并分摊各批次，需要业务范围确认，已询问用户；在此期间继续独立整改项。


## R15 盘点逐行恢复（已定向验证）

`countApplyRecovery` 参数为 `企业,仓,已审批计划`。一轮最多20行，计划锁→行锁→门禁/余额锁；领取次数和代际先独立提交，业务调整在新事务核验代际。每次领取消耗一次预算，30秒租约过期可接管，错误码和指数退避加抖动另行提交；8次后自动停止并在盘点行响应显示隔离状态，保留原审批及失败证据。历史点数不可被恢复任务猜测，未审批不能自动调整。

调整操作身份由企业/仓/计划/行稳定派生，执行人记录为调度任务，审批人仍由计划单独记录。成功行不会重复写入；失败不回滚其他已提交行，部分失败不自动解冻。库存调整现在复用同事务的流水与Outbox写入，保证盘点结果进入可靠查询投影。序列号注册中心尚未接通时明确失败，仍计入恢复预算；不绕开身份校验。

真实MySQL验证通过：23行按20行预算续跑，冲突行不阻塞后续22行；重启与重复执行无多余流水，旧代际失败回写被拒绝，8次预算后不再自动执行。实际XXL handler连续调用只产生一次调整，回滚同时清除流水与Outbox。`/tmp/wms-count-recovery-it.log` BUILD SUCCESS（CountIT2、CountSerialIT2、CountFreezeRaceIT1、InventoryMessagingIT1及全单元）；补充实际handler后的 `/tmp/wms-count-handler-it.log` BUILD SUCCESS（CountIT3及全单元）。人工恢复预算的审核入口、序列号恢复、内部对账及保留策略仍待后续切片。


## R21 消息积压观测与告警检查

当前实际消息服务 inbound/inventory 每5秒在独立线程采样本库Inbox/Outbox，公开受 `observability.read` 保护的低基数queue/state指标：PENDING/CLAIMED/ISOLATED深度（1001为下界）、最旧年龄、计数封顶与采样有效性。SQL每条1秒预算、状态/创建时间索引，指标抓取本身只读内存；查询失败保留旧快照并以sample.age识别失效。四库追加通用观测索引，outbound/fulfillment尚未启用消息Bean，不宣称已有真实业务流量指标。

新增 `scripts/check-message-backlog.py` 读取实际鉴权指标，阈值显式输入；隔离/积压退出1，采样失效或鉴权/网络异常退出2，只有完整有效采样且阈值内退出0。输出JSON供已有监控接收，处置步骤见 `docs/implementation/MESSAGING_RUNTIME.md`。尚未配置生产通知渠道或签署SLO，不以脚本存在代表告警已经发送。

`/tmp/wms-queue-metrics-it.log` BUILD SUCCESS：MessageQueueMetricsIT1（1005积压只计1001、隔离/年龄、采样表不可用后保留旧快照、恢复归零）、ReceiveMessagingProcessesIT1、InventoryMessagingIT1及全单元。第一次测试编译因SimpleMeterRegistry不实现AutoCloseable失败，改为finally显式close后全量定向重跑通过。Python全部4项（容量2+告警2）通过，沿用现有CI发现规则。另修复OperationScopeFilter拒绝响应仍另造requestId的问题，`/tmp/wms-scope-correlation-test.log`相关模块单测全部通过，403正文/响应头/MDC同一关联ID。


## R13 受审计消息重试

已增加按企业/仓隔离的消息元数据分页与人工重试HTTP入口，分别要求messaging.read/recover。仅ISOLATED且expectedEpoch匹配可受理；原消息内容、事件ID与claim_epoch保持不变，retry_base_epoch只用于新一轮有界预算。审计记录JWT操作者、原因、原消息/请求摘要及原代际，与重新排队同事务。相同请求键不会再次恢复预算，不同内容冲突；不可信/被篡改Inbox、来源旧minimal上下文和过期动作不可通过该入口绕过核对。

入口默认关闭，运行方确认所有worker完成支持新预算的升级后才能设置WMS_MESSAGING_RECOVERYENABLED=true；这避免新旧worker混跑时旧预算算法立即重新隔离。追加迁移可先扩展，原消息内容不回填不改写。202使用独立MessageRecoveryAccepted响应schema，不冒用业务完成状态。OpenAPI新增两路径，权限与共享Controller路径纳入自动核对。

`/tmp/wms-message-recovery-it.log` BUILD SUCCESS：MessageRecoveryIT1（八次失败后人工恢复、原代际8→9、旧完成拒绝、审计写失败完整回滚、跨仓/内容冲突/不可信消息拒绝）、RuntimeInboxIT1、OutboxPublisherIT1（库存Outbox12→13且恢复预算）、OutboxCrashRecoveryIT1、SourceOutboxIT1、ReceiveMessagingProcessesIT1（真实JWT缺权/错仓403、恢复202、重复请求及重复回执无二次累计）和全部单元。初次故障夹具使用MySQL trigger缺SUPER权限，改用本测试CHECK约束制造同位置写失败，不提升权限、不改共享数据库配置，重跑通过。

追加来源Outbox17→18的真实Kafka恢复、旧minimal拒绝测试后，`/tmp/wms-source-recovery-final-it.log` BUILD SUCCESS（SourceOutboxIT1及所有单元）；同时修正专用202契约生成，74路径。Compose仅config静态校验，未启动生产栈。该恢复入口不代表质检/上架/出库主链或整个R13已完成。


## R14 序列号登记服务基础（阶段发布）

登记服务已接独立 MySQL、Flyway、受预算约束的连接池和真实 HTTP 接口：认领、激活、实时查询。仅受信服务主体且具有对应 scope、企业和仓权限可调用；X-Wms-Enterprise-Id 必须与已验签 JWT 企业一致。命令审计与登记变更同事务，HTTP 幂等键与原收货 operationId 各自固定。相同命令重放重新核验当前登记状态，不能返回历史 ACTIVE 绕过后续 MISSING；跨仓认领和其他 operation 激活均拒绝。

SerialRegistryHttpIT 与 SerialRegistryActivateIT 最新定向回归通过（2026-09-12 23:35，BUILD SUCCESS）：真实 MySQL、RSA JWT/JWKS、HTTP 权限边界、跨企业拒绝、重放和审计失败回滚。一次 Ryuk 连接失败发生在业务测试前；保留自动清理机制后重跑通过。TP99 unverified。

部署配置新增 WMS_SERIAL_DB_PASSWORD、WMS_SERIAL_ALLOWED_SUBJECTS；后者必须匹配真实服务账户，空值会阻止登记数据库启用。30-serial-registry.sh 仅用于新数据卷初始化；既有卷需单独初始化登记库和授权，不能通过重建卷处理。当前只提交配置，未启动或部署服务。库存 HTTP 适配、序列号转移恢复和真实 TM/TC 接线尚未完成，R14 保持进行中。


## 2026-09-13 阶段发布验证

用户要求先发布当前整改分支，R13/R14/R15/R22 保持进行中。固定提交 7e258d0 在独立工作树 .local/backend-remediation-integrate 的完整默认 verify 于 00:12:57 BUILD SUCCESS：99 个测试类、200 个用例，失败/错误/跳过均为 0，耗时 17:29；必需集成用例门禁 55 项通过。四个实际 Jar 的独立进程 smoke（存活、未配置依赖不就绪、业务入口默认拒绝）通过；控制台类型检查、33 项测试及构建、Python 4 项测试、文档/契约/Compose 静态检查通过。原主目录回归因 23:50 外部切换到旧 main，编译类与文件迁移混版，已终止，不用于验收。

远程分支运行 [34703330446](https://github.com/lirji/wms-platform/actions/runs/34703330446) 控制台成功，Java 在 SerialRegistryIT 暴露旧测试固定用 WH-A 重放的调度假设。测试现从数据库取实际获胜仓和操作号，增加另一仓借同操作号重放必须 SERIAL_OWNER_MISMATCH 的断言，并限制并发等待 10 秒；未放宽领域校验。修正后 SerialRegistryIT 及依赖模块单元测试于 00:13:38 BUILD SUCCESS（/tmp/wms-serial-race-publish-it.log）。

本次发布范围为 14 个整改提交及上述测试修正；目标为 origin/main。新提交远程 CI 与 warehouse-it/tc-it/failure-it 组合结果仍需核验，默认回归通过不能替代这些结果；没有生产部署，也不是 24 项或 50 AC 完整验收。


## R13 按收货分批质检（2026-09-13）

用户确认质检范围为每次收货分批。原 RECEIVE commandId 绑定不可变库位/批次/货主/SKU/单据，质检请求新增 receiptCommandId，以递增 sourceVersion 表示该批累计 accepted/rejected，未检数量保持 HOLD。质检只能在该批收货凭证确认后受理；前一版本未生效时拒绝跨版本更新，累计数量不能超过该批，不能降到该批已上架量以下。旧行级请求仅在消息关闭时兼容，不为历史缺失上下文猜测库存桶。

T1 的 inbound_receipt_quality/inbound_quality_revision、原操作者、来源命令和 Outbox 同事务；QUALITY 使用已有受信消息通道。T2 以本库原收货凭证+流水校验维度和数量，按稳定顺序锁定 HOLD/GOOD/REJECTED 三桶，质量差额守恒变化、流水、投影事件、库存命令凭证及结果 Outbox 同事务。T3 收到可信过账回执才更新该批 applied_version，不能把 HTTP202 当作质量已生效。

/tmp/wms-batch-quality-it.log 已 BUILD SUCCESS：原入库域/HTTP 回归和实际两个 Jar + 两个 MySQL + Kafka + RSA JWT 的分批质检链路。两批同一行不同库位互不混用，超批数量拒绝、重复/迟到事件不重复转桶，最后状态更新注入 CHECK 失败时转桶与流水回滚，恢复后自动续跑。等值小数格式和严格消息版本检查的补充验证另见最新进度。TP99 unverified。分批上架与出库链路尚未完成，R13保持进行中。

补充验证 /tmp/wms-batch-quality-final-it.log 于 00:30:25 BUILD SUCCESS：超出本批但未超整行的质检仍拒绝，2 与 2.0 重放一致；双进程故障恢复/迟到消息及所有单元测试通过。

## R13 分批上架与批次入口（2026-09-13）

分批上架在来源 T1 将 task 固定到原收货批次/库位，并以 CAS 扣减该批已同步的累计合格额度；同 task 不能更换批次。库存 T2 独立校验本库原收货证据、批次质量额度及真实 STORAGE 主数据，额度、GOOD 移库、流水、effect 与结果 Outbox 同事务提交。重复命令先查幂等效果，不再扣减额度。质检修订不能将合格量降至本批已上架量以下。V013/V014 为追加迁移，不猜测旧任务归属。

新增仓/企业/订单限定的收货批次游标接口（最多 200），仅返回操作需要的维度，不暴露持久化消息正文。控制台按服务端批次选择质检/上架，收货和 PDA 显式输入库位、货品批次；PDA 明确受理后才清理幂等键，网络失败保留原键。

真实两个 Jar、两个 MySQL、Kafka、RSA JWT：/tmp/wms-batch-putaway-it.log 于 00:35:29 BUILD SUCCESS（InboundReceiptIT 5、InboundHttpIT 1、ReceiveMessagingProcessesIT 1）；新增批次游标与响应校验 /tmp/wms-batch-list-it.log 于 00:37:32 BUILD SUCCESS。覆盖按批超额拒绝、换批拒绝、跨批上架、重复不重复记账、源/库存额度一致；此前质量故障回滚/自动续跑回归保留。未将本切片作为出库、序列号或完整 R13 完成证据。

控制台 typecheck、33 项既有回归和 production build 通过（/tmp/wms-batch-console.log、/tmp/wms-batch-console-build.log）。文档结构通过；契约生成与暂存产物一致性在提交后复验。

## R15 有界内部对账与归档规划（2026-09-13）

修复内部对账只扫描前 100 余额却全局关闭 REMEDIATING 差异的缺陷。现在每次最多 100 余额、100 来源 PHYSICAL 事实、100 库存 posting；V030 持久化三流游标，窗口行锁串行化，同事务提交差异和检查点。只复核/关闭本页明确检查过的身份；源服务也参与差异身份，POSTED 投影不能覆盖 PHYSICAL。窗口时刻及三方水位不可覆盖，修订需新窗口；已关闭差异复发重新打开。余额/流水/预占/序列号使用单页 REPEATABLE READ 当前一致性快照，明确区别于完整历史库存快照；未齐水位只落 SOURCE_INCOMPLETE，不判来源丢失。查询超时 5 秒，前台仍只分页查差异，不触发扫描。

stockInternalReconcile handler 参数为 enterprise,warehouse,cutoffId，窗口必须已经由可信水位流程建立，不能临时把当前时刻伪装成已关闭窗口。每次触发只完成有界页，cycleCompleted 不等于差异修复或水位完备。

archivePlanner 已实现实际候选规划，参数 enterprise,warehouse,runKey,cutoffISO,policyRef。显式提供过去关闭时刻和保留依据引用，不生成保留天数；V031 保存计划/候选引用/全字段摘要/链式 manifest。每次最多 200 行，计划行锁和版本检查防并发，候选与游标同事务；同键不能修改窗口或依据。PLANNED_EXPORT 只说明本次枚举结束，exported/deleted 均为 false。候选为关闭时刻之前的不可变库存流水；晚到数据进入新计划，导出需按清单逐项核对原行摘要、条数和 manifest，清理仍须独立授权并保留幂等身份。本轮未导出到对象存储，未删除任何业务数据。

/tmp/wms-recon-archive-it.log 于 00:47:48 BUILD SUCCESS，StockInternalReconcileIT 4 项及全部单元通过：205 余额跨重启三页、未访问差异不误关闭、保留审批操作引用、最后检查点失败整页回滚；205 旧流水加 1 近期流水仅规划前者，分两次续跑、重复计划幂等、改变依据拒绝、规划检查点故障回滚且源余额/206 流水不变。先前增量测试暴露测试查询未限定仓，已修正夹具后通过。

R15 仍余 serialTransferRecovery，依赖 R14 真实登记端口；三方水位事实接线继续随 R13 处理，不能仅凭 handler 已注册宣称所有外部链路完成。

## R22 数据库固定偏移与 UTC 边界（2026-09-13）

DatabaseTimePolicy 在迁移前核对旧库来源、数据库会话偏移，在迁移后持久化不可覆盖的物理库规则。新空库 UTC；已有业务表但无来源记录时拒绝启动，必须给出核实后的固定偏移与依据引用；+08 旧值原样保存，按已声明偏移读写后输出 UTC。区域夏令时/混合时区历史没有被猜测或自动转换，仍需独立数据审计。五个服务和种子入口同样遵守规则，Compose 按库配置且移除重复 JDBC 时区别名。

JDBC 显式设置会话/连接偏移、保留瞬时与微秒、拒绝零日期；MyBatis Map 对 TIMESTAMP 显式读取 Timestamp，修复只设驱动 getObject 选项仍返回 LocalDateTime 的问题。HTTP、效期、消息和恢复边界不再使用 JVM 默认时区。时间游标升级为保存 Instant 的 v2，跨 JVM 以 Timestamp 绑定 SQL；纯 ID v1 继续兼容，无时区的旧时间游标明确失效。上线需安排旧读节点排空，不能宣称 v1 旧节点已能读取 v2。完整配置与升级限制见 [DATABASE_TIME.md](../../implementation/DATABASE_TIME.md)。

/tmp/wms-time-cross-jvm-it.log 于 00:59:43 BUILD SUCCESS：真实 MySQL 下上海/美西独立 JVM 验证新库 UTC、微秒、跨 JVM 分页和 DATE 不漂移；旧 +08 库未知来源拒绝、声明后不改写旧值、偏移不可覆盖；InboundHttpIT、ReceiveMessagingProcessesIT、StockInternalReconcileIT、SerialRegistryHttpIT、FulfillmentHttpIT 通过。增加会话/精度约束后 TimeSemanticsIT 于 01:01:02 再次通过；实际四个 Seed*ReplayIT、BoundedPaginationIT、SourceOutboxIT 于 01:03:13 BUILD SUCCESS（/tmp/wms-time-seed-pagination-it.log）。直接 JDBC 测试夹具同步为显式 UTC，不通过修改测试 JVM 全局时区隐藏问题。58 个必需用例清单新增跨 JVM 与两个 R15 故障恢复门禁，最终全量组合仍待。

当前没有访问、转换或部署共享/生产数据库；其历史时区不能据此宣称已核实。R22 代码与定向证据已完成，整体整改仍有 R13/R14/R15 及最终验证工作。

## 消息健康状态与 CI 回归（2026-09-13）

上一阶段 main f9710ef 的远程 CI 34704623423 默认/warehouse 验证通过，在 tc profile 重复跑默认测试时暴露 InventoryMessagingIT 的 readiness 抖动。MessageWorker 原先每轮开始把状态清空，导致运行中的健康任务被瞬间判 DOWN；现在保存最近完整轮次结果并检查 30 秒新鲜度，失败立即降级，stop/start 以原子代际拒绝旧执行线程迟到结果。启动健康探针的断言按实际缓存与异步完成语义进行 15 秒有界等待，没有取消 UP/DOWN 断连验证。

/tmp/wms-messaging-health-it.log 于 01:05:24 BUILD SUCCESS（实际 Kafka/MySQL 投影、暂停 broker 后 DOWN、恢复后自动投递及全部单元）；最终代际原子化单元 /tmp/wms-worker-generation-tests.log 于 01:06:24 BUILD SUCCESS。CI 保留默认全仓验证，后续 warehouse/tc/failure 只执行各自拥有独立探针的 wms-test-support 模块，避免再把默认业务测试重复三遍。调整后的 warehouse 命令于 01:09:05 BUILD SUCCESS（/tmp/wms-ci-warehouse-only.log），其余 profile 与远程新提交 CI 仍待最终执行，不将旧失败改写为成功。

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
