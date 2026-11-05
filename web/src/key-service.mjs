// Shared licence-key creation logic. Both the browser panel and the personal
// API-key endpoints go through this module so pricing, saldo deduction,
// key formatting and history rows can never drift apart.
import crypto from 'node:crypto';
import { config } from './config.mjs';
import { db, getUserById, getKeyByUserKey } from './db.mjs';

const nowIso = () => new Date().toISOString();
const num = (v, fallback = 0) => (Number.isFinite(Number(v)) ? Number(v) : fallback);
const str = (v) => String(v ?? '').trim();
const randomAlnum = (n) => crypto.randomBytes(Math.ceil(n * 2)).toString('base64url')
  .replace(/[^A-Za-z0-9]/g, '').slice(0, n).padEnd(n, 'X');

/** Price of one key-generation request, identical to the panel's formula. */
export function quoteKeys(duration, maxDevices) {
  const hours = num(duration, 0);
  const devices = num(maxDevices, 0);
  return { price: (config.prices[hours] || 0) * devices, duration: hours, max_devices: devices, currency: 'INR' };
}

/**
 * Creates one or more licence keys for `user` and debits their saldo.
 *
 * opts: { game, duration, max_devices, count, custom_key }
 * returns { ok:true, licenses, rows, price, saldo_before, saldo_after, ... }
 *      or { ok:false, code, message, errors, price }
 */
export async function createKeysForUser(user, opts = {}) {
  const game = str(opts.game) || 'PUBG';
  const duration = num(opts.duration, 0);
  const maxDevices = num(opts.max_devices, 0);
  const count = Math.max(1, Math.min(100, Math.trunc(num(opts.count, 1)) || 1));
  const customKey = str(opts.custom_key);
  const errors = {};

  if (!config.games[game]) errors.game = 'Invalid game.';
  if (!Number.isInteger(maxDevices) || maxDevices < 1 || maxDevices > 100) errors.max_devices = 'Minimum devices is 1.';
  if (!config.prices[duration]) errors.duration = 'Invalid duration.';
  const { price } = quoteKeys(duration, maxDevices);
  if (price <= 0) errors.duration = 'Invalid duration or devices.';
  if (customKey) {
    if (count > 1) errors.custom_key = 'A custom key can only be used when count is 1.';
    else if (customKey.length < 4 || customKey.length > 19 || !/^[A-Za-z0-9_-]+$/.test(customKey)) {
      errors.custom_key = 'Custom Key is too Short/Long or has invalid characters.';
    }
  }
  if (Object.keys(errors).length) {
    return { ok: false, code: 'invalid_request', message: 'Invalid key parameters.', errors, price };
  }

  // Always read a fresh balance, never trust a stale session/user object.
  const fresh = await getUserById(user.id_users);
  if (!fresh || Number(fresh.status) !== 1) {
    return { ok: false, code: 'account_inactive', message: 'Account is not active.', price };
  }
  const saldoBefore = Number(fresh.saldo || 0);
  if (saldoBefore < price) {
    return {
      ok: false, code: 'insufficient_balance', message: 'Insufficient balance',
      errors: { duration: 'Insufficient balance' }, price, saldo: saldoBefore,
    };
  }
  if (customKey && (await getKeyByUserKey(customKey))) {
    return {
      ok: false, code: 'key_exists', message: 'Key already exists!!',
      errors: { user_key: 'Key already exists' }, price,
    };
  }

  const licenses = [];
  if (customKey) licenses.push(customKey);
  else {
    for (let i = 0; i < count; i += 1) {
      let candidate = null;
      for (let attempt = 0; attempt < 8; attempt += 1) {
        const value = `${fresh.username}-${duration}-${randomAlnum(5)}`;
        if (!licenses.includes(value) && !(await getKeyByUserKey(value))) { candidate = value; break; }
      }
      if (!candidate) {
        return { ok: false, code: 'generation_failed', message: 'Could not generate a unique key.', price };
      }
      licenses.push(candidate);
    }
  }

  // Optimistic conditional debit prevents two concurrent requests from
  // spending the same balance twice (works on Supabase and the memory driver).
  const { data: debited, error: debitError } = await db().from('users')
    .update({ saldo: saldoBefore - price, updated_at: nowIso() })
    .eq('id_users', fresh.id_users)
    .eq('saldo', saldoBefore)
    .select('id_users,saldo');
  if (debitError || !debited || !debited.length) {
    return { ok: false, code: 'balance_conflict', message: 'Balance changed, please retry.', price };
  }

  const rows = [];
  try {
    for (const license of licenses) {
      const { data, error } = await db().from('keys_code').insert({
        game, user_key: license, duration, max_devices: maxDevices,
        registrator: fresh.username, admin_id: fresh.id_users, status: 1,
        expired_date: null, created_at: nowIso(), updated_at: nowIso(),
      }).select().single();
      if (error) throw new Error(error.message);
      rows.push(data);
    }
  } catch (error) {
    // Never charge for a key that was not persisted.
    await db().from('users').update({ saldo: saldoBefore, updated_at: nowIso() }).eq('id_users', fresh.id_users);
    return { ok: false, code: 'insert_failed', message: `Could not generate key: ${error.message}`, price };
  }

  await db().from('history').insert({
    keys_id: String(rows.at(-1)?.id_keys ?? ''), user_do: fresh.username,
    info: `${game}|${licenses[0].slice(0, 5)}|${duration}|${maxDevices}`,
    created_at: nowIso(), updated_at: nowIso(),
  });

  return {
    ok: true, licenses, rows, price, game, duration, max_devices: maxDevices,
    saldo_before: saldoBefore, saldo_after: saldoBefore - price,
  };
}
