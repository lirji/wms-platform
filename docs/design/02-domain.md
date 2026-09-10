# 领域模型、业务规则与状态机

## 1. 决策状态

用户确认：自营、多仓、批次/序列号/效期能力、跨仓分配、库存不为负，第一版独立入库/出库/库存服务；跨仓预占采用Seata TCC，各仓确认并取得全局成功证据后才能执行。跨服务协议见[专项设计](08-service-boundaries-protocols.md)。

身份术语：operation_id指服务端持久化业务操作身份；PDA/上游使用clientOperationId或等值Idempotency-Key重试，服务端映射到同一operation_id。调拨接收额度在申请时绑定预先生成的targetClientOperationId，目标受理后记录对应operation_id。

序列号唯一范围已确认：`enterprise_id + sku_id + normalized_serial`。其余仍为可执行提案，实施相应功能前确认：首期局部冻结盘点；收货进入待验区、发运减少仓内实物；FIFO/FEFO 按 SKU 配置；跨仓调拨货权保持不变。

## 2. 库存数量与维度

库存桶唯一维度：`enterprise_id + warehouse_id + owner_id + location_id + sku_id + lot_id + quality_code`。无批次使用主数据定义的非空 sentinel `NO_LOT`，不能以 NULL 唯一键规避重复。容器为可选执行关联，首期不重复纳入余额维度；后续若以托盘管理独立余额必须迁移模型。

- `on_hand_qty`：当前桶的仓内登记实物数量，非负。
- `reserved_qty`：仍占用本桶实物的 TRIED、CONFIRMED 及已拣未发数量，`0 <= reserved_qty + free_execution_claim_qty <= on_hand_qty`，两类占用各自非负。
- `available_qty`：非序列号商品只有商品/库位可分配、质量 GOOD、效期满足订单、未被冻结时才是 `on_hand_qty - reserved_qty - free_execution_claim_qty`，否则为 0。序列号商品还必须逐个筛选登记授权有效、未预占且未被活动permit占用的serial，数量为该集合件数，不能把同桶中待登记的件数一起放行。是资格过滤后的计算值，不存独立可随意改写的“可用总数”。
- `free_execution_claim_qty`是未预占实物操作的活动执行占用；已预占拣发的inflight仅是reserved子集，不重复相减。
- 冻结、质量限制、效期是资格约束，不再作为另一个数量从余额机械相减，避免重复扣减。
- 在途按调拨明细维护，不计入任一仓 on_hand。企业维度守恒：期末仓内合计+在途 = 期初仓内合计+在途 + 外部收货 - 外部发运 + 有凭据调整；仓间发出/接收只是其中位置变化。

数量：数据库 `DECIMAL(20,6)`，API 十进制字符串，Java BigDecimal。SKU 规定基础单位与允许精度，数量必须恰好可换算，禁止截断凑数。序列号商品基础单位数量为整数，每一件绑定一个 serial_id；首期预占时明确 serial，避免后续重复分配。

批次关联生产日、失效时刻、供应商批号和主数据版本。效期有效区间为 `[produced_at, expires_at)`；源系统只给日期时按仓库 IANA 时区和已批准换算规则转 UTC，记录原日期和规则版本，禁止默认当日 00:00 导致一天差异。订单携带最小剩余效期天数/截止时点；拣货和发运再次校验。

## 3. 聚合与事务

| 聚合 | 规则/操作 | 事务提交内容 |
| --- | --- | --- |
| 库存桶 | 预占、释放、收货、移动、扣减 | 余额、预占明细、不可变库存流水、幂等、Outbox |
| 入库/出库执行单 | 来源服务单据/任务和实物事实 | 来源行累计量、source_execution、source_command、幂等、Outbox；不包含库存表 |
| 库存操作凭证 | 消费来源命令及执行授权 | inventory内余额、流水、stock_posting、claim/permit、幂等、Outbox |
| 仓级预占单 | 一个 attempt 在一仓的多 SKU 预占 | 头/行状态、所有相关桶数量、序列号关联 |
| 全局分配单 | 选仓、TCC发起、观察结果、授权 | attempt/XID映射、仓级结果、TC终态证据和业务Outbox；全局决定属于Seata TC |
| 盘点单 | 封锁、点数、复核、审批、调整 | 范围状态、盘点行、调整凭据、库存效果 |
| 序列号登记 | 身份声明、转移授权、归属版本 | 登记行、转移记录、幂等及登记服务 Outbox |

所有库存入口共用锁协议：在inventory内先锁相关库位门禁行，再锁stock_effect、stock_command/permit/预占头，再按稳定桶键锁余额，最后锁序列号本地记录；同类对象按 ID 排序。涉及多个门禁/单据先完整计算集合后排序。纯预占接口也遵循同一顺序。死锁检测后只对整个幂等用例有界重试，不在已部分提交的远程流程内重放实物动作。

门禁切换必须与写操作共享锁，不能“查一次冻结标志后再无锁扣减”。初始化空库存桶由数据库唯一键仲裁，插入冲突后重新读取，不能仅在 Java 中查不存在。

## 4. 收货与上架

收货单状态：`CREATED -> APPROVED -> RECEIVING -> RECEIVED -> PUTTING_AWAY -> COMPLETED`；允许明细部分执行；未发生实物执行的剩余量可取消。头状态由行状态汇总，不允许任意改头状态。

| 动作 | 前置条件 | 数量效果 | 失败/恢复 |
| --- | --- | --- | --- |
| 收货确认 | inbound核验单据并记录实物/命令；inventory核验库存资格 | inventory入待验库存；inbound收到回执后增加posted累计 | 同commandId恢复，不重复入账 |
| 序列号登记 | 身份合法、无冲突归属 | 先记录待登记收货意向，不生成可分配库存 | 登记冲突留待验异常，不能伪装正常收货 |
| 质检通过 | 实物已收、质量结果有效 | 质量桶从 HOLD 到 GOOD，写成对流水 | 未完成的质检任务不自动放行 |
| 上架确认 | 目的库位容量/存储属性合格，作业量合法 | 源桶减、目标桶加，总量不变 | inventory两桶与stock_posting同事务；inbound执行记录通过回执收敛 |

超收默认拒绝；允许超收须经审批配置和超收凭据。短收支持结束剩余收货并记录原因。退货重新质检，不沿用原发运时的“良品”状态。

质量与序列号登记是两个独立资格：质量结论由inbound的quality_inspection持有，inventory的quality_qualification按版本接收并控制quality_code，登记由local_serial授权状态/owner_epoch持有。质检通过仅改变质量桶，不能设置登记ACTIVE；登记激活仅设置授权，不把HOLD质量改为GOOD。二者顺序可交换，只有均满足且效期/门禁允许才可分配。重复或乱序确认不得覆盖另一个资格。

## 5. 跨仓预占 Seata TCC

跨仓预占明确采用Seata TCC，完整协议见[事务专项设计](09-seata-tcc.md)。fulfillment为TM，各仓inventory为RM，Seata Server为TC；不再由业务表自行决定COMMIT/ABORT。

仓级预占状态：`TRIED -> CONFIRMED -> CONSUMED`，或`TRIED -> CANCELLED`。全局提交后，未执行的剩余预占可经独立业务释放转RELEASED；部分消费/释放以明细数量累计，全部结清才进入终态，不能对已消费或STARTED未核验数量重复释放。Try原子校验并增加reserved；Confirm只确认已占资源、不重复加量；Cancel原子释放本分支占用。Fence与预占/余额/流水/Outbox同物理本地事务，空回滚、防悬挂和重复调用必须真实验证。

fulfillment创建固定参与者attempt，持久化XID映射后调用各仓Try，由TC决定/驱动二阶段。TC提交完成的可靠证据与全部仓CONFIRMED同时满足后，fulfillment本地事务记录ALLOCATED并写outbound建单/执行授权Outbox；任一步丢响应进入RECOVERY_PENDING查询恢复。

Try占用不由TTL/XXL释放。Confirm不重新按当前效期/质量竞争库存，后续执行授权仍按当前业务规则拒绝过期/冻结商品。TC失败时保留资源等待框架恢复；已提交后的用户取消走新业务取消/补偿，不调用原TCC Cancel。未知旧attempt未结案不得生成新XID重复占用。

TCC仅覆盖短时跨仓库存资源预留，不包含设备、拣货、发运或长时调拨。原execution_permit的PREPARED/STARTED是实物协议，与TCC的TRIED状态不同。

## 6. 拣货、复核与发运

仓级出库状态：`CREATED -> ALLOCATED -> PICKING -> PICKED -> PACKING -> READY_TO_SHIP -> SHIPPED`。各步骤支持明细部分完成；发运数量不超过复核/预占剩余量。每行另有physicalQty/postedQty及PENDING/APPLIED/EXCEPTION同步状态，细节见08；后续依赖动作等待前一步过账。

- 拣货将存储位 on_hand 与对应 reserved 同量转到集货位，预占行更新桶关联；单仓同事务完成。拣货不减少全仓实物。
- outbound先记录实物交接与库存命令；inventory按STARTED permit原子减少on_hand/reserved、消耗claim、写stock_posting/流水/Outbox并更新serial。outbound消费回执另事务增加shippedPostedQty。不能宣称跨两库原子更新单据与库存。
- 短拣：记录实拣及缺货原因，保留未完成量等待重分配/审批取消，不自动编造盘亏。
- 已拣未发取消：生成回库任务，完成实物回库后释放相应占用。
- 已发部分通过退货/逆向流程处理，不能把 SHIPPED 改回 CREATED 后加库存。
- 人工与设备执行使用同一业务入口和幂等约束；设备 ACK 不等于库存已过账。

## 7. 序列号唯一性与跨仓转移

已确认唯一键：`enterprise_id + sku_id + normalized_serial`。正规化规则由商品版本确定，首期保留大小写，不随意去除内部空格；使用二进制比较。企业全局唯一（跨 SKU）不在本决定内。

登记权威状态：`CLAIMED -> ACTIVE -> TRANSFER_PREPARED -> IN_TRANSIT -> RECEIVING -> ACTIVE`；外发为 SHIPPED，报废为 SCRAPPED，退货通过带原发运引用的 RETURN_CLAIMED 再进入 ACTIVE。每次归属授权都有递增 owner_epoch；状态变更含 transfer_id/receipt_operation_id。

首次收货：inbound持久化意向，inventory协调登记服务 CAS 创建 CLAIMED，绑定仓和操作；inventory本地写 HOLD 库存及 serial 接收记录；登记服务核实收货事实激活授权；仓端收到/查询该授权后才转可用。取消 CLAIMED 必须确认本地没有已生效库存，不能靠 TTL 把序列号授予另仓。

转移协议：

1. 登记服务校验源仓 owner_epoch，固定 transfer_id 与目的仓，进入 TRANSFER_PREPARED。
2. 源仓锁本地 serial 和库存，校验无不允许的预占，原子发出、减少 on_hand，并将本地授权 SEALED；写 source-release 事件。
3. 登记服务根据已持久化的源仓释放事实进入 IN_TRANSIT，签发绑定目的仓/transfer_id/新 epoch 的接收资格。
4. 目的仓inbound先记录收货事实与命令；inventory消费后在本地事务写HOLD库存、stock_posting及serial；登记服务核实库存凭证后切换ACTIVE owner，inventory才放行分配，inbound另收回执。
5. 任一步响应丢失按 operation_id 查询，不能同时授予另一仓。源仓 SEALED 后即使收到旧授权消息也不恢复可用；需匹配更新的逆向转移。

owner_epoch 单独不能阻止源仓旧进程操作；必须有源仓事务内 SEALED 检查和持久化释放确认。登记服务没有收到释放事实前不能授予目的仓，即使租约/网络超时。

序列号与桶量一致：每个可分配序列号最多关联一个有效桶和预占；序列号商品桶 on_hand 等于同状态本地有效 serial 数。批次+序列号组合需同时核对。首次登记、退货、报废均复用登记协议，不能只处理调拨路径。

## 8. 调拨、盘点与调整

调拨状态：`CREATED -> APPROVED -> SHIPPING -> IN_TRANSIT -> RECEIVING -> COMPLETED`，差异转 DISCREPANCY_OPEN。全局调拨行分别维护 issued/received/loss_confirmed，`received + loss_confirmed <= issued`。源仓发出与目的仓接收独立事务；在途是按调拨事实生成的权威过程记录，单个事件重复不得累计两次。

非序列号商品也必须有接收额度权威。全局调拨模块根据去重后的源仓发出事实增加issued；目的仓每次接收前请求 `receipt_authorization`，全局事务预留quota并发出绑定transferLine/目的仓/operation/数量的唯一token。约束为 `received + loss_confirmed + active_receipt_quota <= issued`。目的仓inventory本地事务锁token消费记录，实际入账量必须等于token数量；需要部分接收先申请较小token，不部分消费同一token。入账与token消费、流水、Outbox同事务。

全局收到接收事实后原子将active_quota转received；报损与授权申请竞争同一调拨行额度。token响应/接收事实丢失时查询本地操作恢复；全局不能仅因token到期回收未知额度，否则目的仓迟到入账会超收。撤销必须由目的仓持久化取消未消费token并确认，之后释放额度。物理已到但授权不可用时记录待接收意向并隔离，不能直接入正常库存。序列号商品同时要求数量token和匹配serial/epoch资格，任一缺失不放行。

批次跨仓使用稳定 `business_lot_key`（企业/货主/SKU/来源批号及规范版本），保留源lot_id和目的lot_id映射。目的仓新建自身批次行并核对生产/效期/规则版本；不能把源仓内部lot_id直接当目的仓外键。跨仓对账比较business_lot_key和效期语义。

盘点首期范围为明确库位集合：`DRAFT -> QUIESCING -> FROZEN -> COUNTING -> REVIEWING -> APPROVED -> APPLYING -> COMPLETED`。

- inventory的QUIESCING拒绝新permit开始，inbound/outbound分别停止新派工，按08的执行授权协议排空；未知动作/待过账未清零前不得FROZEN。
- FROZEN 与库存入口共享门禁锁，冻结后生成版本化快照。扫描计数记录独立 observation_id，复盘不覆盖原记录。
- 盘亏若导致 reserved > 新 on_hand，先进入 RESERVATION_CONFLICT，完成重分配/取消再审批调整。
- 调整逐行有界提交，业务效果与检查点同事务；中途失败可恢复。已提交行不会因取消剩余任务而撤销。
- 调整不得跨 SKU、单位抵销差异；必须有原因、审批者、操作人和不可变流水。

冻结门禁允许矩阵：

| 命令 | OPEN | QUIESCING | FROZEN |
| --- | --- | --- | --- |
| 新预占/派工/普通入出库/移位 | 按权限和业务条件 | 拒绝；物理已开始任务走已登记排空流程 | 拒绝 |
| 已登记在途作业确认 | 正常条件 | inventory校验既有STARTED permit与来源事实，仅完成排空 | 理应无在途；若可信迟到事实出现，隔离并作异常记账/废弃受影响快照，不丢弃事实 |
| TC驱动TCC Cancel | 按Fence/分支规则 | 允许释放TRIED，不能改实物量 | 允许，仍锁同一门禁/预占记录 |
| TRIED/CONFIRMED任意释放 | 拒绝 | 拒绝 | TRIED只接受TC Cancel；CONFIRMED走新业务取消协议 |
| 点数/复盘 | 仅准备操作 | 不作为正式冻结快照 | 匹配countPlanId/gateEpoch可记录 |
| 审批盘点调整 | 拒绝绕开盘点 | 拒绝 | 仅匹配countPlanId+gateEpoch+approvalId的专用命令 |
| 解冻 | 无操作 | 可受控取消冻结准备 | 全部调整终态、无未知物理动作、核对通过后CAS解冻 |

盘点调整部分失败保持FROZEN，恢复未完成分片；不因job结束就解冻。非盘点stock_hold可重叠，解冻一个count_plan不能清掉其他质量/召回限制。门禁迁移/维护状态拒绝业务写，仅允许对应迁移令牌的维护操作。

序列号盘点每轮持久化观察身份集合。调整行必须包含新增/缺失serial明细，deltaQty等于身份集合净变化，禁止仅录数量。盘盈身份先经登记服务FOUND_CLAIMED或首次CLAIMED核查；身份冲突时隔离，未确认前不新增可分配量。盘亏先处理相关预占，在本地调整事务扣量并将serial封存为MISSING_PENDING，不再能操作；登记服务收到事实后标为MISSING且禁止再授权，确认后本地终态MISSING。重现时走获批准的found登记流程，不自行恢复原授权。调整任务需等待登记收敛及账实核对后才完成/解冻。

## 9. 通用不变量与待验收

所有状态使用显式稳定字符串 code，不用 enum ordinal。非法状态迁移返回冲突；未知新状态不得默认为成功。每个变更校验版本及影响行数，审计 source_document、operation_id、actor、reason。

完整验收编号见 [实施计划](../delivery/wms-v1/DELIVERY_PLAN.md)，重点覆盖并发预占、数量守恒、重复扫描、TCC二阶段故障恢复、序列号两仓冲突和冻结期间实物/系统一致性。

## v0.4幂等补充

业务效果与授权执行尝试分离：效果行控制最多一个活动尝试和一次有效过账；旧尝试失败结果保留，安全重授权、稳定事实身份、TCC分支所有者和XID启动CAS遵循[幂等专项](10-idempotency-protocols.md)。本节不改变部分执行和逆向数量不变量。
