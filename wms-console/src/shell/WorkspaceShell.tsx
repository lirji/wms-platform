import { useEffect, useMemo, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate, useParams } from 'react-router-dom';
import { User } from 'oidc-client-ts';
import {
  Avatar,
  Button,
  Flex,
  Layout,
  Menu,
  Popover,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  LogoutOutlined,
  MobileOutlined,
  SafetyCertificateOutlined,
  ShopOutlined,
} from '@ant-design/icons';
import { api } from '../api/client';
import { field, pageItems } from '../api/envelope';
import { createUserManager } from '../auth/oidc';
import { useWorkspaceAccess, workspaceRouteAllowed } from '../auth/workspaceAccess';
import { StatusBanner } from '../shared/ui/StatusBanner';
import { NAV_GROUPS } from './nav';
import { rememberWarehouse } from './warehouseSession';
import { wmsTokens } from '../design/tokens';
import { WorkspaceProvider } from './WorkspaceContext';

type Warehouse = { id: string; name: string };

function hrefFor(warehouseId: string, to: string, pda?: boolean) {
  if (!warehouseId) {
    return '/';
  }
  if (pda) {
    return `/pda/${encodeURIComponent(warehouseId)}/${to || 'receive'}`;
  }
  return `/w/${encodeURIComponent(warehouseId)}${to ? `/${to}` : ''}`;
}

function useClock() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  return now.toISOString().replace('T', ' ').slice(0, 19);
}

export function WorkspaceShell({ user, token }: { user: User; token?: string }) {
  const { warehouseId = '' } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [loadState, setLoadState] = useState<'loading' | 'ready' | 'error'>('loading');
  const displayName = user.profile.name || user.profile.preferred_username || user.profile.sub;
  const { access, error, retry } = useWorkspaceAccess(token, warehouseId);
  const claims = {
    enterpriseId: access?.enterpriseId,
    warehouses: access?.warehouseIds ?? [],
    scopes: access?.scopes ?? [],
  };
  const routeAllowed = access && workspaceRouteAllowed(access, warehouseId, location.pathname);
  const navGroups = NAV_GROUPS.map((group) => ({
    ...group,
    items: group.items.flatMap((item) => {
      if (!access) return [];
      if (access.mode === 'LEGACY') return [item];
      const menu = access.menus.find(
        (menu) => menu.route === (item.pda ? `/pda/${item.to}` : `/${item.to}`),
      );
      return menu
        ? [
            {
              ...item,
              label: menu.label ?? item.label,
              centralParent: menu.parent,
              position: menu.position ?? 100,
            },
          ]
        : [];
    }),
  }))
    .filter((group) => group.items.length)
    .map((group) => {
      if (access?.mode !== 'CENTRAL') return group;
      const first = access.menus.find(
        (menu) =>
          menu.route ===
          (group.items[0].pda ? `/pda/${group.items[0].to}` : `/${group.items[0].to}`),
      );
      const parent = access.menus.find((menu) => menu.code === first?.parent);
      const position = (item: (typeof group.items)[number]) =>
        access.menus.find((menu) => menu.route === (item.pda ? `/pda/${item.to}` : `/${item.to}`))
          ?.position ?? 100;
      return {
        ...group,
        title: parent?.label ?? group.title,
        items: group.items.sort((left, right) => position(left) - position(right)),
      };
    });
  const clock = useClock();
  const current = warehouses.find((row) => row.id === warehouseId);

  useEffect(() => {
    let active = true;
    setWarehouses([]);
    setLoadState('loading');
    if (!access || !token) return;
    const fallback = access.warehouseIds.map((id) => ({ id, name: id }));
    const complete = (mapped: Warehouse[]) => {
      if (!active) return;
      setWarehouses(mapped);
      setLoadState('ready');
      const valid = mapped.some((row) => row.id === warehouseId);
      const next = valid
        ? warehouseId
        : warehouseId === '_' || access.mode === 'LEGACY'
          ? mapped[0]?.id
          : undefined;
      if (next && next !== warehouseId) {
        rememberWarehouse(next);
        const menu = access.menus.find((menu) => menu.route);
        const route = menu?.route ?? '/';
        navigate(hrefFor(next, route.replace(/^\/(pda\/)?/, ''), route.startsWith('/pda/')), {
          replace: true,
        });
      }
    };
    // 作业成员可能没有主数据读取能力；用本人接口的仓ID，名称读取拒绝不扩大范围。
    if (access.mode === 'CENTRAL' && !access.capabilities.includes('wms.masterdata.read')) {
      complete(fallback);
      return () => {
        active = false;
      };
    }
    api('/api/wms/v1/warehouses', token)
      .then((body) => {
        const mapped = pageItems(body)
          .map((row) => ({
            id: field(row, 'id', 'warehouseId'),
            name: field(row, 'name', 'code', 'id'),
          }))
          .filter((row) => access.warehouseIds.includes(row.id));
        complete(fallback.map((row) => mapped.find((value) => value.id === row.id) ?? row));
      })
      .catch(() => complete(fallback));
    return () => {
      active = false;
    };
  }, [access, navigate, token, warehouseId]);

  function changeWarehouse(next: string) {
    rememberWarehouse(next);
    const leaf = location.pathname.replace(/^\/w\/[^/]+/, '') || '';
    navigate(`/w/${encodeURIComponent(next)}${leaf}`);
  }

  const selected = useMemo(() => {
    const match = navGroups
      .flatMap((group) => group.items)
      .find((item) => {
        const href = hrefFor(warehouseId, item.to, item.pda);
        return item.end ? location.pathname === href : location.pathname.startsWith(href);
      });
    return match ? [match.label] : [];
  }, [location.pathname, warehouseId, access]);

  useEffect(() => {
    document.getElementById('page-title')?.focus();
  }, [location.pathname]);

  return (
    <WorkspaceProvider
      value={{
        token,
        warehouseId,
        warehouseName: current?.name,
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
        <Layout.Sider
          width={220}
          theme="light"
          className="app-sider"
          breakpoint="lg"
          collapsedWidth={64}
        >
          <Link
            className="brand"
            to={warehouseId ? `/w/${warehouseId}` : '/'}
            aria-label="WMS 工作台首页"
          >
            <span className="brand-mark" aria-hidden="true">
              <svg viewBox="0 0 32 32">
                <path
                  d="M4 13 16 5l12 8v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2zM12 27v-8h8v8M6 13h20"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                />
              </svg>
            </span>
            <span>
              <strong>WMS 作业台</strong>
              <small>仓储执行系统</small>
            </span>
          </Link>
          <Menu
            theme="light"
            mode="inline"
            selectedKeys={selected}
            items={navGroups.map((group) => ({
              type: 'group',
              key: group.title,
              label: group.title,
              children: group.items.map((item) => ({
                key: item.label,
                icon: item.icon,
                label: item.label,
                onClick: () =>
                  navigate(
                    hrefFor(
                      access?.mode === 'CENTRAL' && !access.warehouseIds.length ? '_' : warehouseId,
                      item.to,
                      item.pda,
                    ),
                  ),
              })),
            }))}
          />
          <Typography.Text className="side-foot">数据权威在后端 · 数量按字符串展示</Typography.Text>
        </Layout.Sider>
        <Layout>
          <Layout.Header className="app-header">
            <Flex className="app-header-row" align="center" justify="space-between" gap={16}>
              <Space className="workspace-switcher" size={10}>
                <ShopOutlined />
                <Typography.Text type="secondary">作业仓</Typography.Text>
                <Select
                  style={{ minWidth: 280 }}
                  value={warehouseId || undefined}
                  disabled={loadState !== 'ready' || warehouses.length === 0}
                  placeholder={loadState === 'error' ? '仓库服务不可达' : '正在读取可访问仓…'}
                  options={warehouses.map((row) => ({
                    value: row.id,
                    label: `${row.name} · ${row.id}`,
                  }))}
                  onChange={changeWarehouse}
                />
              </Space>
              <Space className="workspace-actions" size={12} wrap>
                <Typography.Text type="secondary">本机 {clock} UTC</Typography.Text>
                <Popover
                  title="当前可用权限"
                  content={
                    <Space orientation="vertical" size={8} style={{ maxWidth: 420 }}>
                      <Typography.Text>企业 {claims.enterpriseId || '未声明'}</Typography.Text>
                      <Typography.Text>
                        仓范围 {claims.warehouses.length ? claims.warehouses.join(', ') : '无'}
                      </Typography.Text>
                      <div>
                        {claims.scopes.length ? (
                          claims.scopes.map((scope) => <Tag key={scope}>{scope}</Tag>)
                        ) : (
                          <Typography.Text type="secondary">当前没有作业权限</Typography.Text>
                        )}
                      </div>
                    </Space>
                  }
                >
                  <Button icon={<SafetyCertificateOutlined />}>
                    {claims.scopes.length ? `${claims.scopes.length} 项权限` : '无可用权限'}
                  </Button>
                </Popover>
                <Button disabled={!token} onClick={retry}>
                  刷新权限
                </Button>
                <Avatar style={{ background: wmsTokens.colorPrimary }}>
                  {String(displayName).slice(0, 1).toUpperCase()}
                </Avatar>
                <Typography.Text className="workspace-user" strong title={String(displayName)}>
                  {displayName}
                </Typography.Text>
                <Tooltip title="打开 PDA">
                  <Button
                    disabled={
                      !access ||
                      (access.mode === 'CENTRAL' &&
                        !access.menus.some((menu) => menu.route === '/pda/receive'))
                    }
                    icon={<MobileOutlined />}
                    onClick={() => navigate(warehouseId ? `/pda/${warehouseId}/receive` : '/')}
                  >
                    打开 PDA
                  </Button>
                </Tooltip>
                <Button
                  icon={<LogoutOutlined />}
                  onClick={() => void createUserManager().signoutRedirect()}
                >
                  退出
                </Button>
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
            ) : routeAllowed ? (
              <Outlet />
            ) : (
              <StatusBanner kind="forbidden" title="当前仓或页面未获授权" />
            )}
          </Layout.Content>
        </Layout>
      </Layout>
    </WorkspaceProvider>
  );
}
