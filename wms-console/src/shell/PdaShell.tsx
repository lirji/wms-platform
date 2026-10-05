import { useEffect } from 'react';
import { Link, Outlet, useLocation, useParams } from 'react-router-dom';
import { User } from 'oidc-client-ts';
import { Button, Flex, Layout, Space, Typography } from 'antd';
import { menuRouteFor, useWorkspaceAccess } from '../auth/workspaceAccess';
import { StatusBanner } from '../shared/ui/StatusBanner';
import { WorkspaceProvider } from './WorkspaceContext';

const PDA_JOBS = [
  { to: 'receive', label: '收货' },
  { to: 'pick', label: '拣货' },
  { to: 'ship', label: '发运' },
];

export function PdaShell({ user, token }: { user: User; token?: string }) {
  const { warehouseId = '' } = useParams();
  const location = useLocation();
  const displayName = user.profile.name || user.profile.preferred_username || user.profile.sub;
  const job = PDA_JOBS.find((item) => location.pathname.endsWith(`/${item.to}`)) ?? PDA_JOBS[0];
  const { access, error, retry } = useWorkspaceAccess(token, warehouseId);
  const claims = {
    enterpriseId: access?.enterpriseId,
    warehouses: access?.warehouseIds ?? [],
    scopes: access?.scopes ?? [],
  };
  const allowed =
    access &&
    (access.mode === 'LEGACY' ||
      (access.warehouseIds.includes(warehouseId) &&
        access.menus.some((menu) => menu.route === menuRouteFor(location.pathname))));
  const jobs = PDA_JOBS.filter(
    (item) =>
      access &&
      (access.mode === 'LEGACY' || access.menus.some((menu) => menu.route === `/pda/${item.to}`)),
  );

  useEffect(() => {
    document.getElementById('page-title')?.focus();
  }, [warehouseId]);

  return (
    <WorkspaceProvider
      value={{
        token,
        warehouseId,
        enterpriseId: claims.enterpriseId,
        warehouses: claims.warehouses,
        scopes: claims.scopes,
        mode: access?.mode,
        capabilities: access?.capabilities,
      }}
    >
      <Layout className="app-shell">
        <a className="skip-link" href="#main">
          跳到主内容
        </a>
        <Layout.Header className="app-header">
          <Flex className="app-header-row" align="center" justify="space-between" gap={8}>
            <Link to={warehouseId ? `/w/${warehouseId}` : '/'}>
              <Typography.Title level={4} style={{ margin: 0, color: '#1D2129' }}>
                PDA {job.label}
              </Typography.Title>
              <Typography.Text type="secondary">
                {warehouseId || '未选仓'} · {displayName}
              </Typography.Text>
            </Link>
            <Space className="workspace-actions" wrap>
              {jobs.map((item) => (
                <Link key={item.to} to={`/pda/${encodeURIComponent(warehouseId)}/${item.to}`}>
                  <Button type={item.to === job.to ? 'primary' : 'default'}>{item.label}</Button>
                </Link>
              ))}
              <Link to={warehouseId ? `/w/${warehouseId}` : '/'}>
                <Button>返回工作台</Button>
              </Link>
            </Space>
          </Flex>
        </Layout.Header>
        <Layout.Content className="app-content" id="main">
          {!access ? (
            <Space orientation="vertical">
              <StatusBanner
                kind={error ? 'error' : 'loading'}
                title={
                  error
                    ? (error as { status?: number }).status === 403
                      ? '当前权限已失效，请重新读取'
                      : (error as { status?: number }).status === 401
                        ? '登录已失效，请重新登录'
                        : '权限暂不可用，请重试'
                    : '正在读取本人权限'
                }
              />
              {(error as { status?: number } | undefined)?.status === 401 ? (
                <Button href="/login?reauth=1">重新登录</Button>
              ) : null}
              {error ? <Button onClick={retry}>重新读取权限</Button> : null}
            </Space>
          ) : allowed ? (
            <Outlet />
          ) : (
            <StatusBanner kind="forbidden" title="当前仓或作业未获授权" />
          )}
        </Layout.Content>
      </Layout>
    </WorkspaceProvider>
  );
}
