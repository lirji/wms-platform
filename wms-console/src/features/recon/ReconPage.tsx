import { FormEvent, useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Button, Card, Descriptions, Form, Input, Space } from "antd";
import { api } from "../../api/client";
import { field, recordId, withQuery } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandDrawer } from "../../shared/command/CommandDrawer";
import { DataTable } from "../../shared/ui/DataTable";
import { errorBanner } from "../../shared/ui/errorBanner";
import { PageHead } from "../../shared/ui/PageHead";
import { StatusChip } from "../../shared/ui/StatusChip";
import { useDocument } from "../../shared/useDocument";
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
  const windowPath = ready
    ? `/api/wms/v1/warehouses/${warehouseId}/reconciliation-windows/${encodeURIComponent(cutoffId)}`
    : undefined;
  const windowDoc = useDocument(token, windowPath);
  const [snapshotId, setSnapshotId] = useState("");
  const [afterPart, setAfterPart] = useState("0");
  const [snapshotTick, setSnapshotTick] = useState(0);
  const snapshotPath = snapshotTick > 0 && snapshotId.trim()
    ? withQuery(`/api/wms/v1/reconciliation-snapshots/${encodeURIComponent(snapshotId.trim())}`, {
      afterPart: afterPart.trim() || "0"
    })
    : undefined;
  const snapshot = useDocument(token, snapshotPath, snapshotTick);

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
        sub="先看窗口状态。只有 COMPLETE 才返回服务器三方水位。审批修复只写 APPROVE/REJECT，不在页面改余额。"
      />
      <Card>
        <Form layout="inline" className="list-toolbar" onSubmitCapture={load} style={{ justifyContent: "flex-start" }}>
          <Form.Item label="cutoffId" required>
            <Input value={draft} onChange={(event) => setDraft(event.target.value)} placeholder="由对账任务返回，不在页面写死" />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={loading} disabled={!warehouseId || warehouseId === "_" || !draft.trim()}>
            加载差异
          </Button>
          <CommandDrawer
            triggerLabel="请求窗口"
            title="请求对账窗口"
            hint="只提交历史 cutoff。调用者不能设置水位或完成状态。"
            triggerType="default"
            requireScope="recon.export"
            disabled={!warehouseId || warehouseId === "_"}
          >
            <CommandCard
              embedded
              pollOperation
              requireScope="recon.export"
              title="请求窗口"
              hint="202 表示已受理采集。不要手写水位。"
              operation={`recon-window:${warehouseId}:${cutoffId || "draft"}`}
              submitLabel="请求窗口"
              disabled={!token || !warehouseId || warehouseId === "_"}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/reconciliation-windows/${encodeURIComponent(values.cutoffId || cutoffId)}`, token, {
                method: "POST",
                idempotencyKey: key,
                body: { cutoff: values.cutoff }
              })}
            >
              <Form.Item label="cutoffId" name="cutoffId" initialValue={cutoffId} rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="关闭时刻 UTC" name="cutoff" rules={[{ required: true }]} extra="过去的排他上界。"><Input placeholder="2026-09-10T13:00:00Z" /></Form.Item>
            </CommandCard>
          </CommandDrawer>
          <CommandDrawer
            triggerLabel="重排窗口"
            title="重排对账窗口"
            hint="隔离后按原领取代际重排。旧代际返回 409。"
            triggerType="default"
            requireScope="recon.remediate"
            disabled={!ready}
          >
            <CommandCard
              embedded
              requireScope="recon.remediate"
              title="重排窗口"
              hint="必须带当前 claimEpoch。"
              operation={`recon-window-retry:${warehouseId}:${cutoffId}`}
              submitLabel="重排"
              disabled={!token || !ready}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/reconciliation-windows/${encodeURIComponent(cutoffId)}/retries`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  expectedClaimEpoch: Number(values.expectedClaimEpoch || field(windowDoc.record, "claimEpoch") || "0"),
                  reason: values.reason
                }
              })}
            >
              <Form.Item label="expectedClaimEpoch" name="expectedClaimEpoch"><Input placeholder="默认用当前窗口" /></Form.Item>
              <Form.Item label="原因" name="reason" rules={[{ required: true }]}><Input /></Form.Item>
            </CommandCard>
          </CommandDrawer>
          <CommandDrawer
            triggerLabel="取消窗口"
            title="取消对账窗口"
            hint="停止采集并释放活动名额。已提交业务效果保留。"
            triggerType="default"
            requireScope="recon.remediate"
            disabled={!ready}
          >
            <CommandCard
              embedded
              danger
              requireScope="recon.remediate"
              title="取消窗口"
              hint="必须带当前 claimEpoch。"
              operation={`recon-window-cancel:${warehouseId}:${cutoffId}`}
              submitLabel="取消采集"
              disabled={!token || !ready}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/reconciliation-windows/${encodeURIComponent(cutoffId)}/cancellations`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  expectedClaimEpoch: Number(values.expectedClaimEpoch || field(windowDoc.record, "claimEpoch") || "0"),
                  reason: values.reason
                }
              })}
            >
              <Form.Item label="expectedClaimEpoch" name="expectedClaimEpoch"><Input placeholder="默认用当前窗口" /></Form.Item>
              <Form.Item label="原因" name="reason" rules={[{ required: true }]}><Input /></Form.Item>
            </CommandCard>
          </CommandDrawer>
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
              pollOperation
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
      {ready ? (
        <Card title={`窗口 ${cutoffId}`} extra="COMPLETE 才返回服务器三方标识，页面不手写水位。">
          {windowDoc.error ? errorBanner(windowDoc.error) : null}
          <Descriptions
            size="small"
            column={2}
            items={[
              { key: "state", label: "状态", children: field(windowDoc.record, "state") ? <StatusChip value={field(windowDoc.record, "state")} /> : "—" },
              { key: "claim", label: "claimEpoch", children: field(windowDoc.record, "claimEpoch") || "—" },
              { key: "attempts", label: "attempts", children: field(windowDoc.record, "attempts") || "—" },
              { key: "complete", label: "水位齐", children: field(windowDoc.record, "watermarksComplete") || "—" },
              { key: "source", label: "sourceWatermark", children: field(windowDoc.record, "sourceWatermark") || "—" },
              { key: "posting", label: "postingWatermark", children: field(windowDoc.record, "postingWatermark") || "—" },
              { key: "receipt", label: "receiptWatermark", children: field(windowDoc.record, "receiptWatermark") || "—" },
              { key: "error", label: "errorCode", children: field(windowDoc.record, "errorCode") || "—" }
            ]}
          />
        </Card>
      ) : null}
      <Card title="快照分段" extra="nextPartNo 作为下次 afterPart。旧快照缺证明会 SOURCE_INCOMPLETE。">
        <Form layout="inline" className="list-toolbar" onSubmitCapture={(event: FormEvent) => {
          event.preventDefault();
          setSnapshotTick((current) => current + 1);
        }}>
          <Form.Item label="snapshotId" required>
            <Input value={snapshotId} onChange={(event) => setSnapshotId(event.target.value)} />
          </Form.Item>
          <Form.Item label="afterPart">
            <Input value={afterPart} onChange={(event) => setAfterPart(event.target.value)} inputMode="numeric" />
          </Form.Item>
          <Button type="primary" htmlType="submit" disabled={!token || !snapshotId.trim()} loading={snapshot.loading}>读取快照</Button>
        </Form>
        {snapshot.error ? errorBanner(snapshot.error) : null}
        {snapshotTick > 0 && !snapshot.loading && !snapshot.error ? (
          <Descriptions
            size="small"
            column={2}
            style={{ marginTop: 12 }}
            items={[
              { key: "id", label: "快照", children: field(snapshot.record, "id", "snapshotId") || snapshotId },
              { key: "status", label: "状态", children: field(snapshot.record, "status", "state", "code") || "—" },
              { key: "next", label: "nextPartNo", children: field(snapshot.record, "nextPartNo") || "—" },
              { key: "cutoff", label: "cutoffId", children: field(snapshot.record, "cutoffId") || "—" }
            ]}
          />
        ) : null}
      </Card>
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
