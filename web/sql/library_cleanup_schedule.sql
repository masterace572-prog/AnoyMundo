-- Optional infrastructure for reliable post-publication cleanup on the free
-- tier, even if the publisher closes the site. Install AFTER the retention
-- migration and deploying web/edge/library-storage-cleanup.mjs.
-- No account/API credentials are read, copied, rotated or returned. A separate
-- random task token is generated inside Postgres and stored encrypted in Vault.
-- New jobs start PAUSED; verify the live libraries through the worker before
-- enabling this dedicated job. Reapplying preserves an existing job's state.
begin;
create extension if not exists pg_net with schema extensions;
create extension if not exists pg_cron;

do $$
declare v_token text;
begin
  select decrypted_secret into v_token from vault.decrypted_secrets where name = 'loader_library_storage_worker' limit 1;
  if v_token is null then
    v_token := encode(extensions.gen_random_bytes(32), 'hex');
    perform vault.create_secret(v_token, 'loader_library_storage_worker', 'Dedicated library-retention worker task token; not an account or API key');
  end if;
  insert into public.library_storage_worker_auth(singleton, token_sha256)
    values(true, encode(extensions.digest(v_token, 'sha256'), 'hex'))
    on conflict (singleton) do update set token_sha256 = excluded.token_sha256;
end $$;

create or replace function public.request_library_storage_cleanup(p_action text default 'cleanup')
returns bigint language plpgsql security definer set search_path = public, pg_temp as $$
declare v_token text; v_request bigint;
begin
  if p_action not in ('cleanup', 'verify', 'maintenance') or p_action is null then raise exception 'Unknown library maintenance action'; end if;
  if p_action = 'cleanup' then
    perform public.plan_library_storage_cleanup(null, true);
    -- Keep only this new job's own short-lived operational logs, never any
    -- unrelated application, account, billing or other cron-job history.
    delete from cron.job_run_details where jobid in (
      select jobid from cron.job where jobname = 'loader-library-storage-cleanup'
    ) and end_time < now() - interval '2 days';
    if not exists(select 1 from public.library_storage_cleanup where deleted_at is null and delete_after <= now()) then return null; end if;
  end if;
  select decrypted_secret into v_token from vault.decrypted_secrets where name = 'loader_library_storage_worker' limit 1;
  if v_token is null then raise exception 'Library cleanup task is not configured'; end if;
  select net.http_post(
    url := 'https://bwsqenepvfwhfomdhaig.supabase.co/functions/v1/library-storage-cleanup',
    headers := jsonb_build_object('Content-Type', 'application/json', 'Authorization', 'Bearer ' || v_token),
    body := jsonb_build_object('action', p_action), timeout_milliseconds := 60000
  ) into v_request;
  return v_request;
end $$;
revoke all on function public.request_library_storage_cleanup(text) from public, anon, authenticated, service_role;

do $$
declare v_job bigint;
begin
  if not exists(select 1 from cron.job where jobname = 'loader-library-storage-cleanup') then
    select cron.schedule('loader-library-storage-cleanup', '* * * * *', 'select public.request_library_storage_cleanup();') into v_job;
    perform cron.alter_job(v_job, active := false);
  end if;
end $$;
commit;

-- After successful verify/maintenance checks (only a request ID is returned):
-- select public.request_library_storage_cleanup('verify') as request_id;
-- Inspect net._http_response for THAT request ID, without reading request headers.
-- select cron.alter_job(jobid, active := true) from cron.job where jobname = 'loader-library-storage-cleanup';
