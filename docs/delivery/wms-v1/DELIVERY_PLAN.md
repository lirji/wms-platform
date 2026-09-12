# WMS v1 具体实施计划

## 1. 范围、当前阶段与批准记录

本文件是唯一实施计划，技术细节引用 [设计索引](../../../README.md)，不再创建另一份 FINAL_PLAN。当前进入实施，先完成S0工程与兼容验证，再依阶段退出条件推进业务。

授权来源：用户已批准v0.4设计，并明确“那就先补齐几个执行门禁后推进业务开发”。当前授权覆盖本项目计划补充、S0及后续已确定业务的实现、隔离本地测试和持续Git交付；不包括生产部署、修改共享基础设施配置或未经明确范围确认的recon代码。前端、后端、数据库与API均由当前实施者负责，不再按工具拆分。沿用本文件，不重建计划。

可行性 conditional-go：技术方向可实现；版本兼容 POC、规模/SLO、业务提案、对账 quantity 扩展和现场设备环境是对应阶段的门禁。文档交付可以完成，不把实施未开始记成系统上线。

## 2. 角色和范围

业务角色：仓管员、收货员、质检员、拣货员、复核发运员、盘点员、审批员、运营配置员、对账处理员、运维人员。服务身份：OMS、ERP、WCS、对账平台、调度器，各自只拥有所需仓和动作权限。

v1 范围：基础资料、入库质检上架、库存预占/释放/冻结、批次/序列号/效期、出库执行、跨仓分配/调拨、盘点调整、任务恢复、内部核对、独立对账适配、查询投影、权限审计及运维。高级机器人路径规划、第三方仓租计费、财务账务、完整 TMS、生产部署不在本次文档执行范围；未来硬件接入按明确协议逐个交付。

## 3. 业务验收矩阵

所有条目当前状态 **planned，未执行**。通过条件是观察业务效果与持久化记录，不能只断言 HTTP 200 或源码含某个注解。

| AC | 可观察验收行为 | 实施切片 | 证据 |
| --- | --- | --- | --- |
| AC-01 | 伪造企业/跨仓权限访问被拒绝，日志无越权数据 | S1 | HTTP黑盒+权限集成测试 |
| AC-02 | SKU 单位精度、批次、序列号开关、效期校验正确 | S1/S2 | DTO/领域用例与真实库数据 |
| AC-03 | 并发预占100件不超分，余额>=reserved+freeExecutionClaim，两种占用非负 | S2 | 并发请求、最终余额/预占/流水 |
| AC-04 | 同键重试一次入账，同键异内容409 | S2 | 响应丢失后重试与数据库核对 |
| AC-05 | inventory流水/Outbox失败，余额/库存凭证/占用整体回滚；来源单据保留PENDING恢复 | S2 | 真实MySQL注入失败 |
| AC-06 | 同仓inventory多SKU只路由一个物理事务资源；入/出库独立数据库，无仓键拒写 | S0/S2 | 实际路由与连接日志、回滚证明 |
| AC-07 | 收货/上架重复和部分完成正确，总量守恒 | S3 | 端到端单据及成对流水 |
| AC-08 | 两仓首次登记相同序列号，只有一个有效授权 | S3 | 登记并发与两仓库存核查 |
| AC-09 | 序列号接收确认丢失可恢复；登记/质检任意顺序完成，双资格未齐不可分配 | S3 | 崩溃、乱序/重复确认与本地记录 |
| AC-10 | Try成功后库存保留，TC全局超时驱动Cancel；XXL不释放TRIED | S4 | 真实TC超时与分支状态/库存量 |
| AC-11 | TC二阶段中断可恢复，TRIED不TTL释放，Confirm不重新抢库存 | S4 | TC/RM重启、Confirm重试和库存核对 |
| AC-12 | 旧attempt/错误XID隔离，全部仓确认且TC全局成功前不能执行 | S4 | TC终态与分支屏障、业务建单恢复 |
| AC-13 | 部分拣货/包装/发运符合数量约束，无重复扣减 | S5 | 明细数量、serial、库存流水 |
| AC-14 | 已拣取消回库，已发取消走补偿不直接回滚 | S5 | 正/逆向用例与非法迁移测试 |
| AC-15 | 效期在分配与发运STARTED授权重校验；已执行迟到事实保留并按permit恢复 | S3/S5 | 控制时钟边界与任务延迟 |
| AC-16 | 跨仓调拨部分接收/损耗/迟到事件守恒；不同操作并发收货不超额度 | S6 | 两仓+在途+额度token+差异核对 |
| AC-17 | 源SEALED后旧授权失效，目的确认前不可分配 | S6 | 旧epoch/重复转移/故障恢复 |
| AC-18 | 盘点冻结与库存写入具备确定顺序 | S6 | 冻结/出库/移位并发测试 |
| AC-19 | 盘亏不足覆盖预占时拒绝；冻结内仅授权调整；serial增减身份与数量一致 | S6 | 预占冲突、重复调整、盘盈登记/盘亏失踪恢复 |
| AC-20 | worker宕机/换主后继续检查点，旧worker不能提交 | S7 | task shard持久化及fencing测试 |
| AC-21 | 数据库提交与消息发送间崩溃不丢最终投递 | S2/S7 | Outbox恢复+消费者一次业务效果 |
| AC-22 | 重复/乱序/缺口事件处理正确，投影可重建 | S7 | 重放、版本缺口与重建比对 |
| AC-23 | 内部余额/流水/预占/serial核对识别真实差异 | S7 | 注入隔离差异与稳定cutoff验证 |
| AC-24 | 数量对账识别缺失/数量/单位/批次/序列号差异 | S8 | WMS+recon真实联调与金额回归 |
| AC-25 | 设备UNKNOWN不盲重发；离线重放不会重复记账 | S5/S8 | 模拟协议及单列真实设备证据 |
| AC-26 | 前端显示处理中/陈旧/冲突，操作权限和恢复正确 | S8 | Cursor返回版本的UI黑盒验收 |
| AC-27 | 峰值、热点与后台重放下资源有界且不变量不破坏 | S9 | 已签署负载的压测报告 |
| AC-28 | 仓迁移后旧路由拒写，库存/任务/Outbox完整 | S9 | 两物理库迁移与失败回退演练 |
| AC-29 | N/N-1接口事件和扩展迁移支持滚动升级 | S9 | 版本共存矩阵与回退验证 |
| AC-30 | 隔离恢复可用库存、决策、serial和积压，测得RTO/RPO | S9 | 恢复报告与对账证据 |
| AC-31 | 数据库种子幂等，页面业务演示数据来自API | S1/S8 | 连续两次seed结果与网络检查 |
| AC-32 | CI遇真实库测试未启动/被跳过不得显示集成通过 | S0/S9 | CI必选集成profile与报告断言 |
| AC-33 | 三服务独立部署/库/账号，互相不能写业务表 | S0/S2 | 三库权限负例和独立启停/发布验证 |
| AC-34 | T1/T2/T3任一崩溃可恢复，库存已过账回执丢失不重复入账 | S2/S3/S5 | source_command/posting/inbox最终逐项核对 |
| AC-35 | 取消先到留下墓碑；已STARTED/UNKNOWN不能直接释放；逆向先到不能先加库存 | S2/S5 | 乱序、并发取消、补偿依赖及限量测试 |
| AC-36 | 同批库存并发授权不重复占用，非预占执行claim阻止新预占 | S2/S5 | claim/serial/余额约束及并发测试 |
| AC-37 | 冻结排空三服务在途permit/命令，旧worker真实回执可核验恢复 | S5/S6 | STARTED故障/晚回执/门禁快照验证 |
| AC-38 | 质量撤销以inventory确认生效，登记/质检独立版本防乱序 | S3/S5 | 撤销与授权并发、陈旧资格事件 |
| AC-39 | 来源physical/posted与库存posting按三方watermark核对 | S7/S8 | 同步延迟/缺回执不误判、超龄异常 |
| AC-40 | UI能区分货已执行但库存待同步，禁止再次实物操作 | S8 | 202/UNKNOWN/PENDING恢复端到端 |
| AC-41 | TCC空回滚、Cancel先到/晚Try、重复Confirm/Cancel不多占或多放 | S0/S4 | 真实TC/RM+Fence与余额验证 |
| AC-42 | TM/TC宕机后保留同attempt/XID，结果未知不重开、不放行 | S4/S9 | TC恢复、终态证据缺失和建单Outbox补齐 |
| AC-43 | Try/Confirm/Cancel的Fence与库存同物理连接，二阶段换实例可路由 | S0/S4 | 双库存分片、RM重启、原子回滚与无广播路由证据 |
| AC-44 | TCC仅覆盖跨仓预占，无AT代理/XA、Kafka/线程池不泄漏XID | S0/S4/S9 | 有实际行为的上下文隔离/事务边界测试 |
| AC-45 | 重复Try换branch/XID不能接管原预占，原身份重放不多占 | S0/S4 | 实际RPC重试、owner冲突及交错Confirm/Cancel；见10专项 |
| AC-46 | 并发启动只绑定一个XID，启动崩溃/部分Try不错误提交 | S0/S4/S9 | begin/绑定断点、CAS胜负、旧epoch隔离与原事务恢复 |
| AC-47 | 换客户端键/HTTP-MQ/离线重报同事实不重复，合法分批正常 | S2/S3/S5 | 收货part/子动作映射、父额度与库存流水核对 |
| AC-48 | 安全关闭后允许唯一新尝试，旧命令/回执不能覆盖或再执行 | S2/S5 | 改包裹、双重授权、取消响应丢失、旧消息并发 |
| AC-49 | STARTED/UNKNOWN/已实物执行/APPLIED不允许重做原效果 | S2/S5 | 原结果恢复、核验与补偿入口；同effect最多一posting |
| AC-50 | 同补偿事实只入账一次，独立部分累计不超量，摘要版本重放稳定 | S2/S5/S9 | casePart换键/并发部分逆向/DEFERRED/版本共存 |

## 4. 阶段、依赖与退出条件

第一版的三服务、命令恢复和执行资格是必选范围，不先交付合并核心再拆分。执行依赖：S0 → S1 → S2 → S3 → S4 → S5 → S6 → S7 → S8 → S9。S7 的任务执行框架/消息恢复从 S2 起随业务同步交付，S7 是完善与汇总验收，不能在首期跨服务后才补幂等和 Outbox。Cursor 可在 S1 的契约和种子接口稳定后并行开展前端。

| 切片 | 可交付成果 | 主要依赖/退出门禁 |
| --- | --- | --- |
| S0 工程与兼容验证 | 独立inbound/outbound/inventory骨架、三库及双Cell环境、版本锁/CI/路由POC | 远程/CI明确；组件组合真实通过，AC-06/32基础 |
| S1 基础资料与安全 | 仓/库位/SKU/批次、权限、审计、接口文档、种子 | OQ-03/10相关规则；AC-01/02/31 |
| S2 库存事务内核 | inventory命令/凭证/执行permit/claim、余额/流水/预占/Outbox及来源最小客户端 | 库存本地原子性、AC-33..36，不等待S7补恢复 |
| S3 入库与序列号 | inbound独立收货/质检/上架、T1/T2/T3恢复、登记隔离/效期 | OQ-01已确认；AC-07/08/09/15 |
| S4 跨仓履约 | 选仓、Seata TCC Try/Confirm/Cancel、全局成功屏障 | OQ-02/OQ-04已确认（TM=`wms-fulfillment`）；版本POC；AC-10..12/41..46 |
| S5 出库闭环 | outbound独立拣货/包装/发运、permit/取消回库/设备抽象 | AC-13/14/15/25模拟部分 |
| S6 调拨与盘点 | 在途、序列号移交、盘点冻结、审批调整 | OQ-06；AC-16/17/18/19 |
| S7 任务、查询、内部核对 | XXL运营任务、查询投影、恢复、内部差异 | AC-20/21/22/23 |
| S8 外部对账与UI | quantity适配、OMS/ERP接口、Cursor集成 | OQ-07/09；AC-24/25真实部分/26/31 |
| S9 容量与发布准备 | 压测、迁移、恢复、兼容、发布手册 | OQ-05/06/08及外部环境；AC-27..30/32 |

不在未知团队人数/交付节奏下编造工期。S0 完成后依据切片吞吐和外部依赖交期估算日期，单独维护里程碑，技术门禁不因排期自动取消。

## 5. 可直接领取的实施任务

### S0 工程与选型验证

- S0-01 本地仓库已存在，origin为GitHub；当前已核对origin/main存在且包含a37477b基线。使用feat/wms-s0-foundation，保护.idea；沿用持续授权正常快进发布，不强推。
- S0-02 产出 `docs/implementation/VERSION_LOCK.md`，验证候选组合，登记许可证/漏洞/镜像摘要；所有版本在父 BOM 管理。
- S0-03 创建Maven Wrapper/父pom，以及wms-inbound、wms-outbound、wms-inventory独立启动包/镜像/配置/数据库账号；包前缀com.lrj.wms。共享wms-contract只含契约，不共享领域实体或Mapper。
- S0-04 编写 `deploy/compose.local.yml` 及 `.env.example`（仅变量名/占位），记录 dev-infra 资源；隔离故障测试另起专属实例。
- S0-05 创建 `wms-test-support/.../ShardingRouteIT.java`，验证三个服务独立数据库与账号、双Cell库存数据源、inventory同仓多SKU同连接、缺键拒写、事务回滚、唯一约束、锁和批量SQL。
- S0-05a 加入真实Seata TC与两个inventory分片，验证TCC Fence同连接、RM回调路由、TC宕机恢复和禁用AT代理；锁定Seata Server/Client及schema版本。
- S0-06 根据确定的CI平台创建流水线；文档中的命令在此阶段成为实际存在且可执行入口。

- S0-07 验证实际Seata/RPC重试是否重新注册branchId及Fence对重复Try的返回行为；验证begin/绑定故障与epoch隔离，建立AC-45/46真实集成夹具。

- S0-08 唯一TM已确认为 `wms-fulfillment`；无现有OMS协调器。S0的TC实验可用隔离探针，正式履约服务在S4创建。
- S0-09 候选实验与限制见[TC终态证据](../../implementation/TC_TERMINAL_EVIDENCE.md)。验证选定Seata版本全局提交完成证据的真实获取机制、持久化时机、TC记录清理和TM宕机窗口；若无法形成可恢复成功屏障，不通过该组合门禁。
- S0-10 产出可重复构建、三服务独立启动和隔离数据库测试证据；其他服务启动包/账号在首次业务切片创建：fulfillment为S4、serial-registry为S3、integration为S5、query为S7，不以核心三服务冒充全平台。

### S1 基础资料与安全

- S1-01 `wms-inventory/masterdata`：实现仓、库位、门禁、SKU、单位版本和批次，迁移 `wms-inventory/src/main/resources/db/migration/V001__warehouse_masterdata.sql`；表/每列中文注释。
- S1-02 接入已确认的 OIDC 资源服务器：issuer/client/权限映射用环境配置指向已有提供方，不在仓库写死密钥；认证集成失败不能在共享测试环境回退免认证。未指定具体 IdP 产品。
- S1-03 `wms-contract/src/main/resources/openapi/wms-v1.yaml`：落实接口、schema、错误、分页、状态和幂等头；生成契约测试。
- S1-04 `scripts/seed-local.*`：幂等写入2仓、普通/批次/序列号/临期/过期SKU、权限账户映射、基础库位、开账库存投影、草稿盘点，以及入出库/履约/调拨演示单；attempt 只写 PLANNED；仅作用于显式测试库。
- S1-05 用测试身份验证越权、单位换算、效期字段和数据库种子复跑，提交该完整切片。

- S1-06 细化每动作事实身份、digestVersion规范及effect/attempt查询/重授权契约，验证未来版本适配不会随机生成业务身份。

### S2 库存事务内核

- S2-01 `wms-inventory/inventory/domain`：Quantity、StockBucketKey、ReservationState、InventoryPolicy；封闭集合稳定code，数量精度显式校验。
- S2-02 `wms-inventory/inventory/infrastructure/mapper`：余额、预占、流水和门禁Mapper/XML，迁移 `V002__inventory_transactions.sql`。
- S2-03 `InventoryApplicationService`：收货入账、预占、释放、移动、发运原语；统一门禁/锁顺序、版本、影响行数、流水和Outbox。
- S2-04 各服务自有 `outbox`：持久化领取、发布、重试、隔离，按物理数据源扫描；`command_dedup` 与业务同事务。
- S2-04a 新增stock_command/posting、execution_permit/claim、取消墓碑、业务效果唯一键；inbound/outbound各自source_command/source_execution/inbox，最小三服务T1/T2/T3闭环与恢复查询。
- S2-05 测试 `InventoryConcurrencyIT`、`IdempotencyRecoveryIT`、`OutboxCrashRecoveryIT`，保存SQL和最终不变量证据。必须通过后才上层业务依赖。

- S2-07 在来源与库存分别迁移source_effect/stock_effect、command尝试字段及posting效果唯一约束；同切片替换旧effect+action命令唯一键，统一effect锁与原事务入口，覆盖AC-47..50基础。

### S3 入库与序列号登记

- S3-01 在wms-inbound实现收货/质检/上架与physical/posted双累计、source命令；独立迁移`wms-inbound/src/main/resources/db/migration/V001__inbound.sql`。inventory另建quality_qualification与库存操作迁移，不写入库单。
- S3-02 新建 `wms-serial-registry` 身份/归属聚合与迁移 `db/migration/registry/V001__serial_registry.sql`；全局逻辑桶路由与唯一性。
- S3-03 实现 CLAIMED、本地HOLD收货、登记激活、本地放行和恢复查询；登记不可用时保留意向和异常状态。
- S3-04 实现FEFO候选和实时效期校验；覆盖序列号重复、两仓并发登记、质检不通过、上架错误库位。

- S3-05 实现收货session/part/行事实映射、离线观察映射、分批额度和身份恢复；同次收货换键与合法第二批均纳入AC-47。

### S4 跨仓履约

- S4-01 fulfillment实现attempt/XID/participant映射及TC状态证据；删除设计中的自研decision权威字段，迁移`db/migration/fulfillment/V001__allocation_tracking.sql`。
- S4-02 候选仓策略固定参与者/数量/摘要，单attempt唯一XID，未知结果不重开；保持有界Try和显式XID传播。
- S4-03 inventory实现ReservationTccAction：Try预留/TRIED，Confirm确认不重新竞争，Cancel释放；使用受支持的Fence与同库本地事务，禁用AT数据源自动代理。
- S4-04 `SeataTccRecoveryIT`、`TccFenceShardingIT`覆盖AC-10..12/41..46：空回滚、防悬挂、二阶段重入、TC/RM故障、二阶段换实例路由、终态缺证据、跨attempt误重试。
- S4-05 全局TC成功且全部仓CONFIRMED后，在独立本地事务写ALLOCATED与出库建单/执行授权Outbox。失败由恢复任务补齐，不在TCC事务内派发设备。
- S4-06 XXL只监控TRIED/TC状态、同步结果并补齐业务Outbox，不发送二阶段决定；TC持久化/会话保留和业务终态证据保留纳入运维验收。

- S4-07 实现owner严格匹配、Try失联回滚/恢复、订单活动attempt CAS、launch记录与XID绑定/空启动清理，覆盖AC-45/46。

### S5 出库与设备适配

- S5-01 在wms-outbound实现单据、任务、包裹、physical/posted累计、取消和来源命令，独立迁移`wms-outbound/src/main/resources/db/migration/V003__outbound.sql`（V001 已被来源协议占用）；inventory负责permit/claim、转桶和扣减，不写出库单。
- S5-02 `wms-integration/wcs` 定义命令/查询/回执适配端口；编写明确标识 simulator 的测试实现。
- S5-03 人工/PDA/设备共享稳定动作身份，worker换主沿用；STARTED授权后才能派发，UNKNOWN保持库存占用。可信旧worker回执用于恢复，但旧worker不能新派工。逆向依赖原posting且累计不超过可逆量。
- S5-04 黑盒验证同单部分完成、短拣、效期在拣货后到期、重复发运及取消后回库。

- S5-05 实现安全关闭独立证据、executionAttempt重授权、迟到旧命令处理与补偿casePart身份；覆盖AC-48/49/50，保留旧幂等与部分执行回归。

- S5-06 以同一批测试数据串联收货→入账→两仓预占→授权→拣货→发运→来源回执收敛；逐项核对余额、流水、预占、claim/permit、posting和来源累计。包含一次响应丢失恢复，作为进入S6的必选门禁。

### S6 调拨与盘点

- S6-01 全局调拨/仓级子单、在途行；源发出与目的接收事实以操作键去重。
- S6-01a 调拨接收额度token、目的仓消费/取消仲裁、报损竞争与批次映射；不同operation并发也不得超收。
- S6-02 序列号TRANSFER_PREPARED/SEALED/IN_TRANSIT/RECEIVING/ACTIVE与epoch，覆盖旧事件和目的重复收货。
- S6-03 盘点QUIESCING与物理排空、冻结门禁、点数/复盘、审批、预占冲突和分片调整恢复；迁移 `V005__count_transfer.sql`。
- S6-03a 实现门禁×命令矩阵、序列号观察集合、FOUND/MISSING调整协议；部分失败保持冻结，登记收敛后核对解冻。
- S6-04 `CountFreezeRaceIT`、`SerialTransferRecoveryIT`、`TransferConservationIT`验证真实数据库和独立服务边界。

### S7 调度、投影与内部核对

- S7-01 集成XXL-JOB，注册 [任务目录](../../design/05-jobs-reconciliation.md)；admin/core版本一致，集群及重复触发验证。
- S7-02 业务job_run/job_shard、检查点/epoch/取消/隔离/人工重试；迁移 `V006__jobs.sql`。
- S7-03 查询投影按eventId+aggregateVersion更新，API显示asOf/lag；按范围可重建，重建切换有追平校验。
- S7-04 稳定cutoff内部对账、差异工作台和审批修复；来源事实/库存posting/回执watermark分别核对；后台预算与前台隔离。
- S7-05 任务中断恢复、旧worker回写、投影乱序/缺口、消息恢复负载测试。

### S8 跨系统与前端

- S8-01 固化 WarehouseQuantityFact 和快照manifest；生成数据导出API与服务权限，不开放核心库账号。
- S8-02 recon-platform 建独立任务分支实现quantity场景，保留Money路径；修改范围先完成该仓源码评审和具体计划，不能直接猜模块写入。
- S8-03 recon消费WMS快照、差异状态联动、审批修复再核对；先支持向后兼容契约，记录双方版本和发布顺序。
- S8-04 按 [Cursor交接](CURSOR_HANDOFF.md) 实现页面，后端种子和真实API联调；handoff prepared不等于UI accepted。
- S8-05 在获授权测试设备/协议环境验证设备回执丢失、查询和接管；没有环境保持该项blocked，模拟通过另列。

### S9 验证、迁移与发布准备

- S9-01 用签署的容量输入替换合成例子，运行分布/热点/故障/恢复矩阵，记录各Cell资源上限。
- S9-02 迁移演练：全量+增量追平、停写、校验、epoch切换、旧写拒绝及回退边界。
- S9-03 N/N-1事件/API/配置/schema兼容测试；生产开关和观察指标演练。
- S9-04 隔离恢复备份并对账，测实际RTO/RPO，补全操作手册。
- S9-05 汇总每AC实际证据、QA报告、代码评审、UI验收、CI和Git状态；必要项未过不宣布全交付。

- S9-06 将六项新故障验收纳入集成CI必选项，复核迁移后身份仍唯一及旧摘要版本重放；设备模拟和真实硬件证据分开记录。

## 6. 验证命令与 CI 设计

以下命令为实施 S0 必须创建/验证的目标入口，当前没有 pom/mvnw/scripts，不可报告已运行：

```bash
./mvnw -B -ntp verify
./mvnw -B -ntp -Pwarehouse-it verify
./mvnw -B -ntp -Pfailure-it verify
./scripts/seed-local.sh --profile isolated-wms
./scripts/verify-contracts.sh
./scripts/run-capacity.sh --scenario agreed-peak
```

`warehouse-it` 部署独立inbound/outbound/inventory及三套业务库，使用专属真实MySQL+ShardingSphere+Kafka+XXL+Seata TC，Docker不可用/测试数为0/关键用例skip应失败；`failure-it`只操作明确列出的本任务容器，不 kill 共享dev-infra。测试名称由实现补齐并在CI检查报告数量，不接受悄悄skip。

| CI门禁 | 执行内容 | 产物 |
| --- | --- | --- |
| PR快速验证 | 格式、编译、领域测试、依赖方向、OpenAPI/schema、DDL注释检查 | 单测/契约报告 |
| 集成验证 | 三服务/三库、分片/消息/XXL/Seata TCC、权限、T1/T2/T3故障、取消和迁移 | Failsafe报告、路由证据、故障日志 |
| UI验证 | Cursor项目锁文件安装、类型检查、构建、关键端到端 | 构建、截图/交互证据 |
| 夜间/按需 | 故障矩阵、容量、迁移、恢复 | 指标快照与逐AC证据 |
| 发布验证 | 版本锁、SBOM/漏洞审查、镜像构建、兼容性 | 可追溯制品；不自动授权生产部署 |

本次只写平台无关CI计划。OQ-08明确远程后选实际CI语法，不生成未经支持的GitHub Actions或GitLab流水线。

## 7. 变更与提交策略

实施授权后，每个可独立开发/测试的切片使用任务分支；同一切片按可审查逻辑单元提交，数据库迁移/实现/对应测试一起提交。跨切片按依赖合并，不混入Cursor或既有仓库无关改动。

按持续Git授权，在必要验证通过后正常推送任务分支并合并推送远程main；不强推、不绕过保护，生产部署另授权。recon扩展先发布兼容quantity能力，再启用WMS适配；跨仓库非原子发布，通过功能开关避免中间状态误接入。每仓库独立记录提交与CI证据。

## 8. 完成门禁

前期文档门禁已完成。当前实施门禁按切片执行：编译、真实库/组件验证、代码评审及必要CI通过后才推进依赖切片；生产部署仍不在范围内。

未来全项目门禁：全部必选AC有实现与验证证据；无未处理高风险问题；真实对账/UI/设备必选验收通过；CI与Git交付状态明确；容量和恢复目标签署且实测；运行手册对应最终行为。部分外部环境缺失时继续独立切片并记录具体阻塞，不能用“设计已写”替代验收。


## 9. 实施决策与退出门禁

| 门禁 | 截止阶段 | 必须交付的证据/决定 | 未满足时行为 |
| --- | --- | --- | --- |
| EG-01 工程与远程 | S0 | Maven可重复构建，任务分支，GitHub CI及正常main发布（远程已存在） | 本地通过不替代当前提交的远程CI |
| EG-02 唯一TM与技术组合 | S0 | TM归属决定、VERSION_LOCK、Fence同物理事务/路由、全局终态证据POC | TC组合未证明则阻塞依赖的跨仓实现，不能用Mock通过 |
| EG-03 身份与序列号业务规则 | S1/S3 | 认证约定=OIDC；序列号唯一范围=enterprise+SKU+serial。单位与效期规则（OQ-03）仍待确认 | 通用工程可继续；OQ-03 未确认前不把单位/效期默认值落成生产规则 |
| EG-04 首个完整闭环 | S5退出 | S5-06同批数据与故障恢复报告、适用AC结果 | 未通过不进入S6扩展 |
| EG-05 外部集成与非功能 | 对应S8/S9 | 对账/设备/UI证据、量化容量/SLO/RTO/RPO和实测 | 标明阻塞，不将模拟通过等同真实验收 |

v0.4幂等任务已并入各阶段。每项任务记录实现、验证、提交和剩余问题；全部业务AC默认planned，只有实际证据覆盖的子项才能更新。S0的测试夹具不等于生产库存或跨仓履约已实现。


## 2026-09-12 后端评审整改（用户已批准）

授权：先完成 R16–R20，再按 R01–R04 → R05–R12 → R13–R15/R21/R24 → R22–R23 连续实施、验证与 Git 交付。基线更新到 origin/main db02821，保留已合入的契约缺口改动。编号来自本轮评审，不替代原 50 AC。

| 顺序 | 编号 | 范围 | 状态 / 验收 |
| --- | --- | --- | --- |
| 1 | R16 | 稳定游标、有界列表、明确多仓语义 | implemented / local targeted regressions passed; combined CI pending；定向分页、权限过滤通过 |
| 1 | R17 | 有界连接池、超时、入口限流 | implemented / local targeted regressions passed; combined CI pending；真实MySQL连接预算和租户配额单测通过 |
| 1 | R18 | SQL 归入 Mapper XML | implemented / local targeted regressions passed; combined CI pending；XML加载/迁移/恢复定向通过 |
| 1 | R19 | 请求 DTO 与异常分类 | implemented / local targeted regressions passed; combined CI pending；DTO和400/409/503边界测试通过 |
| 1 | R20 | 适用读路径 L1/L2、回源预算、重试配置 | implemented / local targeted regressions passed; combined CI pending；真实Redis跨实例/TTL/断连/回源预算通过 |
| 2 | R01–R04 | 库存事务、操作权限、仓范围、可信执行授权 | implemented / local regressions passed；生产事务、79路由、仓范围、可信授权和现有HTTP回归通过；combined CI pending |
| 3 | R05–R08/R12 | 重试、分批身份、整单状态、行归属、建任务幂等 | implemented；本地定向回归通过，待组合CI |
| 3 | R09–R11 | 快照完整性/截点、JSON 与版本解析 | implemented；251桶历史分页及兼容回归通过；跨服务关闭证明边界见证据 |
| 4 | R13–R15 | 消息闭环、TC/序列号服务、恢复与清理任务 | in progress；R13库存投影真实MQ断连恢复通过，来源主链待补；R15三项实际handler通过、其余待接线；R14待实施 |
| 4 | R21/R24 | 就绪/观测、正式装配测试、容量执行器 | 核心已提交并本地验证；异步积压观测随R13补齐；容量目标未签署，执行器测试不等于WMS容量达标 |
| 5 | R22–R23 | UTC 序列化、操作者审计 | R23作为消息原始身份依赖提前实施并定向通过；R22历史DATETIME与UTC兼容仍待实施 |

实施规则：同批相关改动完整提交，先做首批；修改其他项仅允许为本批必要依赖并记录原因。TP99 沿用原契约目标，未实测路径标记 unverified。配置沿用首期环境变量方案，所有策略有类型/范围校验，不擅自新增配置中心。测试仅使用本地隔离目标，不修改共享 dev_infra。
