import { ownerPassword } from './offline-fixtures.mjs';
// Called by ui-screenshots.mjs on the OFFLINE demo server only. Tests the
// actual browser flow for private Owner signup, referral deletion and uploads.
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import path from 'node:path';
import { zipSync } from 'fflate';

export async function channelBrowserTests(browser, base, out, { kennyReferral }) {
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
  const publish = async (page, name, bytes, version, channel = null) => {
    await page.goto(`${base}/lib${channel ? '?channel=' + channel : ''}`);
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
    await login(owner, 'Anoy', ownerPassword);
    await owner.goto(`${base}/admin/create-referral`);
    assert.deepEqual(await owner.locator('#accLevel option').evaluateAll(options => options.map(o => o.value).sort()), ['2', '3']);
    assert.doesNotMatch(await owner.locator('body').textContent(), /Anoy can invite|Choose Owner|Owner referrals expire/);
    await shot(owner, 'referral_form');
    await owner.selectOption('#accLevel', '3');
    await owner.fill('#set_saldo', '0');
    await Promise.all([owner.waitForNavigation(), owner.locator('form[action="/admin/create-referral"] button[type=submit]').click()]);
    const message = await owner.locator('[data-flash=success]').textContent();
    const deletedCode = /Referral code created: ([A-Z2-9]{7})/.exec(message)?.[1];
    assert.ok(deletedCode);
    await owner.getByRole('button', { name: `Delete referral ${deletedCode}`, exact: true }).click();
    await owner.locator('.modal-backdrop.show').waitFor();
    await shot(owner, 'referral_delete_confirm');
    await Promise.all([owner.waitForNavigation(), owner.locator('.modal .btn-danger').click()]);
    assert.equal(await owner.getByRole('button', { name: `Delete referral ${deletedCode}`, exact: true }).count(), 0);
    await second.goto(`${base}/register`);
    for (const [field, value] of Object.entries({ username: 'DeletedInvite', fullname: 'Fixture User', email: 'deleted@example.com',
      password: 'OpaqueSignal93', password2: 'OpaqueSignal93', referral: deletedCode })) {
      await second.fill(`[name=${field}]`, value);
    }
    await Promise.all([second.waitForNavigation(), second.click('button[type=submit]')]);
    assert.ok(new URL(second.url()).pathname === '/register', 'deleted referral cannot register');

    await second.goto(`${base}/register`);
    for (const [field, value] of Object.entries({ username: 'KennyPvt', fullname: 'Kenny Owner', email: 'kenny@example.com',
      password: 'OpaqueSignal93', password2: 'OpaqueSignal93', referral: kennyReferral })) {
      await second.fill(`[name=${field}]`, value);
    }
    await Promise.all([second.waitForURL('**/login'), second.click('button[type=submit]')]);
    await login(second, 'KennyPvt', 'OpaqueSignal93');
    await second.goto(`${base}/admin/create-referral`);
    assert.equal(await second.locator('#accLevel option[value="1"]').count(), 0);
    assert.ok(await second.evaluate(() => document.documentElement.scrollWidth - window.innerWidth) <= 1, 'referral controls fit a phone');
    await shot(second, 'phone_referrals');
    await second.goto(`${base}/lib`);
    assert.equal(await second.locator('[data-release-channel]').getAttribute('data-release-channel'), 'kenny');
    assert.match(await second.locator('.page-sub').textContent(), /KennyPvt (?:library|APK updates)/);
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
    assert.equal((await secondCtx.request.post(`${base}/app-update`, { data: { action: 'remove', channel: 'anoy' } })).status(), 403);
    await owner.goto(`${base}/lib?channel=kenny`);
    assert.equal(await owner.locator('[data-release-channel]').getAttribute('data-release-channel'), 'kenny');
    assert.equal(await owner.locator('[aria-label="Manage release channel"] a').count(), 2);
    assert.match(await owner.locator('.who-sub').textContent(), /Real Owner/);
    await shot(owner, 'real_owner_kenny_channel');
    await publish(owner, 'real-owner-fixture.so', elf(768), '0.2', 'kenny');
    assert.equal((await (await ownerCtx.request.get(`${base}/api/lib/manifest`)).json()).version, '1.8');
    assert.equal((await (await ownerCtx.request.get(`${base}/api/lib/manifest?channel=kenny`)).json()).version, '0.2');
    assert.match(await owner.locator('.list-row').filter({ hasText: 'Published by' }).textContent(), /Anoy/);
    await owner.goto(`${base}/app-update?channel=kenny`);
    const latestResponse = owner.waitForResponse(response => new URL(response.url()).pathname === '/app-update/latest-build');
    await owner.click('#app-latest');
    assert.equal(new URL((await latestResponse).url()).searchParams.get('channel'), 'kenny');
    await owner.locator('#app-latest:not([disabled])').waitFor({ state: 'visible' });
    assert.match(await owner.inputValue('#app-apk-url'), /KennyPvt-loader\.apk/);
    await owner.fill('#app-changelog', 'Offline browser authority check');
    const appPublished = owner.waitForResponse(response => new URL(response.url()).pathname === '/app-update' && response.request().method() === 'POST');
    await owner.click('#app-submit');
    const publishedApp = await appPublished;
    assert.equal(publishedApp.status(), 200); assert.equal((await publishedApp.json()).channel, 'kenny');
    await owner.locator('#app-remove').waitFor({ state: 'visible' });
    assert.equal((await (await ownerCtx.request.get(`${base}/api/app/update`)).json()).available, false, 'Anoy APK selection remains unchanged');
    const removedResponse = owner.waitForResponse(response => new URL(response.url()).pathname === '/app-update' && response.request().method() === 'POST');
    await owner.click('#app-remove');
    await owner.locator('.modal-backdrop.show').waitFor();
    await owner.locator('.modal .btn-danger').click();
    assert.equal((await removedResponse).status(), 200);
    await owner.locator('#app-remove').waitFor({ state: 'detached' });
    assert.equal((await (await ownerCtx.request.get(`${base}/api/app/update?channel=kenny`)).json()).available, false);
    await second.goto(`${base}/app-update`);
    assert.match(await second.locator('.page-sub').textContent(), /KennyPvt (?:library|APK updates)/);
    await shot(second, 'kenny_apk_channel');
    const overflow = await second.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert.ok(overflow <= 1, 'Kenny release UI fits a phone');
    assert.deepEqual(errors, [], 'no browser script errors in invitation/upload flow');
    console.log('PASS browser: clean referral UI, confirmed deletion, private one-time Owner signup, 10 MiB .so + ZIP, Real Owner cross-channel library/APK controls, Kenny isolation');
  } finally { await ownerCtx.close(); await secondCtx.close(); }
}
