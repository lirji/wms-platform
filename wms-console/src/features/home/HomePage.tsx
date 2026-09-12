import { Link } from "react-router-dom";
import { Card, Col, List, Row, Space, Statistic, Typography } from "antd";
import { asOfMeta, recordId } from "../../api/envelope";
import { DataTable } from "../../shared/ui/DataTable";
import { EmptyState } from "../../shared/ui/EmptyState";
import { errorBanner } from "../../shared/ui/errorBanner";
import { PageHead } from "../../shared/ui/PageHead";
import { QueryMeta } from "../../shared/ui/QueryMeta";
import { StatusBanner } from "../../shared/ui/StatusBanner";
import { countText, useSettledResource } from "../../shared/useSettledResource";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function HomePage() {
  const { token, warehouseId, warehouseName } = useWorkspace();
  const ready = Boolean(warehouseId && warehouseId !== "_");
  const { buckets, loading } = useSettledResource(token, ready ? [
    "/api/wms/v1/warehouses",
    "/api/wms/v1/skus",
    `/api/wms/v1/warehouses/${warehouseId}/locations`,
    `/api/wms/v1/inventory?warehouseIds=${encodeURIComponent(warehouseId)}`,
    `/api/wms/v1/warehouses/${warehouseId}/inbound-orders`,
    `/api/wms/v1/warehouses/${warehouseId}/outbound-orders`,
    `/api/wms/v1/jobs?warehouseId=${encodeURIComponent(warehouseId)}`
  ] : []);
  const by = Object.fromEntries(buckets.map((bucket) => [bucket.path, bucket]));
  const stock = by[`/api/wms/v1/inventory?warehouseIds=${encodeURIComponent(warehouseId)}`];
  const inbound = by[`/api/wms/v1/warehouses/${warehouseId}/inbound-orders`];
  const outbound = by[`/api/wms/v1/warehouses/${warehouseId}/outbound-orders`];
  const jobs = by[`/api/wms/v1/jobs?warehouseId=${encodeURIComponent(warehouseId)}`];
  const counts = {
    warehouses: countText(by["/api/wms/v1/warehouses"]),
    skus: countText(by["/api/wms/v1/skus"]),
    locations: countText(by[`/api/wms/v1/warehouses/${warehouseId}/locations`]),
    stock: countText(stock),
    inbound: countText(inbound),
    outbound: countText(outbound),
    jobs: countText(jobs)
  };
  const meta = stock?.payload ? asOfMeta(stock.payload) : null;
  const firstError = buckets.find((bucket) => bucket.error)?.error;
  const notes = [
    inbound && !inbound.error && inbound.rows.length === 0 ? "本仓暂无入库单，收货入口可从 PDA 打开。" : "",
    stock && !stock.error && stock.rows.length === 0 ? "库存投影当前没有行，不把健康检查当成有货。" : "",
    jobs && !jobs.error && jobs.rows.length === 0 ? "没有正在跟踪的任务运行。" : "",
    meta?.stale ? `库存查询陈旧，lagSeconds=${meta.lagSeconds}，写入仍由服务端重校验。` : ""
  ].filter(Boolean);

  return (
    <Space orientation="vertical" size={16} style={{ display: "flex" }}>
      <PageHead
        eyebrow="作业总览"
        title="仓库工作台"
        sub="先确认当前仓与实时队列。数字是接口返回的行数，不是页面写死的库存。"
        extra={(
          <QueryMeta
            warehouseId={warehouseId}
            warehouseName={warehouseName}
            asOf={meta?.asOf}
            lagSeconds={meta?.lagSeconds}
            stale={meta?.stale}
          />
        )}
      />
      {!ready ? <StatusBanner kind="empty" title="还没有可作业的仓库" detail="顶栏会列出当前令牌允许的仓；服务不可达时不会伪装成没有权限。" /> : null}
      {firstError ? errorBanner(firstError) : null}
      <Row gutter={[12, 12]}>
        {[
          { label: "可访问仓", value: loading ? "…" : counts.warehouses, hint: "当前令牌可见" },
          { label: "SKU", value: loading ? "…" : counts.skus, hint: "企业主数据" },
          { label: "本仓库位", value: loading ? "…" : counts.locations, hint: "当前作业仓" },
          { label: "库存行", value: loading ? "…" : counts.stock, hint: "投影行，非合计数量" },
          { label: "入库单", value: loading ? "…" : counts.inbound, hint: "本仓收货单据" },
          { label: "出库单", value: loading ? "…" : counts.outbound, hint: "本仓出库单据" }
        ].map((item) => (
          <Col xs={12} md={8} xl={4} key={item.label}>
            <Card size="small">
              <Statistic title={item.label} value={item.value} />
              <Typography.Text type="secondary">{item.hint}</Typography.Text>
            </Card>
          </Col>
        ))}
      </Row>
      <Row gutter={[12, 12]}>
        <Col xs={24} xl={8}>
          <Card
            title="本仓入库"
            extra={ready ? <Link to={`/w/${warehouseId}/inbound`}>打开列表</Link> : null}
          >
            <DataTable
              rows={(inbound?.rows ?? []).slice(0, 8)}
              loading={loading && ready}
              emptyText={ready ? `当前仓 ${warehouseId} 没有入库单` : "选仓后显示活队列"}
              hrefFor={(row) => ready ? `/w/${warehouseId}/inbound/${recordId(row, "id", "orderId")}` : undefined}
              columns={[
                { key: "id", label: "单据", keys: ["id", "orderId"] },
                { key: "status", label: "状态", keys: ["status", "state"] }
              ]}
            />
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card
            title="本仓出库"
            extra={ready ? <Link to={`/w/${warehouseId}/fulfillment`}>打开列表</Link> : null}
          >
            <DataTable
              rows={(outbound?.rows ?? []).slice(0, 8)}
              loading={loading && ready}
              emptyText={ready ? `当前仓 ${warehouseId} 没有出库单` : "选仓后显示活队列"}
              hrefFor={(row) => ready ? `/w/${warehouseId}/outbound/${recordId(row)}` : undefined}
              columns={[
                { key: "id", label: "单据", keys: ["id", "orderId"] },
                { key: "status", label: "状态", keys: ["status", "state"] }
              ]}
            />
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card
            title="本仓任务"
            extra={ready ? <Link to={`/w/${warehouseId}/jobs`}>打开列表</Link> : null}
          >
            <DataTable
              rows={(jobs?.rows ?? []).slice(0, 8)}
              loading={loading && ready}
              emptyText={ready ? `当前仓 ${warehouseId} 没有任务` : "选仓后显示活队列"}
              hrefFor={(row) => ready ? `/w/${warehouseId}/jobs/${recordId(row)}` : undefined}
              columns={[
                { key: "id", label: "任务", keys: ["id", "jobId", "run_key"] },
                { key: "status", label: "状态", keys: ["state", "status"] }
              ]}
            />
          </Card>
        </Col>
      </Row>
      <Card title="本仓注意" extra={<Typography.Text type="secondary">只陈述接口事实</Typography.Text>}>
        {loading ? <StatusBanner kind="loading" title="正在汇总本仓只读指标" /> : null}
        {!loading && notes.length === 0 ? (
          <EmptyState title="没有需要单独提示的查询异常" detail="单据行数见上方队列，不在这里写死待办清单。" />
        ) : null}
        {!loading && notes.length > 0 ? (
          <List dataSource={notes} renderItem={(note) => <List.Item>{note}</List.Item>} />
        ) : null}
      </Card>
    </Space>
  );
}
