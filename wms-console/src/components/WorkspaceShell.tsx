import { Dispatch, SetStateAction, useEffect, useState, type ReactNode } from "react";
import { Link, NavLink } from "react-router-dom";
import { User } from "oidc-client-ts";
import { api } from "../api/client";
import { createUserManager } from "../auth/oidc";

type Warehouse = { id: string; name: string };

const NAV = [
  { to: "/", label: "工作台", end: true },
  { to: "/masterdata", label: "商品/库位" },
  { to: "/inbound", label: "入库" },
  { to: "/inventory", label: "库存台账" },
  { to: "/outbound", label: "出库履约" },
  { to: "/transfers", label: "调拨" },
  { to: "/counts", label: "盘点" },
  { to: "/jobs", label: "任务/设备" },
  { to: "/recon", label: "对账" },
  { to: "/pda", label: "PDA 收货" }
];

export function WorkspaceShell({
  user,
  token,
  warehouseId,
  setWarehouseId,
  children
}: {
  user: User;
  token?: string;
  warehouseId: string;
  setWarehouseId: Dispatch<SetStateAction<string>>;
  children: ReactNode;
}) {
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
        const items = body && typeof body === "object" && "items" in body && Array.isArray(body.items)
          ? body.items as Array<Record<string, unknown>>
          : [];
        const mapped = items.map((row) => ({
          id: String(row.id ?? ""),
          name: String(row.name ?? row.code ?? row.id ?? "")
        })).filter((row) => row.id);
        setWarehouses(mapped);
        setWarehouseId((current) => mapped.some((row) => row.id === current) ? current : (mapped[0]?.id ?? ""));
        setLoadState("ready");
      })
      .catch(() => {
        setWarehouses([]);
        setLoadState("error");
      });
  }, [setWarehouseId, token]);

  return (
    <div className="app">
      <header className="topbar">
        <Link className="brand" to="/" aria-label="WMS 工作台首页">
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
            onChange={(event) => setWarehouseId(event.target.value)}
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
          <span title={displayName}>{displayName}</span>
          <button type="button" className="btn btn-ghost" onClick={() => void createUserManager().signoutRedirect()}>退出</button>
        </div>
      </header>
      <nav className="app-nav" aria-label="作业模块">
        {NAV.map((item) => (
          <NavLink key={item.to} to={item.to} end={item.end} className={({ isActive }) => isActive ? "app-nav-link is-active" : "app-nav-link"}>
            {item.label}
          </NavLink>
        ))}
      </nav>
      <main className="app-main">{children}</main>
    </div>
  );
}
