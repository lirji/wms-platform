#!/usr/bin/env python3
"""Generate committed OpenAPI from the approved contract table. Run from repo root."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "wms-contract/src/main/resources/openapi/wms-v1.yaml"
SCOPE_OUT = ROOT / "wms-security/src/main/resources/wms-operation-scopes.tsv"
scope_rules = []


def responses(*codes, success_schema="ResourceEnvelope"):
    lines = ["      responses:"]
    for code in codes:
        desc = {
            "200": "成功",
            "201": "已创建资源",
            "202": "已持久化受理，不是业务终态成功",
        }.get(code, "错误")
        if code in ("200", "201", "202"):
            schema = "AcceptedOperation" if code == "202" and success_schema == "ResourceEnvelope" else success_schema
            lines.append(f"        '{code}':")
            lines.append(f"          description: {desc}")
            lines.append("          content:")
            lines.append("            application/json:")
            lines.append("              schema:")
            lines.append(f"                $ref: '#/components/schemas/{schema}'")
        else:
            lines.append(f"        '{code}':")
            lines.append("          $ref: '#/components/responses/Error'")
    return "\n".join(lines)


def write_op(method, path, op, tag, scope, body, success, extra_params=None, description="", success_schema="ResourceEnvelope"):
    scope_rules.append((method.upper(), path, scope))
    write = method in ("post", "put", "patch")
    params = extra_params or []
    if write:
        params = [
            "- $ref: '#/components/parameters/IdempotencyKey'",
            "- $ref: '#/components/parameters/XRequestId'",
        ] + params
    else:
        params = ["- $ref: '#/components/parameters/XRequestId'"] + params
    lines = [
        f"  {path}:",
        f"    {method}:",
        f"      operationId: {op}",
        f"      tags: [{tag}]",
        f"      summary: {description or op}",
        f"      security:",
        f"        - oidc: [{scope}]",
        "      parameters:",
    ]
    for param in params:
        lines.append(f"        {param}")
    if body:
        lines += [
            "      requestBody:",
            "        required: true",
            "        content:",
            "          application/json:",
            "            schema:",
            f"              $ref: '#/components/schemas/{body}'",
        ]
    schema = "CursorPage" if any("Cursor" in item for item in (extra_params or [])) else success_schema
    lines.append(responses(*success, "400", "401", "403", "404", "409", "429", "503", success_schema=schema))
    return "\n".join(lines)


ops = []

def post(path, op, tag, scope, body, success, description, extra=None, success_schema="ResourceEnvelope"):
    ops.append(write_op("post", path, op, tag, scope, body, success, extra, description, success_schema))


def get(path, op, tag, scope, success, description, extra=None, success_schema="ResourceEnvelope"):
    ops.append(write_op("get", path, op, tag, scope, None, success, extra, description, success_schema))


cursor = [
    "- $ref: '#/components/parameters/Cursor'",
    "- $ref: '#/components/parameters/Limit'",
]
wh = ["- $ref: '#/components/parameters/WarehouseId'"]

post("/api/wms/v1/fulfillments", "createFulfillment", "fulfillment", "fulfillment.create",
     "FulfillmentCreateRequest", ("202",), "创建全局履约单，202表示受理而非已分配")
get("/api/wms/v1/fulfillments/{fulfillmentId}", "getFulfillment", "fulfillment", "fulfillment.read",
    ("200",), "查询履约单及仓分配", ["- $ref: '#/components/parameters/FulfillmentId'"])
post("/api/wms/v1/fulfillments/{fulfillmentId}/cancellations", "cancelFulfillment", "fulfillment",
     "fulfillment.cancel", "VersionedReasonRequest", ("202",), "取消履约",
     ["- $ref: '#/components/parameters/FulfillmentId'"])
post("/api/wms/v1/warehouses/{warehouseId}/outbound-orders/{outboundOrderId}/execution-authorizations",
     "authorizeOutboundExecution", "outbound", "fulfillment.execute",
     "ExecutionAuthorizationRequest", ("200",), "核验TCC成功屏障后授权出库执行",
     wh + ["- $ref: '#/components/parameters/OutboundOrderId'"])
post("/api/wms/v1/warehouses/{warehouseId}/inbound-orders", "createInboundOrder", "inbound",
     "inbound.create", "InboundOrderCreateRequest", ("201",), "创建入库单", wh)
get("/api/wms/v1/warehouses/{warehouseId}/inbound-orders", "listInboundOrders", "inbound",
    "inbound.read", ("200",), "入库单列表", wh + cursor)
get("/api/wms/v1/warehouses/{warehouseId}/inbound-orders/{inboundOrderId}", "getInboundOrder",
    "inbound", "inbound.read", ("200",), "入库单详情",
    wh + ["- $ref: '#/components/parameters/InboundOrderId'"])
post("/api/wms/v1/warehouses/{warehouseId}/inbound-orders/{inboundOrderId}/receipts",
     "confirmReceipt", "inbound", "inbound.receive", "ReceiptRequest", ("202",),
     "记录收货事实；涉及库存同步返回202",
     wh + ["- $ref: '#/components/parameters/InboundOrderId'"])
post("/api/wms/v1/warehouses/{warehouseId}/quality-inspections/{inspectionId}/results",
     "recordQualityResult", "inbound", "quality.inspect", "QualityResultRequest", ("200",),
     "记录质检结果", wh + ["- $ref: '#/components/parameters/InspectionId'"])
post("/api/wms/v1/warehouses/{warehouseId}/tasks/{taskId}/claims", "claimTask", "task",
     "task.claim", "VersionedReasonRequest", ("200",), "领取任务",
     wh + ["- $ref: '#/components/parameters/TaskId'"])
get("/api/wms/v1/warehouses/{warehouseId}/tasks", "listTasks", "task", "task.read",
    ("200",), "任务列表，必须携带taskType并校验所属域",
    wh + cursor + ["- $ref: '#/components/parameters/TaskType'"])
get("/api/wms/v1/warehouses/{warehouseId}/tasks/{taskId}", "getTask", "task", "task.read",
    ("200",), "任务详情", wh + ["- $ref: '#/components/parameters/TaskId'"])
get("/api/wms/v1/warehouses/{warehouseId}/tasks/{taskId}/action-effects", "listTaskActionEffects",
    "idempotency", "task.read", ("200",), "恢复动作槽与effect身份",
    wh + ["- $ref: '#/components/parameters/TaskId'"] + cursor)
post("/api/wms/v1/warehouses/{warehouseId}/tasks/{taskId}/putaways", "confirmPutaway", "inbound",
     "inbound.putaway", "PutawayRequest", ("202",), "上架确认",
     wh + ["- $ref: '#/components/parameters/TaskId'"])
post("/api/wms/v1/warehouses/{warehouseId}/tasks/{taskId}/picks", "confirmPick", "outbound",
     "outbound.pick", "PickRequest", ("202",), "拣货确认",
     wh + ["- $ref: '#/components/parameters/TaskId'"])
get("/api/wms/v1/warehouses/{warehouseId}/outbound-orders", "listOutboundOrders", "outbound",
    "outbound.read", ("200",), "出库单列表", wh + cursor)
get("/api/wms/v1/warehouses/{warehouseId}/outbound-orders/{outboundOrderId}", "getOutboundOrder",
    "outbound", "outbound.read", ("200",), "出库单详情",
    wh + ["- $ref: '#/components/parameters/OutboundOrderId'"])
post("/api/wms/v1/warehouses/{warehouseId}/outbound-orders/{outboundOrderId}/packings",
     "confirmPacking", "outbound", "outbound.pack", "PackingRequest", ("201",), "装箱",
     wh + ["- $ref: '#/components/parameters/OutboundOrderId'"])
post("/api/wms/v1/warehouses/{warehouseId}/outbound-orders/{outboundOrderId}/shipments",
     "confirmShipment", "outbound", "outbound.ship", "ShipmentRequest", ("202",),
     "发运；库存同步返回202", wh + ["- $ref: '#/components/parameters/OutboundOrderId'"])
post("/api/wms/v1/warehouses/{warehouseId}/moves", "moveStock", "inventory", "stock.move",
     "MoveRequest", ("202",), "移库", wh)
post("/api/wms/v1/warehouses/{warehouseId}/stock-holds", "createStockHold", "inventory",
     "stock.hold", "StockHoldRequest", ("202",), "库存限制", wh)
post("/api/wms/v1/warehouses/{warehouseId}/stock-holds/{holdId}/releases", "releaseStockHold",
     "inventory", "stock.releaseHold", "VersionedReasonRequest", ("200",), "释放库存限制",
     wh + ["- $ref: '#/components/parameters/HoldId'"])
post("/api/wms/v1/transfers", "createTransfer", "fulfillment", "transfer.create",
     "TransferCreateRequest", ("201",), "创建跨仓调拨")
get("/api/wms/v1/transfers", "listTransfers", "fulfillment", "transfer.read", ("200",),
    "调拨列表", cursor)
get("/api/wms/v1/transfers/{transferId}", "getTransfer", "fulfillment", "transfer.read",
    ("200",), "调拨详情", ["- $ref: '#/components/parameters/TransferId'"])
post("/api/wms/v1/transfers/{transferId}/receipt-authorizations", "authorizeTransferReceipt",
     "fulfillment", "transfer.authorizeReceipt", "ReceiptAuthorizationRequest", ("201",),
     "目的仓接收额度授权", ["- $ref: '#/components/parameters/TransferId'"])
post("/api/wms/v1/warehouses/{warehouseId}/transfer-receipts", "receiveTransfer", "inbound",
     "transfer.receive", "TransferReceiptRequest", ("202",), "调拨接收", wh)
post("/api/wms/v1/warehouses/{warehouseId}/count-plans", "createCountPlan", "inventory",
     "count.create", "CountPlanCreateRequest", ("201",), "创建盘点计划", wh)
get("/api/wms/v1/warehouses/{warehouseId}/count-plans", "listCountPlans", "inventory",
    "count.read", ("200",), "盘点计划列表", wh + cursor)
get("/api/wms/v1/warehouses/{warehouseId}/count-plans/{countPlanId}", "getCountPlan",
    "inventory", "count.read", ("200",), "盘点计划详情",
    wh + ["- $ref: '#/components/parameters/CountPlanId'"])
post("/api/wms/v1/warehouses/{warehouseId}/count-plans/{countPlanId}/freeze-requests",
     "requestCountFreeze", "inventory", "count.freeze", "VersionedReasonRequest", ("202",),
     "进入QUIESCING", wh + ["- $ref: '#/components/parameters/CountPlanId'"])
post("/api/wms/v1/warehouses/{warehouseId}/count-plans/{countPlanId}/observations",
     "recordCountObservation", "inventory", "count.record", "CountObservationRequest", ("201",),
     "盘点观察，不覆盖历史轮次", wh + ["- $ref: '#/components/parameters/CountPlanId'"])
post("/api/wms/v1/warehouses/{warehouseId}/adjustments", "createAdjustment", "inventory",
     "adjustment.create", "AdjustmentCreateRequest", ("201",), "创建调整", wh)
get("/api/wms/v1/warehouses/{warehouseId}/adjustments", "listAdjustments", "inventory",
    "adjustment.read", ("200",), "调整列表", wh + cursor)
get("/api/wms/v1/warehouses/{warehouseId}/adjustments/{adjustmentId}", "getAdjustment",
    "inventory", "adjustment.read", ("200",), "调整详情",
    wh + ["- $ref: '#/components/parameters/AdjustmentId'"])
post("/api/wms/v1/warehouses/{warehouseId}/adjustments/{adjustmentId}/approvals",
     "approveAdjustment", "inventory", "adjustment.approve", "AdjustmentApprovalRequest",
     ("200",), "审批调整", wh + ["- $ref: '#/components/parameters/AdjustmentId'"])
post("/api/wms/v1/warehouses/{warehouseId}/adjustments/{adjustmentId}/applications",
     "applyAdjustment", "inventory", "adjustment.apply", "AdjustmentApplyRequest", ("202",),
     "应用调整", wh + ["- $ref: '#/components/parameters/AdjustmentId'"])
get("/api/wms/v1/inventory", "queryInventory", "inventory", "stock.read", ("200",),
    "库存查询，资格过滤后的计算值", cursor + [
        "- $ref: '#/components/parameters/SkuIdQuery'",
        "- $ref: '#/components/parameters/LotIdQuery'",
        "- $ref: '#/components/parameters/QualityCodeQuery'",
        "- $ref: '#/components/parameters/WarehouseIdsQuery'",
    ])
get("/api/wms/v1/warehouses/{warehouseId}/inventory/{balanceId}/ledger", "listLedger",
    "inventory", "stock.audit", ("200",), "库存流水",
    wh + ["- $ref: '#/components/parameters/BalanceId'"] + cursor)
get("/api/wms/v1/operations/{operationId}", "getOperation", "common", "operation.read",
    ("200",), "按服务端operationId查询结果",
    ["- $ref: '#/components/parameters/OperationId'"])
get("/api/wms/v1/jobs/{jobId}", "getJob", "job", "job.read", ("200",), "作业进度",
    ["- $ref: '#/components/parameters/JobId'"])
post("/api/wms/v1/jobs/{jobId}/retries", "retryJob", "job", "job.retry",
     "JobRetryRequest", ("202",), "失败分片恢复同一业务任务",
     ["- $ref: '#/components/parameters/JobId'"])
post("/api/wms/v1/reconciliation-snapshots", "createReconciliationSnapshot", "recon",
     "recon.export", "ReconciliationSnapshotRequest", ("202",), "每次导出最多100行，以相同请求续跑直至COMPLETE")
get("/api/wms/v1/reconciliation-snapshots/{snapshotId}", "getReconciliationSnapshot", "recon",
    "recon.read", ("200",), "读取快照manifest及一个分段，nextPartNo作为下次afterPart",
    ["- $ref: '#/components/parameters/SnapshotId'", "- $ref: '#/components/parameters/AfterPart'"])
get("/api/wms/v1/warehouses/{warehouseId}/reconciliation-cases", "listReconciliationCases",
    "recon", "recon.read", ("200",), "对账差异列表", wh + cursor)
get("/api/wms/v1/warehouses/{warehouseId}/reconciliation-cases/{caseId}", "getReconciliationCase",
    "recon", "recon.read", ("200",), "对账差异详情",
    wh + ["- $ref: '#/components/parameters/CaseId'"])
post("/api/wms/v1/warehouses/{warehouseId}/reconciliation-cases/{caseId}/remediations",
     "remediateReconciliationCase", "recon", "recon.remediate", "RemediationRequest", ("202",),
     "审批后走业务入口修复", wh + ["- $ref: '#/components/parameters/CaseId'"])
post("/api/wms/v1/warehouses/{warehouseId}/action-effects", "registerActionEffect",
     "idempotency", "task.read", "ActionEffectRequest", ("201",),
     "按权威事实登记effect身份，换客户端键仍返回同一effect", wh)
get("/api/wms/v1/warehouses/{warehouseId}/action-effects/{effectId}", "getActionEffect",
    "idempotency", "task.read", ("200",), "查询active/applied命令与safeToRetry",
    wh + ["- $ref: '#/components/parameters/EffectId'"])
post("/api/wms/v1/warehouses/{warehouseId}/action-effects/{effectId}/execution-attempts",
     "createExecutionAttempt", "idempotency", "task.claim", "ExecutionAttemptRequest", ("202",),
     "安全重授权；未知状态不得直接重做",
     wh + ["- $ref: '#/components/parameters/EffectId'"])
post("/api/wms/v1/warehouses", "createWarehouse", "masterdata", "masterdata.write",
     "WarehouseCreateRequest", ("201",), "创建仓库")
get("/api/wms/v1/warehouses", "listWarehouses", "masterdata", "masterdata.read", ("200",),
    "可访问仓列表", cursor)
get("/api/wms/v1/warehouses/{warehouseId}", "getWarehouse", "masterdata", "masterdata.read",
    ("200",), "仓库详情", wh)
post("/api/wms/v1/warehouses/{warehouseId}/locations", "createLocation", "masterdata",
     "masterdata.write", "LocationCreateRequest", ("201",), "创建库位并初始化OPEN门禁", wh)
get("/api/wms/v1/warehouses/{warehouseId}/locations", "listLocations", "masterdata",
    "masterdata.read", ("200",), "库位列表", wh + cursor)
get("/api/wms/v1/warehouses/{warehouseId}/locations/{locationId}", "getLocation", "masterdata",
    "masterdata.read", ("200",), "库位详情",
    wh + ["- $ref: '#/components/parameters/LocationId'"])
get("/api/wms/v1/warehouses/{warehouseId}/locations/{locationId}/gate", "getLocationGate",
    "masterdata", "masterdata.read", ("200",), "库位门禁",
    wh + ["- $ref: '#/components/parameters/LocationId'"])
post("/api/wms/v1/skus", "createSku", "masterdata", "masterdata.write", "SkuCreateRequest",
     ("201",), "创建SKU及基础单位换算")
get("/api/wms/v1/skus", "listSkus", "masterdata", "masterdata.read", ("200",), "商品列表", cursor)
get("/api/wms/v1/skus/{skuId}", "getSku", "masterdata", "masterdata.read", ("200",),
    "商品详情含单位版本", ["- $ref: '#/components/parameters/SkuId'"])
get("/api/wms/v1/skus/{skuId}/units", "listSkuUnits", "masterdata", "masterdata.read", ("200",),
    "当前策略版本单位换算", ["- $ref: '#/components/parameters/SkuId'"] + cursor)
post("/api/wms/v1/skus/{skuId}/units", "addSkuUnit", "masterdata", "masterdata.write",
     "SkuUnitCreateRequest", ("201",), "追加当前策略版本单位",
     ["- $ref: '#/components/parameters/SkuId'"])
post("/api/wms/v1/warehouses/{warehouseId}/lots", "createLot", "masterdata", "masterdata.write",
     "LotCreateRequest", ("201",), "登记仓级批次；无批次SKU使用NO_LOT sentinel且不落本表", wh)
get("/api/wms/v1/warehouses/{warehouseId}/lots", "listLots", "masterdata", "masterdata.read",
    ("200",), "批次列表", wh + cursor)
get("/api/wms/v1/warehouses/{warehouseId}/lots/{lotId}", "getLot", "masterdata",
    "masterdata.read", ("200",), "批次详情",
    wh + ["- $ref: '#/components/parameters/LotId'"])
post("/internal/wms/v1/warehouses/{warehouseId}/stock-commands", "submitStockCommand",
     "internal-inventory", "inventory.command", "StockCommandRequest", ("200",),
     "来源服务提交库存命令", wh)
get("/internal/wms/v1/warehouses/{warehouseId}/stock-commands/{commandId}", "getStockCommand",
    "internal-inventory", "inventory.command", ("200",), "查询库存命令；404不代表可安全取消实物",
    wh + ["- $ref: '#/components/parameters/CommandId'"])
post("/internal/wms/v1/warehouses/{warehouseId}/stock-commands/{commandId}/cancellations",
     "cancelStockCommand", "internal-inventory", "inventory.command",
     "StockCommandCancelRequest", ("200",), "库存命令取消/墓碑",
     wh + ["- $ref: '#/components/parameters/CommandId'"])
post("/internal/wms/v1/warehouses/{warehouseId}/execution-permits", "prepareExecutionPermit",
     "internal-inventory", "inventory.permit", "ExecutionPermitRequest", ("201",),
     "申请PREPARED执行许可", wh)
post("/internal/wms/v1/warehouses/{warehouseId}/execution-permits/{permitId}/starts",
     "startExecutionPermit", "internal-inventory", "inventory.permit",
     "ExecutionPermitStartRequest", ("200",), "开始执行许可",
     wh + ["- $ref: '#/components/parameters/PermitId'"])

# 已有 HTTP 入口补齐契约；复用作业台的现有 scope，不新增第二套角色语义。
get("/api/wms/v1/fulfillments", "listFulfillments", "fulfillment", "fulfillment.read", ("200",), "履约列表", cursor)
post("/api/wms/v1/fulfillments/{fulfillmentId}/attempts", "prepareAttempt", "fulfillment", "fulfillment.execute", "PrepareAttemptRequest", ("201",), "准备参与仓分配", ["- $ref: '#/components/parameters/FulfillmentId'"])
post("/api/wms/v1/warehouses/{warehouseId}/outbound-orders", "createOutboundOrder", "outbound", "fulfillment.execute", "OutboundCreateRequest", ("201",), "登记待授权出库单", wh)
post("/api/wms/v1/warehouses/{warehouseId}/outbound-orders/{outboundOrderId}/pick-tasks", "planPickTasks", "outbound", "outbound.pick", "PlanPickRequest", ("201",), "规划拣货任务", wh + ["- $ref: '#/components/parameters/OutboundOrderId'"])
post("/api/wms/v1/warehouses/{warehouseId}/outbound-orders/{outboundOrderId}/cancellations", "cancelUnpicked", "outbound", "outbound.pick", "CancelUnpickedRequest", ("202",), "取消未拣数量", wh + ["- $ref: '#/components/parameters/OutboundOrderId'"])
for suffix, op, scope, body in [("reviews", "reviewCount", "count.record", None), ("approvals", "approveCount", "adjustment.approve", "CountApproveRequest"), ("applications", "applyCount", "adjustment.apply", "CountApplyRequest")]:
    post("/api/wms/v1/warehouses/{warehouseId}/count-plans/{countPlanId}/" + suffix, op, "count", scope, body, ("202",) if suffix == "applications" else ("200",), "盘点作业", wh + ["- $ref: '#/components/parameters/CountPlanId'"])
get("/api/wms/v1/warehouses/{warehouseId}/action-effects", "listActionEffects", "idempotency", "task.read", ("200",), "效果列表", wh + cursor)
get("/api/wms/v1/jobs", "listJobs", "job", "job.read", ("200",), "仓任务列表", cursor + ["- in: query\n          name: warehouseId\n          required: true\n          schema: { type: string, maxLength: 64 }"])
get("/api/wms/v1/reconciliation-cases", "listReconciliationCasesByQuery", "recon", "recon.read", ("200",), "指定单仓差异单", cursor + ["- $ref: '#/components/parameters/WarehouseIdsQuery'"])
post("/api/wms/v1/reconciliation-cases/{id}/remediations", "remediateReconciliationByQuery", "recon", "recon.remediate", "RemediationRequest", ("202",), "差异单修复", ["- in: path\n          name: id\n          required: true\n          schema: { type: string }", "- in: query\n          name: warehouseId\n          required: true\n          schema: { type: string }"])
for suffix, op, scope in [("issues", "issueTransfer", "transfer.create"), ("losses", "confirmTransferLoss", "stock.move")]:
    post("/api/wms/v1/transfers/{transferId}/" + suffix, op, "transfer", scope, "TransferPartRequest", ("202",), "调拨数量作业", ["- $ref: '#/components/parameters/TransferId'"])

get("/api/wms/v1/warehouses/{warehouseId}/message-queues/{queue}/messages", "listOperationalMessages", "common",
    "messaging.read", ("200",), "本服务本库消息元数据列表，不返回消息正文",
    wh + ["- $ref: '#/components/parameters/MessageQueue'", "- $ref: '#/components/parameters/MessageState'"] + cursor)
post("/api/wms/v1/warehouses/{warehouseId}/message-queues/{queue}/messages/{messageId}/retries", "retryIsolatedMessage", "common",
     "messaging.recover", "MessageRetryRequest", ("202",), "原消息受审计重新排队，保持身份且不重置领取代际",
     wh + ["- $ref: '#/components/parameters/MessageQueue'", "- $ref: '#/components/parameters/MessageId'"], success_schema="MessageRecoveryAccepted")

for suffix, operation in [("claims", "claimSerialIdentity"), ("activations", "activateSerialIdentity")]:
    post("/internal/wms/v1/serial-identities/" + suffix, operation, "internal-registry", "serial.registry.write",
         "SerialIdentityCommand", ("200",), "仅受信服务主体调用，JWT企业仓范围和原操作引用必需", ["- $ref: '#/components/parameters/SerialEnterpriseHeader'"], success_schema="SerialIdentity")
get("/internal/wms/v1/serial-identities", "getSerialIdentity", "internal-registry", "serial.registry.read", ("200",),
    "查询实时全局登记，检查当前归属仓权限", ["- $ref: '#/components/parameters/SerialEnterpriseHeader'", "- $ref: '#/components/parameters/SerialSkuQuery'", "- $ref: '#/components/parameters/SerialQuery'"], success_schema="SerialIdentity")

header = """openapi: 3.1.0
info:
  title: WMS v1 HTTP contract
  version: 0.1.0
  description: |
    公开前缀 `/api/wms/v1`，内部前缀 `/internal/wms/v1`。
    enterpriseId/operator 来自已验证 OIDC token；请求中的企业字段仅核验一致性，不能扩大权限。
    数量 JSON 为十进制字符串。时间 RFC3339 UTC。列表 cursor 分页，默认 50、上限 200。
    写入头必须带 Authorization、Idempotency-Key、X-Request-Id；clientOperationId 若出现必须与 Idempotency-Key 一致。
    Seata TCC Try/Confirm/Cancel 不是 REST 资源，不提供自定义 /prepare 或 /decisions。
    OIDC issuer/client/权限映射由环境变量配置，本文件不含密钥。
    本契约覆盖 S1 主数据与后续切片接口形状；未实现切片不得把 200 当成业务已交付。
servers:
  - url: /
security:
  - oidc: []
tags:
  - name: masterdata
  - name: inbound
  - name: outbound
  - name: inventory
  - name: fulfillment
  - name: task
  - name: job
  - name: recon
  - name: idempotency
  - name: common
  - name: internal-registry
  - name: internal-inventory
paths:
"""

components = r"""
components:
  securitySchemes:
    oidc:
      type: openIdConnect
      description: issuer 与 client 由 WMS_OIDC_ISSUER / WMS_OIDC_CLIENT_ID 指向已有提供方，仓库不写死密钥
      openIdConnectUrl: https://issuer.example.invalid/.well-known/openid-configuration
  parameters:
    SerialEnterpriseHeader:
      name: X-Wms-Enterprise-Id
      in: header
      required: true
      description: 调用方预期企业，必须与签名JWT企业相同，避免误用另一租户服务令牌
      schema: { $ref: '#/components/schemas/Id' }
    SerialSkuQuery:
      name: skuId
      in: query
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    SerialQuery:
      name: serial
      in: query
      required: true
      schema: { type: string, minLength: 1, maxLength: 128 }
    MessageQueue:
      name: queue
      in: path
      required: true
      schema: { type: string, enum: [INBOX, OUTBOX] }
    MessageId:
      name: messageId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    MessageState:
      name: status
      in: query
      schema: { type: string, enum: [PENDING, CLAIMED, ISOLATED], default: ISOLATED }
    IdempotencyKey:
      name: Idempotency-Key
      in: header
      required: true
      schema: { type: string, minLength: 1, maxLength: 64 }
      description: 与 clientOperationId 一致；同键同内容返回原结果，同键异内容 409
    XRequestId:
      name: X-Request-Id
      in: header
      required: true
      schema: { type: string, minLength: 8, maxLength: 128 }
    IfMatch:
      name: If-Match
      in: header
      required: false
      schema: { type: string }
    Cursor:
      description: 不透明游标，绑定原查询条件；更换筛选或投影世代后从第一页重新读取。
      name: cursor
      in: query
      required: false
      schema: { type: string }
    Limit:
      name: limit
      in: query
      required: false
      schema: { type: integer, minimum: 1, maximum: 200, default: 50 }
    WarehouseId:
      name: warehouseId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    FulfillmentId:
      name: fulfillmentId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    InboundOrderId:
      name: inboundOrderId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    OutboundOrderId:
      name: outboundOrderId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    TaskId:
      name: taskId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    InspectionId:
      name: inspectionId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    HoldId:
      name: holdId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    TransferId:
      name: transferId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    CountPlanId:
      name: countPlanId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    AdjustmentId:
      name: adjustmentId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    BalanceId:
      name: balanceId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    OperationId:
      name: operationId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    JobId:
      name: jobId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    AfterPart:
      name: afterPart
      in: query
      schema:
        type: integer
        minimum: 0
        default: 0
    SnapshotId:
      name: snapshotId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    CaseId:
      name: caseId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    EffectId:
      name: effectId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    LocationId:
      name: locationId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    SkuId:
      name: skuId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    LotId:
      name: lotId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    CommandId:
      name: commandId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    PermitId:
      name: permitId
      in: path
      required: true
      schema: { $ref: '#/components/schemas/Id' }
    TaskType:
      name: taskType
      in: query
      required: true
      schema: { type: string }
    SkuIdQuery:
      name: skuId
      in: query
      required: false
      schema: { $ref: '#/components/schemas/Id' }
    LotIdQuery:
      name: lotId
      in: query
      required: false
      schema: { $ref: '#/components/schemas/Id' }
    QualityCodeQuery:
      name: qualityCode
      in: query
      required: false
      schema: { type: string }
    WarehouseIdsQuery:
      description: 当前单次查询只支持一个仓库；跨仓分别读取并保留各仓水位。
      name: warehouseIds
      in: query
      required: true
      schema:
        type: array
        minItems: 1
        maxItems: 1
        items: { $ref: '#/components/schemas/Id' }
  responses:
    Ok:
      description: 成功
    Created:
      description: 已创建
    Accepted:
      description: 已受理
    Error:
      description: 业务或协议错误
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
  schemas:
    Id:
      type: string
      minLength: 1
      maxLength: 64
    Quantity:
      type: string
      pattern: '^-?\d+(\.\d+)?$'
      description: 十进制字符串，禁止用JSON number做库存量
    Version:
      type: integer
      format: int64
      minimum: 0
    ErrorResponse:
      type: object
      additionalProperties: false
      required: [code, message, requestId, retryable]
      properties:
        code:
          type: string
          enum:
            - SERIAL_ACCESS_FORBIDDEN
            - SERIAL_NOT_FOUND
            - SERIAL_OWNER_MISMATCH
            - SERIAL_OPERATION_MISMATCH
            - SERIAL_ALREADY_CLAIMED
            - SERIAL_STATE_CONFLICT
            - SERIAL_MISSING
            - INVALID_MESSAGE_QUEUE
            - INVALID_MESSAGE_STATE
            - INVALID_RECOVERY_REQUEST
            - MESSAGE_NOT_FOUND
            - MESSAGE_STATE_CONFLICT
            - MESSAGE_NOT_REPLAYABLE
            - RECOVERY_KEY_CONFLICT
            - MESSAGE_RECOVERY_FORBIDDEN
            - INVALID_QUANTITY
            - INVALID_UNIT
            - REQUIRED_LOT
            - INVALID_EXPIRY
            - UNAUTHENTICATED
            - WAREHOUSE_FORBIDDEN
            - RESOURCE_NOT_FOUND
            - IDEMPOTENCY_PAYLOAD_MISMATCH
            - VERSION_CONFLICT
            - STOCK_INSUFFICIENT
            - STOCK_FROZEN
            - EXPIRED_STOCK
            - SERIAL_OWNERSHIP_CONFLICT
            - STALE_OWNER_EPOCH
            - TCC_TRANSACTION_PENDING
            - INVALID_STATE
            - TCC_CONTEXT_MISMATCH
            - TCC_OWNER_CONFLICT
            - ROUTE_EPOCH_STALE
            - RESOURCE_BUDGET_EXCEEDED
            - DEPENDENCY_UNAVAILABLE
            - EFFECT_IDENTITY_CONFLICT
            - STALE_EXECUTION_ATTEMPT
            - EFFECT_ALREADY_APPLIED
            - RECOVERY_PENDING
            - INVALID_ARGUMENT
            - INVALID_PAGE
            - DATABASE_UNAVAILABLE
            - QUERY_OVERLOADED
            - RATE_LIMITED
            - DUPLICATE_DOCUMENT
            - DUPLICATE_INSPECTION
        message: { type: string }
        requestId: { type: string }
        operationId: { $ref: '#/components/schemas/Id' }
        retryable: { type: boolean }
        details:
          type: array
          items:
            type: object
            additionalProperties: false
            properties:
              field: { type: string }
              code: { type: string }
    CursorPage:
      type: object
      additionalProperties: false
      required: [items, limit]
      properties:
        items:
          type: array
          items: { $ref: '#/components/schemas/ResourceEnvelope' }
        nextCursor: { type: string }
        limit: { type: integer, minimum: 1, maximum: 200 }
        asOf: { type: string, format: date-time }
        lagSeconds: { type: integer, minimum: 0 }
    ResourceEnvelope:
      type: object
      additionalProperties: true
      required: [id, version]
      properties:
        id: { $ref: '#/components/schemas/Id' }
        version: { $ref: '#/components/schemas/Version' }
        status: { type: string }
        actions:
          type: array
          items: { type: string }
          description: 交互提示，写入仍以服务端校验为准
    AcceptedOperation:
      type: object
      additionalProperties: false
      required: [operationId, status, statusUrl]
      properties:
        operationId: { $ref: '#/components/schemas/Id' }
        sourceOperationId: { $ref: '#/components/schemas/Id' }
        commandId: { $ref: '#/components/schemas/Id' }
        fulfillmentId: { $ref: '#/components/schemas/Id' }
        receiptId: { $ref: '#/components/schemas/Id' }
        executionId: { $ref: '#/components/schemas/Id' }
        physicalStatus: { type: string }
        stockSyncStatus: { type: string }
        status: { type: string }
        version: { $ref: '#/components/schemas/Version' }
        statusUrl: { type: string }
        safeToRetry: { type: boolean }
    DeviceContext:
      type: object
      additionalProperties: false
      required: [deviceId, scanSessionId, scanSequence]
      properties:
        deviceId: { $ref: '#/components/schemas/Id' }
        scanSessionId: { $ref: '#/components/schemas/Id' }
        scanSequence: { type: integer, minimum: 0 }
    LotInput:
      type: object
      additionalProperties: false
      required: [lotCode]
      properties:
        lotCode: { $ref: '#/components/schemas/Id' }
        producedAt: { type: string, format: date-time }
        expiresAt: { type: string, format: date-time }
        sourceDate: { type: string }
        expiryRuleVersion: { $ref: '#/components/schemas/Version' }
    VersionedReasonRequest:
      type: object
      additionalProperties: false
      required: [expectedVersion]
      properties:
        reason: { type: string }
        expectedVersion: { $ref: '#/components/schemas/Version' }
        clientOperationId: { $ref: '#/components/schemas/Id' }
    FulfillmentCreateRequest:
      type: "object"
      additionalProperties: false
      required: ["sourceSystem","sourceOrderNo","lines"]
      properties:
        sourceSystem:
          type: "string"
          maxLength: 64
          minLength: 1
        sourceOrderNo:
          type: "string"
          maxLength: 64
          minLength: 1
        digest:
          type: "string"
          maxLength: 64
        lines:
          type: "array"
          items:
            $ref: "#/components/schemas/FulfillmentLine"
          minItems: 1
          maxItems: 200
        strategyVersion:
          type: "integer"
          minimum: 0
    ExecutionAuthorizationRequest:
      type: "object"
      additionalProperties: false
      required: ["attemptId","authorizationId","xid","tcTerminalEvidenceRef","participantSetHash"]
      properties:
        clientOperationId:
          type: "string"
          maxLength: 64
        attemptId:
          type: "string"
          maxLength: 64
          minLength: 1
        authorizationId:
          type: "string"
          maxLength: 64
          minLength: 1
        xid:
          type: "string"
          maxLength: 128
          minLength: 1
        tcTerminalEvidenceRef:
          type: "string"
          maxLength: 256
          minLength: 1
        participantSetHash:
          type: "string"
          maxLength: 64
          minLength: 1
    InboundOrderCreateRequest:
      type: "object"
      additionalProperties: false
      required: ["sourceSystem","externalNo","ownerId","lines"]
      properties:
        inboundOrderId:
          type: "string"
          maxLength: 64
        sourceSystem:
          type: "string"
          maxLength: 64
          minLength: 1
        externalNo:
          type: "string"
          maxLength: 64
          minLength: 1
        ownerId:
          type: "string"
          maxLength: 64
          minLength: 1
        lines:
          type: "array"
          items:
            $ref: "#/components/schemas/InboundLine"
          minItems: 1
          maxItems: 200
    ReceiptRequest:
      type: "object"
      additionalProperties: false
      required: ["lineId","qty"]
      properties:
        lineId:
          type: "string"
          maxLength: 64
          minLength: 1
        locationId:
          type: "string"
          maxLength: 64
          description: "收货现场库位；消息链路启用时必填，与lotId成组提供"
        lotId:
          type: "string"
          maxLength: 64
          description: "明确批次标识；无批次SKU显式使用NO_LOT，由库存主数据校验"
        clientOperationId:
          type: "string"
          maxLength: 64
        receiptPartId:
          type: "string"
          maxLength: 64
        receiptSessionId:
          type: "string"
          maxLength: 64
        deviceId:
          type: "string"
          maxLength: 64
        deviceSessionId:
          type: "string"
          maxLength: 64
        scanSequence:
          type: "integer"
          minimum: 0
        qty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0"
    QualityResultRequest:
      type: "object"
      additionalProperties: false
      required: ["lineId","acceptedQty","rejectedQty"]
      properties:
        lineId:
          type: "string"
          maxLength: 64
          minLength: 1
        acceptedQty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；不得小于0"
        rejectedQty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；不得小于0"
        sourceVersion:
          type: "integer"
          minimum: 1
    PutawayRequest:
      type: "object"
      additionalProperties: false
      required: ["inboundOrderId","lineId","qty"]
      properties:
        inboundOrderId:
          type: "string"
          maxLength: 64
          minLength: 1
        lineId:
          type: "string"
          maxLength: 64
          minLength: 1
        locationId:
          type: "string"
          maxLength: 64
        targetLocationId:
          type: "string"
          maxLength: 64
        locationType:
          type: "string"
          maxLength: 64
        qty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0"
        clientOperationId:
          type: "string"
          maxLength: 64
    PickRequest:
      type: "object"
      additionalProperties: false
      required: ["qty"]
      properties:
        pickPartId:
          type: string
          maxLength: 64
          description: 分批事实身份；省略时沿用命令键，同分批换键重试应携带原身份。
        clientOperationId:
          type: "string"
          maxLength: 64
        qty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0"
    PackingRequest:
      type: "object"
      additionalProperties: false
      required: ["orderLineId","qty"]
      properties:
        orderLineId:
          type: "string"
          maxLength: 64
          minLength: 1
        packageNo:
          type: "string"
          maxLength: 64
        qty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0"
    ShipmentRequest:
      type: "object"
      additionalProperties: false
      required: ["orderLineId","qty"]
      properties:
        shipmentPartId:
          type: string
          maxLength: 64
          description: 分批事实身份；省略时沿用命令键，同分批换键重试应携带原身份。
        orderLineId:
          type: "string"
          maxLength: 64
          minLength: 1
        clientOperationId:
          type: "string"
          maxLength: 64
        qty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0"
    MoveRequest:
      type: "object"
      additionalProperties: false
      required: ["sourceBalanceId","targetLocationId","qty","reason"]
      properties:
        clientOperationId:
          type: "string"
          maxLength: 64
        sourceBalanceId:
          type: "string"
          maxLength: 64
          minLength: 1
        targetLocationId:
          type: "string"
          maxLength: 64
          minLength: 1
        qty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0"
        unit:
          type: "string"
          maxLength: 64
        reason:
          type: "string"
          maxLength: 128
          minLength: 1
    StockHoldRequest:
      type: "object"
      additionalProperties: false
      required: ["scope","reason"]
      properties:
        clientOperationId:
          type: "string"
          maxLength: 64
        scope:
          $ref: "#/components/schemas/HoldScope"
        qty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0"
        reason:
          type: "string"
          maxLength: 128
          minLength: 1
        evidenceRefs:
          type: "array"
          items:
            type: "string"
            minLength: 1
            maxLength: 512
          maxItems: 200
    TransferCreateRequest:
      type: "object"
      additionalProperties: false
      required: ["sourceWarehouseId","targetWarehouseId","lines"]
      properties:
        transferId:
          type: "string"
          maxLength: 64
        sourceWarehouseId:
          type: "string"
          maxLength: 64
          minLength: 1
        targetWarehouseId:
          type: "string"
          maxLength: 64
          minLength: 1
        lines:
          type: "array"
          items:
            $ref: "#/components/schemas/TransferLine"
          minItems: 1
          maxItems: 200
    ReceiptAuthorizationRequest:
      type: "object"
      additionalProperties: false
      properties:
        lineId:
          type: "string"
          maxLength: 64
        transferLineId:
          type: "string"
          maxLength: 64
        targetClientOperationId:
          type: "string"
          maxLength: 64
        clientOperationId:
          type: "string"
          maxLength: 64
        quantity:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0"
        qty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0"
    TransferReceiptRequest:
      type: "object"
      additionalProperties: false
      required: ["transferId","authorizationId","tokenVersion","qty"]
      properties:
        transferId:
          type: "string"
          maxLength: 64
          minLength: 1
        lineId:
          type: "string"
          maxLength: 64
        sourceLineRef:
          type: "string"
          maxLength: 64
        clientOperationId:
          type: "string"
          maxLength: 64
        authorizationId:
          type: "string"
          maxLength: 64
          minLength: 1
        tokenVersion:
          type: "integer"
          minimum: 0
        qty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0"
        targetLotId:
          type: "string"
          maxLength: 64
          minLength: 1
    CountPlanCreateRequest:
      type: "object"
      additionalProperties: false
      required: ["locationIds"]
      properties:
        planId:
          type: "string"
          maxLength: 64
        countPlanId:
          type: "string"
          maxLength: 64
        reason:
          type: "string"
          maxLength: 32
        locationIds:
          type: "array"
          items:
            type: "string"
            minLength: 1
            maxLength: 64
          minItems: 1
          maxItems: 200
    CountObservationRequest:
      type: "object"
      additionalProperties: false
      required: ["lineId","qty"]
      properties:
        lineId:
          type: "string"
          maxLength: 64
          minLength: 1
        observationId:
          type: "string"
          maxLength: 64
        qty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；不得小于0"
        roundNo:
          type: "integer"
          minimum: 1
    AdjustmentCreateRequest:
      type: "object"
      additionalProperties: false
      required: ["balanceId","deltaQty","reason"]
      properties:
        clientOperationId:
          type: "string"
          maxLength: 64
        balanceId:
          type: "string"
          maxLength: 64
          minLength: 1
        deltaQty:
          type: "string"
          pattern: "^-?[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；可正可负"
        reason:
          type: "string"
          maxLength: 128
          minLength: 1
        countLineId:
          type: "string"
          maxLength: 64
        evidenceRefs:
          type: "array"
          items:
            type: "string"
            minLength: 1
            maxLength: 512
          maxItems: 200
        serialActions:
          type: "array"
          items:
            type: "string"
            minLength: 1
            maxLength: 512
          maxItems: 0
          description: "独立调整不处理序列号身份；非空请求应走盘点流程。"
    AdjustmentApprovalRequest:
      type: "object"
      additionalProperties: false
      required: ["decision","expectedVersion"]
      properties:
        clientOperationId:
          type: "string"
          maxLength: 64
        decision:
          type: "string"
          maxLength: 64
          minLength: 1
        reason:
          type: "string"
          maxLength: 128
        expectedVersion:
          type: "integer"
          minimum: 0
    AdjustmentApplyRequest:
      type: "object"
      additionalProperties: false
      required: ["expectedVersion"]
      properties:
        clientOperationId:
          type: "string"
          maxLength: 64
        expectedVersion:
          type: "integer"
          minimum: 0
    JobRetryRequest:
      type: "object"
      additionalProperties: false
      properties:
        action:
          type: "string"
          maxLength: 64
          pattern: "(?i)RECLAIM|CLAIM|TAKEOVER"
    ReconciliationSnapshotRequest:
      type: "object"
      additionalProperties: false
      required: ["warehouseIds","cutoffId","cutoff"]
      properties:
        warehouseIds:
          type: "array"
          items:
            type: "string"
            minLength: 1
            maxLength: 64
          minItems: 1
          maxItems: 1
        cutoffId:
          type: "string"
          maxLength: 64
          minLength: 1
        cutoff:
          type: "string"
          maxLength: 64
          minLength: 1
          format: "date-time"
        sourceWatermark:
          type: "string"
          maxLength: 64
        postingWatermark:
          type: "string"
          maxLength: 64
        receiptWatermark:
          type: "string"
          maxLength: 64
    RemediationRequest:
      type: object
      additionalProperties: false
      required: [approvedAction, reason, expectedVersion]
      properties:
        approvedAction: { type: string }
        reason: { type: string }
        expectedVersion: { $ref: '#/components/schemas/Version' }
    ActionEffectRequest:
      type: object
      additionalProperties: false
      required: [factType, factParentId, factPartId, factLineId, action]
      properties:
        factType: { type: string }
        factParentId: { $ref: '#/components/schemas/Id' }
        factPartId: { $ref: '#/components/schemas/Id' }
        factLineId: { $ref: '#/components/schemas/Id' }
        action: { type: string }
        digestVersion: { $ref: '#/components/schemas/Version' }
        clientOperationId: { $ref: '#/components/schemas/Id' }
    ExecutionAttemptRequest:
      type: "object"
      additionalProperties: false
      required: ["expectedEffectVersion"]
      properties:
        clientOperationId:
          type: "string"
          maxLength: 64
        expectedEffectVersion:
          type: "integer"
          minimum: 0
        digestVersion:
          type: "integer"
          minimum: 0
        previousCommandId:
          type: "string"
          maxLength: 64
    WarehouseCreateRequest:
      type: "object"
      additionalProperties: false
      required: ["code","name","timezone"]
      properties:
        clientOperationId:
          type: "string"
          maxLength: 64
        code:
          type: "string"
          maxLength: 32
          minLength: 1
        name:
          type: "string"
          maxLength: 128
          minLength: 1
        timezone:
          type: "string"
          maxLength: 64
          minLength: 1
    LocationCreateRequest:
      type: "object"
      additionalProperties: false
      required: ["code","zoneCode","locationType"]
      properties:
        clientOperationId:
          type: "string"
          maxLength: 64
        code:
          type: "string"
          maxLength: 32
          minLength: 1
        zoneCode:
          type: "string"
          maxLength: 32
          minLength: 1
        locationType:
          type: "string"
          maxLength: 32
          minLength: 1
        capacityQty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；不得小于0"
        capacityUnit:
          type: "string"
          maxLength: 32
    SkuCreateRequest:
      type: "object"
      additionalProperties: false
      required: ["code","name","baseUnit","quantityScale"]
      properties:
        clientOperationId:
          type: "string"
          maxLength: 64
        code:
          type: "string"
          maxLength: 64
          minLength: 1
        name:
          type: "string"
          maxLength: 128
          minLength: 1
        baseUnit:
          type: "string"
          maxLength: 32
          minLength: 1
        quantityScale:
          type: "integer"
          minimum: 0
          maximum: 6
        lotEnabled:
          type: "boolean"
        serialEnabled:
          type: "boolean"
        expiryEnabled:
          type: "boolean"
    SkuUnitCreateRequest:
      type: "object"
      additionalProperties: false
      required: ["unitCode","numerator","denominator"]
      properties:
        clientOperationId:
          type: "string"
          maxLength: 64
        unitCode:
          type: "string"
          maxLength: 32
          minLength: 1
        numerator:
          type: "string"
          pattern: "^[0-9]{1,14}$"
          description: "精确十进制；必须大于0"
        denominator:
          type: "string"
          pattern: "^[0-9]{1,14}$"
          description: "精确十进制；必须大于0"
        sampleQuantity:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；不得小于0"
    LotCreateRequest:
      type: "object"
      additionalProperties: false
      required: ["ownerId","skuId","lotCode","businessLotKey"]
      properties:
        clientOperationId:
          type: "string"
          maxLength: 64
        ownerId:
          type: "string"
          maxLength: 64
          minLength: 1
        skuId:
          type: "string"
          maxLength: 64
          minLength: 1
        lotCode:
          type: "string"
          maxLength: 64
          minLength: 1
        businessLotKey:
          type: "string"
          maxLength: 64
          minLength: 1
        producedAt:
          type: "string"
          maxLength: 64
          format: "date-time"
        expiresAt:
          type: "string"
          maxLength: 64
          format: "date-time"
        sourceDate:
          type: "string"
          maxLength: 32
        expiryRuleVersion:
          type: "integer"
          minimum: 0
    InboundLine:
      type: "object"
      additionalProperties: false
      required: ["externalLineId","skuId","expectedQty"]
      properties:
        lineId:
          type: "string"
          maxLength: 64
        externalLineId:
          type: "string"
          maxLength: 64
          minLength: 1
        skuId:
          type: "string"
          maxLength: 64
          minLength: 1
        expectedQty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0"
        unit:
          type: "string"
          maxLength: 32
    FulfillmentLine:
      type: "object"
      additionalProperties: false
      required: ["sourceLineId","skuId","requestedQty","baseUnit"]
      properties:
        sourceLineId:
          type: "string"
          maxLength: 64
          minLength: 1
          description: "兼容旧字段名 lineId"
        skuId:
          type: "string"
          maxLength: 64
          minLength: 1
        requestedQty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0；兼容旧字段名 qty"
        baseUnit:
          type: "string"
          maxLength: 32
          minLength: 1
          description: "兼容旧字段名 unit"
    TransferLine:
      type: "object"
      additionalProperties: false
      required: ["lineId","skuId","plannedQty"]
      properties:
        lineId:
          type: "string"
          maxLength: 64
          minLength: 1
          description: "兼容旧字段名 sourceLineId"
        skuId:
          type: "string"
          maxLength: 64
          minLength: 1
        plannedQty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0；兼容旧字段名 qty"
        businessLotKey:
          type: "string"
          maxLength: 64
        sourceLotId:
          type: "string"
          maxLength: 64
    HoldScope:
      type: "object"
      additionalProperties: false
      required: ["balanceId"]
      properties:
        balanceId:
          type: "string"
          maxLength: 64
          minLength: 1
        qty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0"
    PrepareAttemptRequest:
      type: "object"
      additionalProperties: false
      required: ["lines"]
      properties:
        clientOperationId:
          type: "string"
          maxLength: 64
        deadline:
          type: "string"
          maxLength: 64
          format: "date-time"
        warehouses:
          type: "array"
          items:
            type: "string"
            minLength: 1
            maxLength: 64
          maxItems: 200
        lines:
          type: "array"
          items:
            $ref: "#/components/schemas/AttemptLine"
          minItems: 1
          maxItems: 200
    OutboundCreateRequest:
      type: "object"
      additionalProperties: false
      required: ["allocationId","ownerId","lines"]
      properties:
        allocationId:
          type: "string"
          maxLength: 64
          minLength: 1
        attemptId:
          type: "string"
          maxLength: 64
        ownerId:
          type: "string"
          maxLength: 64
          minLength: 1
        authorizationId:
          type: "string"
          maxLength: 64
        lines:
          type: "array"
          items:
            $ref: "#/components/schemas/OutboundLine"
          minItems: 1
          maxItems: 200
    PlanPickRequest:
      type: "object"
      additionalProperties: false
      required: ["orderLineId","sourceLocationId","stagingLocationId","qty"]
      properties:
        orderLineId:
          type: "string"
          maxLength: 64
          minLength: 1
        sourceLocationId:
          type: "string"
          maxLength: 64
          minLength: 1
        stagingLocationId:
          type: "string"
          maxLength: 64
          minLength: 1
        qty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0"
        clientOperationId:
          type: "string"
          maxLength: 64
    CancelUnpickedRequest:
      type: "object"
      additionalProperties: false
      required: ["orderLineId"]
      properties:
        orderLineId:
          type: "string"
          maxLength: 64
          minLength: 1
        clientOperationId:
          type: "string"
          maxLength: 64
    CountApproveRequest:
      type: "object"
      additionalProperties: false
      properties:
        approvalId:
          type: "string"
          maxLength: 64
    CountApplyRequest:
      type: "object"
      additionalProperties: false
      required: ["lineId"]
      properties:
        lineId:
          type: "string"
          maxLength: 64
          minLength: 1
        clientOperationId:
          type: "string"
          maxLength: 64
    TransferPartRequest:
      type: "object"
      additionalProperties: false
      required: ["qty"]
      properties:
        lineId:
          type: "string"
          maxLength: 64
        transferLineId:
          type: "string"
          maxLength: 64
        clientOperationId:
          type: "string"
          maxLength: 64
        qty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0"
    AttemptLine:
      type: "object"
      additionalProperties: false
      required: ["orderLineId","skuId","warehouseId","qty","baseUnit"]
      properties:
        orderLineId:
          type: "string"
          maxLength: 64
          minLength: 1
          description: "兼容旧字段名 sourceLineId"
        skuId:
          type: "string"
          maxLength: 64
          minLength: 1
        warehouseId:
          type: "string"
          maxLength: 64
          minLength: 1
        qty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0"
        baseUnit:
          type: "string"
          maxLength: 32
          minLength: 1
          description: "兼容旧字段名 unit"
    OutboundLine:
      type: "object"
      additionalProperties: false
      required: ["orderLineId","skuId","qty"]
      properties:
        orderLineId:
          type: "string"
          maxLength: 64
          minLength: 1
        skuId:
          type: "string"
          maxLength: 64
          minLength: 1
        qty:
          type: "string"
          pattern: "^[0-9]{1,14}([.][0-9]{1,6})?$"
          description: "精确十进制；必须大于0"
        baseUnit:
          type: "string"
          maxLength: 32
    StockCommandRequest:
      type: object
      additionalProperties: false
      required: [commandId, sourceService, sourceExecutionId, businessEffectKey, payloadDigest, action, quantity]
      properties:
        commandId: { $ref: '#/components/schemas/Id' }
        sourceService: { type: string, enum: [inbound, outbound, inventory] }
        sourceExecutionId: { $ref: '#/components/schemas/Id' }
        businessEffectKey: { $ref: '#/components/schemas/Id' }
        payloadDigest: { type: string }
        action: { type: string }
        quantity: { $ref: '#/components/schemas/Quantity' }
        permitRef: { $ref: '#/components/schemas/Id' }
        authorizationRef: { $ref: '#/components/schemas/Id' }
        digestVersion: { $ref: '#/components/schemas/Version' }
        executionAttemptId: { $ref: '#/components/schemas/Id' }
        attemptNo: { type: integer, minimum: 1 }
        previousCommandId: { $ref: '#/components/schemas/Id' }
    StockCommandCancelRequest:
      type: object
      additionalProperties: false
      required: [sourceService, sourceVersion, reason]
      properties:
        sourceService: { type: string }
        sourceVersion: { $ref: '#/components/schemas/Version' }
        notExecutedEvidence: { type: string }
        reason: { type: string }
        digestVersion: { $ref: '#/components/schemas/Version' }
    ExecutionPermitRequest:
      type: object
      additionalProperties: false
      required: [commandId, sourceTaskId, taskEpoch, action, qty, payloadDigest]
      properties:
        commandId: { $ref: '#/components/schemas/Id' }
        sourceTaskId: { $ref: '#/components/schemas/Id' }
        taskEpoch: { $ref: '#/components/schemas/Version' }
        action: { type: string }
        qty: { $ref: '#/components/schemas/Quantity' }
        payloadDigest: { type: string }
        stockRefs:
          type: array
          items: { $ref: '#/components/schemas/Id' }
        serialIds:
          type: array
          items: { $ref: '#/components/schemas/Id' }
        digestVersion: { $ref: '#/components/schemas/Version' }
        businessEffectKey: { $ref: '#/components/schemas/Id' }
        executionAttemptId: { $ref: '#/components/schemas/Id' }
        attemptNo: { type: integer, minimum: 1 }
        previousCommandId: { $ref: '#/components/schemas/Id' }
    SerialIdentityCommand:
      type: object
      additionalProperties: false
      required: [warehouseId, skuId, serial, operationId]
      properties:
        warehouseId: { $ref: '#/components/schemas/Id' }
        skuId: { $ref: '#/components/schemas/Id' }
        serial: { type: string, minLength: 1, maxLength: 128 }
        operationId: { $ref: '#/components/schemas/Id' }
    SerialIdentity:
      type: object
      additionalProperties: false
      required: [id, state, normalizedSerial, ownerWarehouseId, ownerEpoch, claimOperationId, routeBucket, version]
      properties:
        id: { $ref: '#/components/schemas/Id' }
        state: { type: string }
        normalizedSerial: { type: string }
        ownerWarehouseId: { $ref: '#/components/schemas/Id' }
        ownerEpoch: { type: integer, format: int64 }
        claimOperationId: { $ref: '#/components/schemas/Id' }
        routeBucket: { type: integer, minimum: 0, maximum: 63 }
        transferId: { type: [string, 'null'] }
        receiptOperationId: { type: [string, 'null'] }
        version: { $ref: '#/components/schemas/Version' }
    MessageRecoveryAccepted:
      type: object
      additionalProperties: false
      required: [recoveryId, messageId, state, replayed]
      properties:
        recoveryId: { $ref: '#/components/schemas/Id' }
        messageId: { $ref: '#/components/schemas/Id' }
        state: { type: string, enum: [RETRY_ACCEPTED] }
        replayed: { type: boolean }
    MessageRetryRequest:
      type: object
      additionalProperties: false
      required: [expectedEpoch, reason]
      properties:
        expectedEpoch: { type: integer, format: int64, minimum: 0 }
        reason: { type: string, minLength: 1, maxLength: 500 }
    ExecutionPermitStartRequest:
      type: object
      additionalProperties: false
      required: [commandId, taskEpoch, expectedVersion]
      properties:
        commandId: { $ref: '#/components/schemas/Id' }
        taskEpoch: { $ref: '#/components/schemas/Version' }
        expectedVersion: { $ref: '#/components/schemas/Version' }
        digestVersion: { $ref: '#/components/schemas/Version' }
"""

# Merge duplicate path keys: OpenAPI cannot repeat the same path. Group methods.
from collections import defaultdict
grouped = defaultdict(list)
current_path = None
blocks = []
for block in ops:
    first = block.splitlines()[0].strip()
    path = first[:-1].strip()
    rest = "\n".join(block.splitlines()[1:])
    grouped[path].append(rest)

path_yaml = []
for path, methods in grouped.items():
    path_yaml.append(f"  {path}:")
    path_yaml.append("\n".join(methods))

text = header + "\n".join(path_yaml) + components
# Strip trailing spaces
text = "\n".join(line.rstrip() for line in text.splitlines()) + "\n"
OUT.write_text(text)
print(f"wrote {OUT} paths={len(grouped)} bytes={OUT.stat().st_size}")

SCOPE_OUT.write_text("# Generated from OpenAPI operation scopes; do not edit by hand.\n" + "".join("\t".join(rule) + "\n" for rule in sorted(set(scope_rules)) if rule[1].startswith("/api/wms/")))
