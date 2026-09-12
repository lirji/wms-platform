import { FormEvent, useState } from "react";
import { Button, Card, Form, Input, Space } from "antd";
import { api } from "../../api/client";
import { pageItems, type ItemRecord } from "../../api/envelope";
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
        sub="按仓与 cutoff 查询服务端差异，页面不预置差异列表。"
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
            { key: "status", label: "状态", keys: ["status"] },
            { key: "skuId", label: "SKU", keys: ["skuId"] },
            { key: "qty", label: "数量", qty: true, keys: ["qty", "deltaQty"] }
          ]}
        />
      </Card>
    </Space>
  );
}
