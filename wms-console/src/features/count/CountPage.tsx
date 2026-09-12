import { Form, Input } from "antd";
import { api } from "../../api/client";
import { recordId } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { DocumentListPage } from "../lists/DocumentListPage";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function CountPage() {
  const { token, warehouseId } = useWorkspace();
  const ready = warehouseId && warehouseId !== "_";
  return (
    <DocumentListPage
      title="盘点"
      sub="先建计划，再排空冻结、点数、复盘、审批、按行调整。"
      empty={`当前仓 ${warehouseId || "(未选)"} 没有盘点计划`}
      paths={ready ? [`/api/wms/v1/warehouses/${warehouseId}/count-plans`] : []}
      hrefFor={(row) => ready ? `/w/${warehouseId}/counts/${recordId(row, "id", "planId")}` : undefined}
      createLabel="创建计划"
      createTitle="创建盘点计划"
      createScope="count.create"
      createHint="表单在抽屉里。库位必须已有门禁。"
      create={(
        <CommandCard
          embedded
          requireScope="count.create"
          title="创建盘点计划"
          hint="库位必须已有门禁。范围用逗号分隔库位。"
          operation={`count-create:${warehouseId}`}
          submitLabel="创建计划"
          disabled={!token || !ready}
          onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/count-plans`, token, {
            method: "POST",
            idempotencyKey: key,
            body: {
              planId: key,
              reason: values.reason || "CYCLE",
              locationIds: (values.locationIds || "").split(",").map((item) => item.trim()).filter(Boolean)
            }
          })}
        >
          <Form.Item label="原因" name="reason" initialValue="CYCLE"><Input /></Form.Item>
          <Form.Item label="库位" name="locationIds" rules={[{ required: true }]}><Input placeholder="LOC-1,LOC-2" /></Form.Item>
        </CommandCard>
      )}
    />
  );
}
