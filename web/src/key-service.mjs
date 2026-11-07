// Shared licence-key creation logic. Both the browser panel and the personal
// API-key endpoints go through this module so pricing, saldo deduction,
// key formatting and history rows can never drift apart.
//
// Security model (v1.2):
//   * price = price-per-device-for-duration x devices x NUMBER OF KEYS.
//     Bulk generation can never cost the same as a single key.
//   * The debit and every key insert happen in ONE database transaction
//     (public.create_keys_atomic). A timeout, a duplicate key, a DB error or a
//     Vercel function kill can never leave keys behind without charging, or
//     charge without creating keys.
//   * If the transaction function is unavailable we FAIL CLOSED: no key is
//     created and no balance is touched.
//   * Every request carries an idempotency id, so a double click / network
//     replay cannot create a second batch.
//   * price_paid is stored on each key so deleting a never-used key can refund
//     exactly what it cost, once, in one transaction.
import crypto from 'node:crypto';
import { config } from './config.mjs';
import { db, isMemory, getUserById, getKeyByUserKey } from './db.mjs';

const nowIso = () => new Date().toISOString();
const str = (v) => String(v ?? '').trim();
const num = (v, fallback = 0) => (Number.isFinite(Number(v)) ? Number(v) : fallback);
const KEY_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';

/** Cryptographically random A-Z0-9 string (uniform, no modulo bias). */
export function randomKeyPart(length = config.keySuffixLength) {
  let out = '';
  for (let i = 0; i < length; i += 1) out += KEY_ALPHABET[crypto.randomInt(KEY_ALPHABET.length)];
  return out;
}

/** "ANOY-7D-" for username Anoy and a 168 hour key. Always upper case. */
export function keyPrefix(username, duration) {
  const token = config.durationTokens[Number(duration)] || `${Number(duration) || 0}H`;
  const name = String(username || '').toUpperCase().replace(/[^A-Z0-9]/g, '') || 'KEY';
  return `${name}-${token}-`;
}

/** Normalises any licence key the same way everywhere (panel, API, loader). */
export function normalizeKey(value) {
  return String(value ?? '').trim().replace(/\s+/g, '').toUpperCase();
}

/**
 * Accepts the custom part a seller typed. A full key with the right prefix is
 * also accepted (people paste whole keys), the prefix is simply stripped.
 * Returns { suffix } or { error }.
 */
export function parseCustomSuffix(value, prefix) {
  let suffix = normalizeKey(value);
  if (prefix && suffix.startsWith(prefix)) suffix = suffix.slice(prefix.length);
  if (!suffix) return { suffix: '' };
  const min = config.customSuffixMin;
  const max = config.customSuffixMax;
  if (!new RegExp(`^[A-Z0-9]{${min},${max}}$`).test(suffix)) {
    return { error: `Custom part must be ${min}-${max} letters or numbers (A-Z, 0-9).` };
  }
  return { suffix };
}

export const MAX_BULK_KEYS = 100;

/** Total price for one generation request. `price` is the FULL amount due. */
export function quoteKeys(duration, maxDevices, count = 1) {
  const hours = num(duration, 0);
  const devices = num(maxDevices, 0);
  const qty = Number.isInteger(Number(count)) ? Math.trunc(Number(count)) : 1;
  const unitPrice = (config.prices[hours] || 0) * devices;
  return {
    price: unitPrice * qty, unit_price: unitPrice, count: qty,
    duration: hours, max_devices: devices, currency: 'INR',
  };
}

function buildPlan(opts) {
  const errors = {};
  const game = str(opts.game) || 'PUBG';
  const duration = Number(opts.duration);
  const maxDevices = Number(opts.max_devices);
  const count = Number(opts.count ?? 1);
  const customRaw = str(opts.custom_key);
  const requestId = str(opts.request_id);

  if (!config.games[game]) errors.game = 'Choose a valid game.';
  if (!Number.isInteger(duration) || !config.prices[duration]) errors.duration = 'Choose a valid duration.';
  if (!Number.isInteger(maxDevices) || maxDevices < 1 || maxDevices > 100) errors.max_devices = 'Devices must be 1-100.';
  if (!Number.isInteger(count) || count < 1 || count > MAX_BULK_KEYS) errors.count = `Keys per request must be 1-${MAX_BULK_KEYS}.`;
  // The custom part replaces the random part, so it always describes ONE key.
  let customKey = '';
  if (customRaw) {
    if (count !== 1) errors.custom_key = 'A custom key creates exactly one key. Set the quantity to 1.';
    const parsed = parseCustomSuffix(customRaw, opts.username ? keyPrefix(opts.username, duration) : '');
    if (parsed.error) errors.custom_key = parsed.error;
    else customKey = parsed.suffix;
  }
  if (requestId && !/^[A-Za-z0-9_-]{8,80}$/.test(requestId)) errors.request_id = 'Invalid request id.';

  const unitPrice = (config.prices[duration] || 0) * maxDevices;
  const total = unitPrice * (Number.isInteger(count) ? count : 0);
  if (!Number.isSafeInteger(unitPrice) || unitPrice <= 0 ||
      !Number.isSafeInteger(total) || total <= 0) {
    errors.duration = 'Invalid price for these options.';
  }
  return {
    errors, game, duration, maxDevices, count, customKey,
    requestId: requestId || `auto-${crypto.randomUUID()}`,
    unitPrice: Number.isSafeInteger(unitPrice) ? unitPrice : 0,
    total: Number.isSafeInteger(total) ? total : 0,
  };
}

async function existingKeys(candidates) {
  if (!candidates.length) return new Set();
  const { data, error } = await db().from('keys_code').select('user_key').in('user_key', candidates);
  if (error) throw new Error(error.message);
  return new Set((data || []).map((row) => row.user_key));
}

/**
 * Builds the final keys: USERNAME-DURATION-RANDOM (all upper case). Random
 * parts are re-rolled until they are unique in this batch AND in the
 * database, so a large batch never fails on an unlucky collision.
 * Returns null when a custom key is already taken.
 */
async function makeLicenses(fresh, plan) {
  const prefix = keyPrefix(fresh.username, plan.duration);
  if (plan.customKey) {
    const key = `${prefix}${plan.customKey}`;
    if ((await existingKeys([key])).has(key)) return null;
    return [key];
  }
  const chosen = new Set();
  for (let round = 0; round < 6 && chosen.size < plan.count; round += 1) {
    const batch = new Set();
    while (batch.size < plan.count - chosen.size) {
      const candidate = `${prefix}${randomKeyPart(round < 3 ? config.keySuffixLength : 6)}`;
      if (!chosen.has(candidate)) batch.add(candidate);
    }
    const taken = await existingKeys([...batch]);
    for (const candidate of batch) if (!taken.has(candidate)) chosen.add(candidate);
  }
  if (chosen.size < plan.count) throw new Error('could not allocate unique keys');
  return [...chosen];
}

/** In-process equivalent of the SQL transaction, used only by the offline
 *  memory driver (npm test and the local --memory preview). */
async function createLocal(fresh, plan, licenses) {
  const saldoBefore = Number(fresh.saldo || 0);
  const unlimited = isUnlimited(fresh);
  const charge = unlimited ? 0 : plan.total;
  const { data: existing } = await db().from('key_orders').select('*').eq('request_id', plan.requestId).maybeSingle();
  if (existing?.result) return { ...existing.result, ok: true };

  if (charge > 0) {
    const { data: debited, error: debitError } = await db().from('users')
      .update({ saldo: saldoBefore - charge, updated_at: nowIso() })
      .eq('id_users', fresh.id_users).eq('saldo', saldoBefore).select('id_users,saldo');
    if (debitError || !debited?.length) {
      return { ok: false, code: 'balance_conflict', message: 'Balance changed. Please try again.' };
    }
  }

  const rows = licenses.map((license) => ({
    game: plan.game, user_key: license, duration: plan.duration,
    max_devices: plan.maxDevices, registrator: fresh.username, admin_id: fresh.id_users,
    status: 1, expired_date: null, price_paid: unlimited ? 0 : plan.unitPrice,
    created_at: nowIso(), updated_at: nowIso(),
  }));
  const { data: inserted, error: insertError } = await db().from('keys_code').insert(rows).select();
  if (insertError) {
    if (charge > 0) await db().from('users').update({ saldo: saldoBefore, updated_at: nowIso() }).eq('id_users', fresh.id_users);
    return { ok: false, code: 'key_exists', message: 'That key already exists. Nothing was charged.' };
  }
  await db().from('history').insert({
    keys_id: '', user_do: fresh.username,
    info: `${plan.game}|${licenses[0].slice(0, 5)}|${plan.duration}|${plan.maxDevices}|${plan.count}|${charge}`,
    created_at: nowIso(), updated_at: nowIso(),
  });
  const result = {
    ok: true, keys: licenses, rows: inserted || [], count: licenses.length,
    price: charge, unit_price: unlimited ? 0 : plan.unitPrice, unlimited,
    saldo_before: saldoBefore, saldo_after: saldoBefore - charge,
  };
  await db().from('key_orders').insert({ request_id: plan.requestId, user_id: fresh.id_users, created_at: nowIso(), result });
  return result;
}

/**
 * Creates licence keys for `user` and debits their saldo, atomically.
 * Returns { ok:true, licenses, rows, price, count, saldo_before, saldo_after }
 *      or { ok:false, code, message, errors, price, required, available }.
 */
export async function createKeysForUser(user, opts = {}) {
  const plan = buildPlan({ ...opts, username: user?.username });
  if (Object.keys(plan.errors).length) {
    return {
      ok: false, code: 'invalid_request', message: 'Please check the key options.',
      errors: plan.errors, price: plan.total,
    };
  }

  // Always read a fresh balance, never trust a stale session/user object.
  let fresh;
  try {
    fresh = await getUserById(user?.id_users);
  } catch (error) {
    return { ok: false, code: 'service_unavailable', message: 'Cannot verify your balance right now. Nothing was charged.', price: plan.total };
  }
  if (!fresh || Number(fresh.status) !== 1) {
    return { ok: false, code: 'account_inactive', message: 'Account is not active.', price: plan.total };
  }
  if (fresh.expiration_date && new Date(fresh.expiration_date).getTime() < Date.now()) {
    return { ok: false, code: 'account_expired', message: 'Account has expired.', price: plan.total };
  }
  const saldo = Number(fresh.saldo || 0);
  const unlimited = isUnlimited(fresh);
  // A replayed request (double click, flaky network) returns the original
  // result instead of failing on its own, already-created custom key.
  try {
    const { data: previous } = await db().from('key_orders').select('result')
      .eq('request_id', plan.requestId).eq('user_id', fresh.id_users).maybeSingle();
    if (previous?.result?.ok) return finalResult(previous.result, plan, saldo, previous.result.keys || []);
  } catch { /* the transaction below re-checks idempotency */ }
  if (!unlimited && (!Number.isSafeInteger(saldo) || saldo < plan.total)) {
    return {
      ok: false, code: 'insufficient_balance', message: 'Not enough balance.',
      errors: { duration: `Need ₹${plan.total.toLocaleString('en-IN')}, you have ₹${saldo.toLocaleString('en-IN')}.` },
      price: plan.total, required: plan.total, available: saldo, saldo,
    };
  }

  let licenses;
  try {
    licenses = await makeLicenses(fresh, plan);
  } catch {
    return { ok: false, code: 'service_unavailable', message: 'Cannot verify key availability. Nothing was charged.', price: plan.total };
  }
  if (!licenses || !licenses.length) {
    const taken = `${keyPrefix(fresh.username, plan.duration)}${plan.customKey}`;
    return {
      ok: false, code: 'key_exists', message: `${taken} already exists. Choose a different custom part.`,
      errors: { custom_key: 'Already taken.' }, price: plan.total,
    };
  }

  let result;
  if (isMemory) {
    result = await createLocal(fresh, plan, licenses);
  } else {
    const { data, error } = await db().rpc('create_keys_atomic', {
      p_request_id: plan.requestId,
      p_user_id: fresh.id_users,
      p_game: plan.game,
      p_duration: plan.duration,
      p_max_devices: plan.maxDevices,
      p_unit_price: plan.unitPrice,
      p_total_price: plan.total,
      p_keys: licenses,
    });
    if (error) {
      // FAIL CLOSED. Never fall back to a non-transactional insert.
      console.error('[anoymundo] create_keys_atomic failed:', error.message);
      result = {
        ok: false, code: 'service_unavailable',
        message: 'Key generation is temporarily unavailable. No keys were created and nothing was charged.',
      };
    } else {
      try { result = typeof data === 'string' ? JSON.parse(data) : data; }
      catch { result = { ok: false, code: 'service_unavailable', message: 'Unexpected response. Nothing was charged.' }; }
    }
  }

  if (!result?.ok) {
    return {
      ok: false,
      code: result?.code || 'service_unavailable',
      message: result?.message || 'Could not create keys. Nothing was charged.',
      errors: { duration: result?.message || 'Please try again.' },
      price: plan.total,
      required: result?.required,
      available: result?.available ?? saldo,
      saldo: result?.saldo ?? saldo,
    };
  }
  return finalResult(result, plan, saldo, licenses);
}

function finalResult(result, plan, saldo, licenses) {
  const price = Number(result.price ?? plan.total);
  return {
    ok: true,
    licenses: result.keys || licenses,
    rows: result.rows || [],
    price,
    unit_price: Number(result.unit_price ?? plan.unitPrice),
    unlimited: !!result.unlimited,
    count: Number(result.count || (result.keys || licenses).length),
    game: plan.game,
    duration: plan.duration,
    max_devices: plan.maxDevices,
    saldo_before: Number(result.saldo_before ?? saldo),
    saldo_after: Number(result.saldo_after ?? (saldo - price)),
  };
}

/** The single Owner account has an unlimited balance and is never charged. */
export function isUnlimited(user) {
  return Number(user?.level) === 1 && String(user?.username) === config.ownerUsername;
}

/**
 * Deletes keys and refunds the exact price paid for keys that were never used
 * (no registered device and never activated). Refund goes back to the account
 * that created the key. Runs in one SQL transaction in production.
 */
export async function deleteKeysWithRefund(ids, actor) {
  const list = [...new Set((ids || []).map(Number).filter((n) => Number.isInteger(n) && n > 0))].slice(0, 500);
  if (!list.length) return { ok: true, deleted: 0, refunded: 0, refund_total: 0 };
  if (!actor?.id_users) return { ok: false, message: 'Please sign in again.' };

  if (!isMemory) {
    const { data, error } = await db().rpc('delete_keys_refund', { p_ids: list, p_actor_id: actor.id_users });
    if (error) {
      console.error('[anoymundo] delete_keys_refund failed:', error.message);
      return { ok: false, message: 'Could not delete keys. Nothing was changed.' };
    }
    let result = data;
    try { if (typeof result === 'string') result = JSON.parse(result); } catch { result = null; }
    if (!result?.ok) return { ok: false, message: result?.message || 'Could not delete keys.' };
    return {
      ok: true,
      deleted: Number(result.deleted || 0),
      refunded: Number(result.refunded || 0),
      refund_total: Number(result.refund_total || 0),
    };
  }

  // Offline driver: same rules, no network round trips.
  const allowAll = isUnlimited(actor);
  const { data: all } = await db().from('keys_code').select('*');
  const owned = (all || []).filter((k) => list.includes(Number(k.id_keys)) &&
    (allowAll || String(k.registrator || '') === String(actor.username)));
  const credits = new Map();
  let refunded = 0;
  let refundTotal = 0;
  for (const key of owned) {
    const neverUsed = (!key.devices || !String(key.devices).trim()) && !key.expired_date;
    const amount = neverUsed ? Number(key.price_paid || 0) : 0;
    if (amount > 0) {
      credits.set(key.registrator, (credits.get(key.registrator) || 0) + amount);
      refunded += 1;
      refundTotal += amount;
    }
    await db().from('keys_code').delete().eq('id_keys', key.id_keys);
  }
  for (const [username, amount] of credits) {
    const { data: rows } = await db().from('users').select('*').eq('username', username);
    for (const row of rows || []) {
      await db().from('users').update({ saldo: Number(row.saldo || 0) + amount, updated_at: nowIso() })
        .eq('id_users', row.id_users);
    }
  }
  return { ok: true, deleted: owned.length, refunded, refund_total: refundTotal };
}

export function refundMessage(result) {
  if (!result?.ok) return result?.message || 'Could not delete keys.';
  if (!result.deleted) return 'No keys were deleted.';
  const base = `Deleted ${result.deleted} key${result.deleted === 1 ? '' : 's'}.`;
  if (!result.refunded) return `${base} No unused keys to refund.`;
  return `${base} Refunded ₹${Number(result.refund_total).toLocaleString('en-IN')} for ${result.refunded} unused.`;
}
