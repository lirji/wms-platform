import { useSearchParams } from "react-router-dom";
import { Card, Form, Input, Select, Space } from "antd";
import { api } from "../../api/client";
import { recordId } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandDrawer } from "../../shared/command/CommandDrawer";
import { DocumentListPage } from "../lists/DocumentListPage";
import { useWorkspace } from "../../shell/WorkspaceContext";

const TASK_TYPES = [
  { value: "PICK", label: "拣货 PICK" },
  { value: "RESTOCK", label: "回库 RESTOCK" },
  { value: "PUTAWAY", label: "上架 PUTAWAY" }
];

export function JobsPage() {
  const { token, warehouseId } = useWorkspace();
  const [search, setSearch] = useSearchParams();
  const ready = warehouseId && warehouseId !== "_";
  const taskType = search.get("taskType") || "PICK";
  const queue = search.get("queue") || "INBOX";
  const service = search.get("service") || "inventory";

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
      <DocumentListPage
        secondary
        title="仓级动作效果"
        sub="按仓查看 effect。safeToRetry 只在详情由服务端给出，页面不自行判断。"
        empty={`当前仓没有动作效果`}
        cursorKey="ec"
        queryKey="eq"
        columns={[
          { key: "id", label: "效果", keys: ["id", "effectId"], kind: "id", copyKind: "效果" },
          { key: "status", label: "状态", keys: ["state", "status"], kind: "status" },
          { key: "action", label: "动作", keys: ["action"] },
          { key: "safe", label: "可重试", keys: ["safeToRetry"] }
        ]}
        paths={ready ? [`/api/wms/v1/warehouses/${warehouseId}/action-effects`] : []}
        hrefFor={(row) => ready ? `/w/${warehouseId}/effects/${recordId(row, "effectId")}` : undefined}
        actions={(
          <CommandDrawer
            triggerLabel="登记效果"
            title="登记动作效果"
            hint="按权威事实登记。换客户端键仍返回同一 effect。"
            triggerType="default"
            requireScope="task.read"
            disabled={!ready}
          >
            <CommandCard
              embedded
              pollOperation
              requireScope="task.read"
              title="登记效果"
              hint="不要用新随机键重做旧实物。"
              operation={`effect-register:${warehouseId}`}
              submitLabel="登记"
              disabled={!token || !ready}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/action-effects`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  factType: values.factType,
                  factParentId: values.factParentId,
                  factPartId: values.factPartId,
                  factLineId: values.factLineId,
                  action: values.action,
                  digestVersion: values.digestVersion ? Number(values.digestVersion) : undefined,
                  clientOperationId: key
                }
              })}
            >
              <Form.Item label="factType" name="factType" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="factParentId" name="factParentId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="factPartId" name="factPartId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="factLineId" name="factLineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="action" name="action" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="digestVersion" name="digestVersion"><Input /></Form.Item>
            </CommandCard>
          </CommandDrawer>
        )}
      />
      <DocumentListPage
        secondary
        title="序列号恢复"
        sub="只看恢复元数据。重排保持原意图，不重置领取代际。"
        empty={`当前仓没有序列号恢复意图`}
        cursorKey="sc"
        queryKey="sq"
        columns={[
          { key: "id", label: "意图", keys: ["id", "intentId"], kind: "id", copyKind: "恢复" },
          { key: "status", label: "状态", keys: ["state", "status"], kind: "status" },
          { key: "serial", label: "序列号", keys: ["serial", "normalizedSerial"] },
          { key: "reason", label: "隔离原因", keys: ["reason", "isolationReason"] }
        ]}
        paths={ready ? [`/api/wms/v1/warehouses/${warehouseId}/serial-recoveries`] : []}
        actions={(
          <CommandDrawer
            triggerLabel="重排恢复"
            title="重排序列号恢复"
            hint="202 只表示已重新排队。"
            triggerType="default"
            requireScope="messaging.recover"
            disabled={!ready}
          >
            <CommandCard
              embedded
              pollOperation
              requireScope="messaging.recover"
              title="重排恢复"
              hint="必须带当前 epoch 与原因。"
              operation={`serial-retry:${warehouseId}`}
              submitLabel="重排"
              disabled={!token || !ready}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/serial-recoveries/${values.intentId}/retries`, token, {
                method: "POST",
                idempotencyKey: key,
                body: { expectedEpoch: Number(values.expectedEpoch || "0"), reason: values.reason }
              })}
            >
              <Form.Item label="意图" name="intentId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="expectedEpoch" name="expectedEpoch" rules={[{ required: true }]}><Input inputMode="numeric" /></Form.Item>
              <Form.Item label="原因" name="reason" rules={[{ required: true }]}><Input /></Form.Item>
            </CommandCard>
          </CommandDrawer>
        )}
      />
      <Card
        size="small"
        title="消息积压"
        extra={(
          <Space>
            <Select
              aria-label="消息服务"
              value={service}
              style={{ width: 140 }}
              options={[
                { value: "inventory", label: "库存" },
                { value: "inbound", label: "入库" },
                { value: "outbound", label: "出库" },
                { value: "fulfillment", label: "履约" }
              ]}
              onChange={(value) => {
                const merged = new URLSearchParams(search);
                merged.set("service", value);
                merged.delete("mc");
                setSearch(merged, { replace: true });
              }}
            />
            <Select
              aria-label="消息队列"
              value={queue}
              style={{ width: 120 }}
              options={[{ value: "INBOX", label: "INBOX" }, { value: "OUTBOX", label: "OUTBOX" }]}
              onChange={(value) => {
                const merged = new URLSearchParams(search);
                merged.set("queue", value);
                merged.delete("mc");
                setSearch(merged, { replace: true });
              }}
            />
          </Space>
        )}
      >
        <DocumentListPage
          secondary
          title={`${service} ${queue}`}
          sub="不返回消息正文。默认看 ISOLATED。"
          empty={`当前没有 ${queue} 隔离消息`}
          cursorKey="mc"
          queryKey="mq"
          columns={[
            { key: "id", label: "消息", keys: ["id", "messageId"], kind: "id", copyKind: "消息" },
            { key: "status", label: "状态", keys: ["state", "status"], kind: "status" },
            { key: "epoch", label: "代际", keys: ["epoch", "leaseEpoch"] }
          ]}
          paths={ready ? [`/api/wms/v1/warehouses/${warehouseId}/message-queues/${queue}/messages?service=${encodeURIComponent(service)}`] : []}
          actions={(
            <CommandDrawer
              triggerLabel="重排消息"
              title="重排隔离消息"
              hint="保持原消息身份，不重置领取代际。"
              triggerType="default"
              requireScope="messaging.recover"
              disabled={!ready}
            >
              <CommandCard
                embedded
                pollOperation={service === "inventory"}
                requireScope="messaging.recover"
                title="重排消息"
                hint="202 不是业务完成。"
                operation={`msg-retry:${warehouseId}:${service}:${queue}`}
                submitLabel="重排"
                disabled={!token || !ready}
                onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/message-queues/${queue}/messages/${values.messageId}/retries?service=${encodeURIComponent(service)}`, token, {
                  method: "POST",
                  idempotencyKey: key,
                  body: { expectedEpoch: Number(values.expectedEpoch || "0"), reason: values.reason }
                })}
              >
                <Form.Item label="消息" name="messageId" rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item label="expectedEpoch" name="expectedEpoch" rules={[{ required: true }]}><Input inputMode="numeric" /></Form.Item>
                <Form.Item label="原因" name="reason" rules={[{ required: true }]}><Input /></Form.Item>
              </CommandCard>
            </CommandDrawer>
          )}
        />
      </Card>
    </Space>
  );
}
