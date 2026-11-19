-- v2.0 (2026-11-07): fresh start + Owner account + new key format.
-- Wipes every user, key, API key, referral code and history row, then seeds
-- the single Owner (Anoy). Server settings and loader releases are kept.

-- 1. Schema
alter table public.keys_code alter column user_key type varchar(64);

-- 2. Fresh data
truncate table public.keys_code, public.api_keys, public.history, public.referral_code,
               public.key_orders, public.auth_attempt restart identity;
delete from public.users;
alter sequence public.users_id_users_seq restart with 1;
create unique index if not exists users_username_lower_idx on public.users (lower(username));

insert into public.users
  (fullname, username, email, reset_link_token, exp_date, level, saldo,
   status, uplink, password, user_ip, created_at, updated_at, expiration_date)
values ('Anoy', 'Anoy', 'saabarjun705@gmail.com', '', '', 1, 999999999999,
   1, 'Owner', '$2a$08$sQefH.l6cnpeUwuTifoZvuF1SSh7U6Mw4UwddAO9cawj5pip8blOa',
   '', now(), now(), '2050-01-01 00:00:00+00');

-- 3. Owner never pays; owner check no longer hard-codes a username
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
  v_unlimited boolean;
  v_charge   bigint;
  v_unit     bigint;
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

  -- The Owner (level 1, exactly one exists) has an unlimited balance and is
  -- never charged; everyone else pays price x devices x keys.
  v_unlimited := (v_user.level = 1);
  v_charge := case when v_unlimited then 0 else p_total_price end;
  v_unit := case when v_unlimited then 0 else p_unit_price end;

  if not v_unlimited and v_user.saldo < p_total_price then
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
         null, v_unit, now(), now()
  from unnest(p_keys) as k;

  if v_charge > 0 then
    update public.users
       set saldo = saldo - v_charge, updated_at = now()
     where id_users = p_user_id;
  end if;

  insert into public.history (keys_id, user_do, info, created_at, updated_at)
  values ('', v_user.username,
          p_game || '|' || left(p_keys[1], 5) || '|' || p_duration || '|' ||
          p_max_devices || '|' || v_count || '|' || v_charge,
          now(), now());

  select jsonb_agg(to_jsonb(k) order by k.id_keys) into v_rows
  from (select * from public.keys_code where user_key = any(p_keys)) k;

  v_result := jsonb_build_object(
    'ok', true,
    'keys', to_jsonb(p_keys),
    'rows', coalesce(v_rows, '[]'::jsonb),
    'count', v_count,
    'price', v_charge,
    'unit_price', v_unit,
    'unlimited', v_unlimited,
    'saldo_before', v_user.saldo,
    'saldo_after', v_user.saldo - v_charge
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
  -- (A unique index guarantees there is exactly one level-1 account.)
  v_allow_all := (v_actor.level = 1);

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


revoke all on function public.create_keys_atomic(text, integer, text, integer, integer, bigint, bigint, text[]) from public, anon, authenticated;
revoke all on function public.delete_keys_refund(integer[], integer) from public, anon, authenticated;
