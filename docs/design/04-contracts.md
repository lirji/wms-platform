# HTTP API、事件与设备契约

## 1. 通用协议

所有接口为待实现 `/api/wms/v1` 契约，生产使用 TLS。内部跨服务调用也认证，仓权限由服务端执行。enterpriseId/operator 来自已验证 token；请求中的企业字段仅用于一致性核验，不能扩大权限。

写入头：`Authorization`、`Idempotency-Key`、`X-Request-Id`；修改已有资源使用 `If-Match` 的版本。路由由服务端可信目录确定，客户端 warehouseId 只是目标仓，不允许客户端指定 datasource。

金额不在本版库存 API 内；数量 JSON 为十进制字符串。所有 ID 为字符串。时间 RFC3339 UTC；展示使用仓时区。列表 cursor 分页，建议默认 50、上限 200；单次批量写建议上限 200 行/1000 序列号，均为初始可配置上限，需压测验证，大请求转异步作业。

幂等作用域：企业+来源系统+动作+仓（仓级接口）+Idempotency-Key。摘要涵盖规范化业务字段、版本和目标对象，忽略 trace/requestId。相同键相同内容返回原 operation/result；同键不同内容 409。来源幂等与source_command同事务；inventory幂等与库存效果同事务；尚未提交失败允许同键重试，已受理异步任务返回同一任务 ID。已提交但响应丢失，通过 operation 查询恢复。

身份命名：clientOperationId由调用方为一次有意操作生成，与Idempotency-Key一致；有请求体字段时二者不一致返回400。operationId是服务端持久化生成的带路由身份，仅响应/事件/状态查询使用。首次响应丢失时重发原请求和客户端键，取回同一operationId。下表按此区分请求身份与服务端处理身份。

## 2. 服务路由与跨服务结果

公开API前缀不必改变，但后端按资源路由到独立服务：inbound-orders/receipts/quality/putaways→inbound；outbound-orders/picks/packings/shipments→outbound；reservations/inventory/stock-holds/count/adjustment/moves→inventory；fulfillments/transfers→fulfillment。tasks接口必须携带taskType并校验taskID所属域；同名ID不能跨服务猜测。operations用域/逻辑桶定位来源服务，返回其关联inventoryOperationId。

入出库写接口首先持久化意图/事实，返回202及sourceOperationId、commandId、physicalStatus、stockSyncStatus和statusUrl。下表201在创建单据/包裹等纯来源资源时仍适用；涉及实物与库存同步的receipts/putaways/picks/shipments/transfer-receipts统一采用上述202契约，不能把受理当过账完成。库存纯同步命令成功可200/201，但公开单据完成需等回执。

| 内部接口 / inventory | 必要参数 | 结果 |
| --- | --- | --- |
| POST `/internal/wms/v1/warehouses/{warehouseId}/stock-commands` | commandId,sourceService,sourceExecutionId,businessEffectKey,payloadDigest,action,quantity,permitRef,authorizationRef | APPLIED/REJECTED/CANCELLED或处理中；inventoryOperationId/postingId |
| GET `/internal/wms/v1/warehouses/{warehouseId}/stock-commands/{commandId}` | sourceService与认证范围 | 不可变结果/处理中/未收到；404不代表可安全取消实物动作 |
| POST `/internal/wms/v1/warehouses/{warehouseId}/stock-commands/{commandId}/cancellations` | sourceService,sourceVersion,notExecutedEvidence,reason | CANCELLED墓碑 / ALREADY_APPLIED / EXECUTION_UNKNOWN |
| POST `/internal/wms/v1/warehouses/{warehouseId}/execution-permits` | commandId,sourceTaskId,taskEpoch,action,qty,stockRefs,serialIds,payloadDigest | PREPARED及permitId/gateEpoch，数量已占用 |
| POST `/internal/wms/v1/warehouses/{warehouseId}/execution-permits/{id}/starts` | commandId,taskEpoch,expectedVersion | STARTED；当前门禁/效期校验 |

sourceService由服务凭据核验，不接受伪造调用者。同步接口与Kafka命令共享处理器。StockCommandRequested / StockOperationApplied / Rejected / Cancelled新增事件均带sourceCommandId、sourceOperationId、inventoryOperationId、sourceExecutionId、businessEffectKey、payloadDigest、permitId；收到回执必须逐项匹配。详见[跨服务协议](08-service-boundaries-protocols.md)。

### 2.1 业务接口清单

W 表示仓级接口，路径前缀 `/warehouses/{warehouseId}`；G 表示全局。下表字段类型沿用数据库字典，camelCase 为 JSON 名称。

| 方法/路径 | 关键请求字段 | 成功结果 | 权限 |
| --- | --- | --- | --- |
| POST G `/fulfillments` | sourceSystem,sourceOrderNo,lines[{sourceLineId,skuId,quantity,unit,minRemainingDays}],strategyVersion | 202 fulfillmentId,operationId,status | fulfillment.create |
| GET G `/fulfillments/{id}` | 企业上下文 | 200 status,attemptId,warehouseAllocations[],version | fulfillment.read |
| POST G `/fulfillments/{id}/cancellations` | reason,expectedVersion | 202 cancellationId,status | fulfillment.cancel |
| 内部 TCC Try `ReservationTccAction.tryReserve` | BusinessActionContext、enterprise/warehouse、allocationId、attemptId、lines、requestDigest、routeEpoch | TRIED、reservationId、XID/branchId | 仅可信TM/RM上下文 |
| 内部 TCC Confirm `ReservationTccAction.confirmReserve` | 持久化BusinessActionContext中的仓/attempt/XID/branchId | CONFIRMED，幂等 | 仅Seata RM二阶段回调 |
| 内部 TCC Cancel `ReservationTccAction.cancelReserve` | 持久化BusinessActionContext中的仓/attempt/XID/branchId | CANCELLED，空回滚安全 | 仅Seata RM二阶段回调 |
| POST W `/outbound-orders/{id}/execution-authorizations` | attemptId,authorizationId,xid,tcTerminalEvidenceRef,participantSetHash | 200 accepted,version | 内部 fulfillment.execute |
| POST W `/inbound-orders` | sourceSystem,externalNo,ownerId,expectedAt,lines[] | 201 orderId,version | inbound.create |
| POST W `/inbound-orders/{id}/receipts` | clientOperationId,effectId,executionAttemptId,receiptSessionId,receiptPartId,lineId,qty,unit,locationId,lot,serialNumbers[],deviceContext | 201 receiptId 或 202 登记待确认，均含operationId | inbound.receive |
| POST W `/quality-inspections/{id}/results` | resultCode,acceptedQty,rejectedQty,evidenceRefs[],reason | 200 inspectionVersion | quality.inspect |
| POST W `/tasks/{id}/claims` | expectedVersion | 200 claimEpoch,taskVersion | task.claim |
| POST W `/tasks/{id}/putaways` | clientOperationId,effectId,executionAttemptId,subActionId,claimEpoch,targetLocationId,qty,unit,serialIds[] | 201 executionId,operationId,version | inbound.putaway |
| POST W `/tasks/{id}/picks` | clientOperationId,effectId,executionAttemptId,subActionId,claimEpoch,sourceLocationId,stagingLocationId,qty,serialIds[] | 201 executionId,operationId,version | outbound.pick |
| POST W `/outbound-orders/{id}/packings` | packageId,lines[],weight?,weightUnit?,serialIds[] | 201 packingId,version | outbound.pack |
| POST W `/outbound-orders/{id}/shipments` | shipmentId,shipmentPartId,effectId,executionAttemptId,manifestRevision,packageIds[],carrierRef?,clientOperationId | 201 executionId,operationId,shippedLines[],version | outbound.ship |
| POST W `/moves` | clientOperationId,effectId,executionAttemptId,subActionId,sourceBalanceId,targetLocationId,qty,unit,serialIds[],reason | 201 movementId,operationId | stock.move |
| POST W `/stock-holds` | scope,reason,evidenceRefs[] | 202 holdId,state | stock.hold |
| POST W `/stock-holds/{id}/releases` | reason,expectedVersion | 200 state,version | stock.releaseHold |
| POST G `/transfers` | sourceWarehouseId,targetWarehouseId,lines[],reason | 201 transferId,status | transfer.create |
| POST G `/transfers/{id}/receipt-authorizations` | transferLineId,targetWarehouseId,targetClientOperationId,quantity | 201 authorizationId,tokenVersion,quantity | 内部 transfer.authorizeReceipt |
| POST W `/transfer-receipts` | transferId,sourceLineRef,authorizationId,tokenVersion,qty,businessLotKey,lot,serialIds[],clientOperationId | 201/202 receiptId,operationId,status | transfer.receive |
| POST W `/count-plans` | locationIds[],reason | 201 planId,DRAFT | count.create |
| POST W `/count-plans/{id}/freeze-requests` | expectedVersion | 202 QUIESCING | count.freeze |
| POST W `/count-plans/{id}/observations` | observationId,lineId,roundNo,qty,serialIds[] | 201 observationId | count.record |
| POST W `/adjustments` | countLineId?,balanceId,deltaQty,serialActions[{serialId,action}],reason,evidenceRefs[] | 201 adjustmentId,PENDING_APPROVAL | adjustment.create |
| POST W `/adjustments/{id}/approvals` | decision,reason,expectedVersion | 200 APPROVED/REJECTED | adjustment.approve |
| POST W `/adjustments/{id}/applications` | clientOperationId,expectedVersion,countPlanId,gateEpoch,approvalId | 200 APPLIED / 202登记待收敛 / 409冲突，含operationId | adjustment.apply |
| GET G `/inventory` | warehouseIds,skuId,lotId,qualityCode,cursor,limit | 200 items,nextCursor,asOf,lagSeconds | stock.read |
| GET W `/inventory/{balanceId}/ledger` | cursor,limit | 200 orderedEntries,nextCursor | stock.audit |
| GET G `/operations/{operationId}` | 所属资源/仓路由信息 | 200 status,result,error,updatedAt | 原业务权限 |
| GET G `/jobs/{jobId}` | scope | 200 progress,state,failedShards[] | job.read |
| POST G `/jobs/{jobId}/retries` | failedShardIds,reason,expectedVersion | 202 同一业务任务恢复 | job.retry |
| POST G `/reconciliation-snapshots` | warehouseIds,scenarioCode,cutoff,sourceWatermarks | 202 snapshotJobId | recon.export |
| GET G `/reconciliation-snapshots/{id}` | 无 | 200 manifest,state,downloadRefs | recon.read |
| POST W `/reconciliation-cases/{id}/remediations` | approvedAction,reason,expectedVersion | 202 operationId | recon.remediate |

按类提供 GET 列表/详情（inbound-orders、outbound-orders、tasks、count-plans、adjustments、transfers、cases），返回同一实体 DTO、version、可执行 actions 和统一 cursor 元数据。服务端 actions 是交互提示，每次写入仍校验权限与当前状态。接口生成阶段把本表逐项落实 OpenAPI 3.1，不以目前 Markdown 冒充已生成 SDK。

TCC三阶段是框架业务接口，实际HTTP/RPC传输与XID传播在S0锁定；不开放自定义/prepare或/decisions REST端点。Confirm/Cancel仅由可信RM回调执行。execution-authorizations需核验fulfillment成功屏障，普通调用者不能填写COMMIT/XID自行确认。

调拨接收需验证全局额度授权和目的仓/操作/数量绑定，本地token消费与入账同事务。额度取消先在目的仓持久化未消费token的CANCELLED，再释放全局额度；已消费则返回原收货事实。序列号调整serialActions为必填且净数量=deltaQty；盘点缺失身份不得用数量输入省略。非盘点调整使用独立审批入口、OPEN门禁及同样身份协议，不能伪造countPlanId绕过冻结。

## 3. 跨仓请求和结果示例

```json
{
  "sourceSystem": "OMS",
  "sourceOrderNo": "OMS-DEMO-001",
  "strategyVersion": 1,
  "lines": [
    {"sourceLineId": "L1", "skuId": "SKU-DEMO", "quantity": "10", "unit": "EA", "minRemainingDays": 30}
  ]
}
```

```json
{
  "fulfillmentId": "F-DEMO-001",
  "operationId": "OP-DEMO-001",
  "status": "ACCEPTED",
  "version": 1,
  "statusUrl": "/api/wms/v1/fulfillments/F-DEMO-001"
}
```

202 表示持久化受理，不能显示“已分配成功”。最终 ALLOCATED 结果包含每仓子单及已确认数量；失败列出业务错误及是否已完成释放。返回 TCC_RECOVERY_PENDING 时明确仍有库存占用。

收货 `lot` 字段：`lotCode, producedAt?, expiresAt?, sourceDate?, expiryRuleVersion`；启用批次/效期时相应字段必填。deviceContext 为 `deviceId,scanSessionId,scanSequence`，每个有意扫描操作有独立clientOperationId；网络重试沿用原客户端键，服务端返回同一operationId。

## 4. 错误语义

统一返回 `code,message,requestId,operationId?,retryable,details[]`，details 只包含安全字段与业务定位，不暴露堆栈、数据库或其他仓库存。

| HTTP | code | 客户端动作 |
| --- | --- | --- |
| 400 | INVALID_QUANTITY / INVALID_UNIT / REQUIRED_LOT / INVALID_EXPIRY | 修正输入；不盲重试 |
| 401/403 | UNAUTHENTICATED / WAREHOUSE_FORBIDDEN | 登录/权限处理；不改变仓参数绕过 |
| 404 | RESOURCE_NOT_FOUND | 对无权限资源采用一致的信息披露策略 |
| 409 | IDEMPOTENCY_PAYLOAD_MISMATCH / VERSION_CONFLICT | 同键内容冲突拒绝；版本冲突刷新后人工/业务重算 |
| 409 | STOCK_INSUFFICIENT / STOCK_FROZEN / EXPIRED_STOCK | 重新规划或异常处理 |
| 409 | SERIAL_OWNERSHIP_CONFLICT / STALE_OWNER_EPOCH | 查询登记/转移事实，不创建重复库存 |
| 409 | TCC_TRANSACTION_PENDING / INVALID_STATE / TCC_CONTEXT_MISMATCH | 查询原 attempt 和决策，禁止改键强行确认 |
| 409 | ROUTE_EPOCH_STALE | 服务间刷新路由；原 operationId 重试 |
| 429 | RESOURCE_BUDGET_EXCEEDED | 遵循 Retry-After、有界抖动退避 |
| 503 | DEPENDENCY_UNAVAILABLE | 查询 operation 后同键有限重试 |

网络超时没有 HTTP 业务结论。库存效果未知时展示“处理中，请查询结果”，不能允许 UI 自动换新幂等键再次提交。

## 5. Kafka 事件信封

首期建议 topic 按域与 Cell 拆分：`wms.<env>.<cell>.inventory.v1`、`wms.<env>.<cell>.execution.v1`、`wms.<env>.fulfillment.v1`、`wms.<env>.serial.v1`。实际分区数按容量测量；扩分区可能改变 key 路由，业务版本仍必须防乱序。

```json
{
  "eventId": "EVT-DEMO-001",
  "eventType": "InventoryBalanceChanged",
  "schemaVersion": 1,
  "enterpriseId": "ENT-DEMO",
  "warehouseId": "WH-A",
  "cellId": "CELL-A",
  "routeEpoch": 1,
  "aggregateType": "STOCK_BALANCE",
  "aggregateId": "BAL-DEMO-001",
  "aggregateVersion": 12,
  "operationId": "OP-DEMO-001",
  "causationId": "SHIPMENT-DEMO-001",
  "occurredAt": "2026-09-10T02:00:00Z",
  "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
  "payload": {
    "onHandDelta": "-2", "reservedDelta": "-2",
    "onHandAfter": "8", "reservedAfter": "3",
    "ledgerEntryId": "LEDGER-DEMO-001", "baseUnit": "EA"
  }
}
```

示例为设计数据，不会硬编码进入页面。Kafka key=`enterpriseId/warehouseId/aggregateType/aggregateId`，全局事件省略 warehouse。一个事务涉及多个桶时每桶有独立 event/version，共享 operationId；消费者不能假设跨 key 同时可见。

| 事件 | 关键 payload | 消费与幂等 |
| --- | --- | --- |
| InventoryBalanceChanged | 桶完整维度、delta/after、balanceVersion、ledgerEntryId | 余额投影用 after+版本；流水按 eventId 插入，不能因余额版本旧就丢历史流水 |
| WarehouseReservationTried/Confirmed/Cancelled | allocationId,attemptId,reservationId,xid,branchId,quantities | 分支事实仅用于投影和核对，不能代替TC全局成功 |
| AllocationCompleted | attemptId,xid,participantSetHash,tcTerminalEvidenceRef,authorizationId | fulfillment在TC全局成功且全部仓确认后发布；不驱动TCC二阶段 |
| ShipmentConfirmed / ReceiptConfirmed | 来源服务在库存回执收敛后发出；单据行、quantity、unit、lot、serial清单、commandId、postingId | OMS/ERP/对账按已过账事实核对；实物交接另有Physical事件 |
| SerialSourceReleased / SerialTargetReceived | serialId,transferId,ownerEpoch,source/target,executionRef | 登记按状态/版本 CAS；大批序列号分块有 manifest 和完整性校验 |
| CountAdjustmentApplied | adjustmentId,countLineId,delta,reason,approvalRef | 对账和审计；重复不得重复调整 |

投递器至少一次：claim_epoch 控制谁更新发送状态；发送后宕机可重复发。消费者本地 inbox+业务效果同事务，位点在其后提交。生产端配置 acks/幂等/副本与 min.insync.replicas 按目标 Kafka 版本验证，开发单节点不能证明高可用。不会把 Kafka 事务当作跨 MySQL 和外部设备的 exactly-once。

增量消费者遇版本缺口暂停该聚合并补拉/重建；完整快照消费者可用更高版本覆盖但记录缺口监控。毒消息进入隔离记录，阻塞相关聚合后续依赖动作，修复后有序重放；不能绕过毒消息继续执行依赖动作。

## 6. 设备、离线与版本演进

设备命令状态：CREATED/SENT/ACCEPTED/EXECUTING/EXECUTED/BUSINESS_POSTED/UNKNOWN/FAILED。外部 commandId、业务 operationId 和厂家回执号分别保存。UNKNOWN 时按厂家协议查询状态，只有确定未执行且协议允许才重发；人工接管需锁定命令处理权并记录证据。

离线首期只记录观察/扫描意图，不承诺库存预占、发运或跨仓确认。重连按设备 session+sequence 提交，服务端依次核对业务版本，冲突隔离给操作员；时间戳只用于审计。离线数据在设备加密、有界存储，退出登录不得把上一用户扫描当作新用户操作。

API v1 只新增可选字段；必填变化和语义变化升版本。事件 schemaVersion 与聚合业务版本分开。先部署兼容消费者再启用新事件；至少验证 N/N-1 共存组合，具体兼容窗口由发布节奏确定。数据库 expand/backfill/validate/switch/contract，旧路径退出且回退窗口关闭前不删字段。

## 7. v0.4幂等与安全重授权契约

本节与[幂等专项](10-idempotency-protocols.md)配套。effectId是businessEffectKey的服务端不透明身份，来源按完整事实字段生成/恢复，不能直接信任客户端提交的effect。下列W使用本文件既有仓级前缀，由动作拥有者服务提供；inventory内置动作由inventory承担来源角色。所有变更接口继续要求Idempotency-Key、权限和请求摘要。

| 方法/路径 | 请求与返回 | 约束 |
| --- | --- | --- |
| POST W `/action-effects` | factType、factParentId、factPartId、factLineId、action、父任务版本及数量预算；返回effectId/currentAttempt/commandId | 来源按动作允许列表及权威父事实登记；同事实换请求键仍返回同effect；新子事实需父额度仲裁 |
| GET W `/tasks/{id}/action-effects` | 有界稳定分页；返回动作槽、effectId、activeAttempt与历史结果查询链接 | 客户端恢复身份入口；外部事实另支持受权限约束的精确映射查询 |
| POST W `/action-effects/{effectId}/execution-attempts` | previousCommandId、expectedEffectVersion、修订意图/摘要版本、reason；返回202同一受理操作与尝试状态 | 首次创建或安全重授权；来源持久化停止派发意图，经库存安全关闭后才生成下一尝试；未知状态不得直接重做 |
| GET W `/action-effects/{effectId}` | activeCommandId、appliedCommandId、attemptNo、pendingOperation及safeToRetry状态/原因 | safeToRetry由服务端证据计算，不由UI时钟判断 |

库存命令、permit申请、开始/取消、事件和结果统一增加businessEffectKey、executionAttemptId、attemptNo、previousCommandId（首次可空）与digestVersion；取消增加原身份、原授权意图及规范化摘要（无记录时用于建立完整墓碑）、未执行证据引用，来源停止派发证明需受控核验。安全关闭结果含safeCloseRef、原command/permit、claim处置、证据版本。REJECTED原结果不改，safeCloseRef为独立事实；新命令仍须库存读取自己的安全关闭记录，不能仅凭外部引用授权。

HTTP业务错误新增409：EFFECT_IDENTITY_CONFLICT、STALE_EXECUTION_ATTEMPT、EFFECT_ALREADY_APPLIED；安全结果未知返回202 RECOVERY_PENDING，不当作取消成功。TCC_OWNER_CONFLICT是内部Try失败码，必须传播为全局失败，不能用HTTP 200包装后让TM提交。重复分支回调按09/10处理，不能将业务HTTP重试策略套到TCC action上。

当前没有已部署v1，本次为首版契约定稿输入。未来若已有旧客户端/事件，必填身份字段须通过适配层从原不可变事实映射或升契约版本；无法恢复稳定身份的旧请求隔离处理，禁止自动生成随机effectId来伪造兼容。原摘要按digestVersion保存并重放。
