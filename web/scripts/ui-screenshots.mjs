// Captures the panel at desktop and phone sizes for visual review.
// Runs in CI (.github/workflows/web-ui-qa.yml): starts the offline dev server
// (memory DB), signs in as the Owner and saves PNGs to docs/ui-redesign/web.
//   npm i --no-save playwright && npx playwright install chromium
//   node scripts/ui-screenshots.mjs
import { spawn } from 'node:child_process';
import { mkdirSync } from 'node:fs';
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
  ['api_keys', '/api-keys'],
  ['settings', '/settings'],
  ['docs', '/docs'],
  ['not_found', '/does-not-exist'],
];

let failures = 0;
async function shot(page, file, opts = {}) {
  try {
    await page.waitForTimeout(350);
    await page.screenshot({ path: path.join(out, `${file}.png`), fullPage: opts.fullPage ?? true });
    console.log('saved', file);
  } catch (e) { failures += 1; console.error('FAILED', file, e.message); }
}

try {
  await waitForServer();
  const browser = await chromium.launch();
  for (const size of sizes) {
    const ctx = await browser.newContext({
      viewport: size.viewport, deviceScaleFactor: size.scale, isMobile: !!size.mobile, hasTouch: !!size.mobile,
      colorScheme: 'dark', reducedMotion: 'reduce',
    });
    const page = await ctx.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

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

    // Confirm dialog from the keys list.
    await page.goto(`${BASE}/keys`);
    await page.waitForSelector('#keys-body .icon-btn.danger', { timeout: 5000 }).catch(() => {});
    const del = await page.$('#keys-body .icon-btn.danger');
    if (del) { await del.click(); await page.waitForSelector('.modal-backdrop.show').catch(() => {}); await shot(page, `${size.name}_confirm`, { fullPage: false }); }

    if (size.mobile) {
      await page.goto(`${BASE}/dashboard`);
      await page.click('[data-nav-open]');
      await shot(page, `${size.name}_nav_open`, { fullPage: false });
    }

    if (errors.length) { console.error(`[${size.name}] browser errors:\n  ${errors.join('\n  ')}`); failures += errors.length; }
    await ctx.close();
  }
  await browser.close();
} finally {
  stop();
}
console.log(failures ? `${failures} problem(s)` : 'all screenshots saved');
process.exit(failures ? 1 : 0);
