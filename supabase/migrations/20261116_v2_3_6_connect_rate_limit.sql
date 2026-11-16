-- v2.3.6: shared, fixed-window throttling for /connect on Vercel.
-- Stores only HMAC fingerprints, never raw licence keys or client IPs.
-- Additive: creates one private table and one RPC; existing tables are untouched.

create table if not exists public.connect_rate_limit (
  dimension         text        not null check (dimension in ('key', 'ip')),
  subject_hash      text        not null check (subject_hash ~ '^[0-9a-f]{64}$'),
  window_started_at timestamptz not null,
  hit_count         integer     not null check (hit_count > 0),
  updated_at        timestamptz not null default now(),
  primary key (dimension, subject_hash)
);

create index if not exists connect_rate_limit_window_idx
  on public.connect_rate_limit (window_started_at);

alter table public.connect_rate_limit enable row level security;
revoke all on table public.connect_rate_limit from PUBLIC, anon, authenticated;

create or replace function public.check_connect_rate_limit(
  p_key_hash       text,
  p_ip_hash        text,
  p_key_limit      integer,
  p_ip_limit       integer,
  p_window_seconds integer
) returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_now          timestamptz := clock_timestamp();
  v_window_start timestamptz;
  v_key_hits     integer;
  v_ip_hits      integer;
begin
  if p_key_hash is null or p_key_hash !~ '^[0-9a-f]{64}$'
     or p_ip_hash is null or p_ip_hash !~ '^[0-9a-f]{64}$'
     or p_key_limit not between 1 and 100000
     or p_ip_limit not between 1 and 100000
     or p_window_seconds not between 1 and 86400 then
    raise exception 'Invalid /connect rate-limit parameters';
  end if;

  v_window_start := to_timestamp(
    floor(extract(epoch from v_now) / p_window_seconds) * p_window_seconds
  );

  -- The key and IP rows are incremented in a single transaction. The upsert
  -- takes a row lock, so concurrent Vercel instances cannot bypass a limit.
  insert into public.connect_rate_limit as current_limit
    (dimension, subject_hash, window_started_at, hit_count, updated_at)
  values ('key', p_key_hash, v_window_start, 1, v_now)
  on conflict (dimension, subject_hash) do update
    set window_started_at = excluded.window_started_at,
        hit_count = case
          when current_limit.window_started_at = excluded.window_started_at
            then current_limit.hit_count + 1
          else 1
        end,
        updated_at = excluded.updated_at
  returning hit_count into v_key_hits;

  insert into public.connect_rate_limit as current_limit
    (dimension, subject_hash, window_started_at, hit_count, updated_at)
  values ('ip', p_ip_hash, v_window_start, 1, v_now)
  on conflict (dimension, subject_hash) do update
    set window_started_at = excluded.window_started_at,
        hit_count = case
          when current_limit.window_started_at = excluded.window_started_at
            then current_limit.hit_count + 1
          else 1
        end,
        updated_at = excluded.updated_at
  returning hit_count into v_ip_hits;

  -- Keep only a small tail of expired subjects; cleanup runs opportunistically
  -- with requests, so old fingerprints are removed as traffic continues.
  delete from public.connect_rate_limit
   where window_started_at < v_window_start - make_interval(secs => p_window_seconds * 2);

  return v_key_hits <= p_key_limit and v_ip_hits <= p_ip_limit;
end;
$$;

revoke all on function public.check_connect_rate_limit(text, text, integer, integer, integer)
  from PUBLIC, anon, authenticated;
grant execute on function public.check_connect_rate_limit(text, text, integer, integer, integer)
  to service_role;
