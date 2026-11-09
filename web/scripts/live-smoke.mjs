// Live smoke test against a deployed panel (Vercel + Supabase).
//   BASE=https://anoymundo-panel.vercel.app OWNER_USERNAME=Anoy OWNER_PASSWORD=... node scripts/live-smoke.mjs
// Without OWNER_PASSWORD only the public checks run. Everything the signed-in
// part creates (keys, one API key) is deleted again before it exits.
// With the Owner account every page is checked and nothing is charged; with a
// reseller account only reseller pages are checked and charges are verified.
import crypto from 'node:crypto';

const BASE = (process.env.BASE || 'https://anoymundo-panel.vercel.app').replace(/\/+$/, '');
const OWNER = process.env.OWNER_USERNAME || 'Anoy';
const PASSWORD = process.env.OWNER_PASSWORD || '';
const PREFIX = OWNER.toUpperCase();

let passed = 0;
let failed = 0;
const cookies = new Map();
const cleanupKeys = new Set();
let cleanupApiKeyId = '';

function ok(condition, label, extra = '') {
  if (condition) { passed += 1; console.log(`  PASS  ${label}`); }
  else {
    failed += 1;
    console.error(`  FAIL  ${label} ${extra}`);
    if (process.env.GITHUB_ACTIONS) console.log(`::error::${label} ${String(extra).slice(0, 300)}`);
  }
}

function storeCookies(response) {
  const list = response.headers.getSetCookie ? response.headers.getSetCookie()
    : [response.headers.get('set-cookie')].filter(Boolean);
  for (const raw of list) {
    const [pair] = raw.split(';');
    const idx = pair.indexOf('=');
    const name = pair.slice(0, idx).trim();
    const value = pair.slice(idx + 1).trim();
    if (!value) cookies.delete(name); else cookies.set(name, value);
  }
}

async function request(route, { method = 'GET', body, headers = {}, raw = false } = {}) {
  const opts = {
    method,
    redirect: 'manual',
    headers: { Cookie: [...cookies.entries()].map(([k, v]) => `${k}=${v}`).join('; '), ...headers },
    signal: AbortSignal.timeout(30000),
  };
  if (body !== undefined) {
    if (body instanceof URLSearchParams) {
      opts.body = body.toString();
      opts.headers['Content-Type'] = 'application/x-www-form-urlencoded';
    } else {
      opts.body = JSON.stringify(body);
      opts.headers['Content-Type'] = 'application/json';
    }
  }
  let response;
  for (let attempt = 0; ; attempt++) {
    try {
      response = await fetch(BASE + route, opts);
      break;
    } catch (err) {
      if (attempt >= 2 || method !== 'GET') throw err;
      await new Promise((r) => setTimeout(r, 1500));
    }
  }
  storeCookies(response);
  if (raw) return { response, status: response.status, buffer: Buffer.from(await response.arrayBuffer()) };
  const contentType = response.headers.get('content-type') || '';
  const text = await response.text();
  let parsed = text;
  if (contentType.includes('application/json')) {
    try { parsed = JSON.parse(text); } catch { /* keep text */ }
  }
  return { response, status: response.status, body: parsed, location: response.headers.get('location') };
}

// Response-time probe (2.3.3). One warm-up request, then `n` timed requests;
// prints the median as a ::notice:: so it shows in the run summary. The
// x-vercel-id header names the edge and the function region
// ("<edge>::<function region>::<id>"); Server-Timing (app;dur=) is the time
// spent inside the function, independent of where this runner is.
async function timing(label, route, n = 7) {
  const totals = [];
  const server = [];
  let id = '';
  let cache = '';
  const at = (i) => (typeof route === 'function' ? route(i) : route);
  await request(at(-1));
  for (let i = 0; i < n; i++) {
    const t0 = performance.now();
    const r = await request(at(i));
    totals.push(performance.now() - t0);
    const dur = /app;dur=([0-9.]+)/.exec(r.response.headers.get('server-timing') || '');
    if (dur) server.push(Number(dur[1]));
    id = (r.response.headers.get('x-vercel-id') || '').split('::').slice(0, -1).join('::');
    cache = r.response.headers.get('x-vercel-cache') || '';
  }
  const median = (a) => (a.length ? [...a].sort((x, y) => x - y)[Math.floor(a.length / 2)].toFixed(0) : 'n/a');
  const line = `TIMING ${label}: median ${median(totals)} ms (min ${Math.min(...totals).toFixed(0)}, max ${Math.max(...totals).toFixed(0)}), ` +
    `in function ${median(server)} ms, route ${id || '?'}, cache ${cache || '-'}`;
  console.log(`  ${line}`);
  if (process.env.GITHUB_ACTIONS) console.log(`::notice::${line}`);
}

const form = (o) => new URLSearchParams(o);
const SAME = { 'sec-fetch-site': 'same-origin' };
const KEY_RE = new RegExp(`^${PREFIX}-(2H|5H|1D|3D|7D|14D|30D)-[A-Z0-9]{4,10}$`);

async function generatedKeys() {
  const page = await request('/keys/generate');
  const m = String(page.body).match(/<textarea class="input" id="generated-keys"[^>]*>([^<]*)<\/textarea>/);
  return { page, keys: (m?.[1] || '').split('\n').map((k) => k.trim()).filter(Boolean) };
}

async function main() {
  console.log(`\n=== Live panel smoke test: ${BASE} ===\n`);
  const run = Date.now().toString(36).toUpperCase().slice(-5);

  // 1. health + public surface
  let r = await request('/api/health');
  ok(r.status === 200 && r.body?.ok === true, 'GET /api/health: Supabase reachable, schema complete', JSON.stringify(r.body).slice(0, 300));
  r = await request('/');
  ok(r.status === 200 && /Sign In/.test(r.body) && /ui\.css/.test(r.body), 'GET / renders the sign-in page with ui.css');
  ok(!/ic_anoy|tailwind|bootstrap/i.test(r.body), 'login page uses no logo image and no old CSS frameworks');
  r = await request('/register');
  ok(r.status === 200 && /Create account/.test(r.body), 'GET /register renders');
  for (const asset of ['/assets/css/ui.css', '/assets/js/app-ui.js', '/favicon.svg']) {
    r = await request(asset);
    ok(r.status === 200 && String(r.body).length > 100, `GET ${asset} -> 200`);
  }
  r = await request('/assets/css/ui.css');
  ok(/--blue/.test(r.body) && /#000/.test(r.body) && !/linear-gradient/.test(r.body), 'ui.css is the flat black/blue theme');
  r = await request('/connect');
  ok(r.body?.web_info?._client === 'Anoy', 'GET /connect info reports the Anoy brand');

  await timing('GET /api/app/update?code=10 (as the app calls it)', '/api/app/update?code=10');
  await timing('GET /api/app/update (function, cache bypassed)', (i) => `/api/app/update?code=10&probe=${run}${i}`);
  // 2.3.3: the function runs next to the database, the app's update check is
  // answered from the CDN, and nothing private is cached.
  r = await request(`/api/app/update?code=10&probe=${run}region`);
  ok(/::bom1::/.test(r.response.headers.get('x-vercel-id') || ''), 'the panel function runs in bom1 (Mumbai, next to Supabase ap-south-1)',
    r.response.headers.get('x-vercel-id'));
  r = await request('/api/app/update?code=10');
  ok(['HIT', 'STALE'].includes(r.response.headers.get('x-vercel-cache')), 'GET /api/app/update is served from the CDN cache',
    `${r.response.headers.get('x-vercel-cache')} / ${r.response.headers.get('cache-control')}`);
  r = await request('/api/app/update?code=10', { headers: { 'Cache-Control': 'no-cache' } });
  console.log(`  (with a client "Cache-Control: no-cache" the CDN answered ${r.response.headers.get('x-vercel-cache')})`);
  for (const route of ['/api/lib/manifest', '/connect']) {
    r = await request(route);
    ok(/no-store/.test(r.response.headers.get('cache-control') || '') && r.response.headers.get('x-vercel-cache') !== 'HIT',
      `${route} is not cached`, `${r.response.headers.get('cache-control')} / ${r.response.headers.get('x-vercel-cache')}`);
  }

  // 2. loader library endpoints
  r = await request('/api/lib/manifest');
  ok(r.status === 200 && r.body?.ok === true && typeof r.body?.version === 'string', 'GET /api/lib/manifest', JSON.stringify(r.body).slice(0, 200));
  const manifest = r.body || {};
  r = await request('/api/lib/version');
  ok(r.status === 200 && String(r.body).trim() === manifest.version, `GET /api/lib/version matches the manifest (${String(r.body).trim()})`);
  if (manifest.available) {
    r = await request('/api/lib/download', { raw: true });
    if (r.status === 302) {
      ok(!!r.response.headers.get('location'), 'GET /api/lib/download redirects to the external file');
    } else {
      const sha = crypto.createHash('sha256').update(r.buffer).digest('hex');
      ok(r.status === 200 && r.buffer.subarray(0, 2).toString() === 'PK', 'GET /api/lib/download serves a ZIP');
      ok(!manifest.size || r.buffer.length === Number(manifest.size), `download size matches the manifest (${r.buffer.length})`);
      ok(!manifest.sha256 || sha === manifest.sha256, 'download SHA-256 matches the manifest');
      ok(r.buffer.includes(Buffer.from('libbgmi.so')), 'ZIP contains libbgmi.so');
    }
  }

  if (!PASSWORD) {
    console.log('\n  (OWNER_PASSWORD not set: signed-in checks skipped)');
    return;
  }

  // 3. sign in as the owner
  // A temporary account (live-smoke.yml) appears once a maintainer has created
  // it; until then the login is refused, so poll gently.
  const waitUntil = Date.now() + Number(process.env.WAIT_FOR_ACCOUNT || 0) * 1000;
  while (Date.now() < waitUntil) {
    r = await request('/login', { method: 'POST', body: form({ username: OWNER, password: PASSWORD }) });
    if (r.status === 302 && r.location === '/dashboard') break;
    console.log(`  (waiting for the temporary account ${OWNER})`);
    await new Promise((done) => setTimeout(done, 20000));
  }
  if (cookies.has('am_session')) await request('/logout');
  r = await request('/login', { method: 'POST', body: form({ username: OWNER, password: 'definitely-wrong' }) });
  ok(r.status === 302 && !cookies.has('am_session'), 'a wrong password is refused');
  r = await request('/login', { method: 'POST', body: form({ username: OWNER, password: PASSWORD }) });
  ok(r.status === 302 && r.location === '/dashboard' && cookies.has('am_session'), `${OWNER} signs in`);
  if (!cookies.has('am_session')) throw new Error('cannot continue without a session');

  r = await request('/dashboard');
  const isOwner = /Unlimited/.test(r.body) && /Owner/.test(r.body);
  console.log(`  (signed in as ${isOwner ? 'Owner' : 'non-owner'})`);
  if (OWNER.toLowerCase() === 'anoy') ok(isOwner, 'Anoy is the Owner with an unlimited balance');
  await timing('GET /dashboard (signed in)', '/dashboard');
  r = await request('/dashboard');
  ok(/no-store/.test(r.response.headers.get('cache-control') || '') && r.response.headers.get('x-vercel-cache') !== 'HIT',
    'signed-in pages are never cached publicly', r.response.headers.get('cache-control'));
  {
    const sprite = /data-icons="([^"]+)"/.exec(String(r.body))?.[1];
    ok(!!sprite && !/id="ui-icons"/.test(String(r.body)), 'signed-in layout uses the icon sprite (no inline icon template)');
    if (sprite) {
      const svg = await request(sprite);
      ok(svg.status === 200 && /immutable/.test(svg.response.headers.get('cache-control') || ''), `icon sprite ${sprite} is cached for a year`,
        svg.response.headers.get('cache-control'));
    }
  }
  // A key that existed before this run (created by hand for the temporary
  // account, or any unused key of the account) still connects.
  r = await request('/keys/api?status=unused&per=5');
  const existing = (r.body?.rows || []).find((row) => /EXIST/.test(row.user_key));
  if (existing) {
    r = await request('/connect', { method: 'POST', body: form({ game: existing.game, user_key: existing.user_key, serial: `smoke${run}` }) });
    ok(r.body?.status === true && /^[a-f0-9]{32}$/.test(r.body?.data?.token || ''), 'POST /connect accepts a key that existed before this deployment', JSON.stringify(r.body).slice(0, 200));
  }
  const routes = ['/dashboard', '/keys', '/keys/generate', '/settings', '/api-keys', '/api-docs', '/docs'];
  if (isOwner) routes.push('/server', '/admin/manage-users', '/admin/create-referral', '/lib');
  for (const route of routes) {
    r = await request(route);
    ok(r.status === 200 && /ui\.css/.test(r.body), `GET ${route} -> 200`, String(r.status));
  }
  if (!isOwner) {
    r = await request('/lib');
    ok(r.status === 302, 'non-owners cannot open /lib');
  }

  // 4. random + custom keys (owner is never charged)
  r = await request('/keys/generate', { method: 'POST', headers: SAME, body: form({ game: 'PUBG', duration: isOwner ? '168' : '2', max_devices: '1', count: '2', custom: '', request_id: `live-random-${run}-${crypto.randomUUID()}` }) });
  let out = await generatedKeys();
  out.keys.forEach((k) => cleanupKeys.add(k));
  const tok = isOwner ? '7D' : '2H';
  ok(out.keys.length === 2 && out.keys.every((k) => KEY_RE.test(k) && k.startsWith(`${PREFIX}-${tok}-`)), `random keys: ${out.keys.join(', ')}`);
  ok(isOwner ? /No charge/.test(out.page.body) : /Charged ₹20/.test(out.page.body), isOwner ? 'Owner is not charged' : 'reseller is charged ₹10 × 2 keys');
  const randomKey = out.keys[0] || '';

  const custom = `L${run}`.slice(0, 6);
  r = await request('/keys/generate', { method: 'POST', headers: SAME, body: form({ game: 'PUBG', duration: isOwner ? '24' : '2', max_devices: '1', custom: '1', custom_key: custom.toLowerCase(), request_id: `live-custom-${run}-${crypto.randomUUID()}` }) });
  out = await generatedKeys();
  out.keys.forEach((k) => cleanupKeys.add(k));
  ok(out.keys.length === 1 && out.keys[0] === `${PREFIX}-${isOwner ? '1D' : '2H'}-${custom}`, `custom key created in upper case: ${out.keys.join(',')}`);
  r = await request('/keys/generate', { method: 'POST', headers: SAME, body: form({ game: 'PUBG', duration: isOwner ? '24' : '2', max_devices: '1', custom: '1', custom_key: custom, request_id: `live-dup-${run}-${crypto.randomUUID()}` }) });
  r = await request('/keys/generate');
  ok(/already exists/.test(r.body), 'a duplicate custom key is reported, nothing created');

  r = await request(`/keys/api?q=${encodeURIComponent(custom)}`);
  ok(r.body?.ok === true && r.body?.total === 1, 'GET /keys/api finds the custom key');

  // 5. licensing API
  const serial = `LIVESMOKE${run}`;
  r = await request('/connect', { method: 'POST', body: form({ game: 'PUBG', user_key: randomKey, serial }) });
  ok(r.body?.status === true && /^[a-f0-9]{32}$/.test(r.body?.data?.token || ''), 'POST /connect accepts the new key and returns a token', JSON.stringify(r.body).slice(0, 200));
  ok(/Z$/.test(r.body?.data?.EXP || '') && Number.isFinite(r.body?.data?.rng), '/connect returns an ISO expiry and server time');
  r = await request('/connect', { method: 'POST', body: form({ game: 'PUBG', user_key: `${PREFIX}-1D-NOPE${run}`, serial }) });
  ok(r.body?.status === false && r.body?.reason === 'USER OR GAME NOT REGISTERED', 'unknown keys are rejected');
  r = await request('/keys/reset', { method: 'POST', headers: SAME, body: form({ userkey: randomKey }) });
  ok(r.body?.ok === true && r.body?.reset === true, 'POST /keys/reset clears devices');

  // 6. personal API key + /api/v1
  r = await request('/api/v1/keys');
  ok(r.status === 401, '/api/v1 refuses calls without an API key');
  await request('/api-keys', { method: 'POST', headers: SAME, body: form({ action: 'create', name: `live-smoke-${run}` }) });
  r = await request('/api-keys');
  const token = String(r.body).match(/amk_[A-Za-z0-9_-]{20,}/)?.[0] || '';
  const ids = [...String(r.body).matchAll(/name="action" value="delete"><input type="hidden" name="id" value="(\d+)"/g)].map((m) => m[1]);
  cleanupApiKeyId = ids[0] || '';
  ok(token.length > 20, 'a new API key is shown once');
  const H = { 'X-API-Key': token };
  r = await request('/api/v1/me', { headers: H });
  ok(r.body?.ok === true && String(r.body?.account?.username).toLowerCase() === OWNER.toLowerCase(), 'GET /api/v1/me');
  const saldoBefore = Number(r.body?.account?.saldo);
  r = await request('/api/v1/keys', { method: 'POST', headers: H, body: { game: 'PUBG', duration: 2, max_devices: 1, count: 1 } });
  const apiKey = r.body?.keys?.[0]?.user_key || '';
  if (apiKey) cleanupKeys.add(apiKey);
  ok(r.status === 201 && KEY_RE.test(apiKey) && apiKey.startsWith(`${PREFIX}-2H-`), `POST /api/v1/keys creates ${apiKey}`);
  ok(isOwner ? r.body?.charged === 0 : (r.body?.charged === 10 && r.body?.saldo_after === saldoBefore - 10),
    `API charge is correct (${r.body?.charged}, ${saldoBefore} -> ${r.body?.saldo_after})`);
  r = await request(`/api/v1/keys/${apiKey.toLowerCase()}`, { headers: H });
  ok(r.body?.key?.user_key === apiKey, 'GET /api/v1/keys/{key} (case-insensitive)');
  r = await request(`/api/v1/keys/${apiKey}`, { method: 'DELETE', headers: H });
  ok(r.body?.deleted === true && (isOwner || r.body?.refund_total === 10), 'DELETE /api/v1/keys/{key} (never-used key refunded)');
  cleanupKeys.delete(apiKey);
}

async function cleanup() {
  for (const key of cleanupKeys) {
    try {
      const r = await request('/keys/resetall', { method: 'POST', headers: SAME, body: form({ userkey: key }) });
      ok(r.body?.ok === true && r.body?.deleted === true, `cleanup: deleted ${key}`);
    } catch (err) { ok(false, `cleanup ${key}`, err.message); }
  }
  if (cleanupApiKeyId) {
    try {
      const r = await request('/api-keys', { method: 'POST', headers: SAME, body: form({ action: 'delete', id: cleanupApiKeyId }) });
      ok(r.status === 302, 'cleanup: deleted the test API key');
    } catch (err) { ok(false, 'cleanup API key', err.message); }
  }
  if (cookies.has('am_session')) {
    const r = await request('/logout');
    ok(r.status === 302, 'GET /logout');
    const after = await request('/dashboard');
    ok(after.status === 302, 'session is gone after logout');
  }
}

try {
  await main();
} catch (err) {
  failed += 1;
  console.error(`  FAIL  ${err.stack || err}`);
} finally {
  await cleanup().catch((err) => { failed += 1; console.error(`  FAIL  cleanup ${err}`); });
}
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
