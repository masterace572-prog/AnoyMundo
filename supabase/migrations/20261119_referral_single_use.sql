-- Referral deletion is revocation: keep a private tombstone so neither a
-- consumed nor a deleted code can ever be issued again. No account is deleted.
alter table public.referral_code add column if not exists revoked_at timestamptz;
create unique index if not exists referral_code_hash_unique on public.referral_code(code);
create unique index if not exists referral_code_text_unique on public.referral_code(lower(referral));
drop index if exists public.referral_one_owner_invite;
create unique index referral_one_owner_invite on public.referral_code(owner_channel)
  where level = 1 and used_by = '' and revoked_at is null;

create or replace function public.protect_referral_lifecycle() returns trigger
language plpgsql set search_path = public, pg_temp as $$
begin
  if tg_op = 'DELETE' then
    if old.used_by <> '' or old.revoked_at is not null then
      raise exception 'Used or deleted referrals cannot be reissued';
    end if;
    return old;
  end if;
  if new.code is distinct from old.code or new.referral is distinct from old.referral
      or new.level is distinct from old.level or new.owner_channel is distinct from old.owner_channel
      or new.created_by is distinct from old.created_by or new.set_saldo is distinct from old.set_saldo
      or new.acc_expiration is distinct from old.acc_expiration
      or new.invite_expires_at is distinct from old.invite_expires_at then
    raise exception 'Referral details cannot be changed';
  end if;
  if (old.used_by <> '' and new.used_by is distinct from old.used_by)
      or (old.revoked_at is not null and new.revoked_at is distinct from old.revoked_at)
      or (old.revoked_at is not null and new.used_by is distinct from old.used_by) then
    raise exception 'A referral can be used only once and cannot be restored';
  end if;
  return new;
end $$;
drop trigger if exists protect_referral_lifecycle on public.referral_code;
create trigger protect_referral_lifecycle before update or delete on public.referral_code
for each row execute function public.protect_referral_lifecycle();

-- Owner accounts are permanent. Invitation validity is a separate field on
-- referral_code and is NEVER copied to either Owner's expiration_date.
update public.users set expiration_date = null where level = 1 and expiration_date is not null;
alter table public.users drop constraint if exists users_owner_permanent_check;
alter table public.users add constraint users_owner_permanent_check check (level <> 1 or expiration_date is null);

-- Operational provisioning only: there is NO panel action for creating an
-- Owner invitation. Retain this service-only RPC for the one fixed slot.
create or replace function public.create_owner_invite(p_actor_id integer, p_code_hash text, p_referral text)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare v_code public.referral_code%rowtype;
begin
  perform pg_advisory_xact_lock(hashtextextended('anoymundo.two_owners', 0));
  if not exists (select 1 from public.users where id_users = p_actor_id and level = 1
      and owner_channel = 'anoy' and status = 1) then
    return jsonb_build_object('ok', false, 'message', 'Owner invitation unavailable.');
  end if;
  if exists (select 1 from public.users where owner_channel = 'kenny') then
    return jsonb_build_object('ok', false, 'message', 'Both owner accounts already exist.');
  end if;
  select * into v_code from public.referral_code where level = 1 and owner_channel = 'kenny'
    and used_by = '' and revoked_at is null for update;
  if found and v_code.invite_expires_at > now() then
    return jsonb_build_object('ok', true, 'referral', v_code.referral, 'expires_at', v_code.invite_expires_at);
  end if;
  if p_referral is null or p_referral !~ '^[A-Z2-9]{7}$'
      or p_code_hash is null or p_code_hash !~ '^[a-f0-9]{32}$' then
    return jsonb_build_object('ok', false, 'message', 'Invalid invitation.');
  end if;
  update public.referral_code set revoked_at = now(), updated_at = now()
    where level = 1 and owner_channel = 'kenny' and used_by = '' and revoked_at is null;
  insert into public.referral_code(code, referral, level, owner_channel, set_saldo, used_by, created_by, acc_expiration, invite_expires_at)
    select p_code_hash, p_referral, 1, 'kenny', 0, '', username, null, now() + interval '7 days'
    from public.users where id_users = p_actor_id returning * into v_code;
  return jsonb_build_object('ok', true, 'referral', v_code.referral, 'expires_at', v_code.invite_expires_at);
exception when unique_violation then
  return jsonb_build_object('ok', false, 'message', 'Invitation unavailable. Try a new code.');
end $$;

create or replace function public.register_owner_from_referral(
  p_code_hash text, p_username text, p_fullname text, p_email text, p_password_hash text, p_ip text
) returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare v_code public.referral_code%rowtype; v_id integer;
begin
  perform pg_advisory_xact_lock(hashtextextended('anoymundo.two_owners', 0));
  select * into v_code from public.referral_code where code = p_code_hash and level = 1
    and owner_channel = 'kenny' for update;
  if not found or v_code.used_by <> '' or v_code.revoked_at is not null
      or v_code.invite_expires_at is null or v_code.invite_expires_at <= now() then
    return jsonb_build_object('ok', false, 'message', 'This referral is invalid, expired or already used.');
  end if;
  if exists (select 1 from public.users where owner_channel = 'kenny') then
    return jsonb_build_object('ok', false, 'message', 'Both owner accounts already exist.');
  end if;
  if p_username is null or p_username !~ '^[A-Za-z0-9]{4,25}$'
      or p_fullname is null or p_fullname !~ '^[A-Za-z][A-Za-z ]{1,39}$'
      or p_email is null or length(p_email) not between 5 and 40 or p_email !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
      or p_password_hash is null or p_password_hash !~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$' then
    return jsonb_build_object('ok', false, 'message', 'Invalid account details.');
  end if;
  insert into public.users(username, fullname, email, password, level, owner_channel, saldo, status, uplink, user_ip, expiration_date)
    values(p_username, p_fullname, p_email, p_password_hash, 1, 'kenny', 0, 1, v_code.created_by, left(coalesce(p_ip, ''), 155), null)
    returning id_users into v_id;
  update public.referral_code set used_by = p_username, updated_at = now() where id_reff = v_code.id_reff;
  return jsonb_build_object('ok', true, 'user_id', v_id, 'channel', 'kenny');
exception when unique_violation then
  return jsonb_build_object('ok', false, 'message', 'That username is taken or the owner slot was just filled.');
end $$;

-- The account insert and one-time claim share ONE PostgreSQL transaction and
-- row lock. Racing signups, revocation and failed inserts cannot reuse a code.
create or replace function public.register_account_from_referral(
  p_code_hash text, p_username text, p_fullname text, p_email text, p_password_hash text, p_ip text
) returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare v_code public.referral_code%rowtype; v_id integer;
begin
  select * into v_code from public.referral_code where code = p_code_hash for update;
  if not found or v_code.level not in (2, 3) or v_code.owner_channel is not null
      or v_code.used_by <> '' or v_code.revoked_at is not null
      or (v_code.acc_expiration is not null and v_code.acc_expiration <= now()) then
    return jsonb_build_object('ok', false, 'message', 'This referral is invalid, expired or already used.');
  end if;
  if p_username is null or p_username !~ '^[A-Za-z0-9]{4,25}$'
      or p_fullname is null or p_fullname !~ '^[A-Za-z][A-Za-z ]{1,39}$'
      or p_email is null or length(p_email) not between 5 and 40 or p_email !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
      or p_password_hash is null or p_password_hash !~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$'
      or v_code.set_saldo not between 0 and 99999 then
    return jsonb_build_object('ok', false, 'message', 'Invalid account details.');
  end if;
  insert into public.users(username, fullname, email, password, level, owner_channel, saldo, status, uplink, user_ip, expiration_date)
    values(p_username, p_fullname, p_email, p_password_hash, v_code.level, null, v_code.set_saldo, 1,
      v_code.created_by, left(coalesce(p_ip, ''), 155), v_code.acc_expiration) returning id_users into v_id;
  update public.referral_code set used_by = p_username, updated_at = now() where id_reff = v_code.id_reff;
  return jsonb_build_object('ok', true, 'user_id', v_id);
exception when unique_violation then
  return jsonb_build_object('ok', false, 'message', 'That username is taken.');
end $$;

create or replace function public.revoke_referral(p_actor_id integer, p_referral_id integer)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare v_actor public.users%rowtype; v_code public.referral_code%rowtype;
begin
  select * into v_actor from public.users where id_users = p_actor_id;
  if not found or v_actor.status <> 1 or v_actor.level not in (1, 2)
      or (v_actor.level <> 1 and v_actor.expiration_date is not null and v_actor.expiration_date <= now()) then
    return jsonb_build_object('ok', false, 'message', 'Referral unavailable.');
  end if;
  select * into v_code from public.referral_code where id_reff = p_referral_id for update;
  if not found or (v_actor.level <> 1 and (v_code.created_by <> v_actor.username or v_code.level <> 3))
      or (v_code.level = 1 and v_code.created_by <> v_actor.username) then
    return jsonb_build_object('ok', false, 'message', 'Referral unavailable.');
  end if;
  if v_code.revoked_at is null then
    update public.referral_code set revoked_at = now(), updated_at = now() where id_reff = v_code.id_reff;
  end if;
  return jsonb_build_object('ok', true);
end $$;

revoke all on function public.create_owner_invite(integer, text, text) from public, anon, authenticated;
revoke all on function public.register_owner_from_referral(text, text, text, text, text, text) from public, anon, authenticated;
revoke all on function public.register_account_from_referral(text, text, text, text, text, text) from public, anon, authenticated;
revoke all on function public.revoke_referral(integer, integer) from public, anon, authenticated;
grant execute on function public.create_owner_invite(integer, text, text) to service_role;
grant execute on function public.register_owner_from_referral(text, text, text, text, text, text) to service_role;
grant execute on function public.register_account_from_referral(text, text, text, text, text, text) to service_role;
grant execute on function public.revoke_referral(integer, integer) to service_role;
notify pgrst, 'reload schema';
