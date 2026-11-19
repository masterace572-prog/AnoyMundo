-- v2.2.0 (2026-11-07): in-app update for the Android app (APK).
-- Adds app_release, read by GET /api/app/update and written from the panel's
-- "App update" page. Idempotent; touches no existing table.
-- ---------------------------------------------------------------------------
-- app_release (v2.2.0): the Android app's own update (APK), published from
-- the panel's "App update" page and read by GET /api/app/update. Only an
-- https link is stored (e.g. a GitHub release asset) - never the APK bytes or
-- any signing material. Separate from client_release (the loader library).
-- ---------------------------------------------------------------------------
create table if not exists public.app_release (
  id            serial primary key,
  version_name  varchar(32)  not null,
  version_code  integer      not null check (version_code > 0),
  apk_url       text         not null check (apk_url like 'https://%'),
  changelog     text         not null default '',
  mandatory     boolean      not null default false,
  is_current    boolean      not null default false,
  published_by  varchar(66)  not null default 'admin',
  created_at    timestamptz  not null default now()
);
create unique index if not exists app_release_one_current
  on public.app_release (is_current) where is_current = true;

-- RLS on with no public policies: only the service-role panel can read/write.
alter table public.app_release enable row level security;
