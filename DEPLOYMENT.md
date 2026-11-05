# Deployment — AnoyMundo Panel on Vercel + Supabase

**Live site:** https://anoymundo-panel.vercel.app
**Status:** deployed, tested and healthy (last verified 2026-11-05).

| Piece | Value |
|---|---|
| GitHub | `masterace572-prog/AnoyMundo`, branch `arena/b2fac70f-anoymundo` |
| Vercel project | `anoymundo-panel` — `prj_eV3iBh1EUffuxQSaV6AkIEh0kn4D` (new project) |
| Vercel team | `masterace572-progs-projects` |
| Production deploy | `dpl_CbTwxFLGcDxZevTbw3ui93mBPuhZ` (READY / PROMOTED, saldo fix + API docs) |
| Panel name / Telegram | `Anoy` / `@libAkAudioVisiual` (`BRAND_NAME`, `TELEGRAM_USERNAME`) |
| Root Directory | `web` |
| Supabase | `anoymundo-panel` — `bwsqenepvfwhfomdhaig` (free, ap-south-1) |
| Default login | `admin` / `admin123` — **change this immediately** |
| Starter referral | `WELCOME` |

The existing `kuro-panel` Vercel project and its Supabase database were **not**
touched.

---

## 1. What is deployed

`web/` is the whole app. `vercel.json` pins `"framework": null` (Other preset)
so Vercel builds `api/index.js` as a normal Node.js serverless function instead
of auto-detecting `src/app.mjs` as a backend entrypoint. Routing:

```json
"routes": [
  { "src": "^/api/.*$", "dest": "/api/index.js" },
  { "handle": "filesystem" },
  { "src": "/.*", "dest": "/api/index.js" }
]
```

`public/` is served as static files; everything else goes to the single
function. `npm run build` compiles the Handlebars templates and Tailwind CSS
before the function is bundled; both generated files are also committed so the
bundle never depends on build ordering.

## 1b. UI design system

`web/public/assets/css/app.css` is the single source of visual styling, loaded
after Bootstrap, Tailwind and the legacy `natacode.css`. It provides:

- **No gradients** — solid indigo/teal/amber/blue accents, light neutral
  surfaces, thin borders and subtle shadows.
- **Typography** — self-hosted Inter variable font (`public/assets/fonts/`,
  copied from `@fontsource-variable/inter` by `npm run prebuild`), a single
  type scale, tabular figures for numbers, tighter heading tracking.
- **Responsive shell** — 252px sticky sidebar on desktop, off-canvas drawer +
  top bar under 960px, single-column grids/tables under 640px. Wide tables are
  wrapped in a horizontal scroll container by `public/assets/js/app-ui.js`.
- **Consistent components** — `.ui-card`, `.ui-btn`, `.ui-icon`, `.ui-tint`,
  `.ui-alert`, uniform inputs/selects/tables, focus rings and reduced-motion
  support.

All flash/error messages are plain text, so no markup is ever rendered as
visible code. `scripts/check.mjs` fails the build if a view reintroduces PHP
tags, gradients or escaped HTML in messages.

## 2. Supabase

Applied `supabase/schema.sql` as migration `anoymundo_panel_schema`, then
migration `add_api_keys_table` for the personal API-key feature. That creates
13 tables, enables RLS (service-role access only), and seeds the original
`main.sql` data: admin account, 2 license keys, feature flags, mod name,
server status, credit text and a `WELCOME` referral code. A placeholder
`client_release` row provides version `1.0` until a real loader ZIP is uploaded.

`public.api_keys` stores only a SHA-256 hash of each `amk_…` token plus its
prefix/last4, owner, status and `last_used_at`; plaintext is shown once in a
one-shot flash cookie.

`client_release.content_base64` stores the loader ZIP as base64 text — Vercel's
filesystem is read-only, and hex-encoded `bytea` would double the payload size.

Verify with:

```sql
select
  (select count(*) from users) as users,
  (select count(*) from keys_code) as keys,
  (select count(*) from referral_code) as referrals,
  (select version from client_release where is_current) as release;
-- expected: 1, 2, 1, 1.0
```

## 3. Vercel environment variables

Configured for Production + Preview + Development:

| Name | Notes |
|---|---|
| `SUPABASE_URL` | `https://bwsqenepvfwhfomdhaig.supabase.co` |
| `SUPABASE_SERVICE_ROLE_KEY` | encrypted, server-only — never `NEXT_PUBLIC_` |
| `AUTH_SECRET` | encrypted, signs the session cookie |
| `STATIC_WORDS` | `Vm8Lk7Uj2JmsjCPVPVjrLa7zgfx3uz9E` (must match loader) |
| `BRAND_NAME` | `Anoy` (panel name in sidebar / `<title>` / `GET /connect` info) |
| `TELEGRAM_USERNAME` | `libAkAudioVisiual` — used on login, register and `GET /connect` info |
| `PUBLIC_BASE_URL` | `https://anoymundo-panel.vercel.app` |
| `MAX_LIB_UPLOAD_MB` | `4` |
| `FALLBACK_LIB_VERSION` | `1.0` |

No secret is committed to git (`.env*` is ignored).

> Deployments are created explicitly from the branch (Vercel dashboard →
> Deployments → Redeploy, or the Vercel API with
> `gitSource.ref = arena/b2fac70f-anoymundo`). If you want deploys on every
> push, enable "Create deployments" in the project's Git settings. The project
> is linked to `masterace572-prog/AnoyMundo`; its production branch is `main`,
> so to use `main` for production, merge this branch into `main` first.

## 4. Verified endpoints

| Check | Result |
|---|---|
| `GET /api/health` | `{"ok":true,...,"schema":"13/13 expected tables present"}` |
| `GET /` and `/login` | flat Anoy sign-in page with @libAkAudioVisiual contact |
| `GET /register` | responsive two-column sign-up form |
| `POST /login` (`admin`/`admin123`) | 302 → `/dashboard`, signed `am_session` cookie |
| `/dashboard`, `/keys`, `/keys/generate`, `/settings`, `/Server`, `/admin/*`, `/lib` | 200, responsive sidebar layout, no gradients, no leaked PHP/HTML |
| `GET /keys/api`, `GET /admin/api/users` | DataTables JSON from Supabase |
| `POST /keys/generate` | creates a key, deducts saldo, writes history |
| `POST /connect` | `status:true` + `real`, `token`, `modname`, 8 feature flags, expiry fields |
| `GET /connect` (no params) | `{"web_info":{"_client":"Anoy",...},"web__dev":{"author":"@libAkAudioVisiual","telegram":"https://t.me/libAkAudioVisiual"}}` |
| `POST /connect` with bad key | `{"status":false,"reason":"USER OR GAME NOT REGISTERED"}` |
| `GET /docs` | in-panel **integration** reference (connect URL, mod name, status/floating text, expiry, C++ example) |
| `GET /api-docs` | in-panel **key-management API** reference (`/api/v1/*`, personal API keys) |
| `GET /api-keys` | create / revoke / delete personal API keys (token shown once) |
| `POST /api/v1/keys` | create licence keys with `X-API-Key`; charges saldo (`price_per_device × max_devices`, one fee per request) |
| `GET /api/v1/keys` | list only the caller's keys (`limit`, `offset`, `game`, `status`) |
| `GET /api/v1/me`, `GET /api/v1/pricing` | account saldo + duration pricing |
| Saldo inputs on `/admin/user/1`, `/admin/create-referral` | `field-prefix` wrapper + `padding-left: 2.875rem !important` — typed digits never hide behind the currency icon |
| `GET /api/lib/version` | plain-text `1.0` |
| `GET /assets/css/tailwind.css` | compiled CSS, no CDN dependency |
| `GET /assets/css/app.css` | flat design system (no gradients) |
| `GET /assets/fonts/inter-latin-wght-normal.woff2` | self-hosted Inter variable font |
| `GET /logout` | clears session |

`/connect` accepts both the original form POST and a query-string GET
(`/connect?game=PUBG&user_key=...&serial=...`) for loaders that use GET.

Example:

```bash
curl -sS -X POST https://anoymundo-panel.vercel.app/connect \
  -d 'game=PUBG' -d 'user_key=admin-2-lyK0Y' -d 'serial=TESTDEVICE001'
```

## 5. Automated tests

- `npm test` — 106 offline end-to-end HTTP tests using an in-memory DB driver
  (login, registration, key CRUD, DataTables, `/connect`, maintenance/feature
  toggles, referral → reseller, library upload/version, logout), plus UI
  regression guards: no gradients, no escaped HTML/PHP in notices or form
  values, responsive viewport, flat CSS and font assets served, the `Anoy`
  brand and `@libAkAudioVisiual` handle on login, register and `GET /connect`
  info, the saldo icon-prefix padding fix, and the `/docs` API reference page.
- `API.md` — the full loader/API reference (also rendered at `/docs`).
- `npm run build` — compiles templates + Tailwind, copies the Inter font,
  parses every template (syntax errors fail the build), validates modules,
  rejects leftover PHP/gradient markup, and verifies the legacy `admin123`
  bcrypt hash.
- `.github/workflows/live-smoke.yml` — GitHub Actions job that curls the live
  Vercel deployment: health, login with cookie jar, all pages, DataTables,
  key generation, `/connect` success + rejection, cleanup, logout, plus the
  flat-UI asset checks, the escaped-text regressions, the `Anoy` /
  `@libAkAudioVisiual` branding guards, the saldo icon-padding fix and the
  `/docs` API reference. Latest passing run: `42008897465`
  (also `42005719821`, `42003546248`, `41998939557`, `41998471594`).

## 6. Post-deploy tasks

1. **Change the seeded admin password** at `/settings`.
2. **Upload the loader ZIP** at `/lib` (owner only, max 4 MB — Vercel's
   function body limit is 4.5 MB). For larger files use an external URL.
   `GET /api/lib/download` then serves it and `/api/lib/version` reports the
   version.
3. Point the XLOX loader's `Version()` / `Link()` endpoints at
   `/api/lib/version` and `/api/lib/download` on this domain (later phase).
4. Add a custom domain in Vercel → Settings → Domains and update
   `PUBLIC_BASE_URL` if you use one.

## 7. Local development & troubleshooting

```bash
cd web
npm install
npm run build     # prebuild + validation
npm test          # 106 offline end-to-end tests
npm run dev       # live preview on 0.0.0.0:3000 (memory DB by default)
```

To use the real Supabase project locally, put the three required variables in
`web/.env.local` and run `npm run dev` (it selects Supabase automatically when
`SUPABASE_URL` is present).

| Symptom | Cause / fix |
|---|---|
| `env.ok:false` at `/api/health` | `SUPABASE_URL` or `SUPABASE_SERVICE_ROLE_KEY` missing |
| `schema: 5/12` | `supabase/schema.sql` not applied |
| 500 on every page | wrong service key, or `vercel.json` framework not pinned to `null` |
| login loops | `AUTH_SECRET` differs between deployments; set it explicitly |
| unstyled pages | `npm run build` didn't run (Tailwind CSS not compiled) |
| `/api/lib/download` 404 | no release uploaded yet — use `/lib` |

Logs: Vercel → `anoymundo-panel` → Deployments → Function logs. Uncaught errors
are logged as `[anoymundo] request failed:`.
