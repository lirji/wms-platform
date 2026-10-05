import { Form, Input } from 'antd';
import { api } from '../../api/client';
import { recordId } from '../../api/envelope';
import { CommandCard } from '../../shared/command/CommandCard';
import { DocumentListPage } from '../lists/DocumentListPage';
import { useWorkspace } from '../../shell/WorkspaceContext';

export function FulfillmentPage() {
  const { token, warehouseId } = useWorkspace();
  const ready = warehouseId && warehouseId !== '_';
  return (
    <>
      <DocumentListPage
        title="履约单"
        extra="tcc"
        sub="准备分配只创建 attempt。没有 TC 终态证据时，页面不会把单仓 Confirmed 写成整单成功。"
        empty="当前企业没有履约单"
        columns={[
          { key: 'id', label: '标识', keys: ['id', 'fulfillmentId'], kind: 'id', copyKind: '单据' },
          { key: 'status', label: '状态', keys: ['status', 'state'], kind: 'status' },
          { key: 'skuId', label: 'SKU', keys: ['skuId', 'sku_id'], kind: 'id', copyKind: 'SKU' },
          {
            key: 'requestedQty',
            label: '数量',
            qty: true,
            keys: ['requestedQty', 'requested_qty'],
          },
          { key: 'sourceOrderNo', label: '来源单号', keys: ['sourceOrderNo', 'source_order_no'] },
        ]}
        paths={ready ? ['/api/wms/v1/fulfillments'] : []}
        hrefFor={(row) =>
          ready
            ? `/w/${warehouseId}/fulfillment/${recordId(row, 'id', 'fulfillmentId')}`
            : undefined
        }
        createLabel="创建履约单"
        createTitle="创建履约单"
        createScope="fulfillment.create"
        createHint="表单在居中弹层。同源单号摘要冲突由服务端拒绝。"
        create={
          <CommandCard
            embedded
            requireScope="fulfillment.create"
            title="创建履约单"
            hint="同源单号摘要冲突由服务端拒绝。"
            operation={`fulfillment-create:${warehouseId}`}
            submitLabel="创建履约单"
            disabled={!token}
            onRun={(key, values) =>
              api('/api/wms/v1/fulfillments', token, {
                method: 'POST',
                idempotencyKey: key,
                body: {
                  sourceSystem: values.sourceSystem || 'OMS',
                  sourceOrderNo: values.sourceOrderNo,
                  ownerId: values.ownerId,
                  strategyVersion: 1,
                  lines: [
                    {
                      sourceLineId: values.sourceLineId || 'SL-1',
                      skuId: values.skuId,
                      requestedQty: values.requestedQty,
                      baseUnit: values.baseUnit || 'EA',
                    },
                  ],
                },
              })
            }
          >
            <Form.Item label="来源系统" name="sourceSystem" initialValue="OMS">
              <Input />
            </Form.Item>
            <Form.Item label="来源单号" name="sourceOrderNo" rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item label="货主" name="ownerId" rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item label="来源行" name="sourceLineId" initialValue="SL-1">
              <Input />
            </Form.Item>
            <Form.Item label="SKU" name="skuId" rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item label="请求数量" name="requestedQty" rules={[{ required: true }]}>
              <Input inputMode="decimal" />
            </Form.Item>
            <Form.Item label="单位" name="baseUnit" initialValue="EA">
              <Input />
            </Form.Item>
          </CommandCard>
        }
      />
      <DocumentListPage
        secondary
        title="本仓出库"
        sub="次表面：拣、包装、部分发运、未拣取消回库在出库详情。"
        empty={`当前仓 ${warehouseId || '(未选)'} 没有出库单`}
        columns={[
          { key: 'id', label: '标识', keys: ['id', 'orderId'], kind: 'id', copyKind: '单据' },
          { key: 'status', label: '状态', keys: ['status', 'state'], kind: 'status' },
          { key: 'skuId', label: 'SKU', keys: ['skuId', 'sku_id'], kind: 'id', copyKind: 'SKU' },
          {
            key: 'allocatedQty',
            label: '数量',
            qty: true,
            keys: ['allocatedQty', 'allocated_qty'],
          },
          {
            key: 'pickedQty',
            label: '实物',
            qty: true,
            keys: ['pickedPhysicalQty', 'picked_physical_qty'],
          },
          {
            key: 'stockSyncStatus',
            label: '库存同步',
            keys: ['stockSyncStatus', 'stock_sync_status'],
            kind: 'status',
          },
        ]}
        queryKey="oq"
        cursorKey="oc"
        paths={ready ? [`/api/wms/v1/warehouses/${warehouseId}/outbound-orders`] : []}
        hrefFor={(row) => (ready ? `/w/${warehouseId}/outbound/${recordId(row)}` : undefined)}
      />
    </>
  );
}
