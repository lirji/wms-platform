import { useState } from "react";
import { Form, Input } from "antd";
import { useParams } from "react-router-dom";
import { api } from "../../api/client";
import { nestedRecords } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandCol, DocumentWorkbench } from "../../shared/document/DocumentWorkbench";
import { DataTable } from "../../shared/ui/DataTable";
import { useDocument } from "../../shared/useDocument";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function JobDetailPage() {
  const { warehouseId = "", jobId = "" } = useParams();
  const { token } = useWorkspace();
  const [tick, setTick] = useState(0);
  const path = warehouseId && jobId
    ? `/api/wms/v1/jobs/${jobId}?warehouseId=${encodeURIComponent(warehouseId)}`
    : undefined;
  const { record, error, loading } = useDocument(token, path, tick);
  const reload = () => setTick((current) => current + 1);
  const shards = nestedRecords(record, "shards");

  return (
    <DocumentWorkbench
      backTo={`/w/${warehouseId}/jobs`}
      backLabel="返回任务列表"
      title={`任务 ${jobId}`}
      sub="回收过期租约会提升 epoch，旧 fence 立即失效。领取只拿 READY 分片，不会重放设备动作。"
      loading={loading}
      error={error}
      record={record}
      extra={(
        <DataTable
          rows={shards}
          emptyText="没有分片"
          columns={[
            { key: "id", label: "分片", keys: ["id", "shardId"] },
            { key: "status", label: "状态", keys: ["state", "status"] },
            { key: "owner", label: "租约", keys: ["lease_owner", "leaseOwner"] },
            { key: "error", label: "错误", keys: ["last_error", "lastError"] }
          ]}
        />
      )}
      commands={(
        <>
          <CommandCol>
            <CommandCard
              title="回收过期租约"
              hint="接管失联分片，不重新派发设备。"
              operation={`job-reclaim:${jobId}`}
              submitLabel="回收租约"
              disabled={!token}
              onDone={reload}
              onRun={(_key, values) => api(`/api/wms/v1/jobs/${jobId}/retries?warehouseId=${encodeURIComponent(warehouseId)}`, token, {
                method: "POST",
                body: { action: "RECLAIM", reason: values.reason || "lease-expired" }
              })}
            >
              <Form.Item label="原因" name="reason" initialValue="lease-expired"><Input /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol>
            <CommandCard
              title="领取就绪分片"
              hint="没有 READY 分片时 claimed=false。"
              operation={`job-claim:${jobId}`}
              submitLabel="领取分片"
              disabled={!token}
              onDone={reload}
              onRun={(_key, values) => api(`/api/wms/v1/jobs/${jobId}/retries?warehouseId=${encodeURIComponent(warehouseId)}`, token, {
                method: "POST",
                body: { action: "TAKEOVER", reason: values.reason || "manual-claim" }
              })}
            >
              <Form.Item label="原因" name="reason" initialValue="manual-claim"><Input /></Form.Item>
            </CommandCard>
          </CommandCol>
        </>
      )}
    />
  );
}
