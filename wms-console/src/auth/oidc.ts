import { UserManager, WebStorageStateStore } from "oidc-client-ts";

export function issuerConfigured(): boolean {
  return Boolean(import.meta.env.VITE_OIDC_ISSUER);
}

export function createUserManager(): UserManager {
  const issuer = import.meta.env.VITE_OIDC_ISSUER;
  if (!issuer) {
    throw new Error("未配置 VITE_OIDC_ISSUER");
  }
  return new UserManager({
    authority: issuer,
    client_id: import.meta.env.VITE_OIDC_CLIENT_ID || "wms-console",
    redirect_uri: `${window.location.origin}/callback`,
    post_logout_redirect_uri: window.location.origin,
    response_type: "code",
    scope: "openid profile",
    userStore: new WebStorageStateStore({ store: window.sessionStorage })
  });
}
