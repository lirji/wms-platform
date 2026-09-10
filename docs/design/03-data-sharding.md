# 数据库、索引、分库分表设计

## 1. 命名与通用字段

以下是建模规范和迁移输入，不是已执行 DDL。物理引擎 MySQL 8.4 InnoDB，字符集 utf8mb4；业务标识使用二进制比较，名称使用选定的非二进制排序规则。JSON 仅承载版本化事件/请求快照，查询和约束字段必须独立列。

字段缩写：ID=`VARCHAR(64)`、CODE=`VARCHAR(32)`、QTY=`DECIMAL(20,6)`、TS=`DATETIME(6)` UTC、VER=`BIGINT`、TEXT=`VARCHAR(512)`。除显式 `?` 可空外均 NOT NULL。ID 对外为不透明字符串；全局资源/operation/serial标识采用 `domain_logicalBucket_uuidv7` 提案（总长<=64），UUIDv7生成器在 S0 验证，数据库唯一约束仍为最后仲裁。logicalBucket为稳定逻辑桶，不编码物理库地址；服务端解析后查目录，权限独立校验，客户端不能修改ID获得跨桶权限。

模板 C：`id ID` 主键；`enterprise_id ID` 企业权限范围；`created_at TS` 创建时间；`updated_at TS` 最近变更时间；`version VER` 并发版本。仓级模板 W=C+`warehouse_id ID`。不可变流水/事件保留 created_at，无须伪造可编辑 updated_at。模板字段与下表所有业务字段必须写中文 COMMENT，所有表必须写表 COMMENT。

引用关系同物理库可采用外键，但须验证 ShardingSphere 实际物理表支持；跨库引用通过领域契约校验。删除用状态/受控归档，库存账与流水不做普通逻辑删除。手机号、地址等履约敏感数据不进入库存流水，仅引用受控地址记录。

## 2. 表所有权与数据字典

v0.4中的W仅表示仓级路由模板，不代表共库。按以下所有权分别生成迁移/账号，禁止跨服务SQL、JOIN、外键或写表：

| 服务 | 独占业务表 |
| --- | --- |
| inbound | inbound_order/line、quality_inspection、source_effect、source_execution、source_command、上架/收货work_task |
| outbound | outbound_order/line、outbound_package、package_line/serial、source_effect、source_execution、source_command、拣发work_task |
| inventory | warehouse/location/gate、sku/unit/lot、stock_balance/ledger、reservation/line、local_serial、count/adjustment/hold各表、receipt_token_consumption、stock_effect、stock_command/posting、execution_permit/claim、quality_qualification |
| fulfillment | fulfillment/attempt/participant、transfer_order/line、receipt_authorization |
| serial-registry | serial_registry/transfer |
| integration | device_command、外部消息映射 |
| query | 各view、checkpoint、导出snapshot/part |

command_dedup/outbox/inbox/job_run/job_shard/reconciliation_case按服务分别建立，仅记录该服务的数据和进度；库存内部盘点/移库work_task在inventory，入出库任务在各自服务，是分属不同业务类型的独立表实例。warehouse_route是控制平面权威，服务使用只读版本快照。

旧模型execution_record改名source_execution，只保存来源实物事实；stock_posting是inventory独立的库存凭证。两者按commandId关联、不同事务。表内引用指向其他服务时只保存不可变ID/版本，不生成跨库外键。

### 2.1 主数据与仓储执行字段

| 表 / 模板 | 业务字段（字段名 类型：含义） | 约束与主要索引 |
| --- | --- | --- |
| warehouse_route / C | warehouse_id ID；cell_id ID；route_epoch VER；state CODE；target_cell_id ID? | UQ(enterprise,warehouse)；路由版本 CAS；仅控制平面可写 |
| warehouse / W | code CODE；name TEXT；timezone VARCHAR(64)；state CODE | UQ(enterprise,code)；时区必须合法 IANA |
| location / W | code CODE；zone_code CODE；location_type CODE；capacity_qty QTY?；capacity_unit CODE?；state CODE | UQ(enterprise,warehouse,code)；容量单位成对且兼容 |
| location_gate / W | location_id ID；state CODE；reason_code CODE?；fence_epoch VER；count_plan_id ID? | UQ(enterprise,warehouse,location)；所有库存写事务锁此行 |
| sku / C | code ID；name TEXT；base_unit CODE；quantity_scale INT；lot_enabled BOOLEAN；serial_enabled BOOLEAN；expiry_enabled BOOLEAN；policy_version VER；state CODE | UQ(enterprise,code)；scale 0..6；serial 要求整数基础单位 |
| sku_unit / C | sku_id ID；unit_code CODE；numerator DECIMAL(20,0)；denominator DECIMAL(20,0)；policy_version VER | UQ(enterprise,sku,unit,policy_version)；正有理数换算，结果精度校验 |
| lot / W | owner_id ID；sku_id ID；lot_code ID；business_lot_key ID；produced_at TS?；expires_at TS?；source_date VARCHAR(32)?；expiry_rule_version VER | UQ(enterprise,warehouse,owner,sku,lot_code)；跨仓按business_lot_key映射；有生产时间时 expiry>production |
| stock_balance / W | owner_id ID；location_id ID；sku_id ID；lot_id ID；quality_code CODE；on_hand_qty QTY；reserved_qty QTY；free_execution_claim_qty QTY | 维度唯一；on_hand>=reserved+free_execution_claim，三者非负；分配索引见后文 |
| stock_ledger / W | operation_id ID；entry_no INT；balance_id ID；on_hand_delta QTY；reserved_delta QTY；on_hand_after QTY；reserved_after QTY；balance_version VER；reason_code CODE；document_id ID；actor_id ID；occurred_at TS | UQ(enterprise,warehouse,operation,entry_no)；UQ(enterprise,warehouse,balance,balance_version)；不可变 |
| reservation / W | allocation_id ID；attempt_id ID；request_digest CHAR(64)；state CODE；xid VARCHAR(128)；branch_id BIGINT；action_name ID；route_epoch VER；execution_authorization_id ID? | UQ(enterprise,warehouse,allocation,attempt)及UQ(enterprise,warehouse,xid,branch_id,action_name)；所有者不可改绑；IDX(warehouse,state,updated_at,id)；XID长度最终沿用选定Seata官方schema |
| reservation_line / W | reservation_id ID；order_line_id ID；balance_id ID；requested_qty QTY；remaining_qty QTY；picked_qty QTY；consumed_qty QTY；released_qty QTY；inflight_qty QTY | inflight是remaining的子集，不重复计reserved；remaining>=0；requested=remaining+consumed+released；picked 是 remaining 子集，不能再次相加 |
| inbound_order / W | external_source CODE；external_no ID；status CODE；owner_id ID；expected_at TS?；source_version VER | UQ(enterprise,warehouse,external_source,external_no) |
| inbound_line / W | order_id ID；external_line_id ID；sku_id ID；expected_qty QTY；received_qty QTY；putaway_qty QTY；closed_qty QTY；base_unit CODE | UQ(warehouse,order,external_line)；累计量非负；超收按批准策略 |
| outbound_order / W | allocation_id ID；attempt_id ID；status CODE；execution_authorization_id ID?；owner_id ID | UQ(enterprise,warehouse,allocation,attempt)；授权前允许空，进入PICKING前必须非空且有效 |
| outbound_line / W | order_id ID；order_line_id ID；sku_id ID；allocated_qty QTY；picked_qty QTY；packed_qty QTY；shipped_qty QTY；cancelled_qty QTY | 阶段数量是包含关系，0<=shipped<=packed<=picked；累计取消结合阶段检查 |
| work_task / W | task_type CODE；document_id ID；document_line_id ID；source_location_id ID?；target_location_id ID?；planned_qty QTY；completed_qty QTY；state CODE；assignee_id ID?；claim_epoch VER | IDX(warehouse,state,task_type,id)；领取 epoch 防旧执行器回写 |
| source_execution / W | task_id ID；operation_id ID；action CODE；qty QTY；base_unit CODE；device_id ID?；scan_session_id ID?；actor_id ID；executed_at TS | UQ(enterprise,warehouse,operation,action)；业务已执行事实，不因任务取消而删 |
| local_serial / W | serial_id ID；sku_id ID；lot_id ID；balance_id ID?；state CODE；owner_epoch VER；transfer_id ID?；reservation_line_id ID?；receipt_operation_id ID? | UQ(enterprise,warehouse,serial)；有效库存状态对应一个桶；SEALED 不接受旧 epoch |
| count_plan / W | scope_version VER；status CODE；reason CODE；approved_by ID?；approved_at TS? | 门禁范围由 count_scope 维护；审批权限与操作人分离 |
| count_scope / W | count_plan_id ID；location_id ID；gate_epoch VER | UQ(warehouse,count_plan,location)；同库位只能有一个有效冻结拥有者，由 gate 仲裁 |
| count_line / W | plan_id ID；balance_id ID；snapshot_version VER；snapshot_qty QTY；counted_qty QTY?；status CODE | UQ(warehouse,plan,balance)；快照不可被第二次扫描覆盖 |
| count_observation / W | count_line_id ID；observation_id ID；qty QTY；actor_id ID；round_no INT | UQ(warehouse,observation)；多轮点数独立保存 |
| count_observation_serial / W | observation_id ID；serial_id ID?；normalized_serial VARCHAR(128)；presence_code CODE | UQ(warehouse,observation,normalized_serial)；未知身份可空serial_id，原扫描不可覆盖 |
| adjustment / W | plan_id ID?；balance_id ID；delta_qty QTY；reason CODE；state CODE；approved_by ID?；applied_operation_id ID? | 每个 applied_operation 唯一；调整前保证新余额>=reserved |
| adjustment_serial / W | adjustment_id ID；serial_id ID；action CODE；registry_operation_id ID；registry_state CODE | UQ(warehouse,adjustment,serial)；FOUND/MISSING身份动作；净件数等于调整量 |
| quality_inspection / W | inbound_line_id ID；balance_id ID；inspected_qty QTY；accepted_qty QTY；rejected_qty QTY；result_code CODE；actor_id ID；evidence_refs JSON | accepted+rejected=inspected；同一收货批次可多次检验，每次独立操作键 |
| stock_hold / W | reason_code CODE；state CODE；requested_by ID；released_by ID?；evidence_refs JSON | 创建/释放审计；质量限制和盘点门禁分别记录原因 |
| stock_hold_scope / W | hold_id ID；location_id ID；sku_id ID?；lot_id ID?；scope_version VER | 同库位门禁锁下判断scope；可重叠多个hold，释放一个不清除其他限制 |
| outbound_package / W | order_id ID；package_no ID；state CODE；weight DECIMAL(20,6)?；weight_unit CODE?；carrier_ref ID? | UQ(enterprise,warehouse,package_no)；重量与单位成对 |
| package_line / W | package_id ID；outbound_line_id ID；reservation_line_id ID；qty QTY | UQ(package,reservation_line)；装箱量不得超过对应已拣未装量 |
| package_serial / W | package_id ID；serial_id ID；state CODE | outbound内使用serial_package_binding(enterprise,warehouse,serial_id)主键保存唯一当前包裹，装箱/拆箱同事务仲裁；inventory不写包裹表 |

余额分配索引建议 `(enterprise_id,warehouse_id,owner_id,sku_id,quality_code,lot_id,location_id)`；批次表按 `(warehouse_id,sku_id,expires_at,id)` 筛候选。FEFO 排序最后加稳定 lot_id/location_id；限制候选桶数量，不足时分页续查或进入异步规划，不能一次锁全仓。索引是否覆盖以真实 SQL EXPLAIN 验证。

补充字段：outbound的serial_package_binding含current_package_id及version；inventory的local_serial只保存活动permit/claim标识，不持有包裹权威；reservation_line 增加 `parent_line_id ID?` 表示桶拆分血缘。部分拣货使一行分属源/目标桶时，事务内拆分仓级分配明细：本次实拣q必须<=源行remaining-picked，从源行requested/remaining各减q，目标行requested/remaining/picked各增q；已consumed/released保留原历史行，不按比例搬迁。所选serial关联原子迁到目标明细，序列号数量只能为整数。保留原请求快照、父行和流水；requested_qty在桶明细上表示当前分配份额，全单原始请求保存在履约行中。不能把整行balance_id直接改到目标桶而遗留未拣数量。各桶明细汇总的requested、remaining、consumed、released必须与仓级单一致；发运另按动作减少remaining/picked并增加consumed，不能混入拆行动作。

## 3. 全局服务和技术表字典

| 表 / 模板 | 业务字段 | 约束与归属 |
| --- | --- | --- |
| fulfillment_order / C | source_system CODE；source_order_no ID；request_digest CHAR(64)；status CODE；strategy_version VER；active_attempt_id ID? | 全局履约；UQ(enterprise,source,source_order) |
| fulfillment_line / C | fulfillment_id ID；source_line_id ID；sku_id ID；requested_qty QTY；base_unit CODE；min_remaining_days INT | UQ(enterprise,fulfillment,source_line)；不存仓内余额 |
| allocation_attempt / C | fulfillment_id ID；state CODE；deadline TS；xid VARCHAR(128)?；tc_observed_status CODE?；tc_terminal_evidence JSON?；participant_set_hash CHAR(64)；cancel_requested BOOLEAN | xid绑定后不可覆盖；CAS见第10节；TC状态仅观察副本，不自行改全局决定；参与者固定 |
| allocation_participant / C | attempt_id ID；warehouse_id ID；reservation_id ID?；state CODE；last_error CODE?；next_retry_at TS?；confirmed_version VER? | UQ(enterprise,attempt,warehouse)；行明细在 participant_line |
| participant_line / C | participant_id ID；order_line_id ID；sku_id ID；qty QTY；base_unit CODE | UQ(participant,order_line)；总分配量不超过请求 |
| transfer_order / C | source_warehouse_id ID；target_warehouse_id ID；status CODE；source_document_id ID?；target_document_id ID? | 调拨协调模块；发出/接收事实幂等汇总 |
| transfer_line / C | transfer_id ID；sku_id ID；business_lot_key ID；source_lot_id ID；target_lot_id ID?；planned_qty QTY；issued_qty QTY；received_qty QTY；loss_confirmed_qty QTY；active_receipt_quota QTY | received+loss+active_receipt_quota<=issued；loss与授权共享行锁；保留批次映射 |
| receipt_authorization / C | transfer_line_id ID；target_warehouse_id ID；target_client_operation_id ID；quantity QTY；state CODE；token_version VER；target_result_ref ID? | UQ(enterprise,transfer_line,target_client_operation)；目标客户端身份在申请前生成；与transfer_line同库；未知结果不自动回收 |
| receipt_token_consumption / W | authorization_id ID；token_version VER；operation_id ID；quantity QTY；state CODE | UQ(enterprise,warehouse,authorization)；CONSUMED/CANCELLED互斥，入账同事务 |
| serial_registry / C | sku_id ID；normalized_serial VARCHAR(128)；state CODE；owner_warehouse_id ID?；owner_epoch VER；transfer_id ID?；claim_operation_id ID | 按稳定序列号键分片；逻辑身份 UQ(enterprise,sku,normalized_serial) 必须落同片 |
| serial_transfer / C | serial_id ID；transfer_id ID；source_warehouse_id ID?；target_warehouse_id ID?；from_epoch VER；to_epoch VER；state CODE；source_release_ref ID?；target_receipt_ref ID? | 与 serial_registry 同片；UQ(serial,transfer)；登记权威审计 |
| command_dedup / W或C | source_system CODE；action CODE；client_operation_id ID；operation_id ID；request_digest CHAR(64)；status CODE；response_json JSON?；retain_until TS | 仓级 UQ(enterprise,warehouse,source,action,client_operation)；operation_id为唯一服务端身份；全局版本不含 warehouse |
| outbox_event / W或C | event_id ID；aggregate_type CODE；aggregate_id ID；aggregate_version VER；event_type ID；payload JSON；status CODE；claim_epoch VER；lease_until TS?；next_attempt_at TS；published_at TS? | event_id 唯一；IDX(status,next_attempt_at,id)；与业务同片同事务 |
| inbox_event / W或C | consumer_name ID；event_id ID；payload_digest CHAR(64)；handled_at TS | UQ(consumer,event_id)；与本地业务效果同事务 |
| job_run / W或C | job_type CODE；business_window ID；scope_id ID；status CODE；input_version VER；expected_shards INT；completed_shards INT | UQ(enterprise,job_type,business_window,scope_id,input_version) |
| job_shard / W或C | run_id ID；shard_key ID；cursor_json JSON?；state CODE；lease_owner ID?；lease_until TS?；claim_epoch VER；retry_count INT；next_attempt_at TS | UQ(run,shard_key)；副作用与检查点尽量同库；跨库调用用操作幂等 |
| device_command / C | warehouse_id ID；device_id ID；command_id ID；business_operation_id ID；state CODE；vendor_ref ID?；last_query_at TS?；attempt_count INT；result_json JSON? | Integration 拥有；UQ(enterprise,device,command)；发送次数不等于业务执行次数 |
| reconciliation_case / W或C | external_case_id ID?；case_type CODE；scope_id ID；cutoff_id ID；state CODE；evidence_ref TEXT；remediation_operation_id ID?；approved_by ID? | WMS 内部差异/外部映射；修复走业务入口；原证据不覆盖 |
| projection_checkpoint / C | projection_name ID；source_partition ID；source_offset ID；last_event_time TS | 投影库；分区检查点与批内投影同事务 |
| reconciliation_snapshot / C | scenario_code CODE；cutoff_id ID；scope_json JSON；source_watermarks JSON；schema_version INT；state CODE；manifest_ref TEXT?；completed_at TS? | UQ(enterprise,scenario,cutoff,scope摘要)并校验完整scope；成功后内容不可变 |
| snapshot_part / C | snapshot_id ID；part_no INT；object_ref TEXT；row_count BIGINT；sha256 CHAR(64)；state CODE | UQ(snapshot,part_no)；所有计划分片成功后才发布manifest |

所有表的 UQ 实施时包含对应完整企业/仓作用域，表中为可读性缩写 enterprise/warehouse/sku 等。W 与 C 的两种技术表分别生成迁移，不把可空 warehouse_id 混入一个唯一键。

查询投影最小表：`inventory_view`（完整库存桶维度、on_hand/reserved/free_execution_claim、source_version、as_of、资格标记）；`execution_order_view`（企业/仓/单据/状态/累计量/source_version）；`serial_trace_view`（serial_id、source_event_id、epoch、动作和来源引用）；它们均属于query库且可重建。投影资格标记用于展示，实时库存命令仍查权威状态。每表使用源聚合键唯一约束，不能用写库实体直接充当公开DTO。

## 4. 核心 DDL 示意

此段是迁移编写参考，不是可直接部署的全库脚本。省略的表按上述模板和字典在对应实施切片生成；迁移按 Cell/global/registry/query 分组，每个物理分片均执行并登记版本。

```sql
CREATE TABLE stock_balance (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '库存桶标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  owner_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '货权主体',
  location_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '实物库位',
  sku_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '商品标识',
  lot_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '批次标识，无批次使用固定非空标识',
  quality_code VARCHAR(32) NOT NULL COMMENT '质量状态稳定编码',
  on_hand_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '仓内登记实物量',
  reserved_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '仍占用当前桶的预占量',
  free_execution_claim_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '未预占实物动作的执行占用量',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '余额并发及流水版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_dimension
    (enterprise_id,warehouse_id,owner_id,location_id,sku_id,lot_id,quality_code),
  KEY idx_stock_allocate
    (enterprise_id,warehouse_id,owner_id,sku_id,quality_code,lot_id,location_id),
  CONSTRAINT ck_stock_quantity CHECK
    (on_hand_qty >= 0 AND reserved_qty >= 0 AND free_execution_claim_qty >= 0
     AND reserved_qty + free_execution_claim_qty <= on_hand_qty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓级权威库存余额，禁止跨业务直接改写';
```

注意 InnoDB 索引字节长度：上述复合键须在真实编码、实际字段长度及目标版本下验证。若标识长度超出索引预算，优先缩短内部二进制 ID、使用内部整数引用；不得仅以不可碰撞假设替代完整业务唯一性校验。

库存条件更新示意（必须在已持有门禁和业务状态锁的事务内）：

```sql
UPDATE stock_balance
SET reserved_qty = reserved_qty + :qty,
    version = version + 1,
    updated_at = :now
WHERE enterprise_id = :enterpriseId
  AND warehouse_id = :warehouseId
  AND id = :balanceId
  AND version = :expectedVersion
  AND quality_code = 'GOOD'
  AND on_hand_qty - reserved_qty - free_execution_claim_qty >= :qty;
```

`:qty > 0`、效期、门禁、序列号授权、库存执行资格由inventory同事务内读取/锁定的permit及授权快照校验，来源订单规则由所属服务事务校验；上段 SQL 不是完整预占算法。影响 0 行是库存不足或版本冲突，不能当成功。量、流水、预占和 Outbox 任一失败回滚整个本地事务。

## 5. 分片算法与连接边界

1. 控制平面维护 `warehouse -> cell` 及版本；Cell 内维护 `warehouse -> physical datasource` 的稳定映射。
2. ShardingSphere-JDBC 的自定义数据库路由算法读取不可变映射快照。快照更新先校验再原子替换，在途请求固定 route_epoch。路由配置只允许受控发布，不能应用自行修改。
3. 首期同仓inventory所属W表位于同一MySQL schema、同一物理数据源，通过一个事务管理器和连接提交。inbound/outbound各自W表分别在自己的数据库，单据不参加库存事务。不能因两个 schema 恰好在同一实例就假定 ShardingSphere 两个 datasource 是同一个事务资源。
4. 初期可每仓一组逻辑表共享库；需要分表时使用固定逻辑桶映射，不随执行器数量或数据库数量变化。余额/明细使用一致的仓路由；流水可按时间分表但同一仓事务仍在同物理数据源。
5. 缺少 warehouse_id 的仓级写请求在应用和 SQL 路由审计中拒绝；允许的跨分片后台读取必须有显式任务、分片清单和预算。
6. 单仓跨 SKU 分到多个物理 datasource 会改变多 SKU 原子范围，v0.3 不采用。热仓先独享 Cell/数据库，再依据测量评估进一步拆分并更新协议。
7. 全局履约按 enterprise+外部订单稳定逻辑桶分片；序列号按 enterprise+SKU+normalized serial 稳定桶分片。不同域不共享业务数据库写权限。
8. 不启用任意透明广播写和无界 SQL Federation。跨仓聚合进入查询投影；主从复制延迟下库存判断强制写库连接。

连接预算：每 Cell 的应用实例数×每数据源 pool 上限×可达数据源数，加后台/迁移/运维连接，必须低于数据库预算并留恢复余量。新增实例可能先耗尽连接，不能仅按 CPU 自动扩容。

按资源ID查询使用ID内稳定logicalBucket定位，再查物理路由；创建时先按来源业务键计算桶，再生成同桶ID。operationId由受理服务生成并携带目标域/桶，调用方生成clientOperationId并以相同值传Idempotency-Key；command_dedup持久化二者映射。响应丢失用原客户端键重试获取同一服务端operationId，不要求客户端事先知道服务器ID。调拨额度绑定申请者提前生成的targetClientOperationId，目标仓接收时映射为自己的operationId，接收事实同时携带两者和authorizationId。序列号只提供原始条码时按规范化身份算桶，已有serialId直接定位同桶。重新分片只迁移逻辑桶映射，不改公开ID；非法ID拒绝，不回退全国广播查找。

## 6. 仓库迁移流程

v0.3 采用短暂停写迁移提案：准备目标库和兼容 schema → 全量拷贝 → 增量追平 → 源仓进入 QUIESCING → 排空物理任务/在途写事务并排空三服务未闭环命令/permit、封锁各服务写入口 → 记录各库高水位向量 → 校验余额、流水、预占、序列号、任务检查点和未发送 Outbox → 一致性校验三服务路由向量后切路由 epoch → 目标解锁 → 观察。

源库旧实例在门禁/路由写令牌处拒绝旧 epoch；仅更新路由缓存不能阻止旧实例写源库。目标接管的 Outbox 保留 event_id，消费者去重。目标开始接受写入后，回退需要反向同步及再次停写校验，不能直接切回旧库。

迁移验收同时检查行数、逐桶校验和、未完成业务状态及后续消息恢复。首期业务无法接受停写窗口时，另行设计双路由迁移协议并做完整演练；不在文档中承诺无损在线扩容。

## 7. 数据生命周期

活跃预占、未决TCC会话/Fence及TRIED占用、未完成调拨与任务不按创建时间简单清理。幂等身份覆盖最大重试/离线重放/消息重放窗口；大响应可到期归档，但关键业务唯一操作引用或去重墓碑保留至业务生命周期结束。

历史流水按批准保留期归档到受控对象存储/分析存储，核对 manifest、条数、checksum 后才允许另行授权清理。文档不编造统一法定保留期限。恢复备份后须重建投影、应用已执行删除/归档清单并验证旧操作不会重新入账。

## 8. 三服务新增协议表和字段

| 表 / 所有者 | 字段 | 约束 |
| --- | --- | --- |
| source_command / inbound或outbound | W模板；command_id ID；source_operation_id ID；source_execution_id ID?；business_effect_key ID；action CODE；payload_digest CHAR(64)；payload_json JSON；state CODE；inventory_operation_id ID?；posting_id ID?；retry_at TS；compensates_command_id ID? | UQ(enterprise,warehouse,command_id)及UQ(enterprise,warehouse,business_effect_key,action,attempt_no)；effect行仲裁唯一活动尝试；冻结本次请求正文 |
| source_execution / inbound或outbound | 原字段+physical_status CODE；stock_sync_status CODE；physical_qty QTY；posted_qty QTY；command_id ID；permit_id ID?；device_command_id ID? | 原实物事实不可覆盖；回执与posted累计同本地事务 |
| stock_command / inventory | W模板；source_service CODE；command_id ID；business_effect_key ID；payload_digest CHAR(64)；state CODE；settlement_digest CHAR(64)?；result_json JSON?；compensates_command_id ID? | UQ(enterprise,warehouse,source_service,command_id)及UQ(enterprise,warehouse,source_service,business_effect_key,action,attempt_no)；stock_effect仲裁；同尝试异摘要拒绝；保留取消墓碑 |
| stock_posting / inventory | W模板；command_id ID；posting_type CODE；quantity QTY；source_execution_id ID；source_document_id ID；ledger_manifest JSON；result_version VER；original_posting_id ID?；reversed_qty QTY | UQ(enterprise,warehouse,source_service,command_id)及UQ(enterprise,warehouse,source_service,business_effect_key,action)；一效果一有效最终凭证；流水/余额/permit同事务；逆向总量不能超过原可逆量 |
| execution_permit / inventory | W模板；permit_id ID；source_service CODE；command_id ID；source_task_id ID；source_task_epoch VER；gate_epoch VER；action CODE；payload_digest CHAR(64)；quantity QTY；actual_qty QTY?；not_executed_qty QTY?；settlement_digest CHAR(64)?；state CODE；started_at TS?；posted_at TS? | UQ(enterprise,warehouse,source_service,command_id)；PREPARED/STARTED/POSTED/CANCELLED；结案actual+not_executed=授权quantity；未知执行不TTL回收 |
| execution_claim / inventory | W模板；permit_id ID；balance_id ID；reservation_line_id ID?；serial_id ID?；claim_type CODE；quantity QTY；state CODE | RESERVED_REFERENCE或FREE_CLAIM；库存事务锁桶/预占/serial仲裁，不能仅靠可空唯一键 |
| quality_qualification / inventory | W模板；inspection_id ID；source_version VER；sku_id ID；lot_id ID；result_code CODE；effective_state CODE；command_id ID | 按inspection版本防乱序；不改变registry epoch；紧急撤销以库存门禁确认生效 |
| serial_package_binding / outbound | W模板；serial_id ID；current_package_id ID? | UQ(enterprise,warehouse,serial_id)；与装箱明细/任务同本地事务 |

source_execution/stock_posting的sourceExecutionId是引用，不存在共享执行表。入出库行原累计字段作为已过账数量，增加receivedPhysicalQty/putawayPhysicalQty/pickedPhysicalQty/shippedPhysicalQty及stockSyncStatus（按动作适用）；物理事实与过账进度分开。新增表/字段均遵循类型模板、中文表列注释和服务独立迁移。

## 9. Seata TCC表归属与二阶段路由

- TC全局/分支会话表使用选定Seata版本官方schema，独立库/账号，不由业务Mapper直接改写。不使用业务allocation_attempt替代TC决策日志。
- inventory各物理库存库配置tcc_fence_log，字段/长度/状态沿用官方版本（通常含xid、branch_id、action_name、status及时间），迁移加中文表列注释。框架Fence与reservation/余额必须同一连接提交，二阶段先按持久化上下文路由再进入Fence事务。
- allocation_participant增加xid、branch_id、action_name、reservation_id、route_epoch及observed_branch_state；fulfillment仅观察，不直接驱动Confirm/Cancel。
- 仓迁移前排空所有未决TCC分支，保留Fence终态；若无法排空则暂停迁移，首期不提供活跃TCC跨库迁移。恢复同时核对TC会话、Fence、XID映射与业务占用。
- ShardingSphere保持LOCAL，禁用Seata AT自动DataSourceProxy。精确代理/事务管理器/Fence装配由S0验证，见[专项设计](09-seata-tcc.md)。

## 10. v0.4幂等身份与CAS约束补充

完整协议见[幂等专项](10-idempotency-protocols.md)。以下字段需与第8节一起生成迁移；当前尚无已执行schema。source_command原effect+action唯一键替换为effect+action+attempt_no，必须同时落地effect权威行及posting唯一约束，禁止只放宽旧索引。

| 表 / 所有者 | 新增字段/数据 | 数据库与事务约束 |
| --- | --- | --- |
| allocation_attempt / fulfillment | launch_epoch VER；launch_owner ID?；launch_lease_until TS?；xid_bound_at TS? | UQ(enterprise,xid)（未绑定允许NULL）；绑定匹配version/epoch/owner/state及xid IS NULL，检查影响行数；绑定后不覆盖；fulfillment_order.active_attempt_id由订单行CAS保护 |
| allocation_launch / fulfillment | C模板；attempt_id ID；launch_epoch VER；executor_id ID；xid VARCHAR(128)?；state CODE；cleanup_state CODE；error_code CODE? | UQ(enterprise,attempt,launch_epoch)；记录空启动/未知begin与清理，不能成为第二全局决策表 |
| source_effect / inbound、outbound或库存自有用例 | W模板；source_service CODE；action CODE；fact_type CODE；fact_parent_id ID；fact_part_id ID；fact_line_id ID；business_effect_key ID；active_command_id ID?；applied_command_id ID?；attempt_no VER；state CODE | UQ(enterprise,warehouse,source_service,action,fact_type,fact_parent_id,fact_part_id,fact_line_id)及UQ(enterprise,warehouse,source_service,action,business_effect_key)；非适用事实维度使用固定非空值；创建子动作与父数量预算同事务 |
| stock_effect / inventory | W模板；source_service CODE；action CODE；business_effect_key ID；active_command_id ID?；applied_command_id ID?；attempt_no VER；state CODE | UQ(enterprise,warehouse,source_service,action,business_effect_key)；在同一行锁/CAS内仲裁活动尝试和唯一有效过账 |
| source_command / stock_command | execution_attempt_id ID；attempt_no VER；previous_command_id ID?；digest_version INT；safe_close_id ID?；safe_close_version VER；safe_close_evidence JSON?；stock_command补action CODE | 原command身份不变；同effect的attempt_no单调且不跳号；只有前次安全终态才能换活动command；正文及摘要不可变 |
| stock_posting | source_service CODE；business_effect_key ID；action CODE；execution_attempt_id ID | 与stock_effect.applied_command_id同本地事务；按第8节命令与效果双唯一；逆向生成独立effect并引用原posting |
| execution_permit | business_effect_key ID；execution_attempt_id ID；attempt_no VER | start/posting/cancel都校验stock_effect活动command；历史permit不因重授权覆盖 |
| command_dedup / source_command / reservation | digest_version INT | 保存规范化正文或不可变快照引用；滚动升级按原摘要版本重放 |
| device_observation_binding / 来源服务 | W模板；device_id ID；session_id ID；sequence_no VER；business_effect_key ID；payload_digest CHAR(64)；digest_version INT | UQ(enterprise,warehouse,device,session,sequence)；同设备观察不能映射到第二效果，与来源动作登记同事务 |

inventory统一锁顺序扩展为：门禁按ID排序 → stock_effect → stock_command → permit → 预占/桶 → serial；多个effect稳定排序。TCC Fence在框架本地事务内先锁其阶段记录，再进入同一库存锁协议，其他入口不反向获取Fence。不同尝试的start、cancel、posting和重授权都经过同一effect行，不能仅锁各自command导致并发绕过。source_effect在来源服务自己的本地事务中仲裁，不持锁远程调用。

仓迁移复制effect/尝试、幂等墓碑、设备映射、posting及Outbox/Inbox并保持旧路由拒写；不存在只搬余额而重新生成业务身份的迁移。归档遵循第7节，响应可以归档，业务唯一身份不能在可重放窗口内丢失。
