// In-app update for the Android app itself (the APK), published from the
// panel's "App update" page. Separate from the loader library (.so / .zip,
// table client_release, /api/lib/*): the app checks this first, on the splash
// screen, and only then runs its library check.
//
//   GET  /api/app/update   public, no auth. The current release or
//                          { available: false }. The app compares versionCode.
//   POST /app-update       Owner / Admin. action=publish | withdraw.
//
// Only an https:// link to the APK is stored (for example a GitHub release
// asset). No file upload, no signing material: Android itself refuses an
// update that is not signed with the same key as the installed app.
import { db } from './db.mjs';
import { sendJson, humanDate } from './render.mjs';

const str = (v) => String(v ?? '').trim();
const nowIso = () => new Date().toISOString();

export const APP_VERSION_NAME = /^[0-9A-Za-z][0-9A-Za-z._+-]{0,31}$/;
const MAX_CODE = 2_100_000_000; // Android versionCode is a positive int.
const MAX_CHANGELOG = 2000;

/** A full https:// address with a host (no spaces, no other schemes). */
export function validHttpsUrl(value) {
  const text = str(value);
  if (!text || text.length > 2000 || /\s/.test(text)) return false;
  try {
    const url = new URL(text);
    return url.protocol === 'https:' && !!url.hostname;
  } catch { return false; }
}

/** Mandatory if the form said so in any of the usual ways. */
function isMandatory(body) {
  const type = str(body.type).toLowerCase();
  if (type) return type === 'mandatory';
  const flag = body.mandatory;
  return flag === true || ['1', 'true', 'on', 'yes', 'mandatory'].includes(str(flag).toLowerCase());
}

/** Line endings normalised, trailing spaces trimmed, at most 2000 characters. */
function cleanChangelog(value) {
  return String(value ?? '').replace(/\r\n?/g, '\n').split('\n')
    .map((line) => line.replace(/\s+$/, '')).join('\n').trim().slice(0, MAX_CHANGELOG);
}

/** A missing table (migration not applied yet) reads as "nothing published". */
function missingTable(error) {
  const text = `${error?.code || ''} ${error?.message || ''}`;
  return /42P01|PGRST205|does not exist|could not find the table/i.test(text);
}

export async function currentAppRelease() {
  const { data, error } = await db().from('app_release').select('*').eq('is_current', true).maybeSingle();
  if (error) {
    if (missingTable(error)) return null;
    throw error;
  }
  return data || null;
}

async function recentAppReleases(limit = 8) {
  const { data, error } = await db().from('app_release').select('*').order('id', { ascending: false }).limit(limit);
  if (error) {
    if (missingTable(error)) return [];
    throw error;
  }
  return data || [];
}

function publicRelease(row) {
  return {
    version_name: row.version_name,
    version_code: Number(row.version_code),
    apk_url: row.apk_url,
    changelog: row.changelog || '',
    mandatory: !!row.mandatory,
    published_at: row.created_at,
  };
}

/**
 * GET /api/app/update. Always 200 with JSON when the database answers (also
 * when nothing is published) so the app can tell "up to date" from "can't
 * check". Optional ?code=<installed versionCode> adds update_available.
 */
export async function handleAppUpdateCheck(res, query) {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Access-Control-Allow-Origin', '*');
  let row;
  try {
    row = await currentAppRelease();
  } catch (error) {
    console.error('[anoymundo] app update check failed:', error?.message || error);
    return sendJson(res, { ok: false, error: 'Update check unavailable. Try again.' }, 503);
  }
  const payload = { ok: true, available: !!row };
  if (row) Object.assign(payload, publicRelease(row));
  const installed = Number.parseInt(str(query?.get?.('code')), 10);
  if (Number.isFinite(installed) && installed > 0) {
    payload.update_available = !!row && Number(row.version_code) > installed;
  }
  return sendJson(res, payload);
}

/** Data for the panel page. */
export async function appReleasePageData() {
  const [current, recent] = await Promise.all([currentAppRelease(), recentAppReleases()]);
  const decorate = (row) => row && ({
    ...row,
    version_code: Number(row.version_code),
    publishedLabel: humanDate(row.created_at),
    typeLabel: row.mandatory ? 'Mandatory' : 'Normal',
  });
  return {
    appRelease: decorate(current),
    appReleases: recent.map(decorate),
    suggestedCode: current ? Number(current.version_code) + 1 : '',
  };
}

/**
 * POST /app-update (JSON). Validates everything first; on a failed insert the
 * previous current release is restored, so there is never a half-published
 * state. Errors come back per field.
 */
export async function doAppReleasePost(req, res, body, user) {
  if (!user?.isAdmin) {
    return sendJson(res, { ok: false, error: 'Only the Owner or an Admin can publish app updates.' }, 403);
  }
  const action = str(body.action) || 'publish';

  if (action === 'withdraw') {
    const current = await currentAppRelease();
    if (!current) return sendJson(res, { ok: false, error: 'There is no published app update to withdraw.' }, 404);
    const { error } = await db().from('app_release').update({ is_current: false }).eq('id', current.id);
    if (error) return sendJson(res, { ok: false, error: 'Could not withdraw the update. Nothing was changed.' }, 500);
    return sendJson(res, { ok: true, withdrawn: true, version_name: current.version_name });
  }
  if (action !== 'publish') return sendJson(res, { ok: false, error: 'Unknown action.' }, 400);

  const versionName = str(body.version_name);
  const codeText = str(body.version_code);
  const apkUrl = str(body.apk_url);
  const changelog = cleanChangelog(body.changelog);
  const mandatory = isMandatory(body);
  const errors = {};

  if (!APP_VERSION_NAME.test(versionName)) {
    errors.version_name = 'Enter a version name like 2.2.0 (letters, numbers, . _ + -).';
  }
  const code = /^\d{1,10}$/.test(codeText) ? Number(codeText) : NaN;
  if (!Number.isInteger(code) || code < 1 || code > MAX_CODE) {
    errors.version_code = 'Enter the version code as a whole number, like 8.';
  }
  if (!apkUrl) {
    errors.apk_url = 'Enter the https:// link to the APK (for example the GitHub release asset).';
  } else if (!validHttpsUrl(apkUrl)) {
    errors.apk_url = 'The APK link must be a full https:// address (http:// is not allowed).';
  }
  if (!changelog) errors.changelog = 'Write a short changelog: it is shown on the update screen.';
  if (String(body.changelog ?? '').length > MAX_CHANGELOG + 200) {
    errors.changelog = `Keep the changelog under ${MAX_CHANGELOG} characters.`;
  }

  let current = null;
  try {
    current = await currentAppRelease();
  } catch {
    return sendJson(res, { ok: false, error: 'Could not read the current app update. Nothing was changed.' }, 500);
  }
  if (!errors.version_code && current && code <= Number(current.version_code)) {
    errors.version_code = `The version code must be higher than the current one (${Number(current.version_code)}).`;
  }
  const first = Object.values(errors)[0];
  if (first) return sendJson(res, { ok: false, error: first, errors }, 400);

  if (current) {
    const { error } = await db().from('app_release').update({ is_current: false }).eq('id', current.id);
    if (error) return sendJson(res, { ok: false, error: 'Could not retire the previous app update. Nothing was changed.' }, 500);
  }
  const row = {
    version_name: versionName, version_code: code, apk_url: apkUrl, changelog, mandatory,
    is_current: true, published_by: user.username, created_at: nowIso(),
  };
  const { error: insertError } = await db().from('app_release').insert(row).select('id').single();
  if (insertError) {
    if (current) await db().from('app_release').update({ is_current: true }).eq('id', current.id);
    console.error('[anoymundo] app release insert failed:', insertError.message);
    const hint = missingTable(insertError) ? ' The app_release table is missing: apply the Supabase migration.' : '';
    return sendJson(res, { ok: false, error: `Could not publish the app update. The previous one is still active.${hint}` }, 500);
  }
  return sendJson(res, { ok: true, ...publicRelease(row) });
}
