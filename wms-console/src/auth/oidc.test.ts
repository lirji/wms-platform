import { expect, test, vi } from 'vitest';

test('concurrent callback effects exchange the one-time authorization code once', async () => {
  vi.resetModules();
  const exchange = vi.fn().mockResolvedValue({ access_token: 'returned-access' });
  vi.doMock('oidc-client-ts', () => ({
    UserManager: class {
      signinRedirectCallback = exchange;
    },
    WebStorageStateStore: class {},
  }));
  vi.stubEnv('VITE_OIDC_ISSUER', 'http://localhost:18090');
  try {
    const { finishSigninRedirect } = await import('./oidc');
    const first = finishSigninRedirect(),
      repeated = finishSigninRedirect();
    expect(first).toBe(repeated);
    expect(await first).toEqual({ access_token: 'returned-access' });
    expect(exchange).toHaveBeenCalledTimes(1);
  } finally {
    vi.unstubAllEnvs();
    vi.doUnmock('oidc-client-ts');
  }
});
