# 详细设计评审报告

日期：2026-09-10。范围：本次设计与实施文档；无业务代码差异，无运行测试。

采用 engineering-workflows 架构咨询流程，Software Architect 子代理独立分析了库存分片、序列号归属、跨仓预占竞态和盘点冻结；主代理结合实际recon源码及官方资料综合并复核文档。不是多模型验证，也不是代码QA通过。

## 已识别并落实的设计问题

| 风险 | 具体失败场景 | 文档处理 |
| --- | --- | --- |
| 跨库假本地事务 | 多SKU操作按SKU落不同datasource，库存成功而流水失败无法一起回滚 | 数据设计限定同仓inventory事务数据同物理资源，单据独立库并走可靠协议，S0真实路由/回滚门禁 |
| 跨仓逐个确认竞态 | A已确认、B已过期，订单错误显示全分配成功 | 引入PREPARED与不可变决策，确认全部后执行授权 |
| 准备库存超时释放 | COMMIT已持久化但消息延迟，本地TTL释放 | PREPARED不可本地到期，超龄查询决策和告警 |
| 序列号双仓授权 | 源仓旧进程继续写，目标仅凭新epoch认为已接管 | 源本地SEALED+释放事实后才授目标资格；目的确认前隔离 |
| 冻结检查竞态 | 查冻结通过后另一事务冻结，原作业仍扣减 | 门禁锁与库存写共用协议；先排空物理任务 |
| 数量对账套金额 | 将EA/KG或小数量塞入currency/amountMinor，精度/语义错误 | quantity独立扩展，Money路径回归，接入保持待实现 |
| 任务假完成 | handler返回成功但业务shard未完成或旧worker继续提交 | 业务run/shard/checkpoint/epoch，调度与业务状态分离 |
| 规模无证据 | 直接把京东级写成已支持，给出任意分片数 | 参数化容量+合成样例+待签署SLO，生产量级未证明 |
| 对账假差异 | 不同提交时刻的余额/流水比较 | 关闭窗口、稳定cutoff、分片watermark和完整manifest |
| 版本假兼容 | 本地已有库/官方最新版即视为全组合可用 | S0锁定候选并做真实集成POC，不承诺已验证 |
| 部分拣货明细失真 | 预占10件只拣3件，单一桶关联丢掉源桶7件，或比例移动历史已发量 | 按本次q只转requested/remaining，目标增加picked，历史consumed/released留原行，serial关联原子迁移 |
| 调拨不同操作超收 | 两次不同operation各自读取同一剩余在途量并同时入账 | 全局接收额度token与报损仲裁，目的消费/取消同事务，未知结果不回收额度 |
| 序列号盘点只改数量 | 盘亏后桶数量变小但有效serial仍在 | 观察身份集合、FOUND/MISSING明细、登记收敛与数量对齐 |
| 质量/登记相互绕过 | 质检或登记任一确认就放行另一未完成条件 | 独立双资格，任意完成顺序和重复消息验收 |
| 冻结中无法调整或任意绕过 | 所有写都拒绝导致盘点死锁，或通用绕过允许普通作业 | 命令×门禁矩阵，仅匹配plan/epoch/approval的调整例外，核对后解冻 |
| 跨仓批次ID混同 | 目的库引用源仓内部lot行 | business_lot_key加源/目的映射，效期版本核对 |
| 全局按ID查找广播 | 分片按业务键、查询只有资源ID，找不到目标库 | ID携带稳定逻辑桶，客户端无权指定物理库 |
| 操作身份生成者冲突 | 请求要求服务端尚未生成的operationId，响应丢失无法重试 | 区分clientOperationId与服务端operationId；调拨token绑定客户端身份 |

## 保留的明确取舍

- 整单准备协议在网络分区时可能占用库存，优先正确性；业务如需部分履约必须显式调整承诺。
- 同仓单物理事务资源限制单仓横向写扩展，热仓先独享资源，再按实测重新定义原子范围。
- 序列号登记增加服务依赖；故障时收货/转移待确认隔离，但不授予双重库存所有权。
- 首期冻结盘点与停写迁移影响局部可用性，等待窗口确认。

## 验证范围与结论

文档可以作为v0.4实施基线；业务默认提案、版本兼容、数量对账扩展、容量和设备协议的未决项已列明。文档结构/引用检查结果记录在 [状态](DELIVERY_STATUS.md)。不存在业务编译/数据库测试/CI已通过的结论。

第二轮子代理复核确认原7项主要风险已有对应设计，并指出拆行历史量与operation身份两处收紧项，主代理已修订并同步接口/字段。上述结论是设计级审查，不替代并发和故障恢复测试。

开始依赖业务语义的切片前处理对应OQ；生产发布必须完成容量、故障、恢复和真实集成验收。

## v0.2首版三服务拆分评审

用户明确首期独立入库/出库/库存后，本轮重写服务/表/事务归属并新增08。Software Architect子代理先做独立协议分析，再只读审查08，指出4项必须收紧的竞态；主代理已落实并同步到数据字典/API/实施AC。

| 风险 | 本轮修订 |
| --- | --- |
| command未到但permit已STARTED，取消墓碑挡住真实过账 | command占位与permit共锁，取消/STARTED/POSTED同库存事务仲裁；UNKNOWN保留占用 |
| free claim只保护新预占，仍能被其他移位/调整侵占 | 全写入口保护reserved+free_execution_claim，serial排除全部活动permit |
| 两任务各占满同一预占行，释放偷走在途量 | reservation行锁及动作级inflight约束，拆行/释放不得侵占其他claim |
| 短拣无法收敛或误按授权量扣库存 | 一permit一结案；actual+确定未执行=授权量；未知不结案；intent与settlement摘要分开 |
| 质量撤销消息未到却宣称立即生效 | inventory门禁确认后才对外宣称限制生效 |
| 旧worker真实事实被丢弃、逆向消息先到就加库存 | 新执行权与旧事实恢复分离；逆向DEFERRED依赖原posting，累计可逆量有界 |

本轮新增AC-33..40，总计40项，均为待实现/待测试。设计审查不能证明真实设备幂等、版本兼容或容量达标。此为v0.2历史计数；当前v0.3结构验证以DOCUMENT_CHECK为准。

## v0.3 Seata TCC选型修订评审

用户已批准跨仓预占采用Seata TCC。此节替代前版表中自研PREPARED/全局decision的现行建议；历史风险分析保留供追溯。主代理同步重写02状态机、03字段/归属、04接口/事件、XXL职责、S4实施任务，新增09作为专项设计。

Software Architect子代理独立列出5项关键门禁：空回滚/晚Try同片Fence、二阶段独立路由、TRIED不TTL释放、TC全局完成后的持久化执行屏障、旧attempt不可换XID重占。均已纳入09与AC-41..44。TC持有全局决定；XXL不自行Confirm/Cancel；实物permit的PREPARED仍保留且不属于TCC。文档版本v0.3，业务与POC均未实现。

## v0.4四项幂等协议补充评审

用户明确批准保留原幂等/消息/部分结案/逆向约束，补齐四项协议及故障验收。新增10专项，同步03数据、04契约、08/09事务边界、任务恢复及唯一实施计划，新增AC-45..50，当前总计50项且全部planned。

- 分支所有者：同业务键不同XID/branchId不能返回已有成功或改绑，失败分支不能释放其他分支资源；实际代理重复Try返回与注册行为列入S0。
- 启动绑定：订单活动attempt及attempt的XID使用CAS，旧epoch无权绑定，已绑定未知结果只恢复；未绑定空启动必须证明无分支才能安全替换。
- 业务事实：逐动作规定收货part、任务子动作、发运part、补偿casePart、调整身份，配套服务端恢复入口及父数量预算。
- 重授权：effect仲裁唯一活动尝试和有效posting，command按attempt保留历史；旧尝试安全关闭后才生成新尝试，UNKNOWN/已实物执行不直接重做。

Backend Architect子代理只读复核10专项，提出两项修正并已纳入：绑定后仅部分Try完成时禁止错误提交，按TC状态恢复/回滚；已REJECTED结果保持原样，安全关闭证据单独保存。主代理同步核查旧命令唯一键与新增效果唯一约束、锁顺序、接口身份字段及AC/任务编号。

本次结论仅为已将四项风险转化为可实施规则和故障断言；不是分布式正确性已实测。此前版本的结构检查不能证明不存在并发漏洞。实际Seata/数据库/设备故障测试、性能及恢复时限仍未执行。

## S0首个实施切片评审

本轮由主代理做源码与实际测试结果的独立复核步骤，未宣称多代理代码评审。范围为独立启动骨架、探针、CI及实施门禁，尚无生产业务接口。

- 修复：ShardingSphere5.5.3插件显式装配和ANTLR4.13.2冲突，真实SQL复测通过。
- 修复：同仓回滚探针从同SKU两次更新改为两SKU，核对两行均回滚；补Boot MyBatis自动配置验证，避免只证明原生会话能运行。
- 边界：服务健康不代表数据库或业务就绪；不生成默认登录用户，未实现业务路径全部拒绝。
- 边界：CI只编译、验证、上传报告，没有生产部署。仓库既有.idea不纳入提交；机密及本地下载源在.local/.env忽略范围。
- 未闭合门禁：真实TC清理后getStatus丢失提交/回滚区分，EG-02仍需可靠终态证据适配；不能从局部Fence通过推导全局事务通过。

本轮局部验证可审查，整体项目未完成。证据见QA_REPORT及VERSION_LOCK；业务决定、远程首次main及后续验证分别记录，不将文档状态当运行证据。

## S0终态与回调路由切片复核

- 终态审计只捕获TC已持久化的9/11/13，缺失及Finished不授权；审计写失败让终态更新失败，恢复后由TC重试。仅隔离候选迁移，正式业务身份关联、最小权限、保留/HA未闭合。
- TC默认恢复阈值不是业务成功时限。探针降低阈值有明示说明，不作为生产参数推荐。
- 新路由探针在取连接时拒绝缺失/不匹配上下文，避开Seata hook吞异常导致的拒绝失效；SqlSessionTemplate与Fence使用相同DataSource事务资源。
- 两个仓为独立数据库与账号，但RM/TM同测试JVM。没有把手动branchRegister等同于真实HTTP/代理重试，也没有把ContextDataSource直连等同于ShardingSphere组合验证。
- 主代理源码复核；未新增多代理审查、未进行生产部署。运行结果与仍缺故障场景以QA报告为准。

## 独立RM与ShardingSphere切片复核

主代理进行源码和实际数据库结果复核，未新增多代理评审。

- `WarehouseRmProcess`只接本Cell账号，三表经同一个ShardingSphere数据源及Spring事务；不用不同DataSource分别执行Fence和库存。
- `IndependentRmProbe`以持有的Process句柄注入崩溃；关闭管道异常不能跳过进程回收，子进程日志/队列/内存及等待有界。账号不进入命令行或版本库。
- Try失败仅把专用库存不足异常转换为确定拒绝，其他异常导致探针失败，不将未知系统结果伪装成业务拒绝。
- 重启不重新prepare，断言原branch仍唯一；核查A效果一次、B失败局部回滚、空Cancel无业务效果。
- 每RM固定单Cell是当前已验证范围。所有权、业务幂等、动态Cell路由和容量仍有门禁，不能把该夹具当正式库存服务。

## S0-07启动CAS与重复Try复核

主代理对实际源码、Fence字节码和tc-it结果复核，未新增多代理评审。

- 启动领取/绑定/代际提升都在Mapper条件更新中完成，检查影响行数；begin不在本地事务内。无入口证明时过期租约不能重开。
- 真实`branchRegister`重试换branchId。预占唯一键拒绝改绑；非所有者Cancel影响行数为0，不得改库存。
- Seata 2.6重复prepareFence不是幂等成功：DuplicateKey后把Tried记录投入异步清理队列。夹具因此不在仍需Cancel的分支上重放Try。这证明设计要求“禁止盲目重试Try”有运行时依据。
- 不是HTTP网关、正式TM进程或多仓履约验收。AC-45/46仍planned。

## 业务屏障与failure-it复核

同会话对抗复核实际diff与命令输出，不是独立多智能体审查。

- `TerminalEvidenceAdapter`只读审计库；缺证据/错代际/空参与者/TM身份不匹配一律`RECOVERY_PENDING`，回滚终态`DENIED`，仅提交9且Fence=COMMITTED才允许Outbox。
- 审计账号INSERT被数据库拒绝。XXL路径显式抛错，不调用TM commit/rollback。
- 初版`evidenceStatus == 9`在证据尚未落盘时NPE，已改为null安全比较；`tc-it`两项复测通过。
- `OwnedContainerGuard`拒绝非owned与dev-infra；不能用共享容器StartedAt作门禁（本机ClickHouse会自行重启）。failure-it不在kill TC后要求TM begin重连。
- 仍不是正式`wms-fulfillment`、生产最小权限或EG-02关闭。

## S1-01/S1-03 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- 主数据表归属 inventory，模板字段与字典一致；`NO_LOT` 禁止写入 lot 表，无批次走 sentinel。OQ-03 未确认，效期只存 UTC/源日期/规则版本，没有默认 00:00 换算。
- `SkuPolicy` 未知状态抛错；换算 `RoundingMode.UNNECESSARY`。门禁新建为 OPEN+fence_epoch=0，与库存写锁协议后续切片衔接，本轮无 HTTP 写入口。
- inventory 引入 mybatis 核心但不引入 JDBC starter，避免 smoke 无数据源启动失败。Boot 进程仍 denyAll。
- OpenAPI 覆盖设计表、内部 stock-commands/permits、主数据与 action-effects；TCC 不开放 REST prepare。契约测试不能证明运行时鉴权。
- 确认：未把未实现切片标为 AC 通过；未开始 `wms-console/`。

## S1-02/S1-04 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- 种子只写调用方显式 JDBC；43306/`dev-infra` 在 shell 与 `SeedLocal.requireIsolated` 双重拒绝。SKU 模板两边都种，仓/库位/批按 Cell 切开，符合模板 C / 仓数据 W。
- `operator_grant` 是审计映射，HTTP 鉴权只信 JWT `warehouses`/`enterprise_id`。跨仓 403 有 IT 证据。
- issuer 默认空字符串，smoke 仍 denyAll。JwtDecoder `@ConditionalOnMissingBean`，测试用本机 RSA，不把 localhost:8000 写进默认配置。
- inventory 不引入 `spring-boot-starter-jdbc`，无 URL 时没有 DataSource 自动配置类可启动失败。
- V002 已被授权映射占用；S2 `V002__inventory_transactions.sql` 文件名需改为 V003，否则 Flyway 冲突。这是范围内的版本号占用，不是提前实现 S2。
- auth-platform 脚本不写 SpiceDB、不提交口令。现场 Casdoor 未验证前不能声称身份已开通。
- 确认：50 项 AC 仍 planned；未开始 `wms-console/`。

## S1-05 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- GET lots 走 `requireWarehouse`，与 locations 同一越权路径；GET units 按企业 `countSku`/`listSkuUnits`，商品主数据不是仓级资源。denied 空仓可见 SKU 目录、不可见仓/库位/批，与现有 `listSkus` 一致。
- Mapper 查询均带 `enterprise_id`，lots 另带 `warehouse_id`，units 另带 `sku_id`。不接受请求头扩大权限。
- DATETIME(6) 注释为 UTC，JDBC 默认按本地墙钟读写。HTTP 将 `LocalDateTime` 按 `ZoneId.systemDefault()` 还原 Instant，与 `SeedReplayIT` 的 `Timestamp.toInstant()` 一致。拒绝改成“一律当 UTC 墙钟”，否则上海 JVM 会把种子 13:00Z 显示成 21:00Z。OQ-03 未批准生产时区换算。
- 数量 JSON 用十进制字符串，匹配 Quantity 契约；测试断言 `"numerator":"12"`。
- `NoSuchElementException` 仅由缺失 SKU 抛出并映射 `SKU_NOT_FOUND`。写接口仍未交付。
- Casdoor 开通成功不能证明隔离 compose 库存库已有种子。Maven IT 仍用测试 RSA `@Primary JwtDecoder`。
- 确认：无 critical/high；50 项 AC 仍 planned；未开始 `wms-console/`。

## S1-06 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- 业务唯一靠事实列 UNIQUE，不透明 UUID 仅作对外 identity；适配层在缺事实时抛 `EFFECT_IDENTITY_CONFLICT`，源码路径无 `randomUUID` 作为兼容回退。
- OPEN/STARTED 拒绝新尝试；UNKNOWN 返回 202 且不 insert attempt。APPLIED 看 `applied_command_id`。安全关闭本轮用测试 SQL 置位，不是库存 cancel/permit。
- digest 重放使用保存的 `digest_version`+canonical，v2 多数量字段不能改变 v1 结果。
- Mapper 均带 enterprise/warehouse。S2 事务表必须用 V004。
- `GET /tasks/{id}/action-effects` 未实现，任务模块不在本切片。
- 确认：无 critical/high；AC-47..50 仍 planned；未开始 `wms-console/`。

## S2-01 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- 数量 `RoundingMode.UNNECESSARY`，没有 HALF_UP 回落。同精度才能加减。
- 桶键 lot 走 `requireCode`，无法用 null 规避唯一；质量封闭 HOLD/GOOD/REJECTED，未知不得当 GOOD。
- 预占 CONFIRMED→CANCELLED 被拒绝，符合「已提交后用户取消走新业务释放」。
- 门禁矩阵用 ALLOW/DENY/DRAIN/ISOLATE，布尔默认 true 不会出现。MAINTENANCE 只放行维护命令。
- 分配策略无默认 FIFO。序列号可用量不走桶公式。
- 确认：无 critical/high；未写库存表；未开始 `wms-console/`。

## S2-02 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- 计划 V002 文件名已被占用，迁移为 V004。流水增加 claim 增减列，否则第三占用无法入账。
- `casReserveGood` 硬编码 `quality_code='GOOD'`，HOLD 影响 0 行，不会把待验当可分配。
- `casAdjust` 依赖表 CHECK 拒绝为负/超占；影响 0 行是版本冲突，不能当成功。
- 预占所有者唯一键含 xid/branch_id/action_name，XID 长度 128 与官方 schema/S0 探针一致。
- 空桶 INSERT ON DUPLICATE 后按维度加锁读回原 id，禁止 Java 侧“查不存在再插入”竞态。
- Mapper 均带 enterprise/warehouse。本切片无应用服务、无 Outbox。
- 确认：无 critical/high；AC-03 仍 planned；未开始 `wms-console/`。

## S2-03 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- 流水与 `outbox_event` 同 `SqlSession`；`insertPending` 失败则整会话回滚，不会出现有流水无 Outbox。
- 重放只看 `countLedger`，同 `operationId` 不二次写流水/Outbox。失败路径（冻结、不足、超发）在 `recordLedgerAndOutbox` 之前抛错。
- Outbox 仅 `PENDING`；无 claim/lease/publish。`payload` 含 delta/after/`ledgerEntryId`，信封列含 `STOCK_BALANCE`/`InventoryBalanceChanged`/聚合版本。
- 跨仓移库拒绝；CONFIRMED 不能 TCC Cancel。`casAdjust` WHERE 守卫使不足返回 0 行，不是 CHECK 异常当成功。
- 确认：无 critical/high；AC-03/AC-05 仍 planned；未开始 `wms-console/`。

## S2-04 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- 领取与投递分会话：先提交 CLAIMED 再调 transport，发送后宕机可凭过期租约重领，符合至少一次。`claim_epoch` CAS 防止旧发布器结案。
- 未加入 Kafka 依赖；`PUBLISHED` 只表示 transport 未抛错。内存 transport 不能证明外部投递。
- `command_dedup` 用 INSERT IGNORE + 锁读比较摘要；同键异内容不覆盖。失败过账回滚幂等行。
- 入出库服务本轮无自有 Outbox 表，因其尚无业务事件；S2-04a/S3 再补。
- 确认：无 critical/high；AC-03/AC-05 仍 planned；未开始 `wms-console/`。

## S2-04a 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- 锁顺序：先 `stock_effect` 再 `stock_command` 再余额原语。已 APPLIED 效果拒绝其他命令；墓碑后 `applyReceive` 不写流水。
- STARTED permit 禁止普通取消。本切片收货直接 POSTED，不模拟设备 UNKNOWN。
- T1/T3 与 T2 分库分会话；`ThreeServiceProtocolIT` 用三容器证明，不经 Kafka。
- inbound V001 占用计划 S3-01 的 `V001__inbound.sql` 文件名，S3 改为后续版本。
- 确认：无 critical/high；AC 仍 planned；未开始 `wms-console/`。

## S2-05 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- 并发预占 CAS 失败不能一律当不足：重锁后 available < qty 才 `STOCK_INSUFFICIENT`，否则最多再试 16 次，耗尽 `VERSION_CONFLICT`。`InventoryConcurrencyIT` 观察到 10/10 胜负与 reserved=on_hand。
- 同键异内容走既有 `command_dedup`，不覆盖摘要。Outbox 插入失败用库内触发器，不是 mock Mapper；失败后 ledger/dedup 为 0。
- Testcontainers MySQL 默认 binlog 拒普通用户建触发器；IT 用 root `SET GLOBAL log_bin_trust_function_creators=1`，不改生产镜像。
- 确认：无 critical/high；AC-03/04/05 仍 planned；未开始 `wms-console/`。

## S2-07 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- 命令唯一键保持 effect+action+attempt_no，没有只放宽旧索引。posting 改为效果级唯一，同效果不能两份有效凭证。
- T1/T2 先锁 effect；同事实换键返回原命令，不插第二 attempt=1。未 SAFE_CLOSED 不得发新尝试；APPLIED/STARTED 拒绝安全关闭。
- 补偿是新 CASE_PART 效果并引用 original_posting_id，本切片不回冲原收货余额。
- 确认：无 critical/high；AC-47..50 仍 planned；未开始 `wms-console/`。

## S3-01 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- 入库单只在 inbound；库存只落 `quality_qualification`。行累计 physical/posted 分列，CHECK 保证 posted<=physical、上架实物<=收货实物。
- T3 用 inbox 插入行数决定是否加 posted，重放不二次累计。超收在加 physical 前按剩余额度拒绝。
- 质检版本乱序不覆盖已生效结论。本切片不改质量桶、不建序列号登记。
- 确认：无 critical/high；AC-07 仍 planned；未开始 `wms-console/`。

## S3-02 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- 登记库独立模块，不写入库单或库存余额。唯一维是 enterprise+SKU+normalized_serial，符合已确认 OQ。
- INSERT IGNORE + 行锁后比较 claim_operation_id，并发第二仓得到 `SERIAL_ALREADY_CLAIMED`。
- 本切片只到 CLAIMED，不激活、不联合质检放行。smoke 仍三进程。
- 确认：无 critical/high；AC-08 仍 planned；未开始 `wms-console/`。

## S3-03 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- 登记激活不改质量桶。AUTHORIZED 只表示登记放行；GOOD 桶仍空，预占得到 `STOCK_INSUFFICIENT`。
- 登记不可用时本地 HOLD 与 `local_serial` EXCEPTION 一起保留，不删除意向。恢复只补登记，不二次加量。
- 库存不编译依赖登记 Boot 包；测试用内存端口。两库真实 HTTP 联调仍未做。
- 确认：无 critical/high；AC-08/09 仍 planned；未开始 `wms-console/`。

## S3-04 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- FEFO 不默认 FIFO。过期批次可收货保留实物，但不能新预占，也不进候选。
- 入库上架看最新质检结论和目标库位类型；不合格或发运位不写任务/实物。
- 两仓并发失败仓保留 HOLD 意向，不删除本地记录。
- 确认：无 critical/high；AC-08/09/15 仍 planned；未开始 `wms-console/`。

## S3-05 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- 观察绑定在额度检查之后写入，超量拒绝不留分批行。同序号只按摘要恢复，不按新 client 命令加量。
- 业务事实键是 receiptSession+part+line；设备会话重置仍带原 part 则复用命令。缺身份直接隔离。
- 未把 inbound 观察接到库存 HTTP。AC-47 端到端仍 planned。
- 确认：无 critical/high；AC-47 仍 planned；未开始 `wms-console/`。

## S4-02 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- `allocation_digest` 含仓+行+SKU+数量+单位；不足量拒绝，不写 attempt。
- 截止后拒绝 claim/bind/tryHeaders；观察/ALLOCATED 不走该门。
- `TryPropagation` 无 XID 不得出头；错 XID/错 TM 拒绝。未引入 Seata 客户端，不是真实 Try。
- DATETIME 截止按 JDBC `LocalDateTime` + JVM 默认时区还原，未发明 OQ-03 UTC 墙钟规则。
- 确认：无 critical/high；AC-10/12 仍 planned；未到 S8 不创建 `wms-console/`。

## S4-03 复核

同会话对实际 diff 复核，不是独立多智能体审查。

- Confirm 只 CAS `TRIED→CONFIRMED`，不调用 `casReserveGood`；冻结门禁仍允许完成原预占。
- Fence 与业务共用库存 DataSource/`TransactionTemplate`；拒绝 `DataSourceProxy`。inbound/outbound POM 仍无 seata。
- 未 `RMClient.init`、未接真实 TC。空回滚由官方 Fence status=4 处理。
- `cancelTried` 无预占时不再抛 `RESOURCE_NOT_FOUND`，以支持空回滚；带 XID 的 Cancel 校验所有者。
- 确认：无 critical/high；AC-10/12 仍 planned；未到 S8 不创建 `wms-console/`。
