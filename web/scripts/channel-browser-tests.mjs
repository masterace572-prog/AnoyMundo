// Called by ui-screenshots.mjs on the OFFLINE demo server only. Tests the
// actual browser XHR/DOM flow for the second-owner invite and binary upload.
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import path from 'node:path';
import { zipSync } from 'fflate';

export async function channelBrowserTests(browser, base, out) {
  const ownerCtx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
  const secondCtx = await browser.newContext({ viewport: { width: 390, height: 844 }, colorScheme: 'dark' });
  const owner = await ownerCtx.newPage(), second = await secondCtx.newPage();
  const errors = [];
  for (const page of [owner, second]) page.on('pageerror', error => errors.push(error.message));
  const login = async (page, username, password) => {
    await page.goto(`${base}/login`);
    await page.fill('#username', username); await page.fill('#password', password);
    await Promise.all([page.waitForURL('**/dashboard'), page.click('button[type=submit]')]);
  };
  const shot = (page, name) => page.screenshot({ path: path.join(out, `channels_${name}.png`), fullPage: true });
  const elf = (length) => {
    const bytes = Buffer.alloc(length);
    bytes.set([0x7f, 0x45, 0x4c, 0x46, 2, 1]); bytes[18] = 183;
    return bytes;
  };
  const publish = async (page, name, bytes, version) => {
    await page.goto(`${base}/lib`);
    await page.fill('#lib-version', version);
    await page.setInputFiles('#lib-file', { name, mimeType: 'application/octet-stream', buffer: bytes });
    const published = page.waitForResponse(response => {
      if (!response.url().endsWith('/lib') || response.request().method() !== 'POST') return false;
      try { return response.request().postDataJSON().action === 'upload'; } catch { return false; }
    }, { timeout: 30000 });
    await page.click('#lib-submit');
    const response = await published;
    assert.equal(response.status(), 200);
    const data = await response.json();
    assert.equal(data.ok, true); assert.equal(data.file_name, 'libbgmi.so');
    // Use a selector rather than waitForFunction's in-page eval: the panel
    // intentionally disallows unsafe-eval in its Content Security Policy.
    await page.locator('.hero-value').filter({ hasText: version }).waitFor({ state: 'visible' });
    return data;
  };
  try {
    await login(owner, 'Anoy', 'Anoy1764');
    await owner.goto(`${base}/admin/create-referral`);
    await owner.selectOption('#accLevel', '1');
    assert.equal(await owner.locator('#accExpire').isDisabled(), true);
    assert.equal(await owner.locator('#set_saldo').isDisabled(), true);
    await shot(owner, 'owner_invite_form');
    await Promise.all([owner.waitForNavigation(), owner.click('button[type=submit]')]);
    const message = await owner.locator('[data-flash=success]').textContent();
    const code = /KennyPvt owner referral: ([A-Z2-9]{7})/.exec(message)?.[1];
    assert.ok(code);

    await second.goto(`${base}/register`);
    for (const [field, value] of Object.entries({ username: 'KennyPvt', fullname: 'Kenny Owner', email: 'kenny@example.com',
      password: 'OpaqueSignal93', password2: 'OpaqueSignal93', referral: code })) {
      await second.fill(`[name=${field}]`, value);
    }
    await Promise.all([second.waitForURL('**/login'), second.click('button[type=submit]')]);
    await login(second, 'KennyPvt', 'OpaqueSignal93');
    await second.goto(`${base}/lib`);
    assert.equal(await second.locator('[data-release-channel]').getAttribute('data-release-channel'), 'kenny');
    assert.match(await second.locator('.page-sub').textContent(), /KennyPvt only/);
    await shot(second, 'kenny_empty');

    // Browser uploads 10 MiB to a signed binary PUT, not a >4.5 MB panel JSON.
    await publish(owner, 'anything-i-like.so', elf(10 * 1024 * 1024), '1.8');
    await shot(owner, 'anoy_10mb_published');
    const randomElf = crypto.randomBytes(4 * 1024 * 1024); randomElf.set(elf(64));
    await publish(second, 'my-release.zip', Buffer.from(zipSync({ 'nested/renamed.so': randomElf })), '0.1');
    await shot(second, 'kenny_zip_published');

    const a = await (await ownerCtx.request.get(`${base}/api/lib/manifest`)).json();
    const k = await (await secondCtx.request.get(`${base}/api/lib/manifest?channel=kenny`)).json();
    assert.equal(a.version, '1.8'); assert.equal(k.version, '0.1');
    assert.notEqual(a.sha256, k.sha256);
    assert.equal((await secondCtx.request.post(`${base}/lib`, { data: { action: 'reset', channel: 'anoy' } })).status(), 403);
    assert.equal((await ownerCtx.request.post(`${base}/app-update`, { data: { action: 'remove', channel: 'kenny' } })).status(), 403);
    await second.goto(`${base}/app-update`);
    assert.match(await second.locator('.page-sub').textContent(), /KennyPvt only/);
    await shot(second, 'kenny_apk_channel');
    const overflow = await second.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert.ok(overflow <= 1, 'Kenny release UI fits a phone');
    assert.deepEqual(errors, [], 'no browser script errors in invitation/upload flow');
    console.log('PASS browser: one-time Owner signup, true 10 MiB .so + >3 MiB ZIP upload, canonical name, channel isolation');
  } finally { await ownerCtx.close(); await secondCtx.close(); }
}
