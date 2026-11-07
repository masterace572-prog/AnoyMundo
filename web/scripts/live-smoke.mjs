// Live smoke test against a deployed panel (Vercel + Supabase).
//   BASE=https://anoymundo-panel.vercel.app OWNER_USERNAME=Anoy OWNER_PASSWORD=... node scripts/live-smoke.mjs
// Without OWNER_PASSWORD only the public checks run. Everything the signed-in
// part creates (keys, one API key) is deleted again before it exits.
// The account must be level 1 (Owner) so no balance is charged.
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
  r = await request('/login', { method: 'POST', body: form({ username: OWNER, password: 'definitely-wrong' }) });
  ok(r.status === 302 && !cookies.has('am_session'), 'a wrong password is refused');
  r = await request('/login', { method: 'POST', body: form({ username: OWNER, password: PASSWORD }) });
  ok(r.status === 302 && r.location === '/dashboard' && cookies.has('am_session'), `${OWNER} signs in`);
  if (!cookies.has('am_session')) throw new Error('cannot continue without a session');

  for (const route of ['/dashboard', '/keys', '/keys/generate', '/settings', '/server', '/admin/manage-users',
    '/admin/create-referral', '/lib', '/api-keys', '/api-docs', '/docs']) {
    r = await request(route);
    ok(r.status === 200 && /ui\.css/.test(r.body), `GET ${route} -> 200`, String(r.status));
  }
  r = await request('/dashboard');
  ok(/Unlimited/.test(r.body) && /Owner/.test(r.body), 'owner dashboard shows an unlimited balance');

  // 4. random + custom keys (owner is never charged)
  r = await request('/keys/generate', { method: 'POST', headers: SAME, body: form({ game: 'PUBG', duration: '168', max_devices: '1', count: '2', custom: '', request_id: `live-random-${run}-${crypto.randomUUID()}` }) });
  let out = await generatedKeys();
  out.keys.forEach((k) => cleanupKeys.add(k));
  ok(out.keys.length === 2 && out.keys.every((k) => KEY_RE.test(k) && k.startsWith(`${PREFIX}-7D-`)), `random keys: ${out.keys.join(', ')}`);
  const randomKey = out.keys[0] || '';

  const custom = `L${run}`.slice(0, 6);
  r = await request('/keys/generate', { method: 'POST', headers: SAME, body: form({ game: 'PUBG', duration: '24', max_devices: '1', custom: '1', custom_key: custom.toLowerCase(), request_id: `live-custom-${run}-${crypto.randomUUID()}` }) });
  out = await generatedKeys();
  out.keys.forEach((k) => cleanupKeys.add(k));
  ok(out.keys.length === 1 && out.keys[0] === `${PREFIX}-1D-${custom}`, `custom key created in upper case: ${out.keys.join(',')}`);

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
  r = await request('/api/v1/keys', { method: 'POST', headers: H, body: { game: 'PUBG', duration: 2, max_devices: 1, count: 1 } });
  const apiKey = r.body?.keys?.[0]?.user_key || '';
  if (apiKey) cleanupKeys.add(apiKey);
  ok(r.status === 201 && KEY_RE.test(apiKey) && apiKey.startsWith(`${PREFIX}-2H-`), `POST /api/v1/keys creates ${apiKey}`);
  r = await request(`/api/v1/keys/${apiKey.toLowerCase()}`, { headers: H });
  ok(r.body?.key?.user_key === apiKey, 'GET /api/v1/keys/{key} (case-insensitive)');
  r = await request(`/api/v1/keys/${apiKey}`, { method: 'DELETE', headers: H });
  ok(r.body?.deleted === true, 'DELETE /api/v1/keys/{key}');
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
