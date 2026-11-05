// Personal API keys + the /api/v1 key-management REST API.
//
// Scope is deliberately limited to LICENCE KEYS only: create / list / read /
// update / reset / delete. There is no endpoint to change mod name, floating
// text, features, maintenance or any other server setting.
import crypto from 'node:crypto';
import { config } from './config.mjs';
import { db, getUserById, getKeyByUserKey, deviceList } from './db.mjs';
import { sendJson } from './render.mjs';
import { createKeysForUser, quoteKeys, deleteKeysWithRefund } from './key-service.mjs';

const sha256 = (value) => crypto.createHash('sha256').update(String(value)).digest('hex');
const nowIso = () => new Date().toISOString();
const str = (v) => String(v ?? '').trim();
const num = (v, fallback = 0) => (Number.isFinite(Number(v)) ? Number(v) : fallback);

export const API_KEY_PREFIX = 'amk_';

// ---------------------------------------------------------------------------
// Panel-side management of the user's own personal API keys
// ---------------------------------------------------------------------------
export function generateApiKey() {
  return API_KEY_PREFIX + crypto.randomBytes(30).toString('base64url');
}

export async function createApiKeyForUser(user, name) {
  const token = generateApiKey();
  const record = {
    user_id: user.id_users,
    name: String(name).slice(0, 80),
    key_prefix: token.slice(0, 12),
    key_hash: sha256(token),
    key_last4: token.slice(-4),
    status: 1,
    last_used_at: null,
    created_at: nowIso(),
    updated_at: nowIso(),
  };
  const { error } = await db().from('api_keys').insert(record).select().single();
  if (error) throw new Error(error.message);
  return { token, record };
}

export async function listApiKeysForUser(user) {
  const { data, error } = await db().from('api_keys').select('*')
    .eq('user_id', user.id_users).order('id', { ascending: false });
  if (error) throw new Error(error.message);
  return data || [];
}

export async function mutateApiKey(user, id, action) {
  const { data: row } = await db().from('api_keys').select('*').eq('id', id).eq('user_id', user.id_users).maybeSingle();
  if (!row) return false;
  if (action === 'delete') await db().from('api_keys').delete().eq('id', row.id);
  else if (action === 'revoke') await db().from('api_keys').update({ status: 0, updated_at: nowIso() }).eq('id', row.id);
  else return false;
  return true;
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------
function cors(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-API-Key, Authorization');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PATCH, DELETE, OPTIONS');
  res.setHeader('Access-Control-Max-Age', '600');
}

function ok(res, payload = {}, status = 200) {
  cors(res);
  return sendJson(res, { ok: true, ...payload }, status);
}

function fail(res, status, code, message) {
  cors(res);
  return sendJson(res, { ok: false, error: { code, message } }, status);
}

function extractToken(req, body, q) {
  const h = req.headers || {};
  const bearer = /^Bearer\s+(.+)$/i.exec(str(h.authorization));
  return str(h['x-api-key'] || (bearer ? bearer[1] : '') || q.get('api_key') || body.api_key || '');
}

async function authenticate(req, body, q) {
  const token = extractToken(req, body, q);
  if (!token) return { error: { status: 401, code: 'missing_api_key', message: 'Send your API key in the X-API-Key header (or Authorization: Bearer).' } };
  const { data: apiKey, error } = await db().from('api_keys').select('*')
    .eq('key_hash', sha256(token)).eq('status', 1).maybeSingle();
  if (error) throw new Error(error.message);
  if (!apiKey) return { error: { status: 401, code: 'invalid_api_key', message: 'API key is invalid or has been revoked.' } };

  const user = await getUserById(apiKey.user_id);
  if (!user || Number(user.status) !== 1 || Number(user.level) > 3) {
    return { error: { status: 403, code: 'account_inactive', message: 'The account that owns this API key is not active.' } };
  }
  if (user.expiration_date && new Date(user.expiration_date).getTime() < Date.now()) {
    return { error: { status: 403, code: 'account_expired', message: 'The account that owns this API key has expired.' } };
  }
  await db().from('api_keys').update({ last_used_at: nowIso(), updated_at: nowIso() }).eq('id', apiKey.id);
  return { apiKey, user };
}

function publicKey(row) {
  if (!row) return null;
  return {
    id: row.id_keys,
    user_key: row.user_key,
    game: row.game,
    duration: Number(row.duration),
    max_devices: Number(row.max_devices || 1),
    status: Number(row.status),
    devices: deviceList(row.devices),
    device_count: deviceList(row.devices).length,
    expired_date: row.expired_date || null,
    price_paid: Number(row.price_paid || 0),
    refundable: Number(row.price_paid || 0) > 0 && !deviceList(row.devices).length && !row.expired_date,
    created_at: row.created_at || null,
    updated_at: row.updated_at || null,
  };
}

function ownsKey(row, user) {
  return String(row.registrator || '') === String(user.username) || Number(row.admin_id) === Number(user.id_users);
}

function parseUserKey(segment) {
  try { return decodeURIComponent(segment || '').trim(); } catch { return String(segment || '').trim(); }
}

function sanitizeDevices(value, maxDevices) {
  const raw = Array.isArray(value) ? value.join(',') : String(value ?? '');
  return Array.from(new Set(raw.split(/[\n,]+/).map((s) => s.replace(/[^A-Za-z0-9_-]/g, '')).filter(Boolean)))
    .slice(0, Math.max(1, num(maxDevices, 1))).join(',');
}

// ---------------------------------------------------------------------------
// /api/v1 router — every route requires a personal API key
// ---------------------------------------------------------------------------
export async function handleApiV1(req, res, body, q, path, method) {
  cors(res);
  if (method === 'OPTIONS') { res.statusCode = 204; return res.end(); }

  try {
    const auth = await authenticate(req, body, q);
    if (auth.error) return fail(res, auth.error.status, auth.error.code, auth.error.message);
    const { apiKey, user } = auth;

    // path is /api/v1/..., so parts[0..1] are "api","v1"
    const parts = String(path).split('/').filter(Boolean);
    const rest = parts.slice(2);

    const index = () => ok(res, {
      service: 'Anoy key-management API',
      version: 'v1',
      api_key: { id: apiKey.id, name: apiKey.name, prefix: apiKey.key_prefix },
      endpoints: [
        'GET    /api/v1/me',
        'GET    /api/v1/pricing',
        'GET    /api/v1/keys',
        'POST   /api/v1/keys',
        'GET    /api/v1/keys/{user_key}',
        'POST   /api/v1/keys/{user_key}   (update; PATCH also accepted)',
        'POST   /api/v1/keys/{user_key}/reset',
        'DELETE /api/v1/keys/{user_key}',
      ],
      base_url: config.baseUrl || null,
      docs: '/api-docs',
    });

    if (rest.length === 0) {
      if (method !== 'GET') return fail(res, 405, 'method_not_allowed', 'Use GET on /api/v1.');
      return index();
    }

    // ----------------------------- account -------------------------------
    if (rest[0] === 'me' && rest.length === 1) {
      if (method !== 'GET') return fail(res, 405, 'method_not_allowed', 'Use GET on /api/v1/me.');
      return ok(res, {
        account: {
          username: user.username, fullname: user.fullname, level: Number(user.level),
          saldo: Number(user.saldo || 0), currency: 'INR', status: Number(user.status),
        },
        api_key: {
          id: apiKey.id, name: apiKey.name,
          prefix: apiKey.key_prefix, last4: apiKey.key_last4,
          status: Number(apiKey.status), created_at: apiKey.created_at,
          last_used_at: apiKey.last_used_at,
        },
      });
    }

    if (rest[0] === 'pricing' && rest.length === 1) {
      if (method !== 'GET') return fail(res, 405, 'method_not_allowed', 'Use GET on /api/v1/pricing.');
      return ok(res, {
        currency: 'INR',
        rule: 'price = price_per_device_for_duration × max_devices × count',
        games: Object.keys(config.games),
        durations: Object.entries(config.prices).map(([hours, perDevice]) => ({
          duration: Number(hours), hours: Number(hours), label: config.durations[hours] || `${hours} Hours`,
          price_per_device: perDevice,
        })),
      });
    }

    // ------------------------------- keys --------------------------------
    if (rest[0] !== 'keys') return fail(res, 404, 'not_found', 'Unknown endpoint.');

    if (rest.length === 1) {
      if (method === 'GET') {
        const limit = Math.min(200, Math.max(1, Math.trunc(num(q.get('limit'), 50)) || 50));
        const offset = Math.max(0, Math.trunc(num(q.get('offset'), 0)) || 0);
        const game = str(q.get('game'));
        const statusFilter = str(q.get('status'));
        const { data, error } = await db().from('keys_code').select('*')
          .order('id_keys', { ascending: false }).limit(5000);
        if (error) throw new Error(error.message);
        let rows = (data || []).filter((row) => ownsKey(row, user));
        if (game) rows = rows.filter((row) => String(row.game) === game);
        if (statusFilter) rows = rows.filter((row) => String(row.status) === statusFilter);
        const total = rows.length;
        return ok(res, { keys: rows.slice(offset, offset + limit).map(publicKey), total, limit, offset });
      }
      if (method === 'POST') {
        const result = await createKeysForUser(user, {
          game: body.game ?? q.get('game'),
          duration: body.duration ?? q.get('duration'),
          max_devices: body.max_devices ?? q.get('max_devices'),
          count: body.count ?? q.get('count') ?? 1,
          custom_key: body.custom_key ?? q.get('custom_key') ?? '',
          request_id: body.request_id ?? q.get('request_id') ?? '',
        });
        if (!result.ok) {
          const status = result.code === 'insufficient_balance' ? 402
            : result.code === 'key_exists' || result.code === 'balance_conflict' ? 409
              : result.code === 'account_inactive' || result.code === 'account_expired' ? 403
                : result.code === 'rate_limited' ? 429
                  : result.code === 'service_unavailable' ? 503 : 400;
          return fail(res, status, result.code, result.message);
        }
        return ok(res, {
          keys: result.rows.map(publicKey),
          count: result.licenses.length,
          price: result.price, unit_price: result.unit_price, currency: 'INR',
          saldo_before: result.saldo_before, saldo_after: result.saldo_after,
          charged: result.price,
        }, 201);
      }
      return fail(res, 405, 'method_not_allowed', 'Use GET or POST on /api/v1/keys.');
    }

    const userKey = parseUserKey(rest[1]);
    if (!userKey) return fail(res, 400, 'invalid_request', 'Missing user_key in the path.');
    const key = await getKeyByUserKey(userKey);
    // 404 for both "not found" and "not yours" so keys cannot be enumerated.
    if (!key || !ownsKey(key, user)) return fail(res, 404, 'key_not_found', 'Licence key not found.');

    if (rest.length === 2) {
      if (method === 'GET') return ok(res, { key: publicKey(key) });

      if (method === 'DELETE') {
        const refund = await deleteKeysWithRefund([key.id_keys], user);
        if (!refund.ok) return fail(res, 500, 'delete_failed', refund.message);
        return ok(res, {
          deleted: refund.deleted > 0, user_key: key.user_key,
          refunded: refund.refunded, refund_total: refund.refund_total,
        });
      }

      if (method === 'POST' || method === 'PATCH' || method === 'PUT') {
        const patch = {};
        if (body.max_devices !== undefined) {
          const maxDevices = num(body.max_devices, -1);
          if (!Number.isInteger(maxDevices) || maxDevices < 1 || maxDevices > 100) {
            return fail(res, 400, 'invalid_request', 'max_devices must be an integer between 1 and 100.');
          }
          patch.max_devices = maxDevices;
        }
        if (body.duration !== undefined) {
          const duration = num(body.duration, 0);
          if (!config.prices[duration]) return fail(res, 400, 'invalid_request', 'duration must be one of the pricing durations.');
          patch.duration = duration;
        }
        if (body.game !== undefined) {
          const game = str(body.game);
          if (!config.games[game]) return fail(res, 400, 'invalid_request', 'game is not supported.');
          patch.game = game;
        }
        if (body.status !== undefined) {
          const status = num(body.status, 0);
          if (![1, 2, 3].includes(status)) return fail(res, 400, 'invalid_request', 'status must be 1 (active), 2 (blocked) or 3 (expired).');
          patch.status = status;
        }
        if (body.devices !== undefined) {
          patch.devices = sanitizeDevices(body.devices, patch.max_devices ?? key.max_devices) || null;
        }
        if (!Object.keys(patch).length) {
          return fail(res, 400, 'invalid_request', 'No updatable field supplied (max_devices, duration, game, status, devices).');
        }
        patch.updated_at = nowIso();
        const { data, error } = await db().from('keys_code').update(patch).eq('user_key', key.user_key).select('*').single();
        if (error) throw new Error(error.message);
        return ok(res, { key: publicKey(data), updated: Object.keys(patch).filter((k) => k !== 'updated_at') });
      }
      return fail(res, 405, 'method_not_allowed', 'Use GET, POST/PATCH or DELETE on a single key.');
    }

    if (rest.length === 3 && rest[2] === 'reset') {
      if (method !== 'POST') return fail(res, 405, 'method_not_allowed', 'Use POST on /reset.');
      const before = deviceList(key.devices).length;
      const { data, error } = await db().from('keys_code')
        .update({ devices: null, updated_at: nowIso() }).eq('user_key', key.user_key).select('*').single();
      if (error) throw new Error(error.message);
      return ok(res, { reset: true, user_key: key.user_key, devices_cleared: before, key: publicKey(data) });
    }

    return fail(res, 404, 'not_found', 'Unknown endpoint.');
  } catch (error) {
    console.error('[anoymundo] /api/v1 failed:', error);
    return fail(res, 500, 'internal_error', 'The request could not be completed.');
  }
}
