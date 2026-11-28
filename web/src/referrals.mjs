import { db, isMemory } from './db.mjs';

export function referralResult({ data, error }) {
  // Database error details can contain submitted account data. Never log them.
  if (error) throw new Error('Referral action failed. Please try again.');
  return typeof data === 'string' ? JSON.parse(data) : data;
}

// Offline equivalent of PostgreSQL row/advisory locks. Registration, private
// Owner provisioning and deletion all share this queue; production uses RPCs.
let pending = Promise.resolve();
export function referralTransaction(fn) {
  const next = pending.then(fn, fn);
  pending = next.catch(() => {});
  return next;
}

const unavailable = () => ({ ok: false, message: 'This referral is invalid, expired or already used.' });

export async function registerFromReferral(code, details) {
  if (!isMemory) return referralResult(await db().rpc(Number(code.level) === 1
    ? 'register_owner_from_referral' : 'register_account_from_referral', {
    p_code_hash: code.code, p_username: details.username, p_fullname: details.fullname,
    p_email: details.email, p_password_hash: details.password, p_ip: details.user_ip,
  }));
  return referralTransaction(async () => {
    const { data: fresh, error: readError } = await db().from('referral_code').select('*').eq('id_reff', code.id_reff).maybeSingle();
    const level = Number(fresh?.level);
    const owner = level === 1;
    const expires = owner ? fresh?.invite_expires_at : fresh?.acc_expiration;
    if (readError || !fresh || fresh.code !== code.code || fresh.used_by || fresh.revoked_at
        || ![1, 2, 3].includes(level) || (expires && new Date(expires).getTime() <= Date.now())
        || (owner && (fresh.owner_channel !== 'kenny' || !expires))
        || (!owner && fresh.owner_channel != null)) return unavailable();
    if (owner && db()._store.users.some(u => u.owner_channel === 'kenny')) return unavailable();
    const row = {
      username: details.username, fullname: details.fullname, email: details.email,
      password: details.password, user_ip: String(details.user_ip || '').slice(0, 155),
      level, owner_channel: owner ? 'kenny' : null, saldo: owner ? 0 : Number(fresh.set_saldo),
      status: 1, uplink: fresh.created_by, expiration_date: owner ? null : fresh.acc_expiration || null,
      created_at: new Date().toISOString(), updated_at: new Date().toISOString(),
    };
    const { data: inserted, error } = await db().from('users').insert(row).select('id_users').single();
    if (error) return { ok: false, message: 'Could not create the account. That username may be taken.' };
    const claim = await db().from('referral_code').update({ used_by: details.username }).eq('id_reff', fresh.id_reff).eq('used_by', '').is('revoked_at', null);
    if (claim.error || !claim.data?.length) {
      // Offline rollback only. Production inserts + claims in one transaction.
      const index = db()._store.users.findIndex(u => u.id_users === inserted.id_users);
      if (index !== -1) db()._store.users.splice(index, 1);
      return unavailable();
    }
    return { ok: true, user_id: inserted.id_users, ...(owner ? { channel: 'kenny' } : {}) };
  });
}

export async function revokeReferral(user, id) {
  if (!Number.isSafeInteger(id) || id < 1) return { ok: false, message: 'Referral unavailable.' };
  if (!isMemory) return referralResult(await db().rpc('revoke_referral', {
    p_actor_id: user.id_users, p_referral_id: id,
  }));
  return referralTransaction(async () => {
    const { data: actor } = await db().from('users').select('*').eq('id_users', user.id_users).maybeSingle();
    const { data: code } = await db().from('referral_code').select('*').eq('id_reff', id).maybeSingle();
    const owner = Number(actor?.level) === 1;
    if (!actor || Number(actor.status) !== 1 || ![1, 2].includes(Number(actor.level)) || !code
        || (!owner && actor.expiration_date && new Date(actor.expiration_date).getTime() <= Date.now())
        || (!owner && (code.created_by !== actor.username || Number(code.level) !== 3))
        || (Number(code.level) === 1 && code.created_by !== actor.username)) {
      return { ok: false, message: 'Referral unavailable.' };
    }
    if (code.revoked_at) return { ok: true };
    const { error } = await db().from('referral_code').update({ revoked_at: new Date().toISOString() }).eq('id_reff', id);
    return error ? { ok: false, message: 'Could not delete the referral.' } : { ok: true };
  });
}
