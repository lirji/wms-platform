import { Form, Input } from "antd";
import { api } from "../../api/client";
import { recordId } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { DocumentListPage } from "../lists/DocumentListPage";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function TransferPage() {
  const { token, warehouseId } = useWorkspace();
  const ready = warehouseId && warehouseId !== "_";
  return (
    <DocumentListPage
      title="调拨"
      sub="源仓发出、目的授权接收、在途损耗。打开单据后提交命令。"
      empty="当前企业没有调拨单"
      paths={ready ? ["/api/wms/v1/transfers"] : []}
      hrefFor={(row) => ready ? `/w/${warehouseId}/transfers/${recordId(row, "id", "transferId")}` : undefined}
      createLabel="创建调拨单"
      createTitle="创建调拨单"
      createHint="表单在抽屉里。源仓与目的仓不能相同。"
      create={(
        <CommandCard
          embedded
          title="创建调拨单"
          hint="源仓与目的仓不能相同。"
          operation={`transfer-create:${warehouseId}`}
          submitLabel="创建调拨单"
          disabled={!token || !ready}
          onRun={(key, values) => api("/api/wms/v1/transfers", token, {
            method: "POST",
            idempotencyKey: key,
            body: {
              transferId: key,
              sourceWarehouseId: values.sourceWarehouseId || warehouseId,
              targetWarehouseId: values.targetWarehouseId,
              lines: [{
                lineId: values.lineId || "TL-1",
                skuId: values.skuId,
                plannedQty: values.plannedQty,
                unit: values.unit || "EA"
              }]
            }
          })}
        >
          <Form.Item label="源仓" name="sourceWarehouseId" initialValue={warehouseId}><Input /></Form.Item>
          <Form.Item label="目的仓" name="targetWarehouseId" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item label="行" name="lineId" initialValue="TL-1"><Input /></Form.Item>
          <Form.Item label="SKU" name="skuId" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item label="计划数量" name="plannedQty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
          <Form.Item label="单位" name="unit" initialValue="EA"><Input /></Form.Item>
        </CommandCard>
      )}
    />
  );
}
