import { lazy, Suspense, useEffect, useState } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { User } from "oidc-client-ts";
import { createUserManager, issuerConfigured } from "../auth/oidc";
import { sanitizeReturnTo } from "../auth/returnTo";
import { LoginCallbackPage, LoginPage, LoginSetupPage } from "../pages/LoginPage";
import { LegacyRedirect, PdaLegacyRedirect, RootRedirect } from "../shell/LegacyRedirect";
import { PdaShell } from "../shell/PdaShell";
import { WorkspaceShell } from "../shell/WorkspaceShell";
import { StatusBanner } from "../shared/ui/StatusBanner";

const HomePage = lazy(() => import("../features/home/HomePage").then((module) => ({ default: module.HomePage })));
const CatalogPage = lazy(() => import("../features/catalog/CatalogPage").then((module) => ({ default: module.CatalogPage })));
const InboundPage = lazy(() => import("../features/inbound/InboundPage").then((module) => ({ default: module.InboundPage })));
const InboundDetailPage = lazy(() => import("../features/inbound/InboundDetailPage").then((module) => ({ default: module.InboundDetailPage })));
const StockPage = lazy(() => import("../features/stock/StockPage").then((module) => ({ default: module.StockPage })));
const FulfillmentPage = lazy(() => import("../features/fulfillment/FulfillmentPage").then((module) => ({ default: module.FulfillmentPage })));
const FulfillmentDetailPage = lazy(() => import("../features/fulfillment/FulfillmentDetailPage").then((module) => ({ default: module.FulfillmentDetailPage })));
const OutboundDetailPage = lazy(() => import("../features/fulfillment/OutboundDetailPage").then((module) => ({ default: module.OutboundDetailPage })));
const TransferPage = lazy(() => import("../features/transfer/TransferPage").then((module) => ({ default: module.TransferPage })));
const TransferDetailPage = lazy(() => import("../features/transfer/TransferDetailPage").then((module) => ({ default: module.TransferDetailPage })));
const CountPage = lazy(() => import("../features/count/CountPage").then((module) => ({ default: module.CountPage })));
const CountDetailPage = lazy(() => import("../features/count/CountDetailPage").then((module) => ({ default: module.CountDetailPage })));
const JobsPage = lazy(() => import("../features/jobs/JobsPage").then((module) => ({ default: module.JobsPage })));
const JobDetailPage = lazy(() => import("../features/jobs/JobDetailPage").then((module) => ({ default: module.JobDetailPage })));
const ReconPage = lazy(() => import("../features/recon/ReconPage").then((module) => ({ default: module.ReconPage })));
const ReceivePage = lazy(() => import("../features/pda/ReceivePage").then((module) => ({ default: module.ReceivePage })));

function PageFallback() {
  return <StatusBanner kind="loading" title="正在打开作业页" />;
}

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
    <Suspense fallback={<PageFallback />}>
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
    </Suspense>
  );
}
