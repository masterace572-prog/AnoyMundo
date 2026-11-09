// In-app update for the Android app itself (the APK), published from the
// panel's "App update" page. Separate from the loader library (.so / .zip,
// table client_release, /api/lib/*): the app checks this first, on the splash
// screen, and only then runs its library check.
//
//   GET  /api/app/update   public, no auth. The current release or
//                          { available: false }. The app compares versionCode.
//   POST /app-update       Owner / Admin. action=publish | remove (withdraw).
//   GET  /app-update/latest-build
//                          Owner / Admin. The newest GitHub release of this
//                          repo (tag anoy-v<name>-<code>) for "Use latest build".
//
// Only an https:// link to the APK is stored (for example a GitHub release
// asset). No file upload, no signing material: Android itself refuses an
// update that is not signed with the same key as the installed app.
//
// 2.3.2: before saving, the link is fetched like the app will (https-only
// redirects, a 4-byte ranged GET): it must answer 200/206 with a non-HTML
// body that starts with "PK". The panel itself (maxDuration 30 s) can never
// serve an APK, so links to it are refused outright.
import { db, isMemory } from './db.mjs';
import { config } from './config.mjs';
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

// ------------------------------------------------------------ link check

const APK_CHECK_TIMEOUT_MS = 10_000;
const APK_CHECK_MAX_REDIRECTS = 6;
const REDIRECTS = new Set([301, 302, 303, 307, 308]);
export const APK_ASSET_NAME = 'Anoy-XLOX-loader.apk';
const RELEASE_TAG = /^anoy-v([0-9A-Za-z][0-9A-Za-z._+]*)-(\d{1,10})$/;

function hostOf(url) {
  try { return new URL(url).hostname.toLowerCase(); } catch { return ''; }
}

/** The panel's own hosts: its public address, the request host and any *.vercel.app. */
export function panelHosts(req) {
  const hosts = new Set();
  const base = hostOf(config.baseUrl);
  if (base) hosts.add(base);
  const header = String(req?.headers?.['x-forwarded-host'] || req?.headers?.host || '').split(',')[0].trim();
  const reqHost = header.replace(/:\d+$/, '').toLowerCase();
  if (reqHost) hosts.add(reqHost);
  return [...hosts];
}

function isPanelHost(hostname, hosts) {
  const h = String(hostname || '').toLowerCase();
  return hosts.includes(h) || h === 'vercel.app' || h.endsWith('.vercel.app');
}

const linkError = (reason) => `This link isn't a direct APK download (${reason}). Use the GitHub release asset link.`;

/** Debug/test only (memory database): no network, "bad-<code>" in the path fails with that HTTP code. */
function stubVerify(url) {
  const m = /bad-(\d{3})/.exec(url.pathname);
  if (m) return { ok: false, error: linkError(`HTTP ${m[1]}`), status: Number(m[1]) };
  if (/\.html?$|\/releases\/tag\//.test(url.pathname)) return { ok: false, error: linkError('it opens a web page') };
  return { ok: true, status: 206, host: url.hostname };
}

/**
 * Fetches the first 4 bytes of the APK the way the app will download it:
 * https on every hop (at most 6 redirects), ~10 s in total. Resolves to
 * { ok: true, status, host } or { ok: false, error } - never throws.
 */
export async function verifyApkLink(rawUrl, { selfHosts = [], fetchImpl = globalThis.fetch } = {}) {
  let url;
  try { url = new URL(String(rawUrl)); } catch { return { ok: false, error: linkError('not a valid address') }; }
  if (url.protocol !== 'https:') return { ok: false, error: linkError('it is not https') };
  if (isPanelHost(url.hostname, selfHosts)) {
    return { ok: false, error: linkError('it points at this panel, which cannot serve the APK') };
  }
  if (isMemory && process.env.APK_VERIFY_STUB === '1') return stubVerify(url);

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), APK_CHECK_TIMEOUT_MS);
  try {
    for (let hop = 0; hop <= APK_CHECK_MAX_REDIRECTS; hop++) {
      if (url.protocol !== 'https:') return { ok: false, error: linkError('it redirects to a non-https address') };
      if (isPanelHost(url.hostname, selfHosts)) {
        return { ok: false, error: linkError('it redirects to this panel, which cannot serve the APK') };
      }
      let res;
      try {
        res = await fetchImpl(url, {
          method: 'GET',
          redirect: 'manual',
          signal: controller.signal,
          headers: {
            Range: 'bytes=0-3',
            Accept: 'application/vnd.android.package-archive, application/octet-stream, */*',
            'Accept-Encoding': 'identity',
            'User-Agent': 'AnoyPanel/2 (APK link check)',
          },
        });
      } catch {
        return { ok: false, error: linkError(controller.signal.aborted ? 'no answer within 10 s' : 'it could not be reached') };
      }
      if (REDIRECTS.has(res.status)) {
        const location = res.headers.get('location');
        try { await res.body?.cancel(); } catch { /* already closed */ }
        if (!location) return { ok: false, error: linkError(`HTTP ${res.status} without a target`) };
        try { url = new URL(location, url); } catch { return { ok: false, error: linkError('it redirects to an invalid address') }; }
        continue;
      }
      if (res.status !== 200 && res.status !== 206) {
        try { await res.body?.cancel(); } catch { /* already closed */ }
        return { ok: false, error: linkError(`HTTP ${res.status}`), status: res.status };
      }
      const type = String(res.headers.get('content-type') || '').toLowerCase();
      if (type.includes('text/html') || type.includes('application/xhtml')) {
        try { await res.body?.cancel(); } catch { /* already closed */ }
        return { ok: false, error: linkError('it opens a web page'), status: res.status };
      }
      // Read just enough for the ZIP signature; a server that ignores Range is cut off here.
      let head = Buffer.alloc(0);
      const reader = res.body?.getReader?.();
      if (reader) {
        try {
          while (head.length < 2) {
            const { done, value } = await reader.read();
            if (done) break;
            head = Buffer.concat([head, Buffer.from(value)]);
          }
        } catch {
          return { ok: false, error: linkError(controller.signal.aborted ? 'no answer within 10 s' : 'the download broke off') };
        } finally {
          try { await reader.cancel(); } catch { /* already closed */ }
        }
      }
      if (head[0] !== 0x50 || head[1] !== 0x4b) {
        return { ok: false, error: linkError("it isn't an APK file"), status: res.status };
      }
      return { ok: true, status: res.status, host: url.hostname };
    }
    return { ok: false, error: linkError('too many redirects') };
  } finally {
    clearTimeout(timer);
  }
}

// ------------------------------------------------------- latest build

/** Test only (memory database): a fixed sample instead of the GitHub API. */
const STUB_BUILD = {
  version_name: '2.3.2', version_code: 11, tag: 'anoy-v2.3.2-11',
  apk_url: `https://github.com/masterace572-prog/AnoyMundo/releases/download/anoy-v2.3.2-11/${APK_ASSET_NAME}`,
};

/** The newest release whose tag reads anoy-v<name>-<code> and that carries the APK, or null. */
export function pickLatestBuild(releases) {
  for (const rel of Array.isArray(releases) ? releases : []) {
    if (!rel || rel.draft || rel.prerelease) continue;
    const m = RELEASE_TAG.exec(String(rel.tag_name || ''));
    if (!m) continue;
    const asset = (rel.assets || []).find((a) => a && a.name === APK_ASSET_NAME && a.browser_download_url);
    if (!asset) continue;
    return {
      version_name: m[1], version_code: Number(m[2]), tag: rel.tag_name,
      apk_url: asset.browser_download_url, published_at: rel.published_at || rel.created_at || null,
    };
  }
  return null;
}

/** GET /app-update/latest-build (Owner / Admin). Public GitHub API, no token. */
export async function handleLatestBuild(res) {
  res.setHeader('Cache-Control', 'no-store');
  if (isMemory && process.env.APK_VERIFY_STUB === '1') return sendJson(res, { ok: true, ...STUB_BUILD });
  const repo = String(process.env.GITHUB_REPO || 'masterace572-prog/AnoyMundo').trim();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10_000);
  try {
    const r = await fetch(`https://api.github.com/repos/${repo}/releases?per_page=20`, {
      signal: controller.signal,
      headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'AnoyPanel/2', 'X-GitHub-Api-Version': '2022-11-28' },
    });
    if (!r.ok) {
      return sendJson(res, { ok: false, error: `GitHub answered HTTP ${r.status}. Enter the fields by hand or try again.` }, 502);
    }
    const build = pickLatestBuild(await r.json());
    if (!build) {
      return sendJson(res, { ok: false, error: `No GitHub release named anoy-v<version>-<code> with ${APK_ASSET_NAME} was found.` }, 404);
    }
    return sendJson(res, { ok: true, ...build });
  } catch (error) {
    console.error('[anoymundo] latest build lookup failed:', error?.message || error);
    return sendJson(res, { ok: false, error: 'Could not reach GitHub. Enter the fields by hand or try again.' }, 502);
  } finally {
    clearTimeout(timer);
  }
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

/** Data for the panel page. 2.3.2: only the current update - no history is shown. */
export async function appReleasePageData() {
  const current = await currentAppRelease();
  const decorate = (row) => row && ({
    ...row,
    version_code: Number(row.version_code),
    publishedLabel: humanDate(row.created_at),
    typeLabel: row.mandatory ? 'Mandatory' : 'Normal',
  });
  return {
    appRelease: decorate(current),
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

  // "Remove" (2.3.2 wording; "withdraw" still accepted): phones stop being
  // offered the current update. The row is kept, only is_current changes.
  if (action === 'remove' || action === 'withdraw') {
    const current = await currentAppRelease();
    if (!current) return sendJson(res, { ok: false, error: 'There is no published app update to remove.' }, 404);
    const { error } = await db().from('app_release').update({ is_current: false }).eq('id', current.id);
    if (error) return sendJson(res, { ok: false, error: 'Could not remove the update. Nothing was changed.' }, 500);
    return sendJson(res, { ok: true, removed: true, withdrawn: true, version_name: current.version_name });
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
    errors.version_code = `The version code must be higher than the current update's (${current.version_name}, code ${Number(current.version_code)}).`;
  }
  // Last, and only for a well-formed link: fetch it like the phone will.
  if (!errors.apk_url) {
    const check = await verifyApkLink(apkUrl, { selfHosts: panelHosts(req) });
    if (!check.ok) errors.apk_url = check.error;
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
