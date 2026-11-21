// No network, production keys, users or usable credentials. The Edge handler
// and retention core here are the same modules bundled for production.
import assert from 'node:assert/strict';
import test from 'node:test';
import crypto from 'node:crypto';
import { createMemoryDb } from '../src/memory-db.mjs';
import { libraryStoragePlan } from '../src/library-storage.mjs';
import { createLibraryStorageWorker } from '../src/library-storage-worker.mjs';
import { LIBRARY_STORAGE_BUCKET } from '../src/library-storage-core.mjs';

const sha = data => crypto.createHash('sha256').update(data).digest('hex');
async function fixture() {
  const client = createMemoryDb();
  client._store.client_release = [];
  client._store.library_storage_cleanup = [];
  for (const channel of ['anoy', 'kenny']) {
    const bytes = Buffer.from(`Offline binary fixture for ${channel}`);
    const path = `${channel}/releases/${crypto.randomUUID()}/libbgmi.zip`;
    await client.storage.from(LIBRARY_STORAGE_BUCKET).upload(path, bytes);
    client._store.client_release.push({ id: client._store.client_release.length + 1, channel, version: '1.0',
      storage_path: path, sha256: sha(bytes), file_size: bytes.length, is_current: true });
  }
  const retired = `anoy/releases/${crypto.randomUUID()}/libbgmi.zip`;
  await client.storage.from(LIBRARY_STORAGE_BUCKET).upload(retired, Buffer.from('obsolete fixture'));
  client._store.client_release.push({ id: 3, channel: 'anoy', version: '0.1', storage_path: retired,
    is_current: false, content_base64: Buffer.from('old embedded fixture').toString('base64') });
  const token = crypto.randomBytes(32).toString('hex');
  const counts = { client: 0, plan: 0, batch: 0, authorization: 0 };
  client.rpc = async (name, args) => {
    if (name === 'authorize_library_storage_worker') {
      counts.authorization++;
      return { data: args.p_token_sha256 === sha(token), error: null };
    }
    if (name === 'plan_library_storage_cleanup') {
      counts.plan++;
      return { data: await libraryStoragePlan({ client, channel: args.p_channel, apply: args.p_apply, memory: true }), error: null };
    }
    if (name === 'library_storage_cleanup_batch') {
      counts.batch++;
      return { data: client._store.library_storage_cleanup.filter(row => !row.deleted_at
        && new Date(row.delete_after).getTime() <= Date.now()).slice(0, args.p_limit), error: null };
    }
    throw new Error('Unexpected offline RPC');
  };
  const env = { SUPABASE_URL: 'https://offline.invalid', SUPABASE_SERVICE_ROLE_KEY: 'not-a-real-service-key' };
  const createClient = (url, key) => { counts.client++; assert.equal(url, env.SUPABASE_URL); assert.equal(key, env.SUPABASE_SERVICE_ROLE_KEY); return client; };
  const handler = createLibraryStorageWorker({ createClient, env });
  const request = (body = {}, auth = token, method = 'POST') => handler(new Request('https://worker.invalid', {
    method, headers: auth ? { authorization: 'Bearer ' + auth } : {},
    ...(method === 'POST' ? { body: typeof body === 'string' ? body : JSON.stringify(body) } : {}),
  }));
  const mature = () => { for (const row of client._store.library_storage_cleanup) row.delete_after = new Date(Date.now() - 1).toISOString(); };
  return { client, counts, token, request, mature, retired };
}

test('the scheduled worker rejects public GETs, missing/malformed and incorrect credentials without planning any deletion', async () => {
  const f = await fixture();
  assert.equal((await f.request({}, null, 'GET')).status, 405);
  assert.equal((await f.request({}, null)).status, 401);
  assert.equal((await f.request({}, 'a-service-key-is-not-the-task-token')).status, 401);
  assert.equal((await f.request({}, crypto.randomBytes(32).toString('hex'))).status, 401);
  assert.equal(f.counts.client, 1); assert.equal(f.counts.authorization, 1);
  assert.equal(f.counts.plan, 0);
  assert.equal(f.client._store.client_release[2].content_base64 != null, true);
  assert.ok((await f.client.storage.from(LIBRARY_STORAGE_BUCKET).download(f.retired)).data);
});

test('authenticated verification checks both current stored downloads and never changes files or release metadata', async () => {
  const f = await fixture();
  const before = JSON.stringify(f.client._store.client_release);
  const response = await f.request({ action: 'verify' });
  assert.equal(response.status, 200);
  const result = await response.json();
  assert.equal(result.ok, true);
  assert.deepEqual(result.libraries.map(row => row.channel), ['anoy', 'kenny']);
  assert.ok(result.libraries.every(row => /^[a-f0-9]{64}$/.test(row.sha256) && row.bytes > 0));
  assert.equal(JSON.stringify(f.client._store.client_release), before);
  assert.equal(f.counts.plan, 0);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.doesNotMatch(JSON.stringify(result), /storage_path|service-key|token/);
});

test('maintenance verifies current integrity before queuing deletion; scheduled retries respect grace and remove only obsolete bytes', async () => {
  const f = await fixture();
  const first = await (await f.request({ action: 'maintenance' })).json();
  assert.equal(first.ok, true); assert.equal(first.cleanup.pending, 1);
  assert.equal(first.cleanup.deleted, 0); assert.equal(first.cleanup.inline_copies, 1);
  assert.deepEqual(first.verified_current_before, first.verified_current_after);
  assert.ok((await f.client.storage.from(LIBRARY_STORAGE_BUCKET).download(f.retired)).data);
  f.mature();
  const second = await (await f.request()).json();
  assert.equal(second.ok, true); assert.equal(second.cleanup.pending, 0); assert.equal(second.cleanup.deleted, 1);
  assert.ok((await f.client.storage.from(LIBRARY_STORAGE_BUCKET).download(f.retired)).error);
  const verified = await (await f.request({ action: 'verify' })).json();
  assert.deepEqual(verified.libraries, first.verified_current_before);
  const repeated = await (await f.request()).json();
  assert.equal(repeated.cleanup.deleted, 0); assert.equal(repeated.cleanup.pending, 0);
});

test('an unreadable/corrupt current library aborts explicit maintenance before old payloads are touched', async () => {
  const f = await fixture();
  f.client._store.client_release[0].sha256 = 'b'.repeat(64);
  const response = await f.request({ action: 'maintenance' });
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { ok: false });
  assert.equal(f.counts.plan, 0);
  assert.notEqual(f.client._store.client_release[2].content_base64, null);
  assert.ok((await f.client.storage.from(LIBRARY_STORAGE_BUCKET).download(f.retired)).data);
});

test('a provider deletion error is retryable and never exposes credentials, provider URLs or payloads', async () => {
  const f = await fixture();
  await f.request(); f.mature();
  const messages = [], warn = console.warn;
  console.warn = (...args) => messages.push(args.join(' '));
  try {
    f.client.storage._failNext('remove', 'https://private.invalid?token=private-fixture');
    const response = await f.request();
    assert.equal(response.status, 503);
    const failed = await response.json();
    assert.equal(failed.cleanup.ok, false); assert.equal(failed.cleanup.pending, true);
    assert.doesNotMatch(JSON.stringify(failed) + messages.join(' '), /private-fixture|private.invalid/);
  } finally { console.warn = warn; }
  const retried = await (await f.request()).json();
  assert.equal(retried.ok, true); assert.equal(retried.cleanup.deleted, 1);
});

test('invalid authenticated actions and payloads never reach the cleanup planner', async () => {
  const f = await fixture();
  for (const body of ['not json', 'null', '[]', '{}'.repeat(200), { action: 'delete-all' }, { action: ['cleanup'] }]) {
    assert.equal((await f.request(body)).status, 400);
  }
  assert.equal(f.counts.plan, 0);
});

test('a compromised/invalid batch outside managed library paths fails closed before Storage.remove', async () => {
  const f = await fixture();
  const rpc = f.client.rpc;
  f.client.rpc = async (name, args) => name === 'library_storage_cleanup_batch'
    ? { data: [{ storage_path: 'unrelated/file.zip', channel: 'anoy' }], error: null } : rpc(name, args);
  const response = await f.request();
  assert.equal(response.status, 503);
  assert.ok((await f.client.storage.from(LIBRARY_STORAGE_BUCKET).download(f.retired)).data);
});

test('retry diagnostics retain only a bounded provider code and operation, never its message or response body', async () => {
  const f = await fixture(), rpc = f.client.rpc;
  f.client.rpc = async (name, args) => name === 'plan_library_storage_cleanup'
    ? { data: null, error: { code: 'PGRST202', message: 'https://private.invalid?token=private-fixture', details: f.token } } : rpc(name, args);
  const response = await f.request();
  assert.equal(response.status, 503);
  const failed = await response.json();
  assert.equal(failed.cleanup.operation, 'plan'); assert.equal(failed.cleanup.code, 'PGRST202');
  assert.doesNotMatch(JSON.stringify(failed), /private-fixture|private.invalid/);
  assert.equal(f.client._store.library_storage_cleanup.length, 0);
});
