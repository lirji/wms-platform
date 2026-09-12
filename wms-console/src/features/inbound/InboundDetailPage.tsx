import { useState } from "react";
import { Form, Input } from "antd";
import { useParams } from "react-router-dom";
import { api } from "../../api/client";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandCol, DocumentWorkbench } from "../../shared/document/DocumentWorkbench";
import { useDocument } from "../../shared/useDocument";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function InboundDetailPage() {
  const { warehouseId = "", inboundOrderId = "" } = useParams();
  const { token } = useWorkspace();
  const [tick, setTick] = useState(0);
  const path = warehouseId && inboundOrderId
    ? `/api/wms/v1/warehouses/${warehouseId}/inbound-orders/${inboundOrderId}`
    : undefined;
  const { record, error, loading } = useDocument(token, path, tick);
  const reload = () => setTick((current) => current + 1);

  return (
    <DocumentWorkbench
      backTo={`/w/${warehouseId}/inbound`}
      backLabel="返回入库列表"
      title={`入库单 ${inboundOrderId}`}
      sub="收货返回 202 表示实物已记、库存待同步。上架前必须质检合格，目标必须是存储位。"
      loading={loading}
      error={error}
      record={record}
      extraColumns={[
        { key: "received", label: "已收实物", qty: true, keys: ["received_physical_qty"] },
        { key: "putaway", label: "已上架", qty: true, keys: ["putaway_physical_qty"] }
      ]}
      commands={(
        <>
          <CommandCol title="收货" requireScope="inbound.receive">
            <CommandCard
              embedded
              requireScope="inbound.receive"
              title="收货"
              hint="不超过剩余应收。同幂等键重试不会换命令。"
              operation={`receive:${warehouseId}:${inboundOrderId}`}
              submitLabel="提交收货"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/inbound-orders/${inboundOrderId}/receipts`, token, {
                method: "POST",
                idempotencyKey: key,
                body: { lineId: values.lineId, qty: values.qty, receiptPartId: `PART-${key}`, clientOperationId: key }
              })}
            >
              <Form.Item label="行" name="lineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol title="质检" requireScope="quality.inspect">
            <CommandCard
              embedded
              requireScope="quality.inspect"
              title="质检"
              hint="accepted + rejected 不能超过已收实物。"
              operation={`qc:${warehouseId}:${inboundOrderId}`}
              submitLabel="记录质检"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/quality-inspections/${key}/results`, token, {
                method: "POST",
                body: {
                  lineId: values.lineId,
                  acceptedQty: values.acceptedQty,
                  rejectedQty: values.rejectedQty || "0",
                  sourceVersion: Number(values.sourceVersion || "1")
                }
              })}
            >
              <Form.Item label="行" name="lineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="合格量" name="acceptedQty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
              <Form.Item label="不合格量" name="rejectedQty" initialValue="0"><Input inputMode="decimal" /></Form.Item>
              <Form.Item label="来源版本" name="sourceVersion" initialValue="1"><Input /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol title="上架" requireScope="inbound.putaway">
            <CommandCard
              embedded
              requireScope="inbound.putaway"
              title="上架"
              hint="目标必须是存储位。未质检或不合格会被拒绝。"
              operation={`putaway:${warehouseId}:${inboundOrderId}`}
              submitLabel="提交上架"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/tasks/${key}/putaways`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  inboundOrderId,
                  lineId: values.lineId,
                  locationId: values.locationId,
                  targetLocationId: values.locationId,
                  locationType: "STORAGE",
                  qty: values.qty,
                  clientOperationId: key
                }
              })}
            >
              <Form.Item label="行" name="lineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="存储库位" name="locationId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
            </CommandCard>
          </CommandCol>
        </>
      )}
    />
  );
}
