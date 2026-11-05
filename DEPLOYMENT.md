# Deployment guide — AnoyMundo Panel on Vercel + Supabase

The site is already converted in `web/`. The free stack is:

- **GitHub** `masterace572-prog/AnoyMundo` (source of truth)
- **Vercel** hobby project, Root Directory `web`, Node 20/22
- **Supabase** free project `anoymundo-panel` (`bwsqenepvfwhfomdhaig`, ap-south-1)

The existing `kuro-panel` Vercel project and its Supabase database are **not**
touched.

---

## 1. Supabase (already done for this deployment)

1. Create a free project (or reuse `anoymundo-panel`).
2. Apply [`supabase/schema.sql`](supabase/schema.sql) in the SQL Editor or as a
   migration (`anoymundo_panel_schema`). It creates 12 tables, enables RLS,
   seeds the original `admin / admin123` account, the two original license
   keys, the server settings, and a starter referral code `WELCOME`.
3. Project Settings → API → copy:
   - **Project URL** → `SUPABASE_URL`
   - **service_role** (legacy JWT) or a secret key → `SUPABASE_SERVICE_ROLE_KEY`

> `client_release.content_base64` holds the loader ZIP as base64 text, because
> Vercel's filesystem is read-only and PostgREST bytea/hex would double the
> payload size.

Verification query:

```sql
select
  (select count(*) from users) as users,
  (select count(*) from keys_code) as keys,
  (select count(*) from referral_code) as referrals,
  (select version from client_release where is_current) as release;
```

Expected: `1, 2, 1, 1.0`.

---

## 2. Push this branch

```bash
cd /home/user/AnoyMundo
git add .gitignore README.md DEPLOYMENT.md supabase/schema.sql web
git commit -m "Convert PHP panel to Vercel serverless + Supabase"
git push origin arena/b2fac70f-anoymundo
```

Only `web/` is deployed; the two original ZIPs, `legacy/` and `_extract/` are
local reference material only.

---

## 3. Create the Vercel project

Vercel dashboard → **Add New… → Project** → import
`masterace572-prog/AnoyMundo`:

| Setting | Value |
|---|---|
| Root Directory | `web` |
| Framework Preset | Other |
| Build Command | `npm run build` |
| Output Directory | *(leave empty)* |
| Install Command | `npm install` |
| Production Branch | `arena/b2fac70f-anoymundo` (or `main` after merge) |
| Node.js Version | 20.x or 22.x |

`vercel.json` routes static files first (`public/`) and everything else to the
single function in `api/index.js`.

### Environment variables

| Name | Value |
|---|---|
| `SUPABASE_URL` | `https://bwsqenepvfwhfomdhaig.supabase.co` |
| `SUPABASE_SERVICE_ROLE_KEY` | service_role JWT (server-only) |
| `AUTH_SECRET` | `openssl rand -base64 32` |
| `BRAND_NAME` | `𝙋𝙍𝙄𝙉𝘾𝙀 𝙋𝘼𝙋𝘼 𝙁𝙄𝙇𝙀` (optional) |
| `STATIC_WORDS` | `Vm8Lk7Uj2JmsjCPVPVjrLa7zgfx3uz9E` (must match loader) |
| `PUBLIC_BASE_URL` | `https://<project>.vercel.app` |
| `MAX_LIB_UPLOAD_MB` | `4` |
| `FALLBACK_LIB_VERSION` | `1.0` |

Apply to **Production, Preview and Development**. Never add `NEXT_PUBLIC_` to
the service key.

---

## 4. Deploy & verify

Trigger a deployment, then check these (replace the host):

| Check | Expected |
|---|---|
| `GET /api/health` | `{"ok":true,...}`, `schema: 12/12 expected tables present` |
| `GET /` | original purple/pink PRINCE PAPA FILE login page |
| `POST /login` (`admin` / `admin123`) | 302 → `/dashboard`, sets `am_session` |
| `GET /dashboard` | stats + history, no `<?php` in source |
| `GET /keys`, `/keys/generate`, `/Server`, `/settings` | 200 with original layout |
| `GET /keys/api?draw=1&start=0&length=10` | DataTables JSON with 2 seeded keys |
| `POST /connect` (`game=PUBG&user_key=admin-2-lyK0Y&serial=TEST1`) | `status:true` + `data.token` |
| `GET /api/lib/version` | `1.0` |
| `GET /assets/css/tailwind.css` | compiled CSS (no CDN dependency) |
| `/logout` | clears session, `/dashboard` redirects to `/login` |

Example curl:

```bash
curl -sS -X POST https://<project>.vercel.app/connect \
  -d 'game=PUBG' -d 'user_key=admin-2-lyK0Y' -d 'serial=TESTDEVICE001'
```

Expected `status: true` with `data.real = PUBG-admin-2-lyK0Y-TESTDEVICE001-Vm8Lk7Uj2JmsjCPVPVjrLa7zgfx3uz9E`
and `token = md5(real)`.

---

## 5. Post-deploy tasks

1. **Change the seeded admin password** (`/settings` → Change Password). The
   legacy hash format is preserved so existing users from `main.sql` can log in.
2. **Upload the loader ZIP** at `/lib` (owner only). `GET /api/lib/download`
   then serves it and `/api/lib/version` reports its version. Keep it under
   4 MB: Vercel's function body limit is 4.5 MB. For larger files set an
   external URL instead.
3. Point the Android/XLOX loader's `Version()` and `Link()` endpoints at
   `/api/lib/version` and `/api/lib/download` on this domain (later phase).
4. If a custom domain is added, update `PUBLIC_BASE_URL`.

---

## 6. Local testing & troubleshooting

```bash
cd web
npm install
npm run build     # prebuild + validation
npm test          # 53 end-to-end tests, offline in-memory DB
npm run dev       # live preview on 0.0.0.0:3000 (memory DB by default)
```

To run locally against the real Supabase project, create `web/.env.local` with
the three required variables and start `npm run dev` — it will use Supabase
instead of the memory driver.

| Symptom | Cause / fix |
|---|---|
| `/api/health` `env.ok:false` | `SUPABASE_URL` or `SUPABASE_SERVICE_ROLE_KEY` missing |
| `schema: 5/12` | `supabase/schema.sql` not applied |
| 500 on every page | wrong service key, or RLS blocking (must use service_role) |
| login loops | `AUTH_SECRET` differs between deployments; set it explicitly |
| unstyled pages | `npm run build` didn't run (Tailwind CSS not compiled) |
| `/api/lib/download` 404 | no release uploaded yet — use `/lib` |

Logs: Vercel → Project → Deployments → Function logs. The handler logs every
uncaught error with `[anoymundo] request failed:`.
