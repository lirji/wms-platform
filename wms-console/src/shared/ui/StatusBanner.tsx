import { Alert } from "antd";

export type BannerKind =
  | "loading"
  | "empty"
  | "accepted"
  | "conflict"
  | "forbidden"
  | "stale"
  | "tcc"
  | "sync-pending"
  | "serial-hold"
  | "error"
  | "success";

export type StatusBannerProps = {
  kind: BannerKind;
  title: string;
  detail?: string;
  operationId?: string;
};

const LABELS: Record<BannerKind, string> = {
  loading: "处理中",
  empty: "无数据",
  accepted: "已受理，库存待同步",
  conflict: "版本冲突",
  forbidden: "权限不足",
  stale: "查询陈旧",
  tcc: "跨仓预占等待全局完成",
  "sync-pending": "货已执行，库存待同步",
  "serial-hold": "序列号隔离中",
  error: "失败",
  success: "查询就绪"
};

function alertType(kind: BannerKind): "success" | "info" | "warning" | "error" {
  if (kind === "error" || kind === "forbidden") {
    return "error";
  }
  if (kind === "accepted" || kind === "sync-pending" || kind === "tcc" || kind === "stale" || kind === "serial-hold" || kind === "conflict") {
    return "warning";
  }
  return "info";
}

export function StatusBanner({ kind, title, detail, operationId }: StatusBannerProps) {
  const extras = [
    detail,
    operationId ? `operationId: ${operationId}` : "",
    kind === "accepted" || kind === "sync-pending" ? "禁止当作业务已成功，请按原命令查询结果，不要新建设备动作。" : "",
    kind === "tcc" ? "库存已预留，等待 Seata 全局事务完成。没有强制释放按钮。" : "",
    kind === "conflict" ? "请确认最新记录后再提交；不会自动更换幂等键。" : "",
    kind === "serial-hold" ? "待登记或待转移确认，禁止拣货或发运。" : ""
  ].filter(Boolean);
  return (
    <Alert
      showIcon
      type={alertType(kind)}
      title={LABELS[kind]}
      description={(
        <div>
          <p>{title}</p>
          {extras.map((line) => <p key={line}>{line}</p>)}
        </div>
      )}
    />
  );
}
