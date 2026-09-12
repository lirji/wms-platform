import { useState } from "react";
import { Form, Input } from "antd";
import { useParams } from "react-router-dom";
import { api } from "../../api/client";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandCol, DocumentWorkbench } from "../../shared/document/DocumentWorkbench";
import { useDocument } from "../../shared/useDocument";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function CountDetailPage() {
  const { warehouseId = "", countPlanId = "" } = useParams();
  const { token } = useWorkspace();
  const [tick, setTick] = useState(0);
  const path = warehouseId && countPlanId
    ? `/api/wms/v1/warehouses/${warehouseId}/count-plans/${countPlanId}`
    : undefined;
  const { record, error, loading } = useDocument(token, path, tick);
  const reload = () => setTick((current) => current + 1);

  return (
    <DocumentWorkbench
      backTo={`/w/${warehouseId}/counts`}
      backLabel="返回盘点列表"
      title={`盘点计划 ${countPlanId}`}
      sub="DRAFT 第一次冻结会进入排空；在途未清零不能 FROZEN。复盘后才能审批，审批后才能按行调整。"
      loading={loading}
      error={error}
      record={record}
      extraColumns={[
        { key: "snapshot", label: "快照", qty: true, keys: ["snapshotQty", "snapshot_qty"] },
        { key: "counted", label: "点数", qty: true, keys: ["countedQty", "counted_qty"] },
        { key: "reserved", label: "预占", qty: true, keys: ["reservedQty", "reserved_qty"] }
      ]}
      commands={(
        <>
          <CommandCol>
            <CommandCard
              title="排空 / 冻结"
              hint="第一次提交排空，第二次在无在途时冻结。在途未清零返回冲突，不会假冻结。"
              operation={`count-freeze:${countPlanId}`}
              submitLabel="推进冻结"
              disabled={!token}
              onDone={reload}
              onRun={(_key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/count-plans/${countPlanId}/freeze-requests`, token, {
                method: "POST",
                body: { phase: values.phase || undefined }
              })}
            >
              <Form.Item label="阶段" name="phase"><Input placeholder="空=按当前状态推进，QUIESCE 或 FREEZE" /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol>
            <CommandCard
              title="点数 / 复盘"
              hint="同 observation 重试。序列号行必须走身份集合，这里只录数量行。"
              operation={`count-observe:${countPlanId}`}
              submitLabel="提交点数"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/count-plans/${countPlanId}/observations`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  lineId: values.lineId,
                  observationId: key,
                  qty: values.qty,
                  roundNo: Number(values.roundNo || "1")
                }
              })}
            >
              <Form.Item label="快照行" name="lineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
              <Form.Item label="轮次" name="roundNo" initialValue="1"><Input /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol>
            <CommandCard
              title="提交复盘"
              hint="所有快照行都点数后才能进入 REVIEWING。"
              operation={`count-review:${countPlanId}`}
              submitLabel="提交复盘"
              disabled={!token}
              onDone={reload}
              onRun={() => api(`/api/wms/v1/warehouses/${warehouseId}/count-plans/${countPlanId}/reviews`, token, {
                method: "POST",
                body: {}
              })}
            >
              <Form.Item label="确认"><Input disabled value="提交当前计划进入复盘" /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol>
            <CommandCard
              title="审批"
              hint="未复盘不能审批。同审批标识重放。"
              operation={`count-approve:${countPlanId}`}
              submitLabel="批准调整"
              disabled={!token}
              onDone={reload}
              onRun={(key) => api(`/api/wms/v1/warehouses/${warehouseId}/count-plans/${countPlanId}/approvals`, token, {
                method: "POST",
                idempotencyKey: key,
                body: { approvalId: key }
              })}
            >
              <Form.Item label="确认"><Input disabled value="以当前操作者为审批人" /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol>
            <CommandCard
              title="按行调整"
              hint="盘亏不足以覆盖预占时行进入 RESERVATION_CONFLICT，已提交行不回滚。"
              operation={`count-apply:${countPlanId}`}
              submitLabel="应用行调整"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/count-plans/${countPlanId}/applications`, token, {
                method: "POST",
                idempotencyKey: key,
                body: { lineId: values.lineId, clientOperationId: key }
              })}
            >
              <Form.Item label="快照行" name="lineId" rules={[{ required: true }]}><Input /></Form.Item>
            </CommandCard>
          </CommandCol>
        </>
      )}
    />
  );
}
