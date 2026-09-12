import { useEffect, useState } from "react";
import { Navigate, useSearchParams } from "react-router-dom";
import { User } from "oidc-client-ts";
import { Alert, Button } from "antd";
import { createUserManager } from "../auth/oidc";
import { sanitizeReturnTo } from "../auth/returnTo";
import "./login.css";

function WarehouseMark() {
  return (
    <svg className="login-mark-svg" viewBox="0 0 32 32" aria-hidden="true">
      <path d="M4 13 16 5l12 8v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <path d="M12 27v-8h8v8M6 13h20" fill="none" stroke="currentColor" strokeWidth="1.8" />
    </svg>
  );
}

export function LoginPage({ user }: { user: User | null }) {
  const [search] = useSearchParams();
  const returnTo = sanitizeReturnTo(search.get("returnTo"));
  const [redirecting, setRedirecting] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    const previous = document.title;
    document.title = "登录 · WMS 仓储管理台";
    document.getElementById("page-title")?.focus();
    return () => {
      document.title = previous;
    };
  }, []);

  if (user) {
    return <Navigate to={returnTo} replace />;
  }

  const startLogin = async () => {
    if (redirecting) {
      return;
    }
    setError("");
    setRedirecting(true);
    try {
      await createUserManager().signinRedirect();
    } catch {
      setRedirecting(false);
      setError("无法跳转统一身份登录。请检查 OIDC 配置后重试。");
    }
  };

  return (
    <main className="login-root">
      <section className="login-card">
        <div className="login-brand">
          <span className="login-brand-mark"><WarehouseMark /></span>
          <span>
            <strong>WMS 仓储管理台</strong>
            <small>WAREHOUSE OPERATIONS</small>
          </span>
        </div>
        <h1 id="page-title" tabIndex={-1}>登录后进入作业台</h1>
        <p className="login-lead">用统一身份确认企业与仓范围，再看本仓数据。页面不写死仓库、SKU 或库存。</p>
        {error ? <Alert type="error" showIcon title="无法开始登录" description={error} style={{ marginBottom: 16 }} /> : null}
        <Button type="primary" size="large" block loading={redirecting} onClick={() => void startLogin()}>
          {redirecting ? "正在跳转统一身份…" : "使用统一身份登录"}
        </Button>
        <p className="login-note">组织 <code>wms-platform</code>。完成后回到本次打开的页面。</p>
      </section>
    </main>
  );
}

export function LoginCallbackPage() {
  return (
    <main className="login-root">
      <section className="login-card" aria-live="polite">
        <p className="login-kicker">正在完成登录</p>
        <h1 id="page-title" tabIndex={-1}>回写会话</h1>
        <p className="login-lead">已从统一身份返回，正在校验授权码。完成后进入作业台。</p>
      </section>
    </main>
  );
}

export function LoginSetupPage() {
  return (
    <main className="login-root">
      <section className="login-card">
        <p className="login-kicker">配置缺失</p>
        <h1 id="page-title" tabIndex={-1}>未配置 OIDC</h1>
        <p className="login-lead">部署环境写入 <code>VITE_OIDC_ISSUER</code> 与 <code>VITE_OIDC_CLIENT_ID</code> 后刷新。页面不会为此写死仓库或库存。</p>
      </section>
    </main>
  );
}
