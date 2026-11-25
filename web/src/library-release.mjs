import crypto from 'node:crypto';
import { db, isMemory } from './db.mjs';
import { assertWriteChannel } from './channels.mjs';
import { normalizeLibrary, LIBRARY_BUCKET, LIBRARY_NAME, MAX_LIBRARY_BYTES, MAX_LIBRARY_MB } from './library-package.mjs';
import { cleanLibraryStorage } from './library-storage.mjs';
import { libraryUrlReferencesPath } from './library-storage-core.mjs';
import { sendJson } from './render.mjs';
import { validHttpsUrl, panelHosts } from './app-release.mjs';
import { verifyLibraryLink } from './library-link.mjs';

const str = (v) => String(v ?? '').trim();
export const validLibraryVersion = (v) => /^[A-Za-z0-9][A-Za-z0-9._+-]{0,31}$/.test(str(v));
const validGame = (v) => v === '' || /^\d{1,4}(\.\d{1,6}){0,3}$/.test(v);
const storage = () => db().storage.from(LIBRARY_BUCKET);
const unwrap = ({ data, error }) => {
  if (error) throw new Error(error.message);
  return typeof data === 'string' ? JSON.parse(data) : data;
};

export async function doLibUpload(req, res, body, user, query = null) {
  const channel = assertWriteChannel(user, body, query);
  if (!channel) return sendJson(res, { ok: false, error: 'You cannot manage that library release channel.' }, 403);
  const action = str(body.action) || 'upload';
  if (!['prepare_upload', 'cancel_upload', 'upload', 'link', 'game', 'reset'].includes(action)) return sendJson(res, { ok: false, error: 'Unknown action.' }, 400);

  if (action === 'cancel_upload') {
    const path = str(body.upload_path);
    if (!new RegExp(`^${channel}/pending/[a-f0-9-]{36}$`).test(path)) {
      return sendJson(res, { ok: false, error: 'That temporary upload does not belong to your channel.' }, 403);
    }
    unwrap(await storage().remove([path]));
    return sendJson(res, { ok: true, channel, cancelled: true });
  }

  if (action === 'prepare_upload') {
    const size = Number(body.file_size);
    if (!Number.isInteger(size) || size < 4 || size > MAX_LIBRARY_BYTES) {
      return sendJson(res, { ok: false, error: `Choose a .so or ZIP file up to ${MAX_LIBRARY_MB} MB.` }, 400);
    }
    const path = `${channel}/pending/${crypto.randomUUID()}`;
    const result = unwrap(await storage().createSignedUploadUrl(path));
    return sendJson(res, { ok: true, channel, upload_path: path, upload_url: result.signedUrl, max_bytes: MAX_LIBRARY_BYTES });
  }

  const hasGame = Object.hasOwn(body, 'game_version');
  const gameVersion = str(body.game_version);
  if (hasGame && !validGame(gameVersion)) return sendJson(res, { ok: false, error: 'Enter the BGMI version as numbers, like 3.9.0 (or leave it empty).' }, 400);
  const current = unwrap(await db().from('client_release').select('*').eq('channel', channel).eq('is_current', true).maybeSingle());

  if (action === 'reset' || action === 'game') {
    if (!current) return sendJson(res, { ok: false, error: 'Publish a release first.' }, 404);
    const patch = action === 'reset' ? { version: '0.1', uploaded_by: user.username } : { game_version: gameVersion || null };
    const { error } = await db().from('client_release').update(patch).eq('channel', channel).eq('id', current.id);
    if (error) return sendJson(res, { ok: false, error: 'Could not save your release. Please try again.' }, 500);
    return sendJson(res, { ok: true, channel, version: action === 'reset' ? '0.1' : current.version,
      previous: current.version, game_version: gameVersion, mode: action });
  }

  const version = str(body.version);
  const uploadPath = str(body.upload_path);
  const base64 = action === 'upload' ? str(body.content_base64) : '';
  // File wins, including a direct upload. Never validate or store the ignored link.
  const externalUrl = uploadPath || base64 ? '' : str(body.external_url);
  if (!validLibraryVersion(version)) return sendJson(res, { ok: false, error: 'Enter a version like 1.3 (letters, numbers, . _ + -).' }, 400);
  if (externalUrl && !validHttpsUrl(externalUrl)) return sendJson(res, { ok: false, error: 'The external link must be a full https:// address (http:// is not allowed).' }, 400);
  if (!uploadPath && !base64 && !externalUrl) return sendJson(res, { ok: false, error: 'Choose a file or enter an external link.' }, 400);
  if (action === 'link' && !current) return sendJson(res, { ok: false, error: 'Upload a release first, then update its link.' }, 404);
  if (uploadPath && !new RegExp(`^${channel}/pending/[a-f0-9-]{36}$`).test(uploadPath)) {
    return sendJson(res, { ok: false, error: 'That upload does not belong to your channel.' }, 403);
  }
  if (base64.length > 4_194_304) return sendJson(res, { ok: false, error: `Use the direct file uploader for files over 3 MB. It supports ${MAX_LIBRARY_MB} MB.` }, 413);
  if (externalUrl) {
    const check = isMemory && process.env.APK_VERIFY_STUB === '1'
      ? (/bad-library/.test(externalUrl) ? { ok: false, error: 'That link is not a working library. The current release is unchanged.' } : { ok: true })
      : await verifyLibraryLink(externalUrl, { selfHosts: panelHosts(req) });
    if (!check.ok) return sendJson(res, { ok: false, error: check.error }, 400);
  }

  let storedPath = null;
  let published = false;
  let commitAttempted = false;
  try {
    let archive = null;
    if (uploadPath || base64) {
      const input = uploadPath
        ? Buffer.from(await unwrap(await storage().download(uploadPath)).arrayBuffer())
        : Buffer.from(base64.replace(/^data:[^;]+;base64,/, ''), 'base64');
      try { archive = normalizeLibrary(input); }
      catch (error) { return sendJson(res, { ok: false, error: error.message }, 400); }
      storedPath = `${channel}/releases/${crypto.randomUUID()}/libbgmi.zip`;
      unwrap(await storage().upload(storedPath, archive, { contentType: 'application/zip', upsert: false }));
      // Do not retire a working library merely because PUT returned 2xx.
      // Read back the normalized private object and verify its size + digest.
      const saved = Buffer.from(await unwrap(await storage().download(storedPath)).arrayBuffer());
      if (saved.length !== archive.length || !crypto.timingSafeEqual(
        crypto.createHash('sha256').update(saved).digest(), crypto.createHash('sha256').update(archive).digest())) {
        throw new Error('Stored library verification failed.');
      }
    }
    const row = {
      channel, version, file_name: LIBRARY_NAME, file_size: archive?.length || 0,
      sha256: archive ? crypto.createHash('sha256').update(archive).digest('hex') : '',
      storage_path: storedPath, content_base64: null, external_url: externalUrl || null,
      notes: str(body.notes).slice(0, 500) || null,
      game_version: hasGame ? gameVersion || null : current?.game_version || null,
      uploaded_by: user.username, is_current: true, created_at: new Date().toISOString(),
    };
    if (isMemory) {
      // No await between the state changes: atomic in the offline process.
      const rows = db()._store.client_release;
      if ((db()._store.library_storage_cleanup || []).some(entry => entry.storage_path === row.storage_path
          || libraryUrlReferencesPath(row.external_url, entry.storage_path))) throw new Error('That file has been retired.');
      for (const r of rows) if (r.channel === channel) r.is_current = false;
      rows.push({ ...row, id: Math.max(0, ...rows.map((r) => r.id)) + 1 });
    } else {
      // After this point a lost RPC response may still mean the DB committed.
      // Keep the private output on ambiguity: never delete a possibly live file.
      commitAttempted = true;
      const result = unwrap(await db().rpc('publish_library_release', { p_actor_id: user.id_users, p_release: row }));
      if (!result?.ok) throw new Error('Publication was not accepted.');
    }
    published = true;
    try {
      await db().from('lib').insert({ channel, file: LIBRARY_NAME, file_type: `Storage/${storedPath || 'external'}`,
        file_size: `${(row.file_size / 1024).toFixed(1)} KB`, time: row.created_at });
    } catch { /* best-effort audit; never report a successful publish as failed */ }
    const cleanup = await cleanLibraryStorage({ channel });
    return sendJson(res, { ok: true, channel, version, file_name: LIBRARY_NAME, file_size: row.file_size,
      sha256: row.sha256, external_url: externalUrl || null, mode: action,
      cleanup_pending: !cleanup.ok || !!cleanup.pending, old_files_deleted: cleanup.deleted });
  } catch (error) {
    console.error('[panel] Library publication could not be confirmed.');
    return sendJson(res, { ok: false, error: commitAttempted ? 'Could not confirm publication. Reload your library page before retrying.' : 'Could not publish your library. The previous release is still active.' }, 500);
  } finally {
    // Sources and failed outputs are private temporary objects, never live releases.
    if (uploadPath) await storage().remove([uploadPath]).catch(() => {});
    if (storedPath && !published && !commitAttempted) await storage().remove([storedPath]).catch(() => {});
  }
}
