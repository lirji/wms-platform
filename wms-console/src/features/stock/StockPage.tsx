import { Form, Input } from "antd";
import { api } from "../../api/client";
import { recordId } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandDrawer } from "../../shared/command/CommandDrawer";
import { DocumentListPage } from "../lists/DocumentListPage";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function StockPage() {
  const { token, warehouseId } = useWorkspace();
  const ready = warehouseId && warehouseId !== "_";
  return (
    <DocumentListPage
      title="库存台账"
      sub="数量按字符串展示，不在浏览器做发运量运算。打开一行查看流水。移库/限制/独立调整写库存域，不是盘点冻结。"
      empty={`当前仓 ${warehouseId || "(未选)"} 没有库存行`}
      columns={[
        { key: "skuId", label: "SKU", keys: ["skuId"] },
        { key: "lotId", label: "批次", keys: ["lotId"] },
        { key: "onHandQty", label: "在手", qty: true, keys: ["onHandQty", "qty", "quantity"] },
        { key: "reservedQty", label: "预占", qty: true, keys: ["reservedQty"] },
        { key: "availableQty", label: "可用", qty: true, keys: ["availableQty"] },
        { key: "qualityCode", label: "质量", keys: ["qualityCode"] }
      ]}
      paths={ready ? [`/api/wms/v1/inventory?warehouseIds=${encodeURIComponent(warehouseId)}`] : []}
      hrefFor={(row) => ready ? `/w/${warehouseId}/stock/${recordId(row)}` : undefined}
      actions={(
        <>
          <CommandDrawer triggerLabel="同仓移库" title="同仓移库" hint="202 表示单据已受理。跨仓请走调拨。" requireScope="stock.move" disabled={!ready}>
            <CommandCard
              embedded
              pollOperation
              requireScope="stock.move"
              title="同仓移库"
              hint="从源桶移到目标库位。返回 202。"
              operation={`move:${warehouseId}`}
              submitLabel="提交移库"
              disabled={!token || !ready}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/moves`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  sourceBalanceId: values.sourceBalanceId,
                  targetLocationId: values.targetLocationId,
                  qty: values.qty,
                  unit: values.unit || "EA",
                  reason: values.reason,
                  clientOperationId: key
                }
              })}
            >
              <Form.Item label="源库存桶" name="sourceBalanceId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="目标库位" name="targetLocationId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
              <Form.Item label="原因" name="reason" rules={[{ required: true }]}><Input /></Form.Item>
            </CommandCard>
          </CommandDrawer>
          <CommandDrawer triggerLabel="库存限制" title="库存限制" hint="占用 reserved，不是盘点冻结。" triggerType="default" requireScope="stock.hold" disabled={!ready}>
            <CommandCard
              embedded
              pollOperation
              requireScope="stock.hold"
              title="库存限制"
              hint="必须指定库存桶与数量。"
              operation={`hold:${warehouseId}`}
              submitLabel="提交限制"
              disabled={!token || !ready}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/stock-holds`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  scope: { balanceId: values.balanceId, qty: values.qty },
                  reason: values.reason,
                  clientOperationId: key
                }
              })}
            >
              <Form.Item label="库存桶" name="balanceId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
              <Form.Item label="原因" name="reason" rules={[{ required: true }]}><Input /></Form.Item>
            </CommandCard>
          </CommandDrawer>
          <CommandDrawer triggerLabel="释放限制" title="释放限制" hint="只释放 stock-hold，不解冻盘点。" triggerType="default" requireScope="stock.releaseHold" disabled={!ready}>
            <CommandCard
              embedded
              pollOperation
              requireScope="stock.releaseHold"
              title="释放限制"
              hint="需要限制标识与 expectedVersion。"
              operation={`hold-release:${warehouseId}`}
              submitLabel="释放"
              disabled={!token || !ready}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/stock-holds/${values.holdId}/releases`, token, {
                method: "POST",
                idempotencyKey: key,
                body: { expectedVersion: Number(values.expectedVersion), reason: values.reason, clientOperationId: key }
              })}
            >
              <Form.Item label="限制标识" name="holdId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="expectedVersion" name="expectedVersion" rules={[{ required: true }]}><Input inputMode="numeric" /></Form.Item>
              <Form.Item label="原因" name="reason"><Input /></Form.Item>
            </CommandCard>
          </CommandDrawer>
          <CommandDrawer triggerLabel="独立调整" title="独立调整" hint="与盘点按行调整不是同一张单。" triggerType="default" requireScope="adjustment.create" disabled={!ready}>
            <CommandCard
              embedded
              pollOperation
              requireScope="adjustment.create"
              title="创建调整"
              hint="创建后还要审批再应用。"
              operation={`adj-create:${warehouseId}`}
              submitLabel="创建调整"
              disabled={!token || !ready}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/adjustments`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  balanceId: values.balanceId,
                  deltaQty: values.deltaQty,
                  reason: values.reason,
                  clientOperationId: key
                }
              })}
            >
              <Form.Item label="库存桶" name="balanceId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="增量" name="deltaQty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
              <Form.Item label="原因" name="reason" rules={[{ required: true }]}><Input /></Form.Item>
            </CommandCard>
          </CommandDrawer>
          <CommandDrawer triggerLabel="审批调整" title="审批调整" hint="未审批不能过账。" triggerType="default" requireScope="adjustment.approve" disabled={!ready}>
            <CommandCard
              embedded
              pollOperation
              requireScope="adjustment.approve"
              title="审批调整"
              hint="结论只能是 APPROVED 或 REJECTED。"
              operation={`adj-approve:${warehouseId}`}
              submitLabel="提交审批"
              disabled={!token || !ready}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/adjustments/${values.adjustmentId}/approvals`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  decision: values.decision,
                  expectedVersion: Number(values.expectedVersion),
                  reason: values.reason,
                  clientOperationId: key
                }
              })}
            >
              <Form.Item label="调整单" name="adjustmentId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="结论" name="decision" initialValue="APPROVED" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="expectedVersion" name="expectedVersion" rules={[{ required: true }]}><Input inputMode="numeric" /></Form.Item>
              <Form.Item label="意见" name="reason"><Input /></Form.Item>
            </CommandCard>
          </CommandDrawer>
          <CommandDrawer triggerLabel="应用调整" title="应用调整" hint="202 表示已过账受理。" triggerType="default" requireScope="adjustment.apply" disabled={!ready}>
            <CommandCard
              embedded
              pollOperation
              requireScope="adjustment.apply"
              title="应用调整"
              hint="必须先审批。"
              operation={`adj-apply:${warehouseId}`}
              submitLabel="应用"
              disabled={!token || !ready}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/adjustments/${values.adjustmentId}/applications`, token, {
                method: "POST",
                idempotencyKey: key,
                body: { expectedVersion: Number(values.expectedVersion), clientOperationId: key }
              })}
            >
              <Form.Item label="调整单" name="adjustmentId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="expectedVersion" name="expectedVersion" rules={[{ required: true }]}><Input inputMode="numeric" /></Form.Item>
            </CommandCard>
          </CommandDrawer>
        </>
      )}
    />
  );
}
