// Runtime-neutral handler, used by the Supabase Edge entry and offline tests.
// The dedicated job token is created inside the database/Vault, never in Git.
import { LIBRARY_STORAGE_BUCKET, storageResult, storageCleanup } from './library-storage-core.mjs';

const json = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'Allow': 'POST' },
});
const sha256 = async bytes => [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))]
  .map(byte => byte.toString(16).padStart(2, '0')).join('');

export async function verifyStoredLibraries(client) {
  const rows = storageResult(await client.from('client_release')
    .select('channel,version,storage_path,sha256,file_size').eq('is_current', true));
  if (!Array.isArray(rows)) throw new Error('Cannot verify current libraries.');
  const verified = [];
  for (const row of rows) {
    if (!row.storage_path) continue; // External and legacy inline releases stay unchanged.
    const blob = storageResult(await client.storage.from(LIBRARY_STORAGE_BUCKET).download(row.storage_path));
    const bytes = await blob.arrayBuffer();
    const hash = await sha256(bytes);
    if (bytes.byteLength !== Number(row.file_size) || hash !== row.sha256) throw new Error('Current library verification failed.');
    verified.push({ channel: row.channel, version: row.version, bytes: bytes.byteLength, sha256: hash });
  }
  return verified;
}

export function createLibraryStorageWorker({ createClient, env }) {
  return async req => {
    if (req.method !== 'POST') return json({ ok: false }, 405);
    const token = /^Bearer ([a-f0-9]{64})$/.exec(req.headers.get('authorization') || '')?.[1];
    if (!token) return json({ ok: false }, 401);
    if (!env.SUPABASE_URL || !env.SUPABASE_SERVICE_ROLE_KEY) return json({ ok: false }, 503);
    try {
      const client = createClient(env.SUPABASE_URL, env.SUPABASE_SERVICE_ROLE_KEY, {
        auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
      });
      const authorized = storageResult(await client.rpc('authorize_library_storage_worker', {
        p_token_sha256: await sha256(new TextEncoder().encode(token)),
      }));
      if (authorized !== true) return json({ ok: false }, 401);
      const text = await req.text();
      if (text.length > 128) return json({ ok: false }, 400);
      let payload;
      try { payload = text ? JSON.parse(text) : {}; } catch { return json({ ok: false }, 400); }
      if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return json({ ok: false }, 400);
      const { action = 'cleanup' } = payload;
      if (!['cleanup', 'verify', 'maintenance'].includes(action)) return json({ ok: false }, 400);
      if (action === 'verify') return json({ ok: true, libraries: await verifyStoredLibraries(client) });
      const before = action === 'maintenance' ? await verifyStoredLibraries(client) : null;
      const cleanup = await storageCleanup(client, { maxBatches: 10 });
      const after = action === 'maintenance' ? await verifyStoredLibraries(client) : null;
      return json({ ok: cleanup.ok, cleanup, ...(before ? { verified_current_before: before, verified_current_after: after } : {}) }, cleanup.ok ? 200 : 503);
    } catch {
      // This includes bad requests, ambiguous network replies and unreadable live
      // files. No provider messages, signed URLs or task credentials are exposed.
      console.warn('[panel] Scheduled library maintenance needs a retry.');
      return json({ ok: false }, 503);
    }
  };
}
