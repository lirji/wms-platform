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
    } catch (cause) {
      setRedirecting(false);
      setError(cause instanceof Error ? cause.message : "无法跳转统一身份登录");
    }
  };

  return (
    <main className="login-root">
      <div className="login-card">
        <aside className="login-aside">
          <div className="login-brand">
            <span className="login-brand-mark"><WarehouseMark /></span>
            <span>
              <strong>WMS 仓储管理台</strong>
              <small>WAREHOUSE OPERATIONS</small>
            </span>
          </div>
          <div>
            <p className="login-kicker">作业入口</p>
            <h1>先确认身份<br />再看本仓数据</h1>
            <p className="login-lead">入库、库存、跨仓履约与 PDA 都从后端读取。页面不写死仓库、SKU 或库存数字。</p>
            <ul className="login-features">
              <li>
                <span>统一登录</span>
                <small>Casdoor 授权码 + PKCE，本台不保存口令</small>
              </li>
              <li>
                <span>企业来自令牌</span>
                <small>enterprise_id 决定可见企业，不能用请求头扩大</small>
              </li>
              <li>
                <span>仓范围来自令牌</span>
                <small>warehouses 之外的仓接口直接拒绝</small>
              </li>
            </ul>
          </div>
        </aside>
        <section className="login-form">
          <p className="login-form-kicker">Casdoor SSO</p>
          <h2>登录后进入作业台</h2>
          <p className="login-form-lead">使用组织 <code>wms-platform</code> 的已开通账号。可见企业与仓范围只信令牌，不在页面写死。</p>
          {error ? <Alert type="error" showIcon title={error} style={{ marginBottom: 16 }} /> : null}
          <Button type="primary" size="large" block loading={redirecting} onClick={() => void startLogin()}>
            {redirecting ? "正在跳转统一身份…" : "使用统一身份登录"}
          </Button>
          <p className="login-note">登录完成后回到本次打开的页面。若 403，是令牌缺少企业或仓范围，需要在身份侧补齐。</p>
        </section>
      </div>
      <p className="login-foot">内部仓储作业 · 身份 http://localhost:8000</p>
    </main>
  );
}

export function LoginCallbackPage() {
  return (
    <main className="login-root">
      <section className="login-card login-card-narrow" aria-live="polite">
        <div className="login-form">
          <p className="login-form-kicker">正在完成登录</p>
          <h2>回写会话</h2>
          <p className="login-form-lead">已从统一身份返回，正在校验授权码。完成后进入作业台。</p>
        </div>
      </section>
    </main>
  );
}

export function LoginSetupPage() {
  return (
    <main className="login-root">
      <section className="login-card login-card-narrow">
        <div className="login-form">
          <p className="login-form-kicker">配置缺失</p>
          <h2>未配置 OIDC</h2>
          <p className="login-form-lead">设置 <code>VITE_OIDC_ISSUER</code> 与 <code>VITE_OIDC_CLIENT_ID</code> 后刷新。页面不会为此写死仓库或库存。</p>
        </div>
      </section>
    </main>
  );
}
