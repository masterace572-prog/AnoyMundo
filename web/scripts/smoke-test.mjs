// End-to-end smoke test against the real HTTP handler with the offline
// in-memory database driver. Run with: npm test
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PORT = Number(process.env.TEST_PORT || 4173);
const BASE = `http://127.0.0.1:${PORT}`;

let passed = 0;
let failed = 0;
const cookies = new Map();

function ok(condition, label, extra = '') {
  if (condition) { passed += 1; console.log(`  PASS  ${label}`); }
  else { failed += 1; console.error(`  FAIL  ${label} ${extra}`); }
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

function cookieHeader() {
  return [...cookies.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
}

async function request(route, { method = 'GET', body, headers = {}, redirect = 'manual' } = {}) {
  const opts = {
    method,
    redirect,
    headers: { Cookie: cookieHeader(), ...headers },
  };
  if (body !== undefined) {
    if (typeof body === 'string') opts.body = body;
    else if (body instanceof URLSearchParams) {
      opts.body = body.toString();
      opts.headers['Content-Type'] = 'application/x-www-form-urlencoded';
    } else {
      opts.body = JSON.stringify(body);
      opts.headers['Content-Type'] = 'application/json';
    }
  }
  const response = await fetch(BASE + route, opts);
  storeCookies(response);
  const contentType = response.headers.get('content-type') || '';
  let parsed = null;
  if (contentType.includes('application/json')) {
    try { parsed = await response.json(); } catch { /* ignore */ }
  }
  const text = parsed === null ? await response.text() : '';
  return { response, body: parsed ?? text, status: response.status, location: response.headers.get('location') };
}

async function waitForServer(timeoutMs = 15000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const r = await fetch(`${BASE}/api/health`);
      if (r.ok) return true;
    } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 150));
  }
  return false;
}

const server = spawn(process.execPath, ['scripts/dev-server.mjs', '--memory'], {
  cwd: root,
  env: { ...process.env, PORT: String(PORT), DB_DRIVER: 'memory', NODE_ENV: 'test' },
  stdio: ['ignore', 'pipe', 'pipe'],
});
let serverLog = '';
server.stdout.on('data', (d) => { serverLog += d.toString(); });
server.stderr.on('data', (d) => { serverLog += d.toString(); });

try {
  if (!await waitForServer()) throw new Error(`server did not start:\n${serverLog}`);
  console.log('\n=== AnoyMundo panel smoke tests (offline memory DB) ===\n');

  const form = (o) => new URLSearchParams(o);
  const SAME = { 'sec-fetch-site': 'same-origin' };
  const generatedKeys = async () => {
    const page = await request('/keys/generate');
    const m = String(page.body).match(/<textarea class="input" id="generated-keys"[^>]*>([^<]*)<\/textarea>/);
    return { page, keys: (m?.[1] || '').split('\n').map((k) => k.trim()).filter(Boolean) };
  };
  const login = (username, password) => request('/login', { method: 'POST', body: form({ username, password }) });

  // 1. health + public pages
  let r = await request('/api/health');
  ok(r.status === 200 && r.body.ok === true, 'GET /api/health reports all checks OK', JSON.stringify(r.body));
  ok(r.body.checks?.schema?.ok === true, 'schema check sees all tables');
  r = await request('/');
  ok(r.status === 200 && /Sign In/.test(r.body) && /name="username"/.test(r.body) && /name="password"/.test(r.body), 'GET / renders the sign-in page');
  ok(/\/assets\/css\/ui\.css/.test(r.body) && !/cdn\.|jquery|bootstrap|tailwind/i.test(r.body), 'sign-in page uses only the local stylesheet (no CDN)');
  ok(!/ic_anoy|logo/i.test(r.body), 'sign-in page shows no logo');
  ok(/https:\/\/t\.me\/libAkAudioVisiual/.test(r.body), 'sign-in page links the Telegram handle');
  r = await request('/register');
  ok(r.status === 200 && /Create account/.test(r.body) && /name="referral"/.test(r.body), 'GET /register renders');
  r = await request('/connect');
  ok(r.body?.web_info?._client === 'Anoy', 'GET /connect info reports the Anoy brand');
  r = await request('/dashboard');
  ok(r.status === 302 && r.location === '/login', 'protected page redirects anonymous visitor to /login');
  r = await request('/does-not-exist');
  ok(r.status === 302 && r.location === '/login', 'unknown page asks anonymous visitors to sign in');

  // 2. sign in
  r = await login('Anoy', 'wrongpass');
  ok(r.status === 302 && r.location === '/login', 'wrong password redirects back to login');
  r = await request('/login');
  ok(/Wrong password/.test(r.body) && /class="alert alert-danger"/.test(r.body), 'bad login shows an inline error');
  r = await request('/login', { method: 'POST', body: form({ username: 'Anoy', password: 'Anoy1764' }), headers: { 'sec-fetch-site': 'cross-site' } });
  ok(r.status === 302 && r.location === '/login' && !cookies.has('am_session'), 'a cross-site login POST is refused');
  r = await login('anoy', 'Anoy1764');
  ok(r.status === 302 && r.location === '/dashboard', 'Owner signs in (username is case-insensitive)');
  ok(cookies.has('am_session'), 'signed session cookie is issued');

  // 3. every page renders in the new design
  for (const route of ['/dashboard', '/keys', '/keys/generate', '/keys/1', '/settings', '/server',
    '/admin/manage-users', '/admin/create-referral', '/lib', '/api-keys', '/api-docs', '/docs']) {
    r = await request(route);
    ok(r.status === 200, `GET ${route} renders (${r.status})`);
    if (r.status !== 200) console.error(String(r.body).slice(0, 300));
    ok(/name="viewport"/.test(r.body) && /ui\.css/.test(r.body) && !/https?:\/\/(cdn|code\.jquery|cdnjs)/.test(r.body)
      && !/<\?php|&lt;\?/.test(r.body), `GET ${route} is self-contained`);
  }
  r = await request('/dashboard');
  ok(/Unlimited/.test(r.body) && /Owner/.test(r.body), 'Owner dashboard shows an unlimited balance');
  r = await request('/keys/generate');
  ok(/Balance: Unlimited/.test(r.body) && /data-unlimited="1"/.test(r.body), 'generate page shows the unlimited balance');
  ok(/ANOY-1D-/.test(r.body), 'generate page knows the Owner key prefix');
  r = await request('/admin/user/1');
  ok(r.status === 302 && r.location === '/admin/manage-users', 'the Owner row cannot be opened in the user editor');
  r = await request('/nope-page');
  ok(r.status === 404 && /Page not found/.test(r.body) && /ui\.css/.test(r.body), 'signed-in 404 uses the panel design');
  r = await request('/favicon.ico');
  ok(r.status === 301 && r.location === '/favicon.svg', '/favicon.ico points at the SVG icon');

  // 4. Owner generates random keys: correct format, never charged
  r = await request('/keys/generate', { method: 'POST', headers: SAME, body: form({ game: 'PUBG', duration: '168', max_devices: '1', count: '3', custom: '', request_id: 'req-owner-random-0001' }) });
  ok(r.status === 302 && r.location === '/keys/generate', 'POST /keys/generate redirects back');
  let out = await generatedKeys();
  ok(out.keys.length === 3 && out.keys.every((k) => /^ANOY-7D-[A-Z0-9]{5}$/.test(k)), `random keys use USER-7D-XXXXX (${out.keys.join(', ')})`);
  ok(/No charge \(unlimited balance\)/.test(out.page.body), 'Owner is not charged');
  const ownerKey = out.keys[0];

  // 5. custom keys (the reported bug): no count field posted at all
  r = await request('/keys/generate', { method: 'POST', headers: SAME, body: form({ game: 'PUBG', duration: '24', max_devices: '2', custom: '1', custom_key: 'vip01', request_id: 'req-owner-custom-0001' }) });
  out = await generatedKeys();
  ok(out.keys.length === 1 && out.keys[0] === 'ANOY-1D-VIP01', `custom key is created in upper case (${out.keys.join(',')})`);
  r = await request('/keys/generate', { method: 'POST', headers: SAME, body: form({ game: 'PUBG', duration: '24', max_devices: '1', custom: '1', custom_key: 'ANOY-1D-VIP01', request_id: 'req-owner-custom-0002' }) });
  r = await request('/keys/generate');
  ok(/ANOY-1D-VIP01 already exists/.test(r.body), 'a taken custom key (even pasted with its prefix) is reported clearly');
  r = await request('/keys/generate', { method: 'POST', headers: SAME, body: form({ game: 'PUBG', duration: '72', max_devices: '1', custom: '1', custom_key: 'anoy-3d-gold7', count: '5', request_id: 'req-owner-custom-0003' }) });
  out = await generatedKeys();
  ok(out.keys.length === 1 && out.keys[0] === 'ANOY-3D-GOLD7', 'a pasted full key keeps only its custom ending, and a custom key is always one key');
  r = await request('/keys/generate', { method: 'POST', headers: SAME, body: form({ game: 'PUBG', duration: '24', max_devices: '1', custom: '1', custom_key: 'ab!', request_id: 'req-owner-custom-0004' }) });
  r = await request('/keys/generate');
  ok(/Custom part must be 4-6 letters or numbers/.test(r.body) && /value="ab!"/.test(r.body), 'an invalid custom key shows a field error and keeps the input');
  r = await request('/keys/generate', { method: 'POST', headers: SAME, body: form({ game: 'PUBG', duration: '24', max_devices: '1', custom: '1', custom_key: 'ABCDEFG', request_id: 'req-owner-custom-0007' }) });
  r = await request('/keys/generate');
  ok(/Custom part must be 4-6 letters or numbers/.test(r.body), 'a 7-character custom ending is rejected (format allows 4-6)');
  r = await request('/keys/generate', { method: 'POST', headers: SAME, body: form({ game: 'PUBG', duration: '24', max_devices: '1', custom: '1', custom_key: '', request_id: 'req-owner-custom-0005' }) });
  r = await request('/keys/generate');
  ok(/Enter the custom part of the key/.test(r.body), 'an empty custom key is rejected');
  r = await request('/keys/generate', { method: 'POST', headers: SAME, body: form({ game: 'PUBG', duration: '24', max_devices: '1', custom: '1', custom_key: 'vip01', request_id: 'req-owner-custom-0001' }) });
  out = await generatedKeys();
  ok(out.keys[0] === 'ANOY-1D-VIP01', 'a double-submitted custom key returns the original result instead of an error');

  // 6. keys list API (search, paging, filters)
  r = await request('/keys/api?page=1&per=25');
  ok(r.body?.ok === true && r.body?.total === 7 && r.body?.rows?.length === 7, 'GET /keys/api lists every key', JSON.stringify(r.body).slice(0, 200));
  r = await request('/keys/api?q=vip01');
  ok(r.body?.total === 1 && r.body.rows[0].user_key === 'ANOY-1D-VIP01' && r.body.rows[0].state === 'unused', 'key search works (case-insensitive)');
  r = await request('/keys/api?per=5&page=2');
  ok(r.body?.rows?.length === 2 && r.body?.pages === 2, 'key list paginates');
  r = await request('/keys/api?q=' + encodeURIComponent('a),user_key.eq.x'));
  ok(r.body?.ok === true, 'filter syntax in a search cannot break the query');
  r = await request('/keys');
  ok(/7 keys in total/.test(r.body), 'keys page shows the total count');

  // 7. edit key
  r = await request('/keys/edit', { method: 'POST', headers: SAME, body: form({ id_keys: '1', game: 'PUBG', user_key: 'anoy-2h-lyk0y', duration: '5', max_devices: '3', status: '1', registrator: 'Anoy', devices: 'AAA111\nBBB222', expired_date: '' }) });
  ok(r.status === 302, 'POST /keys/edit succeeds');
  r = await request('/keys/api?q=LYK0Y');
  ok(r.body?.rows?.[0]?.duration === '5 Hours' && r.body?.rows?.[0]?.devices === 2 && r.body?.rows?.[0]?.user_key === 'ANOY-2H-LYK0Y',
    'key edit persists (and keeps the key upper case)', JSON.stringify(r.body?.rows?.[0]));

  // 8. licensing API
  r = await request('/connect', { method: 'POST', body: form({ game: 'PUBG', user_key: ownerKey, serial: 'TESTDEVICE001' }) });
  ok(r.status === 200 && r.body?.status === true, 'POST /connect accepts a valid key');
  ok(r.body?.data?.token === md5(r.body?.data?.real || ''), '/connect token = md5(real)');
  ok(r.body?.data?.modname === 'VIP MOD' && r.body?.data?.ESP === 'on', '/connect returns mod metadata + feature flags');
  ok(!!r.body?.data?.EXP, '/connect returns the expiry');
  r = await request('/connect', { method: 'POST', body: form({ game: 'PUBG', user_key: ownerKey.toLowerCase(), serial: 'TESTDEVICE001' }) });
  ok(r.body?.status === true && r.body?.data?.token === md5(`PUBG-${ownerKey.toLowerCase()}-TESTDEVICE001-${r.body?.data?.real?.split('-').pop()}`),
    'a lower-case key still signs in and the token matches what the loader sent');
  r = await request('/connect', { method: 'POST', body: form({ game: 'PUBG', user_key: ownerKey, serial: 'OTHERDEVICE' }) });
  ok(r.body?.status === false && /device/i.test(r.body?.reason || ''), 'a second device is refused when the key allows one');
  r = await request('/connect', { method: 'POST', body: form({ game: 'PUBG', user_key: 'DOES-NOT-EXIST', serial: 'TESTDEVICE002' }) });
  ok(r.body?.status === false && r.body?.reason === 'USER OR GAME NOT REGISTERED', 'unknown key is rejected');
  r = await request('/connect', { method: 'POST', body: form({ game: 'PUBG', user_key: ownerKey, serial: 'BAD|SERIAL' }) });
  ok(r.body?.status === false && r.body?.reason === 'Bad Parameter', 'malformed serial is rejected');

  // 9. server settings
  r = await request('/server', { method: 'POST', headers: SAME, body: form({ status_form: '1', radios: 'on', myInput: 'Under maintenance' }) });
  ok(r.status === 302, 'maintenance toggle saves');
  r = await request('/connect', { method: 'POST', body: form({ game: 'PUBG', user_key: ownerKey, serial: 'TESTDEVICE001' }) });
  ok(/maintenance/i.test(r.body?.reason || ''), '/connect honours maintenance mode');
  await request('/server', { method: 'POST', headers: SAME, body: form({ status_form: '1', myInput: '' }) });
  r = await request('/server', { method: 'POST', headers: SAME, body: form({ floating_form: '1', _ftext: 'Short status line' }) });
  r = await request('/server');
  ok(/Short status line/.test(r.body) && !/name="_ftextr" value="Safe" checked/.test(r.body), 'turning Safe OFF changes the mod status');
  await request('/server', { method: 'POST', headers: SAME, body: form({ floating_form: '1', _ftextr: 'Safe', _ftext: 'Short status line' }) });
  r = await request('/connect', { method: 'POST', body: form({ game: 'PUBG', user_key: ownerKey, serial: 'TESTDEVICE001' }) });
  ok(r.body?.data?.mod_status === 'Safe', '/connect serves the freshly saved mod status');
  await request('/server', { method: 'POST', headers: SAME, body: form({ feature_form: '1', AIM: 'on' }) });
  r = await request('/connect', { method: 'POST', body: form({ game: 'PUBG', user_key: ownerKey, serial: 'TESTDEVICE001' }) });
  ok(r.body?.data?.ESP === 'off' && r.body?.data?.AIM === 'on', 'feature toggles reach the licensing API');
  await request('/server', { method: 'POST', headers: SAME, body: form({ feature_form: '1', ESP: 'on', Item: 'on', AIM: 'on', SilentAim: 'on', BulletTrack: 'on', Memory: 'on', Floating: 'on', Setting: 'on' }) });

  // 10. reset / delete (POST from the panel, legacy GET still works)
  r = await request('/keys/reset', { method: 'POST', headers: SAME, body: form({ userkey: ownerKey }) });
  ok(r.body?.ok === true && r.body?.reset === true && r.body?.devices_total === 0, 'POST /keys/reset clears devices');
  r = await request('/keys/reset?userkey=' + encodeURIComponent(ownerKey));
  ok(r.body?.ok === true, 'legacy GET /keys/reset still works for non-browser callers');
  r = await request('/keys/resetall', { method: 'POST', headers: { 'sec-fetch-site': 'cross-site' }, body: form({ userkey: 'ANOY-2H-F7VWE' }) });
  ok(r.status === 403 && r.body?.code === 'cross_site_blocked', 'cross-site key deletion is blocked');
  r = await request('/keys/reset', { method: 'POST', headers: { Origin: 'https://evil.example' }, body: form({ userkey: ownerKey }) });
  ok(r.status === 403, 'a foreign Origin cannot reset devices');
  r = await request('/keys/resetall', { method: 'POST', headers: SAME, body: form({ userkey: 'ANOY-2H-F7VWE' }) });
  ok(r.body?.ok === true && r.body?.deleted === true, 'POST /keys/resetall deletes a key');
  r = await request('/keys/resetall', { method: 'POST', headers: SAME, body: form({ userkey: 'ANOY-2H-F7VWE' }) });
  ok(r.status === 404 && r.body?.ok === false, 'deleting a missing key returns a clear 404');

  // 11. referral -> registration -> reseller
  r = await request('/admin/create-referral', { method: 'POST', headers: SAME, body: form({ set_saldo: '500', accExpire: '30', accLevel: '3' }) });
  ok(r.status === 302, 'POST /admin/create-referral creates a code');
  r = await request('/admin/create-referral');
  const referral = String(r.body).match(/Referral code created: ([A-Z0-9]{7})/)?.[1] || '';
  ok(!!referral, `referral code shown (${referral})`);
  r = await request('/dashboard');
  ok(/Unlimited/.test(r.body), 'Owner balance stays unlimited after giving balance away');
  await request('/logout');
  r = await request('/register', { method: 'POST', headers: SAME, body: form({ email: 'reseller@example.com', username: 'reseller1', fullname: 'Reseller One', password: 'secret123', password2: 'secret123', referral: referral.toLowerCase() }) });
  ok(r.status === 302 && r.location === '/login', `registration with a (lower-case) referral code succeeds (${r.status} ${r.location})`);
  r = await request('/register', { method: 'POST', headers: SAME, body: form({ email: 'other@example.com', username: 'RESELLER1', fullname: 'Other', password: 'secret123', password2: 'secret123', referral }) });
  r = await request('/register');
  ok(/already been used|username is taken/.test(r.body), 'a used code / duplicate username is refused');
  r = await login('reseller1', 'secret123');
  ok(r.status === 302 && r.location === '/dashboard', 'the new reseller can sign in');
  r = await request('/dashboard');
  ok(/Reseller/.test(r.body) && /₹500/.test(r.body), 'reseller dashboard shows role and ₹500 balance');
  for (const route of ['/lib', '/admin/manage-users', '/server', '/admin/create-referral']) {
    r = await request(route);
    ok(r.status === 302 && r.location === '/dashboard', `${route} is blocked for a reseller`);
  }
  r = await request('/keys/api');
  ok(r.body?.total === 0, 'a reseller sees only their own keys');
  r = await request('/keys/reset', { method: 'POST', headers: SAME, body: form({ userkey: ownerKey }) });
  ok(r.status === 404 && !('devices_max' in (r.body || {})), "a reseller cannot reset (or even probe) someone else's key");
  r = await request('/keys/1');
  ok(r.status === 302 && r.location === '/keys', "a reseller cannot open someone else's key");
  r = await request('/keys/generate', { method: 'POST', headers: SAME, body: form({ game: 'PUBG', duration: '24', max_devices: '1', count: '1', custom: '', request_id: 'req-res-0001' }) });
  out = await generatedKeys();
  ok(out.keys.length === 1 && /^RESELLER1-1D-[A-Z0-9]{5}$/.test(out.keys[0]), `reseller key uses their name (${out.keys[0]})`);
  ok(/Charged ₹80/.test(out.page.body) && /Balance: ₹420/.test(out.page.body), 'reseller is charged ₹80 (1 day × 1 device)');
  r = await request('/keys/generate', { method: 'POST', headers: SAME, body: form({ game: 'PUBG', duration: '24', max_devices: '1', custom: '1', custom_key: 'shop1', request_id: 'req-res-0002' }) });
  out = await generatedKeys();
  ok(out.keys[0] === 'RESELLER1-1D-SHOP1' && /Balance: ₹340/.test(out.page.body), 'reseller custom key works and is charged once');
  r = await request('/keys/generate', { method: 'POST', headers: SAME, body: form({ game: 'PUBG', duration: '168', max_devices: '3', count: '100', custom: '', request_id: 'req-res-lowbal-0001' }) });
  r = await request('/keys/generate');
  ok(/Not enough balance/.test(r.body) && /Balance: ₹340/.test(r.body), 'insufficient balance is reported and nothing is charged');
  r = await request('/keys/api');
  ok(r.body?.total === 2, 'a rejected order creates zero keys');

  // 12. personal API keys (as the reseller, so charges are visible)
  r = await request('/api/v1/keys');
  ok(r.status === 401 && r.body?.error?.code === 'missing_api_key', '/api/v1 refuses requests without an API key');
  r = await request('/api-keys', { method: 'POST', headers: SAME, body: form({ action: 'create', name: 'smoke-bot' }) });
  r = await request('/api-keys');
  const apiToken = String(r.body).match(/amk_[A-Za-z0-9_-]{20,}/)?.[0] || '';
  ok(/smoke-bot/.test(r.body) && apiToken.length > 20, 'a new API key is shown once');
  const H = { 'X-API-Key': apiToken };
  r = await request('/api/v1/me', { headers: H });
  ok(r.body?.account?.username === 'reseller1' && r.body?.account?.saldo === 340, 'GET /api/v1/me authenticates the API key');
  r = await request('/api/v1/pricing', { headers: H });
  ok(r.body?.durations?.length === 7 && r.body.durations.find((d) => d.hours === 168)?.label === '7 days', 'GET /api/v1/pricing lists durations');
  r = await request('/api/v1/keys', { method: 'POST', headers: H, body: { game: 'PUBG', duration: 2, max_devices: 1, count: 3 } });
  ok(r.status === 201 && r.body?.keys?.length === 3 && r.body?.price === 30 && r.body?.saldo_after === 310, 'bulk API order charges per key (3 × ₹10)');
  ok((r.body?.keys || []).every((k) => /^RESELLER1-2H-[A-Z0-9]{5}$/.test(k.user_key)), 'API keys use the same format');
  const apiKeyMade = r.body?.keys?.[0]?.user_key || '';
  r = await request('/api/v1/keys', { method: 'POST', headers: H, body: { game: 'PUBG', duration: 2, max_devices: 1, custom_key: 'apione' } });
  ok(r.status === 201 && r.body?.keys?.[0]?.user_key === 'RESELLER1-2H-APIONE', 'API custom key is upper-cased');
  r = await request('/api/v1/keys', { method: 'POST', headers: H, body: { game: 'PUBG', duration: 2, max_devices: 1, custom_key: 'apione' } });
  ok(r.status === 409 && r.body?.error?.code === 'key_exists', 'a duplicate API custom key returns 409');
  r = await request('/api/v1/keys', { method: 'POST', headers: H, body: { game: 'PUBG', duration: 2, max_devices: 1, custom_key: 'x' } });
  ok(r.status === 400 && r.body?.error?.code === 'invalid_request', 'an invalid API custom key is rejected');
  r = await request(`/api/v1/keys/${apiKeyMade.toLowerCase()}`, { headers: H });
  ok(r.body?.key?.user_key === apiKeyMade, 'GET /api/v1/keys/{key} finds keys case-insensitively');
  r = await request(`/api/v1/keys/${apiKeyMade}`, { method: 'DELETE', headers: H });
  ok(r.body?.deleted === true && r.body?.refunded === 1 && r.body?.refund_total === 10, 'DELETE refunds a never-used key (₹10)');
  const idem = 'req-smoke-idem-0000001';
  r = await request('/api/v1/keys', { method: 'POST', headers: H, body: { game: 'PUBG', duration: 2, max_devices: 1, count: 2, request_id: idem } });
  const idemKeys = (r.body?.keys || []).map((k) => k.user_key).join();
  const idemSaldo = r.body?.saldo_after;
  r = await request('/api/v1/keys', { method: 'POST', headers: H, body: { game: 'PUBG', duration: 2, max_devices: 1, count: 2, request_id: idem } });
  ok(r.status === 201 && r.body?.saldo_after === idemSaldo && (r.body?.keys || []).map((k) => k.user_key).join() === idemKeys,
    'replaying a request_id returns the original batch without charging again');
  r = await request('/api/v1/keys/ANOY-1D-VIP01', { headers: H });
  ok(r.status === 404, "an API key cannot read someone else's key");
  r = await request('/api/v1/Server', { method: 'POST', headers: H, body: { modname: 'HACKED' } });
  ok(r.status === 404, 'API keys cannot reach server settings');
  await request('/logout');

  // 13. Owner sees the reseller, library + loader endpoints
  await login('Anoy', 'Anoy1764');
  r = await request('/admin/manage-users');
  ok(/reseller1/.test(r.body) && /Unlimited/.test(r.body), 'user list shows the reseller and the Owner as unlimited');
  r = await request('/api/lib/version');
  ok(r.status === 200 && r.body.trim() === '1.0', 'GET /api/lib/version serves plain text');
  r = await request('/api/lib/manifest');
  ok(r.body?.ok === true && r.body?.version === '1.0' && r.body?.available === false, 'manifest says nothing is published yet');
  const zip = Buffer.from('UEsDBAoAAAAAAA==', 'base64').toString('base64');
  r = await request('/lib', { method: 'POST', headers: SAME, body: { version: '1.1', file_name: 'test.zip', content_base64: zip, notes: 'smoke test' } });
  ok(r.body?.ok === true, 'POST /lib publishes a release');
  r = await request('/api/lib/manifest');
  ok(r.body?.version === '1.1' && r.body?.available === true && r.body?.source === 'file' && /^[a-f0-9]{64}$/.test(r.body?.sha256 || ''),
    'manifest reports the new version, size and sha256');
  r = await request('/api/lib/download');
  ok(r.status === 200 && r.response.headers.get('x-content-sha256') === md5Sha(Buffer.from(zip, 'base64')), 'download sends an integrity header');
  r = await request('/lib', { method: 'POST', headers: SAME, body: { action: 'link', version: '1.2', external_url: 'https://example.com/lib.so' } });
  ok(r.body?.ok === true && r.body?.mode === 'link', 'POST /lib link action bumps the version');
  r = await request('/api/lib/download');
  ok(r.status === 302 && r.location === 'https://example.com/lib.so', 'download follows the pushed link');
  r = await request('/api/lib/manifest');
  ok(r.body?.version === '1.2' && r.body?.source === 'link' && r.body?.available === true, 'manifest reports a link release');
  r = await request('/lib', { method: 'POST', headers: SAME, body: { action: 'link', version: 'bad version', external_url: 'https://example.com/lib.so' } });
  ok(r.status === 400 && /version/i.test(r.body?.error || ''), 'an invalid version is rejected');

  // 14. static assets
  r = await request('/assets/css/ui.css');
  ok(r.status === 200 && /--bg:\s*#000/.test(r.body) && !/gradient\(/.test(r.body), 'ui.css is served (black, no gradients)');
  r = await request('/assets/js/app-ui.js');
  ok(r.status === 200 && r.body.includes('initKeysList'), 'app-ui.js is served');
  r = await request('/favicon.svg');
  ok(r.status === 200, 'favicon.svg is served');
  r = await request('/assets/fonts/inter-latin-wght-normal.woff2');
  ok(r.status === 200, 'self-hosted font is served');

  // 15. logout
  r = await request('/logout');
  ok(r.status === 302 && r.location === '/login', 'GET /logout clears the session');
  r = await request('/dashboard');
  ok(r.status === 302 && r.location === '/login', 'session is invalid after logout');

  console.log(`\n=== ${passed} passed, ${failed} failed ===\n`);
  if (/request failed:|Error:/.test(serverLog)) {
    console.error('Server log contains errors:\n' + serverLog.split('\n').filter((l) => /error|failed/i.test(l)).slice(0, 20).join('\n'));
    failed += 1;
  }
} catch (e) {
  failed += 1;
  console.error('\n[smoke] fatal:', e.stack || e.message);
  console.error(serverLog);
} finally {
  server.kill('SIGTERM');
}

function md5Sha(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex');
}

function md5(value) {
  return crypto.createHash('md5').update(value).digest('hex');
}

process.exit(failed ? 1 : 0);
