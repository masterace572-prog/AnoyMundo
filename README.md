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
| Styling | Tailwind Play CDN + local CSS | Tailwind compiled to static CSS |
| Library upload | writes `TumharaPapaDARK/lib.so` to disk | stored in Supabase `client_release` |
| Deployment | paid shared hosting | GitHub → Vercel (free) |

The original UI is preserved: **PRINCE PAPA FILE** branding, purple/pink
gradients, glass cards, the original page layouts, DataTables, SweetAlert2 and
all original routes.

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
npm test            # 53 end-to-end HTTP tests using an in-memory DB
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

Optional: `BRAND_NAME`, `STATIC_WORDS`, `PUBLIC_BASE_URL`,
`MAX_LIB_UPLOAD_MB`, `FALLBACK_LIB_VERSION`.

## Preserved API contract

`POST /connect` keeps the exact JSON shape the Android client expects:
`status`, `reason`, and `data` with `real`, `token` (md5 of `real`), `modname`,
`mod_status`, `credit`, `ESP/Item/AIM/SilentAim/BulletTrack/Floating/Memory/
Setting`, `expired_date`, `EXP`, `exdate`, `device`, `rng`.

Other endpoints: `GET /api/health`, `GET /api/lib/version`,
`GET /api/lib/download`, `GET /keys/api`, `GET /admin/api/users`,
`GET /keys/reset`, `GET /keys/resetAll`, all legacy page routes.

## Security notes

- All 12 tables have RLS enabled with **no public policies**; only the
  server-side `service_role` key accesses them, so the anon/publishable key
  cannot read passwords or license keys.
- No secret is committed to git (`.env*` is ignored).
- The PHP webshell, stale session files, and IP/mail logging stubs from the
  archive were not carried over.
- Sessions are HMAC-signed cookies with a 30-minute TTL (24 hours with
  "remember me"); no filesystem writes.
