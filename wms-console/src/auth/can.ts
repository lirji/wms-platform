import { CENTRAL_BINDINGS, CENTRAL_QUERIES } from "./centralBindings";
import type { WorkspaceValue } from "../shell/WorkspaceContext";

export function hasScope(scopes: string[] | undefined, required?: string | string[]): boolean {
  if (!required) {
    return true;
  }
  const need = Array.isArray(required) ? required : [required];
  const have = scopes ?? [];
  return need.some((scope) => have.includes(scope));
}

/** 页面可由多个读能力进入，只请求当前能力覆盖的子查询；实际请求仍由后端逐次判权。 */
export function canQuery(workspace: WorkspaceValue, path: string): boolean {
  if (workspace.mode !== "CENTRAL") return true;
  const pathname = path.split("?")[0];
  const operation = CENTRAL_QUERIES.find(query => new RegExp("^" + query.path.replace(/\{[^}]+\}/g, "[^/]+") + "$").test(pathname));
  return Boolean(operation && workspace.capabilities?.includes(operation.capability));
}

export function queryPermissionError() {
  return { status: 403, code: "QUERY_NOT_GRANTED", message: "当前权限未包含此项查询" };
}

/** 中央模式按同源能力及资源区分企业/仓动作，禁止相同旧scope造成按钮串权。 */
export function canOperation(workspace: WorkspaceValue, required?: string | string[], resource: "warehouse" | "enterprise" = "warehouse"): boolean {
  if (workspace.mode !== "CENTRAL") return hasScope(workspace.scopes, required);
  if (!required) return true;
  return (Array.isArray(required) ? required : [required]).some(scope => {
    const warehouse = CENTRAL_BINDINGS[`${scope}:wms_warehouse`];
    const capability = resource === "enterprise" || !warehouse ? CENTRAL_BINDINGS[`${scope}:wms_enterprise`] : warehouse;
    return Boolean(capability && workspace.capabilities?.includes(capability));
  });
}
