# AnoyMundo — licence panel + Android loader

Two parts, one repository:

| Part | Folder | Runs on |
|---|---|---|
| **Licence panel** (web) | [`web/`](web) | Vercel (Node.js serverless) + Supabase Postgres |
| **Android loader** (`com.xlox`) | [`xlox/`](xlox) | Android 5.0+ (arm64-v8a), built by GitHub Actions |

**Live panel:** https://anoymundo-panel.vercel.app

More detail: [REPO_MAP.md](REPO_MAP.md) (where everything lives, for
reviewers), [DEPLOYMENT.md](DEPLOYMENT.md) (setup, environment, database,
verification), [API.md](API.md) (personal API keys, `/api/v1`),
[INTEGRATION.md](INTEGRATION.md) (loader ⇄ panel contract),
[xlox/README.md](xlox/README.md) (Android app),
[xlox/CHANGELOG.md](xlox/CHANGELOG.md) (app versions).

---

## Owner account

The database starts fresh with exactly one user:

| Username | Level | Balance | Email |
|---|---|---|---|
| `Anoy` | 1 — Owner | Unlimited (never charged) | saabarjun705@gmail.com |

Owner-only routes are bound to level 1 **and** the `OWNER_USERNAME`
(default `Anoy`). The password is not stored in this repository; change it from
**Settings** after the first sign-in. Levels: 1 Owner, 2 Admin, 3 Reseller.
New resellers register with a referral code that the Owner creates under
**Referral codes** (no default code exists).

## Licence key format

```
<USERNAME>-<DURATION>-<RANDOM>        e.g.  ANOY-7D-GKFHJ
```

- Always **upper case** (input is upper-cased everywhere: panel, API, loader).
- `USERNAME` = the creator's username, letters and digits only.
- `DURATION` = `2H`, `5H`, `1D`, `3D`, `7D`, `14D` or `30D`.
- `RANDOM` = 5 random characters from `A-Z0-9` (cryptographically random; 6 if
  a collision is ever hit). A **custom** ending replaces it: 4–6 characters
  `A-Z0-9`, exactly one key per request. Pasting a whole key (`ANOY-1D-VIP01`)
  into the custom field is accepted — the prefix is stripped.
- Keys are created and charged in **one database transaction**
  (`create_keys_atomic`) with an idempotency id, so double clicks or timeouts
  never create duplicates or charge without creating keys.

Prices per device (INR): 2 h ₹10 · 5 h ₹20 · 1 day ₹80 · 3 days ₹150 ·
7 days ₹250 · 14 days ₹350 · 30 days ₹500. Total = price × devices × number of
keys. Deleting a never-used key refunds what it cost.

## Panel (web)

- Server-rendered Handlebars views, no frameworks or CDNs. One stylesheet:
  `web/public/assets/css/ui.css` — black background, white text, blue rounded
  buttons, iOS-style grouped lists. Text never has its own background.
  `web/public/assets/js/app-ui.js` adds busy buttons, confirmations, toasts,
  copy buttons, the live key preview and the library upload progress.
- Every error is handled: database outages show a clear message (nothing is
  charged), forms keep their input and show field errors, JSON endpoints
  return `{ ok:false, error }`, and unknown pages render a styled 404.
- Security: signed HTTP-only session cookie, bcrypt passwords, same-origin
  check on every browser POST, strict CSP, rate-limited sign-in.

Routes: `/login`, `/register`, `/dashboard`, `/keys` (search, filters, paging,
edit, reset devices, delete), `/keys/generate`, `/settings`, `/server`
(maintenance, mod status, feature flags), `/admin/manage-users`,
`/admin/create-referral`, `/lib` (loader library release), `/app-update`
(Android app (APK) update), `/api-keys`, `/api-docs`, `/docs`.

Loader endpoints: `POST /connect` (licence check), `GET /api/lib/manifest`
(`{version, available, size, sha256}`), `GET /api/lib/version` (plain text),
`GET /api/lib/download` (ZIP with `X-Release-Version` / `X-Content-SHA256`),
`GET /api/app/update?code=<versionCode>` (current app update, if any),
`GET /api/health`.

```bash
cd web
npm ci
npm run build      # compiles templates + static checks
npm test           # end-to-end checks against an in-memory database
npm run dev        # http://localhost:3000 (memory DB unless Supabase env is set)
```

`node scripts/live-smoke.mjs` runs the same kind of checks against the live
deployment (see the **Live deployment smoke test** workflow).

## Android loader

Splash (app update check) → sign in (library update gate, then licence key)
→ home (Launch / Stop, Settings).

- **App (APK) update:** the splash asks `GET /api/app/update`. A newer
  version code opens `AppUpdateActivity`: download over https, check that the
  APK is this package with a higher version code, then hand it to Android's
  PackageInstaller. Mandatory updates cannot be skipped. "No update" never
  blocks the splash.
- **Library update gate:** before sign-in the app asks `GET /api/lib/manifest`.
  If a newer library exists (or none is installed) the only option is
  **Update now**; sign-in stays blocked until the download is verified
  (size + SHA-256) and installed.
- **Sign in:** takes the licence key; the native code calls `POST /connect`.
- **Home:** licence expiry and countdown (IST), game and library state, one
  primary action (Launch / Stop / Install game / Sign in again), Settings.

Builds run on GitHub Actions: `build-xlox.yml` (every push to `xlox/**`) builds,
lints and checks the signed APK and publishes it as the GitHub release
`anoy-v<versionName>-<versionCode>`; `android-ui-qa.yml` (manual) captures
emulator screenshots as a workflow artifact.

### Icons

Both the panel and the app use [Lucide](https://lucide.dev) icons (ISC licence,
`web/public/assets/LUCIDE-LICENSE.txt`). They are generated once and committed,
so neither build needs extra tooling:

- Panel: `node web/scripts/build-icons.mjs` → `web/src/icons.generated.mjs`
  (runs in `prebuild`; use `{{icon "name"}}` in views).
- App: `xlox/app/src/main/res/drawable/ic_*.xml` (VectorDrawables, committed;
  the one-off generator script was removed and is in git history).

`web-ui-qa.yml` runs the panel tests and uploads desktop and phone screenshots
of every panel page as a workflow artifact.

## Repository layout

```
web/                     panel (Vercel root directory)
  api/index.js           single serverless entry
  src/                   app, auth, licensing, key service, db drivers
  views/                 Handlebars templates
  public/assets/         ui.css, app-ui.js, fonts, favicon.svg
  scripts/               build, checks, smoke tests, dev server
supabase/schema.sql      full schema (fresh projects)
supabase/migrations/     applied migrations (v2 fresh start, app_release)
xlox/                    Android loader (Gradle project)
.github/workflows/       APK build + release, UI QA, update e2e, live smoke test
```

The original source archives, old screenshots and QA logs were removed from
the tree; they remain in git history.
