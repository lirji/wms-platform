import { DocumentListPage } from "../lists/DocumentListPage";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function CatalogPage() {
  const { warehouseId } = useWorkspace();
  return (
    <DocumentListPage
      title="商品 / 库位"
      sub="主数据只读。写接口未开放时不在页面伪造维护表。"
      empty="当前过滤条件下没有主数据"
      columns={[
        { key: "id", label: "标识", keys: ["id", "skuId", "locationId"] },
        { key: "name", label: "名称", keys: ["name", "code"] },
        { key: "status", label: "状态", keys: ["status", "locationStatus"] },
        { key: "lotTracked", label: "批次", keys: ["lotTracked", "batchEnabled"] },
        { key: "serialTracked", label: "序列号", keys: ["serialTracked"] }
      ]}
      paths={["/api/wms/v1/skus", warehouseId && warehouseId !== "_" ? `/api/wms/v1/warehouses/${warehouseId}/locations` : ""]}
    />
  );
}
