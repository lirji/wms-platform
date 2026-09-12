import { useState } from "react";
import { Card, Form, Input, Select, Space } from "antd";
import { api } from "../../api/client";
import { asOfMeta, pageItems, withQuery } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandDrawer } from "../../shared/command/CommandDrawer";
import { DataTable } from "../../shared/ui/DataTable";
import { errorBanner } from "../../shared/ui/errorBanner";
import { PageHead } from "../../shared/ui/PageHead";
import { QueryMeta } from "../../shared/ui/QueryMeta";
import { useResource } from "../../shared/useResource";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function CatalogPage() {
  const { token, warehouseId, warehouseName } = useWorkspace();
  const ready = Boolean(warehouseId && warehouseId !== "_");
  const [tick, setTick] = useState(0);
  const paths = [
    "/api/wms/v1/skus",
    ready ? `/api/wms/v1/warehouses/${warehouseId}/locations` : "",
    ready ? `/api/wms/v1/warehouses/${warehouseId}/lots` : ""
  ].filter(Boolean).map((path) => withQuery(path, { limit: "50" }));
  const { payloads, error, loading } = useResource(token, paths, tick);
  const meta = payloads[0] ? asOfMeta(payloads[0]) : null;
  const skus = payloads[0] ? pageItems(payloads[0]) : [];
  const locations = payloads[1] ? pageItems(payloads[1]) : [];
  const lots = payloads[2] ? pageItems(payloads[2]) : [];
  const reload = () => setTick((current) => current + 1);

  return (
    <Space orientation="vertical" size={16} style={{ display: "flex" }}>
      <PageHead
        eyebrow={warehouseName || warehouseId || "未选仓"}
        title="商品 / 库位"
        sub="主数据来自库存服务。新建仓库不会自动进入当前令牌，须更新身份后才能选仓。"
        extra={(
          <Space wrap>
            <QueryMeta
              warehouseId={warehouseId}
              warehouseName={warehouseName}
              asOf={meta?.asOf}
              lagSeconds={meta?.lagSeconds}
              stale={meta?.stale}
              rowCount={loading ? "读取中" : String(skus.length + locations.length + lots.length)}
            />
            <CommandDrawer
              triggerLabel="创建商品"
              title="创建商品"
              hint="编码即标识。基础单位会写成 1:1 换算。"
              requireScope="masterdata.write"
              disabled={!token}
              onSubmitted={reload}
            >
              <CommandCard
                embedded
                requireScope="masterdata.write"
                title="创建商品"
                hint="数量精度 0–6。序列号商品必须是整数精度。"
                operation={`sku-create:${warehouseId}`}
                submitLabel="创建商品"
                disabled={!token}
                onRun={(key, values) => api("/api/wms/v1/skus", token, {
                  method: "POST",
                  idempotencyKey: key,
                  body: {
                    code: values.code,
                    name: values.name,
                    baseUnit: values.baseUnit || "EA",
                    quantityScale: Number(values.quantityScale || "0"),
                    lotEnabled: values.lotEnabled === "true",
                    serialEnabled: values.serialEnabled === "true",
                    expiryEnabled: values.expiryEnabled === "true",
                    clientOperationId: key
                  }
                })}
              >
                <Form.Item label="编码" name="code" rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item label="名称" name="name" rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item label="基础单位" name="baseUnit" initialValue="EA"><Input /></Form.Item>
                <Form.Item label="数量精度" name="quantityScale" initialValue="0"><Input inputMode="numeric" /></Form.Item>
                <Form.Item label="批次" name="lotEnabled" initialValue="false">
                  <Select options={[{ value: "false", label: "否" }, { value: "true", label: "是" }]} />
                </Form.Item>
                <Form.Item label="序列号" name="serialEnabled" initialValue="false">
                  <Select options={[{ value: "false", label: "否" }, { value: "true", label: "是" }]} />
                </Form.Item>
                <Form.Item label="效期" name="expiryEnabled" initialValue="false">
                  <Select options={[{ value: "false", label: "否" }, { value: "true", label: "是" }]} />
                </Form.Item>
              </CommandCard>
            </CommandDrawer>
            <CommandDrawer
              triggerLabel="创建库位"
              title="创建库位"
              hint="会同时写入 OPEN 门禁。容量与单位必须成对。"
              triggerType="default"
              requireScope="masterdata.write"
              disabled={!token || !ready}
              onSubmitted={reload}
            >
              <CommandCard
                embedded
                requireScope="masterdata.write"
                title="创建库位"
                hint="编码即库位标识。"
                operation={`location-create:${warehouseId}`}
                submitLabel="创建库位"
                disabled={!token || !ready}
                onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/locations`, token, {
                  method: "POST",
                  idempotencyKey: key,
                  body: {
                    code: values.code,
                    zoneCode: values.zoneCode,
                    locationType: values.locationType,
                    capacityQty: values.capacityQty || undefined,
                    capacityUnit: values.capacityUnit || undefined,
                    clientOperationId: key
                  }
                })}
              >
                <Form.Item label="编码" name="code" rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item label="库区" name="zoneCode" rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item label="类型" name="locationType" rules={[{ required: true }]}><Input placeholder="STORAGE" /></Form.Item>
                <Form.Item label="容量" name="capacityQty"><Input inputMode="decimal" /></Form.Item>
                <Form.Item label="容量单位" name="capacityUnit"><Input /></Form.Item>
              </CommandCard>
            </CommandDrawer>
            <CommandDrawer
              triggerLabel="登记批次"
              title="登记批次"
              hint="无批次商品不要调用。不把空日期写成 00:00 UTC。"
              triggerType="default"
              requireScope="masterdata.write"
              disabled={!token || !ready}
              onSubmitted={reload}
            >
              <CommandCard
                embedded
                requireScope="masterdata.write"
                title="登记批次"
                hint="启用效期时才填生产/失效时刻。"
                operation={`lot-create:${warehouseId}`}
                submitLabel="登记批次"
                disabled={!token || !ready}
                onRun={(key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/lots`, token, {
                  method: "POST",
                  idempotencyKey: key,
                  body: {
                    ownerId: values.ownerId,
                    skuId: values.skuId,
                    lotCode: values.lotCode,
                    businessLotKey: values.businessLotKey,
                    producedAt: values.producedAt || undefined,
                    expiresAt: values.expiresAt || undefined,
                    sourceDate: values.sourceDate || undefined,
                    expiryRuleVersion: Number(values.expiryRuleVersion || "0"),
                    clientOperationId: key
                  }
                })}
              >
                <Form.Item label="货主" name="ownerId" rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item label="SKU" name="skuId" rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item label="批次编码" name="lotCode" rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item label="跨仓批次键" name="businessLotKey" rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item label="生产时刻 UTC" name="producedAt"><Input placeholder="可选 ISO-8601" /></Form.Item>
                <Form.Item label="失效时刻 UTC" name="expiresAt"><Input placeholder="可选 ISO-8601" /></Form.Item>
              </CommandCard>
            </CommandDrawer>
            <CommandDrawer
              triggerLabel="追加单位"
              title="追加单位"
              hint="分子分母必须是正整数。不在页面换算发运量。"
              triggerType="default"
              requireScope="masterdata.write"
              disabled={!token}
              onSubmitted={reload}
            >
              <CommandCard
                embedded
                requireScope="masterdata.write"
                title="追加单位"
                hint="写入当前策略版本。"
                operation={`sku-unit:${warehouseId}`}
                submitLabel="追加单位"
                disabled={!token}
                onRun={(key, values) => api(`/api/wms/v1/skus/${values.skuId}/units`, token, {
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
                <Form.Item label="SKU" name="skuId" rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item label="单位" name="unitCode" rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item label="分子" name="numerator" rules={[{ required: true }]}><Input inputMode="numeric" /></Form.Item>
                <Form.Item label="分母" name="denominator" rules={[{ required: true }]}><Input inputMode="numeric" /></Form.Item>
                <Form.Item label="样例数量" name="sampleQuantity"><Input inputMode="decimal" /></Form.Item>
              </CommandCard>
            </CommandDrawer>
            <CommandDrawer
              triggerLabel="查看门禁"
              title="查看库位门禁"
              hint="只读。没有公开写门禁接口，不会在页面发明关闭。"
              triggerType="default"
              requireScope={["masterdata.read", "masterdata.write"]}
              disabled={!token || !ready}
            >
              <CommandCard
                embedded
                requireScope={["masterdata.read", "masterdata.write"]}
                title="查看门禁"
                hint="按库位查询当前 gate。"
                operation={`gate-read:${warehouseId}`}
                submitLabel="查询门禁"
                disabled={!token || !ready}
                onRun={(_key, values) => api(`/api/wms/v1/warehouses/${warehouseId}/locations/${values.locationId}/gate`, token)}
              >
                <Form.Item label="库位" name="locationId" rules={[{ required: true }]}><Input /></Form.Item>
              </CommandCard>
            </CommandDrawer>
            <CommandDrawer
              triggerLabel="创建仓库"
              title="创建仓库"
              hint="企业级写入。新仓要等身份更新后才会出现在选仓器。"
              triggerType="default"
              requireScope="masterdata.write"
              disabled={!token}
              onSubmitted={reload}
            >
              <CommandCard
                embedded
                requireScope="masterdata.write"
                title="创建仓库"
                hint="时区必须是 IANA 标识。"
                operation="warehouse-create"
                submitLabel="创建仓库"
                disabled={!token}
                onRun={(key, values) => api("/api/wms/v1/warehouses", token, {
                  method: "POST",
                  idempotencyKey: key,
                  body: {
                    code: values.code,
                    name: values.name,
                    timezone: values.timezone || "Asia/Shanghai",
                    clientOperationId: key
                  }
                })}
              >
                <Form.Item label="编码" name="code" rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item label="名称" name="name" rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item label="时区" name="timezone" initialValue="Asia/Shanghai"><Input /></Form.Item>
              </CommandCard>
            </CommandDrawer>
          </Space>
        )}
      />
      {error ? errorBanner(error) : null}
      <Card title="商品" size="small">
        <DataTable
          caption="商品"
          rows={skus}
          loading={loading}
          emptyText="当前没有商品"
          columns={[
            { key: "id", label: "SKU", keys: ["id", "skuId"], kind: "id", copyKind: "SKU" },
            { key: "name", label: "名称", keys: ["name"] },
            { key: "code", label: "编码", keys: ["code"] },
            { key: "serial", label: "序列号", keys: ["serialEnabled", "serial_enabled"] },
            { key: "lot", label: "批次", keys: ["lotEnabled", "lot_enabled"] },
            { key: "unit", label: "基础单位", keys: ["baseUnit", "base_unit"] }
          ]}
        />
      </Card>
      <Card title="库位" size="small">
        <DataTable
          caption="库位"
          rows={locations}
          loading={loading}
          emptyText={ready ? `当前仓 ${warehouseId} 没有库位` : "尚未选仓"}
          columns={[
            { key: "id", label: "库位", keys: ["id", "locationId"], kind: "id", copyKind: "库位" },
            { key: "code", label: "编码", keys: ["code"] },
            { key: "type", label: "类型", keys: ["locationType", "location_type"] },
            { key: "status", label: "状态", keys: ["state", "status"], kind: "status" }
          ]}
        />
      </Card>
      <Card title="批次" size="small">
        <DataTable
          caption="批次"
          rows={lots}
          loading={loading}
          emptyText={ready ? `当前仓 ${warehouseId} 没有批次` : "尚未选仓"}
          columns={[
            { key: "id", label: "批次", keys: ["id", "lotId"], kind: "id", copyKind: "批次" },
            { key: "code", label: "编码", keys: ["code", "lotCode", "lot_code"] },
            { key: "skuId", label: "SKU", keys: ["skuId", "sku_id"], kind: "id", copyKind: "SKU" },
            { key: "status", label: "状态", keys: ["state", "status"], kind: "status" }
          ]}
        />
      </Card>
    </Space>
  );
}
