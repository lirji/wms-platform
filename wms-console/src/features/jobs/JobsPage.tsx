import { recordId } from "../../api/envelope";
import { DocumentListPage } from "../lists/DocumentListPage";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function JobsPage() {
  const { warehouseId } = useWorkspace();
  const ready = warehouseId && warehouseId !== "_";
  return (
    <DocumentListPage
      title="任务与设备"
      sub="打开运行后回收过期租约或领取就绪分片。UNKNOWN 回执只提供核验入口，不重做设备动作。"
      empty={`当前仓 ${warehouseId || "(未选)"} 没有任务`}
      columns={[
        { key: "id", label: "任务", keys: ["id", "jobId", "run_key"] },
        { key: "status", label: "状态", keys: ["state", "status"] },
        { key: "type", label: "类型", keys: ["job_type", "jobType", "type"] },
        { key: "shards", label: "分片", keys: ["planned_shards", "plannedShards"] }
      ]}
      paths={ready ? [`/api/wms/v1/jobs?warehouseId=${encodeURIComponent(warehouseId)}`] : []}
      hrefFor={(row) => ready ? `/w/${warehouseId}/jobs/${recordId(row)}` : undefined}
    />
  );
}
