import { UserManager, WebStorageStateStore, type User } from "oidc-client-ts";

let signinCallback: Promise<User> | undefined;

/** React重复执行effect时共享同一次授权码交换；一次性code不能被第二次消费。 */
export function finishSigninRedirect(): Promise<User> {
  signinCallback ??= createUserManager().signinRedirectCallback();
  return signinCallback;
}

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
    client_id: import.meta.env.VITE_OIDC_CLIENT_ID || "wms-platform",
    redirect_uri: `${window.location.origin}/callback`,
    post_logout_redirect_uri: window.location.origin,
    response_type: "code",
    scope: "openid profile",
    userStore: new WebStorageStateStore({ store: window.sessionStorage })
  });
}
