import { useEffect, useState } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { User } from "oidc-client-ts";
import { createUserManager, issuerConfigured } from "../auth/oidc";
import { sanitizeReturnTo } from "../auth/returnTo";
import { CatalogPage } from "../features/catalog/CatalogPage";
import { CountDetailPage } from "../features/count/CountDetailPage";
import { CountPage } from "../features/count/CountPage";
import { FulfillmentDetailPage } from "../features/fulfillment/FulfillmentDetailPage";
import { FulfillmentPage } from "../features/fulfillment/FulfillmentPage";
import { OutboundDetailPage } from "../features/fulfillment/OutboundDetailPage";
import { HomePage } from "../features/home/HomePage";
import { InboundDetailPage } from "../features/inbound/InboundDetailPage";
import { InboundPage } from "../features/inbound/InboundPage";
import { JobDetailPage } from "../features/jobs/JobDetailPage";
import { JobsPage } from "../features/jobs/JobsPage";
import { ReceivePage } from "../features/pda/ReceivePage";
import { ReconPage } from "../features/recon/ReconPage";
import { StockPage } from "../features/stock/StockPage";
import { TransferDetailPage } from "../features/transfer/TransferDetailPage";
import { TransferPage } from "../features/transfer/TransferPage";
import { LoginCallbackPage, LoginPage, LoginSetupPage } from "../pages/LoginPage";
import { LegacyRedirect, PdaLegacyRedirect, RootRedirect } from "../shell/LegacyRedirect";
import { PdaShell } from "../shell/PdaShell";
import { WorkspaceShell } from "../shell/WorkspaceShell";

export function App() {
  const [user, setUser] = useState<User | null>(null);
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
  const returnTo = sanitizeReturnTo(location.pathname + location.search);

  return (
    <Routes>
      <Route path="/login" element={<LoginPage user={user} />} />
      <Route path="/callback" element={<LoginCallbackPage />} />
      <Route
        path="*"
        element={user ? (
          <Routes>
            <Route path="/" element={<RootRedirect />} />
            <Route path="/w/:warehouseId" element={<WorkspaceShell user={user} token={token} />}>
              <Route index element={<HomePage />} />
              <Route path="catalog" element={<CatalogPage />} />
              <Route path="inbound" element={<InboundPage />} />
              <Route path="inbound/:inboundOrderId" element={<InboundDetailPage />} />
              <Route path="stock" element={<StockPage />} />
              <Route path="fulfillment" element={<FulfillmentPage />} />
              <Route path="fulfillment/:fulfillmentId" element={<FulfillmentDetailPage />} />
              <Route path="outbound/:outboundOrderId" element={<OutboundDetailPage />} />
              <Route path="transfers" element={<TransferPage />} />
              <Route path="transfers/:transferId" element={<TransferDetailPage />} />
              <Route path="counts" element={<CountPage />} />
              <Route path="counts/:countPlanId" element={<CountDetailPage />} />
              <Route path="jobs" element={<JobsPage />} />
              <Route path="jobs/:jobId" element={<JobDetailPage />} />
              <Route path="recon" element={<ReconPage />} />
            </Route>
            <Route path="/pda/:warehouseId/receive" element={<PdaShell user={user} token={token} />}>
              <Route index element={<ReceivePage />} />
            </Route>
            <Route path="/masterdata" element={<LegacyRedirect leaf="catalog" />} />
            <Route path="/inbound" element={<LegacyRedirect leaf="inbound" />} />
            <Route path="/inventory" element={<LegacyRedirect leaf="stock" />} />
            <Route path="/outbound" element={<LegacyRedirect leaf="fulfillment" />} />
            <Route path="/transfers" element={<LegacyRedirect leaf="transfers" />} />
            <Route path="/counts" element={<LegacyRedirect leaf="counts" />} />
            <Route path="/jobs" element={<LegacyRedirect leaf="jobs" />} />
            <Route path="/recon" element={<LegacyRedirect leaf="recon" />} />
            <Route path="/pda" element={<PdaLegacyRedirect />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        ) : (
          <Navigate to={`/login?returnTo=${encodeURIComponent(returnTo)}`} replace />
        )}
      />
    </Routes>
  );
}
