import { ApiError } from "../../api/client";
import { StatusBanner } from "./StatusBanner";

export function errorBanner(error: unknown) {
  if (error instanceof TypeError) {
    return <StatusBanner kind="error" title="无法连接对应服务" detail="请确认 inbound / outbound / inventory / fulfillment 已启动" />;
  }
  const apiError = error as ApiError;
  if (apiError?.status === 403 || apiError?.status === 401) {
    return <StatusBanner kind="forbidden" title={apiError.message} detail={apiError.code} />;
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
  return <StatusBanner kind="error" title={apiError?.message ?? "请求失败"} />;
}
