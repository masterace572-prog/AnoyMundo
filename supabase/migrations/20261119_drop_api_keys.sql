-- ============================================================================
-- Drop the retired personal API-key surface.
--
-- The panel no longer exposes API keys, /api-docs or the /api/v1 REST routes
-- (that whole feature was removed from the app code). The `api_keys` table
-- holds ONLY hashed, personal access tokens created by panel users for the
-- now-removed REST API - it never stored licence keys or user accounts.
--
-- NON-DESTRUCTIVE to the data that matters:
--   * public.users      - untouched (accounts / owners / balances kept)
--   * public.keys_code  - untouched (every licence key kept)
--   * everything else   - untouched
--
-- Idempotent: safe to re-run. Run in the Supabase SQL Editor.
-- ============================================================================
begin;

drop table if exists public.api_keys;

commit;
