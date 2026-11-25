-- BEGIN LIBRARY 20 MIB PUBLICATION
-- The RPC checks the NORMALIZED object, not the uploaded/expanded input.
-- Reserve the same 21 MiB as Storage, including ZIP framing/deflate overhead.
-- Patch only the size expression in the installed function. This preserves
-- either historical two-owner or newer Real Owner/retention logic, function
-- identity, SECURITY DEFINER/search_path, grants, locks and cleanup safeguards.
-- No release/data/object is published, replaced, enqueued or deleted here.
begin;
do $library_limit$
declare
  v_definition text;
  v_old constant text := 'coalesce((p_release->>''file_size'')::bigint, 0) not between 1 and 11534336';
  v_new constant text := 'coalesce((p_release->>''file_size'')::bigint, 0) not between 1 and 22020096';
begin
  v_definition := pg_get_functiondef('public.publish_library_release(integer,jsonb)'::regprocedure);
  if position(v_old in v_definition) > 0 then
    if (length(v_definition) - length(replace(v_definition, v_old, ''))) / length(v_old) <> 1 then
      raise exception 'Unexpected duplicate library size guard; review the publication function';
    end if;
    execute replace(v_definition, v_old, v_new);
  elsif position(v_new in v_definition) = 0 then
    raise exception 'Unknown library size guard; review the publication function';
  end if;
end $library_limit$;
commit;
