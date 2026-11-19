-- v2.3.5: the BGMI version the current library works with.
-- Read by GET /api/lib/manifest (field game_version); the app warns players
-- when their installed BGMI is newer. Empty/null = no warning. Safe to re-run.
alter table public.client_release add column if not exists game_version varchar(32);
