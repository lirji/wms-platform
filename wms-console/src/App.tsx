import { useEffect, useState } from "react";
import { Link, Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { User } from "oidc-client-ts";
import { createUserManager, issuerConfigured } from "./auth/oidc";
import { StatusBanner } from "./components/StatusBanner";
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
    return (
      <main className="shell">
        <StatusBanner
          kind="error"
          title="未配置 OIDC"
          detail="设置 VITE_OIDC_ISSUER / VITE_OIDC_CLIENT_ID 后从 API 读取数据。页面不写死仓库或库存。"
        />
      </main>
    );
  }

  const token = user?.access_token;
  const page = { token, warehouseId, setWarehouseId };

  return (
    <div className="shell">
      <header>
        <Link to="/">WMS</Link>
        <span>{warehouseId || "未选仓"}</span>
        {user ? (
          <button type="button" onClick={() => void createUserManager().signoutRedirect()}>退出</button>
        ) : (
          <button type="button" onClick={() => void createUserManager().signinRedirect()}>登录</button>
        )}
      </header>
      {!token ? <StatusBanner kind="forbidden" title="需要登录后才能读取后端数据" /> : null}
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
        <Route path="/callback" element={<StatusBanner kind="loading" title="正在完成登录" />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </div>
  );
}
