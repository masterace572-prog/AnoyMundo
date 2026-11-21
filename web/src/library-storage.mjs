// Offline adapter + production defaults for the shared retention worker.
import { db, isMemory } from './db.mjs';
import { LIBRARY_STORAGE_BUCKET, LIBRARY_DOWNLOAD_GRACE_MS, isManagedLibraryPath,
  validCleanupChannel, storageCleanupPlan, storageCleanup, libraryUrlReferencesPath } from './library-storage-core.mjs';
export { isManagedLibraryPath } from './library-storage-core.mjs';

const liveReference = (rows, path) => rows.some(row => row.is_current && (row.storage_path === path || libraryUrlReferencesPath(row.external_url, path)));

/** Offline analogue of the SQL plan. Production always uses the serialized RPC. */
function memoryPlan(client, channel, apply) {
  const rows = client._store.client_release;
  const queue = client._store.library_storage_cleanup ||= [];
  const scoped = row => channel === null || row.channel === channel;
  const current = new Set(rows.filter(row => row.is_current).map(row => row.channel));
  const retired = rows.filter(row => !row.is_current && scoped(row) && current.has(row.channel));
  const paths = new Set(retired.map(row => row.storage_path).filter(isManagedLibraryPath));
  for (const item of client.storage._inventory()) {
    if (item.bucket_id === LIBRARY_STORAGE_BUCKET && isManagedLibraryPath(item.name)
        && (channel === null || item.name.startsWith(channel + '/'))
        && new Date(item.created_at).getTime() < Date.now() - 24 * 3600_000
        && !rows.some(row => row.storage_path === item.name)) paths.add(item.name);
  }
  for (const path of paths) {
    if (liveReference(rows, path) || queue.some(entry => entry.storage_path === path && entry.deleted_at)) paths.delete(path);
  }
  const copies = retired.filter(row => row.content_base64 != null);
  const bytes = copies.reduce((sum, row) => sum + Buffer.byteLength(row.content_base64), 0);
  if (apply) {
    for (const path of paths) {
      if (!queue.some(entry => entry.storage_path === path)) queue.push({ storage_path: path,
        channel: path.split('/')[0], queued_at: new Date().toISOString(),
        delete_after: new Date(Date.now() + LIBRARY_DOWNLOAD_GRACE_MS).toISOString(), deleted_at: null });
    }
    for (const row of copies) row.content_base64 = null;
  }
  return { ok: true, candidate_objects: paths.size, inline_copies: copies.length, inline_bytes: bytes,
    pending: queue.filter(entry => scoped(entry) && !entry.deleted_at).length, applied: apply };
}

export async function libraryStoragePlan({ channel = null, apply = false, client = db(), memory = isMemory } = {}) {
  validCleanupChannel(channel);
  return memory ? memoryPlan(client, channel, apply) : storageCleanupPlan(client, channel, apply);
}

export async function cleanLibraryStorage({ channel = null, maxBatches = 1, client = db(), memory = isMemory } = {}) {
  return storageCleanup(client, { channel, maxBatches, ...(memory ? {
    plan: apply => libraryStoragePlan({ client, channel, memory, apply }),
    nextBatch: () => client._store.library_storage_cleanup.filter(row => !row.deleted_at
      && (channel === null || row.channel === channel) && new Date(row.delete_after).getTime() <= Date.now()
      && !liveReference(client._store.client_release, row.storage_path)).slice(0, 50),
  } : {}) });
}
