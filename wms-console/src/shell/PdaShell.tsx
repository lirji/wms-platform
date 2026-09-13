import { useEffect } from "react";
import { Link, Outlet, useParams } from "react-router-dom";
import { User } from "oidc-client-ts";
import { Button, Flex, Layout, Typography } from "antd";
import { tokenClaims } from "../auth/tokenClaims";
import { WorkspaceProvider } from "./WorkspaceContext";

export function PdaShell({ user, token }: { user: User; token?: string }) {
  const { warehouseId = "" } = useParams();
  const displayName = user.profile.name || user.profile.preferred_username || user.profile.sub;
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
            <Link to={warehouseId ? `/w/${warehouseId}/inbound` : "/"}>
              <Typography.Title level={4} style={{ margin: 0, color: "#1D2129" }}>PDA 收货</Typography.Title>
              <Typography.Text type="secondary">{warehouseId || "未选仓"} · {displayName}</Typography.Text>
            </Link>
            <Link to={warehouseId ? `/w/${warehouseId}` : "/"}><Button>返回工作台</Button></Link>
          </Flex>
        </Layout.Header>
        <Layout.Content className="app-content" id="main">
          <Outlet />
        </Layout.Content>
      </Layout>
    </WorkspaceProvider>
  );
}
