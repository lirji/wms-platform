import { FormEvent, useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Button, Card, Form, Input, Space } from "antd";
import { api } from "../../api/client";
import { field, pageItems, recordId } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandDrawer } from "../../shared/command/CommandDrawer";
import { DataTable } from "../../shared/ui/DataTable";
import { errorBanner } from "../../shared/ui/errorBanner";
import { PageHead } from "../../shared/ui/PageHead";
import { useResource } from "../../shared/useResource";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function ReconPage() {
  const { token, warehouseId, warehouseName } = useWorkspace();
  const [search, setSearch] = useSearchParams();
  const cutoffId = search.get("cutoffId") ?? "";
  const [draft, setDraft] = useState(cutoffId);
  const ready = Boolean(token && warehouseId && warehouseId !== "_" && cutoffId);
  const { rows, error, loading } = useResource(
    token,
    ready ? [`/api/wms/v1/warehouses/${warehouseId}/reconciliation-cases?cutoffId=${encodeURIComponent(cutoffId)}`] : []
  );

  useEffect(() => {
    setDraft(cutoffId);
  }, [cutoffId]);

  function load(event: FormEvent) {
    event.preventDefault();
    const merged = new URLSearchParams(search);
    const value = draft.trim();
    if (value) {
      merged.set("cutoffId", value);
    } else {
      merged.delete("cutoffId");
    }
    setSearch(merged, { replace: true });
  }

  return (
    <Space orientation="vertical" size={16} style={{ display: "flex" }}>
      <PageHead
        eyebrow={warehouseName || warehouseId || "未选仓"}
        title="对账差异"
        sub="按仓与 cutoff 查询服务端差异。审批修复只写 APPROVE/REJECT，不在页面改余额。"
      />
      <Card>
        <Form layout="inline" onSubmitCapture={load}>
          <Form.Item label="cutoffId" required>
            <Input value={draft} onChange={(event) => setDraft(event.target.value)} placeholder="由对账任务返回，不在页面写死" />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={loading} disabled={!warehouseId || warehouseId === "_" || !draft.trim()}>
            加载差异
          </Button>
          <CommandDrawer
            triggerLabel="导出快照"
            title="导出对账快照"
            hint="快照按批生成。水位不齐会被服务端拒绝；状态为 COMPLETE 时才可用于对账。"
            triggerType="default"
            requireScope="recon.export"
            disabled={!warehouseId || warehouseId === "_"}
          >
            <CommandCard
              embedded
              requireScope="recon.export"
              title="导出快照"
              hint="填写关闭时刻与三方水位。若返回 EXPORTING，保持表单内容再次提交续跑，直到 COMPLETE。"
              operation={`recon-export:${warehouseId}:${cutoffId || "draft"}`}
              submitLabel="导出 / 继续生成"
              disabled={!token || !warehouseId || warehouseId === "_"}
              onRun={(key, values) => api("/api/wms/v1/reconciliation-snapshots", token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  warehouseIds: [warehouseId],
                  cutoffId: values.cutoffId || cutoffId,
                  cutoff: values.cutoff,
                  sourceWatermark: values.sourceWatermark,
                  postingWatermark: values.postingWatermark,
                  receiptWatermark: values.receiptWatermark
                }
              })}
            >
              <Form.Item label="cutoffId" name="cutoffId" initialValue={cutoffId} rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="关闭时刻 UTC" name="cutoff" rules={[{ required: true }]}><Input placeholder="2026-09-10T13:00:00Z" /></Form.Item>
              <Form.Item label="sourceWatermark" name="sourceWatermark" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="postingWatermark" name="postingWatermark" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="receiptWatermark" name="receiptWatermark" rules={[{ required: true }]}><Input /></Form.Item>
            </CommandCard>
          </CommandDrawer>
          <CommandDrawer
            triggerLabel="审批修复"
            title="审批修复"
            hint="这是次要动作。APPROVE / REJECT 不改页面余额。"
            triggerType="default"
            requireScope="recon.remediate"
          >
            <CommandCard
              embedded
              requireScope="recon.remediate"
              title="审批修复"
              hint="APPROVE 进入 REMEDIATING；REJECT 关闭。必须带最新 version，冲突不换幂等键。"
              operation={`recon-fix:${warehouseId}:${cutoffId}`}
              submitLabel="提交审批"
              disabled={!token || !ready || rows.length === 0}
              onRun={(key, values) => {
                const caseId = values.caseId || recordId(rows[0] ?? {});
                return api(`/api/wms/v1/warehouses/${warehouseId}/reconciliation-cases/${caseId}/remediations`, token, {
                  method: "POST",
                  idempotencyKey: key,
                  body: {
                    approvedAction: values.approvedAction || "APPROVE",
                    reason: values.reason,
                    expectedVersion: Number(values.expectedVersion || field(rows[0] ?? {}, "version") || "0")
                  }
                });
              }}
            >
              <Form.Item label="差异单" name="caseId"><Input placeholder="默认第一条" /></Form.Item>
              <Form.Item label="动作" name="approvedAction" initialValue="APPROVE"><Input placeholder="APPROVE 或 REJECT" /></Form.Item>
              <Form.Item label="原因" name="reason" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="expectedVersion" name="expectedVersion"><Input placeholder="默认用当前列表版本" /></Form.Item>
            </CommandCard>
          </CommandDrawer>
        </Form>
      </Card>
      {error ? errorBanner(error) : null}
      <Card title="差异列表" extra={cutoffId ? `接口返回 ${rows.length} 条` : "输入 cutoff 后查询"}>
        <DataTable
          caption="对账差异"
          rows={rows}
          loading={loading && ready}
          emptyText={cutoffId ? `当前 cutoff ${cutoffId} 没有差异` : "输入 cutoff 后查询"}
          columns={[
            { key: "id", label: "差异", keys: ["id", "caseId"], kind: "id", copyKind: "差异" },
            { key: "status", label: "状态", keys: ["status", "state"], kind: "status" },
            { key: "skuId", label: "SKU", keys: ["skuId", "sku_id"], kind: "id", copyKind: "SKU" },
            { key: "qty", label: "数量", qty: true, keys: ["qty", "deltaQty", "expected_qty"] }
          ]}
        />
      </Card>
    </Space>
  );
}
