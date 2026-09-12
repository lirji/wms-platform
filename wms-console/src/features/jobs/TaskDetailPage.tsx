import { useState } from "react";
import { Form, Input } from "antd";
import { useParams, useSearchParams } from "react-router-dom";
import { api } from "../../api/client";
import { field } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandCol, DocumentWorkbench } from "../../shared/document/DocumentWorkbench";
import { DataTable } from "../../shared/ui/DataTable";
import { useDocument } from "../../shared/useDocument";
import { useResource } from "../../shared/useResource";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function TaskDetailPage() {
  const { warehouseId = "", taskId = "" } = useParams();
  const [search] = useSearchParams();
  const taskType = search.get("taskType") || "PICK";
  const { token } = useWorkspace();
  const [tick, setTick] = useState(0);
  const typed = taskType ? `?taskType=${encodeURIComponent(taskType)}` : "";
  const path = warehouseId && taskId
    ? `/api/wms/v1/warehouses/${warehouseId}/tasks/${taskId}${typed}`
    : undefined;
  const { record, error, loading } = useDocument(token, path, tick);
  const effectsPath = warehouseId && taskId
    ? `/api/wms/v1/warehouses/${warehouseId}/tasks/${taskId}/action-effects`
    : "";
  const { rows: effects } = useResource(token, effectsPath ? [effectsPath] : [], tick);
  const reload = () => setTick((current) => current + 1);

  return (
    <DocumentWorkbench
      backTo={`/w/${warehouseId}/jobs?taskType=${encodeURIComponent(taskType)}`}
      backLabel="返回任务列表"
      title={`仓任务 ${taskId}`}
      sub={`${taskType} 领取只改 assignee 与 epoch，不把领取写成库存过账。`}
      loading={loading}
      error={error}
      record={record}
      extra={(
        <DataTable
          rows={effects}
          emptyText="没有可恢复的动作效果"
          columns={[
            { key: "id", label: "效果", keys: ["id", "effectId"], kind: "id", copyKind: "效果" },
            { key: "status", label: "状态", keys: ["state", "status"], kind: "status" },
            { key: "action", label: "动作", keys: ["action"] }
          ]}
        />
      )}
      commands={(
        <CommandCol title="领取任务" requireScope="task.claim">
          <CommandCard
            embedded
            requireScope="task.claim"
            title="领取任务"
            hint="必须带最新 version。终态任务由服务端拒绝。"
            operation={`task-claim:${warehouseId}:${taskId}`}
            submitLabel="领取任务"
            disabled={!token}
            onDone={reload}
            onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/tasks/${taskId}/claims${typed}`, token, {
              method: "POST",
              idempotencyKey: key,
              body: {
                expectedVersion: Number(values.expectedVersion || field(record, "version") || "0"),
                reason: values.reason,
                clientOperationId: key
              }
            })}
          >
            <Form.Item label="expectedVersion" name="expectedVersion">
              <Input placeholder={field(record, "version") || "0"} />
            </Form.Item>
            <Form.Item label="原因" name="reason"><Input /></Form.Item>
          </CommandCard>
        </CommandCol>
      )}
    />
  );
}
