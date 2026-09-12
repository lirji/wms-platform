import { DocumentListPage } from "../lists/DocumentListPage";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function TransferPage() {
  const { warehouseId } = useWorkspace();
  return (
    <DocumentListPage
      title="调拨"
      sub="源仓发出、在途、目的接收。差异以服务端为准。"
      empty="当前过滤条件下没有调拨单"
      paths={warehouseId && warehouseId !== "_" ? ["/api/wms/v1/transfers"] : []}
    />
  );
}
