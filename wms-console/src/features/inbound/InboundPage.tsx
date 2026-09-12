import { Form, Input } from "antd";
import { api } from "../../api/client";
import { recordId } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { DocumentListPage } from "../lists/DocumentListPage";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function InboundPage() {
  const { token, warehouseId } = useWorkspace();
  const ready = warehouseId && warehouseId !== "_";
  return (
    <DocumentListPage
      title="入库工作台"
      sub="打开单据后做收货、质检、上架。库存同步看 stockSyncStatus。"
      empty={`当前仓 ${warehouseId || "(未选)"} 没有入库单`}
      paths={ready ? [`/api/wms/v1/warehouses/${warehouseId}/inbound-orders`] : []}
      hrefFor={(row) => ready ? `/w/${warehouseId}/inbound/${recordId(row, "id", "orderId")}` : undefined}
      actions={(
        <CommandCard
          title="创建入库单"
          hint="外部单号冲突由服务端拒绝。数量按字符串提交。"
          operation={`inbound-create:${warehouseId}`}
          submitLabel="创建入库单"
          disabled={!ready || !token}
          onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/inbound-orders`, token, {
            method: "POST",
            idempotencyKey: key,
            body: {
              sourceSystem: values.sourceSystem || "OMS",
              externalNo: values.externalNo,
              ownerId: values.ownerId || "OWNER-1",
              inboundOrderId: key,
              lines: [{
                lineId: values.lineId || "LINE-1",
                externalLineId: values.lineId || "LINE-1",
                skuId: values.skuId,
                expectedQty: values.expectedQty,
                unit: values.unit || "EA"
              }]
            }
          })}
        >
          <Form.Item label="来源系统" name="sourceSystem" initialValue="OMS"><Input /></Form.Item>
          <Form.Item label="外部单号" name="externalNo" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item label="货主" name="ownerId" initialValue="OWNER-1"><Input /></Form.Item>
          <Form.Item label="行号" name="lineId" initialValue="LINE-1"><Input /></Form.Item>
          <Form.Item label="SKU" name="skuId" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item label="应收数量" name="expectedQty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
          <Form.Item label="单位" name="unit" initialValue="EA"><Input /></Form.Item>
        </CommandCard>
      )}
    />
  );
}
