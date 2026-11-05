# AnoyMundo Panel — PHP → Vercel + Supabase

This repository contains the original archives (`Free Source .zip`,
`XLOX-Loader-Source.zip`) and a **working, free-hosting conversion** of the
CodeIgniter 4 / PHP 7.4 panel from `Free Source .zip`.

**Live site:** https://anoymundo-panel.vercel.app — deployed, tested and healthy.
See [DEPLOYMENT.md](DEPLOYMENT.md) for the full setup and verification record.

| | Original (PHP 7.4) | Converted (this repo) |
|---|---|---|
| Runtime | Apache / PHP + MySQL | Vercel Node.js serverless function |
| Database | MySQL / MariaDB | Supabase Postgres (free tier) |
| Sessions | files in `writable/session/` | signed, stateless HTTP-only cookie |
| Templates | CodeIgniter PHP views | Handlebars (converted 1:1) |
| Styling | Tailwind Play CDN + local CSS | static Tailwind + flat `app.css` design system, self-hosted Inter |
| Library upload | writes `TumharaPapaDARK/lib.so` to disk | stored in Supabase `client_release` |
| Deployment | paid shared hosting | GitHub → Vercel (free) |

The panel is branded **Anoy** (set with `BRAND_NAME`) and the Telegram contact
is **@libAkAudioVisiual** (`TELEGRAM_USERNAME`, linked as
`https://t.me/libAkAudioVisiual`). All original page layouts, DataTables,
SweetAlert2 and every original route are preserved. The visuals were refreshed
into a flat, gradient-free design system (`web/public/assets/css/app.css`):
responsive desktop sidebar / mobile drawer, self-hosted Inter typography,
consistent cards, forms, tables and alerts. `scripts/refresh-ui.py` documents
the one-off conversion of the legacy inline gradient styles.

**Interaction feedback** (`web/public/assets/js/app-ui.js`): every normal form
submit shows a spinner + a "please wait" label, disables the button, shows a
thin accent progress strip at the top of the viewport and ignores repeat
submits — sign-in does a server-side bcrypt check, so a click used to look like
a dead button. Button-style navigation links get the same treatment. The
`/lib` uploader (`web/views/lib.hbs`) uses XHR for real byte-level progress:
reading → uploading → saving → done, with accent / success / danger colours,
percent, bytes sent and an accessible `role="progressbar"`. Both respect
`prefers-reduced-motion` and release their state on bfcache restores.

Quick actions (create / delete / reset key) use compact `AnoyUI.toast()`
notices instead of full SweetAlert result dialogs, and success flashes are
promoted to the same small toast. Errors and warnings stay on the page as
compact banners. Delete confirmations use a smaller `swal-compact` dialog.

**iOS appearance** (`web/public/assets/css/ios.css`): a site-wide light + dark
design layer with the iOS grouped palette and one blue accent. The appearance
button in the top bar (and on both auth pages) toggles Light / Dark; the
segmented control on Settings also offers **System**. The choice is stored in
`localStorage` (`anoy-appearance`), applied by a small `<head>` script before
the stylesheets paint, and kept in sync across tabs and with OS changes.
`window.AnoyUI.setTheme()` / `getTheme()` expose it to custom views. Every text
field, select, segmented control, checkbox and 51×31 switch is an iOS-style
control, buttons are 12px blue pills with an in-button spinner while loading
(switch-only forms show the spinner in the switch track), and secondary copy
was shortened so screens stay quiet. No gradients and no CDN font dependency.

**Billing integrity and error handling (v1.2)** — `supabase/schema.sql` adds an
idempotent hardening migration that must be applied to the project once:

- `create_keys_atomic()` debits saldo and inserts the **whole** key batch in one
  Postgres transaction. A timeout, duplicate key or database error can never
  leave keys behind without charging (or charge without keys). If the function
  is unavailable the panel **fails closed** — no keys, no debit.
- Pricing is `price_per_device(duration) × max_devices × count`: 100 keys cost
  100× one key. A fresh balance is read server-side on every request, and an
  order that exceeds it is rejected **before** anything is written.
- `key_orders` gives every generation request an idempotency id, so a double
  click or replayed request returns the original batch instead of charging
  twice. A short abuse throttle caps rapid-fire purchases.
- `price_paid` is stored on each key. `delete_keys_refund()` deletes keys and
  refunds the exact price for keys that never had a device and never started
  their timer — in the same transaction. Used by single deletes, bulk deletes,
  user deletion and `DELETE /api/v1/keys/{key}`.
- `users_single_owner_idx` makes a second level-1 Owner row impossible at the
  database level. Owner-only routes are additionally bound to `admin`; referral
  codes and the user editor cannot mint another Owner. Login has a failed-attempt
  throttle and new passwords must be 8+ characters with a letter and a number.
- All unexpected errors now render an in-panel iOS error page, a compact flash
  on the form they came from, or JSON for API routes — never a raw Vercel stack
  trace. Mutating handlers check database errors and report clear messages.

**Library releases**: `/lib` shows the current version, suggests the next one,
and has a **Bump** button plus a separate **Push a link** action that updates
the external URL and version without uploading a file. Publish validates first
and restores the previous current release if the write fails, so there is never
zero current releases. `MAX_LIB_UPLOAD_MB` defaults to 3 because Vercel caps
request bodies at 4.5 MB and base64 adds ~33%.

**Mod status**: the Safe switch posts a hidden `floating_form` marker, so
turning the switch **off** is saved too (previously the handler was skipped).
`/connect` serves `_ftext._status` fresh on every request.

**UI clarity**: short iOS section titles, aligned field labels, grouped summary
rows for prices and release info, a live "per key × keys = total" estimate with
an insufficient-balance hint, and a tighter 390px phone layout. Flat colours
only — no gradients, no text overload.

## Layout

```
web/                     Vercel project (set Root Directory = "web")
  api/index.js           single serverless function / router
  src/                   config, Supabase access, auth, rendering, licensing
  views/*.hbs            converted original CI4 views
  public/                static CSS/JS/favicon/robots.txt
  scripts/               build, dev server, offline end-to-end tests
  vercel.json            filesystem-first routing to the function
  package.json           npm run build / dev / test
supabase/schema.sql      Postgres schema + seed data (applied to the project)
DEPLOYMENT.md            step-by-step deployment / verification guide
```

`legacy/` and `_extract/` are local-only, untracked copies of the extracted PHP
source used to build the conversion. **They are not deployed.** The obfuscated
`load.php` webshell found in the original `public/assets/css/` is deliberately
excluded.

## Quick start (offline demo, no credentials)

```bash
cd web
npm install
npm run build       # generates templates + compiles Tailwind, validates all modules
npm test            # 106 end-to-end HTTP tests using an in-memory DB
npm run dev         # http://localhost:3000  (DB_DRIVER=memory)
```

`.github/workflows/live-smoke.yml` additionally runs an automated end-to-end
test against the live Vercel deployment (login, key generation, `/connect`,
DataTables, library version, logout).

Demo login: `admin` / `admin123` — the seeded legacy bcrypt hash is verified
unchanged (`md5(salt + password)` → bcrypt cost 8).

## Production

See [DEPLOYMENT.md](DEPLOYMENT.md). Required Vercel environment variables:

- `SUPABASE_URL`
- `SUPABASE_SERVICE_ROLE_KEY` (server-only secret, never `NEXT_PUBLIC_`)
- `AUTH_SECRET`

Optional: `BRAND_NAME` (default `Anoy`), `TELEGRAM_USERNAME`
(default `libAkAudioVisiual`), `STATIC_WORDS`, `PUBLIC_BASE_URL`,
`MAX_LIB_UPLOAD_MB`, `FALLBACK_LIB_VERSION`.

## Preserved API contract

`POST /connect` keeps the exact JSON shape the Android client expects:
`status`, `reason`, and `data` with `real`, `token` (md5 of `real`), `modname`,
`mod_status`, `credit`, `ESP/Item/AIM/SilentAim/BulletTrack/Floating/Memory/
Setting`, `expired_date`, `EXP`, `exdate`, `device`, `rng`.

Other endpoints: `GET /api/health`, `GET /api/lib/version`,
`GET /api/lib/download`, `GET /keys/api`, `GET /admin/api/users`,
`GET /keys/reset`, `GET /keys/resetAll`, all legacy page routes.

## Two separate developer docs

| Doc | In panel | What it covers |
|---|---|---|
| [`INTEGRATION.md`](INTEGRATION.md) | `/docs` | **Loader / mod integration**: connect URL, `modname`, status text, floating text, mod features, expiry, error text, token check and simple C++ (libcurl + nlohmann/json) code |
| [`API.md`](API.md) | `/api-docs` | **Key-management REST API** (`/api/v1/*`) with personal API keys: create / list / read / update / reset / delete licence keys, saldo charging, error codes |
| [`API_AGENT_PROMPT.md`](API_AGENT_PROMPT.md) | – | Copy-paste prompt describing only the API-key API, for building a separate site/bot with another AI agent |

## Personal API keys (`/api/v1`)

Any logged-in user can create personal API keys at **`/api-keys`** (sidebar →
*Developer*). Tokens start with `amk_`, are shown once and are stored only as a
SHA-256 hash. Send them as `X-API-Key` on every `/api/v1/*` request. The API is
scoped to **licence keys only**: it can create, list, read, update, reset and
delete keys — never mod name, status/floating text, features, maintenance or
users. Creating a key through the API uses the exact same pricing and saldo
debit as the panel (`price = price_per_device × max_devices`, one fee per
generation request, with a conditional atomic debit and automatic refund on
failure).

## XLOX Android loader

[`xlox/`](xlox/README.md) holds the extracted `XLOX-Loader-Source.zip` app,
integrated with this panel: `GET /api/lib/version`, `GET /api/lib/download` and
`POST /connect`, with all URLs in `xlox/app/src/main/jni/panel_config.h`. The
broken library-download/load logic was rebuilt (HTTP + ZIP + ELF validation,
nested-release handling, real startup version check, visible errors).
[`.github/workflows/build-xlox.yml`](.github/workflows/build-xlox.yml) builds the
signed release APK and a starter `libbgmi.so` ZIP on GitHub Actions and uploads
both as the `xlox-apk` artifact and commits the signed APK to
[`dist/Anoy-XLOX-loader.apk`](dist/Anoy-XLOX-loader.apk). A starter
`libbgmi.so` ZIP is published to `/lib` so `/api/lib/download` is live; replace
it with the real library at `/lib` (Admin → Library) before shipping.

## Security notes

- All 13 tables have RLS enabled with **no public policies**; only the
  server-side `service_role` key accesses them, so the anon/publishable key
  cannot read passwords or license keys.
- No secret is committed to git (`.env*` is ignored).
- The PHP webshell, stale session files, and IP/mail logging stubs from the
  archive were not carried over.
- Sessions are HMAC-signed cookies with a 30-minute TTL (24 hours with
  "remember me"); no filesystem writes.
