// Private operational provisioning; deliberately NOT exposed by a panel
// route or role selector. Public registration can only claim the fixed slot.
import crypto from 'node:crypto';
import { db, isMemory } from './db.mjs';
import { codeHash } from './auth.mjs';
import { ownerChannel } from './channels.mjs';
import { referralResult, referralTransaction } from './referrals.mjs';

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const randomCode = () => Array.from({ length: 7 }, () => ALPHABET[crypto.randomInt(ALPHABET.length)]).join('');

export async function createOwnerInvite(user) {
  if (ownerChannel(user) !== 'anoy' || Number(user.status) !== 1) return { ok: false, message: 'Owner invitation unavailable.' };
  const referral = randomCode();
  if (!isMemory) return referralResult(await db().rpc('create_owner_invite', {
    p_actor_id: user.id_users, p_code_hash: codeHash(referral), p_referral: referral,
  }));
  return referralTransaction(async () => {
    if (db()._store.users.some(u => u.owner_channel === 'kenny')) return { ok: false, message: 'Both owner accounts already exist.' };
    const { data: old } = await db().from('referral_code').select('*').eq('level', 1).eq('used_by', '').is('revoked_at', null).maybeSingle();
    if (old && new Date(old.invite_expires_at).getTime() > Date.now()) return { ok: true, referral: old.referral, expires_at: old.invite_expires_at };
    if (old) await db().from('referral_code').update({ revoked_at: new Date().toISOString() }).eq('id_reff', old.id_reff);
    const expires = new Date(Date.now() + 7 * 86400_000).toISOString();
    const { error } = await db().from('referral_code').insert({
      code: codeHash(referral), referral, level: 1, owner_channel: 'kenny', set_saldo: 0,
      used_by: '', created_by: user.username, acc_expiration: null, invite_expires_at: expires,
      revoked_at: null, created_at: new Date().toISOString(), updated_at: new Date().toISOString(),
    });
    if (error) throw new Error('Could not provision the Owner invitation.');
    return { ok: true, referral, expires_at: expires };
  });
}
