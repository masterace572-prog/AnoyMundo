# Copy-paste prompt: build a licence-key management site with the Anoy API

Copy everything between the two `-----` lines and paste it into another AI agent.
Replace `<MY_API_KEY>` with a token created at
`https://anoymundo-panel.vercel.app/api-keys` (log in first, sidebar →
**Developer → API keys** → Create API key → copy the `amk_…` token once).

-----

Build me a small, production-quality web app (frontend + a thin backend proxy)
that manages **licence keys** through the Anoy Key Management API. Use any stack
you like (Node/Express + plain HTML/JS, Next.js, or Python/FastAPI). Do NOT
invent endpoints — use exactly the ones below.

## API basics

- Base URL: `https://anoymundo-panel.vercel.app/api/v1`
- Authentication: send the header `X-API-Key: <MY_API_KEY>` on EVERY request.
  `Authorization: Bearer <MY_API_KEY>` also works (replace `<MY_API_KEY>` with
  your real `amk_…` token).
- Content type for POST/PATCH bodies: `application/json` (form-encoded also
  accepted).
- CORS is open; `OPTIONS` preflight returns `204`.
- No cookies, no session, no CSRF token, no OAuth.
- Every response is JSON: success `{ "ok": true, ... }`, failure
  `{ "ok": false, "error": { "code": "...", "message": "..." } }`.
  Always check BOTH `ok` and the HTTP status.
- Keep `<MY_API_KEY>` in a server-side environment variable and proxy all calls
  through my backend. NEVER put the key in browser JavaScript, HTML, or a
  public repo. Put it in `.env` and add `.env` to `.gitignore`.

## Endpoints (all require the X-API-Key header)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1` | endpoint index + API key name |
| GET | `/api/v1/me` | account (username, level, saldo) + api_key info |
| GET | `/api/v1/pricing` | games + durations with `price_per_device` |
| GET | `/api/v1/keys` | list my keys |
| POST | `/api/v1/keys` | CREATE keys (charges saldo) |
| GET | `/api/v1/keys/{user_key}` | one key |
| POST or PATCH | `/api/v1/keys/{user_key}` | update key |
| POST | `/api/v1/keys/{user_key}/reset` | clear bound devices |
| DELETE | `/api/v1/keys/{user_key}` | delete key |

### GET /api/v1/me
```json
{ "ok": true,
  "account": { "username": "admin", "fullname": "admin", "level": 1, "saldo": 2147384644, "currency": "INR", "status": 1 },
  "api_key": { "id": 7, "name": "my-site", "prefix": "amk_Xy12AbCd", "last4": "pQ9z", "status": 1, "created_at": "...", "last_used_at": "..." } }
```

### GET /api/v1/pricing
```json
{ "ok": true, "currency": "INR", "rule": "price = price_per_device_for_duration × max_devices",
  "games": ["PUBG"],
  "durations": [
    { "duration": 2, "hours": 2, "label": "2 Hours — ₹10/Device", "price_per_device": 10 },
    { "duration": 5, "hours": 5, "label": "5 Hours — ₹20/Device", "price_per_device": 20 },
    { "duration": 24, "hours": 24, "label": "1 Days — ₹80/Device", "price_per_device": 80 },
    { "duration": 72, "hours": 72, "label": "3 Days — ₹150/Device", "price_per_device": 150 },
    { "duration": 168, "hours": 168, "label": "7 Days — ₹250/Device", "price_per_device": 250 },
    { "duration": 336, "hours": 336, "label": "14 Days — ₹350/Device", "price_per_device": 350 },
    { "duration": 720, "hours": 720, "label": "30 Days — ₹500/Device", "price_per_device": 500 }
  ] }
```
Use this to populate the duration dropdown and to compute the price preview.

### GET /api/v1/keys
Optional query params: `limit` (1–200, default 50), `offset` (default 0),
`game`, `status` (`1` active, `2` blocked, `3` expired).
```json
{ "ok": true, "keys": [ { "id": 1, "user_key": "admin-2-lyK0Y", "game": "PUBG",
  "duration": 2, "max_devices": 1, "status": 1, "devices": [], "device_count": 0,
  "expired_date": null, "created_at": "...", "updated_at": "..." } ],
  "total": 1, "limit": 50, "offset": 0 }
```

### POST /api/v1/keys — CREATE (this is the main feature)
Body (JSON):
- `game`: optional, default `"PUBG"`
- `duration`: REQUIRED, one of `2, 5, 24, 72, 168, 336, 720` (hours)
- `max_devices`: REQUIRED, integer 1–100
- `count`: optional, 1–100, default `1`
- `custom_key`: optional, 4–19 chars `[A-Za-z0-9_-]+`, only when `count` is 1

Example request:
```
POST /api/v1/keys
X-API-Key: <MY_API_KEY>
Content-Type: application/json

{"game":"PUBG","duration":24,"max_devices":1}
```
Response `201`:
```json
{ "ok": true, "keys": [ { "id": 3, "user_key": "admin-24-aB3dE", "game": "PUBG",
  "duration": 24, "max_devices": 1, "status": 1, "devices": [], "device_count": 0,
  "expired_date": null, "created_at": "...", "updated_at": "..." } ],
  "count": 1, "price": 80, "currency": "INR",
  "saldo_before": 2147384644, "saldo_after": 2147384564, "charged": 80 }
```

**Saldo rules:** `price = price_per_device(duration) × max_devices`, charged
ONCE per `POST /keys` request (the panel's original bulk-generation pricing —
`count` creates that many keys for the same single fee; it does NOT multiply
the price). The balance is debited atomically before keys are written; on a
write failure it is refunded. `402 insufficient_balance` means nothing was created and the
balance did not change. Deleting a key does NOT refund. List/read/update/reset
are free. Generated keys look like `<username>-<duration>-<5 chars>` and
`expired_date` stays `null` until the loader's first `/connect`.

### GET /api/v1/keys/{user_key}
`{ "ok": true, "key": { ...same key object..., "devices": ["DEVICE123"], "device_count": 1 } }`

### POST or PATCH /api/v1/keys/{user_key} — manage
Send only fields to change (at least one): `max_devices` (1–100), `duration`
(pricing durations), `game`, `status` (1/2/3), `devices` (comma-separated string
or array, sanitised to `[A-Za-z0-9_-]+`, capped at `max_devices`).
```json
{ "ok": true, "key": { "...": "..." }, "updated": ["max_devices", "status"] }
```

### POST /api/v1/keys/{user_key}/reset — clear devices
```json
{ "ok": true, "reset": true, "user_key": "admin-24-aB3dE", "devices_cleared": 1,
  "key": { "devices": [], "device_count": 0, "...": "..." } }
```

### DELETE /api/v1/keys/{user_key}
```json
{ "ok": true, "deleted": true, "user_key": "admin-24-aB3dE" }
```

### Error codes
`401 missing_api_key`, `401 invalid_api_key`, `403 account_inactive`,
`403 account_expired`, `400 invalid_request`, `402 insufficient_balance`,
`404 key_not_found`, `404 not_found`, `405 method_not_allowed`,
`409 key_exists`, `409 balance_conflict`, `500 internal_error`.
Show `error.code` + `error.message` in the UI. On `409 balance_conflict`,
retry once. A `404 key_not_found` also means "not your key".

## What to build

1. A dashboard showing current **saldo** (from `/me`) and the API key name.
2. A **Create key** form: game (from `/pricing`), duration (from `/pricing`),
   `max_devices`, `count`, optional `custom_key`, with a live **price preview**
   computed as `price_per_device × max_devices` (one fee per request, not per
   key). Confirm before submit, then show the returned `user_key` values with a
   copy button plus the charged
   amount and new balance.
3. A **keys table** from `/keys`: user_key, game, duration, max_devices, status
   badge, device_count, expired_date, created_at. Support pagination
   (`limit`/`offset`), filtering by game/status and refresh.
4. Row actions: **view**, **edit** (max_devices / duration / status / devices),
   **reset devices** (with confirmation), **delete** (with confirmation).
5. Proper error/empty/loading states, responsive mobile layout, no secrets in
   the browser, and no gradient-heavy styling (clean flat UI).
6. A short README with setup: `npm install`, put
   `ANOY_API_KEY=<MY_API_KEY>` in `.env`, `npm run dev`, and note that
   the key comes from `https://anoymundo-panel.vercel.app/api-keys`.

## Hard constraints

- ONLY use the `/api/v1/*` endpoints above. Do NOT call `/connect`,
  `/Server`, `/settings`, `/admin/*`, or anything that changes mod name,
  status text, floating text, feature flags or maintenance mode. This project
  is for **key management only**.
- Do not hardcode the API key in source; read it from an env var server-side.
- Do not fabricate response fields; handle `ok:false` responses generically.
- Verify with at least one real request: create a key with
  `{"game":"PUBG","duration":2,"max_devices":1}` (costs ₹10), confirm saldo
  dropped by exactly 10, list it, then delete it. Tell me the created
  `user_key` and the before/after saldo so I can confirm it worked.

-----
