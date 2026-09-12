import { useState } from "react";
import { Form, Input } from "antd";
import { useParams } from "react-router-dom";
import { api } from "../../api/client";
import { field } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandCol, DocumentWorkbench } from "../../shared/document/DocumentWorkbench";
import { StatusBanner } from "../../shared/ui/StatusBanner";
import { useDocument } from "../../shared/useDocument";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function FulfillmentDetailPage() {
  const { warehouseId = "", fulfillmentId = "" } = useParams();
  const { token } = useWorkspace();
  const [tick, setTick] = useState(0);
  const { record, error, loading } = useDocument(token, fulfillmentId ? `/api/wms/v1/fulfillments/${fulfillmentId}` : undefined, tick);
  const reload = () => setTick((current) => current + 1);
  const attemptState = field(record, "attemptState", "state");

  return (
    <DocumentWorkbench
      backTo={`/w/${warehouseId}/fulfillment`}
      backLabel="返回履约列表"
      title={`履约单 ${fulfillmentId}`}
      sub="准备分配会冻结参与仓。确认 ALLOCATED 需要 TC Committed 证据，控制台不会伪造。"
      loading={loading}
      error={error}
      record={record}
      extra={(
        <StatusBanner
          kind="tcc"
          title={`活动 attempt ${field(record, "activeAttemptId") || "尚未准备"}`}
          detail={`attemptState=${attemptState || "无"} tcObservedStatus=${field(record, "tcObservedStatus") || "无"}`}
        />
      )}
      extraColumns={[
        { key: "requested", label: "请求数量", qty: true, keys: ["requested_qty", "requestedQty"] }
      ]}
      commands={(
        <>
          <CommandCol title="准备跨仓分配" requireScope="fulfillment.execute">
            <CommandCard
              embedded
              requireScope="fulfillment.execute"
              title="准备跨仓分配"
              hint="每个履约行数量必须分完。没有真实 TC 时 attempt 会停在准备/尝试态。"
              operation={`attempt:${fulfillmentId}`}
              submitLabel="准备分配"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/fulfillments/${fulfillmentId}/attempts`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  warehouses: [values.warehouseId || warehouseId],
                  lines: [{
                    warehouseId: values.warehouseId || warehouseId,
                    orderLineId: values.orderLineId,
                    skuId: values.skuId,
                    qty: values.qty,
                    baseUnit: values.baseUnit || "EA"
                  }]
                }
              })}
            >
              <Form.Item label="参与仓" name="warehouseId" initialValue={warehouseId}><Input /></Form.Item>
              <Form.Item label="履约行/来源行" name="orderLineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="SKU" name="skuId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
              <Form.Item label="单位" name="baseUnit" initialValue="EA"><Input /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol title="请求取消履约" requireScope="fulfillment.cancel">
            <CommandCard
              embedded
              danger
              requireScope="fulfillment.cancel"
              title="请求取消履约"
              hint="202 只表示已受理。不会回滚已确认的 TC，也不会写成 ALLOCATED。"
              operation={`cancel:${fulfillmentId}`}
              submitLabel="请求取消"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/fulfillments/${fulfillmentId}/cancellations`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  reason: values.reason,
                  expectedVersion: Number(values.expectedVersion || field(record, "version") || "0"),
                  clientOperationId: key
                }
              })}
            >
              <Form.Item label="expectedVersion" name="expectedVersion"><Input placeholder="默认用当前单据 version" /></Form.Item>
              <Form.Item label="原因" name="reason"><Input /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol title="执行跨仓分配" requireScope="fulfillment.execute">
            <CommandCard
              embedded
              requireScope="fulfillment.execute"
              title="执行跨仓分配"
              hint="202 只表示执行器已受理。没有 TC 终态证据时不会写成 ALLOCATED。"
              operation={`execute:${fulfillmentId}`}
              submitLabel="提交执行"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/fulfillments/${fulfillmentId}/attempts/${values.attemptId || field(record, "activeAttemptId")}/executions`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  clientOperationId: key,
                  warehouses: [{
                    schemaVersion: 1,
                    enterpriseId: values.enterpriseId,
                    warehouseId: values.warehouseId || warehouseId,
                    ownerId: values.ownerId,
                    allocationId: values.allocationId || field(record, "activeAttemptId") || fulfillmentId,
                    attemptId: values.attemptId || field(record, "activeAttemptId"),
                    cellId: values.cellId,
                    routeEpoch: Number(values.routeEpoch || "1"),
                    lines: [{
                      orderLineId: values.orderLineId,
                      skuId: values.skuId,
                      sourceLocationId: values.sourceLocationId,
                      lotId: values.lotId || "NO_LOT",
                      qty: values.qty,
                      baseUnit: values.baseUnit || "EA",
                      minRemainingDays: Number(values.minRemainingDays || "0")
                    }]
                  }]
                }
              })}
            >
              <Form.Item label="企业" name="enterpriseId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="参与仓" name="warehouseId" initialValue={warehouseId}><Input /></Form.Item>
              <Form.Item label="货主" name="ownerId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="attemptId" name="attemptId"><Input placeholder="默认用活动 attempt" /></Form.Item>
              <Form.Item label="allocationId" name="allocationId"><Input /></Form.Item>
              <Form.Item label="cellId" name="cellId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="routeEpoch" name="routeEpoch" initialValue="1"><Input /></Form.Item>
              <Form.Item label="履约行" name="orderLineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="SKU" name="skuId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="来源库位" name="sourceLocationId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="批次" name="lotId" initialValue="NO_LOT"><Input /></Form.Item>
              <Form.Item label="数量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
              <Form.Item label="单位" name="baseUnit" initialValue="EA"><Input /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol title="生成本仓出库单" requireScope="fulfillment.execute">
            <CommandCard
              embedded
              requireScope="fulfillment.execute"
              title="生成本仓出库单"
              hint="不要编造执行授权。空着会建成待授权出库单；核验 TCC Committed 后再授权。跨仓未 ALLOCATED 时服务端仍可能拒绝后续库存同步。"
              operation={`outbound-from:${fulfillmentId}:${warehouseId}`}
              submitLabel="创建出库单"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/outbound-orders`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  allocationId: values.allocationId || field(record, "activeAttemptId") || fulfillmentId,
                  attemptId: values.attemptId || field(record, "activeAttemptId") || key,
                  ownerId: values.ownerId || "OWNER-1",
                  authorizationId: values.authorizationId || undefined,
                  lines: [{
                    orderLineId: values.orderLineId,
                    skuId: values.skuId,
                    qty: values.qty,
                    baseUnit: values.baseUnit || "EA"
                  }]
                }
              })}
            >
              <Form.Item label="allocationId" name="allocationId"><Input placeholder="默认用活动 attempt" /></Form.Item>
              <Form.Item label="attemptId" name="attemptId"><Input /></Form.Item>
              <Form.Item label="执行授权" name="authorizationId"><Input placeholder="有真实授权再填，否则留空待核验" /></Form.Item>
              <Form.Item label="货主" name="ownerId" initialValue="OWNER-1"><Input /></Form.Item>
              <Form.Item label="出库行" name="orderLineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="SKU" name="skuId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
              <Form.Item label="单位" name="baseUnit" initialValue="EA"><Input /></Form.Item>
            </CommandCard>
          </CommandCol>
        </>
      )}
    />
  );
}
