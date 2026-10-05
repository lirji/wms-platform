import { Alert, Button, Form, Input, Space } from 'antd';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import { recordId, type ItemRecord } from '../../api/envelope';
import { CommandCard } from '../../shared/command/CommandCard';
import { httpStatusOf } from '../../shared/command/accepted';
import { useCommandDialog } from '../../shared/command/dirtyForm';
import { DocumentListPage } from '../lists/DocumentListPage';
import { useWorkspace } from '../../shell/WorkspaceContext';

export function InboundPage() {
  const { token, warehouseId } = useWorkspace();
  const ready = warehouseId && warehouseId !== '_';
  return (
    <DocumentListPage
      title="入库工作台"
      sub="打开单据后做收货、质检、上架。库存同步看 stockSyncStatus。"
      empty={`当前仓 ${warehouseId || '(未选)'} 没有入库单`}
      columns={[
        { key: 'id', label: '标识', keys: ['id', 'orderId'], kind: 'id', copyKind: '单据' },
        { key: 'status', label: '状态', keys: ['status', 'state'], kind: 'status' },
        { key: 'skuId', label: 'SKU', keys: ['skuId', 'sku_id'], kind: 'id', copyKind: 'SKU' },
        { key: 'expectedQty', label: '应收数量', qty: true, keys: ['expectedQty', 'expected_qty'] },
        {
          key: 'receivedQty',
          label: '实物',
          qty: true,
          keys: ['receivedPhysicalQty', 'received_physical_qty'],
        },
        {
          key: 'stockSyncStatus',
          label: '库存同步',
          keys: ['stockSyncStatus', 'stock_sync_status'],
          kind: 'status',
        },
      ]}
      paths={ready ? [`/api/wms/v1/warehouses/${warehouseId}/inbound-orders`] : []}
      hrefFor={(row) =>
        ready ? `/w/${warehouseId}/inbound/${recordId(row, 'id', 'orderId')}` : undefined
      }
      createLabel="创建入库单"
      createTitle="创建入库单"
      createScope="inbound.create"
      createHint={`当前仓 ${warehouseId || '未选'} · 填写入库单和一条入库明细。`}
      clearFilterOnCreate
      create={
        <CommandCard
          embedded
          requireScope="inbound.create"
          title="创建入库单"
          hint="创建后打开单据继续收货、质检和上架。外部单号与行号冲突时，请核对后修改。"
          operation={`inbound-create:${warehouseId}`}
          submitLabel="创建入库单"
          disabled={!ready || !token}
          confirmResult={(result) =>
            httpStatusOf(result) === 201 &&
            Boolean(recordId(result, 'orderId')) &&
            result.status === 'APPROVED'
          }
          renderSuccess={(result, startNext) => (
            <InboundCreated result={result} warehouseId={warehouseId!} startNext={startNext} />
          )}
          onRun={(key, values) =>
            api(`/api/wms/v1/warehouses/${warehouseId}/inbound-orders`, token, {
              method: 'POST',
              idempotencyKey: key,
              body: {
                sourceSystem: values.sourceSystem || 'OMS',
                externalNo: values.externalNo,
                ownerId: values.ownerId || 'OWNER-1',
                inboundOrderId: key,
                lines: [
                  {
                    lineId: values.lineId || key,
                    externalLineId: values.lineId || key,
                    skuId: values.skuId,
                    expectedQty: values.expectedQty,
                    unit: values.unit || 'EA',
                  },
                ],
              },
            })
          }
        >
          <Form.Item
            label="来源系统"
            name="sourceSystem"
            initialValue="OMS"
            rules={[{ required: true, whitespace: true, message: '请填写来源系统' }, { max: 64 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            label="外部单号"
            name="externalNo"
            rules={[{ required: true, whitespace: true, message: '请填写外部单号' }, { max: 64 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            label="货主"
            name="ownerId"
            initialValue="OWNER-1"
            rules={[{ required: true, whitespace: true, message: '请填写货主' }, { max: 64 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            label="行号"
            name="lineId"
            extra="可选，留空自动生成。行号全局唯一。"
            rules={[{ max: 64 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            label="SKU"
            name="skuId"
            rules={[{ required: true, whitespace: true, message: '请填写 SKU' }, { max: 64 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            label="应收数量"
            name="expectedQty"
            extra="大于 0，最多 14 位整数和 6 位小数。按填写精度提交。"
            rules={[
              { required: true, message: '请填写应收数量' },
              {
                validator: (_, value: string) => {
                  if (!value) return Promise.resolve();
                  const integer = value.split('.')[0].replace(/^0+/, '');
                  return /^\d+(\.\d{1,6})?$/.test(value) &&
                    /[1-9]/.test(value) &&
                    integer.length <= 14
                    ? Promise.resolve()
                    : Promise.reject(new Error('请输入大于 0 的数量，最多 14 位整数和 6 位小数'));
                },
              },
            ]}
          >
            <Input inputMode="decimal" />
          </Form.Item>
          <Form.Item
            label="单位"
            name="unit"
            initialValue="EA"
            rules={[{ required: true, whitespace: true, message: '请填写单位' }, { max: 32 }]}
          >
            <Input />
          </Form.Item>
        </CommandCard>
      }
    />
  );
}

function InboundCreated({
  result,
  warehouseId,
  startNext,
}: {
  result: ItemRecord;
  warehouseId: string;
  startNext: () => void;
}) {
  const { close } = useCommandDialog();
  const navigate = useNavigate();
  const orderId = recordId(result, 'orderId');
  return (
    <Space orientation="vertical" size={12} style={{ display: 'flex' }}>
      <Alert
        type="success"
        showIcon
        title="入库单已创建"
        description={
          <div role="status">
            <p>单据：{orderId}</p>
            <p>
              状态：{String(result.status)} · 当前仓 {warehouseId}
            </p>
            <p>已发起列表刷新并清除当前页筛选。打开单据后继续收货。</p>
          </div>
        }
      />
      <Space wrap>
        <Button
          type="primary"
          onClick={() => {
            close();
            navigate(`/w/${warehouseId}/inbound/${encodeURIComponent(orderId)}`);
          }}
        >
          打开单据
        </Button>
        <Button
          onClick={() => {
            startNext();
            close();
          }}
        >
          返回列表
        </Button>
        <Button onClick={startNext}>继续创建</Button>
      </Space>
    </Space>
  );
}
