# Deployment — shared Anoy / KennyPvt panel

**Panel:** https://anoymundo-panel.vercel.app
**Repository:** `masterace572-prog/AnoyMundo`
**Implementation branch:** `arena/dda4cc57-anoymundo`

## 1. Infrastructure

One custom Node ESM / Handlebars application, deployed as `web/api/index.js`
on Vercel (project `anoymundo-panel`, root directory **web**, region **bom1**).
One Supabase project (`bwsqenepvfwhfomdhaig`, **ap-south-1**), with private
Postgres tables and private Storage. The application uses its own sessions,
not Supabase Auth. Only the server has the service-role key.

Build: `npm install` then `npm run build`. Generated templates and icon assets
are committed so function bundling does not depend on build order. A stale
template bundle fails `npm run check`.

## 2. Database migration / safe rollout

- **Fresh project:** run `supabase/schema.sql`. It includes v2.4's two-owner
  constraints, RPCs and Storage bucket. It initially seeds Anoy only; invite
  the KennyPvt owner through the panel.
- **Existing project:** apply **only**
  `supabase/migrations/20261119_two_owner_channels.sql` for this upgrade. It is
  additive/non-destructive: users, keys, settings, old library bytes and APK
  releases are preserved. Existing releases become `channel='anoy'`.
- **NEVER re-run `20261107_v2_fresh_start.sql` on production.** That historical
  migration deliberately deletes users, keys and billing/referral history.

Order: run local checks → apply the new migration transactionally → deploy the
current branch to Vercel → verify public and signed-in endpoints → issue the
single-use KennyPvt Owner invitation. Do not create a throwaway Kenny Owner in
production for testing: that slot is intended for the real second Owner and
Owner identities cannot be deleted/reassigned by the application.

The migration replaces the old one-owner index with two unique, immutable
Owner channels (`anoy`, `kenny`). Only Anoy can create the second-owner invite;
it expires after 7 days and is consumed transactionally on registration.
Owner accounts have unlimited billing balance and no account expiry. Password
and full-name changes are still made by each user in their own Settings.
Neither Owner may edit/delete/demote/rename the other Owner account.

Useful **read-only** verification:

```sql
select username, level, owner_channel from public.users order by id_users;
select channel, version, file_size, sha256, storage_path
  from public.client_release where is_current order by channel;
select channel, version_name, version_code
  from public.app_release where is_current order by channel;
select id, public, file_size_limit from storage.buckets
  where id = 'loader-releases';
```

Each release table permits at most one current row **per channel**. Publication
uses service-role-only RPCs with per-channel advisory locks, so failed writes
cannot demote the previous release or touch the other channel. Keys, pricing,
billing RPCs, maintenance, floating text and feature settings remain shared.

## 3. Library files: 10 MiB, .so or ZIP

The browser first asks `POST /lib {action:'prepare_upload', file_size:...}`.
The server authenticates the Owner and signs one random private object:
`<own-channel>/pending/<uuid>`. The browser **PUTs the binary directly to
Storage**, then sends a small publication descriptor to `/lib`. This avoids
Vercel's approximately 4.5 MB request-body limit.

The server caps both input and total expanded ZIP bytes at **10 MiB** and ZIP
entry count at 128; checks paths, CRCs and actual bounded inflation; accepts
arm64 ELF libraries; and writes a normalized ZIP with **libbgmi.so** as its main
entry. Input filenames do not become installed library names. Verified sibling
`.so` dependencies are retained. The private bucket `loader-releases` allows
11 MiB per object to accommodate normalized ZIP overhead. Do not make it public
or add anonymous upload policies.

`GET /api/lib/download?channel=…` signs a short-lived download URL and returns
302. The manifest supplies normalized size and SHA-256. Existing base64-stored
releases remain downloadable unchanged; legacy small base64 publications are
accepted up to approximately 3 MiB, but new integrations must use direct
upload for the full 10 MiB limit. External HTTPS links remain supported.
`cancel_upload` can remove only an Owner's own **pending** object, never a live
release. A lost publication response is ambiguous: reload before retrying;
the server never deletes an output that might already be current.

## 4. Environment variables

| Name | Required | Notes |
|---|---|---|
| `SUPABASE_URL` | yes | Existing Supabase URL; its HTTPS origin is also allowed by the panel CSP for direct uploads |
| `SUPABASE_SERVICE_ROLE_KEY` | yes | Server only; never exposed to the browser |
| `AUTH_SECRET` | yes | Keep stable between deployments; signs session cookies |
| `STATIC_WORDS` | yes | Must match native `panel_config.h`; unchanged by this upgrade |
| `BRAND_NAME` | no | Shared website name; default `Anoy` |
| `OWNER_USERNAME` | no | Legacy account-name safeguard only; not Owner authority |
| `TELEGRAM_USERNAME` | no | Shared contact; default `libAkAudioVisiual` |
| `PUBLIC_BASE_URL` | no | Canonical panel URL |
| `MAX_LIB_UPLOAD_MB` | no | Legacy deployment metadata only; v2.4 enforces 10 MiB in code |
| `FALLBACK_LIB_VERSION` | no | Anoy-only legacy fallback; Kenny never falls back to Anoy |

No new secret, second database, second site or client-side service key is
needed. Production is deployed explicitly from the working branch; do not
assume that a Git push automatically promotes the site. Use the existing
Vercel project/team, with `gitSource.ref='arena/dda4cc57-anoymundo'` and
`target='production'` (or the Vercel dashboard's deploy/promote controls).

## 5. Android build and publication

One workflow, **Build Anoy and KennyPvt APKs** (`build-xlox.yml`), builds:

| Flavor / Gradle task | Application ID | Download / artifact |
|---|---|---|
| `:app:assembleAnoyRelease` | `com.xlox` | `Anoy-XLOX-loader.apk` / `Anoy-APK` |
| `:app:assembleKennyRelease` | `com.kennypvt` | `KennyPvt-loader.apk` / `KennyPvt-APK` |

Both use the existing signing key and repository secrets `XLOX_STORE_PASSWORD`
/ `XLOX_KEY_PASSWORD`. The workflow never regenerates Anoy's key. Without
signing, it produces clearly marked **unsigned artifacts only**, not a public
release. JDK 17, SDK 36 and NDK `24.0.8215888` are installed on the CI runner.

The workflow runs panel/SQL tests, UI/branding static gates, both builds,
both lint tasks, package/branding/permission checks on both APKs, signature
verification and native alignment checks. Signed APKs are attached to **one**
`loaders-v<versionName>-<versionCode>` GitHub release. Old releases/tags are
**never pruned** because either Owner may still be using their links. No live
library or APK update is automatically published to the panel.

Each Owner goes to **App update → Use latest build**, which selects only their
brand's filename, reviews the changelog and publishes. Unknown/forged write
channels are rejected; the server derives the real channel from the user's
immutable database identity. Existing loaders without a `channel` parameter
continue using Anoy. KennyPvt adds `channel=kenny` to every library and app-update
check, including Java fallbacks and native endpoints.

## 6. Verification

```bash
cd web
npm ci
npm run build
npm test                 # 213 smoke assertions, channel/upload HTTP tests, PostgreSQL tests
cd ..
python3 xlox/scripts/ui_quality_gate.py
python3 xlox/scripts/check_branding.py
```

The PostgreSQL tests use PGlite offline, including migration preservation,
repeat application, full-schema reapplication after two Owners exist, invite
replay, Owner protection, cross-channel RPC denial and shared billing.

`live-smoke.yml` is manual: runs the deployed app's public checks and, when
`PANEL_OWNER_PASSWORD` is configured in repository secrets, signed-in checks
with disposable keys/API keys. The tests do not create an Owner or change a
live release. `web-ui-qa.yml` captures desktop/mobile pages. Manual Android UI
QA and APK-update end-to-end workflows continue targeting Anoy's preserved
package with explicit flavor tasks.

## 7. Troubleshooting

| Symptom | Check |
|---|---|
| Owner has no release access / column missing | Apply the v2.4 migration before deploying the new code |
| Direct upload blocked by CSP | `SUPABASE_URL` must be the configured HTTPS Storage project origin |
| Storage HTTP 404 / bucket missing | Migration must create private `loader-releases` |
| Upload rejected | Input ≤10 MiB; expanded ZIP ≤10 MiB; valid arm64 ELF; unambiguous main library |
| Kenny download 404 before first publication | Expected; the second Owner must upload their own library |
| APK update rejected on device | Check flavor/package, versionCode and signing key; APKs are not interchangeable |
| Sign-in loops | Check stable `AUTH_SECRET` and account password/session status |
