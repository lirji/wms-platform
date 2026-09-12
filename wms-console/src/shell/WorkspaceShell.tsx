import { useEffect, useState } from "react";
import { Link, NavLink, Outlet, useNavigate, useParams } from "react-router-dom";
import { User } from "oidc-client-ts";
import { api } from "../api/client";
import { field, pageItems } from "../api/envelope";
import { createUserManager } from "../auth/oidc";
import { DESKTOP_NAV } from "./nav";
import { rememberWarehouse } from "./warehouseSession";
import { WorkspaceProvider } from "./WorkspaceContext";

type Warehouse = { id: string; name: string };

export function WorkspaceShell({ user, token }: { user: User; token?: string }) {
  const { warehouseId = "" } = useParams();
  const navigate = useNavigate();
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [loadState, setLoadState] = useState<"loading" | "ready" | "error">("loading");
  const displayName = user.profile.name || user.profile.preferred_username || user.profile.sub;

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
    const leaf = window.location.pathname.replace(/^\/w\/[^/]+/, "") || "";
    navigate(`/w/${encodeURIComponent(next)}${leaf}`);
  }

  return (
    <WorkspaceProvider value={{ token, warehouseId }}>
      <div className="app">
        <header className="topbar">
          <Link className="brand" to={warehouseId ? `/w/${warehouseId}` : "/"} aria-label="WMS 工作台首页">
            <span className="brand-mark" aria-hidden="true">
              <svg viewBox="0 0 32 32"><path d="M4 13 16 5l12 8v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2zM12 27v-8h8v8M6 13h20" fill="none" stroke="currentColor" strokeWidth="1.8" /></svg>
            </span>
            <span>
              <strong>WMS 仓储管理台</strong>
              <small>作业数据来自后端</small>
            </span>
          </Link>
          <label className="warehouse-picker">
            <span>当前仓</span>
            <select
              value={warehouseId}
              disabled={loadState !== "ready" || warehouses.length === 0}
              onChange={(event) => changeWarehouse(event.target.value)}
            >
              {loadState === "loading" ? <option value="">正在读取可访问仓…</option> : null}
              {loadState === "error" ? <option value="">仓库服务不可达</option> : null}
              {loadState === "ready" && warehouses.length === 0 ? <option value="">当前身份没有可访问仓</option> : null}
              {warehouses.map((row) => (
                <option key={row.id} value={row.id}>{row.name}</option>
              ))}
            </select>
          </label>
          <div className="topbar-user">
            <Link className="btn btn-ghost" to={warehouseId ? `/pda/${warehouseId}/receive` : "/"}>PDA</Link>
            <span title={displayName}>{displayName}</span>
            <button type="button" className="btn btn-ghost" onClick={() => void createUserManager().signoutRedirect()}>退出</button>
          </div>
        </header>
        <nav className="app-nav" aria-label="作业模块">
          {DESKTOP_NAV.map((item) => {
            const href = warehouseId ? `/w/${warehouseId}${item.to ? `/${item.to}` : ""}` : "/";
            return (
              <NavLink key={item.label} to={href} end={item.end} className={({ isActive }) => isActive ? "app-nav-link is-active" : "app-nav-link"}>
                {item.label}
              </NavLink>
            );
          })}
        </nav>
        <main className="app-main">
          <Outlet />
        </main>
      </div>
    </WorkspaceProvider>
  );
}
