import { useParams } from "react-router-dom";
import { DocumentListPage } from "../lists/DocumentListPage";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function StockLedgerPage() {
  const { warehouseId, balanceId = "" } = useParams();
  const { warehouseName } = useWorkspace();
  const ready = Boolean(warehouseId && warehouseId !== "_" && balanceId);
  return (
    <DocumentListPage
      title={`库存流水 ${balanceId}`}
      sub={`${warehouseName || warehouseId || "未选仓"} 的不可变流水。空列表表示该桶还没有过账。`}
      empty={`库存桶 ${balanceId} 没有流水`}
      columns={[
        { key: "id", label: "分录", keys: ["id"], kind: "id", copyKind: "流水" },
        { key: "operationId", label: "操作", keys: ["operation_id", "operationId"], kind: "id", copyKind: "操作" },
        { key: "reason", label: "原因", keys: ["reason_code", "reasonCode"] },
        { key: "qty", label: "实物增量", qty: true, keys: ["on_hand_delta", "onHandDelta"] }
      ]}
      paths={ready ? [`/api/wms/v1/warehouses/${warehouseId}/inventory/${balanceId}/ledger`] : []}
    />
  );
}
