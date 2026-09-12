import { ApiError } from "../../api/client";
import { useWorkspace } from "../../shell/WorkspaceContext";
import { StatusBanner } from "./StatusBanner";

export function errorBanner(error: unknown) {
  if (error instanceof TypeError) {
    return <StatusBanner kind="error" title="无法连接对应服务" detail="请确认 inbound / outbound / inventory / fulfillment 已启动" />;
  }
  const apiError = error as ApiError;
  if (apiError?.status === 401) {
    return <StatusBanner kind="error" title="登录已过期或尚未登录" detail="请重新使用统一身份登录。这不是仓权限不足。" />;
  }
  if (apiError?.status === 403) {
    return <ForbiddenBanner message={apiError.message} code={apiError.code} />;
  }
  if (apiError?.status === 409) {
    return <StatusBanner kind="conflict" title={apiError.message} detail={JSON.stringify(apiError.body)} />;
  }
  if (apiError?.status === 202) {
    return <StatusBanner kind="accepted" title={apiError.message} />;
  }
  if (apiError?.status && apiError.status >= 500) {
    return <StatusBanner kind="error" title="对应服务暂时不可用" detail={`${apiError.status} ${apiError.message}`} />;
  }
  if (apiError?.status === 404) {
    return <StatusBanner kind="error" title="对应接口不存在或资源未找到" detail={`${apiError.status} ${apiError.message}`} />;
  }
  return <StatusBanner kind="error" title={apiError?.message ?? "请求失败"} detail={apiError?.status ? String(apiError.status) : undefined} />;
}

function ForbiddenBanner({ message, code }: { message: string; code?: string }) {
  const { warehouses = [], scopes = [], enterpriseId } = useWorkspace();
  const detail = [
    code,
    enterpriseId ? `enterprise=${enterpriseId}` : "",
    warehouses.length ? `warehouses=${warehouses.join(",")}` : "令牌没有仓范围",
    scopes.length ? `scope=${scopes.join(" ")}` : "令牌没有作业权限"
  ].filter(Boolean).join(" · ");
  return <StatusBanner kind="forbidden" title={message || "当前令牌无权访问该资源"} detail={detail} />;
}
