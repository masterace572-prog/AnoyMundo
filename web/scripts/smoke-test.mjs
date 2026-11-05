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

  // 1. health
  let r = await request('/api/health');
  ok(r.status === 200 && r.body.ok === true, 'GET /api/health reports all checks OK', JSON.stringify(r.body));
  ok(r.body.checks?.schema?.ok === true, 'schema check sees all 13 tables');

  // 2. public pages + auth wall
  r = await request('/');
  ok(r.status === 200 && /Sign In/.test(r.body), 'GET / renders the original login page');
  ok(/>Anoy</.test(r.body) && !/𝙋𝙍𝙄𝙉𝘾𝙀|PRINCE PAPA/.test(r.body),
    'login page shows the Anoy brand (old name removed)');
  ok(/name="viewport"/.test(r.body) && /app\.css/.test(r.body),
    'login page has a responsive viewport and loads the design system');
  ok(!/linear-gradient|bg-gradient/.test(r.body) && !/ui-alert/.test(r.body),
    'login page is gradient-free and has no stale welcome banner');
  ok(/https:\/\/t\.me\/libAkAudioVisiual/.test(r.body) && /@libAkAudioVisiual/.test(r.body)
    && !/aalyanmods|AALYANMODS|AloneBoy_Boss/.test(r.body),
    'login page links the new Telegram handle');
  r = await request('/register');
  ok(r.status === 200 && /Create Account/.test(r.body), 'GET /register renders the flat sign-up form');
  ok(!/linear-gradient|bg-gradient/.test(r.body), 'register page is gradient-free');
  ok(/https:\/\/t\.me\/libAkAudioVisiual/.test(r.body) && /@libAkAudioVisiual/.test(r.body)
    && !/aalyanmods|AALYANMODS|AloneBoy_Boss/.test(r.body),
    'register page links the same Telegram handle');
  r = await request('/connect');
  ok(r.body?.web_info?._client === 'Anoy' && r.body?.web__dev?.author === '@libAkAudioVisiual'
    && r.body?.web__dev?.telegram === 'https://t.me/libAkAudioVisiual',
    'GET /connect info reports the Anoy brand and Telegram handle');
  r = await request('/dashboard');
  ok(r.status === 302 && r.location === '/login', 'protected page redirects anonymous visitor to /login');

  // 3. bad login
  r = await request('/login', { method: 'POST', body: new URLSearchParams({ username: 'admin', password: 'wrongpass' }) });
  ok(r.status === 302 && r.location === '/login', 'wrong password redirects back to login');
  r = await request('/login');
  ok(/Wrong password|check the form/.test(r.body), 'bad login shows the legacy flash error');

  // 4. good login (legacy bcrypt hash compatibility)
  r = await request('/login', { method: 'POST', body: new URLSearchParams({ username: 'admin', password: 'admin123', stay_log: 'yes' }) });
  ok(r.status === 302 && r.location === '/dashboard', 'admin / admin123 logs in');
  ok([...cookies.keys()].includes('am_session'), 'signed session cookie is issued');

  // 5. dashboard & all pages render
  r = await request('/dashboard');
  ok(r.status === 200 && /Dashboard/.test(r.body), 'GET /dashboard renders');
  ok(!/<\?php|<\?=/.test(r.body), 'no untranslated PHP tags leak into HTML');
  for (const route of ['/keys', '/keys/generate', '/keys/1', '/settings', '/Server',
    '/admin/manage-users', '/admin/create-referral', '/lib', '/api-keys', '/api-docs', '/docs']) {
    r = await request(route);
    ok(r.status === 200, `GET ${route} renders (${r.status})`);
    if (r.status !== 200) console.error(String(r.body).slice(0, 300));
    ok(/name="viewport"/.test(r.body) && !/linear-gradient|bg-gradient/.test(r.body),
      `GET ${route} is responsive and gradient-free`);
  }

  // Regression: the two "code showing as text" bugs reported by the user.
  r = await request('/keys/generate');
  ok(/Total Saldo/.test(r.body) && !/&lt;i class|bi-wallet|&lt;strong&gt;/.test(r.body),
    'saldo notice is plain text, not escaped HTML');
  r = await request('/settings');
  ok(!/<\?=|&lt;\?=|old\('fullname'\)/.test(r.body),
    'settings fullname field contains no PHP source code');
  ok(/name="fullname"[\s\S]{0,400}value="admin"/.test(r.body),
    'settings fullname field is prefilled with the saved value');

  // Regression: typed saldo digits must not sit underneath the currency icon.
  r = await request('/admin/user/1');
  ok(r.status === 200 && /class="relative field-prefix"/.test(r.body)
    && /class="field-prefix__icon"/.test(r.body),
    'edit-user saldo input reserves space for its currency icon');
  r = await request('/admin/create-referral');
  ok(/class="relative field-prefix"/.test(r.body),
    'referral set-saldo input reserves space for its currency icon');
  r = await request('/assets/css/app.css');
  ok(/\.field-prefix > input[\s\S]{0,160}padding-left:\s*2\.875rem\s*!important/.test(r.body),
    'app.css gives icon-prefixed inputs enough left padding');

  // Two separate in-panel docs pages.
  r = await request('/docs');
  ok(r.status === 200 && /Integration docs/.test(r.body) && /POST [^<]*\/connect/.test(r.body)
    && /Use in C\+\+/.test(r.body) && /curl_easy_perform/.test(r.body)
    && /expired_date/.test(r.body) && /mod_status/.test(r.body) && /Floating/.test(r.body)
    && !/linear-gradient|bg-gradient/.test(r.body),
    'GET /docs is the loader integration reference with fields and C++ code');
  ok(/<title>Anoy · Integration docs<\/title>/.test(r.body) && /nav-link is-active/.test(r.body),
    'integration docs use the Anoy title and highlight their sidebar link');
  r = await request('/api-docs');
  ok(r.status === 200 && /key management/i.test(r.body) && /X-API-Key/.test(r.body)
    && /\/api\/v1\/keys/.test(r.body) && /<title>Anoy · API docs<\/title>/.test(r.body),
    'GET /api-docs is the separate key-management API reference');

  // 5b. Personal API keys + /api/v1 key-management API.
  r = await request('/api/v1/keys');
  ok(r.status === 401 && r.body?.error?.code === 'missing_api_key',
    '/api/v1 refuses requests without an API key');
  r = await request('/api/v1/keys', { method: 'OPTIONS' });
  ok(r.status === 204 && r.response.headers.get('access-control-allow-origin') === '*',
    'OPTIONS /api/v1/keys answers CORS preflight for external sites');
  r = await request('/api-keys', { method: 'POST', body: new URLSearchParams({ action: 'create', name: 'smoke-bot' }) });
  ok(r.status === 302 && r.location === '/api-keys', 'panel creates a personal API key');
  r = await request('/api-keys');
  const apiToken = String(r.body).match(/amk_[A-Za-z0-9_-]{20,}/)?.[0] || '';
  ok(/smoke-bot/.test(r.body) && apiToken.length > 20, 'new API key is shown once on /api-keys');
  const H = { 'X-API-Key': apiToken };
  r = await request('/api/v1/me', { headers: H });
  ok(r.status === 200 && r.body?.ok === true && r.body?.account?.username === 'admin'
    && r.body?.api_key?.name === 'smoke-bot', 'GET /api/v1/me authenticates the API key');
  const saldoBefore = Number(r.body.account.saldo);
  r = await request('/api/v1/pricing', { headers: H });
  ok(r.body?.ok === true && r.body?.durations?.length === 7 && r.body?.games?.includes('PUBG'),
    'GET /api/v1/pricing returns the duration price list');
  r = await request('/api/v1/keys', { headers: H });
  ok(r.body?.ok === true && Array.isArray(r.body.keys) && r.body.keys.length >= 2,
    'GET /api/v1/keys lists the caller licence keys');
  r = await request('/api/v1/keys', { method: 'POST', headers: H,
    body: { game: 'PUBG', duration: 2, max_devices: 1 } });
  ok(r.status === 201 && r.body?.ok === true && r.body?.price === 10
    && r.body?.saldo_after === saldoBefore - 10 && r.body?.charged === 10,
    'POST /api/v1/keys creates a key and cuts saldo by the panel price');
  const madeKey = r.body?.keys?.[0]?.user_key || '';
  ok(/^admin-2-[A-Za-z0-9]{5}$/.test(madeKey), 'API-created key uses the legacy format');
  r = await request(`/api/v1/keys/${madeKey}`, { headers: H });
  ok(r.body?.ok === true && r.body?.key?.user_key === madeKey, 'GET /api/v1/keys/{key} reads one key');
  r = await request(`/api/v1/keys/${madeKey}`, { method: 'PATCH', headers: H, body: { max_devices: 3 } });
  ok(r.body?.ok === true && r.body?.key?.max_devices === 3, 'PATCH /api/v1/keys/{key} manages a key');
  r = await request(`/api/v1/keys/${madeKey}/reset`, { method: 'POST', headers: H });
  ok(r.body?.ok === true && r.body?.reset === true && r.body?.key?.device_count === 0,
    'POST /api/v1/keys/{key}/reset clears devices');
  r = await request(`/api/v1/keys/${madeKey}`, { method: 'DELETE', headers: H });
  ok(r.body?.ok === true && r.body?.deleted === true, 'DELETE /api/v1/keys/{key} deletes a key');
  r = await request(`/api/v1/keys/${madeKey}`, { headers: H });
  ok(r.status === 404 && r.body?.error?.code === 'key_not_found', 'deleted key is gone');
  r = await request('/api/v1/keys', { method: 'POST', headers: H,
    body: { game: 'PUBG', duration: 2, max_devices: 1, custom_key: 'x' } });
  ok(r.status === 400 && r.body?.error?.code === 'invalid_request', 'invalid custom_key is rejected');
  r = await request('/api/v1/keys', { method: 'POST', headers: H,
    body: { game: 'PUBG', duration: 2, max_devices: 1, count: 3 } });
  ok(r.status === 201 && r.body?.keys?.length === 3 && r.body?.price === 10
    && r.body?.saldo_after === saldoBefore - 20,
    'bulk count keeps the legacy one-fee-per-request pricing (docs and panel agree)');
  for (const bulk of r.body?.keys || []) {
    await request(`/api/v1/keys/${bulk.user_key}`, { method: 'DELETE', headers: H });
  }
  r = await request('/api/v1/keys', { method: 'POST', headers: { 'X-API-Key': 'amk_wrong' },
    body: { game: 'PUBG', duration: 2, max_devices: 1 } });
  ok(r.status === 401 && r.body?.error?.code === 'invalid_api_key', 'revoked/unknown API key is rejected');
  r = await request('/api/v1/Server', { method: 'POST', headers: H, body: { modname: 'HACKED' } });
  ok(r.status === 404 && r.body?.error?.code === 'not_found',
    'API keys cannot reach server/mod-name settings routes');
  r = await request('/api-keys', { method: 'POST', body: new URLSearchParams({ action: 'revoke', id: '1' }) });
  ok(r.status === 302, 'panel revokes a personal API key');
  r = await request('/api/v1/me', { headers: H });
  ok(r.status === 401 && r.body?.error?.code === 'invalid_api_key', 'revoked API key stops working');

  // 6. DataTables API
  r = await request('/keys/api?draw=3&start=0&length=10&order%5B0%5D%5Bcolumn%5D=0&order%5B0%5D%5Bdir%5D=desc');
  ok(r.body?.draw === 3 && r.body?.recordsTotal === 2 && r.body?.data?.length === 2,
    'GET /keys/api returns DataTables payload', JSON.stringify(r.body));
  r = await request('/admin/api/users?draw=1&start=0&length=10');
  ok(r.body?.data?.length === 1 && r.body?.data?.[0]?.level === 'Owner', 'GET /admin/api/users returns DataTables payload');

  // 7. generate keys
  r = await request('/keys/generate', { method: 'POST', body: new URLSearchParams({ game: 'PUBG', max_devices: '2', duration: '24', loopcount: '5', custominput: 'auto' }) });
  ok(r.status === 302 && r.location === '/keys/generate', 'POST /keys/generate redirects back');
  r = await request('/keys/generate');
  ok(/Key Generated Successfully/.test(r.body), 'generated key is displayed via flash');
  const match = String(r.body).match(/<textarea id="mytext"[^>]*>([^<]+)<\/textarea>/);
  const generatedKey = match?.[1]?.trim() || '';
  ok(/^admin-24-[A-Za-z0-9]{5}$/.test(generatedKey), `generated key has legacy format (${generatedKey})`);
  r = await request('/keys/api?draw=1&start=0&length=50');
  ok(r.body?.recordsTotal === 3, 'generated key persisted in the database');

  // 8. edit key
  r = await request('/keys/edit', { method: 'POST', body: new URLSearchParams({ id_keys: '1', game: 'PUBG', user_key: 'admin-2-lyK0Y', duration: '5', max_devices: '3', status: '1', registrator: 'admin', devices: 'AAA111\nBBB222', expired_date: '' }) });
  ok(r.status === 302, 'POST /keys/edit succeeds');
  r = await request('/keys/api?draw=1&start=0&length=50&search%5Bvalue%5D=lyK0Y');
  ok(r.body?.data?.[0]?.duration === '5 Hours' && r.body?.data?.[0]?.devices === 2,
    'key edit persists duration + device list', JSON.stringify(r.body?.data?.[0]));

  // 9. licensing API (exact legacy contract)
  r = await request('/connect', { method: 'POST', body: new URLSearchParams({ game: 'PUBG', user_key: generatedKey, serial: 'TESTDEVICE001' }) });
  ok(r.status === 200 && r.body?.status === true, 'POST /connect accepts a valid key');
  ok(r.body?.data?.token === md5(r.body?.data?.real || ''), '/connect token = md5(real)');
  ok(r.body?.data?.modname === 'VIP MOD' && r.body?.data?.ESP === 'on', '/connect returns mod metadata + feature flags');
  ok(!!r.body?.data?.EXP && !!r.body?.data?.expired_date, '/connect returns expiry fields');
  r = await request('/connect', { method: 'POST', body: new URLSearchParams({ game: 'PUBG', user_key: generatedKey, serial: 'TESTDEVICE001' }) });
  ok(r.body?.status === true, 'same serial can re-authenticate');
  r = await request('/connect', { method: 'POST', body: new URLSearchParams({ game: 'PUBG', user_key: 'does-not-exist', serial: 'TESTDEVICE002' }) });
  ok(r.body?.status === false && r.body?.reason === 'USER OR GAME NOT REGISTERED', 'unknown key is rejected');
  r = await request('/connect', { method: 'POST', body: new URLSearchParams({ game: 'PUBG', user_key: generatedKey, serial: 'BAD|SERIAL' }) });
  ok(r.body?.status === false && r.body?.reason === 'Bad Parameter', 'malformed serial is rejected');

  // 10. maintenance + feature toggles
  r = await request('/Server', { method: 'POST', body: new URLSearchParams({ status_form: '1', radios: 'on', myInput: 'Under maintenance' }) });
  ok(r.status === 302, 'POST /Server maintenance toggle succeeds');
  r = await request('/connect', { method: 'POST', body: new URLSearchParams({ game: 'PUBG', user_key: generatedKey, serial: 'X1' }) });
  ok(r.body?.status === true && /maintenance/i.test(r.body?.reason || ''), '/connect honours maintenance mode');
  r = await request('/Server', { method: 'POST', body: new URLSearchParams({ status_form: '1', myInput: '' }) });
  ok(r.status === 302, 'POST /Server maintenance off succeeds');
  r = await request('/Server', { method: 'POST', body: new URLSearchParams({ feature_form: '1', AIM: 'on' }) });
  ok(r.status === 302, 'POST /Server feature toggle succeeds');
  r = await request('/connect', { method: 'POST', body: new URLSearchParams({ game: 'PUBG', user_key: generatedKey, serial: 'TESTDEVICE001' }) });
  ok(r.body?.data?.ESP === 'off' && r.body?.data?.AIM === 'on', 'feature toggles reach the licensing API');
  await request('/Server', { method: 'POST', body: new URLSearchParams({ feature_form: '1', ESP: 'on', Item: 'on', AIM: 'on', SilentAim: 'on', BulletTrack: 'on', Memory: 'on', Floating: 'on', Setting: 'on' }) });

  // 11. reset / delete
  r = await request('/keys/reset?userkey=' + encodeURIComponent(generatedKey) + '&reset=1');
  ok(r.body?.registered === true && r.body?.reset === true && r.body?.devices_total === 0, 'GET /keys/reset clears devices');
  r = await request('/keys/resetAll?userkey=admin-2-f7VwE&reset=1');
  ok(r.body?.deleted === true, 'GET /keys/resetAll deletes a key');

  // 12. referral -> registration -> reseller login
  r = await request('/admin/create-referral', { method: 'POST', body: new URLSearchParams({ set_saldo: '500', accExpire: '30', accLevel: '3' }) });
  ok(r.status === 302, 'POST /admin/create-referral creates a code');
  r = await request('/admin/create-referral');
  const codeMatch = String(r.body).match(/Referral : ([A-Za-z0-9]{6})/);
  const referral = codeMatch?.[1] || '';
  ok(!!referral, `referral code shown in flash (${referral})`);
  // Registration is for anonymous visitors (logged-in users are redirected).
  await request('/logout');
  r = await request('/register', { method: 'POST', body: new URLSearchParams({ email: 'reseller@example.com', username: 'reseller1', fullname: 'ResellerOne', password: 'secret123', password2: 'secret123', referral }) });
  ok(r.status === 302 && r.location === '/login', `registration with referral code succeeds (status ${r.status}, loc ${r.location})`);
  r = await request('/login', { method: 'POST', body: new URLSearchParams({ username: 'reseller1', password: 'secret123' }) });
  ok(r.status === 302 && r.location === '/dashboard', 'the new reseller can log in');
  r = await request('/dashboard');
  ok(r.status === 200 && /Reseller/.test(r.body), 'reseller dashboard shows the Reseller role');
  await request('/logout');
  await request('/login', { method: 'POST', body: new URLSearchParams({ username: 'admin', password: 'admin123' }) });
  r = await request('/admin/api/users?draw=1&start=0&length=10&search%5Bvalue%5D=reseller1');
  ok(r.body?.data?.[0]?.level === 'Reseller' && Number(r.body?.data?.[0]?.saldo) === 500, 'new user inherits referral level + saldo');

  // 13. library / loader endpoints
  r = await request('/api/lib/version');
  ok(r.status === 200 && r.body.trim() === '1.0', 'GET /api/lib/version serves plain-text version');
  const zip = Buffer.from('UEsDBAoAAAAAAA==', 'base64').toString('base64');
  r = await request('/lib', { method: 'POST', body: { version: '1.1', file_name: 'test.zip', content_base64: zip, notes: 'smoke test' } });
  ok(r.body?.ok === true, 'POST /lib uploads a release to the database');
  r = await request('/api/lib/version');
  ok(r.body.trim() === '1.1', 'version endpoint reflects the new release');

  // 14. static assets
  r = await request('/assets/css/tailwind.css');
  ok(r.status === 200 && r.body.includes('.bg-purple-600'), 'compiled Tailwind CSS is served statically');
  r = await request('/assets/css/app.css');
  ok(r.status === 200 && !/linear-gradient|radial-gradient/.test(r.body) && r.body.includes('--accent'),
    'flat app.css design system is served without gradients');
  r = await request('/assets/js/app-ui.js');
  ok(r.status === 200 && r.body.includes('table-scroll'), 'responsive table helper is served');
  r = await request('/assets/fonts/inter-latin-wght-normal.woff2');
  ok(r.status === 200, 'self-hosted Inter font is served statically');

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

function md5(value) {
  return crypto.createHash('md5').update(value).digest('hex');
}

process.exit(failed ? 1 : 0);
