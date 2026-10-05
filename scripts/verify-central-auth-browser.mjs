/** 真实浏览器PKCE和后端本人接口；不注入Token，不替换业务API响应。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const { chromium, expect } = createRequire(import.meta.url)(process.env.WMS_PLAYWRIGHT_MODULE);
const run = process.env.WMS_AUTH_RUN;
const fixture = JSON.parse(fs.readFileSync(path.join(run, 'fixture.json'), 'utf8'));
const origin = 'http://127.0.0.1:18180';
const browser = await chromium.launch({ headless: true });
const checks = [], errors = [];
const save = (name, value) => fs.writeFileSync(path.join(run, name), JSON.stringify(value, null, 2), { mode: 0o600 });
async function login(name, viewport = { width: 1440, height: 1000 }) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin + '/login');
  await page.getByRole('button', { name: '使用统一身份登录' }).click();
  await page.waitForURL(/18090/);
  await page.locator('#username').fill(fixture.users[name].name);
  await page.locator('#password').fill(fixture.users[name].password);
  await page.getByRole('button', { name: 'Sign In', exact: true }).click();
  await page.waitForURL(url => url.origin === origin && url.pathname !== '/callback', { timeout: 45000 });
  return { context, page };
}
async function shot(page, name) { await page.screenshot({ path: path.join(run, 'w04-' + name + '.png'), fullPage: true, animations: 'disabled' }); }
try {
  if (process.env.WMS_BROWSER_SCENARIO === 'enterprise-only') {
    const enterprise = await login('denied');
    const queries = [];
    enterprise.page.on('request', request => {
      const url = new URL(request.url());
      const apiPath = url.pathname.replace(/^\/(inbound|outbound|inventory|fulfillment)-api/, '');
      if (url.origin === origin && apiPath.startsWith('/api/wms/') && !apiPath.endsWith('/me/access')) queries.push(apiPath);
    });
    await enterprise.page.goto(origin + '/w/_/catalog');
    await expect(enterprise.page.getByRole('heading', { name: '商品 / 库位', exact: true })).toBeVisible({ timeout: 30000 });
    await expect(enterprise.page.getByText('SKU-NEAR', { exact: true }).first()).toBeVisible();
    await expect(enterprise.page.getByRole('button', { name: '创建商品', exact: true })).toHaveCount(0);
    assert(queries.includes('/api/wms/v1/skus'));
    assert(queries.every(query => query === '/api/wms/v1/skus'));
    await shot(enterprise.page, 'enterprise-only-catalog');
    await enterprise.page.goto(origin + '/w/WH-B/catalog');
    await expect(enterprise.page.getByText('当前仓或页面未获授权', { exact: true })).toBeVisible({ timeout: 30000 });
    await enterprise.page.goto(origin + '/w/_/catalog/locations/LOC-NEAR');
    await expect(enterprise.page.getByText('当前仓或页面未获授权', { exact: true })).toBeVisible({ timeout: 30000 });
    checks.push('仅企业SKU读取授权可打开无仓商品目录，真实SKU可见；不发送仓查询、显示写按钮或允许未授仓/库位深链');
    await enterprise.context.close();
  } else {
  const reader = await login('reader-a');
  await reader.page.goto(origin + '/w/WH-A/catalog');
  await expect(reader.page.getByRole('heading', { name: '商品 / 库位', exact: true })).toBeVisible({ timeout: 30000 });
  await expect(reader.page.getByText('SKU-NEAR', { exact: true }).first()).toBeVisible();
  await expect(reader.page.getByRole('button', { name: '创建商品', exact: true })).toHaveCount(0);
  await expect(reader.page.getByRole('button', { name: '创建库位', exact: true })).toHaveCount(0);
  if (fixture.phase === 'W04') await expect(reader.page.getByRole('menuitem', { name: '入库作业', exact: true })).toHaveCount(0);
  else await expect(reader.page.getByRole('menuitem', { name: /入库作业/ })).toBeVisible();
  await shot(reader.page, 'reader-a-desktop');
  checks.push('真实reader A PKCE登录，中央目录仅显示已授权页面，数据库SKU可见且写按钮隐藏');
  await reader.page.goto(origin + '/w/WH-B/catalog');
  await expect(reader.page.getByText('当前仓或页面未获授权', { exact: true })).toBeVisible({ timeout: 30000 });
  await expect(reader.page.getByRole('heading', { name: '商品 / 库位', exact: true })).toHaveCount(0);
  await shot(reader.page, 'cross-warehouse-deep-link');
  checks.push('未授权仓深链不自动跳回或渲染旧数据');
  await reader.page.setViewportSize({ width: 390, height: 844 });
  await reader.page.goto(origin + '/pda/WH-A/receive');
  await expect(reader.page.getByText('当前仓或作业未获授权', { exact: true })).toBeVisible({ timeout: 30000 });
  await expect(reader.page.getByRole('button', { name: '回车提交', exact: true })).toHaveCount(0);
  await shot(reader.page, 'pda-reader-denied');
  checks.push('390px PDA未授收货能力时隐藏作业按钮并拒绝深链');
  await reader.context.close();
  const operator = await login('operator');
  await operator.page.goto(origin + '/w/WH-B/catalog');
  await expect(operator.page.getByRole('heading', { name: '商品 / 库位', exact: true })).toBeVisible({ timeout: 30000 });
  if (fixture.phase === 'W06') await expect(operator.page.getByRole('button', { name: '创建商品', exact: true })).toBeVisible();
  else await expect(operator.page.getByRole('button', { name: '创建商品', exact: true })).toHaveCount(0);
  await expect(operator.page.getByRole('button', { name: '创建库位', exact: true })).toHaveCount(0);
  await shot(operator.page, 'operator-b-narrow-write');
  checks.push(fixture.phase === 'W06' ? '同一旧masterdata.write区分企业商品写与A仓写，B仓库位写隐藏' : 'operator可读取B仓，未授写能力时不因读权显示写按钮');
  await operator.context.close();
  const denied = await login('denied');
  await expect(denied.page.getByText('当前仓或页面未获授权', { exact: true })).toBeVisible({ timeout: 30000 });
  await expect(denied.page.getByRole('menuitem')).toHaveCount(0);
  await shot(denied.page, 'no-grant');
  checks.push('未授权真实成员没有菜单、仓选择或业务内容');
  await denied.context.close();
  }
  assert.deepEqual(errors, []);
  save(process.env.WMS_BROWSER_SCENARIO === 'enterprise-only' ? 'w04-enterprise-browser-result.json' : 'w04-browser-result.json', { result: 'PASS', checks, errors, token_injection: false, mocked_api: false });
  console.log('PASS: ' + checks.length + '个真实浏览器PKCE/菜单/深链检查。');
} catch (error) {
  save('w04-browser-failure-' + Date.now() + '.json', { message: String(error), errors, checks });
  throw error;
} finally { await browser.close(); }
