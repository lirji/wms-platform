import { useSearchParams } from "react-router-dom";
import { Card, Select, Space } from "antd";
import { recordId } from "../../api/envelope";
import { DocumentListPage } from "../lists/DocumentListPage";
import { useWorkspace } from "../../shell/WorkspaceContext";

const TASK_TYPES = [
  { value: "PICK", label: "拣货 PICK" },
  { value: "RESTOCK", label: "回库 RESTOCK" },
  { value: "PUTAWAY", label: "上架 PUTAWAY" }
];

export function JobsPage() {
  const { warehouseId } = useWorkspace();
  const [search, setSearch] = useSearchParams();
  const ready = warehouseId && warehouseId !== "_";
  const taskType = search.get("taskType") || "PICK";

  function setTaskType(value: string) {
    const merged = new URLSearchParams(search);
    merged.set("taskType", value);
    merged.delete("tc");
    setSearch(merged, { replace: true });
  }

  return (
    <Space orientation="vertical" size={16} style={{ display: "flex" }}>
      <DocumentListPage
        title="任务与设备"
        sub="打开运行后回收过期租约或领取就绪分片。仓执行任务按 taskType 分域查询，不与 job_run 混用。"
        empty={`当前仓 ${warehouseId || "(未选)"} 没有任务`}
        columns={[
          { key: "id", label: "任务", keys: ["id", "jobId", "run_key"], kind: "id", copyKind: "任务" },
          { key: "status", label: "状态", keys: ["state", "status"], kind: "status" },
          { key: "type", label: "类型", keys: ["job_type", "jobType", "type"] },
          { key: "shards", label: "分片", keys: ["planned_shards", "plannedShards"] }
        ]}
        paths={ready ? [`/api/wms/v1/jobs?warehouseId=${encodeURIComponent(warehouseId)}`] : []}
        hrefFor={(row) => ready ? `/w/${warehouseId}/jobs/${recordId(row)}` : undefined}
      />
      <Card
        size="small"
        title="仓执行任务"
        extra={(
          <Select
            aria-label="任务类型"
            value={taskType}
            style={{ width: 180 }}
            options={TASK_TYPES}
            onChange={setTaskType}
          />
        )}
      >
        <DocumentListPage
          secondary
          title={`${taskType} 任务`}
          sub=""
          empty={`当前仓没有 ${taskType} 任务`}
          cursorKey="tc"
          queryKey="tq"
          columns={[
            { key: "id", label: "任务", keys: ["id", "taskId"], kind: "id", copyKind: "任务" },
            { key: "status", label: "状态", keys: ["state", "status"], kind: "status" },
            { key: "type", label: "类型", keys: ["task_type", "taskType"] },
            { key: "qty", label: "计划", qty: true, keys: ["planned_qty", "plannedQty"] }
          ]}
          paths={ready ? [`/api/wms/v1/warehouses/${warehouseId}/tasks?taskType=${encodeURIComponent(taskType)}`] : []}
          hrefFor={(row) => ready
            ? `/w/${warehouseId}/tasks/${recordId(row)}?taskType=${encodeURIComponent(taskType)}`
            : undefined}
        />
      </Card>
    </Space>
  );
}
