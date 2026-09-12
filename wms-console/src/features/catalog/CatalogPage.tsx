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
        { key: "name", label: "名称", keys: ["name"] },
        { key: "code", label: "编码", keys: ["code"] },
        { key: "status", label: "状态", keys: ["state", "status", "locationStatus"] },
        { key: "lotTracked", label: "批次", keys: ["lot_enabled", "lotEnabled", "lotTracked"] },
        { key: "serialTracked", label: "序列号", keys: ["serial_enabled", "serialEnabled", "serialTracked"] },
        { key: "type", label: "类型 / 单位", keys: ["location_type", "base_unit"] }
      ]}
      paths={["/api/wms/v1/skus", warehouseId && warehouseId !== "_" ? `/api/wms/v1/warehouses/${warehouseId}/locations` : ""]}
    />
  );
}
