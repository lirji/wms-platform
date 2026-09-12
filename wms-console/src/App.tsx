import { useEffect, useState } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { User } from "oidc-client-ts";
import { createUserManager, issuerConfigured } from "./auth/oidc";
import { sanitizeReturnTo } from "./auth/returnTo";
import { WorkspaceShell } from "./components/WorkspaceShell";
import { LoginCallbackPage, LoginPage, LoginSetupPage } from "./pages/LoginPage";
import {
  DocumentPage,
  HomePage,
  InventoryPage,
  MasterdataPage,
  PdaPage,
  ReconPage
} from "./pages/Pages";

export function App() {
  const [user, setUser] = useState<User | null>(null);
  const [warehouseId, setWarehouseId] = useState("");
  const navigate = useNavigate();
  const location = useLocation();
  const configured = issuerConfigured();

  useEffect(() => {
    if (!configured) {
      return;
    }
    const manager = createUserManager();
    void manager.getUser().then(setUser);
    if (window.location.pathname === "/callback") {
      void manager.signinRedirectCallback().then((signed) => {
        setUser(signed);
        navigate("/", { replace: true });
      });
    }
  }, [configured, navigate]);

  if (!configured) {
    return <LoginSetupPage />;
  }

  const token = user?.access_token;
  const page = { token, warehouseId, setWarehouseId };
  const returnTo = sanitizeReturnTo(location.pathname + location.search);

  return (
    <Routes>
      <Route path="/login" element={<LoginPage user={user} />} />
      <Route path="/callback" element={<LoginCallbackPage />} />
      <Route
        path="*"
        element={user ? (
          <WorkspaceShell user={user} token={token} warehouseId={warehouseId} setWarehouseId={setWarehouseId}>
            <Routes>
              <Route path="/" element={<HomePage {...page} />} />
              <Route path="/masterdata" element={<MasterdataPage {...page} />} />
              <Route path="/inventory" element={<InventoryPage {...page} />} />
              <Route path="/inbound" element={<DocumentPage {...page} title="入库工作台" path={`/api/wms/v1/warehouses/${warehouseId}/inbound-orders`} />} />
              <Route path="/outbound" element={<DocumentPage {...page} title="履约与出库" path={`/api/wms/v1/warehouses/${warehouseId}/outbound-orders`} extra="跨仓分配请看履约单各仓进度，单仓 CONFIRMED 不是整单成功" />} />
              <Route path="/transfers" element={<DocumentPage {...page} title="调拨" path="/api/wms/v1/transfers" />} />
              <Route path="/counts" element={<DocumentPage {...page} title="盘点" path={`/api/wms/v1/warehouses/${warehouseId}/count-plans`} />} />
              <Route path="/jobs" element={<DocumentPage {...page} title="任务与设备" path={`/api/wms/v1/jobs?warehouseId=${warehouseId}`} />} />
              <Route path="/recon" element={<ReconPage {...page} />} />
              <Route path="/pda" element={<PdaPage {...page} />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </WorkspaceShell>
        ) : (
          <Navigate to={`/login?returnTo=${encodeURIComponent(returnTo)}`} replace />
        )}
      />
    </Routes>
  );
}
