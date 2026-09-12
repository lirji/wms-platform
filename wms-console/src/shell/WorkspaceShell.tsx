import { useEffect, useMemo, useState } from "react";
import { Link, Outlet, useLocation, useNavigate, useParams } from "react-router-dom";
import { User } from "oidc-client-ts";
import { Avatar, Button, Flex, Layout, Menu, Popover, Select, Space, Tag, Typography } from "antd";
import { LogoutOutlined, MobileOutlined, SafetyCertificateOutlined, ShopOutlined } from "@ant-design/icons";
import { api } from "../api/client";
import { field, pageItems } from "../api/envelope";
import { createUserManager } from "../auth/oidc";
import { tokenClaims } from "../auth/tokenClaims";
import { NAV_GROUPS } from "./nav";
import { rememberWarehouse } from "./warehouseSession";
import { WorkspaceProvider } from "./WorkspaceContext";

type Warehouse = { id: string; name: string };

function hrefFor(warehouseId: string, to: string, pda?: boolean) {
  if (!warehouseId) {
    return "/";
  }
  if (pda) {
    return `/pda/${encodeURIComponent(warehouseId)}/receive`;
  }
  return `/w/${encodeURIComponent(warehouseId)}${to ? `/${to}` : ""}`;
}

function useClock() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  return now.toISOString().replace("T", " ").slice(0, 19);
}

export function WorkspaceShell({ user, token }: { user: User; token?: string }) {
  const { warehouseId = "" } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [loadState, setLoadState] = useState<"loading" | "ready" | "error">("loading");
  const displayName = user.profile.name || user.profile.preferred_username || user.profile.sub;
  const claims = useMemo(() => tokenClaims(token), [token]);
  const clock = useClock();
  const current = warehouses.find((row) => row.id === warehouseId);

  useEffect(() => {
    if (!token) {
      return;
    }
    setLoadState("loading");
    api("/api/wms/v1/warehouses", token)
      .then((body) => {
        const mapped = pageItems(body).map((row) => ({
          id: field(row, "id", "warehouseId"),
          name: field(row, "name", "code", "id")
        })).filter((row) => row.id);
        setWarehouses(mapped);
        setLoadState("ready");
        const valid = mapped.some((row) => row.id === warehouseId);
        const next = valid ? warehouseId : mapped[0]?.id ?? "";
        if (next && next !== warehouseId) {
          rememberWarehouse(next);
          navigate(`/w/${encodeURIComponent(next)}`, { replace: true });
        }
        if (next) {
          rememberWarehouse(next);
        }
      })
      .catch(() => {
        setWarehouses([]);
        setLoadState("error");
      });
  }, [navigate, token, warehouseId]);

  function changeWarehouse(next: string) {
    rememberWarehouse(next);
    const leaf = location.pathname.replace(/^\/w\/[^/]+/, "") || "";
    navigate(`/w/${encodeURIComponent(next)}${leaf}`);
  }

  const selected = useMemo(() => {
    const match = NAV_GROUPS.flatMap((group) => group.items).find((item) => {
      const href = hrefFor(warehouseId, item.to, item.pda);
      return item.end ? location.pathname === href : location.pathname.startsWith(href);
    });
    return match ? [match.label] : [];
  }, [location.pathname, warehouseId]);

  return (
    <WorkspaceProvider value={{
      token,
      warehouseId,
      warehouseName: current?.name,
      enterpriseId: claims.enterpriseId,
      warehouses: claims.warehouses,
      scopes: claims.scopes
    }}>
      <Layout className="app-shell">
        <Layout.Sider width={232} theme="dark" className="app-sider" breakpoint="lg" collapsedWidth={72}>
          <Link className="brand" to={warehouseId ? `/w/${warehouseId}` : "/"} aria-label="WMS 工作台首页">
            <span className="brand-mark" aria-hidden="true">
              <svg viewBox="0 0 32 32"><path d="M4 13 16 5l12 8v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2zM12 27v-8h8v8M6 13h20" fill="none" stroke="currentColor" strokeWidth="1.8" /></svg>
            </span>
            <span>
              <strong>WMS 作业台</strong>
              <small>仓储执行系统</small>
            </span>
          </Link>
          <Menu
            theme="dark"
            mode="inline"
            selectedKeys={selected}
            items={NAV_GROUPS.map((group) => ({
              type: "group",
              key: group.title,
              label: group.title,
              children: group.items.map((item) => ({
                key: item.label,
                icon: item.icon,
                label: item.label,
                onClick: () => navigate(hrefFor(warehouseId, item.to, item.pda))
              }))
            }))}
          />
          <Typography.Text className="side-foot">数据权威在后端 · 数量按字符串展示</Typography.Text>
        </Layout.Sider>
        <Layout>
          <Layout.Header className="app-header">
            <Flex align="center" justify="space-between" gap={16}>
              <Space size={10}>
                <ShopOutlined />
                <Typography.Text type="secondary">作业仓</Typography.Text>
                <Select
                  style={{ minWidth: 280 }}
                  value={warehouseId || undefined}
                  disabled={loadState !== "ready" || warehouses.length === 0}
                  placeholder={loadState === "error" ? "仓库服务不可达" : "正在读取可访问仓…"}
                  options={warehouses.map((row) => ({ value: row.id, label: `${row.name} · ${row.id}` }))}
                  onChange={changeWarehouse}
                />
              </Space>
              <Space size={12}>
                <Typography.Text type="secondary">本机 {clock} UTC</Typography.Text>
                <Popover
                  title="当前令牌权限"
                  content={(
                    <Space orientation="vertical" size={8} style={{ maxWidth: 420 }}>
                      <Typography.Text>企业 {claims.enterpriseId || "未声明"}</Typography.Text>
                      <Typography.Text>仓范围 {claims.warehouses.length ? claims.warehouses.join(", ") : "无"}</Typography.Text>
                      <div>
                        {claims.scopes.length
                          ? claims.scopes.map((scope) => <Tag key={scope}>{scope}</Tag>)
                          : <Typography.Text type="secondary">令牌没有作业权限名</Typography.Text>}
                      </div>
                    </Space>
                  )}
                >
                  <Button icon={<SafetyCertificateOutlined />}>
                    {claims.scopes.length ? `${claims.scopes.length} 项权限` : "权限未声明"}
                  </Button>
                </Popover>
                <Avatar style={{ background: "#0f766e" }}>{String(displayName).slice(0, 1).toUpperCase()}</Avatar>
                <Typography.Text strong>{displayName}</Typography.Text>
                <Button icon={<MobileOutlined />} onClick={() => navigate(warehouseId ? `/pda/${warehouseId}/receive` : "/")}>
                  打开 PDA
                </Button>
                <Button icon={<LogoutOutlined />} onClick={() => void createUserManager().signoutRedirect()}>
                  退出
                </Button>
              </Space>
            </Flex>
          </Layout.Header>
          <Layout.Content className="app-content">
            <Outlet />
          </Layout.Content>
        </Layout>
      </Layout>
    </WorkspaceProvider>
  );
}
