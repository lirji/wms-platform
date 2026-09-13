import { useState } from "react";
import { Form, Input, Space } from "antd";
import { useParams } from "react-router-dom";
import { api } from "../../api/client";
import { field, nestedRecords } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandCol, DocumentWorkbench } from "../../shared/document/DocumentWorkbench";
import { SerialChoiceQuery } from "../../shared/serial/SerialChoiceQuery";
import { SerialExecutionField } from "../../shared/serial/SerialExecutionField";
import { countText, serialExecution } from "../../shared/serial/serialIds";
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
  const serialHold = /HOLD|CLAIMED|TRANSFER/.test(field(record, "serialState", "qualityCode", "quality_code"));
  const syncPending = field(record, "stockSyncStatus") === "PENDING";

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
        <Space orientation="vertical" size={16} style={{ display: "flex" }}>
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
          <SerialChoiceQuery kind="selectable" warehouseId={warehouseId} />
          <SerialChoiceQuery kind="shippable" warehouseId={warehouseId} outboundOrderId={outboundOrderId} />
        </Space>
      )}
      commands={(
        <>
          <CommandCol title="核验后授权执行" requireScope="fulfillment.execute">
            <CommandCard
              embedded
              requireScope="fulfillment.execute"
              title="核验后授权执行"
              hint="必须已有本库 TCC Committed 证据。没有证据会 409，不会发明 ALLOCATED。"
              operation={`authorize:${outboundOrderId}`}
              submitLabel="提交授权"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/outbound-orders/${outboundOrderId}/execution-authorizations`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  attemptId: values.attemptId,
                  authorizationId: values.authorizationId,
                  xid: values.xid,
                  tcTerminalEvidenceRef: values.tcTerminalEvidenceRef,
                  participantSetHash: values.participantSetHash,
                  clientOperationId: key
                }
              })}
            >
              <Form.Item label="attemptId" name="attemptId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="authorizationId" name="authorizationId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="xid" name="xid" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="TC证据引用" name="tcTerminalEvidenceRef" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="参与者摘要" name="participantSetHash" rules={[{ required: true }]}><Input /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol title="规划拣货" requireScope="outbound.pick">
            <CommandCard
              embedded
              requireScope="outbound.pick"
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
          <CommandCol title="拣货" requireScope="outbound.pick">
            <CommandCard
              embedded
              requireScope="outbound.pick"
              title="拣货"
              hint="返回 202。超过任务或行剩余量会被拒绝。序列号 SKU 提交 serialExecution，数量必须等于身份数。"
              operation={`pick:${outboundOrderId}`}
              submitLabel="确认拣货"
              disabled={!token || serialHold || syncPending}
              onDone={reload}
              onRun={(key, values) => {
                const selection = serialExecution(values.serialExecution || "");
                return api(`/api/wms/v1/warehouses/${warehouseId}/tasks/${values.taskId}/picks`, token, {
                  method: "POST",
                  idempotencyKey: key,
                  body: {
                    qty: selection ? countText(selection.identities.map((item) => item.serialId)) : values.qty,
                    lotId: values.lotId,
                    clientOperationId: key,
                    ...(selection ? { serialExecution: selection } : {})
                  }
                });
              }}
            >
              <Form.Item label="任务" name="taskId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="批次" name="lotId" rules={[{ required: true }]} extra="不管理批次的商品填写 NO_LOT"><Input /></Form.Item>
              <Form.Item label="数量" name="qty" extra="填写身份时按身份个数提交。" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
              <SerialExecutionField extra="先查本页可选序列号，抄入当前 ownerEpoch。普通 SKU 留空。" />
            </CommandCard>
          </CommandCol>
          <CommandCol title="包装" requireScope="outbound.pack">
            <CommandCard
              embedded
              requireScope="outbound.pack"
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
          <CommandCol title="部分发运" requireScope="outbound.ship">
            <CommandCard
              embedded
              requireScope="outbound.ship"
              title="部分发运"
              hint="必须已有本集货位、批次的拣货过账回执。序列发运须指定原已拣 SN/epoch，数量等于身份数。"
              operation={`ship:${outboundOrderId}`}
              submitLabel="确认发运"
              disabled={!token || serialHold || syncPending}
              onDone={reload}
              onRun={(key, values) => {
                const selection = serialExecution(values.serialExecution || "");
                return api(`/api/wms/v1/warehouses/${warehouseId}/outbound-orders/${outboundOrderId}/shipments`, token, {
                  method: "POST",
                  idempotencyKey: key,
                  body: {
                    orderLineId: values.orderLineId,
                    qty: selection ? countText(selection.identities.map((item) => item.serialId)) : values.qty,
                    stagingLocationId: values.stagingLocationId,
                    lotId: values.lotId,
                    clientOperationId: key,
                    ...(selection ? { serialExecution: selection } : {})
                  }
                });
              }}
            >
              <Form.Item label="集货位" name="stagingLocationId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="批次" name="lotId" rules={[{ required: true }]} extra="不管理批次的商品填写 NO_LOT"><Input /></Form.Item>
              <Form.Item label="出库行" name="orderLineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" extra="填写身份时按身份个数提交。" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
              <SerialExecutionField extra="先查本页可发运序列号。库存 POSTED 不等于全球登记完成。" />
            </CommandCard>
          </CommandCol>
          <CommandCol title="取消未拣" requireScope="outbound.pick">
            <CommandCard
              embedded
              danger
              requireScope="outbound.pick"
              title="取消未拣"
              hint="释放指定原库位和批次的未拣预占，等待库存回执。"
              operation={`cancel:${outboundOrderId}`}
              submitLabel="取消剩余"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/outbound-orders/${outboundOrderId}/cancellations`, token, {
                method: "POST",
                idempotencyKey: key,
                body: { orderLineId: values.orderLineId, sourceLocationId: values.sourceLocationId, lotId: values.lotId, qty: values.qty, clientOperationId: key }
              })}
            >
              <Form.Item label="本桶取消量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
              <Form.Item label="原库位" name="sourceLocationId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="批次" name="lotId" rules={[{ required: true }]} extra="不管理批次的商品填写 NO_LOT"><Input /></Form.Item>
              <Form.Item label="出库行" name="orderLineId" rules={[{ required: true }]}><Input /></Form.Item>
            </CommandCard>
          </CommandCol>
        </>
      )}
    />
  );
}
