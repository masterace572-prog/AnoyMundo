-- ============================================================================
-- AnoyMundo Panel - PostgreSQL / Supabase schema
-- Converted 1:1 from the original MariaDB dump (Free Source/main.sql).
-- Safe to run on a brand-new Supabase project. Idempotent: uses IF NOT EXISTS.
--
-- Run with: Supabase Dashboard -> SQL Editor -> New query -> paste -> Run
-- ============================================================================

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
create table if not exists public.users (
  id_users          serial primary key,
  fullname          varchar(155),
  username          varchar(66)  not null,
  email             varchar(40)  not null default '',
  reset_link_token  varchar(255) not null default '',
  exp_date          varchar(250) not null default '',
  level             integer      not null default 3,
  saldo             bigint       not null default 0,
  status            smallint     not null default 1,
  uplink            varchar(66),
  password          varchar(155) not null,
  user_ip           varchar(155) not null default '',
  created_at        timestamptz  not null default now(),
  updated_at        timestamptz  not null default now(),
  expiration_date   timestamptz,
  constraint users_username_key unique (username)
);
create index if not exists users_uplink_idx on public.users (uplink);

-- ---------------------------------------------------------------------------
-- keys_code
-- ---------------------------------------------------------------------------
create table if not exists public.keys_code (
  id_keys      serial primary key,
  game         varchar(32) not null,
  user_key     varchar(32),
  duration     integer,
  expired_date timestamptz,
  max_devices  integer,
  devices      text,
  status       smallint    not null default 1,
  registrator  varchar(32),
  admin_id     integer,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  constraint keys_code_user_key_key unique (user_key)
);
create index if not exists keys_code_registrator_idx on public.keys_code (registrator);
create index if not exists keys_code_expired_date_idx on public.keys_code (expired_date);

-- ---------------------------------------------------------------------------
-- history
-- ---------------------------------------------------------------------------
create table if not exists public.history (
  id_history serial primary key,
  keys_id    varchar(33),
  user_do    varchar(33),
  info       text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- referral_code  (column is lowercase "referral"; the MySQL dump used "Referral")
-- "code" stores md5('XquxmymXDtWRA66D' + plaintext_code), exactly like the
-- original CodeIgniter create_password($code, false) helper.
-- ---------------------------------------------------------------------------
create table if not exists public.referral_code (
  id_reff        serial primary key,
  code           varchar(128) not null,
  referral       varchar(7)   not null,
  level          integer      not null default 3,
  set_saldo      integer      not null default 0,
  used_by        varchar(66)  not null default '',
  created_by     varchar(66)  not null default 'Owner',
  created_at     timestamptz  not null default now(),
  updated_at     timestamptz  not null default now(),
  acc_expiration timestamptz
);

-- ---------------------------------------------------------------------------
-- feature (mod feature toggles)
-- ---------------------------------------------------------------------------
create table if not exists public.feature (
  id          serial primary key,
  esp         varchar(3) not null default 'on',
  item        varchar(3) not null default 'on',
  silentaim   varchar(3) not null default 'on',
  aim         varchar(3) not null default 'on',
  bullettrack varchar(3) not null default 'on',
  memory      varchar(3) not null default 'on',
  floating    varchar(3) not null default 'on',
  setting     varchar(3) not null default 'on'
);

create table if not exists public.modname (
  id      serial primary key,
  modname varchar(100) not null
);

create table if not exists public.onoff (
  id      serial primary key,
  status  varchar(5)   not null default 'off',
  myinput varchar(500) not null default ''
);

create table if not exists public.credit (
  id   serial primary key,
  name varchar(10) not null default ''
);

create table if not exists public.lib (
  id        serial primary key,
  file      varchar(255) not null,
  file_type varchar(255) not null,
  file_size varchar(32)  not null,
  time      timestamptz  not null default now()
);

create table if not exists public._ftext (
  id      serial primary key,
  _status varchar(100) not null,
  _ftext  varchar(100) not null
);

-- ---------------------------------------------------------------------------
-- client_release: stores the loader ZIP that the Android client downloads.
-- Replaces the old on-disk "TumharaPapaDARK/lib.so" upload with DB storage,
-- because Vercel functions have a read-only, ephemeral filesystem.
-- ---------------------------------------------------------------------------
create table if not exists public.client_release (
  id           serial primary key,
  version      varchar(32)  not null,
  file_name    varchar(255) not null,
  file_size    bigint       not null default 0,
  sha256       varchar(64)  not null default '',
  content_base64 text,
  external_url text,
  notes        text,
  is_current   boolean      not null default false,
  uploaded_by  varchar(66)  not null default 'admin',
  created_at   timestamptz  not null default now()
);
create unique index if not exists client_release_one_current
  on public.client_release (is_current) where is_current = true;

-- Simple login audit / throttle table.
create table if not exists public.auth_attempt (
  id         bigserial primary key,
  username   varchar(66),
  ip         varchar(100),
  ok         boolean not null default false,
  created_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- api_keys (new in v1.1): personal API keys created by logged-in panel users.
-- Only a SHA-256 hash of the token is stored; the plaintext is shown once.
-- These keys can ONLY manage licence keys, never server/mod settings.
-- ---------------------------------------------------------------------------
create table if not exists public.api_keys (
  id            serial primary key,
  user_id       integer      not null references public.users(id_users) on delete cascade,
  name          varchar(80)  not null,
  key_prefix    varchar(20)  not null,
  key_hash      varchar(64)  not null,
  key_last4     varchar(4)   not null,
  status        smallint     not null default 1,
  last_used_at  timestamptz,
  created_at    timestamptz  not null default now(),
  updated_at    timestamptz  not null default now(),
  constraint api_keys_key_hash_key unique (key_hash)
);
create index if not exists api_keys_user_id_idx on public.api_keys (user_id);
create index if not exists api_keys_key_hash_idx on public.api_keys (key_hash);

-- ---------------------------------------------------------------------------
-- Row Level Security: the browser never talks to the database directly.
-- All traffic goes through the Vercel functions using the service_role key,
-- which bypasses RLS. Keeping RLS ON with no public policies means a leaked
-- anon/publishable key cannot read users, passwords or license keys.
-- ---------------------------------------------------------------------------
alter table public.users          enable row level security;
alter table public.keys_code      enable row level security;
alter table public.history        enable row level security;
alter table public.referral_code  enable row level security;
alter table public.feature        enable row level security;
alter table public.modname        enable row level security;
alter table public.onoff          enable row level security;
alter table public.credit         enable row level security;
alter table public.lib            enable row level security;
alter table public._ftext         enable row level security;
alter table public.client_release enable row level security;
alter table public.auth_attempt   enable row level security;
alter table public.api_keys       enable row level security;

-- ---------------------------------------------------------------------------
-- Seed data (same values as the original main.sql dump)
-- ---------------------------------------------------------------------------
insert into public.users
  (id_users, fullname, username, email, reset_link_token, exp_date, level, saldo,
   status, uplink, password, user_ip, created_at, updated_at, expiration_date)
values
  (1, 'admin', 'admin', 'support@aloneboy.com',
   'a886a84c38036225b4bcabfd81e7d1f0582', '2023-07-23 01:22:54', 1, 2147384644,
   1, 'Owner', '$2y$08$/CsSVgrGgCqVcievCuR2COPnlMIpRz6kA.hzItBD/xd1Cx0hj0kMK',
   '42.109.149.*', '2022-06-22 22:15:21', '2025-11-19 17:51:31', '2050-01-01 00:00:00+00')
on conflict (id_users) do nothing;
select setval(pg_get_serial_sequence('public.users','id_users'),
              greatest(1, (select max(id_users) from public.users)));

insert into public.keys_code
  (id_keys, game, user_key, duration, expired_date, max_devices, devices, status, registrator, created_at, updated_at)
values
  (1, 'PUBG', 'admin-2-lyK0Y', 2, null, 1, null, 1, 'admin', '2025-11-19 13:17:39', '2025-11-19 13:17:39'),
  (2, 'PUBG', 'admin-2-f7VwE', 2, null, 1, null, 1, 'admin', '2025-11-19 17:51:31', '2025-11-19 17:51:31')
on conflict (id_keys) do nothing;
select setval(pg_get_serial_sequence('public.keys_code','id_keys'),
              greatest(1, (select max(id_keys) from public.keys_code)));

insert into public.history (id_history, keys_id, user_do, info, created_at, updated_at)
values
  (1, '1', 'admin', 'PUBG|admin|2|1', '2025-11-19 13:17:39', '2025-11-19 13:17:39'),
  (2, '2', 'admin', 'PUBG|admin|2|1', '2025-11-19 17:51:31', '2025-11-19 17:51:31')
on conflict (id_history) do nothing;
select setval(pg_get_serial_sequence('public.history','id_history'),
              greatest(1, (select max(id_history) from public.history)));

insert into public.feature (id, esp, item, silentaim, aim, bullettrack, memory, floating, setting)
values (1, 'on','on','on','on','on','on','on','on') on conflict (id) do nothing;
insert into public.modname (id, modname) values (1, 'VIP MOD') on conflict (id) do nothing;
insert into public.onoff   (id, status, myinput) values (1, 'off', '') on conflict (id) do nothing;
insert into public.credit  (id, name) values (1, '') on conflict (id) do nothing;
insert into public.lib     (id, file, file_type, file_size, time)
values (1, 'lib.so', 'Onlinelib/lib.so', '555 KB', '2022-06-04 23:43:38') on conflict (id) do nothing;
insert into public._ftext  (id, _status, _ftext)
values (1, 'Safe', 'MOD STATUS :- 100% SAFE') on conflict (id) do nothing;

-- Starter referral code so registration is usable immediately.
-- Plain code: WELCOME   (hashed with the legacy md5(salt + code) algorithm)
insert into public.referral_code
  (code, referral, level, set_saldo, used_by, created_by, acc_expiration)
select 'f36d16b166f38a1a148a2069a74c866c', 'WELCOME', 3, 1000, '', 'Owner', now() + interval '365 days'
where not exists (select 1 from public.referral_code where referral = 'WELCOME');

-- Default loader release marker. Upload a real ZIP from Admin -> Library.
insert into public.client_release (version, file_name, file_size, sha256, is_current, uploaded_by, notes)
select '1.0', 'no-release.zip', 0, '', true, 'admin', 'Placeholder - upload a release from Admin -> Library'
where not exists (select 1 from public.client_release);

-- ============================================================================
-- v1.2 hardening (idempotent). Run after the base schema.
--
--  1. price_paid on every key  -> exact, one-time refunds for unused keys
--  2. users_single_owner_idx   -> at most one level-1 (Owner) row can exist
--  3. key_orders               -> idempotency ledger for generation requests
--  4. create_keys_atomic()     -> balance debit + ALL key inserts + history in
--                                ONE transaction. A timeout or error can never
--                                leave keys behind without charging, or charge
--                                without creating the keys.
--  5. delete_keys_refund()     -> atomic delete + full refund of never-used keys
-- ============================================================================

alter table public.keys_code
  add column if not exists price_paid bigint not null default 0;

-- A level-1 row is structurally unique: no second Owner can be inserted even
-- if application-level checks are bypassed.
create unique index if not exists users_single_owner_idx
  on public.users ((level)) where level = 1;

create table if not exists public.key_orders (
  request_id  text primary key,
  user_id     integer not null,
  created_at  timestamptz not null default now(),
  result      jsonb
);
create index if not exists key_orders_user_created_idx
  on public.key_orders (user_id, created_at);
alter table public.key_orders enable row level security;

-- Failure helper: removes the in-flight order so an honest retry is allowed,
-- while a successful order stays cached and replays its original result.
create or replace function public._keys_fail(
  p_request_id text, p_code text, p_message text, p_extra jsonb default '{}'::jsonb
) returns jsonb
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  delete from public.key_orders where request_id = p_request_id;
  return jsonb_build_object('ok', false, 'code', p_code, 'message', p_message)
         || coalesce(p_extra, '{}'::jsonb);
end $$;

create or replace function public.create_keys_atomic(
  p_request_id  text,
  p_user_id     integer,
  p_game        text,
  p_duration    integer,
  p_max_devices integer,
  p_unit_price  bigint,
  p_total_price bigint,
  p_keys        text[]
) returns jsonb
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_user     public.users%rowtype;
  v_existing jsonb;
  v_count    integer;
  v_recent   integer;
  v_rows     jsonb;
  v_result   jsonb;
begin
  -- Idempotency: a repeated POST (double click, retry, network replay) never
  -- creates a second batch and never charges twice.
  insert into public.key_orders (request_id, user_id)
  values (p_request_id, p_user_id)
  on conflict (request_id) do nothing;
  if not found then
    select result into v_existing from public.key_orders where request_id = p_request_id;
    if v_existing is not null then return v_existing; end if;
    return public._keys_fail(p_request_id, 'duplicate_request',
      'This request is already being processed. Please try again.');
  end if;

  -- Row lock serialises concurrent purchases by the same account.
  select * into v_user from public.users where id_users = p_user_id for update;
  if not found then
    return public._keys_fail(p_request_id, 'account_inactive', 'Account not found.');
  end if;
  if v_user.status <> 1 then
    return public._keys_fail(p_request_id, 'account_inactive', 'Account is not active.');
  end if;
  if v_user.expiration_date is not null and v_user.expiration_date < now() then
    return public._keys_fail(p_request_id, 'account_expired', 'Account has expired.');
  end if;

  v_count := coalesce(array_length(p_keys, 1), 0);
  if v_count < 1 or v_count > 100 then
    return public._keys_fail(p_request_id, 'invalid_request', 'Invalid key count.');
  end if;
  if p_unit_price <= 0 or p_total_price <= 0 or p_total_price <> p_unit_price * v_count then
    return public._keys_fail(p_request_id, 'invalid_request', 'Invalid price.');
  end if;
  if p_duration <= 0 or p_max_devices < 1 or p_max_devices > 100 then
    return public._keys_fail(p_request_id, 'invalid_request', 'Invalid key options.');
  end if;

  if v_user.saldo < p_total_price then
    return public._keys_fail(p_request_id, 'insufficient_balance', 'Insufficient balance.',
      jsonb_build_object('required', p_total_price, 'available', v_user.saldo,
                         'saldo', v_user.saldo, 'price', p_total_price));
  end if;

  -- Light abuse throttle: at most 8 purchased batches per 10 seconds.
  select count(*) into v_recent from public.key_orders
   where user_id = p_user_id and created_at > now() - interval '10 seconds';
  if v_recent > 8 then
    return public._keys_fail(p_request_id, 'rate_limited',
      'Too many requests. Wait a moment and try again.');
  end if;

  if exists (select 1 from public.keys_code where user_key = any(p_keys)) then
    return public._keys_fail(p_request_id, 'key_exists', 'Key already exists.');
  end if;

  -- ONE statement for the whole batch. Everything below is in the same
  -- transaction, so a failure anywhere rolls back the debit as well.
  insert into public.keys_code
    (game, user_key, duration, max_devices, registrator, admin_id, status,
     expired_date, price_paid, created_at, updated_at)
  select p_game, k, p_duration, p_max_devices, v_user.username, p_user_id, 1,
         null, p_unit_price, now(), now()
  from unnest(p_keys) as k;

  update public.users
     set saldo = saldo - p_total_price, updated_at = now()
   where id_users = p_user_id;

  insert into public.history (keys_id, user_do, info, created_at, updated_at)
  values ('', v_user.username,
          p_game || '|' || left(p_keys[1], 5) || '|' || p_duration || '|' ||
          p_max_devices || '|' || v_count || '|' || p_total_price,
          now(), now());

  select jsonb_agg(to_jsonb(k) order by k.id_keys) into v_rows
  from (select * from public.keys_code where user_key = any(p_keys)) k;

  v_result := jsonb_build_object(
    'ok', true,
    'keys', to_jsonb(p_keys),
    'rows', coalesce(v_rows, '[]'::jsonb),
    'count', v_count,
    'price', p_total_price,
    'unit_price', p_unit_price,
    'saldo_before', v_user.saldo,
    'saldo_after', v_user.saldo - p_total_price
  );
  update public.key_orders set result = v_result where request_id = p_request_id;
  delete from public.key_orders where created_at < now() - interval '2 days';
  return v_result;
end $$;

create or replace function public.delete_keys_refund(
  p_ids integer[], p_actor_id integer
) returns jsonb
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_actor     public.users%rowtype;
  v_allow_all boolean;
  v_deleted   integer;
  v_refunded  integer;
  v_total     bigint;
begin
  select * into v_actor from public.users where id_users = p_actor_id;
  if not found then
    return jsonb_build_object('ok', false, 'code', 'unauthorized', 'message', 'Please sign in again.');
  end if;
  -- Server-side authority: only the real Owner may delete other users' keys.
  v_allow_all := (v_actor.level = 1 and v_actor.username = 'admin');

  with locked as (
    select k.*,
      case when (k.devices is null or btrim(k.devices) = '')
                and k.expired_date is null
                and coalesce(k.price_paid, 0) > 0
           then k.price_paid else 0 end as refund
      from public.keys_code k
     where k.id_keys = any(p_ids)
       and (v_allow_all or k.registrator = v_actor.username)
     for update
  ),
  counted as (
    select count(*)::integer as deleted,
           count(*) filter (where refund > 0)::integer as refunded,
           coalesce(sum(refund), 0)::bigint as total
      from locked
  ),
  agg as (
    select registrator, sum(refund)::bigint as amount
      from locked where refund > 0 group by registrator
  ),
  credited as (
    update public.users u
       set saldo = u.saldo + a.amount, updated_at = now()
      from agg a
     where u.username = a.registrator
    returning u.id_users
  ),
  removed as (
    delete from public.keys_code k using locked l
     where k.id_keys = l.id_keys
    returning k.id_keys
  )
  select c.deleted, c.refunded, c.total into v_deleted, v_refunded, v_total
  from counted c;

  return jsonb_build_object(
    'ok', true,
    'deleted', coalesce(v_deleted, 0),
    'refunded', coalesce(v_refunded, 0),
    'refund_total', coalesce(v_total, 0)
  );
end $$;

revoke all on function public._keys_fail(text, text, text, jsonb) from public, anon, authenticated;
revoke all on function public.create_keys_atomic(text, integer, text, integer, integer, bigint, bigint, text[]) from public, anon, authenticated;
revoke all on function public.delete_keys_refund(integer[], integer) from public, anon, authenticated;
grant execute on function public._keys_fail(text, text, text, jsonb) to service_role;
grant execute on function public.create_keys_atomic(text, integer, text, integer, integer, bigint, bigint, text[]) to service_role;
grant execute on function public.delete_keys_refund(integer[], integer) to service_role;
