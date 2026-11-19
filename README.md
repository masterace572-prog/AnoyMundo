# AnoyMundo — licence panel + Android loader

Two parts, one repository:

| Part | Folder | Runs on |
|---|---|---|
| **Licence panel** (web) | [`web/`](web) | Vercel (Node.js serverless) + Supabase Postgres |
| **Android loaders** (`com.xlox`, `com.kennypvt`) | [`xlox/`](xlox) | Android 5.0+ (arm64-v8a), built by GitHub Actions |

**Live panel:** https://anoymundo-panel.vercel.app

More detail: [REPO_MAP.md](REPO_MAP.md) (where everything lives, for
reviewers), [DEPLOYMENT.md](DEPLOYMENT.md) (setup, environment, database,
verification), [API.md](API.md) (personal API keys, `/api/v1`),
[INTEGRATION.md](INTEGRATION.md) (loader ⇄ panel contract),
[xlox/README.md](xlox/README.md) (Android app),
[xlox/CHANGELOG.md](xlox/CHANGELOG.md) (app versions).

---

## Two Owners, one shared panel

| Owner slot / brand | Fixed channel | Android package |
|---|---|---|
| Existing **Anoy** | `anoy` | `com.xlox` (unchanged; existing signing key) |
| Second **KennyPvt** Owner | `kenny` | `com.kennypvt` (separately installable) |

Anoy creates a **single-use Owner referral** at **Referral codes → Owner —
KennyPvt**. The second person registers normally with that code within 7 days.
Both Owners have unlimited balance and no account expiry. Once the second
slot is filled, neither Owner (nor any Admin) can create a third Owner.

Owner authority is `level=1` plus an immutable, unique `owner_channel`, never a
request parameter or editable username. Neither Owner can edit/delete the
other Owner account, library releases or APK-update releases. Password and
full-name changes still happen in each user's own **Settings**.

**Shared:** one site/database, licence keys, prices, billing/refunds, general
server settings and Admin/Reseller functionality. **Separate:** libraries,
BGMI-supported-version notes, APK update choices and each loader's branding.
Old loaders with no channel parameter keep using **Anoy**.

**Library uploads:** raw `.so` or ZIP, up to **10 MiB** input and expanded
contents. Files upload directly to private Supabase Storage, not as a large
base64 Vercel request. Whatever input filename is used, the main installed
library is **`libbgmi.so`**. See [DEPLOYMENT.md](DEPLOYMENT.md) for migration and
rollout; never re-run the historical destructive fresh-start migration.

## Licence key format

```
<USERNAME>-<DURATION>-<RANDOM>        e.g.  ANOY-7D-GKFHJ
```

- Always **upper case** (input is upper-cased everywhere: panel, API, loader).
- `USERNAME` = the creator's username, letters and digits only.
- `DURATION` = `1H`, `2H`, `5H`, `12H`, `1D`, `3D`, `7D`, `14D`, `30D` or `60D`.
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
`GET /api/health`. All `/api/lib/*` and `/api/app/update` calls accept
`?channel=anoy` or `?channel=kenny`; omitting it means Anoy, not a shared release.
Library downloads can redirect to a short-lived Storage URL; verify the final
ZIP against the manifest hash.

```bash
cd web
npm ci
npm run build      # compiles templates + static checks
npm test           # smoke, channel/upload HTTP and real PostgreSQL (PGlite) checks
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

One GitHub Actions workflow, **Build Anoy and KennyPvt APKs**, builds and
checks **both** signed flavors, uploads separate `Anoy-APK` / `KennyPvt-APK`
artifacts, and attaches `Anoy-XLOX-loader.apk` and `KennyPvt-loader.apk` to one
`loaders-v<versionName>-<versionCode>` release. It never prunes older releases
or overwrites the live panel's library. Each Owner independently publishes
their APK update through **App update → Use latest build**. Without signing
secrets, CI uploads clearly marked unsigned artifacts only.

KennyPvt has its own app strings, launcher/system-splash wordmarks, native
library endpoints and Java fallbacks. JNI class names, licensing protocol,
container/OBB flow, minSdk 21, targetSdk 28 and Anoy signing compatibility are
unchanged. Manual Android UI QA continues targeting the Anoy flavor.

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
supabase/migrations/     historical migrations + non-destructive two-owner upgrade
xlox/                    Android loader (Gradle project)
.github/workflows/       APK build + release, UI QA, update e2e, live smoke test
```

The original source archives, old screenshots and QA logs were removed from
the tree; they remain in git history.
