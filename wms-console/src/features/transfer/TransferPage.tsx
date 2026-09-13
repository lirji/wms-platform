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
      columns={[
        { key: "id", label: "标识", keys: ["id", "transferId"], kind: "id", copyKind: "单据" },
        { key: "status", label: "状态", keys: ["status", "state"], kind: "status" },
        { key: "skuId", label: "SKU", keys: ["skuId", "sku_id"], kind: "id", copyKind: "SKU" },
        { key: "plannedQty", label: "数量", qty: true, keys: ["plannedQty", "planned_qty"] },
        { key: "issuedQty", label: "已发", qty: true, keys: ["issuedQty", "issued_qty"] },
        { key: "receivedQty", label: "已收", qty: true, keys: ["receivedQty", "received_qty"] },
        { key: "sourceWarehouseId", label: "源仓", keys: ["sourceWarehouseId", "source_warehouse_id"] },
        { key: "targetWarehouseId", label: "目的仓", keys: ["targetWarehouseId", "target_warehouse_id"] }
      ]}
      paths={ready ? ["/api/wms/v1/transfers"] : []}
      hrefFor={(row) => ready ? `/w/${warehouseId}/transfers/${recordId(row, "id", "transferId")}` : undefined}
      createLabel="创建调拨单"
      createTitle="创建调拨单"
      createScope="transfer.create"
      createHint="表单在居中弹层。源仓与目的仓不能相同。"
      create={(
        <CommandCard
          embedded
          requireScope="transfer.create"
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
