import { createClient } from '@supabase/supabase-js';
import { config } from './config.mjs';
import { createMemoryDb } from './memory-db.mjs';

let client;
export const isMemory = process.env.DB_DRIVER === 'memory';

export function db() {
  if (!client) {
    if (isMemory) {
      client = createMemoryDb();
    } else {
      if (!config.supabaseUrl || !config.supabaseServiceKey) {
        throw new Error('Supabase is not configured: set SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY');
      }
      client = createClient(config.supabaseUrl, config.supabaseServiceKey, {
        auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
        global: { headers: { 'X-Client-Info': 'anoymundo-panel/1.0' } },
      });
    }
  }
  return client;
}

export function unwrap(result) {
  if (result.error) throw new Error(`${result.error.message} (${result.error.code || 'db'})`);
  return result.data;
}

export const one = async (query) => unwrap(await query);

// ---------------------------------------------------------------------------
// users
// ---------------------------------------------------------------------------
export async function getUserById(id) {
  const { data, error } = await db().from('users').select('*').eq('id_users', id).maybeSingle();
  if (error) throw new Error(error.message);
  return data;
}

export async function getUserByUsername(username) {
  const name = String(username ?? '').trim();
  if (!name) return null;
  const { data, error } = await db().from('users').select('*').eq('username', name).maybeSingle();
  if (error) throw new Error(error.message);
  if (data || !/^[A-Za-z0-9]+$/.test(name)) return data;
  // Usernames are unique case-insensitively in practice: "anoy" signs in as
  // "Anoy". Only alphanumeric names reach this lookup, so no LIKE wildcards.
  const { data: rows, error: ciError } = await db().from('users').select('*').ilike('username', name).limit(5);
  if (ciError) throw new Error(ciError.message);
  const matches = (rows || []).filter((row) => String(row.username).toLowerCase() === name.toLowerCase());
  return matches.length === 1 ? matches[0] : null;
}

export async function listUsers() {
  return unwrap(await db().from('users').select('*').order('id_users', { ascending: true }));
}

export async function countRows(table, column = '*') {
  const { count, error } = await db().from(table).select(column, { count: 'exact', head: true });
  if (error) throw new Error(error.message);
  return count || 0;
}

// ---------------------------------------------------------------------------
// settings / lookup tables
// ---------------------------------------------------------------------------
export async function getSettings() {
  const [onoff, ftext, feature, modname, credit, libRows, release] = await Promise.all([
    db().from('onoff').select('*').eq('id', 1).maybeSingle(),
    db().from('_ftext').select('*').eq('id', 1).maybeSingle(),
    db().from('feature').select('*').eq('id', 1).maybeSingle(),
    db().from('modname').select('*').eq('id', 1).maybeSingle(),
    db().from('credit').select('*').eq('id', 1).maybeSingle(),
    db().from('lib').select('*').order('id', { ascending: false }).limit(1),
    db().from('client_release').select('id,version,file_name,file_size,sha256,external_url,notes,is_current,uploaded_by,created_at').eq('is_current', true).maybeSingle(),
  ]);
  for (const r of [onoff, ftext, feature, modname, credit, libRows, release]) {
    if (r.error) throw new Error(r.error.message);
  }
  return {
    onoff: onoff.data || { status: 'off', myinput: '' },
    ftext: ftext.data || { _status: 'Safe', _ftext: 'MOD STATUS :- 100% SAFE' },
    feature: feature.data || {},
    modname: modname.data || { modname: 'VIP MOD' },
    credit: credit.data || { name: '' },
    lib: libRows.data?.[0] || null,
    release: release.data || null,
  };
}

// ---------------------------------------------------------------------------
// keys
// ---------------------------------------------------------------------------
export async function getKeyById(id) {
  return unwrap(await db().from('keys_code').select('*').eq('id_keys', id).maybeSingle());
}

export async function getKeyByUserKey(userKey) {
  const raw = String(userKey ?? '').trim();
  if (!raw) return null;
  const found = unwrap(await db().from('keys_code').select('*').eq('user_key', raw).maybeSingle());
  if (found || raw.toUpperCase() === raw) return found;
  return unwrap(await db().from('keys_code').select('*').eq('user_key', raw.toUpperCase()).maybeSingle());
}

/** Licence lookup used by /connect. Keys are upper case; legacy mixed-case
 *  keys are still found by their exact spelling. */
export async function getKeyForGame(userKey, game) {
  const raw = String(userKey ?? '').trim();
  const upper = raw.toUpperCase();
  const found = unwrap(await db().from('keys_code').select('*').eq('user_key', upper).eq('game', game).maybeSingle());
  if (found || upper === raw) return found;
  return unwrap(await db().from('keys_code').select('*').eq('user_key', raw).eq('game', game).maybeSingle());
}

export async function getRecentHistory(limit = 10) {
  return unwrap(await db().from('history').select('*').order('id_history', { ascending: false }).limit(limit));
}

export async function getReferralCodes(limit = 10) {
  return unwrap(await db().from('referral_code').select('*').order('id_reff', { ascending: false }).limit(limit));
}

// ---------------------------------------------------------------------------
// helpers shared by the licensing API
// ---------------------------------------------------------------------------
export function deviceList(devices) {
  if (!devices) return [];
  return String(devices).split(',').map((s) => s.trim()).filter(Boolean);
}
