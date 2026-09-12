import { useEffect, useState } from "react";
import { Link, NavLink, Outlet, useLocation, useNavigate, useParams } from "react-router-dom";
import { User } from "oidc-client-ts";
import { api } from "../api/client";
import { field, pageItems } from "../api/envelope";
import { createUserManager } from "../auth/oidc";
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

  return (
    <WorkspaceProvider value={{ token, warehouseId, warehouseName: current?.name }}>
      <div className="app">
        <aside className="sidebar">
          <Link className="brand" to={warehouseId ? `/w/${warehouseId}` : "/"} aria-label="WMS 工作台首页">
            <span className="brand-mark" aria-hidden="true">
              <svg viewBox="0 0 32 32"><path d="M4 13 16 5l12 8v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2zM12 27v-8h8v8M6 13h20" fill="none" stroke="currentColor" strokeWidth="1.8" /></svg>
            </span>
            <span>
              <strong>WMS 作业台</strong>
              <small>仓储执行系统</small>
            </span>
          </Link>
          <nav className="side-nav" aria-label="作业模块">
            {NAV_GROUPS.map((group) => (
              <div key={group.title} className="side-group">
                <p className="side-label">{group.title}</p>
                {group.items.map((item) => {
                  const href = hrefFor(warehouseId, item.to, item.pda);
                  return (
                    <NavLink
                      key={item.label}
                      to={href}
                      end={item.end}
                      className={({ isActive }) => isActive ? "side-link is-active" : "side-link"}
                    >
                      {item.label}
                    </NavLink>
                  );
                })}
              </div>
            ))}
          </nav>
          <p className="side-foot">数据权威在后端 · 数量按字符串展示</p>
        </aside>
        <div className="workspace">
          <header className="topbar">
            <label className="warehouse-picker">
              <span>作业仓</span>
              <select
                value={warehouseId}
                disabled={loadState !== "ready" || warehouses.length === 0}
                onChange={(event) => changeWarehouse(event.target.value)}
              >
                {loadState === "loading" ? <option value="">正在读取可访问仓…</option> : null}
                {loadState === "error" ? <option value="">仓库服务不可达</option> : null}
                {loadState === "ready" && warehouses.length === 0 ? <option value="">当前身份没有可访问仓</option> : null}
                {warehouses.map((row) => (
                  <option key={row.id} value={row.id}>{row.name} · {row.id}</option>
                ))}
              </select>
            </label>
            <div className="topbar-meta">
              <span>本机时间 {clock} UTC</span>
              <span className="topbar-user" title={displayName}>{displayName}</span>
              <Link className="btn" to={warehouseId ? `/pda/${warehouseId}/receive` : "/"}>打开 PDA</Link>
              <button type="button" className="btn" onClick={() => void createUserManager().signoutRedirect()}>退出</button>
            </div>
          </header>
          <main className="app-main">
            <Outlet />
          </main>
        </div>
      </div>
    </WorkspaceProvider>
  );
}
