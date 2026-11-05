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
both as the `xlox-apk` artifact. Upload the real loader ZIP at `/lib` (Admin →
Library) to make `/api/lib/download` live.

## Security notes

- All 13 tables have RLS enabled with **no public policies**; only the
  server-side `service_role` key accesses them, so the anon/publishable key
  cannot read passwords or license keys.
- No secret is committed to git (`.env*` is ignored).
- The PHP webshell, stale session files, and IP/mail logging stubs from the
  archive were not carried over.
- Sessions are HMAC-signed cookies with a 30-minute TTL (24 hours with
  "remember me"); no filesystem writes.
