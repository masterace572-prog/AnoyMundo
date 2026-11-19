// HTTP integration + archive adversarial tests. Offline only: no live owner,
// referral, licence, storage object or release is touched.
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { Script } from 'node:vm';
import { test, before, after } from 'node:test';
import crypto from 'node:crypto';
import { zipSync, unzipSync } from 'fflate';
import { normalizeLibrary, MAX_LIBRARY_BYTES, LIBRARY_BUCKET } from '../src/library-package.mjs';

process.env.DB_DRIVER = 'memory';
process.env.APK_VERIFY_STUB = '1';
process.env.NODE_ENV = 'test';
process.env.AUTH_SECRET = crypto.randomBytes(32).toString('hex');
const { handle } = await import('../src/app.mjs');
const { db } = await import('../src/db.mjs');
const { hashPassword } = await import('../src/auth.mjs');
const { publicChannel } = await import('../src/channels.mjs');
const { pickLatestBuild } = await import('../src/app-release.mjs');
let base;
let server;
const users = () => db()._store.users;
const active = (table, channel) => db()._store[table].find(r => r.channel === channel && r.is_current);
const elf = (size = 128, fill = 0) => {
  const out = Buffer.alloc(size, fill);
  out.set([0x7f, 0x45, 0x4c, 0x46, 2, 1]); out[18] = 183; out[19] = 0;
  return out;
};
const sha = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
function client() {
  const jar = new Map();
  return {
    async request(route, { method = 'GET', body, redirect = 'manual', raw = false } = {}) {
      const headers = { Cookie: [...jar].map(([k, v]) => `${k}=${v}`).join('; '), 'sec-fetch-site': 'same-origin' };
      if (body instanceof URLSearchParams) headers['Content-Type'] = 'application/x-www-form-urlencoded';
      else if (body !== undefined && !raw) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(body); }
      const response = await fetch(new URL(route, base), { method, headers, body, redirect });
      for (const cookie of response.headers.getSetCookie()) {
        const [pair] = cookie.split(';'); const pos = pair.indexOf('=');
        const k = pair.slice(0, pos), v = pair.slice(pos + 1);
        if (v) jar.set(k, v); else jar.delete(k);
      }
      const type = response.headers.get('content-type') || '';
      const data = type.includes('application/json') ? await response.json()
        : type.includes('application/zip') ? Buffer.from(await response.arrayBuffer()) : await response.text();
      return { status: response.status, data, headers: response.headers, location: response.headers.get('location') };
    },
    async post(route, body) { return this.request(route, { method: 'POST', body }); },
    async login(username, password) {
      const r = await this.post('/login', new URLSearchParams({ username, password }));
      assert.equal(r.location, '/dashboard', 'test user can sign in');
    },
  };
}
const anoy = client(), kenny = client(), admin = client(), anon = client();
let second;
let ownerInvite;

before(async () => {
  server = http.createServer((req, res) => {
    const fn = (req.url || '').startsWith('/_offline/storage/') ? db().storage.handle.bind(db().storage) : handle;
    Promise.resolve(fn(req, res)).catch(e => { console.error(e); res.statusCode = 500; res.end('unhandled'); });
  });
  server.listen(0, '0.0.0.0');
  await once(server, 'listening');
  base = `http://127.0.0.1:${server.address().port}`;
  await anoy.login('Anoy', 'Anoy1764');
  await db().from('users').insert({ username: 'TestAdmin', fullname: 'Test Admin', level: 2, saldo: 0, status: 1, password: hashPassword('OfflineTest97') });
  await admin.login('TestAdmin', 'OfflineTest97');
});
after(async () => { server?.closeAllConnections(); if (server?.listening) await new Promise(resolve => server.close(resolve)); });

test('public channels default safely for old loaders and reject unknown/prototype/duplicate values', async () => {
  assert.equal(publicChannel(new URLSearchParams()), 'anoy');
  for (const bad of ['third', '__proto__', 'toString', 'constructor', 'ANOY']) {
    for (const route of ['/api/lib/version', '/api/lib/manifest', '/api/lib/download', '/lib.so', '/version.txt', '/api/app/update']) {
      assert.equal((await anon.request(`${route}?channel=${bad}`)).status, 400, `${route} rejects ${bad}`);
    }
  }
  assert.equal((await anon.request('/api/lib/version?channel=anoy&channel=kenny')).status, 400);
  assert.equal((await anon.request('/api/lib/version')).data.trim(), '1.0');
  assert.equal((await anon.request('/api/lib/version?channel=kenny')).data.trim(), '0.0');
  assert.equal((await anon.request('/api/lib/manifest?channel=kenny')).data.available, false);
});

test('only Anoy can issue one pending owner referral, with expiry and no account balance/expiry', async () => {
  assert.match((await anoy.request('/admin/create-referral')).data, /Owner — KennyPvt/);
  await admin.post('/admin/create-referral', { accLevel: 1 });
  assert.equal(db()._store.referral_code.filter(c => Number(c.level) === 1).length, 0, 'Admin cannot forge Owner role');
  await Promise.all([anoy.post('/admin/create-referral', { accLevel: 1 }), anoy.post('/admin/create-referral', { accLevel: 1 })]);
  const invites = db()._store.referral_code.filter(c => Number(c.level) === 1);
  assert.equal(invites.length, 1, 'concurrent invitation requests reuse a single code');
  ownerInvite = invites[0];
  assert.equal(ownerInvite.owner_channel, 'kenny');
  assert.equal(ownerInvite.created_by, 'Anoy');
  assert.ok(new Date(ownerInvite.invite_expires_at) > Date.now());
  assert.equal(ownerInvite.acc_expiration, null);
  assert.equal(ownerInvite.set_saldo, 0);

  ownerInvite.invite_expires_at = new Date(Date.now() - 1000).toISOString();
  await anon.post('/register', signup('ExpiredOwner', ownerInvite.referral));
  assert.equal(users().filter(u => u.level === 1).length, 1, 'expired invite cannot register an owner');
  await anoy.post('/admin/create-referral', { accLevel: 1 });
  const fresh = db()._store.referral_code.find(c => Number(c.level) === 1 && !c.used_by);
  assert.notEqual(fresh.code, ownerInvite.code, 'expired invite is replaced');
  ownerInvite = fresh;
});

function signup(username, referral) {
  return new URLSearchParams({ username, referral, email: `${username}@example.com`, fullname: 'Kenny Owner', password: 'OpaqueSignal93', password2: 'OpaqueSignal93' });
}

test('parallel registration consumes the owner invitation once; neither owner can create a third', async () => {
  const a = client(), b = client();
  const responses = await Promise.all([a.post('/register', signup('KennyPvt', ownerInvite.referral.toLowerCase())), b.post('/register', signup('KennyRace', ownerInvite.referral))]);
  assert.equal(responses.filter(r => r.location === '/login').length, 1);
  assert.equal(responses.filter(r => r.location === '/register').length, 1);
  second = users().find(u => u.owner_channel === 'kenny');
  assert.ok(second);
  assert.equal(second.level, 1);
  assert.equal(second.expiration_date, null);
  assert.equal(second.saldo, 0);
  assert.equal(users().filter(u => u.level === 1).length, 2);
  await kenny.login(second.username, 'OpaqueSignal93');
  assert.match((await kenny.request('/dashboard')).data, /Unlimited/);
  const roleSelect = (await anoy.request('/admin/create-referral')).data.match(/<select[^>]+name="accLevel"[^>]*>(.*?)<\/select>/s)?.[1];
  assert.ok(roleSelect && !/value="1"/.test(roleSelect), 'Owner role disappears when both slots are filled');
  for (const actor of [anoy, kenny, admin]) await actor.post('/admin/create-referral', { accLevel: 1 });
  await anon.post('/register', signup('ThirdOwner', ownerInvite.referral));
  assert.equal(users().filter(u => u.level === 1).length, 2, 'no third account or referral replay');
});

test('owners cannot view/edit/delete each other through user management or promote another user', async () => {
  const first = users().find(u => u.owner_channel === 'anoy');
  const snapshot = [first, second].map(u => ({ ...u }));
  for (const [actor, target] of [[anoy, second], [kenny, first]]) {
    assert.equal((await actor.request(`/admin/user/${target.id_users}`)).location, '/admin/manage-users');
    await actor.post(`/admin/user/${target.id_users}`, { user_id: target.id_users, username: 'Takeover', password: 'ChangedPass93', level: 3, status: 2 });
    await actor.request(`/admin/user/delete/${target.id_users}`);
  }
  assert.deepEqual([first, second], snapshot);
  const target = users().find(u => u.level === 2);
  await kenny.post(`/admin/user/${target.id_users}`, { user_id: target.id_users, username: target.username, fullname: 'Test Admin', level: 1, saldo: 0, status: 1, uplink: 'Anoy' });
  assert.equal(target.level, 2);
  assert.equal((await admin.request('/app-update')).location, '/dashboard');
  assert.equal((await admin.request('/app-update/latest-build')).status, 403);
  assert.equal((await anon.post('/lib', { action: 'prepare_upload', file_size: 128 })).location, '/login');
});

test('both .so and ZIP normalize any main filename to libbgmi.so, preserving valid sibling dependencies', () => {
  const raw = elf();
  assert.deepEqual(Buffer.from(unzipSync(normalizeLibrary(raw))['libbgmi.so']), raw);
  const zipped = zipSync({ 'folder/custom name.so': raw, 'readme.txt': Buffer.from('not a library') });
  assert.deepEqual(Object.keys(unzipSync(normalizeLibrary(zipped))), ['libbgmi.so']);
  const siblings = unzipSync(normalizeLibrary(zipSync({ 'dir/libbgmi.so': raw, 'dir/libdep.so': elf(129), 'elsewhere/ignore.so': elf(130) })));
  assert.deepEqual(Object.keys(siblings).sort(), ['libbgmi.so', 'libdep.so']);
  assert.deepEqual(normalizeLibrary(raw), normalizeLibrary(raw), 'normalization is deterministic');
  assert.throws(() => normalizeLibrary(elf(MAX_LIBRARY_BYTES + 1)), /10 MB/);
  assert.throws(() => normalizeLibrary(Buffer.from('this is not a library')), /arm64/);
  const wrongAbi = elf(); wrongAbi[18] = 62;
  assert.throws(() => normalizeLibrary(wrongAbi), /arm64/);
  assert.throws(() => normalizeLibrary(zipSync({ 'one.so': raw, 'two.so': raw })), /identifiable/);
  assert.throws(() => normalizeLibrary(zipSync({ '../escape.so': raw })), /unsafe/);
  assert.throws(() => normalizeLibrary(zipSync({ 'C:/escape.so': raw })), /unsafe/);
  assert.throws(() => normalizeLibrary(zipSync(Object.fromEntries(Array.from({ length: 129 }, (_, i) => [`${i}.txt`, new Uint8Array()])))), /128 entry/);
  assert.throws(() => normalizeLibrary(zipSync({ 'too-big.so': elf(MAX_LIBRARY_BYTES + 1) })), /expanded/);
  // A forged size must not bypass the ACTUAL inflation cap.
  const lie = Buffer.from(zipSync({ 'bomb.so': elf(MAX_LIBRARY_BYTES + 1) }));
  const cd = lie.indexOf(Buffer.from([0x50, 0x4b, 0x01, 0x02]));
  lie.writeUInt32LE(64, 22); lie.writeUInt32LE(64, cd + 24);
  assert.throws(() => normalizeLibrary(lie), /invalid|damaged/);
  const badCrc = Buffer.from(zipSync({ 'ok.so': raw }, { level: 0 })); badCrc[40] ^= 1;
  assert.throws(() => normalizeLibrary(badCrc), /invalid|damaged/);
});

async function prepare(actor, bytes, name = 'any-name.so') {
  const r = await actor.post('/lib', { action: 'prepare_upload', file_name: name, file_size: bytes.length });
  assert.equal(r.status, 200);
  assert.ok(r.data.upload_url && r.data.upload_path);
  const upload = await anon.request(r.data.upload_url, { method: 'PUT', body: bytes, raw: true });
  assert.equal(upload.status, 200, 'direct signed upload accepts binary, not a base64 panel request');
  return r.data;
}
async function manifest(channel) { return (await anon.request(`/api/lib/manifest?channel=${channel}`)).data; }

test('10 MiB direct upload succeeds and the other owner cannot adopt, overwrite or delete its pending file', async () => {
  const raw = elf(MAX_LIBRARY_BYTES);
  const ticket = await prepare(anoy, raw, 'my-own-library.so');
  assert.match(ticket.upload_path, /^anoy\/pending\//);
  assert.equal((await anon.request(ticket.upload_url)).status, 403, 'write ticket is not a read grant');
  assert.equal((await anon.request(ticket.upload_url, { method: 'PUT', body: raw, raw: true })).status, 409, 'signed source is not upsertable');
  assert.equal((await kenny.post('/lib', { action: 'upload', upload_path: ticket.upload_path, version: '9.9' })).status, 403);
  assert.equal((await kenny.post('/lib', { action: 'cancel_upload', upload_path: ticket.upload_path })).status, 403);
  assert.ok((await db().storage.from(LIBRARY_BUCKET).download(ticket.upload_path)).data, 'denied cross-owner request does not delete the source');
  const published = await anoy.post('/lib', { action: 'upload', upload_path: ticket.upload_path, version: '1.1', game_version: '4.7.0' });
  assert.equal(published.status, 200); assert.equal(published.data.file_name, 'libbgmi.so');
  const current = await manifest('anoy');
  assert.equal(current.version, '1.1'); assert.equal(current.channel, 'anoy');
  assert.equal(current.sha256, sha(normalizeLibrary(raw)));
  const redirect = await anon.request('/api/lib/download');
  assert.equal(redirect.status, 302); assert.equal(redirect.headers.get('cache-control'), 'no-store');
  const file = await anon.request(redirect.location);
  assert.equal(file.status, 200); assert.equal(sha(file.data), current.sha256);
  assert.deepEqual(Buffer.from(unzipSync(file.data)['libbgmi.so']), raw);
  assert.equal((await manifest('kenny')).available, false, 'new owner never falls back to Anoy library');
  assert.ok((await db().storage.from(LIBRARY_BUCKET).download(ticket.upload_path)).error, 'source is cleaned after publication');
  const cancel = await prepare(anoy, elf());
  assert.equal((await anoy.post('/lib', { action: 'cancel_upload', upload_path: cancel.upload_path })).status, 200);
  assert.ok((await db().storage.from(LIBRARY_BUCKET).download(cancel.upload_path)).error);
  assert.equal((await anoy.post('/lib', { action: 'cancel_upload', upload_path: active('client_release', 'anoy').storage_path })).status, 403, 'cancel cannot delete a live release');
  assert.equal((await anoy.post('/lib', { action: 'prepare_upload', file_size: MAX_LIBRARY_BYTES + 1 })).status, 400);
  assert.equal((await anoy.post('/lib', { action: 'upload', version: '2', content_base64: 'a'.repeat(4_194_305) })).status, 413, 'legacy base64 cannot bypass direct-upload limits');
});

test('a ZIP larger than the old upload cap publishes only to Kenny; invalid uploads keep both live releases intact', async () => {
  const raw = crypto.randomBytes(4 * 1024 * 1024); raw.set(elf(64));
  const archive = Buffer.from(zipSync({ 'wrapped/random-name.so': raw }));
  assert.ok(archive.length > 3 * 1024 * 1024);
  const ticket = await prepare(kenny, archive, 'something.zip');
  const anoyBefore = await manifest('anoy');
  const published = await kenny.post('/lib?channel=anoy', { action: 'upload', upload_path: ticket.upload_path, version: '7.1', notes: 'Kenny only' });
  assert.equal(published.status, 200); assert.equal(published.data.channel, 'kenny');
  const health = await anon.request('/api/health');
  assert.equal(health.status, 200);
  assert.equal(health.data.checks.lib_channel.ok, true, 'health accepts two current releases instead of a single-row query');
  assert.match(health.data.checks.lib_channel.detail, /anoy: 1\.1.*kenny: 7\.1/);
  assert.match(health.data.checks.schema.detail, /15\/15/);
  const kennyBefore = await manifest('kenny');
  assert.equal(kennyBefore.version, '7.1'); assert.equal(kennyBefore.file_name, 'libbgmi.so');
  assert.deepEqual(await manifest('anoy'), anoyBefore, 'query parameters cannot redirect an owner write');
  assert.doesNotMatch((await kenny.request('/lib?channel=anoy')).data, /value="1\.1"/);
  for (const actor of [anoy, kenny]) {
    const bad = await prepare(actor, Buffer.alloc(64));
    const r = await actor.post('/lib', { action: 'upload', upload_path: bad.upload_path, version: '100' });
    assert.equal(r.status, 400);
    assert.ok((await db().storage.from(LIBRARY_BUCKET).download(bad.upload_path)).error);
  }
  assert.deepEqual(await manifest('anoy'), anoyBefore);
  assert.deepEqual(await manifest('kenny'), kennyBefore);
});

test('all library and APK writes reject a forged other-owner channel in BOTH directions', async () => {
  const before = ['anoy', 'kenny'].map(c => ({ ...active('client_release', c) }));
  for (const [actor, other] of [[anoy, 'kenny'], [kenny, 'anoy']]) {
    for (const action of ['prepare_upload', 'cancel_upload', 'upload', 'link', 'reset', 'game']) {
      for (const field of ['channel', 'owner_channel']) {
        assert.equal((await actor.post('/lib', { [field]: other, action, version: '99', file_size: 64, game_version: '99.9', external_url: 'https://example.com/a.so' })).status, 403);
      }
    }
    for (const action of ['publish', 'remove', 'withdraw']) {
      assert.equal((await actor.post('/app-update', { action, channel: other, version_code: 99 })).status, 403);
    }
  }
  assert.deepEqual(['anoy', 'kenny'].map(c => active('client_release', c)), before);
  await kenny.post('/lib', { action: 'game', game_version: '4.8.0' });
  await kenny.post('/lib', { action: 'reset' });
  assert.equal((await manifest('anoy')).game_version, '4.7.0');
  assert.equal((await manifest('anoy')).version, '1.1');
  assert.equal((await manifest('kenny')).version, '0.1');
  assert.equal((await manifest('kenny')).game_version, '4.8.0');
});

test('APK publications, removals and latest-build assets are separate; old clients stay on Anoy', async () => {
  const publish = async (actor, name, code) => actor.post('/app-update', { action: 'publish', version_name: name, version_code: code, apk_url: 'https://github.com/o/r/releases/download/v1/app.apk', changelog: `Update ${name}`, mandatory: true });
  assert.equal((await publish(anoy, '2.4.0', 18)).status, 200);
  assert.equal((await publish(kenny, '1.0.0', 1)).status, 200, 'Kenny version is independent of Anoy code');
  const first = (await anon.request('/api/app/update?code=17')).data;
  const secondUpdate = (await anon.request('/api/app/update?channel=kenny&code=1')).data;
  assert.equal(first.channel, 'anoy'); assert.equal(first.version_code, 18); assert.equal(first.update_available, true);
  assert.equal(secondUpdate.channel, 'kenny'); assert.equal(secondUpdate.version_code, 1); assert.equal(secondUpdate.update_available, false);
  assert.match((await anoy.request('/app-update/latest-build')).data.apk_url, /\/Anoy-XLOX-loader\.apk$/);
  assert.match((await kenny.request('/app-update/latest-build?channel=anoy')).data.apk_url, /\/KennyPvt-loader\.apk$/);
  const releases = [{ tag_name: 'loaders-v2.4.0-18', assets: [
    { name: 'Anoy-XLOX-loader.apk', browser_download_url: 'https://github.com/o/r/Anoy.apk' },
    { name: 'KennyPvt-loader.apk', browser_download_url: 'https://github.com/o/r/Kenny.apk' },
  ] }];
  assert.match(pickLatestBuild(releases, 'anoy').apk_url, /Anoy/);
  assert.match(pickLatestBuild(releases, 'kenny').apk_url, /Kenny/);
  assert.equal(pickLatestBuild([{ tag_name: 'anoy-v2.3.8-17', assets: releases[0].assets.slice(0, 1) }], 'kenny'), null);
  await kenny.post('/app-update', { action: 'remove' });
  assert.equal((await anon.request('/api/app/update?channel=kenny')).data.available, false);
  assert.equal((await anon.request('/api/app/update?code=17')).data.version_code, 18);
});

test('keys, unlimited-owner billing and general settings remain shared', async () => {
  const r = await kenny.post('/keys/generate', new URLSearchParams({ game: 'PUBG', duration: '24', max_devices: '1', count: '1', request_id: 'kenny-shared-keys-test' }));
  assert.equal(r.status, 302);
  const key = db()._store.keys_code.find(k => k.registrator === second.username);
  assert.ok(key); assert.equal(key.price_paid, 0);
  const keys = (await anoy.request('/keys/api')).data;
  assert.ok(JSON.stringify(keys).includes(key.user_key), 'Anoy still sees shared keys from the second owner');
  const connected = await anon.post('/connect', new URLSearchParams({ game: 'PUBG', user_key: key.user_key, serial: 'shared-device-123456' }));
  assert.equal(connected.data.status, true, 'shared /connect accepts the second owner key');
  await kenny.post('/server', new URLSearchParams({ floating_form: '1', _ftext: 'Shared status remains shared', _ftextr: 'Safe' }));
  assert.match((await anoy.request('/server')).data, /Shared status remains shared/);
  assert.equal(users().filter(u => u.level === 1).length, 2);
});

// Parsing real rendered inline JS catches browser syntax errors that plain
// Handlebars compilation cannot detect. No DOM/network execution is needed.
test('release-page browser scripts parse after rendering; file uploads never use base64', async () => {
  for (const route of ['/lib', '/app-update', '/admin/create-referral']) {
    const { data } = await kenny.request(route);
    for (const match of data.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) new Script(match[1], { filename: route });
    if (route === '/lib') {
      assert.ok(data.includes('prepare_upload') && data.includes("xhr.open('PUT'"));
      assert.doesNotMatch(data, /readAsDataURL|FileReader/);
    }
  }
});

test('a failed normalized-object write cleans private output/source and never changes either live release', async () => {
  const before = await Promise.all(['anoy', 'kenny'].map(manifest));
  const ticket = await prepare(kenny, elf(512));
  const storage = db().storage;
  const from = storage.from;
  let output;
  storage.from = bucket => {
    const api = from.call(storage, bucket);
    return { ...api, async upload(path, bytes, options) {
      output = path;
      // Emulate Storage accepting a PUT, then losing its success response.
      await api.upload(path, bytes, options);
      return { data: null, error: { message: 'Simulated Storage transport failure' } };
    } };
  };
  try {
    const response = await kenny.post('/lib', { action: 'upload', version: 'fail', upload_path: ticket.upload_path });
    assert.equal(response.status, 500);
    assert.match(response.data.error, /previous release is still active/);
  } finally { storage.from = from; }
  const api = storage.from(LIBRARY_BUCKET);
  assert.ok((await api.download(ticket.upload_path)).error, 'failed source removed');
  assert.ok((await api.download(output)).error, 'never-published private output removed even after an ambiguous Storage PUT');
  assert.deepEqual(await Promise.all(['anoy', 'kenny'].map(manifest)), before);
});

test('simultaneous publications leave exactly one current release per owner with no mixed content', async () => {
  const requests = await Promise.all([['anoy', anoy], ['kenny', kenny]].flatMap(([channel, actor]) => [1, 2].map(async n => {
    const bytes = elf(128 + n, channel === 'anoy' ? 11 : 22);
    const ticket = await prepare(actor, bytes);
    return { channel, actor, ticket, bytes, version: `parallel-${n}` };
  })));
  const responses = await Promise.all(requests.map(({ actor, ticket, version }) => actor.post('/lib', {
    action: 'upload', upload_path: ticket.upload_path, version,
  })));
  assert.ok(responses.every(response => response.status === 200));
  for (const channel of ['anoy', 'kenny']) {
    assert.equal(db()._store.client_release.filter(row => row.channel === channel && row.is_current).length, 1);
    const current = await manifest(channel);
    assert.ok(requests.filter(item => item.channel === channel).some(item => sha(normalizeLibrary(item.bytes)) === current.sha256));
    const redirect = await anon.request(`/api/lib/download?channel=${channel}`);
    const download = await anon.request(redirect.location);
    assert.equal(sha(download.data), current.sha256, 'a concurrent winner never points at a deleted object');
  }
  const appPosts = await Promise.all([anoy, kenny].flatMap(actor => [1, 2].map(() => actor.post('/app-update', {
    action: 'publish', version_name: '2.4.1', version_code: 19, apk_url: 'https://example.com/update.apk', changelog: 'Concurrent publish',
  }))));
  assert.equal(appPosts.filter(response => response.status === 200).length, 2, 'same code can win once in EACH channel');
  assert.equal(appPosts.filter(response => response.status === 409 || response.status === 400).length, 2, 'duplicate concurrent code loses cleanly');
  for (const channel of ['anoy', 'kenny']) assert.equal(db()._store.app_release.filter(row => row.channel === channel && row.is_current).length, 1);
});
