import { useEffect, useState, type ReactNode } from "react";
import { Link, useParams } from "react-router-dom";
import { Button, Card, Descriptions, Form, Input, Space } from "antd";
import { api, type ApiError } from "../../api/client";
import { asRecord, field, nestedRecords, type ItemRecord } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandDrawer } from "../../shared/command/CommandDrawer";
import { CopyId } from "../../shared/ui/CopyId";
import { DataTable } from "../../shared/ui/DataTable";
import { errorBanner } from "../../shared/ui/errorBanner";
import { PageHead } from "../../shared/ui/PageHead";
import { StatusBanner } from "../../shared/ui/StatusBanner";
import { StatusChip } from "../../shared/ui/StatusChip";
import { useDocument } from "../../shared/useDocument";
import { useWorkspace } from "../../shell/WorkspaceContext";

function flagText(value: string): string {
  if (!value) {
    return "—";
  }
  if (/^(1|true|yes)$/i.test(value)) {
    return "是";
  }
  if (/^(0|false|no)$/i.test(value)) {
    return "否";
  }
  return value;
}

function textItem(record: ItemRecord, label: string, ...keys: string[]) {
  const value = field(record, ...keys);
  return { key: keys[0], label, children: value || "—" };
}

function idItem(record: ItemRecord, label: string, kind: string, ...keys: string[]) {
  const value = field(record, ...keys);
  return { key: keys[0], label, children: value ? <CopyId value={value} kind={kind} /> : "—" };
}

function CatalogRecord({
  backTo,
  title,
  status,
  sub,
  loading,
  error,
  items,
  extra,
  commands
}: {
  backTo: string;
  title: string;
  status?: string;
  sub: string;
  loading?: boolean;
  error?: unknown;
  items: { key: string; label: string; children: ReactNode }[];
  extra?: ReactNode;
  commands?: ReactNode;
}) {
  return (
    <Space orientation="vertical" size={16} style={{ display: "flex" }}>
      <PageHead
        breadcrumb={[{ label: "商品 / 库位", to: backTo }, { label: title }]}
        title={title}
        status={status}
        sub={sub}
        extra={(
          <Space>
            {commands}
            <Link to={backTo}><Button>返回主数据</Button></Link>
          </Space>
        )}
      />
      {loading ? <StatusBanner kind="loading" title="正在读取主数据" /> : null}
      {error ? errorBanner(error) : null}
      <Card title="资料">
        <Descriptions size="small" column={2} items={items} />
      </Card>
      {extra}
    </Space>
  );
}

export function SkuDetailPage() {
  const { warehouseId = "", skuId = "" } = useParams();
  const { token } = useWorkspace();
  const [tick, setTick] = useState(0);
  const decoded = decodeURIComponent(skuId);
  const { record, error, loading } = useDocument(token, decoded ? `/api/wms/v1/skus/${encodeURIComponent(decoded)}` : undefined, tick);
  const units = nestedRecords(record, "units");
  const state = field(record, "state", "status");

  return (
    <CatalogRecord
      backTo={`/w/${warehouseId}/catalog`}
      title={`商品 ${decoded}`}
      status={field(record, "state", "status")}
      sub="详情含策略开关与当前策略版本单位。超过 200 个单位走列表接口，不在本页编造。"
      loading={loading}
      error={error}
      commands={(
        <CommandDrawer
          triggerLabel="追加单位"
          title="追加单位"
          hint="分子分母必须是正整数。不在页面换算发运量。"
          requireScope="masterdata.write"
          disabled={!token}
          onSubmitted={() => setTick((current) => current + 1)}
        >
          <CommandCard
            embedded
            requireScope="masterdata.write"
            title="追加单位"
            hint="写入当前策略版本。"
            operation={`sku-unit:${decoded}`}
            submitLabel="追加单位"
            disabled={!token}
            onRun={(key, values) => api(`/api/wms/v1/skus/${encodeURIComponent(decoded)}/units`, token, {
              method: "POST",
              idempotencyKey: key,
              body: {
                unitCode: values.unitCode,
                numerator: values.numerator,
                denominator: values.denominator,
                sampleQuantity: values.sampleQuantity || undefined,
                clientOperationId: key
              }
            })}
          >
            <Form.Item label="单位" name="unitCode" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item label="分子" name="numerator" rules={[{ required: true }]}><Input inputMode="numeric" /></Form.Item>
            <Form.Item label="分母" name="denominator" rules={[{ required: true }]}><Input inputMode="numeric" /></Form.Item>
            <Form.Item label="样例数量" name="sampleQuantity"><Input inputMode="decimal" /></Form.Item>
          </CommandCard>
        </CommandDrawer>
      )}
      items={[
        idItem(record, "SKU", "SKU", "id", "skuId", "sku_id"),
        { key: "state", label: "状态", children: state ? <StatusChip value={state} /> : "—" },
        textItem(record, "编码", "code"),
        textItem(record, "名称", "name"),
        textItem(record, "基础单位", "baseUnit", "base_unit"),
        textItem(record, "数量精度", "quantityScale", "quantity_scale"),
        { key: "lot", label: "批次", children: flagText(field(record, "lotEnabled", "lot_enabled")) },
        { key: "serial", label: "序列号", children: flagText(field(record, "serialEnabled", "serial_enabled")) },
        { key: "expiry", label: "效期", children: flagText(field(record, "expiryEnabled", "expiry_enabled")) },
        textItem(record, "策略版本", "policyVersion", "policy_version"),
        textItem(record, "版本", "version")
      ]}
      extra={(
        <Card title="单位换算">
          <DataTable
            caption="单位换算"
            rows={units}
            emptyText="当前策略版本没有额外单位"
            columns={[
              { key: "id", label: "单位行", keys: ["id"], kind: "id", copyKind: "单位" },
              { key: "unit", label: "单位", keys: ["unitCode", "unit_code"] },
              { key: "num", label: "分子", qty: true, keys: ["numerator"] },
              { key: "den", label: "分母", qty: true, keys: ["denominator"] },
              { key: "policy", label: "策略版本", keys: ["policyVersion", "policy_version"] }
            ]}
          />
        </Card>
      )}
    />
  );
}

export function LocationDetailPage() {
  const { warehouseId = "", locationId = "" } = useParams();
  const { token } = useWorkspace();
  const decoded = decodeURIComponent(locationId);
  const path = warehouseId && decoded
    ? `/api/wms/v1/warehouses/${warehouseId}/locations/${encodeURIComponent(decoded)}`
    : undefined;
  const { record, error, loading } = useDocument(token, path);
  const [gate, setGate] = useState<ItemRecord>({});
  const [gateError, setGateError] = useState<unknown>();
  const [gateMissing, setGateMissing] = useState(false);
  const state = field(record, "state", "status");

  useEffect(() => {
    if (!token || !warehouseId || !decoded) {
      setGate({});
      setGateMissing(false);
      setGateError(undefined);
      return;
    }
    let cancelled = false;
    api(`/api/wms/v1/warehouses/${warehouseId}/locations/${encodeURIComponent(decoded)}/gate`, token)
      .then((body) => {
        if (!cancelled) {
          setGate(asRecord(body));
          setGateMissing(false);
          setGateError(undefined);
        }
      })
      .catch((caught) => {
        if (cancelled) {
          return;
        }
        const status = (caught as ApiError)?.status;
        if (status === 404) {
          setGate({});
          setGateMissing(true);
          setGateError(undefined);
          return;
        }
        setGateError(caught);
      });
    return () => {
      cancelled = true;
    };
  }, [token, warehouseId, decoded]);

  const gateState = field(gate, "state", "status");

  return (
    <CatalogRecord
      backTo={`/w/${warehouseId}/catalog`}
      title={`库位 ${decoded}`}
      status={field(record, "state", "status")}
      sub="容量与门禁只读展示。没有公开写门禁接口，不会在页面发明关闭。"
      loading={loading}
      error={error}
      items={[
        idItem(record, "库位", "库位", "id", "locationId", "location_id"),
        { key: "state", label: "状态", children: state ? <StatusChip value={state} /> : "—" },
        textItem(record, "编码", "code"),
        textItem(record, "库区", "zoneCode", "zone_code"),
        textItem(record, "类型", "locationType", "location_type"),
        textItem(record, "容量", "capacityQty", "capacity_qty"),
        textItem(record, "容量单位", "capacityUnit", "capacity_unit"),
        textItem(record, "版本", "version")
      ]}
      extra={(
        <Card title="门禁">
          {gateError ? errorBanner(gateError) : null}
          {gateMissing ? <p className="command-dialog-hint">当前库位没有门禁记录。</p> : (
            <Descriptions
              size="small"
              column={2}
              items={[
                { key: "gateState", label: "状态", children: gateState ? <StatusChip value={gateState} /> : "—" },
                textItem(gate, "原因", "reasonCode", "reason_code"),
                textItem(gate, "围栏世代", "fenceEpoch", "fence_epoch"),
                textItem(gate, "盘点计划", "countPlanId", "count_plan_id"),
                textItem(gate, "版本", "version")
              ]}
            />
          )}
        </Card>
      )}
    />
  );
}

export function LotDetailPage() {
  const { warehouseId = "", lotId = "" } = useParams();
  const { token } = useWorkspace();
  const decoded = decodeURIComponent(lotId);
  const path = warehouseId && decoded
    ? `/api/wms/v1/warehouses/${warehouseId}/lots/${encodeURIComponent(decoded)}`
    : undefined;
  const { record, error, loading } = useDocument(token, path);
  const skuId = field(record, "skuId", "sku_id");

  return (
    <CatalogRecord
      backTo={`/w/${warehouseId}/catalog`}
      title={`批次 ${decoded}`}
      sub="仓级批次。无批次商品使用 NO_LOT，不会落本表。"
      loading={loading}
      error={error}
      items={[
        idItem(record, "批次", "批次", "id", "lotId", "lot_id"),
        {
          key: "sku",
          label: "SKU",
          children: skuId ? (
            <Link to={`/w/${warehouseId}/catalog/skus/${encodeURIComponent(skuId)}`}>{skuId}</Link>
          ) : "—"
        },
        textItem(record, "货主", "ownerId", "owner_id"),
        textItem(record, "批次编码", "lotCode", "lot_code"),
        textItem(record, "跨仓批次键", "businessLotKey", "business_lot_key"),
        textItem(record, "生产时刻", "producedAt", "produced_at"),
        textItem(record, "失效时刻", "expiresAt", "expires_at"),
        textItem(record, "来源日", "sourceDate", "source_date"),
        textItem(record, "效期规则版本", "expiryRuleVersion", "expiry_rule_version"),
        textItem(record, "版本", "version")
      ]}
    />
  );
}
