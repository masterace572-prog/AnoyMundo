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
