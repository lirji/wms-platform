import { Link, Outlet, useParams } from "react-router-dom";
import { User } from "oidc-client-ts";
import { WorkspaceProvider } from "./WorkspaceContext";

export function PdaShell({ user, token }: { user: User; token?: string }) {
  const { warehouseId = "" } = useParams();
  const displayName = user.profile.name || user.profile.preferred_username || user.profile.sub;
  return (
    <WorkspaceProvider value={{ token, warehouseId }}>
      <div className="app pda-shell">
        <header className="topbar">
          <Link className="brand" to={warehouseId ? `/w/${warehouseId}/inbound` : "/"}>
            <span className="brand-mark" aria-hidden="true">
              <svg viewBox="0 0 32 32"><path d="M4 13 16 5l12 8v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2zM12 27v-8h8v8M6 13h20" fill="none" stroke="currentColor" strokeWidth="1.8" /></svg>
            </span>
            <span>
              <strong>PDA 收货</strong>
              <small>{warehouseId || "未选仓"} · {displayName}</small>
            </span>
          </Link>
          <Link className="btn btn-ghost" to={warehouseId ? `/w/${warehouseId}` : "/"}>返回工作台</Link>
        </header>
        <main className="app-main">
          <Outlet />
        </main>
      </div>
    </WorkspaceProvider>
  );
}
