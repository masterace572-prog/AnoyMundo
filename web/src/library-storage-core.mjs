// Shared by the site and the scheduled Supabase worker. No environment values,
// user data, Storage bytes or signed URLs are written to logs.
export const LIBRARY_STORAGE_BUCKET = 'loader-releases';
export const LIBRARY_DOWNLOAD_GRACE_MS = 180_000; // signed downloads last 60s; allow an extra request/transfer margin
const MANAGED = /^(anoy|kenny)\/(?:releases\/[a-f0-9-]{36}\/libbgmi\.zip|pending\/[a-f0-9-]{36})$/;
export const isManagedLibraryPath = path => typeof path === 'string' && MANAGED.test(path);
export function libraryUrlReferencesPath(url, path) {
  if (!path) return false;
  const decoded = String(url || '').replace(/%([a-f0-9]{2})/gi, (_, hex) => String.fromCharCode(parseInt(hex, 16))).toLowerCase();
  return decoded.includes(path.toLowerCase());
}
export const storageResult = ({ data, error }) => {
  if (error) {
    const problem = new Error('Library storage operation failed.');
    // A bounded provider code is useful for retries; its message/body may
    // contain URLs or credentials and must never be copied to logs/responses.
    problem.storageCode = /^[A-Z0-9_]{1,16}$/.test(String(error.code || '')) ? error.code : 'provider_failure';
    throw problem;
  }
  return typeof data === 'string' ? JSON.parse(data) : data;
};
export function validCleanupChannel(channel) {
  if (channel !== null && !['anoy', 'kenny'].includes(channel)) throw new Error('Unknown release channel.');
}

export async function storageCleanupPlan(client, channel = null, apply = false) {
  validCleanupChannel(channel);
  return storageResult(await client.rpc('plan_library_storage_cleanup', { p_channel: channel, p_apply: apply }));
}

/** One bounded batch in a web request; the scheduled worker drains more. */
export async function storageCleanup(client, { channel = null, maxBatches = 1,
  plan = (apply) => storageCleanupPlan(client, channel, apply),
  nextBatch = async () => storageResult(await client.rpc('library_storage_cleanup_batch', { p_channel: channel, p_limit: 50 })),
} = {}) {
  let deleted = 0;
  let operation = 'plan';
  try {
    validCleanupChannel(channel);
    const planned = await plan(true);
    if (!planned?.ok) throw new Error('Invalid cleanup plan.');
    for (let i = 0; i < Math.max(1, Math.min(50, maxBatches)); i++) {
      operation = 'batch';
      const entries = await nextBatch();
      if (!Array.isArray(entries)) throw new Error('Invalid cleanup plan.');
      if (!entries.length) break;
      const paths = entries.map(entry => entry.storage_path);
      if (entries.some(entry => !isManagedLibraryPath(entry.storage_path) || !entry.storage_path.startsWith(entry.channel + '/')
          || (channel !== null && entry.channel !== channel))) throw new Error('Unsafe cleanup plan.');
      // A resolved {error} is a failure too. An ambiguous acknowledgement is
      // retried idempotently; never make a successful publication look failed.
      operation = 'remove';
      storageResult(await client.storage.from(LIBRARY_STORAGE_BUCKET).remove(paths));
      operation = 'acknowledge';
      storageResult(await client.from('library_storage_cleanup').update({ deleted_at: new Date().toISOString() })
        .in('storage_path', paths).is('deleted_at', null));
      deleted += paths.length;
    }
    operation = 'count';
    let pending = client.from('library_storage_cleanup').select('storage_path', { count: 'exact', head: true }).is('deleted_at', null);
    if (channel !== null) pending = pending.eq('channel', channel);
    const count = await pending;
    storageResult(count);
    return { ok: true, deleted, pending: count.count || 0, inline_copies: planned.inline_copies, inline_bytes: planned.inline_bytes };
  } catch (error) {
    const code = error?.storageCode || 'internal_failure';
    console.warn(`[panel] Old-library cleanup pending (${operation}/${code}); active releases were not removed.`);
    return { ok: false, deleted, pending: true, operation, code };
  }
}
