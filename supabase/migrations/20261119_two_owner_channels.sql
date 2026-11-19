-- Two fixed brands on one panel. NON-DESTRUCTIVE: existing users, keys,
-- library bytes and APK update rows remain in Anoy's channel.
alter table public.users add column if not exists owner_channel text;
update public.users set owner_channel = 'anoy' where level = 1 and username = 'Anoy' and owner_channel is null;
drop index if exists public.users_single_owner_idx;
create unique index if not exists users_owner_channel_unique on public.users(owner_channel) where owner_channel is not null;
alter table public.users drop constraint if exists users_owner_channel_check;
alter table public.users add constraint users_owner_channel_check check (
  (level = 1 and owner_channel is not null and owner_channel in ('anoy', 'kenny'))
  or (level <> 1 and owner_channel is null)
);

-- Owner identities cannot be swapped, demoted, renamed or deleted through
-- account-management code. Password/full-name changes in Settings still work.
create or replace function public.protect_owner_identity() returns trigger
language plpgsql set search_path = public, pg_temp as $$
begin
  if tg_op = 'DELETE' then
    if old.level = 1 then raise exception 'Owner accounts cannot be deleted'; end if;
    return old;
  end if;
  if (old.level = 1 and (new.level <> 1 or new.username <> old.username
      or new.owner_channel is distinct from old.owner_channel or new.status <> old.status))
      or (old.level <> 1 and new.level = 1) then
    raise exception 'Owner identities cannot be changed';
  end if;
  return new;
end $$;
drop trigger if exists protect_owner_identity on public.users;
create trigger protect_owner_identity before update or delete on public.users
for each row execute function public.protect_owner_identity();

alter table public.referral_code add column if not exists owner_channel text;
alter table public.referral_code add column if not exists invite_expires_at timestamptz;
alter table public.referral_code drop constraint if exists referral_owner_channel_check;
alter table public.referral_code add constraint referral_owner_channel_check check (
  (level = 1 and owner_channel = 'kenny' and owner_channel is not null and invite_expires_at is not null)
  or (level in (2, 3) and owner_channel is null)
);
create unique index if not exists referral_one_owner_invite on public.referral_code(owner_channel)
  where level = 1 and used_by = '';

alter table public.client_release add column if not exists channel text not null default 'anoy';
alter table public.client_release add column if not exists storage_path text;
alter table public.app_release add column if not exists channel text not null default 'anoy';
alter table public.lib add column if not exists channel text not null default 'anoy';
alter table public.client_release drop constraint if exists client_release_channel_check;
alter table public.client_release add constraint client_release_channel_check check (channel in ('anoy', 'kenny'));
alter table public.lib drop constraint if exists lib_channel_check;
alter table public.lib add constraint lib_channel_check check (channel in ('anoy', 'kenny'));
alter table public.app_release drop constraint if exists app_release_channel_check;
alter table public.app_release add constraint app_release_channel_check check (channel in ('anoy', 'kenny'));
drop index if exists public.client_release_one_current;
create unique index if not exists client_release_one_current on public.client_release(channel) where is_current;
drop index if exists public.app_release_one_current;
create unique index if not exists app_release_one_current on public.app_release(channel) where is_current;

-- Files bypass Vercel's 4.5 MB request limit. No public bucket or public write
-- policy: the server grants short-lived upload access to one random object.
-- Inputs are capped at 10 MiB in the app; 1 MiB extra allows ZIP overhead.
insert into storage.buckets(id, name, public, file_size_limit)
values ('loader-releases', 'loader-releases', false, 11534336)
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit;

create or replace function public.create_owner_invite(p_actor_id integer, p_code_hash text, p_referral text)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare v_code public.referral_code%rowtype;
begin
  perform pg_advisory_xact_lock(hashtextextended('anoymundo.two_owners', 0));
  if not exists (select 1 from public.users where id_users = p_actor_id and level = 1
      and owner_channel = 'anoy' and status = 1) then
    return jsonb_build_object('ok', false, 'message', 'Only Anoy can invite the KennyPvt owner.');
  end if;
  if exists (select 1 from public.users where owner_channel = 'kenny') then
    return jsonb_build_object('ok', false, 'message', 'Both owner accounts already exist. No more owners can be created.');
  end if;
  select * into v_code from public.referral_code where level = 1 and owner_channel = 'kenny' and used_by = '' for update;
  if found and v_code.invite_expires_at > now() then
    return jsonb_build_object('ok', true, 'referral', v_code.referral, 'expires_at', v_code.invite_expires_at);
  end if;
  delete from public.referral_code where level = 1 and owner_channel = 'kenny' and used_by = '';
  if p_referral !~ '^[A-Z2-9]{7}$' or p_code_hash !~ '^[a-f0-9]{32}$' then
    return jsonb_build_object('ok', false, 'message', 'Invalid invitation.');
  end if;
  insert into public.referral_code(code, referral, level, owner_channel, set_saldo, used_by, created_by, acc_expiration, invite_expires_at)
    select p_code_hash, p_referral, 1, 'kenny', 0, '', username, null, now() + interval '7 days'
    from public.users where id_users = p_actor_id
    returning * into v_code;
  return jsonb_build_object('ok', true, 'referral', v_code.referral, 'expires_at', v_code.invite_expires_at);
end $$;

create or replace function public.register_owner_from_referral(
  p_code_hash text, p_username text, p_fullname text, p_email text, p_password_hash text, p_ip text
) returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare v_code public.referral_code%rowtype; v_id integer;
begin
  perform pg_advisory_xact_lock(hashtextextended('anoymundo.two_owners', 0));
  select * into v_code from public.referral_code where code = p_code_hash and level = 1
    and owner_channel = 'kenny' and used_by = '' for update;
  if not found or v_code.invite_expires_at <= now() or v_code.invite_expires_at is null then
    return jsonb_build_object('ok', false, 'message', 'This owner referral is invalid, expired or already used.');
  end if;
  if exists (select 1 from public.users where owner_channel = 'kenny') then
    return jsonb_build_object('ok', false, 'message', 'Both owner accounts already exist.');
  end if;
  if p_username !~ '^[A-Za-z0-9]{4,25}$' or length(p_fullname) not between 2 and 40
      or length(p_email) not between 5 and 40 or p_password_hash !~ '^\$2[aby]\$[0-9]{2}\$' then
    return jsonb_build_object('ok', false, 'message', 'Invalid account details.');
  end if;
  insert into public.users(username, fullname, email, password, level, owner_channel, saldo, status, uplink, user_ip, expiration_date)
    values(p_username, p_fullname, p_email, p_password_hash, 1, 'kenny', 0, 1, v_code.created_by, left(p_ip, 155), null)
    returning id_users into v_id;
  update public.referral_code set used_by = p_username, updated_at = now() where id_reff = v_code.id_reff;
  return jsonb_build_object('ok', true, 'user_id', v_id, 'channel', 'kenny');
exception when unique_violation then
  return jsonb_build_object('ok', false, 'message', 'That username is taken or the owner slot was just filled.');
end $$;

-- Publishing is serialized and atomic PER CHANNEL: a failed publication
-- never demotes the previous release, and can never affect the other owner.
create or replace function public.publish_library_release(p_actor_id integer, p_release jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare v_channel text; v_id integer;
begin
  select owner_channel into v_channel from public.users where id_users = p_actor_id and level = 1 and status = 1;
  if v_channel is null or v_channel not in ('anoy', 'kenny') or
      (p_release ? 'channel' and (p_release->>'channel') is distinct from v_channel) then
    raise exception 'Not your release channel';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('anoymundo.lib.' || v_channel, 0));
  if coalesce(p_release->>'version', '') !~ '^[A-Za-z0-9][A-Za-z0-9._+-]{0,31}$' then raise exception 'Invalid version'; end if;
  if coalesce(p_release->>'storage_path', '') <> '' then
    if p_release->>'storage_path' !~ ('^' || v_channel || '/releases/[a-f0-9-]{36}/libbgmi.zip$')
        or coalesce(p_release->>'sha256', '') !~ '^[a-f0-9]{64}$'
        or coalesce((p_release->>'file_size')::bigint, 0) not between 1 and 11534336 then raise exception 'Invalid stored library'; end if;
  elsif coalesce(p_release->>'external_url', '') !~ '^https://[^[:space:]]+$' then
    raise exception 'A file or HTTPS link is required';
  end if;
  update public.client_release set is_current = false where channel = v_channel and is_current;
  insert into public.client_release(channel, version, file_name, file_size, sha256, storage_path, external_url, notes, game_version, is_current, uploaded_by)
    select v_channel, p_release->>'version', 'libbgmi.so', coalesce((p_release->>'file_size')::bigint, 0),
      coalesce(p_release->>'sha256', ''), nullif(p_release->>'storage_path', ''), nullif(p_release->>'external_url', ''),
      p_release->>'notes', nullif(p_release->>'game_version', ''), true, username from public.users where id_users = p_actor_id
    returning id into v_id;
  return jsonb_build_object('ok', true, 'id', v_id, 'channel', v_channel);
end $$;

create or replace function public.publish_app_release(p_actor_id integer, p_release jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare v_channel text; v_code integer; v_current integer;
begin
  select owner_channel into v_channel from public.users where id_users = p_actor_id and level = 1 and status = 1;
  if v_channel is null or v_channel not in ('anoy', 'kenny') or
      (p_release ? 'channel' and (p_release->>'channel') is distinct from v_channel) then
    raise exception 'Not your release channel';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('anoymundo.app.' || v_channel, 0));
  v_code := (p_release->>'version_code')::integer;
  select version_code into v_current from public.app_release where channel = v_channel and is_current;
  if v_code is null or v_code < 1 or v_code > 2100000000 or v_code <= coalesce(v_current, 0)
      or coalesce(p_release->>'apk_url', '') !~ '^https://[^[:space:]]+$'
      or coalesce(p_release->>'version_name', '') !~ '^[0-9A-Za-z][0-9A-Za-z._+-]{0,31}$'
      or length(coalesce(p_release->>'changelog', '')) not between 1 and 2000 then
    raise exception 'Invalid app release (version code must increase within your channel)';
  end if;
  update public.app_release set is_current = false where channel = v_channel and is_current;
  insert into public.app_release(channel, version_name, version_code, apk_url, changelog, mandatory, is_current, published_by)
    select v_channel, p_release->>'version_name', v_code, p_release->>'apk_url', p_release->>'changelog',
      coalesce((p_release->>'mandatory')::boolean, false), true, username from public.users where id_users = p_actor_id;
  return jsonb_build_object('ok', true, 'channel', v_channel);
end $$;

revoke all on function public.protect_owner_identity() from public, anon, authenticated;
revoke all on function public.create_owner_invite(integer, text, text) from public, anon, authenticated;
revoke all on function public.register_owner_from_referral(text, text, text, text, text, text) from public, anon, authenticated;
revoke all on function public.publish_library_release(integer, jsonb) from public, anon, authenticated;
revoke all on function public.publish_app_release(integer, jsonb) from public, anon, authenticated;
grant execute on function public.create_owner_invite(integer, text, text) to service_role;
grant execute on function public.register_owner_from_referral(text, text, text, text, text, text) to service_role;
grant execute on function public.publish_library_release(integer, jsonb) to service_role;
grant execute on function public.publish_app_release(integer, jsonb) to service_role;
