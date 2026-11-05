# Anoy — Key Management API (`/api/v1`)

**Base URL:** `https://anoymundo-panel.vercel.app/api/v1`
**In-panel reference:** `/api-docs` (login required)
**Scope:** licence keys **only** — create, list, read, update, reset, delete.

> This API can **not** change the mod name, status text, floating text, feature
> flags, maintenance mode, users or any other server setting. For the loader /
> mod integration (`POST /connect`, `modname`, expiry, status, floating text,
> C++ code) see [`INTEGRATION.md`](INTEGRATION.md) and `/docs`.

---

## 1. Get a personal API key

1. Log in to the panel at `https://anoymundo-panel.vercel.app`.
2. Open **API keys** (`/api-keys`) in the sidebar under *Developer*.
3. Enter a name (e.g. `my-telegram-bot`) → **Create API key**.
4. Copy the token immediately. It starts with `amk_` and is shown **once**.
   Only a SHA-256 hash is stored, so it cannot be retrieved later.

You can create one key per project, and **Revoke** or **Delete** it at any time.
A revoked key immediately returns `401 invalid_api_key`.

---

## 2. Authentication

Send the token on **every** request:

```
X-API-Key: amk_YOUR_TOKEN_HERE
Content-Type: application/json        # for POST/PATCH bodies
```

`Authorization: Bearer amk_YOUR_TOKEN_HERE` also works. `api_key` as a query
parameter or body field is accepted for simple clients, but the header is
recommended.

- No session cookie, no CSRF token, no OAuth flow.
- CORS is open (`Access-Control-Allow-Origin: *`, `OPTIONS` preflight returns
  `204`), so a browser site on another domain can call the API directly.
- Every key can **only** see and manage licence keys created by its own
  account (`registrator` / `admin_id`).

---

## 3. Response shape

```json
{ "ok": true,  "keys": [ ... ], "total": 2, "limit": 50, "offset": 0 }
{ "ok": false, "error": { "code": "insufficient_balance", "message": "Insufficient balance" } }
```

Always check `ok` **and** the HTTP status. Errors are always JSON, never an HTML
login page.

### Key object

| Field | Type | Meaning |
|---|---|---|
| `id` | number | internal key id |
| `user_key` | string | the licence key string (use as `{user_key}` in paths) |
| `game` | string | `PUBG` by default |
| `duration` | number | validity in hours (2, 5, 24, 72, 168, 336, 720) |
| `max_devices` | number | device slot limit |
| `status` | number | `1` active, `2` blocked, `3` expired |
| `devices` | string[] | bound serials |
| `device_count` | number | `devices.length` |
| `expired_date` | string \| null | ISO-8601 UTC, set on the key's first `/connect` |
| `created_at`, `updated_at` | string | ISO-8601 UTC |

---

## 4. Endpoints

| Method | Path | Description | Charges saldo |
|---|---|---|---|
| `GET` | `/api/v1` | endpoint index + your API key name | no |
| `GET` | `/api/v1/me` | your account (username, saldo, level) + API key info | no |
| `GET` | `/api/v1/pricing` | games, durations, price per device | no |
| `GET` | `/api/v1/keys` | list your keys | no |
| `POST` | `/api/v1/keys` | **create** one or more keys | **yes** |
| `GET` | `/api/v1/keys/{user_key}` | one key + device list | no |
| `POST` / `PATCH` | `/api/v1/keys/{user_key}` | update `max_devices`, `duration`, `game`, `status`, `devices` | no |
| `POST` | `/api/v1/keys/{user_key}/reset` | clear all bound devices | no |
| `DELETE` | `/api/v1/keys/{user_key}` | delete a key | no |

`OPTIONS` on any path is answered with `204` (CORS preflight).

---

## 5. `GET /api/v1/me`

```bash
curl -sS https://anoymundo-panel.vercel.app/api/v1/me \
  -H 'X-API-Key: amk_YOUR_TOKEN_HERE'
```

```json
{
  "ok": true,
  "account": { "username": "admin", "fullname": "admin", "level": 1, "saldo": 2147384644, "currency": "INR", "status": 1 },
  "api_key": { "id": 7, "name": "my-telegram-bot", "prefix": "amk_Xy12AbCd", "last4": "pQ9z", "status": 1,
               "created_at": "2026-11-05T10:00:00.000Z", "last_used_at": "2026-11-05T10:30:00.000Z" }
}
```

Call this first in your site to display the current balance before a create.

---

## 6. `GET /api/v1/pricing`

```json
{
  "ok": true,
  "currency": "INR",
  "rule": "price = price_per_device_for_duration × max_devices",
  "games": ["PUBG"],
  "durations": [
    { "duration": 2,   "hours": 2,   "label": "2 Hours — ₹10/Device",  "price_per_device": 10 },
    { "duration": 5,   "hours": 5,   "label": "5 Hours — ₹20/Device",  "price_per_device": 20 },
    { "duration": 24,  "hours": 24,  "label": "1 Days — ₹80/Device",   "price_per_device": 80 },
    { "duration": 72,  "hours": 72,  "label": "3 Days — ₹150/Device",  "price_per_device": 150 },
    { "duration": 168, "hours": 168, "label": "7 Days — ₹250/Device",  "price_per_device": 250 },
    { "duration": 336, "hours": 336, "label": "14 Days — ₹350/Device", "price_per_device": 350 },
    { "duration": 720, "hours": 720, "label": "30 Days — ₹500/Device", "price_per_device": 500 }
  ]
}
```

---

## 7. `GET /api/v1/keys` — list

Query parameters (all optional):

| Param | Default | Notes |
|---|---|---|
| `limit` | `50` | 1–200 |
| `offset` | `0` | pagination offset |
| `game` | – | exact match |
| `status` | – | `1`, `2` or `3` |

```bash
curl -sS 'https://anoymundo-panel.vercel.app/api/v1/keys?limit=20&offset=0&game=PUBG' \
  -H 'X-API-Key: amk_YOUR_TOKEN_HERE'
```

```json
{ "ok": true, "keys": [ { "user_key": "admin-2-lyK0Y", "game": "PUBG", "duration": 2,
  "max_devices": 1, "status": 1, "devices": [], "device_count": 0,
  "expired_date": null, "created_at": "...", "updated_at": "..." } ],
  "total": 1, "limit": 20, "offset": 0 }
```

---

## 8. `POST /api/v1/keys` — create (charges saldo)

### Body (JSON or form-encoded)

| Field | Required | Rules |
|---|---|---|
| `game` | no | default `PUBG`; must be one of `/api/v1/pricing` → `games` |
| `duration` | **yes** | one of `2, 5, 24, 72, 168, 336, 720` (hours) |
| `max_devices` | **yes** | integer 1–100 |
| `count` | no | 1–100, default `1`. Bulk generation is charged once per request (legacy panel behaviour). |
| `custom_key` | no | 4–19 chars, `[A-Za-z0-9_-]+`, only when `count = 1` |

```bash
curl -sS -X POST https://anoymundo-panel.vercel.app/api/v1/keys \
  -H 'X-API-Key: amk_YOUR_TOKEN_HERE' \
  -H 'Content-Type: application/json' \
  -d '{"game":"PUBG","duration":24,"max_devices":1}'
```

```json
{
  "ok": true,
  "keys": [ { "id": 3, "user_key": "admin-24-aB3dE", "game": "PUBG", "duration": 24,
              "max_devices": 1, "status": 1, "devices": [], "device_count": 0,
              "expired_date": null, "created_at": "2026-11-05T10:30:00.000Z",
              "updated_at": "2026-11-05T10:30:00.000Z" } ],
  "count": 1,
  "price": 80, "currency": "INR",
  "saldo_before": 2147384644,
  "saldo_after": 2147384564,
  "charged": 80
}
```

HTTP `201 Created`. Generated keys look like `<username>-<duration>-<5 chars>`.
`expired_date` is `null` until the first `/connect` from the loader.

### Saldo / charging rules

```
price            = price_per_device(duration) × max_devices   (one fee per request)
new_saldo        = current_saldo − price
```

This is exactly the panel's original pricing rule: a generation **request**
costs `price × max_devices`, and `count` simply creates that many keys for the
one fee (legacy bulk generation). It is shared by the panel and `/api/v1`, so
the price shown in the panel and the debit from the API can never differ.

- The balance is read fresh, then debited with an **optimistic conditional
  update** (`WHERE saldo = previously_read_value`) so two concurrent requests
  cannot spend the same balance twice. A conflict returns `409
  balance_conflict` — just retry.
- If inserting a key fails, the debit is refunded automatically.
- There is **no refund** when you later delete a key.
- Updates, resets, deletes and listing are free.
- Insufficient balance → `402 insufficient_balance`; nothing is created and
  the balance is unchanged.

---

## 9. `GET /api/v1/keys/{user_key}` — one key

```bash
curl -sS https://anoymundo-panel.vercel.app/api/v1/keys/admin-24-aB3dE \
  -H 'X-API-Key: amk_YOUR_TOKEN_HERE'
```

```json
{ "ok": true, "key": { "user_key": "admin-24-aB3dE", "devices": ["DEVICE123"], "device_count": 1, "...": "..." } }
```

---

## 10. `POST` / `PATCH /api/v1/keys/{user_key}` — manage

Send only the fields you want to change. At least one is required.

| Field | Rules |
|---|---|
| `max_devices` | integer 1–100 |
| `duration` | one of the pricing durations |
| `game` | supported game |
| `status` | `1` active, `2` blocked, `3` expired |
| `devices` | comma-separated string or array; sanitised to `[A-Za-z0-9_-]+`, capped at `max_devices` |

```bash
curl -sS -X PATCH https://anoymundo-panel.vercel.app/api/v1/keys/admin-24-aB3dE \
  -H 'X-API-Key: amk_YOUR_TOKEN_HERE' \
  -H 'Content-Type: application/json' \
  -d '{"max_devices":3,"status":1}'
```

```json
{ "ok": true, "key": { "user_key": "admin-24-aB3dE", "max_devices": 3, "status": 1, "...": "..." },
  "updated": ["max_devices", "status"] }
```

`POST` is accepted as an alias for `PATCH` so clients that cannot send PATCH
still work.

---

## 11. `POST /api/v1/keys/{user_key}/reset` — clear devices

```bash
curl -sS -X POST https://anoymundo-panel.vercel.app/api/v1/keys/admin-24-aB3dE/reset \
  -H 'X-API-Key: amk_YOUR_TOKEN_HERE'
```

```json
{ "ok": true, "reset": true, "user_key": "admin-24-aB3dE", "devices_cleared": 1,
  "key": { "devices": [], "device_count": 0, "...": "..." } }
```

---

## 12. `DELETE /api/v1/keys/{user_key}`

```bash
curl -sS -X DELETE https://anoymundo-panel.vercel.app/api/v1/keys/admin-24-aB3dE \
  -H 'X-API-Key: amk_YOUR_TOKEN_HERE'
```

```json
{ "ok": true, "deleted": true, "user_key": "admin-24-aB3dE" }
```

---

## 13. JavaScript example

```js
const BASE = "https://anoymundo-panel.vercel.app/api/v1";
const TOKEN = "amk_YOUR_TOKEN_HERE";

async function api(path, options = {}) {
  const r = await fetch(BASE + path, {
    ...options,
    headers: { "X-API-Key": TOKEN, "Content-Type": "application/json", ...options.headers },
  });
  const data = await r.json();
  if (!data.ok) throw new Error(`${data.error.code}: ${data.error.message}`);
  return data;
}

const me = await api("/me");                 // show saldo
const made = await api("/keys", {
  method: "POST",
  body: JSON.stringify({ game: "PUBG", duration: 24, max_devices: 1 }),
});
console.log(made.keys[0].user_key, "charged", made.price, "remaining", made.saldo_after);
const list = await api("/keys?limit=20");
```

---

## 14. Error codes

| HTTP | `error.code` | Meaning |
|---|---|---|
| 401 | `missing_api_key` | no `X-API-Key` header supplied |
| 401 | `invalid_api_key` | unknown or revoked key |
| 403 | `account_inactive` | the key owner is blocked |
| 403 | `account_expired` | the key owner's account expired |
| 400 | `invalid_request` | bad parameters, or no updatable field sent |
| 402 | `insufficient_balance` | not enough saldo |
| 404 | `key_not_found` | key does not exist or is not owned by you |
| 404 | `not_found` | unknown path |
| 405 | `method_not_allowed` | wrong HTTP verb |
| 409 | `key_exists` | `custom_key` already taken |
| 409 | `balance_conflict` | concurrent balance change — retry |
| 500 | `internal_error` | server-side failure |

A `404 key_not_found` is returned for both "missing" and "not yours", so keys
cannot be enumerated with an API key.

---

## 15. Security notes

- Keep the token in a **backend** (bot server, serverless function, server-side
  route). Do not ship it in public frontend JavaScript or a mobile APK.
- Use one key per project so revoking one does not break the others.
- Always use HTTPS. Rotate by creating a new key, switching clients, then
  deleting the old one.
- API keys are hashed with SHA-256 at rest; RLS is enabled on the table with no
  public policies, so only the server-side service role can read it.
- Scope is enforced in code: no `/api/v1` route touches `modname`, `_ftext`,
  `feature`, `onoff`, `users` or `client_release`.

---

**Related:** [`API_AGENT_PROMPT.md`](API_AGENT_PROMPT.md) (copy-paste prompt for
building a site), [`INTEGRATION.md`](INTEGRATION.md) (loader / C++ docs),
[`DEPLOYMENT.md`](DEPLOYMENT.md) (infrastructure).
