import crypto from 'node:crypto';
import { db, isMemory } from './db.mjs';
import { codeHash } from './auth.mjs';
import { ownerChannel } from './channels.mjs';

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const randomCode = () => Array.from({ length: 7 }, () => ALPHABET[crypto.randomInt(ALPHABET.length)]).join('');
const resultOf = ({ data, error }) => {
  if (error) throw new Error(error.message);
  return typeof data === 'string' ? JSON.parse(data) : data;
};

// Offline equivalent only. PostgreSQL uses one advisory lock + transaction.
let pending = Promise.resolve();
function localTransaction(fn) {
  const next = pending.then(fn, fn);
  pending = next.catch(() => {});
  return next;
}

export async function canInviteOwner(user) {
  if (ownerChannel(user) !== 'anoy') return false;
  const { data, error } = await db().from('users').select('id_users').eq('owner_channel', 'kenny').maybeSingle();
  if (error) throw new Error(error.message);
  return !data;
}

export async function createOwnerInvite(user) {
  if (ownerChannel(user) !== 'anoy') return { ok: false, message: 'Only Anoy can invite the KennyPvt owner.' };
  const referral = randomCode();
  if (!isMemory) return resultOf(await db().rpc('create_owner_invite', {
    p_actor_id: user.id_users, p_code_hash: codeHash(referral), p_referral: referral,
  }));
  return localTransaction(async () => {
    if (!await canInviteOwner(user)) return { ok: false, message: 'Both owner accounts already exist. No more owners can be created.' };
    const { data: old } = await db().from('referral_code').select('*').eq('level', 1).eq('used_by', '').maybeSingle();
    if (old && new Date(old.invite_expires_at).getTime() > Date.now()) return { ok: true, referral: old.referral, expires_at: old.invite_expires_at };
    if (old) await db().from('referral_code').delete().eq('id_reff', old.id_reff);
    const expires = new Date(Date.now() + 7 * 86400_000).toISOString();
    const { error } = await db().from('referral_code').insert({
      code: codeHash(referral), referral, level: 1, owner_channel: 'kenny', set_saldo: 0,
      used_by: '', created_by: user.username, acc_expiration: null, invite_expires_at: expires,
      created_at: new Date().toISOString(), updated_at: new Date().toISOString(),
    });
    if (error) throw new Error(error.message);
    return { ok: true, referral, expires_at: expires };
  });
}

export async function registerOwner(code, details) {
  const args = {
    p_code_hash: code.code, p_username: details.username, p_fullname: details.fullname,
    p_email: details.email, p_password_hash: details.password, p_ip: details.user_ip,
  };
  if (!isMemory) return resultOf(await db().rpc('register_owner_from_referral', args));
  return localTransaction(async () => {
    const { data: fresh } = await db().from('referral_code').select('*').eq('id_reff', code.id_reff).maybeSingle();
    const { data: existing } = await db().from('users').select('id_users').eq('owner_channel', 'kenny').maybeSingle();
    if (existing || !fresh || fresh.used_by || fresh.owner_channel !== 'kenny'
        || new Date(fresh.invite_expires_at || 0).getTime() <= Date.now()) {
      return { ok: false, message: 'This owner referral is invalid, expired or already used.' };
    }
    const { error } = await db().from('users').insert({
      ...details, level: 1, owner_channel: 'kenny', saldo: 0, status: 1, uplink: fresh.created_by,
      expiration_date: null, created_at: new Date().toISOString(), updated_at: new Date().toISOString(),
    });
    if (error) return { ok: false, message: 'That username is taken.' };
    await db().from('referral_code').update({ used_by: details.username }).eq('id_reff', fresh.id_reff);
    return { ok: true, channel: 'kenny' };
  });
}
