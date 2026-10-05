/** W07实际PKCE与当前容器页面：不注入Token、不替换API，不产生业务写入。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const { chromium, expect } = createRequire(import.meta.url)(process.env.WMS_PLAYWRIGHT_MODULE);
const fixture = JSON.parse(fs.readFileSync(path.join(process.env.WMS_AUTH_RUN, 'fixture.json'), 'utf8'));
const output = process.env.WMS_BROWSER_OUTPUT;
assert(output && fs.statSync(output).isDirectory());
const origin = 'http://127.0.0.1:18180';
const browser = await chromium.launch({ headless: true });
const checks = [], errors = [];
const responses = [];
const save = (name, value) => fs.writeFileSync(path.join(output, name), JSON.stringify(value, null, 2), { mode: 0o600 });
async function login(actor, viewport = { width: 1440, height: 1000 }) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
  page.on('response', response => {
    if (response.url().includes('/api/wms/')) responses.push({ actor, path: new URL(response.url()).pathname, status: response.status() });
  });
  await page.goto(origin + '/login');
  await page.getByRole('button', { name: '使用统一身份登录' }).click();
  await page.waitForURL(/18090/);
  await page.locator('#username').fill(fixture.users[actor].name);
  await page.locator('#password').fill(fixture.users[actor].password);
  await page.getByRole('button', { name: 'Sign In', exact: true }).click();
  await page.waitForURL(url => url.origin === origin && url.pathname !== '/callback', { timeout: 45000 });
  const issued = await page.evaluate(() => {
    const key = Object.keys(sessionStorage).find(value => value.startsWith('oidc.user:'));
    return key ? JSON.parse(sessionStorage.getItem(key)).access_token : undefined;
  });
  assert(issued); save('browser-issued-' + actor + '-token.json', { access_token: issued });
  await settled(page);
  return { context, page };
}
async function settled(page) {
  // 登录与导航都等待实际本人请求结束；立即跳页会额外制造并发，本接口依法失败关闭。
  await expect(page.getByRole('button', { name: '刷新权限', exact: true })).toBeVisible({ timeout: 30000 });
  await expect(page.getByText('正在读取本人权限', { exact: true })).toHaveCount(0, { timeout: 30000 });
  const retry = page.getByRole('button', { name: '重新读取权限', exact: true });
  if (await retry.isVisible()) {
    await expect(page.getByRole('menuitem')).toHaveCount(0);
    await expect(page.getByText('权限暂不可用，请重试', { exact: true })).toBeVisible();
    const response = page.waitForResponse(value => value.url().includes('/api/wms/v1/me/access'));
    await retry.click(); assert.equal((await response).status(), 200);
    await expect(retry).toHaveCount(0, { timeout: 30000 });
    checks.push('实际暂不可用时旧菜单清空，用户明确重试恢复到真实权限');
  }
}
async function catalog(page, warehouse) {
  // 真实用户通过仓选择和菜单进入页面；整页重载会额外重建壳层并制造无关提示请求。
  if (new URL(page.url()).pathname.split('/')[2] !== warehouse) {
    const picker = page.getByRole('combobox').first();
    await expect(picker).toBeEnabled({ timeout: 30000 }); await picker.click();
    await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: warehouse }).click();
    await page.waitForURL(url => url.pathname.split('/')[2] === warehouse);
    await settled(page);
  }
  await page.getByRole('menuitem', { name: /商品.*库位/ }).click();
  await page.waitForURL(origin + '/w/' + warehouse + '/catalog');
  await settled(page);
  await expect(page.getByRole('heading', { name: '商品 / 库位', exact: true })).toBeVisible({ timeout: 30000 });
}
async function shot(page, name) {
  await expect(page.locator('.ant-skeleton:visible')).toHaveCount(0, { timeout: 30000 });
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: path.join(output, name + '.png'), fullPage: false });
}
try {
  const operator = await login('operator');
  await catalog(operator.page, 'WH-A');
  await expect(operator.page.getByRole('button', { name: '创建库位', exact: true })).toBeVisible();
  await expect(operator.page.getByRole('menuitem', { name: /盘点/ })).toBeVisible();
  await shot(operator.page, 'operator-a');
  checks.push('当前镜像真实PKCE操作员A仓菜单和独立写入口可见');
  await catalog(operator.page, 'WH-B');
  await expect(operator.page.getByRole('button', { name: '创建库位', exact: true })).toHaveCount(0);
  await shot(operator.page, 'operator-b-readonly');
  checks.push('同一操作员B仓仅有仓级读权限，库位写入口隐藏，企业商品权限单独保留');
  await operator.context.close();
  const reader = await login('reader-b');
  await catalog(reader.page, 'WH-B');
  await expect(reader.page.getByRole('button', { name: '创建库位', exact: true })).toHaveCount(0);
  await shot(reader.page, 'reader-b');
  checks.push('B仓读者真实读取本人目录，不能获得写入口');
  await reader.page.goto(origin + '/w/WH-A/catalog');
  await settled(reader.page);
  await expect(reader.page.getByText('当前仓或页面未获授权', { exact: true })).toBeVisible({ timeout: 30000 });
  await expect(reader.page.getByRole('heading', { name: '商品 / 库位', exact: true })).toHaveCount(0);
  checks.push('B仓读者A仓深链拒绝且不显示旧业务内容');
  await reader.page.setViewportSize({ width: 390, height: 844 });
  await reader.page.goto(origin + '/pda/WH-B/receive');
  const pdaRetry = reader.page.getByRole('button', { name: '重新读取权限', exact: true });
  // 等待实际请求的终态；goto刚返回时，尚未挂载的加载提示不能被误认为已完成。
  await expect(reader.page.getByText('当前仓或作业未获授权', { exact: true }).or(pdaRetry)).toBeVisible({ timeout: 30000 });
  if (await pdaRetry.isVisible()) {
    await expect(reader.page.getByText('权限暂不可用，请重试', { exact: true })).toBeVisible();
    await expect(reader.page.getByRole('button', { name: '回车提交', exact: true })).toHaveCount(0);
    const response = reader.page.waitForResponse(value => value.url().includes('/api/wms/v1/me/access'));
    await pdaRetry.click(); assert.equal((await response).status(), 200);
    checks.push('PDA实际权限暂不可用时隐藏提交，用户明确重试后仍按岗位拒绝');
  }
  await expect(reader.page.getByText('当前仓或作业未获授权', { exact: true })).toBeVisible({ timeout: 30000 });
  await expect(reader.page.getByRole('button', { name: '回车提交', exact: true })).toHaveCount(0);
  await shot(reader.page, 'reader-pda-denied');
  checks.push('390px PDA只读岗位拒绝收货并隐藏提交');
  await reader.context.close();
  const denied = await login('denied');
  await expect(denied.page.getByText('当前仓或页面未获授权', { exact: true })).toBeVisible({ timeout: 30000 });
  await expect(denied.page.getByRole('menuitem')).toHaveCount(0);
  await shot(denied.page, 'no-grant');
  checks.push('无授权成员真实登录后没有菜单和业务内容');
  await denied.context.close();
  assert.deepEqual(errors, []);
  save('browser-result.json', { result: 'PASS', checks, errors, responses, mocked_api: false, token_injection: false, business_writes: 0 });
  console.log('PASS: ' + checks.length + '个当前运行页面真实PKCE检查。');
} catch (error) {
  save('browser-failure-' + Date.now() + '.json', { message: String(error), checks, errors, responses });
  throw error;
} finally { await browser.close(); }
