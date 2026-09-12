import { DocumentListPage } from "../lists/DocumentListPage";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function CountPage() {
  const { warehouseId } = useWorkspace();
  return (
    <DocumentListPage
      title="盘点"
      sub="冻结、点数、复盘与调整。部分完成显示已提交与剩余。"
      empty={`当前仓 ${warehouseId || "(未选)"} 没有盘点计划`}
      paths={warehouseId && warehouseId !== "_" ? [`/api/wms/v1/warehouses/${warehouseId}/count-plans`] : []}
    />
  );
}
