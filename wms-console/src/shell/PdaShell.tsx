import { Link, Outlet, useParams } from "react-router-dom";
import { User } from "oidc-client-ts";
import { Button, Flex, Layout, Typography } from "antd";
import { WorkspaceProvider } from "./WorkspaceContext";

export function PdaShell({ user, token }: { user: User; token?: string }) {
  const { warehouseId = "" } = useParams();
  const displayName = user.profile.name || user.profile.preferred_username || user.profile.sub;
  return (
    <WorkspaceProvider value={{ token, warehouseId }}>
      <Layout className="app-shell">
        <Layout.Header className="app-header">
          <Flex align="center" justify="space-between">
            <Link to={warehouseId ? `/w/${warehouseId}/inbound` : "/"}>
              <Typography.Title level={4} style={{ margin: 0, color: "#0f172a" }}>PDA 收货</Typography.Title>
              <Typography.Text type="secondary">{warehouseId || "未选仓"} · {displayName}</Typography.Text>
            </Link>
            <Link to={warehouseId ? `/w/${warehouseId}` : "/"}><Button type="primary">返回工作台</Button></Link>
          </Flex>
        </Layout.Header>
        <Layout.Content className="app-content">
          <Outlet />
        </Layout.Content>
      </Layout>
    </WorkspaceProvider>
  );
}
