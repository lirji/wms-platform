import { Link } from "react-router-dom";
import { asOfMeta } from "../../api/envelope";
import { errorBanner } from "../../shared/ui/errorBanner";
import { PageHead } from "../../shared/ui/PageHead";
import { StatusBanner } from "../../shared/ui/StatusBanner";
import { useResource } from "../../shared/useResource";
import { useWorkspace } from "../../shell/WorkspaceContext";

const MODULES = [
  { to: "catalog", title: "商品 / 库位", hint: "SKU 策略与库位状态，只读主数据" },
  { to: "inbound", title: "入库工作台", hint: "收货、质检、上架单据" },
  { to: "stock", title: "库存台账", hint: "余额与 asOf，数量按字符串展示" },
  { to: "fulfillment", title: "出库履约", hint: "跨仓进度以各仓状态为准" },
  { to: "transfers", title: "调拨", hint: "源仓发出与目的接收" },
  { to: "counts", title: "盘点", hint: "冻结、点数与调整" },
  { to: "jobs", title: "任务 / 设备", hint: "作业任务与异常回执" },
  { to: "recon", title: "对账差异", hint: "按仓查询，不写死差异" }
];

export function HomePage() {
  const { token, warehouseId } = useWorkspace();
  const { payloads, error } = useResource(
    token,
    warehouseId ? [`/api/wms/v1/inventory?warehouseIds=${encodeURIComponent(warehouseId)}`] : []
  );
  const meta = payloads[0] ? asOfMeta(payloads[0]) : null;
  return (
    <section>
      <PageHead title="仓库工作台" sub="先确认当前仓，再进入作业。列表与数量都来自接口，不在页面写死。" />
      {error ? errorBanner(error) : null}
      {!warehouseId || warehouseId === "_" ? (
        <StatusBanner kind="empty" title="还没有可作业的仓库" detail="顶栏会列出当前令牌允许的仓；服务不可达时不会伪装成没有权限。" />
      ) : null}
      {meta?.asOf ? (
        <StatusBanner
          kind={meta.stale ? "stale" : "success"}
          title={`当前仓 ${warehouseId} · asOf ${meta.asOf}`}
          detail={meta.lagSeconds ? `lagSeconds=${meta.lagSeconds}，陈旧时请刷新后等待` : "延迟未知，写入仍由服务端重校验"}
        />
      ) : null}
      <div className="module-grid">
        {MODULES.map((item) => (
          <Link key={item.to} className="module-card" to={warehouseId && warehouseId !== "_" ? `/w/${warehouseId}/${item.to}` : "."}>
            <strong>{item.title}</strong>
            <span>{item.hint}</span>
          </Link>
        ))}
      </div>
    </section>
  );
}
