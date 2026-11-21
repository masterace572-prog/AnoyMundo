-- Site/backend only. Apply AFTER the existing two_owner_channels and
-- referral_single_use migrations. No users, keys, APK selections or current
-- library bytes are changed by installing this migration.
begin;

create table if not exists public.library_storage_cleanup (
  storage_path text primary key,
  channel text not null check (channel in ('anoy', 'kenny')),
  queued_at timestamptz not null default now(),
  delete_after timestamptz not null default (now() + interval '3 minutes'),
  deleted_at timestamptz,
  constraint library_cleanup_managed_path check (
    storage_path ~ ('^' || channel || '/(releases/[a-f0-9-]{36}/libbgmi[.]zip|pending/[a-f0-9-]{36})$')
  )
);
alter table public.library_storage_cleanup enable row level security;
revoke all on public.library_storage_cleanup from public, anon, authenticated;
grant select, insert, update on public.library_storage_cleanup to service_role;
create index if not exists library_storage_cleanup_due on public.library_storage_cleanup(delete_after) where deleted_at is null;

-- The scheduled worker receives a dedicated random task token, generated and
-- encrypted in Vault by the separate scheduler installation. Only its SHA256
-- digest is kept here; it is not an account, service key or login credential.
create table if not exists public.library_storage_worker_auth (
  singleton boolean primary key default true check (singleton),
  token_sha256 text not null check (token_sha256 ~ '^[a-f0-9]{64}$')
);
alter table public.library_storage_worker_auth enable row level security;
revoke all on public.library_storage_worker_auth from public, anon, authenticated, service_role;
create or replace function public.authorize_library_storage_worker(p_token_sha256 text)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
  select exists(select 1 from public.library_storage_worker_auth where singleton and token_sha256 = p_token_sha256)
$$;

-- Preserve numeric role/billing compatibility. The immutable original 'anoy'
-- identity is Real Owner; the immutable 'kenny' identity is a delegated Owner.
create or replace function public.authorized_release_channel(p_actor_id integer, p_release jsonb)
returns text language plpgsql security definer set search_path = public, pg_temp as $$
declare v_own text; v_channel text;
begin
  select owner_channel into v_own from public.users
    where id_users = p_actor_id and level = 1 and status = 1;
  if v_own is null or v_own not in ('anoy', 'kenny') or jsonb_typeof(p_release) is distinct from 'object' then
    raise exception 'Owner access required';
  end if;
  v_channel := case when p_release ? 'channel' then p_release->>'channel' else v_own end;
  if v_channel is null or v_channel not in ('anoy', 'kenny')
      or (v_own <> 'anoy' and v_channel <> v_own)
      or (p_release ? 'owner_channel' and (p_release->>'owner_channel') is distinct from v_channel) then
    raise exception 'Not your release channel';
  end if;
  return v_channel;
end $$;

-- Protect current files even if a legacy external URL refers to a managed
-- object. Match both ordinary and percent-encoded paths conservatively.
create or replace function public.library_url_references_path(p_url text, p_path text)
returns boolean language plpgsql immutable set search_path = public, pg_temp as $$
declare v_url text := lower(coalesce(p_url, '')); v_encoded text; v_byte integer;
begin
  -- Managed paths contain only ASCII. Decode valid percent escapes without
  -- accepting a malformed escape as a reason to ignore an otherwise live link.
  for v_encoded in select distinct m[1] from regexp_matches(v_url, '(%[0-9a-f]{2})', 'g') m loop
    v_byte := get_byte(decode(substr(v_encoded, 2), 'hex'), 0);
    if v_byte between 32 and 126 then v_url := replace(v_url, v_encoded, lower(chr(v_byte))); end if;
  end loop;
  return coalesce(p_path <> '' and strpos(v_url, lower(p_path)) > 0, false);
end $$;
create or replace function public.library_storage_path_is_current(p_path text)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
  select exists (select 1 from public.client_release where is_current
    and (storage_path = p_path or public.library_url_references_path(external_url, p_path)))
$$;

-- A retired/queued object can never be promoted again while an asynchronous
-- Storage deletion is in progress. Cleanup and publication share this short DB
-- lock, but no DB lock is held during network calls to Storage.
create or replace function public.protect_library_storage_history() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if tg_op = 'UPDATE' and new.storage_path is not distinct from old.storage_path
      and new.is_current is not distinct from old.is_current
      and new.external_url is not distinct from old.external_url then return new; end if;
  perform pg_advisory_xact_lock(hashtextextended('anoymundo.library_storage', 0));
  if tg_op = 'UPDATE' then
    if new.storage_path is distinct from old.storage_path then raise exception 'Library storage paths are immutable'; end if;
    if not old.is_current and new.is_current then raise exception 'Retired libraries cannot be restored'; end if;
  end if;
  if new.is_current and exists (select 1 from public.library_storage_cleanup q
      where q.storage_path = new.storage_path or public.library_url_references_path(new.external_url, q.storage_path)) then
    raise exception 'This library file has been retired; upload a new file';
  end if;
  return new;
end $$;
drop trigger if exists protect_library_storage_history on public.client_release;
create trigger protect_library_storage_history before insert or update on public.client_release
for each row execute function public.protect_library_storage_history();
create unique index if not exists client_release_storage_path_once
  on public.client_release(storage_path) where storage_path is not null;

-- Dry-run by default. Only our private bucket and known library path shapes
-- qualify. Unreferenced/pending objects must be >24h old (signed upload tickets
-- expire well before that), so concurrent or abandoned-but-retryable uploads
-- are not mistaken for obsolete releases. Completed cleanup rows are retained
-- as tiny tombstones/audit records, NOT copies of the library bytes.
create or replace function public.plan_library_storage_cleanup(p_channel text default null, p_apply boolean default false)
returns jsonb language plpgsql security definer set search_path = public, storage, pg_temp as $$
declare v_paths text[]; v_inline_count integer; v_inline_bytes bigint; v_pending integer;
begin
  if p_channel is not null and p_channel not in ('anoy', 'kenny') then raise exception 'Unknown release channel'; end if;
  perform pg_advisory_xact_lock(hashtextextended('anoymundo.library_storage', 0));
  select coalesce(array_agg(distinct path), array[]::text[]) into v_paths from (
    select r.storage_path as path from public.client_release r
    where not r.is_current and (p_channel is null or r.channel = p_channel)
      and exists (select 1 from public.client_release live where live.channel = r.channel and live.is_current)
      and r.storage_path ~ ('^' || r.channel || '/releases/[a-f0-9-]{36}/libbgmi[.]zip$')
    union
    select o.name as path from storage.objects o
    where o.bucket_id = 'loader-releases' and o.created_at < now() - interval '24 hours'
      and (p_channel is null or split_part(o.name, '/', 1) = p_channel)
      and o.name ~ '^(anoy|kenny)/(pending/[a-f0-9-]{36}|releases/[a-f0-9-]{36}/libbgmi[.]zip)$'
      and not exists (select 1 from public.client_release r where r.storage_path = o.name)
  ) candidates where not public.library_storage_path_is_current(path)
    and not exists (select 1 from public.library_storage_cleanup done where done.storage_path = path and done.deleted_at is not null);
  select count(*)::integer, coalesce(sum(octet_length(r.content_base64)), 0) into v_inline_count, v_inline_bytes
    from public.client_release r where not r.is_current and r.content_base64 is not null
      and (p_channel is null or r.channel = p_channel)
      and exists (select 1 from public.client_release live where live.channel = r.channel and live.is_current);
  if p_apply then
    insert into public.library_storage_cleanup(storage_path, channel)
      select path, split_part(path, '/', 1) from unnest(v_paths) path on conflict (storage_path) do nothing;
    update public.client_release r set content_base64 = null
      where not r.is_current and r.content_base64 is not null and (p_channel is null or r.channel = p_channel)
        and exists (select 1 from public.client_release live where live.channel = r.channel and live.is_current);
  end if;
  select count(*)::integer into v_pending from public.library_storage_cleanup
    where deleted_at is null and (p_channel is null or channel = p_channel);
  return jsonb_build_object('ok', true, 'candidate_objects', cardinality(v_paths),
    'inline_copies', v_inline_count, 'inline_bytes', v_inline_bytes, 'pending', v_pending, 'applied', p_apply);
end $$;

-- Wait longer than our 60-second download signatures before removing a file.
-- Final current-reference recheck immediately before calling Storage.remove.
-- Queued paths are tombstoned by the trigger, so they cannot become current
-- after this recheck (even via a cross-channel external link).
create or replace function public.library_storage_cleanup_batch(p_channel text default null, p_limit integer default 50)
returns table(storage_path text, channel text) language sql stable security definer set search_path = public, pg_temp as $$
  select q.storage_path, q.channel from public.library_storage_cleanup q
    where q.deleted_at is null and q.delete_after <= now() and (p_channel is null or q.channel = p_channel)
      and (p_channel is null or p_channel in ('anoy', 'kenny'))
      and not public.library_storage_path_is_current(q.storage_path)
    order by q.queued_at, q.storage_path limit greatest(1, least(coalesce(p_limit, 50), 100))
$$;

create or replace function public.publish_library_release(p_actor_id integer, p_release jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare v_channel text; v_id integer;
begin
  v_channel := public.authorized_release_channel(p_actor_id, p_release);
  perform pg_advisory_xact_lock(hashtextextended('anoymundo.library_storage', 0));
  perform pg_advisory_xact_lock(hashtextextended('anoymundo.lib.' || v_channel, 0));
  if coalesce(p_release->>'version', '') !~ '^[A-Za-z0-9][A-Za-z0-9._+-]{0,31}$' then raise exception 'Invalid version'; end if;
  if coalesce(p_release->>'storage_path', '') <> '' then
    if p_release->>'storage_path' !~ ('^' || v_channel || '/releases/[a-f0-9-]{36}/libbgmi[.]zip$')
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
  -- If insert/validation failed, this transaction (including demotion/queue)
  -- rolls back. The worker is called only after a confirmed successful commit.
  perform public.plan_library_storage_cleanup(v_channel, true);
  return jsonb_build_object('ok', true, 'id', v_id, 'channel', v_channel);
end $$;

create or replace function public.publish_app_release(p_actor_id integer, p_release jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare v_channel text; v_code integer; v_current integer;
begin
  v_channel := public.authorized_release_channel(p_actor_id, p_release);
  perform pg_advisory_xact_lock(hashtextextended('anoymundo.app.' || v_channel, 0));
  v_code := (p_release->>'version_code')::integer;
  select version_code into v_current from public.app_release where channel = v_channel and is_current;
  if v_code is null or v_code < 1 or v_code > 2100000000 or v_code <= coalesce(v_current, 0)
      or coalesce(p_release->>'apk_url', '') !~ '^https://[^[:space:]]+$'
      or coalesce(p_release->>'version_name', '') !~ '^[0-9A-Za-z][0-9A-Za-z._+-]{0,31}$'
      or length(coalesce(p_release->>'changelog', '')) not between 1 and 2000 then
    raise exception 'Invalid app release (version code must increase within its channel)';
  end if;
  update public.app_release set is_current = false where channel = v_channel and is_current;
  insert into public.app_release(channel, version_name, version_code, apk_url, changelog, mandatory, is_current, published_by)
    select v_channel, p_release->>'version_name', v_code, p_release->>'apk_url', p_release->>'changelog',
      coalesce((p_release->>'mandatory')::boolean, false), true, username from public.users where id_users = p_actor_id;
  return jsonb_build_object('ok', true, 'channel', v_channel);
end $$;

revoke all on function public.authorize_library_storage_worker(text) from public, anon, authenticated;
revoke all on function public.authorized_release_channel(integer, jsonb) from public, anon, authenticated;
revoke all on function public.library_url_references_path(text, text) from public, anon, authenticated;
revoke all on function public.library_storage_path_is_current(text) from public, anon, authenticated;
revoke all on function public.protect_library_storage_history() from public, anon, authenticated;
revoke all on function public.plan_library_storage_cleanup(text, boolean) from public, anon, authenticated;
revoke all on function public.library_storage_cleanup_batch(text, integer) from public, anon, authenticated;
revoke all on function public.publish_library_release(integer, jsonb) from public, anon, authenticated;
revoke all on function public.publish_app_release(integer, jsonb) from public, anon, authenticated;
grant execute on function public.authorize_library_storage_worker(text) to service_role;
grant execute on function public.authorized_release_channel(integer, jsonb) to service_role;
grant execute on function public.library_url_references_path(text, text) to service_role;
grant execute on function public.library_storage_path_is_current(text) to service_role;
grant execute on function public.plan_library_storage_cleanup(text, boolean) to service_role;
grant execute on function public.library_storage_cleanup_batch(text, integer) to service_role;
grant execute on function public.publish_library_release(integer, jsonb) to service_role;
grant execute on function public.publish_app_release(integer, jsonb) to service_role;

commit;
