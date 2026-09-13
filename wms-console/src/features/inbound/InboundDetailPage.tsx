import { useState } from "react";
import { Button, Card, Form, Input, Select, Table } from "antd";
import { useParams } from "react-router-dom";
import { field, nextCursorOf, withQuery, type ItemRecord } from "../../api/envelope";
import { useResource } from "../../shared/useResource";
import { errorBanner } from "../../shared/ui/errorBanner";
import { ListPager } from "../../shared/ui/ListPager";
import { api } from "../../api/client";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandCol, DocumentWorkbench } from "../../shared/document/DocumentWorkbench";
import { SerialIdsField } from "../../shared/serial/SerialIdsField";
import { countText, qualityObservation, receiptObservation, stockSelection } from "../../shared/serial/serialIds";
import { useDocument } from "../../shared/useDocument";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function InboundDetailPage() {
  const { warehouseId = "", inboundOrderId = "" } = useParams();
  const { token } = useWorkspace();
  const [tick, setTick] = useState(0);
  const path = warehouseId && inboundOrderId
    ? `/api/wms/v1/warehouses/${warehouseId}/inbound-orders/${inboundOrderId}`
    : undefined;
  const { record, error, loading } = useDocument(token, path, tick);
  const reload = () => setTick((current) => current + 1);
  const [cursors, setCursors] = useState<string[]>([""]);
  const batches = useResource(token, path ? [withQuery(`${path}/receipts`, { limit: "50", cursor: cursors.at(-1) })] : [], tick);
  const nextCursor = nextCursorOf(batches.payloads[0]);
  function batchValues(values: Record<string, string>) {
    const batch = batches.rows.find((row) => row.receiptCommandId === values.receiptCommandId);
    if (!batch || batches.loading || batches.error) throw new Error("请刷新并选择当前收货批次");
    return { receiptCommandId: values.receiptCommandId, lineId: field(batch, "lineId"), sourceVersion: Number(batch.qualitySourceVersion) + 1 };
  }

  return (
    <>
    <DocumentWorkbench
      backTo={`/w/${warehouseId}/inbound`}
      backLabel="返回入库列表"
      title={`入库单 ${inboundOrderId}`}
      sub="收货返回 202 表示实物已记、库存待同步。上架前必须质检合格，目标必须是存储位。"
      loading={loading}
      error={error}
      record={record}
      extraColumns={[
        { key: "received", label: "已收实物", qty: true, keys: ["received_physical_qty"] },
        { key: "putaway", label: "已上架", qty: true, keys: ["putaway_physical_qty"] }
      ]}
      commands={(
        <>
          <CommandCol title="收货" requireScope="inbound.receive">
            <CommandCard
              embedded
              requireScope="inbound.receive"
              title="收货"
              hint="不超过剩余应收。同幂等键重试不会换命令。"
              operation={`receive:${warehouseId}:${inboundOrderId}`}
              submitLabel="提交收货"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => {
                const serialObservation = receiptObservation(values.serialIds || "");
                return api(`/api/wms/v1/warehouses/${warehouseId}/inbound-orders/${inboundOrderId}/receipts`, token, {
                  method: "POST",
                  idempotencyKey: key,
                  body: {
                    lineId: values.lineId,
                    locationId: values.locationId,
                    lotId: values.lotId,
                    qty: serialObservation ? countText(serialObservation.serialIds) : values.qty,
                    receiptPartId: `PART-${key}`,
                    clientOperationId: key,
                    ...(serialObservation ? { serialObservation } : {})
                  }
                });
              }}
            >
              <Form.Item label="行" name="lineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="收货库位" name="locationId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="货品批次" name="lotId" extra="不按批次管理的货品填写 NO_LOT；其余填写已建档批次。" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" extra="填写身份清单时按身份个数提交，不在浏览器做小数运算。" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
              <SerialIdsField name="serialIds" label="序列号观察" extra="序列号 SKU 必填完整清单。数量按身份个数提交。" />
            </CommandCard>
          </CommandCol>
          <CommandCol title="质检" requireScope="quality.inspect">
            <CommandCard
              embedded
              requireScope="quality.inspect"
              title="质检"
              hint="填写所选收货批次的累计合格量和不合格量。前次库存同步完成后才能修订。"
              operation={`qc:${warehouseId}:${inboundOrderId}`}
              submitLabel="记录质检"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => {
                const serialQualityObservation = qualityObservation(values.acceptedSerials || "", values.rejectedSerials || "");
                return api(`/api/wms/v1/warehouses/${warehouseId}/quality-inspections/${key}/results`, token, {
                  method: "POST",
                  idempotencyKey: key,
                  body: {
                    ...batchValues(values),
                    acceptedQty: serialQualityObservation ? countText(serialQualityObservation.acceptedSerials) : values.acceptedQty,
                    rejectedQty: serialQualityObservation ? countText(serialQualityObservation.rejectedSerials) : (values.rejectedQty || "0"),
                    ...(serialQualityObservation ? { serialQualityObservation } : {})
                  }
                });
              }}
            >
              <ReceiptBatchField rows={batches.rows} disabled={batches.loading || Boolean(batches.error)} />
              <Form.Item label="累计合格量" name="acceptedQty" extra="填写合格身份时按身份个数提交。" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
              <Form.Item label="累计不合格量" name="rejectedQty" initialValue="0"><Input inputMode="decimal" /></Form.Item>
              <SerialIdsField name="acceptedSerials" label="累计合格身份" extra="序列号批次填写。未列出的原身份仍 HOLD。" />
              <SerialIdsField name="rejectedSerials" label="累计不合格身份" extra="与合格身份互斥，合计最多 200。" />
            </CommandCard>
          </CommandCol>
          <CommandCol title="上架" requireScope="inbound.putaway">
            <CommandCard
              embedded
              requireScope="inbound.putaway"
              title="上架"
              hint="目标必须是存储位。未质检或不合格会被拒绝。"
              operation={`putaway:${warehouseId}:${inboundOrderId}`}
              submitLabel="提交上架"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => {
                const serialSelection = stockSelection(values.serialIds || "");
                return api(`/api/wms/v1/warehouses/${warehouseId}/tasks/${key}/putaways`, token, {
                  method: "POST",
                  idempotencyKey: key,
                  body: {
                    inboundOrderId,
                    ...batchValues(values),
                    locationId: values.locationId,
                    targetLocationId: values.locationId,
                    locationType: "STORAGE",
                    qty: serialSelection ? countText(serialSelection.serialIds) : values.qty,
                    clientOperationId: key,
                    ...(serialSelection ? { serialSelection } : {})
                  }
                });
              }}
            >
              <ReceiptBatchField rows={batches.rows} requireQuality disabled={batches.loading || Boolean(batches.error)} />
              <Form.Item label="存储库位" name="locationId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" extra="填写身份时按所选身份个数提交，可分次上架。" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
              <SerialIdsField name="serialIds" label="上架身份" extra="序列号批次勾选本次要上架的合格身份，不必一次全部上架。" />
            </CommandCard>
          </CommandCol>
        </>
      )}
    />
    <Card title="收货批次" extra={<Button onClick={reload}>刷新批次</Button>}>
      {batches.error ? errorBanner(batches.error) : null}
      <Table rowKey="id" size="small" loading={batches.loading} dataSource={batches.rows} pagination={false} scroll={{ x: 800 }} columns={[
        { title: "收货批次", dataIndex: "receiptCommandId" },
        { title: "行", dataIndex: "lineId" },
        { title: "收货库位", dataIndex: "locationId" },
        { title: "收货量", dataIndex: "qty" },
        { title: "累计合格", dataIndex: "acceptedQty" },
        { title: "累计不合格", dataIndex: "rejectedQty" },
        { title: "已上架", dataIndex: "putawayQty" },
        { title: "质检同步", render: (_, row) => field(row, "qualityState") === "APPLIED" ? "已同步" : field(row, "qualityState") === "PENDING" ? "同步中" : "未质检" }
      ]} />
      <ListPager
        prevLabel="上一页"
        prevDisabled={batches.loading || cursors.length === 1}
        nextDisabled={batches.loading || !nextCursor}
        onPrev={() => setCursors((prev) => prev.slice(0, -1))}
        onNext={() => setCursors((prev) => [...prev, nextCursor])}
      />
    </Card>
    </>
  );
}

function ReceiptBatchField({ rows, requireQuality, disabled }: { rows: ItemRecord[]; requireQuality?: boolean; disabled: boolean }) {
  return <Form.Item label="收货批次" name="receiptCommandId" extra="选择下方列表当前页中的批次；翻页或刷新后请重新核对。" rules={[{ required: true }]}>
    <Select disabled={disabled} showSearch optionFilterProp="label" options={rows.map((row) => ({
      value: field(row, "receiptCommandId"),
      label: `${field(row, "receiptCommandId")} · ${field(row, "locationId")} · 收货 ${field(row, "qty")} / 合格 ${field(row, "acceptedQty")} / 已上架 ${field(row, "putawayQty")}`,
      disabled: !row.contextAvailable || row.stockSyncStatus !== "APPLIED" || row.qualityState === "PENDING" || (requireQuality && row.qualityState !== "APPLIED")
    }))} />
  </Form.Item>;
}
