import { useState } from "react";
import { Card, Descriptions, Form, Input } from "antd";
import { useParams } from "react-router-dom";
import { api } from "../../api/client";
import { field } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandCol, DocumentWorkbench } from "../../shared/document/DocumentWorkbench";
import { useDocument } from "../../shared/useDocument";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function ActionEffectDetailPage() {
  const { warehouseId = "", effectId = "" } = useParams();
  const { token } = useWorkspace();
  const [tick, setTick] = useState(0);
  const path = warehouseId && effectId
    ? `/api/wms/v1/warehouses/${warehouseId}/action-effects/${effectId}`
    : undefined;
  const { record, error, loading } = useDocument(token, path, tick);
  const reload = () => setTick((current) => current + 1);
  const safeToRetry = record.safeToRetry;
  const retryBlocked = safeToRetry === false;

  return (
    <DocumentWorkbench
      backTo={`/w/${warehouseId}/jobs`}
      backLabel="返回任务列表"
      title={`动作效果 ${effectId}`}
      sub="safeToRetry 只看服务端证据。UNKNOWN 不得直接重做。"
      loading={loading}
      error={error}
      record={record}
      extra={(
        <Card size="small" title="恢复证据">
          <Descriptions
            size="small"
            column={2}
            items={[
              { key: "action", label: "动作", children: field(record, "action") || "—" },
              { key: "safe", label: "safeToRetry", children: field(record, "safeToRetry") || "—" },
              { key: "reason", label: "原因", children: field(record, "safeToRetryReason") || "—" },
              { key: "active", label: "活动命令", children: field(record, "activeCommandId") || "—" },
              { key: "applied", label: "已应用命令", children: field(record, "appliedCommandId") || "—" }
            ]}
          />
        </Card>
      )}
      commands={(
        <CommandCol title="安全重授权" requireScope="task.claim">
          <CommandCard
            embedded
            requireScope="task.claim"
            title="安全重授权"
            hint={retryBlocked
              ? field(record, "safeToRetryReason") || "当前状态不可重做"
              : "202 只表示已受理下一尝试，不把未知结果改写成成功。"}
            operation={`effect-attempt:${warehouseId}:${effectId}`}
            submitLabel="登记下一尝试"
            disabled={!token || retryBlocked}
            onDone={reload}
            onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/action-effects/${effectId}/execution-attempts`, token, {
              method: "POST",
              idempotencyKey: key,
              body: {
                expectedEffectVersion: Number(values.expectedEffectVersion || field(record, "version") || "0"),
                digestVersion: values.digestVersion ? Number(values.digestVersion) : undefined,
                previousCommandId: values.previousCommandId || undefined,
                clientOperationId: key
              }
            })}
          >
            <Form.Item label="expectedEffectVersion" name="expectedEffectVersion">
              <Input placeholder={field(record, "version") || "0"} />
            </Form.Item>
            <Form.Item label="digestVersion" name="digestVersion"><Input /></Form.Item>
            <Form.Item label="previousCommandId" name="previousCommandId"><Input /></Form.Item>
          </CommandCard>
        </CommandCol>
      )}
    />
  );
}
