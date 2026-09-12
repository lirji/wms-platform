import { FormEvent, useState } from "react";
import { Button, Card, Form, Input, Space } from "antd";
import { api } from "../../api/client";
import { field, pageItems, recordId, type ItemRecord } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandDrawer } from "../../shared/command/CommandDrawer";
import { DataTable } from "../../shared/ui/DataTable";
import { errorBanner } from "../../shared/ui/errorBanner";
import { PageHead } from "../../shared/ui/PageHead";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function ReconPage() {
  const { token, warehouseId, warehouseName } = useWorkspace();
  const [cutoffId, setCutoffId] = useState("");
  const [rows, setRows] = useState<ItemRecord[]>([]);
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  async function load(event: FormEvent) {
    event.preventDefault();
    if (!token || !warehouseId || warehouseId === "_") {
      return;
    }
    setBusy(true);
    try {
      const body = await api(
        `/api/wms/v1/warehouses/${warehouseId}/reconciliation-cases?cutoffId=${encodeURIComponent(cutoffId)}`,
        token
      );
      setRows(pageItems(body));
      setError(undefined);
      setLoaded(true);
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Space orientation="vertical" size={16} style={{ display: "flex" }}>
      <PageHead
        eyebrow={warehouseName || warehouseId || "未选仓"}
        title="对账差异"
        sub="按仓与 cutoff 查询服务端差异。审批修复只写 APPROVE/REJECT，不在页面改余额。"
        extra={(
          <CommandDrawer triggerLabel="审批修复" title="审批修复" hint="不在列表下再铺一张长表单。">
            <CommandCard
              embedded
              title="审批修复"
              hint="APPROVE 进入 REMEDIATING；REJECT 关闭。必须带最新 version，冲突不换幂等键。"
              operation={`recon-fix:${warehouseId}:${cutoffId}`}
              submitLabel="提交审批"
              disabled={!token || !warehouseId || warehouseId === "_" || rows.length === 0}
              onDone={() => {
                if (cutoffId) {
                  void api(
                    `/api/wms/v1/warehouses/${warehouseId}/reconciliation-cases?cutoffId=${encodeURIComponent(cutoffId)}`,
                    token
                  ).then((body) => setRows(pageItems(body)));
                }
              }}
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
        )}
      />
      <Card>
        <Form layout="inline" onSubmitCapture={load}>
          <Form.Item label="cutoffId" required>
            <Input value={cutoffId} onChange={(event) => setCutoffId(event.target.value)} placeholder="由对账任务返回，不在页面写死" />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={busy} disabled={!cutoffId || !warehouseId || warehouseId === "_"}>
            加载差异
          </Button>
        </Form>
      </Card>
      {error ? errorBanner(error) : null}
      <Card title="差异列表" extra={loaded ? `接口返回 ${rows.length} 条` : "输入 cutoff 后查询"}>
        <DataTable
          rows={rows}
          emptyText={loaded ? `当前 cutoff ${cutoffId || "(未填)"} 没有差异` : "输入 cutoff 后查询"}
          columns={[
            { key: "id", label: "差异", keys: ["id", "caseId"] },
            { key: "status", label: "状态", keys: ["status", "state"] },
            { key: "skuId", label: "SKU", keys: ["skuId", "sku_id"] },
            { key: "qty", label: "数量", qty: true, keys: ["qty", "deltaQty", "expected_qty"] }
          ]}
        />
      </Card>
    </Space>
  );
}
