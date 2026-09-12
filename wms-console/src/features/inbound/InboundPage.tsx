import { DocumentListPage } from "../lists/DocumentListPage";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function InboundPage() {
  const { warehouseId } = useWorkspace();
  return (
    <DocumentListPage
      title="入库工作台"
      sub="收货、质检、上架。库存同步看 stockSyncStatus。"
      empty={`当前仓 ${warehouseId || "(未选)"} 没有入库单`}
      paths={warehouseId && warehouseId !== "_" ? [`/api/wms/v1/warehouses/${warehouseId}/inbound-orders`] : []}
    />
  );
}
