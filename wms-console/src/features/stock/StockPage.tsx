import { recordId } from "../../api/envelope";
import { DocumentListPage } from "../lists/DocumentListPage";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function StockPage() {
  const { warehouseId } = useWorkspace();
  const ready = warehouseId && warehouseId !== "_";
  return (
    <DocumentListPage
      title="库存台账"
      sub="数量按字符串展示，不在浏览器做发运量运算。打开一行查看流水。"
      empty={`当前仓 ${warehouseId || "(未选)"} 没有库存行`}
      columns={[
        { key: "skuId", label: "SKU", keys: ["skuId"] },
        { key: "lotId", label: "批次", keys: ["lotId"] },
        { key: "onHandQty", label: "在手", qty: true, keys: ["onHandQty", "qty", "quantity"] },
        { key: "reservedQty", label: "预占", qty: true, keys: ["reservedQty"] },
        { key: "availableQty", label: "可用", qty: true, keys: ["availableQty"] },
        { key: "qualityCode", label: "质量", keys: ["qualityCode"] }
      ]}
      paths={ready ? [`/api/wms/v1/inventory?warehouseIds=${encodeURIComponent(warehouseId)}`] : []}
      hrefFor={(row) => ready ? `/w/${warehouseId}/stock/${recordId(row)}` : undefined}
    />
  );
}
