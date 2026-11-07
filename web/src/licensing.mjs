import crypto from 'node:crypto';
import { config } from './config.mjs';
import { db, getSettings, getKeyForGame, deviceList, countRows } from './db.mjs';
import { envStatus } from './config.mjs';
import { sendJson, sendText, sqlDateTime } from './render.mjs';

const md5 = (value) => crypto.createHash('md5').update(String(value)).digest('hex');

/**
 * Port of app/Controllers/Connect.php::index_post().
 * Keeps the exact JSON contract the Android client expects.
 */
export async function handleConnect(req, res, body) {
  try {
    return await connect(req, res, body);
  } catch (error) {
    console.error('[anoymundo] /connect failed:', error);
    return sendJson(res, { status: false, reason: 'SERVER UNAVAILABLE' }, 503);
  }
}

async function connect(req, res, body) {
  const game = String(body.game || '').trim();
  const userKey = String(body.user_key || '').trim();
  const serial = String(body.serial || '').trim();

  if (!game || !serial || !userKey) {
    return sendJson(res, { status: false, reason: 'INVALID PARAMETER' });
  }
  if (!/^[A-Za-z0-9_-]{1,32}$/.test(game) || !/^[A-Za-z0-9_-]{1,128}$/.test(serial) ||
      !/^[A-Za-z0-9_-]{1,64}$/.test(userKey)) {
    return sendJson(res, { status: false, reason: 'Bad Parameter' });
  }

  const settings = await getSettings();

  // Maintenance mode: the original returned status=true with a message.
  if (settings.onoff.status === 'on') {
    return sendJson(res, { status: true, reason: settings.onoff.myinput || 'Server is Under Maintenance' });
  }

  const key = await getKeyForGame(userKey, game);
  if (!key) return sendJson(res, { status: false, reason: 'USER OR GAME NOT REGISTERED' });
  if (Number(key.status) !== 1) return sendJson(res, { status: false, reason: 'USER BLOCKED' });

  let expiry = key.expired_date ? new Date(key.expired_date) : null;
  const now = new Date();
  if (!expiry || Number.isNaN(expiry.getTime())) {
    expiry = new Date(now.getTime() + Number(key.duration || 1) * 3600_000);
    await db().from('keys_code').update({ expired_date: expiry.toISOString() }).eq('id_keys', key.id_keys);
  } else if (expiry.getTime() <= now.getTime()) {
    return sendJson(res, { status: false, reason: 'EXPIRED KEY' });
  }

  const devices = deviceList(key.devices);
  const maxDevices = Number(key.max_devices || 1);
  if (!devices.includes(serial)) {
    if (devices.length >= maxDevices) {
      return sendJson(res, { status: false, reason: 'MAX DEVICE REACHED' });
    }
    devices.push(serial);
    await db().from('keys_code').update({ devices: devices.join(','), updated_at: new Date().toISOString() })
      .eq('id_keys', key.id_keys);
  }

  // The token is derived from the key exactly as the loader sent it, so the
  // loader's own md5 check matches even for a legacy mixed-case spelling.
  const real = `${game}-${userKey}-${serial}-${config.staticWords}`;
  const feature = settings.feature || {};
  const expiryText = expiry.toISOString();
  return sendJson(res, {
    status: true,
    data: {
      real,
      token: md5(real),
      modname: settings.modname.modname,
      mod_status: settings.ftext._status,
      credit: settings.ftext._ftext,
      ESP: feature.esp || 'off',
      Item: feature.item || 'off',
      AIM: feature.aim || 'off',
      SilentAim: feature.silentaim || 'off',
      BulletTrack: feature.bullettrack || 'off',
      Floating: feature.floating || 'off',
      Memory: feature.memory || 'off',
      Setting: feature.setting || 'off',
      expired_date: expiryText,
      EXP: expiryText,
      exdate: expiryText,
      device: maxDevices,
      rng: Math.floor(Date.now() / 1000),
    },
  });
}

export function handleConnectInfo(res) {
  return sendJson(res, {
    web_info: {
      _client: config.brand,
      license: config.license,
      version: config.version,
    },
    web__dev: {
      author: `@${config.telegramHandle}`,
      telegram: `https://t.me/${config.telegramHandle}`,
    },
  });
}

/** GET /api/health - used by Vercel / the deployment checklist. */
export async function handleHealth(res, deep = false) {
  const started = Date.now();
  const env = envStatus();
  const memory = process.env.DB_DRIVER === 'memory';
  const envOk = memory || env.ok;
  const checks = {
    env: {
      ok: envOk,
      detail: memory ? 'ok · offline memory driver' : env.ok ? 'ok' : `missing: ${env.missing.join(', ')}`,
    },
  };
  let ok = envOk;
  const [database, libChannel] = await Promise.all([
    db().from('onoff').select('id').eq('id', 1).maybeSingle().then((r) => r).catch((e) => ({ error: e })),
    db().from('client_release').select('version,file_name,is_current').eq('is_current', true).maybeSingle()
      .then((r) => r).catch((e) => ({ error: e })),
  ]);
  if (database.error) {
    ok = false;
    checks.database = { ok: false, detail: database.error.message };
  } else {
    checks.database = { ok: true, detail: `connected · ${sqlDateTime()}` };
  }
  if (libChannel.error) {
    checks.lib_channel = { ok: false, detail: libChannel.error.message };
  } else {
    checks.lib_channel = {
      ok: true,
      detail: libChannel.data ? `version ${libChannel.data.version} (${libChannel.data.file_name})` : 'no release uploaded',
    };
  }
  try {
    const expected = ['users', 'keys_code', 'history', 'referral_code', 'feature', 'modname', 'onoff', '_ftext', 'credit', 'lib', 'client_release', 'auth_attempt', 'api_keys', 'key_orders'];
    // Parallel round trips keep the health probe fast on a cold lambda.
    const results = await Promise.all(expected.map((table) => countRows(table).catch(() => null)));
    const present = results.filter((n) => n !== null).length;
    checks.schema = { ok: present === expected.length, detail: `${present}/${expected.length} expected tables present` };
    if (present !== expected.length) ok = false;
  } catch (e) {
    checks.schema = { ok: false, detail: e.message };
    ok = false;
  }
  // Optional deep probe (?rpc=1): prove the billing RPC is reachable from the
  // serverless function through PostgREST. The deliberately invalid price makes
  // the function reject the order and remove its own idempotency row, so this
  // has no lasting effect on keys or balances.
  if (deep && !memory) {
    try {
      const { data, error } = await db().rpc('create_keys_atomic', {
        p_request_id: `health-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
        p_user_id: 0, p_game: 'PUBG', p_duration: 1, p_max_devices: 1,
        p_unit_price: 0, p_total_price: 0, p_keys: ['health-probe'],
      });
      let result = data;
      try { if (typeof result === 'string') result = JSON.parse(result); } catch { /* keep raw */ }
      const rpcOk = !error && result?.ok === false && typeof result.code === 'string';
      checks.billing_rpc = {
        ok: rpcOk,
        detail: error ? error.message : (result?.code || 'unexpected response'),
      };
      if (!rpcOk) ok = false;
    } catch (e) {
      checks.billing_rpc = { ok: false, detail: e.message };
      ok = false;
    }
  }
  return sendJson(res, { ok, ms: Date.now() - started, driver: process.env.DB_DRIVER === 'memory' ? 'memory' : 'supabase', checks }, ok ? 200 : 500);
}

/** Plain-text version string read by the Android loader (Downtwo.java). */
export async function handleLibVersion(res) {
  try {
    const { data, error } = await db().from('client_release').select('version').eq('is_current', true).maybeSingle();
    if (error) throw new Error(error.message);
    const value = data?.version || process.env.FALLBACK_LIB_VERSION || '1.0';
    return sendText(res, `${value}\n`);
  } catch (e) {
    // Never invent a version on a database error: a made-up number would
    // force every loader into a pointless (and failing) update.
    console.error('[anoymundo] /api/lib/version failed:', e);
    return sendText(res, 'unavailable\n', 503);
  }
}

/**
 * JSON description of the current loader library, read by the app's
 * mandatory update check before sign-in:
 *   { ok, version, available, size, sha256, source, notes, updated_at }
 * `available` is false when nothing downloadable has been published yet.
 */
export async function handleLibManifest(res) {
  try {
    const { data, error } = await db().from('client_release')
      .select('version,file_name,file_size,sha256,external_url,notes,created_at')
      .eq('is_current', true).maybeSingle();
    if (error) throw new Error(error.message);
    // An uploaded file always stores its size and sha256; a pushed link clears
    // both. Reading those avoids transferring the file itself.
    const hasFile = !!(data && Number(data.file_size) > 0 && data.sha256);
    const hasLink = !!(data && data.external_url);
    return sendJson(res, {
      ok: true,
      version: data?.version || process.env.FALLBACK_LIB_VERSION || '1.0',
      available: hasFile || hasLink,
      size: hasFile ? Number(data.file_size || 0) : 0,
      sha256: hasFile ? String(data.sha256 || '') : '',
      source: hasFile ? 'file' : hasLink ? 'link' : 'none',
      file_name: data?.file_name || '',
      notes: data?.notes || '',
      updated_at: data?.created_at || null,
    });
  } catch (e) {
    console.error('[anoymundo] /api/lib/manifest failed:', e);
    return sendJson(res, { ok: false, error: 'Update service temporarily unavailable.' }, 503);
  }
}

/** Binary release download. Also serves the legacy /Onlinelib/lib.so path. */
export async function handleLibDownload(req, res) {
  try {
    const { data, error } = await db().from('client_release')
      .select('version,file_name,content_base64,external_url,file_size,sha256')
      .eq('is_current', true).maybeSingle();
    if (error) throw new Error(error.message);
    if (!data) return sendText(res, 'No release has been uploaded yet.', 404);
    // A stored file always wins. Only a release published as a pure link (the
    // /lib "Push a link" action clears content_base64) redirects, so an old
    // external URL can never shadow a freshly uploaded file.
    res.setHeader('X-Release-Version', String(data.version || ''));
    if (!data.content_base64 && data.external_url) {
      res.statusCode = 302;
      res.setHeader('Location', data.external_url);
      res.setHeader('Cache-Control', 'no-store');
      return res.end();
    }
    if (!data.content_base64) return sendText(res, 'The current release has no file attached.', 404);
    const buffer = Buffer.from(data.content_base64, 'base64');
    res.statusCode = 200;
    res.setHeader('Content-Type', 'application/zip');
    res.setHeader('Content-Disposition', `attachment; filename="${String(data.file_name || 'release.zip').replace(/["\r\n]/g, '')}"`);
    res.setHeader('Content-Length', buffer.length);
    res.setHeader('Cache-Control', 'no-store');
    // Lets the loader verify the file arrived complete and unmodified.
    if (data.sha256) res.setHeader('X-Content-SHA256', String(data.sha256));
    if (String(req.method || 'GET').toUpperCase() === 'HEAD') return res.end();
    return res.end(buffer);
  } catch (e) {
    console.error('[anoymundo] /api/lib/download failed:', e);
    return sendText(res, 'Download temporarily unavailable. Please try again.', 503);
  }
}

export function handleLibInfo(res) {
  return db().from('client_release')
    .select('id,version,file_name,file_size,sha256,external_url,notes,is_current,uploaded_by,created_at')
    .order('id', { ascending: false }).limit(20)
    .then(({ data, error }) => sendJson(res, error ? { error: error.message } : { releases: data || [] }, error ? 500 : 200))
    .catch((error) => sendJson(res, { error: error.message }, 500));
}
