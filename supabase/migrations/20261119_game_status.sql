-- Per-game availability, set from the Server page. NON-DESTRUCTIVE: no
-- existing table, user or key is touched. The loader reads these rows through
-- the library manifest; 'available' is the default when a row is missing.
create table if not exists public.game_status (
  game text primary key,
  status text not null default 'available',
  message text not null default '',
  updated_at timestamptz not null default now()
);

alter table public.game_status drop constraint if exists game_status_status_check;
alter table public.game_status add constraint game_status_status_check
  check (status in ('available', 'unavailable', 'maintenance'));

insert into public.game_status (game) values ('bgmi'), ('bgmi_air')
on conflict (game) do nothing;
