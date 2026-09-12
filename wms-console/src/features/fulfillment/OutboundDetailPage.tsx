import { useState } from "react";
import { Form, Input } from "antd";
import { useParams } from "react-router-dom";
import { api } from "../../api/client";
import { nestedRecords } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandCol, DocumentWorkbench } from "../../shared/document/DocumentWorkbench";
import { DataTable } from "../../shared/ui/DataTable";
import { useDocument } from "../../shared/useDocument";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function OutboundDetailPage() {
  const { warehouseId = "", outboundOrderId = "" } = useParams();
  const { token } = useWorkspace();
  const [tick, setTick] = useState(0);
  const path = warehouseId && outboundOrderId
    ? `/api/wms/v1/warehouses/${warehouseId}/outbound-orders/${outboundOrderId}`
    : undefined;
  const { record, error, loading } = useDocument(token, path, tick);
  const reload = () => setTick((current) => current + 1);
  const tasks = nestedRecords(record, "tasks");

  return (
    <DocumentWorkbench
      backTo={`/w/${warehouseId}/fulfillment`}
      backLabel="返回履约/出库"
      title={`出库单 ${outboundOrderId}`}
      sub="先规划拣货任务再拣。部分发运只发已包装量；取消只针对未拣剩余并生成回库任务。"
      loading={loading}
      error={error}
      record={record}
      extraColumns={[
        { key: "allocated", label: "已分配", qty: true, keys: ["allocated_qty"] },
        { key: "picked", label: "已拣", qty: true, keys: ["picked_physical_qty"] },
        { key: "packed", label: "已包装", qty: true, keys: ["packed_physical_qty"] },
        { key: "shipped", label: "已发", qty: true, keys: ["shipped_physical_qty"] },
        { key: "cancelled", label: "已取消", qty: true, keys: ["cancelled_qty"] }
      ]}
      extra={(
        <DataTable
          rows={tasks}
          emptyText="还没有拣货或回库任务"
          columns={[
            { key: "id", label: "任务", keys: ["id", "taskId"] },
            { key: "status", label: "状态", keys: ["state", "status"] },
            { key: "type", label: "类型", keys: ["task_type", "taskType"] },
            { key: "qty", label: "计划/完成", qty: true, keys: ["planned_qty", "completed_qty"] }
          ]}
        />
      )}
      commands={(
        <>
          <CommandCol>
            <CommandCard
              title="规划拣货"
              hint="进入拣货前必须已有执行授权。"
              operation={`plan-pick:${outboundOrderId}`}
              submitLabel="规划任务"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/outbound-orders/${outboundOrderId}/pick-tasks`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  orderLineId: values.orderLineId,
                  sourceLocationId: values.sourceLocationId,
                  stagingLocationId: values.stagingLocationId,
                  qty: values.qty,
                  clientOperationId: key
                }
              })}
            >
              <Form.Item label="出库行 orderLineId" name="orderLineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="来源库位" name="sourceLocationId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="集货位" name="stagingLocationId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol>
            <CommandCard
              title="拣货"
              hint="返回 202。超过任务或行剩余量会被拒绝。"
              operation={`pick:${outboundOrderId}`}
              submitLabel="确认拣货"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/tasks/${values.taskId}/picks`, token, {
                method: "POST",
                idempotencyKey: key,
                body: { qty: values.qty, clientOperationId: key }
              })}
            >
              <Form.Item label="任务" name="taskId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol>
            <CommandCard
              title="包装"
              hint="不能超过已拣未装量。"
              operation={`pack:${outboundOrderId}`}
              submitLabel="确认包装"
              disabled={!token}
              onDone={reload}
              onRun={(_key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/outbound-orders/${outboundOrderId}/packings`, token, {
                method: "POST",
                body: { orderLineId: values.orderLineId, qty: values.qty, packageNo: values.packageNo }
              })}
            >
              <Form.Item label="出库行" name="orderLineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
              <Form.Item label="包裹号" name="packageNo"><Input /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol>
            <CommandCard
              title="部分发运"
              hint="不能超过已包装未发量。货已发出、库存待同步时不要再点一次当新发运。"
              operation={`ship:${outboundOrderId}`}
              submitLabel="确认发运"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/outbound-orders/${outboundOrderId}/shipments`, token, {
                method: "POST",
                idempotencyKey: key,
                body: { orderLineId: values.orderLineId, qty: values.qty, clientOperationId: key }
              })}
            >
              <Form.Item label="出库行" name="orderLineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol>
            <CommandCard
              title="取消未拣回库"
              hint="只取消未拣剩余。已拣未发不会在这里直接回滚库存。"
              operation={`cancel:${outboundOrderId}`}
              submitLabel="取消剩余"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/outbound-orders/${outboundOrderId}/cancellations`, token, {
                method: "POST",
                idempotencyKey: key,
                body: { orderLineId: values.orderLineId, clientOperationId: key }
              })}
            >
              <Form.Item label="出库行" name="orderLineId" rules={[{ required: true }]}><Input /></Form.Item>
            </CommandCard>
          </CommandCol>
        </>
      )}
    />
  );
}
