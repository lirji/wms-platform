import { DocumentListPage } from "../lists/DocumentListPage";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function JobsPage() {
  const { warehouseId } = useWorkspace();
  return (
    <DocumentListPage
      title="任务与设备"
      sub="仓内任务列表。UNKNOWN 回执只提供核验入口，不重做设备动作。"
      empty={`当前仓 ${warehouseId || "(未选)"} 没有任务`}
      columns={[
        { key: "id", label: "任务", keys: ["id", "taskId"] },
        { key: "status", label: "状态", keys: ["status"] },
        { key: "type", label: "类型", keys: ["type", "taskType"] },
        { key: "physicalStatus", label: "实物", keys: ["physicalStatus"] }
      ]}
      paths={warehouseId && warehouseId !== "_" ? [`/api/wms/v1/warehouses/${warehouseId}/tasks`] : []}
    />
  );
}
