# Deployment — Anoy panel on Vercel + Supabase

**Live site:** https://anoymundo-panel.vercel.app

| Piece | Value |
|---|---|
| GitHub | `masterace572-prog/AnoyMundo`, branch `arena/2e95185a-anoymundo` |
| Vercel project | `anoymundo-panel` — `prj_eV3iBh1EUffuxQSaV6AkIEh0kn4D`, Root Directory `web` |
| Supabase | `anoymundo-panel` — `bwsqenepvfwhfomdhaig` (ap-south-1) |
| Owner | `Anoy` (level 1, unlimited balance, saabarjun705@gmail.com) |
| Panel name / Telegram | `Anoy` / `@libAkAudioVisiual` |

---

## 1. What is deployed

`web/` is the whole app. `vercel.json` pins `"framework": null` so Vercel builds
`api/index.js` as one Node.js serverless function; `public/` is served as static
files (`/assets/css/ui.css`, `/assets/js/app-ui.js`, fonts, `/favicon.svg`) and
every other path goes to the function. `npm run build` compiles the Handlebars
templates into `src/templates.generated.mjs` (committed, so bundling never
depends on build order) and runs `scripts/check.mjs`, which fails the build on
broken templates, leftover PHP, gradients, external CDNs or billing/security
regressions.

## 2. Supabase

- Fresh projects: run `supabase/schema.sql` once (tables, RLS — service role
  only —, `create_keys_atomic`, `delete_keys_refund`, helper functions).
- This project: migration `supabase/migrations/20261107_v2_fresh_start.sql`
  (applied as `v2_fresh_start_owner_anoy`). It
  - widens `keys_code.user_key` to 64 characters,
  - **wipes** all users, keys, API keys, referral codes, history, key orders
    and sign-in attempts,
  - creates the single Owner `Anoy` (bcrypt hash only, balance 999999999999,
    treated as unlimited and never charged),
  - adds a case-insensitive unique index on usernames,
  - updates `create_keys_atomic` so level-1 accounts are never charged.
  Server settings (maintenance, mod status, feature flags) and the loader
  release are kept.
- Migration `supabase/migrations/20261107_v2_2_app_release.sql` adds the
  `app_release` table behind `/app-update` and `GET /api/app/update`.

Check:

```sql
select (select count(*) from users) as users,
       (select username from users where level = 1) as owner,
       (select count(*) from keys_code) as keys,
       (select count(*) from api_keys) as api_keys,
       (select version from client_release where is_current) as release;
-- expected: 1, Anoy, 0, 0, <current library version>
```

`client_release.content_base64` holds the loader ZIP (Vercel's filesystem is
read-only). `GET /api/lib/manifest` reports its version, size and SHA-256; the
Android app verifies both after downloading.

## 3. Vercel environment variables

| Name | Required | Notes |
|---|---|---|
| `SUPABASE_URL` | yes | `https://bwsqenepvfwhfomdhaig.supabase.co` |
| `SUPABASE_SERVICE_ROLE_KEY` | yes | server-only, never `NEXT_PUBLIC_` |
| `AUTH_SECRET` | yes | signs the session cookie; keep it stable |
| `STATIC_WORDS` | yes | licence-token salt — must match `xlox/app/src/main/jni/panel_config.h` |
| `BRAND_NAME` | no | `Anoy` |
| `OWNER_USERNAME` | no | default `Anoy` |
| `TELEGRAM_USERNAME` | no | `libAkAudioVisiual` |
| `PUBLIC_BASE_URL` | no | `https://anoymundo-panel.vercel.app` |
| `MAX_LIB_UPLOAD_MB` | no | `3` (the base64 JSON upload must stay under Vercel's 4.5 MB body limit; use an external URL for bigger files) |
| `FALLBACK_LIB_VERSION` | no | version reported when no release exists |

No secret is committed (`.env*` is git-ignored).

Pushes to the branch create preview deployments only. Production
(`anoymundo-panel.vercel.app`) is deployed from the branch on demand (Vercel
dashboard → Deployments → Promote / Redeploy, or the API with
`gitSource.ref = arena/2e95185a-anoymundo` and `target = production`).

## 4. Verification

| Where | What |
|---|---|
| `cd web && npm test` | end-to-end checks against an in-memory DB: sign-in, every page, random + custom keys (format, upper case, duplicates, 4–6 char endings), charging/refunds, `/connect`, maintenance, feature flags, API keys and `/api/v1`, library upload/manifest/download, security guards |
| **Live deployment smoke test** workflow | `web/scripts/live-smoke.mjs` against the live site: health, pages, assets, manifest vs. download SHA-256, and — when the repository secret `PANEL_OWNER_PASSWORD` is set — Owner sign-in, key creation (random, custom, duplicate), `/connect`, API keys, then deletes everything it created. With a reseller account (`temp_password` input) it checks reseller pages and verifies charges and refunds instead |
| `GET /api/health` | `{ ok: true }` with database + schema checks |

## 5. After deploying

1. Sign in as **Anoy** and change the password under **Settings**.
2. Add the repository secret **`PANEL_OWNER_PASSWORD`** (GitHub → Settings →
   Secrets → Actions) if you want CI to run the signed-in live checks and to
   publish loader ZIPs (`build-xlox.yml` → *publish_loader_zip*). The
   repository is public, so never put the password in a workflow file.
3. Upload the real arm64-v8a `libbgmi.so` (as a ZIP) at **/lib**. Every app
   install must update to it before it can sign in.
4. Create referral codes for resellers under **Referral codes**.

## 6. Android loader builds

`.github/workflows/build-xlox.yml` (push to `xlox/**` or manual run) runs the
static UI gates, builds the signed release APK with JDK 17, the runner's
Android SDK and NDK `24.0.8215888`, runs Android Lint, checks the APK's
permissions and the 16 KB ELF alignment of `libXloX.so` and the loader ZIP,
uploads the `xlox-apk` artifact (7 days) and publishes the GitHub release
`anoy-v<versionName>-<versionCode>` (marked Latest; every other release and tag
is deleted). Nothing is committed back to the repository. The owner then
publishes the update in the panel at `/app-update` (**Use latest build**).
`android-ui-qa.yml` (manual) runs emulator screenshots; `app-update-e2e.yml`
(manual) tests the in-app update against the real release asset.

## 7. Local development & troubleshooting

```bash
cd web
npm ci
npm run build
npm test
npm run dev        # 0.0.0.0:3000; memory DB unless SUPABASE_URL is set (web/.env.local)
```

| Symptom | Cause / fix |
|---|---|
| `/api/health` → `env.ok:false` | `SUPABASE_URL` or `SUPABASE_SERVICE_ROLE_KEY` missing |
| `schema: n/14` | `supabase/schema.sql` not applied |
| “temporarily unavailable … nothing was charged” | Supabase unreachable — retry |
| sign-in loops | `AUTH_SECRET` changed between deployments |
| app says “Panel returned HTTP 5xx” | check Vercel function logs (`[anoymundo]` lines) |
| `/api/lib/download` 404 | no release uploaded yet — use `/lib` |
