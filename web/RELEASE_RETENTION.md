# Site release ownership and library retention

This change is confined to `web/`, including the site's additive backend SQL and
cleanup worker. Android, original Mundo, artwork, APKs, signing, login work and
GitHub release assets are not changed.

## 20 MiB upload follow-up

The current panel accepts **20 MiB uploaded AND 20 MiB total expanded**. This is
not a per-library expanded allowance: two 7.57 MiB siblings fit one ZIP. The
original retention/ownership rollout below is historical; this limit increase
does not publish a replacement library or APK.

- Source `365fb8de0c88f4ec98545a81c6c1e6d92f096162`; production deployment
  `dpl_2bmvyqJDfA1aFJyzcTkctmzD2pKC` is READY/PROMOTED on the existing panel URL.
- Additive migration `20261125082507_site_library_upload_20mib` corresponds to
  `../supabase/migrations/20261121134307_library_upload_20mib.sql`. It raises the
  private bucket to **21 MiB** for normalized ZIP overhead. Every existing library
  and APK release row matched the pre-migration digests afterwards.
- Local/CI checks: 213 smoke + 37 archive/channel/live-safety + 8 cleanup-worker +
  127 PostgreSQL checks; Chromium covers direct 20 MiB uploads on the offline site.
- The repository's explicit `[live-check]` push opt-in (or the workflow's manual
  opt-in when available) tests one **private pending** 20 MiB PUT, then deletes it.
  It rejects oversize/unknown-channel tickets and compares both libraries and
  APK selections before/after. It never resets a real channel (Anoy is Real Owner),
  publishes a test library, or reaches licence-key activation/generation fixtures.
  The real-Storage result must be read from CI, not inferred from a READY deploy.
- The Android split/no-OBB follow-up is separate: both signed variants built and
  passed **320/320** host checks in [45576035996](https://github.com/masterace572-prog/AnoyMundo/actions/runs/45576035996).
  Publication was skipped. Device game testing is still required; see
  [installed APK-set scope](../docs/SPLIT_APK_SUPPORT.md).

## Ownership

- The two immutable Owner slots remain permanent, with their existing numeric
  role, credentials and unlimited billing behavior.
- **Anoy is Real Owner**: Library and App update offer an Anoy / KennyPvt switch.
  Anoy can publish/manage either channel, including the correctly branded latest
  GitHub APK lookup. Audit fields record the actual actor, not an impersonated user.
- Kenny remains Owner of Kenny only. Both HTTP authorization and the publication
  SQL functions reject Kenny targeting Anoy. Display names and form flags confer
  no authority. Missing selectors preserve each Owner's previous default channel.
- Licence keys, devices, referrals, pricing and general settings stay shared.

## Retention and safety

1. Accept the existing 20 MiB `.so` / ZIP inputs and normalize to `libbgmi.so`.
   Read back the newly stored ZIP and verify its length and SHA256 before publishing.
   External links must also yield a valid library before replacing a working file.
2. Atomically select the new current release and enqueue retired managed objects.
   A failed transaction rolls back the selection and deletion queue together.
3. Clear only retired inline `content_base64` payloads; retain history metadata.
   Both current channels and any current managed-object URL reference are protected.
4. Wait **three minutes** before deleting Storage objects (download signatures last
   60 seconds). Pending/unreferenced upload objects are ignored until 24 hours old.
5. A scheduled worker removes physical bytes with `Storage.remove`, rechecking
   current references first. Tombstones prevent retired paths being made current
   during a concurrent deletion, including through percent-encoded external URLs.
6. Storage/acknowledgement failures leave retryable queue rows. They do not turn an
   already successful publication into a failed-publication prompt. Provider codes
   and operation names are safe diagnostics; provider messages and private URLs
   are never logged. No public loader request can trigger deletion.

The database checks every minute and invokes the worker only when deletions are
due. It does not redownload current libraries on idle ticks. An authenticated
Library visit or successful publication can also drain due files. The job only
prunes its own new operational logs after two days, not application/audit history.

## Backend rollout / fresh installs

Apply these **after** the existing schema and the two-owner/referral migrations;
never run the destructive fresh-start migration against an existing project:

1. `sql/real_owner_library_retention.sql` (live migration
   `20261121134238_site_real_owner_library_retention`). Installation itself does
   not clear any payload, replace either release, or change any user.
2. `node scripts/build-library-worker.mjs` creates a single-file Edge bundle in
   `.cache/site-owner-storage/` at the repository root. Deploy it as the
   `library-storage-cleanup` Supabase Edge function. The gateway JWT check is
   disabled because the handler enforces its separate dedicated task token.
3. `sql/library_cleanup_schedule.sql` (live migration
   `20261121134306_site_library_cleanup_schedule`) creates a **paused** job.
   Its dedicated random task token is generated inside Postgres, stored encrypted
   in Vault, and never copied to source, environment files, or an operator response.
   Account/service/API credentials are neither retrieved nor rotated.
4. Run the documented authenticated `verify` action and inspect that request's
   `net._http_response`: both live stored libraries must match their saved SHA256
   and length. Then run maintenance/queue the verified obsolete files and enable
   **only** the `loader-library-storage-cleanup` job. Recheck both downloads and
   the Storage inventory after the grace period.
5. Deploy the site from the active branch. No APK publication is needed. The
   existing combined Android workflow also watches `web/**`, so this site-only
   push uses `[skip ci]`; all relevant site tests are run locally beforehand.

Do not reapply historical publication functions *after* the site migration; that
would restore the old reciprocal isolation. Both new SQL files are idempotent.
For another Supabase project, change the worker URL in the scheduler SQL first.

## Local verification

```sh
cd web
npm ci --no-audit --no-fund
npm run build
npm test
node scripts/build-library-worker.mjs
```

The suite covers existing billing/referral/licence behavior, asymmetric release
permissions, 20 MiB/archive limits, concurrent publication, transaction rollback,
readback failure, broken links, short-lived downloads, stale-orphan protection,
Storage errors, acknowledgement retries, worker authentication, and scheduling.
The scheduler's SQL control flow is exercised with offline extension stubs; live
cron/HTTP behavior is checked separately during rollout.

The existing offline Chromium browser flow also exercises Anoy publishing Kenny's
library, the correctly branded latest-APK lookup, publishing/removing Kenny's APK,
Kenny's restrictions, and desktop/390px phone layouts. It never uses live accounts.

Optional operator CLI in the site's privately configured environment:

```sh
npm run storage:cleanup                      # read-only plan
npm run storage:cleanup -- --apply           # verify before/after; respect grace
npm run storage:cleanup -- --apply --channel=kenny
```

Database payload removal makes that space reusable; physical database size/quota
counters may lag autovacuum. It is distinct from reclaimed Storage bucket bytes.

## Live backend acceptance

- 375 automated checks passed, plus desktop/mobile Chromium publication flows.
- The scheduled worker deleted nine obsolete stored objects via Storage API:
  11 objects / 33,556,295 bytes became 2 objects / 6,194,364 bytes
  (27,361,931 bytes reclaimed from Storage).
- 43 retired inline payloads (170,753,180 base64 bytes) were cleared; all 60
  release-history rows were retained. No current object entered the deletion queue.
- Actual private downloads for Anoy 2.2 and Kenny 0.2 matched their saved length
  and SHA256 before and after cleanup. The authorized verification returned 200;
  the real unauthenticated worker POST returned 401.
- The job remains enabled, has a confirmed successful automatic deletion run,
  and makes no Edge/file-download request when there is no due work.
- Account, referral, general-settings and APK-table digests matched their
  pre-rollout values. No live licence key or referral was consumed for QA;
  normal live licence activity was not blocked or rolled back.
