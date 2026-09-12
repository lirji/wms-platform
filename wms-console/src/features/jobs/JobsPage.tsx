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
        { key: "id", label: "任务", keys: ["id", "jobId", "run_key"] },
        { key: "status", label: "状态", keys: ["state", "status"] },
        { key: "type", label: "类型", keys: ["job_type", "jobType", "type"] },
        { key: "shards", label: "分片", keys: ["planned_shards", "plannedShards"] }
      ]}
      paths={warehouseId && warehouseId !== "_" ? [`/api/wms/v1/jobs?warehouseId=${encodeURIComponent(warehouseId)}`] : []}
    />
  );
}
