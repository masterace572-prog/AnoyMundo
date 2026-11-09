// Captures the panel at desktop and phone sizes for visual review.
// Runs in CI (.github/workflows/web-ui-qa.yml): starts the offline dev server
// (memory DB), signs in as the Owner and saves PNGs to docs/ui-redesign/web.
//   npm i --no-save playwright && npx playwright install chromium
//   node scripts/ui-screenshots.mjs
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const out = path.resolve(root, '..', 'docs', 'ui-redesign', 'web');
mkdirSync(out, { recursive: true });

const PORT = 3177;
const BASE = `http://127.0.0.1:${PORT}`;
const { chromium } = await import('playwright');

const server = spawn(process.execPath, ['scripts/dev-server.mjs'], {
  cwd: root, env: { ...process.env, PORT: String(PORT), HOST: '127.0.0.1' }, stdio: 'inherit',
});
const stop = () => { try { server.kill('SIGTERM'); } catch { /* gone */ } };
process.on('exit', stop);

async function waitForServer() {
  for (let i = 0; i < 60; i += 1) {
    try { const r = await fetch(`${BASE}/health`); if (r.ok) return; } catch { /* not yet */ }
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error('dev server did not start');
}

const sizes = [
  { name: 'desktop', viewport: { width: 1440, height: 900 }, scale: 1 },
  { name: 'mobile', viewport: { width: 390, height: 844 }, scale: 2, mobile: true },
];

const pages = [
  ['dashboard', '/dashboard'],
  ['keys', '/keys'],
  ['generate', '/keys/generate'],
  ['server', '/server'],
  ['users', '/admin/manage-users'],
  ['referral', '/admin/create-referral'],
  ['lib', '/lib'],
  ['app_update', '/app-update'],
  ['api_docs', '/api-docs'],
  ['api_keys', '/api-keys'],
  ['settings', '/settings'],
  ['docs', '/docs'],
  ['not_found', '/does-not-exist'],
];

let failures = 0;
const report = [];
async function shot(page, file, opts = {}) {
  try {
    await page.waitForTimeout(350);
    await page.screenshot({ path: path.join(out, `${file}.png`), fullPage: opts.fullPage ?? true });
    console.log('saved', file);
  } catch (e) { failures += 1; report.push(`FAILED ${file}: ${e.message}`); console.error('FAILED', file, e.message); }
}

try {
  await waitForServer();
  const browser = await chromium.launch();
  for (const size of sizes) {
    const ctx = await browser.newContext({
      viewport: size.viewport, deviceScaleFactor: size.scale, isMobile: !!size.mobile, hasTouch: !!size.mobile,
      colorScheme: 'dark', reducedMotion: 'reduce',
    });
    await ctx.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: BASE }).catch(() => {});
    const page = await ctx.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    // Expected 4xx pages (404, wrong password) log "Failed to load resource"; ignore those.
    page.on('console', (m) => {
      if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push(`${page.url()} ${m.text()}`);
    });

    await page.goto(`${BASE}/login`);
    await shot(page, `${size.name}_login`);
    await page.fill('#password', 'hunter22');
    await page.click('[data-toggle-password="password"]');
    await shot(page, `${size.name}_login_password_shown`, { fullPage: false });

    // Wrong password -> inline error.
    await page.fill('#username', 'Anoy');
    await page.fill('#password', 'wrongpass');
    await Promise.all([page.waitForNavigation(), page.click('button[type=submit]')]);
    await shot(page, `${size.name}_login_error`);

    await page.goto(`${BASE}/register`);
    await shot(page, `${size.name}_register`);

    await page.goto(`${BASE}/login`);
    await page.fill('#username', 'Anoy');
    await page.fill('#password', 'Anoy1764');
    await Promise.all([page.waitForNavigation(), page.click('button[type=submit]')]);

    // A batch of keys so the lists are not empty.
    if (size.name === 'desktop') {
      await page.goto(`${BASE}/keys/generate`);
      await page.click('label.choice:has(input[value="168"])');
      await page.fill('#count', '6');
      await Promise.all([page.waitForNavigation(), page.click('#generate-submit')]);
      await shot(page, `${size.name}_generate_result`);
    }

    for (const [name, url] of pages) {
      await page.goto(`${BASE}${url}`);
      if (name === 'keys') await page.waitForSelector('#keys-body tr:not(.skeleton)', { timeout: 5000 }).catch(() => {});
      await shot(page, `${size.name}_${name}`);
    }

    // Custom key mode on the generate form.
    await page.goto(`${BASE}/keys/generate`);
    await page.click('.segmented label:nth-child(2)');
    await page.fill('#custom_key', 'VIP01');
    await shot(page, `${size.name}_generate_custom`);

    const problem0 = (msg) => { failures += 1; report.push(`[${size.name}] ${msg}`); console.error('CHECK', size.name, msg); };
    if (!size.mobile) {
      // Confirm dialog from the keys table.
      await page.goto(`${BASE}/keys`);
      await page.waitForSelector('#keys-body .icon-btn.danger', { timeout: 5000 }).catch(() => {});
      const del = await page.$('#keys-body .icon-btn.danger');
      if (del) { await del.click(); await page.waitForSelector('.modal-backdrop.show').catch(() => {}); await shot(page, `${size.name}_confirm`, { fullPage: false }); }

      // Copy for customer: the button itself must briefly say "Copied", then
      // return to its icon (the toast alone is easy to miss).
      await page.goto(`${BASE}/keys`);
      await page.waitForSelector('#keys-body tr:not(.skeleton)', { timeout: 5000 }).catch(() => {});
      const share = await page.$('#keys-body button[aria-label="Copy for customer"]');
      if (!share) {
        problem0('Copy for customer button not found');
      } else {
        await share.click();
        await page.waitForSelector('#keys-body button.labelled .copied-label', { timeout: 3000 }).catch(() => {});
        const during = await share.evaluate((b) => ({ text: b.textContent.trim(), cls: b.className }));
        if (during.text !== 'Copied' || !/\blabelled\b/.test(during.cls)) problem0(`Copy for customer: no visible "Copied" on the button (${JSON.stringify(during)})`);
        await shot(page, `${size.name}_copy_customer`, { fullPage: false });
        await page.waitForTimeout(2300);
        const after = await share.evaluate((b) => ({ text: b.textContent.trim(), cls: b.className, svg: !!b.querySelector('svg') }));
        if (after.text !== '' || /\blabelled\b/.test(after.cls) || !after.svg) problem0(`Copy for customer: button did not return to its icon (${JSON.stringify(after)})`);
      }
    } else {
      // 2.3.4 phone keys list: 72 px rows, "⋯" sheet, select mode.
      await page.goto(`${BASE}/keys`);
      await page.waitForSelector('#keys-list .key-row:not(.skeleton)', { timeout: 5000 }).catch(() => {});
      const rows = await page.$$eval('#keys-list .key-row:not(.skeleton)', (r) => r.map((x) => Math.round(x.getBoundingClientRect().height)));
      if (!rows.length) problem0('keys: no phone rows rendered');
      else if (rows.some((h) => h < 72)) problem0(`keys: a phone row is shorter than 72 px (${rows.join(',')})`);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      if (overflow > 1) problem0(`keys: page scrolls sideways by ${overflow}px`);
      const more = await page.$('#keys-list .key-row button[aria-label^="More actions"]');
      if (!more) problem0('keys: row "⋯" button not found');
      else {
        await more.click();
        await page.waitForSelector('.sheet.show', { timeout: 3000 }).catch(() => {});
        const items = await page.$$eval('.sheet.show .sheet-item', (b) => b.map((x) => x.textContent.trim()));
        if (items.join('|') !== 'Copy key|Reset devices|Edit|Delete key') problem0(`keys: sheet items are ${JSON.stringify(items)}`);
        await shot(page, `${size.name}_keys_sheet`, { fullPage: false });
        await page.click('.sheet.show .sheet-item.danger');
        await page.waitForSelector('.modal-backdrop.show', { timeout: 3000 }).catch(() => {});
        await shot(page, `${size.name}_confirm`, { fullPage: false });
        await page.click('.modal-backdrop.show .btn-secondary').catch(() => {});
      }
      await page.click('#keys-select');
      const boxes = await page.$$('#keys-list [data-pick]');
      for (const b of boxes.slice(0, 3)) await b.click();
      const label = (await page.textContent('#keys-selected-count').catch(() => '')) || '';
      if (boxes.length >= 3 && label.trim() !== '3 selected') problem0(`keys: select mode says "${label.trim()}"`);
      await shot(page, `${size.name}_keys_select`, { fullPage: false });
      await page.click('#keys-select-cancel');
      // Every page: nothing wider than the phone.
      for (const [name, url] of pages) {
        await page.goto(`${BASE}${url}`);
        const wide = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
        if (wide > 1) problem0(`${name}: page scrolls sideways by ${wide}px at 390`);
      }
      // No "+" tab on the generate page.
      await page.goto(`${BASE}/keys/generate`);
      if (await page.$('.tabbar .tab-cta')) problem0('generate: the + tab is shown on the generate page');
    }

    // ---- 2.2.0 layout checks: measured from the DOM, not eyeballed.
    const problem = (msg) => { failures += 1; report.push(`[${size.name}] ${msg}`); console.error('LAYOUT', size.name, msg); };
    const overlaps = (a, b) => a && b && a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1;

    // 1. Dashboard "Account expires": the label and the full IST date never overlap.
    await page.goto(`${BASE}/dashboard`);
    const exp = await page.evaluate(() => {
      const row = document.getElementById('account-expires');
      if (!row) return null;
      const box = (el) => { const b = el.getBoundingClientRect(); return { left: b.left, right: b.right, top: b.top, bottom: b.bottom }; };
      const label = row.querySelector('.lr-label');
      // The label's text extent (its box can be wider than the words).
      const range = document.createRange(); range.selectNodeContents(label);
      const t = range.getBoundingClientRect();
      return { label: { left: t.left, right: t.right, top: t.top, bottom: t.bottom }, value: box(row.querySelector('.lr-value')), row: box(row), text: row.querySelector('.lr-value').textContent.trim() };
    });
    if (!exp) problem('dashboard: Account expires row missing');
    else {
      if (overlaps(exp.label, exp.value)) problem(`dashboard: Account expires label overlaps "${exp.text}"`);
      if (exp.value.right > exp.row.right + 1 || exp.value.left < exp.row.left - 1) problem('dashboard: expiry value runs outside its row');
    }
    report.push(`[${size.name}] expiry row: label ${JSON.stringify(exp && exp.label)} value ${JSON.stringify(exp && exp.value)}`);
    await shot(page, `${size.name}_dashboard_expiry`, { fullPage: false });

    // 2. Docs callouts: ordinary flowing text, links inline and tappable.
    for (const [name, url] of [['docs', '/docs'], ['api_docs', '/api-docs']]) {
      await page.goto(`${BASE}${url}`);
      const notes = await page.evaluate(() => [...document.querySelectorAll('.doc-note')].map((n) => {
        const parts = [...n.querySelectorAll('a, strong, .doc-inline')].map((el) => [...el.getClientRects()].map((r) => ({ left: r.left, right: r.right, top: r.top, bottom: r.bottom, h: r.height })));
        return { display: getComputedStyle(n).display, parts, links: [...n.querySelectorAll('a')].map((a) => a.textContent.trim()) };
      }));
      if (!notes.length) problem(`${name}: no .doc-note callouts found`);
      notes.forEach((note, i) => {
        if (note.display !== 'block') problem(`${name}: callout ${i} is display:${note.display}, not flowing text`);
        for (let a = 0; a < note.parts.length; a += 1) {
          for (let b = a + 1; b < note.parts.length; b += 1) {
            if (note.parts[a].some((ra) => note.parts[b].some((rb) => overlaps(ra, rb)))) problem(`${name}: callout ${i} has overlapping words/links`);
          }
          if (note.parts[a].some((r) => r.h < 12)) problem(`${name}: callout ${i} has a squeezed link/word`);
        }
      });
    }

    // 4. Referral codes + API keys: stacked on a phone, side by side on desktop, never overlapping.
    for (const [name, url] of [['referral', '/admin/create-referral'], ['api_keys', '/api-keys']]) {
      await page.goto(`${BASE}${url}`);
      const cards = await page.evaluate(() => [...document.querySelectorAll('.grid-split > .card')].slice(0, 2).map((c) => {
        const b = c.getBoundingClientRect(); return { left: b.left, right: b.right, top: b.top, bottom: b.bottom };
      }));
      if (cards.length < 2) { problem(`${name}: create/list cards not found`); continue; }
      const [a, b] = cards;
      if (overlaps(a, b)) problem(`${name}: create and list cards overlap`);
      if (size.mobile && b.top < a.bottom) problem(`${name}: cards are not stacked on a phone`);
      if (!size.mobile && !(b.left >= a.right && Math.abs(a.top - b.top) < 2)) problem(`${name}: cards are not two columns on desktop`);
    }

    // 3. Library: one publish card; an http:// link gets a clear error.
    await page.goto(`${BASE}/lib`);
    const forms = await page.$$eval('form', (f) => f.length);
    if (forms !== 1 || await page.$('#link-form')) problem(`lib: expected exactly one publish form, found ${forms}`);
    await page.fill('#lib-url', 'http://example.com/lib.so');
    await page.click('#lib-submit');
    const libError = (await page.textContent('#lib-result').catch(() => '')) || '';
    if (!/https/i.test(libError)) problem(`lib: http:// link did not give a clear error (${libError})`);
    await shot(page, `${size.name}_lib_error`);

    // 7. App update: empty publish -> per-field errors, nothing published.
    await page.goto(`${BASE}/app-update`);
    await page.fill('#app-version-code', '');
    await page.click('#app-submit');
    const fieldErrors = await page.$$eval('#app-form .field.invalid', (f) => f.length);
    if (fieldErrors < 3) problem(`app update: expected field errors for an empty form, got ${fieldErrors}`);
    await shot(page, `${size.name}_app_update_errors`);

    if (size.mobile) {
      await page.goto(`${BASE}/dashboard`);
      await page.click('[data-nav-open]');
      await shot(page, `${size.name}_nav_open`, { fullPage: false });
    }

    if (errors.length) {
      console.error(`[${size.name}] browser errors:\n  ${errors.join('\n  ')}`);
      errors.forEach((e) => report.push(`[${size.name}] ${e}`));
      failures += errors.length;
    }
    await ctx.close();
  }
  await browser.close();
} finally {
  stop();
}
writeFileSync(path.join(out, 'report.txt'), report.length ? `${report.join('\n')}\n` : 'ok\n');
console.log(failures ? `${failures} problem(s)` : 'all screenshots saved');
process.exit(failures ? 1 : 0);
