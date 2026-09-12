import { Button } from "antd";
import { ApiError } from "../../api/client";
import { useWorkspace } from "../../shell/WorkspaceContext";
import { StatusBanner } from "./StatusBanner";

function truncate(value: string, max = 180): string {
  const text = value.trim();
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

export function errorBanner(error: unknown) {
  if (error instanceof TypeError) {
    return <StatusBanner kind="error" title="对应服务不可达" detail="请确认 inbound / outbound / inventory / fulfillment 已启动。这不是权限不足。" />;
  }
  const apiError = error as ApiError;
  const code = apiError?.code;
  const message = apiError?.message ? truncate(apiError.message) : "";
  if (apiError?.status === 401) {
    return (
      <>
        <StatusBanner kind="error" title="登录已失效" detail="请重新登录。这不是仓权限不足。" />
        <Button type="primary" href="/login" style={{ marginTop: 8 }}>去登录</Button>
      </>
    );
  }
  if (apiError?.status === 403) {
    return <ForbiddenBanner message={message} code={code} />;
  }
  if (apiError?.status === 404) {
    return <StatusBanner kind="error" title="找不到该记录" detail={[code, message].filter(Boolean).join(" · ")} />;
  }
  if (apiError?.status === 409) {
    return <StatusBanner kind="conflict" title="版本冲突" detail={[code, message, "请确认最新记录后再提交，不会自动更换幂等键。"].filter(Boolean).join(" · ")} />;
  }
  if (apiError?.status === 422) {
    return <StatusBanner kind="error" title="填写有误" detail={[code, message].filter(Boolean).join(" · ")} />;
  }
  if (apiError?.status === 429) {
    return <StatusBanner kind="error" title="请求过于频繁" detail="稍后重试。主按钮应保持禁用至可重试。" />;
  }
  if (apiError?.status === 202) {
    return <StatusBanner kind="accepted" title={message || "已受理"} operationId={code} />;
  }
  if (apiError?.status && apiError.status >= 500) {
    return <StatusBanner kind="error" title="对应服务不可达" detail={[String(apiError.status), code, message].filter(Boolean).join(" · ")} />;
  }
  return (
    <StatusBanner
      kind="error"
      title="请求失败"
      detail={[apiError?.status ? String(apiError.status) : "", code, message].filter(Boolean).join(" · ") || undefined}
    />
  );
}

function ForbiddenBanner({ message, code }: { message: string; code?: string }) {
  const { warehouses = [], scopes = [], enterpriseId } = useWorkspace();
  const detail = [
    code,
    message,
    enterpriseId ? `enterprise=${enterpriseId}` : "",
    warehouses.length ? `warehouses=${warehouses.join(",")}` : "令牌没有仓范围",
    scopes.length ? `scope=${scopes.slice(0, 8).join(" ")}${scopes.length > 8 ? "…" : ""}` : "令牌没有作业权限"
  ].filter(Boolean).join(" · ");
  return <StatusBanner kind="forbidden" title="没有权限访问该资源" detail={detail} />;
}
