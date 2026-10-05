import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { canQuery } from './can';

export type AccessMenu = {
  code: string;
  parent: string | null;
  route: string | null;
  label: string | null;
  position: number | null;
};
export type WorkspaceAccess = {
  mode: 'CENTRAL' | 'LEGACY';
  enterpriseId: string;
  warehouseIds: string[];
  scopes: string[];
  capabilities: string[];
  menus: AccessMenu[];
  observedAt: string;
};

/** 后端模式是唯一依据；缺失、异常和未知模式都不能回读JWT权限。 */
export function readWorkspaceAccess(value: unknown): WorkspaceAccess {
  const row = value as Partial<WorkspaceAccess> | null;
  const strings = (items: unknown): items is string[] =>
    Array.isArray(items) && items.length <= 200 && items.every((item) => typeof item === 'string');
  if (
    !row ||
    !['CENTRAL', 'LEGACY'].includes(row.mode ?? '') ||
    typeof row.enterpriseId !== 'string' ||
    !strings(row.warehouseIds) ||
    !strings(row.scopes) ||
    typeof row.observedAt !== 'string'
  )
    throw new Error('权限响应无效');
  if (
    row.mode === 'CENTRAL' &&
    (!strings(row.capabilities) ||
      !Array.isArray(row.menus) ||
      row.menus.length > 100 ||
      row.menus.some(
        (menu) =>
          !menu ||
          typeof menu.code !== 'string' ||
          (menu.route !== null && typeof menu.route !== 'string'),
      ))
  )
    throw new Error('中央权限响应无效');
  return {
    ...row,
    capabilities: row.capabilities ?? [],
    menus: row.menus ?? [],
  } as WorkspaceAccess;
}

/** 当前用户/仓切换及失败立即丢弃旧提示；页面聚焦重新读取，后端动作仍逐次判权。 */
export function useWorkspaceAccess(token: string | undefined, warehouse: string) {
  const [result, setResult] = useState<{ key: string; access?: WorkspaceAccess; error?: unknown }>({
    key: '',
  });
  const [revision, setRevision] = useState(0);
  const key = `${token ?? ''}:${warehouse}:${revision}`;
  useEffect(() => {
    let active = true;
    setResult({ key });
    if (token)
      api(
        `/api/wms/v1/me/access${warehouse && warehouse !== '_' ? `?warehouseId=${encodeURIComponent(warehouse)}` : ''}`,
        token,
      )
        .then(readWorkspaceAccess)
        .then((access) => {
          if (active) setResult({ key, access });
        })
        .catch((error) => {
          if (active) setResult({ key, error });
        });
    const refresh = () => setRevision((value) => value + 1);
    const invalidated = (event: Event) => {
      if (active)
        setResult({
          key,
          error: (event as CustomEvent).detail ?? { status: 503, message: '权限需要重新读取' },
        });
    };
    window.addEventListener('focus', refresh);
    window.addEventListener('wms:access-invalidated', invalidated);
    return () => {
      active = false;
      window.removeEventListener('focus', refresh);
      window.removeEventListener('wms:access-invalidated', invalidated);
    };
  }, [key, token, warehouse]);
  const current = result.key === key ? result : { key };
  return {
    access: current.access,
    error: current.error,
    retry: () => setRevision((value) => value + 1),
  };
}

/** 详情沿所属列表菜单鉴权，未知相对入口保持拒绝。 */
export function menuRouteFor(path: string): string {
  const leaf = path.replace(/^\/(w|pda)\/[^/]+/, '');
  if (path.startsWith('/pda/')) return '/pda' + leaf;
  if (/^\/(tasks|effects)(\/|$)/.test(leaf)) return '/jobs';
  if (/^\/outbound(\/|$)/.test(leaf)) return '/fulfillment';
  return '/' + (leaf.split('/')[1] ?? '');
}

/** 企业共享页面无需虚构仓权；未授仓深链仍拒绝，缺仓只允许真实企业查询对应的入口。 */
export function workspaceRouteAllowed(
  access: WorkspaceAccess,
  warehouse: string,
  path: string,
): boolean {
  if (access.mode === 'LEGACY') return true;
  const route = menuRouteFor(path);
  if (!access.menus.some((menu) => menu.route === route)) return false;
  if (access.warehouseIds.includes(warehouse)) return true;
  if (warehouse !== '_' || access.warehouseIds.length !== 0) return false;
  const context = { ...access, warehouseId: warehouse };
  const leaf = path.replace(/^\/w\/[^/]+/, '');
  const enterpriseViews = [
    { path: /^\/catalog(?:\/skus\/[^/]+)?\/?$/, query: '/api/wms/v1/skus' },
    { path: /^\/fulfillment(?:\/[^/]+)?\/?$/, query: '/api/wms/v1/fulfillments' },
    { path: /^\/recon\/?$/, query: '/api/wms/v1/reconciliation-cases' },
  ];
  return enterpriseViews.some(
    (view) =>
      (leaf === '' || leaf === '/' || view.path.test(leaf)) && canQuery(context, view.query),
  );
}
