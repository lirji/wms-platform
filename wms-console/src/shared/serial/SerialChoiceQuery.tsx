import { FormEvent, useState } from "react";
import { Button, Card, Form, Input } from "antd";
import { field, nextCursorOf, withQuery } from "../../api/envelope";
import { DataTable } from "../ui/DataTable";
import { errorBanner } from "../ui/errorBanner";
import { ListPager } from "../ui/ListPager";
import { useResource } from "../useResource";
import { useWorkspace } from "../../shell/WorkspaceContext";

type Kind = "selectable" | "shippable";

export function SerialChoiceQuery({
  kind,
  warehouseId,
  outboundOrderId
}: {
  kind: Kind;
  warehouseId: string;
  outboundOrderId?: string;
}) {
  const { token } = useWorkspace();
  const [ownerId, setOwnerId] = useState("");
  const [skuId, setSkuId] = useState("");
  const [locationId, setLocationId] = useState("");
  const [lotId, setLotId] = useState("NO_LOT");
  const [orderLineId, setOrderLineId] = useState("");
  const [applied, setApplied] = useState<Record<string, string>>({});
  const [cursors, setCursors] = useState<string[]>([""]);
  const selectableReady = Boolean(applied.ownerId && applied.skuId && applied.locationId);
  const shippableReady = Boolean(outboundOrderId && applied.orderLineId && applied.locationId && applied.lotId);
  const path = kind === "selectable"
    ? (selectableReady
      ? withQuery(`/api/wms/v1/warehouses/${warehouseId}/serial-stock`, {
        ownerId: applied.ownerId,
        skuId: applied.skuId,
        locationId: applied.locationId,
        lotId: applied.lotId || "NO_LOT",
        limit: "50",
        cursor: cursors.at(-1) || undefined
      })
      : "")
    : (shippableReady
      ? withQuery(`/api/wms/v1/warehouses/${warehouseId}/outbound-orders/${outboundOrderId}/shippable-serials`, {
        orderLineId: applied.orderLineId,
        stagingLocationId: applied.locationId,
        lotId: applied.lotId,
        limit: "50",
        cursor: cursors.at(-1) || undefined
      })
      : "");
  const { rows, payloads, error, loading } = useResource(token, path ? [path] : []);
  const nextCursor = nextCursorOf(payloads[0]);

  function apply(event: FormEvent) {
    event.preventDefault();
    setCursors([""]);
    setApplied(kind === "selectable"
      ? { ownerId: ownerId.trim(), skuId: skuId.trim(), locationId: locationId.trim(), lotId: lotId.trim() || "NO_LOT" }
      : { orderLineId: orderLineId.trim(), locationId: locationId.trim(), lotId: lotId.trim() });
  }

  return (
    <Card
      size="small"
      title={kind === "selectable" ? "可选序列号" : "可发运序列号"}
      extra="查询不预占。提交拣货/发运仍核验当前 epoch。"
    >
      <Form layout="inline" className="list-toolbar" onSubmitCapture={apply} style={{ justifyContent: "flex-start", marginBottom: 12 }}>
        {kind === "selectable" ? (
          <>
            <Form.Item label="货主" required>
              <Input value={ownerId} onChange={(event) => setOwnerId(event.target.value)} />
            </Form.Item>
            <Form.Item label="SKU" required>
              <Input value={skuId} onChange={(event) => setSkuId(event.target.value)} />
            </Form.Item>
            <Form.Item label="来源库位" required>
              <Input value={locationId} onChange={(event) => setLocationId(event.target.value)} />
            </Form.Item>
          </>
        ) : (
          <>
            <Form.Item label="出库行" required>
              <Input value={orderLineId} onChange={(event) => setOrderLineId(event.target.value)} />
            </Form.Item>
            <Form.Item label="集货位" required>
              <Input value={locationId} onChange={(event) => setLocationId(event.target.value)} />
            </Form.Item>
          </>
        )}
        <Form.Item label="批次">
          <Input value={lotId} onChange={(event) => setLotId(event.target.value)} placeholder="NO_LOT" />
        </Form.Item>
        <Button type="primary" htmlType="submit" disabled={!token} loading={loading}>查询</Button>
      </Form>
      {error ? errorBanner(error) : null}
      <DataTable
        rows={rows}
        loading={loading && Boolean(path)}
        emptyText={path ? "当前桶没有可选身份" : "填写完整桶条件后查询"}
        columns={[
          { key: "serialId", label: "序列号", keys: ["serialId", "serial_id"], kind: "id", copyKind: "序列号" },
          { key: "ownerEpoch", label: "ownerEpoch", keys: ["ownerEpoch", "owner_epoch"] },
          { key: "skuId", label: "SKU", keys: ["skuId", "sku_id"], kind: "id", copyKind: "SKU" },
          { key: "locationId", label: "库位", keys: ["locationId", "location_id"] },
          { key: "lotId", label: "批次", keys: ["lotId", "lot_id"] }
        ]}
      />
      <ListPager
        countLabel={path ? `本页 ${rows.length} 条` : undefined}
        prevDisabled={loading || cursors.length === 1}
        nextDisabled={loading || !nextCursor}
        onPrev={() => setCursors((prev) => prev.slice(0, -1))}
        onNext={() => setCursors((prev) => [...prev, nextCursor])}
      />
      {rows[0] ? (
        <p style={{ color: "rgba(0,0,0,0.45)", marginTop: 8 }}>
          提交命令时按「{field(rows[0], "serialId")} {field(rows[0], "ownerEpoch")}」格式抄入身份清单，不要手写旧代际。
        </p>
      ) : null}
    </Card>
  );
}
