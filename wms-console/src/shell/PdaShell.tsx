import { useEffect } from "react";
import { Link, Outlet, useLocation, useParams } from "react-router-dom";
import { User } from "oidc-client-ts";
import { Button, Flex, Layout, Space, Typography } from "antd";
import { tokenClaims } from "../auth/tokenClaims";
import { WorkspaceProvider } from "./WorkspaceContext";

const PDA_JOBS = [
  { to: "receive", label: "收货" },
  { to: "pick", label: "拣货" },
  { to: "ship", label: "发运" }
];

export function PdaShell({ user, token }: { user: User; token?: string }) {
  const { warehouseId = "" } = useParams();
  const location = useLocation();
  const displayName = user.profile.name || user.profile.preferred_username || user.profile.sub;
  const job = PDA_JOBS.find((item) => location.pathname.endsWith(`/${item.to}`)) ?? PDA_JOBS[0];
  const claims = tokenClaims(token);

  useEffect(() => {
    document.getElementById("page-title")?.focus();
  }, [warehouseId]);

  return (
    <WorkspaceProvider value={{
      token,
      warehouseId,
      enterpriseId: claims.enterpriseId,
      warehouses: claims.warehouses,
      scopes: claims.scopes
    }}>
      <Layout className="app-shell">
        <a className="skip-link" href="#main">跳到主内容</a>
        <Layout.Header className="app-header">
          <Flex align="center" justify="space-between">
            <Link to={warehouseId ? `/w/${warehouseId}` : "/"}>
              <Typography.Title level={4} style={{ margin: 0, color: "#1D2129" }}>PDA {job.label}</Typography.Title>
              <Typography.Text type="secondary">{warehouseId || "未选仓"} · {displayName}</Typography.Text>
            </Link>
            <Space>
              {PDA_JOBS.map((item) => (
                <Link key={item.to} to={`/pda/${encodeURIComponent(warehouseId)}/${item.to}`}>
                  <Button type={item.to === job.to ? "primary" : "default"}>{item.label}</Button>
                </Link>
              ))}
              <Link to={warehouseId ? `/w/${warehouseId}` : "/"}><Button>返回工作台</Button></Link>
            </Space>
          </Flex>
        </Layout.Header>
        <Layout.Content className="app-content" id="main">
          <Outlet />
        </Layout.Content>
      </Layout>
    </WorkspaceProvider>
  );
}
