import { DocumentListPage } from "../lists/DocumentListPage";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function FulfillmentPage() {
  const { warehouseId } = useWorkspace();
  const ready = warehouseId && warehouseId !== "_";
  return (
    <DocumentListPage
      title="履约与出库"
      sub="全局履约单与本仓出库单。各仓进度独立判断。"
      extra="tcc"
      empty={`当前仓 ${warehouseId || "(未选)"} 没有履约或出库单`}
      paths={ready ? [
        "/api/wms/v1/fulfillments",
        `/api/wms/v1/warehouses/${warehouseId}/outbound-orders`
      ] : []}
    />
  );
}
