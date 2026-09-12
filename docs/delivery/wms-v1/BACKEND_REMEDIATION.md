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
