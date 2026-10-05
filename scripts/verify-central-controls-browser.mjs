/** W06真实PKCE：审批/应用与调拨源/目的仓入口；只读取当前专属fixture，不注入Token或替换API。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const { chromium, expect } = createRequire(import.meta.url)(process.env.WMS_PLAYWRIGHT_MODULE);
const run = process.env.WMS_AUTH_RUN;
const fixture = JSON.parse(fs.readFileSync(path.join(run, 'fixture.json'), 'utf8'));
const data = JSON.parse(fs.readFileSync(path.join(run, 'controls-data.json'), 'utf8'));
assert.equal(fixture.phase, 'W06');
const origin = 'http://127.0.0.1:18180';
const browser = await chromium.launch({ headless: true });
const checks = [], errors = [];
const save = (name, value) => fs.writeFileSync(path.join(run, name), JSON.stringify(value, null, 2), { mode: 0o600 });
async function login(actor, viewport = { width: 1440, height: 1000 }) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin + '/login');
  await page.getByRole('button', { name: '使用统一身份登录' }).click();
  await page.waitForURL(/18090/);
  await page.locator('#username').fill(fixture.users[actor].name);
  await page.locator('#password').fill(fixture.users[actor].password);
  await page.getByRole('button', { name: 'Sign In', exact: true }).click();
  await page.waitForURL(url => url.origin === origin && url.pathname !== '/callback', { timeout: 45000 });
  return { context, page };
}
async function transfer(page, wh) {
  await page.goto(`${origin}/w/${wh}/transfers/${data.transfer_id}`);
  await expect(page.getByRole('heading', { name: '调拨单 ' + data.transfer_id, exact: true })).toBeVisible({ timeout: 30000 });
  await expect(page.getByText('源仓 WH-A → 目的仓 WH-B。在源仓发出，在目的仓申请额度并接收。', { exact: true })).toBeVisible();
}
async function open(page) {
  await page.screenshot({ path: path.join(run, 'w06-before-command-' + Date.now() + '.png'), fullPage: true });
  save('controls-current-page.json', { url: page.url(), text: await page.locator('body').innerText() });
  await page.getByRole('button', { name: '提交命令', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
}
async function shot(page, name) {
  const dialog = page.getByRole('dialog');
  if (await dialog.count()) {
    await expect(dialog).toHaveCSS('opacity', '1');
    await expect(dialog).toHaveCSS('transform', 'none');
    await expect.poll(async () => {
      const box = await dialog.boundingBox();
      return Boolean(box && box.y >= 0 && box.y + box.height <= page.viewportSize().height);
    }).toBe(true);
  }
  await page.screenshot({ path: path.join(run, 'w06-' + name + '.png'), fullPage: false });
}
try {
  const operator = await login('operator');
  await transfer(operator.page, 'WH-A'); await open(operator.page);
  await expect(operator.page.getByRole('tab', { name: '源仓发出', exact: true })).toBeVisible();
  await expect(operator.page.getByRole('tab', { name: '目的接收授权', exact: true })).toHaveCount(0);
  await expect(operator.page.getByRole('tab', { name: '目的仓接收', exact: true })).toHaveCount(0);
  await shot(operator.page, 'source-commands');
  checks.push('源仓已授写能力仅显示源动作，A仓接收授权不能放大为目的B仓按钮');
  await operator.page.keyboard.press('Escape');
  await expect(operator.page.getByRole('dialog')).toHaveCount(0);
  await expect(operator.page.getByRole('button', { name: '提交命令', exact: true })).toBeFocused();
  checks.push('命令弹层键盘关闭并恢复入口焦点');
  await transfer(operator.page, 'WH-B');
  await expect(operator.page.getByRole('button', { name: '提交命令', exact: true })).toHaveCount(0);
  await shot(operator.page, 'destination-read-only');
  checks.push('B仓仅有调拨读权时真实详情可读且所有写命令入口隐藏');
  await operator.context.close();
  const approver = await login('denied');
  await approver.page.goto(`${origin}/w/WH-A/counts/${data.plan_id}`);
  await expect(approver.page.getByRole('heading', { name: '盘点计划 ' + data.plan_id, exact: true })).toBeVisible({ timeout: 30000 });
  await open(approver.page);
  await expect(approver.page.getByRole('tab')).toHaveCount(1);
  await expect(approver.page.getByRole('tab', { name: '审批', exact: true })).toBeVisible();
  await expect(approver.page.getByRole('button', { name: '批准调整', exact: true })).toBeVisible();
  await shot(approver.page, 'approval-only');
  checks.push('真实独立审批岗位只有审批命令，没有冻结/点数/应用入口');
  await approver.page.keyboard.press('Escape');
  await approver.page.setViewportSize({ width: 390, height: 844 });
  await transfer(approver.page, 'WH-B'); await open(approver.page);
  await expect(approver.page.getByRole('tab')).toHaveCount(3);
  await expect(approver.page.getByRole('tab', { name: '目的接收授权', exact: true })).toBeVisible();
  await expect(approver.page.getByRole('tab', { name: '源仓发出', exact: true })).toHaveCount(0);
  await approver.page.getByRole('tab', { name: '目的仓接收', exact: true }).click();
  await expect(approver.page.getByRole('textbox', { name: /^目的仓/ })).toHaveValue('WH-B');
  await expect(approver.page.getByRole('textbox', { name: /^目的仓/ })).toHaveAttribute('readonly', '');
  const box = await approver.page.getByRole('dialog').boundingBox();
  assert(box && box.x >= 0 && box.x + box.width <= 390);
  await shot(approver.page, 'destination-mobile');
  checks.push('390px目的接收岗位只有目的动作，目的仓来自实际单据且不可改为其他仓');
  await approver.context.close();
  assert.deepEqual(errors, []);
  save('controls-browser-result.json', { result: 'PASS', checks, errors, real_pkce: true, token_injection: false, mocked_api: false });
  console.log('PASS: ' + checks.length + '个真实审批/调拨浏览器检查。');
} catch (error) { save('controls-browser-failure-' + Date.now() + '.json', { message: String(error), checks, errors }); throw error; }
finally { await browser.close(); }
