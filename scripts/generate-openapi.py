#!/usr/bin/env python3
"""Generate committed OpenAPI from the approved contract table. Run from repo root."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "wms-contract/src/main/resources/openapi/wms-v1.yaml"


def responses(*codes, success_schema="ResourceEnvelope"):
    lines = ["      responses:"]
    for code in codes:
        desc = {
            "200": "成功",
            "201": "已创建资源",
            "202": "已持久化受理，不是业务终态成功",
        }.get(code, "错误")
        if code in ("200", "201", "202"):
            schema = "AcceptedOperation" if code == "202" else success_schema
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

def post(path, op, tag, scope, body, success, description, extra=None):
    ops.append(write_op("post", path, op, tag, scope, body, success, extra, description))


def get(path, op, tag, scope, success, description, extra=None):
    ops.append(write_op("get", path, op, tag, scope, None, success, extra, description))


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
     "recon.export", "ReconciliationSnapshotRequest", ("202",), "导出对账快照")
get("/api/wms/v1/reconciliation-snapshots/{snapshotId}", "getReconciliationSnapshot", "recon",
    "recon.read", ("200",), "读取快照manifest",
    ["- $ref: '#/components/parameters/SnapshotId'"])
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
    IdempotencyKey:
      name: Idempotency-Key
      in: header
      required: true
      schema: { type: string, minLength: 8, maxLength: 128 }
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
      name: warehouseIds
      in: query
      required: false
      schema:
        type: array
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
      type: object
      additionalProperties: false
      required: [sourceSystem, sourceOrderNo, strategyVersion, lines]
      properties:
        sourceSystem: { type: string }
        sourceOrderNo: { $ref: '#/components/schemas/Id' }
        strategyVersion: { $ref: '#/components/schemas/Version' }
        clientOperationId: { $ref: '#/components/schemas/Id' }
        lines:
          type: array
          minItems: 1
          maxItems: 200
          items:
            type: object
            additionalProperties: false
            required: [sourceLineId, skuId, quantity, unit]
            properties:
              sourceLineId: { $ref: '#/components/schemas/Id' }
              skuId: { $ref: '#/components/schemas/Id' }
              quantity: { $ref: '#/components/schemas/Quantity' }
              unit: { type: string }
              minRemainingDays: { type: integer, minimum: 0 }
    ExecutionAuthorizationRequest:
      type: object
      additionalProperties: false
      required: [attemptId, authorizationId, xid, tcTerminalEvidenceRef, participantSetHash]
      properties:
        attemptId: { $ref: '#/components/schemas/Id' }
        authorizationId: { $ref: '#/components/schemas/Id' }
        xid: { type: string }
        tcTerminalEvidenceRef: { type: string }
        participantSetHash: { type: string }
    InboundOrderCreateRequest:
      type: object
      additionalProperties: false
      required: [sourceSystem, externalNo, ownerId, lines]
      properties:
        sourceSystem: { type: string }
        externalNo: { $ref: '#/components/schemas/Id' }
        ownerId: { $ref: '#/components/schemas/Id' }
        expectedAt: { type: string, format: date-time }
        clientOperationId: { $ref: '#/components/schemas/Id' }
        lines:
          type: array
          minItems: 1
          maxItems: 200
          items:
            type: object
            additionalProperties: false
            required: [externalLineId, skuId, expectedQty, unit]
            properties:
              externalLineId: { $ref: '#/components/schemas/Id' }
              skuId: { $ref: '#/components/schemas/Id' }
              expectedQty: { $ref: '#/components/schemas/Quantity' }
              unit: { type: string }
    ReceiptRequest:
      type: object
      additionalProperties: false
      required: [clientOperationId, effectId, executionAttemptId, receiptSessionId, receiptPartId, lineId, qty, unit, locationId]
      properties:
        clientOperationId: { $ref: '#/components/schemas/Id' }
        effectId: { $ref: '#/components/schemas/Id' }
        executionAttemptId: { $ref: '#/components/schemas/Id' }
        receiptSessionId: { $ref: '#/components/schemas/Id' }
        receiptPartId: { $ref: '#/components/schemas/Id' }
        lineId: { $ref: '#/components/schemas/Id' }
        qty: { $ref: '#/components/schemas/Quantity' }
        unit: { type: string }
        locationId: { $ref: '#/components/schemas/Id' }
        lot: { $ref: '#/components/schemas/LotInput' }
        serialNumbers:
          type: array
          maxItems: 1000
          items: { type: string }
        deviceContext: { $ref: '#/components/schemas/DeviceContext' }
        digestVersion: { $ref: '#/components/schemas/Version' }
    QualityResultRequest:
      type: object
      additionalProperties: false
      required: [resultCode, acceptedQty, rejectedQty]
      properties:
        resultCode: { type: string }
        acceptedQty: { $ref: '#/components/schemas/Quantity' }
        rejectedQty: { $ref: '#/components/schemas/Quantity' }
        evidenceRefs:
          type: array
          items: { type: string }
        reason: { type: string }
        clientOperationId: { $ref: '#/components/schemas/Id' }
    PutawayRequest:
      type: object
      additionalProperties: false
      required: [clientOperationId, effectId, executionAttemptId, subActionId, claimEpoch, targetLocationId, qty, unit]
      properties:
        clientOperationId: { $ref: '#/components/schemas/Id' }
        effectId: { $ref: '#/components/schemas/Id' }
        executionAttemptId: { $ref: '#/components/schemas/Id' }
        subActionId: { $ref: '#/components/schemas/Id' }
        claimEpoch: { $ref: '#/components/schemas/Version' }
        targetLocationId: { $ref: '#/components/schemas/Id' }
        qty: { $ref: '#/components/schemas/Quantity' }
        unit: { type: string }
        serialIds:
          type: array
          maxItems: 1000
          items: { $ref: '#/components/schemas/Id' }
        digestVersion: { $ref: '#/components/schemas/Version' }
    PickRequest:
      type: object
      additionalProperties: false
      required: [clientOperationId, effectId, executionAttemptId, subActionId, claimEpoch, sourceLocationId, stagingLocationId, qty]
      properties:
        clientOperationId: { $ref: '#/components/schemas/Id' }
        effectId: { $ref: '#/components/schemas/Id' }
        executionAttemptId: { $ref: '#/components/schemas/Id' }
        subActionId: { $ref: '#/components/schemas/Id' }
        claimEpoch: { $ref: '#/components/schemas/Version' }
        sourceLocationId: { $ref: '#/components/schemas/Id' }
        stagingLocationId: { $ref: '#/components/schemas/Id' }
        qty: { $ref: '#/components/schemas/Quantity' }
        serialIds:
          type: array
          maxItems: 1000
          items: { $ref: '#/components/schemas/Id' }
        digestVersion: { $ref: '#/components/schemas/Version' }
    PackingRequest:
      type: object
      additionalProperties: false
      required: [packageId, lines]
      properties:
        packageId: { $ref: '#/components/schemas/Id' }
        clientOperationId: { $ref: '#/components/schemas/Id' }
        lines:
          type: array
          minItems: 1
          maxItems: 200
          items:
            type: object
            additionalProperties: false
            required: [outboundLineId, qty]
            properties:
              outboundLineId: { $ref: '#/components/schemas/Id' }
              qty: { $ref: '#/components/schemas/Quantity' }
        weight: { $ref: '#/components/schemas/Quantity' }
        weightUnit: { type: string }
        serialIds:
          type: array
          maxItems: 1000
          items: { $ref: '#/components/schemas/Id' }
    ShipmentRequest:
      type: object
      additionalProperties: false
      required: [shipmentId, shipmentPartId, effectId, executionAttemptId, manifestRevision, packageIds, clientOperationId]
      properties:
        shipmentId: { $ref: '#/components/schemas/Id' }
        shipmentPartId: { $ref: '#/components/schemas/Id' }
        effectId: { $ref: '#/components/schemas/Id' }
        executionAttemptId: { $ref: '#/components/schemas/Id' }
        manifestRevision: { $ref: '#/components/schemas/Version' }
        packageIds:
          type: array
          minItems: 1
          items: { $ref: '#/components/schemas/Id' }
        carrierRef: { $ref: '#/components/schemas/Id' }
        clientOperationId: { $ref: '#/components/schemas/Id' }
        digestVersion: { $ref: '#/components/schemas/Version' }
    MoveRequest:
      type: object
      additionalProperties: false
      required: [clientOperationId, effectId, executionAttemptId, subActionId, sourceBalanceId, targetLocationId, qty, unit, reason]
      properties:
        clientOperationId: { $ref: '#/components/schemas/Id' }
        effectId: { $ref: '#/components/schemas/Id' }
        executionAttemptId: { $ref: '#/components/schemas/Id' }
        subActionId: { $ref: '#/components/schemas/Id' }
        sourceBalanceId: { $ref: '#/components/schemas/Id' }
        targetLocationId: { $ref: '#/components/schemas/Id' }
        qty: { $ref: '#/components/schemas/Quantity' }
        unit: { type: string }
        reason: { type: string }
        serialIds:
          type: array
          items: { $ref: '#/components/schemas/Id' }
        digestVersion: { $ref: '#/components/schemas/Version' }
    StockHoldRequest:
      type: object
      additionalProperties: false
      required: [scope, reason]
      properties:
        scope: { type: object, additionalProperties: true }
        reason: { type: string }
        evidenceRefs:
          type: array
          items: { type: string }
        clientOperationId: { $ref: '#/components/schemas/Id' }
    TransferCreateRequest:
      type: object
      additionalProperties: false
      required: [sourceWarehouseId, targetWarehouseId, lines, reason]
      properties:
        sourceWarehouseId: { $ref: '#/components/schemas/Id' }
        targetWarehouseId: { $ref: '#/components/schemas/Id' }
        reason: { type: string }
        clientOperationId: { $ref: '#/components/schemas/Id' }
        lines:
          type: array
          minItems: 1
          maxItems: 200
          items:
            type: object
            additionalProperties: false
            required: [skuId, businessLotKey, plannedQty, unit]
            properties:
              skuId: { $ref: '#/components/schemas/Id' }
              businessLotKey: { $ref: '#/components/schemas/Id' }
              plannedQty: { $ref: '#/components/schemas/Quantity' }
              unit: { type: string }
    ReceiptAuthorizationRequest:
      type: object
      additionalProperties: false
      required: [transferLineId, targetWarehouseId, targetClientOperationId, quantity]
      properties:
        transferLineId: { $ref: '#/components/schemas/Id' }
        targetWarehouseId: { $ref: '#/components/schemas/Id' }
        targetClientOperationId: { $ref: '#/components/schemas/Id' }
        quantity: { $ref: '#/components/schemas/Quantity' }
    TransferReceiptRequest:
      type: object
      additionalProperties: false
      required: [transferId, sourceLineRef, authorizationId, tokenVersion, qty, businessLotKey, clientOperationId]
      properties:
        transferId: { $ref: '#/components/schemas/Id' }
        sourceLineRef: { $ref: '#/components/schemas/Id' }
        authorizationId: { $ref: '#/components/schemas/Id' }
        tokenVersion: { $ref: '#/components/schemas/Version' }
        qty: { $ref: '#/components/schemas/Quantity' }
        businessLotKey: { $ref: '#/components/schemas/Id' }
        lot: { $ref: '#/components/schemas/LotInput' }
        serialIds:
          type: array
          items: { $ref: '#/components/schemas/Id' }
        clientOperationId: { $ref: '#/components/schemas/Id' }
        digestVersion: { $ref: '#/components/schemas/Version' }
    CountPlanCreateRequest:
      type: object
      additionalProperties: false
      required: [locationIds, reason]
      properties:
        locationIds:
          type: array
          minItems: 1
          items: { $ref: '#/components/schemas/Id' }
        reason: { type: string }
        clientOperationId: { $ref: '#/components/schemas/Id' }
    CountObservationRequest:
      type: object
      additionalProperties: false
      required: [observationId, lineId, roundNo, qty]
      properties:
        observationId: { $ref: '#/components/schemas/Id' }
        lineId: { $ref: '#/components/schemas/Id' }
        roundNo: { type: integer, minimum: 1 }
        qty: { $ref: '#/components/schemas/Quantity' }
        serialIds:
          type: array
          items: { $ref: '#/components/schemas/Id' }
        clientOperationId: { $ref: '#/components/schemas/Id' }
    AdjustmentCreateRequest:
      type: object
      additionalProperties: false
      required: [balanceId, deltaQty, reason]
      properties:
        countLineId: { $ref: '#/components/schemas/Id' }
        balanceId: { $ref: '#/components/schemas/Id' }
        deltaQty: { $ref: '#/components/schemas/Quantity' }
        reason: { type: string }
        evidenceRefs:
          type: array
          items: { type: string }
        serialActions:
          type: array
          items:
            type: object
            additionalProperties: false
            required: [serialId, action]
            properties:
              serialId: { $ref: '#/components/schemas/Id' }
              action: { type: string, enum: [FOUND, MISSING] }
        clientOperationId: { $ref: '#/components/schemas/Id' }
    AdjustmentApprovalRequest:
      type: object
      additionalProperties: false
      required: [decision, expectedVersion]
      properties:
        decision: { type: string, enum: [APPROVED, REJECTED] }
        reason: { type: string }
        expectedVersion: { $ref: '#/components/schemas/Version' }
    AdjustmentApplyRequest:
      type: object
      additionalProperties: false
      required: [clientOperationId, expectedVersion]
      properties:
        clientOperationId: { $ref: '#/components/schemas/Id' }
        expectedVersion: { $ref: '#/components/schemas/Version' }
        countPlanId: { $ref: '#/components/schemas/Id' }
        gateEpoch: { $ref: '#/components/schemas/Version' }
        approvalId: { $ref: '#/components/schemas/Id' }
        digestVersion: { $ref: '#/components/schemas/Version' }
    JobRetryRequest:
      type: object
      additionalProperties: false
      required: [failedShardIds, reason, expectedVersion]
      properties:
        failedShardIds:
          type: array
          items: { $ref: '#/components/schemas/Id' }
        reason: { type: string }
        expectedVersion: { $ref: '#/components/schemas/Version' }
    ReconciliationSnapshotRequest:
      type: object
      additionalProperties: false
      required: [warehouseIds, scenarioCode, cutoff, sourceWatermarks]
      properties:
        warehouseIds:
          type: array
          items: { $ref: '#/components/schemas/Id' }
        scenarioCode: { type: string }
        cutoff: { type: string }
        sourceWatermarks: { type: object, additionalProperties: true }
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
      type: object
      additionalProperties: false
      required: [expectedEffectVersion, reason]
      properties:
        previousCommandId: { $ref: '#/components/schemas/Id' }
        expectedEffectVersion: { $ref: '#/components/schemas/Version' }
        reason: { type: string }
        digestVersion: { $ref: '#/components/schemas/Version' }
        clientOperationId: { $ref: '#/components/schemas/Id' }
    WarehouseCreateRequest:
      type: object
      additionalProperties: false
      required: [code, name, timezone, clientOperationId]
      properties:
        code: { type: string, maxLength: 32 }
        name: { type: string, maxLength: 512 }
        timezone: { type: string, description: IANA时区 }
        clientOperationId: { $ref: '#/components/schemas/Id' }
    LocationCreateRequest:
      type: object
      additionalProperties: false
      required: [code, zoneCode, locationType, clientOperationId]
      properties:
        code: { type: string, maxLength: 32 }
        zoneCode: { type: string, maxLength: 32 }
        locationType: { type: string, maxLength: 32 }
        capacityQty: { $ref: '#/components/schemas/Quantity' }
        capacityUnit: { type: string }
        clientOperationId: { $ref: '#/components/schemas/Id' }
    SkuCreateRequest:
      type: object
      additionalProperties: false
      required: [code, name, baseUnit, quantityScale, lotEnabled, serialEnabled, expiryEnabled, clientOperationId]
      properties:
        code: { $ref: '#/components/schemas/Id' }
        name: { type: string }
        baseUnit: { type: string }
        quantityScale: { type: integer, minimum: 0, maximum: 6 }
        lotEnabled: { type: boolean }
        serialEnabled: { type: boolean }
        expiryEnabled: { type: boolean }
        clientOperationId: { $ref: '#/components/schemas/Id' }
    SkuUnitCreateRequest:
      type: object
      additionalProperties: false
      required: [unitCode, numerator, denominator, clientOperationId]
      properties:
        unitCode: { type: string }
        numerator: { type: string, pattern: '^[1-9]\d*$' }
        denominator: { type: string, pattern: '^[1-9]\d*$' }
        sampleQuantity: { $ref: '#/components/schemas/Quantity' }
        clientOperationId: { $ref: '#/components/schemas/Id' }
    LotCreateRequest:
      type: object
      additionalProperties: false
      required: [ownerId, skuId, lotCode, businessLotKey, clientOperationId]
      properties:
        ownerId: { $ref: '#/components/schemas/Id' }
        skuId: { $ref: '#/components/schemas/Id' }
        lotCode: { $ref: '#/components/schemas/Id' }
        businessLotKey: { $ref: '#/components/schemas/Id' }
        producedAt: { type: string, format: date-time }
        expiresAt: { type: string, format: date-time }
        sourceDate: { type: string }
        expiryRuleVersion: { $ref: '#/components/schemas/Version' }
        clientOperationId: { $ref: '#/components/schemas/Id' }
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
