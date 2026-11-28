import assert from 'node:assert/strict';
import { test } from 'node:test';
import fs from 'node:fs/promises';
import { ownerStorageChecks } from './live-channel-checks.mjs';

function fixture({ putStatus = 200, putThrows = false } = {}) {
  const calls = [], checks = [], selections = [
    { channel: 'anoy', version: 'keep-anoy', sha256: 'unchanged-anoy' },
    { channel: 'kenny', version: 'keep-kenny', sha256: 'unchanged-kenny' },
  ];
  let pending = false, cancelled = false;
  const path = 'anoy/pending/99999999-1111-4222-8333-444444444444';
  const storage = 'https://storage.example/upload';
  const ok = (condition, message) => checks.push({ condition, message });
  const request = async (route, options = {}) => {
    calls.push({ route, ...options });
    if (route.startsWith('/api/lib/manifest?') || route.startsWith('/api/app/update?')) {
      const channel = new URL(route, 'https://panel.example').searchParams.get('channel');
      return { status: 200, body: { ...selections.find(row => row.channel === channel) } };
    }
    if (route === '/lib' && !options.body) return { status: 200,
      body: '<main data-release-channel="anoy">up to 20 MB uploaded and 20 MB total unpacked</main>',
      response: new Response('', { headers: { 'content-security-policy': "connect-src 'self' https://storage.example" } }),
    };
    assert.equal(route, '/lib'); assert.equal(options.method, 'POST');
    const { action, channel, file_size, upload_path } = options.body;
    // Model Anoy as Real Owner: a reset of EITHER real channel WOULD succeed.
    // Such a request must therefore never be sent by live acceptance.
    assert.ok(['prepare_upload', 'cancel_upload'].includes(action), `Forbidden live mutation: ${action}`);
    if (action === 'cancel_upload') {
      assert.equal(upload_path, path); pending = false; cancelled = true;
      return { status: 200, body: { cancelled: true } };
    }
    if (channel === 'not-a-channel') return { status: 403, body: { ok: false } };
    if (file_size > 20 * 1024 * 1024) return { status: 400, body: { error: 'Choose up to 20 MB.' } };
    assert.equal(file_size, 20 * 1024 * 1024); pending = true;
    return { status: 200, body: { ok: true, upload_path: path, upload_url: storage, max_bytes: file_size } };
  };
  const fetchImpl = async (url, options) => {
    assert.equal(String(url), storage); assert.equal(options.method, 'PUT');
    assert.equal(options.body.length, 20 * 1024 * 1024);
    assert.equal(options.headers.Cookie, undefined, 'never forward a panel session to Storage');
    if (putThrows) throw new Error('fixture transport failure');
    return new Response('', { status: putStatus });
  };
  return { calls, checks, request, ok, fetchImpl, cancelled: () => cancelled, pending: () => pending };
}

test('live 20 MiB acceptance never resets/publishes either channel, including for Real Owner', async () => {
  const f = fixture();
  assert.equal(await ownerStorageChecks(f.request, f.ok, 'https://panel.example', { fetchImpl: f.fetchImpl }), true);
  assert.equal(f.pending(), false); assert.equal(f.cancelled(), true);
  assert.ok(f.checks.length >= 8); assert.ok(f.checks.every(check => check.condition));
  assert.equal(f.calls.filter(call => call.route.startsWith('/api/')).length, 8,
    'both libraries and both APK selections are read before AND after, not changed');
});

test('real Storage refusal still cleans up the temporary ticket and reports failure', async () => {
  const f = fixture({ putStatus: 413 });
  assert.equal(await ownerStorageChecks(f.request, f.ok, 'https://panel.example', { fetchImpl: f.fetchImpl }), false);
  assert.equal(f.pending(), false); assert.equal(f.cancelled(), true);
  assert.equal(f.checks.filter(check => !check.condition).length, 1);
});

test('a failed PUT transport still removes its private pending object', async () => {
  const f = fixture({ putThrows: true });
  await assert.rejects(ownerStorageChecks(f.request, f.ok, 'https://panel.example', { fetchImpl: f.fetchImpl }), /fixture transport failure/);
  assert.equal(f.pending(), false); assert.equal(f.cancelled(), true);
});

test('channel-only live smoke returns before activation, key generation or API-key fixtures', async () => {
  const source = await fs.readFile(new URL('./live-smoke.mjs', import.meta.url), 'utf8');
  const start = source.indexOf("if (process.env.CHANNEL_LIVE_ONLY === '1')");
  const end = source.indexOf('\n  }', start);
  assert.ok(start > 0 && end > start);
  assert.match(source.slice(start, end), /ownerStorageChecks/);
  assert.match(source.slice(start, end), /return;/);
  for (const marker of ["const existing =", '// 4. random + custom keys', "cleanupApiKeyId = String("]) {
    const at = source.indexOf(marker);
    if (at >= 0) assert.ok(start < at);
  }
});
