/** W05真实PKCE与PDA写入；只使用当前工具专属MySQL单据，不注入Token或拦截替换API。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const { chromium, expect } = createRequire(import.meta.url)(process.env.WMS_PLAYWRIGHT_MODULE);
const run = process.env.WMS_AUTH_RUN;
const fixture = JSON.parse(fs.readFileSync(path.join(run, 'fixture.json'), 'utf8'));
const data = JSON.parse(fs.readFileSync(path.join(run, 'action-data.json'), 'utf8'));
assert(['W05', 'W06'].includes(fixture.phase));
const origin = 'http://127.0.0.1:18180';
const browser = await chromium.launch({ headless: true });
const checks = [], errors = [];
const save = (name, value) => fs.writeFileSync(path.join(run, name), JSON.stringify(value, null, 2), { mode: 0o600 });
async function login(name, viewport) {
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
try {
  const reader = await login('reader-a', { width: 1440, height: 1000 });
  await reader.page.goto(origin + '/w/WH-A/inbound');
  await expect(reader.page.getByRole('heading', { name: '入库工作台', exact: true })).toBeVisible({ timeout: 30000 });
  await expect(reader.page.getByText(data.inbound_order_id, { exact: true }).first()).toBeVisible();
  await expect(reader.page.getByRole('button', { name: '创建入库单', exact: true })).toHaveCount(0);
  await reader.page.screenshot({ path: path.join(run, 'w05-reader-inbound.png'), fullPage: true });
  checks.push('只读成员看到真实入库单且没有创建动作');
  await reader.page.goto(origin + '/pda/WH-A/receive');
  await expect(reader.page.getByText('当前仓或作业未获授权', { exact: true })).toBeVisible({ timeout: 30000 });
  await expect(reader.page.getByRole('button', { name: '回车提交', exact: true })).toHaveCount(0);
  checks.push('入库读取能力不能打开PDA收货写作业');
  await reader.context.close();
  const operator = await login('operator', { width: 390, height: 844 });
  await operator.page.goto(origin + '/pda/WH-A/receive');
  await expect(operator.page.getByRole('heading', { name: 'PDA 收货', exact: true })).toBeVisible({ timeout: 30000 });
  await operator.page.getByLabel('入库单', { exact: true }).fill(data.inbound_order_id);
  await operator.page.getByLabel('收货库位', { exact: true }).fill('WH-A-STO');
  await operator.page.getByLabel('货品批次', { exact: true }).fill('NO_LOT');
  await operator.page.getByLabel('数量', { exact: true }).fill('1');
  await operator.page.getByLabel('行/扫码', { exact: true }).fill(data.inbound_line_id);
  const response = operator.page.waitForResponse(value => value.request().method() === 'POST'
    && value.url().includes('/inbound-orders/' + data.inbound_order_id + '/receipts'));
  await operator.page.getByLabel('行/扫码', { exact: true }).press('Enter');
  const accepted = await response;
  assert.equal(accepted.status(), 202);
  const result = await accepted.json();
  assert.equal(result.stockSyncStatus, 'PENDING');
  await expect(operator.page.getByText('扫码已受理，库存待同步', { exact: true })).toBeVisible();
  await expect(operator.page.getByText('收货已记录实物，库存同步待查询', { exact: true })).toBeVisible();
  await operator.page.screenshot({ path: path.join(run, 'w05-pda-receive-accepted.png'), fullPage: true });
  save('pda-command.json', { result, request: JSON.parse(accepted.request().postData()), idempotency_key: accepted.request().headers()['idempotency-key'] });
  checks.push('390px真实扫码回车收货返回202并明确库存待同步');
  await operator.page.goto(origin + '/pda/WH-B/receive');
  await expect(operator.page.getByText('当前仓或作业未获授权', { exact: true })).toBeVisible({ timeout: 30000 });
  await expect(operator.page.getByRole('button', { name: '回车提交', exact: true })).toHaveCount(0);
  await operator.page.screenshot({ path: path.join(run, 'w05-pda-foreign-write-denied.png'), fullPage: true });
  checks.push('B仓读权不能显示或执行B仓收货动作');
  assert.deepEqual(errors, []);
  save('actions-browser-result.json', { result: 'PASS', checks, errors, real_pkce: true, token_injection: false, mocked_api: false });
  console.log('PASS: ' + checks.length + '个真实入库/PDA浏览器检查。');
  await operator.context.close();
} catch (error) { save('actions-browser-failure.json', { message: String(error), checks, errors }); throw error; }
finally { await browser.close(); }
