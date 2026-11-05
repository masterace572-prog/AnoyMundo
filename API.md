# Anoy — API docs (simple reference)

**Base URL:** `https://anoymundo-panel.vercel.app`
**Also available inside the panel:** `/docs` (login required).

Everything the Android/loader client needs comes from **one** call: `POST /connect`.
The rest of this page explains every field, the error text, and how to consume it
from C++.

---

## 1. Endpoints at a glance

| Method | Path | What it returns |
|---|---|---|
| `POST` | `/connect` | licence check → the full mod payload (main endpoint) |
| `GET` | `/connect?game=…&user_key=…&serial=…` | same as POST, for loaders that use GET |
| `GET` | `/connect` (no params) | `web_info` / `web__dev` panel information |
| `GET` | `/api/health` | `ok`, Supabase connection, lib version, `12/12` schema check |
| `GET` | `/api/lib/version` | plain text version, e.g. `1.0\n` (read by the loader) |
| `GET` | `/api/lib/download` | the current loader ZIP (or 302 to an external URL) |
| `GET` | `/api/lib/info` | JSON list of uploaded releases |

`POST /connect` is also reachable as `/api/connect`.
Legacy aliases still work: `/version.txt`, `/api/version`, `/lib/version.txt`,
`/xlox.zip`, `/lib.zip`, `/lib.so`, `/Onlinelib/lib.so`, `/download/lib.so`.

---

## 2. Connect URL and request

```
POST https://anoymundo-panel.vercel.app/connect
Content-Type: application/x-www-form-urlencoded

game=PUBG&user_key=admin-2-lyK0Y&serial=DEVICE123
```

```bash
curl -sS -X POST https://anoymundo-panel.vercel.app/connect \
  -d 'game=PUBG' \
  -d 'user_key=admin-2-lyK0Y' \
  -d 'serial=DEVICE123'
```

| Parameter | Required | Rules |
|---|---|---|
| `game` | yes | `[A-Za-z0-9_-]+`, must match the game the key was generated for (default `PUBG`) |
| `user_key` | yes | 1–36 characters, the licence key shown in `/keys` |
| `serial` | yes | `[A-Za-z0-9_-]+`, a per-device ID (Android ID, IMEI hash, etc.). Keep it stable — it is stored against the key |

`GET` alternative (same validation, same JSON):

```
https://anoymundo-panel.vercel.app/connect?game=PUBG&user_key=admin-2-lyK0Y&serial=DEVICE123
```

---

## 3. Success response

```json
{
  "status": true,
  "data": {
    "real": "PUBG-admin-2-lyK0Y-DEVICE123-Vm8Lk7Uj2JmsjCPVPVjrLa7zgfx3uz9E",
    "token": "1aa36c890b3155533de4ed2e09714704",
    "modname": "VIP MOD",
    "mod_status": "Safe",
    "credit": "MOD STATUS :- 100% SAFE",
    "ESP": "on",
    "Item": "on",
    "AIM": "on",
    "SilentAim": "on",
    "BulletTrack": "on",
    "Floating": "on",
    "Memory": "on",
    "Setting": "on",
    "expired_date": "2026-11-05T11:00:20.704Z",
    "EXP": "2026-11-05T11:00:20.704Z",
    "exdate": "2026-11-05T11:00:20.704Z",
    "device": 1,
    "rng": 1762333220
  }
}
```

### Field reference

| Field | Type | Meaning / how to use it |
|---|---|---|
| `status` | bool | `true` = authorised. **Always check this first.** When `false`, read `reason`. |
| `reason` | string | Only present on failure, or on a maintenance response. See §5. |
| `data.real` | string | `${game}-${user_key}-${serial}-${STATIC_WORDS}`. Informational; the client normally does not build it itself. |
| `data.token` | string | `md5(real)`. Use it to verify the reply was not altered in transit. |
| `data.modname` | string | Mod name shown in your menu / title. Set at `/Server → Change Mod Name`. |
| `data.mod_status` | string | **Status text**: `Safe` or `Anti-Cheat is High..!!`. Show it as a badge; when it is not `Safe`, you may want to disable risky features. Set at `/Server → Floating Text`. |
| `data.credit` | string | **Floating text**: free-form line drawn over your mod menu, e.g. `MOD STATUS :- 100% SAFE`. Set at `/Server → Floating Text`. |
| `data.ESP` | `"on"` / `"off"` | ESP feature gate |
| `data.Item` | `"on"` / `"off"` | Item feature gate |
| `data.AIM` | `"on"` / `"off"` | AIM feature gate |
| `data.SilentAim` | `"on"` / `"off"` | Silent aim feature gate |
| `data.BulletTrack` | `"on"` / `"off"` | Bullet track feature gate |
| `data.Floating` | `"on"` / `"off"` | Floating window feature gate |
| `data.Memory` | `"on"` / `"off"` | Memory feature gate |
| `data.Setting` | `"on"` / `"off"` | In-mod settings feature gate |
| `data.expired_date` | ISO-8601 UTC | Key expiry. Compare with the current UTC time before enabling features. |
| `data.EXP` | ISO-8601 UTC | Same value, kept for legacy loaders |
| `data.exdate` | ISO-8601 UTC | Same value, kept for legacy loaders |
| `data.device` | number | Maximum number of devices allowed for this key |
| `data.rng` | number | Unix timestamp (seconds) at which the server built the response — useful for anti-replay checks |

The eight `ESP … Setting` flags are toggled at
`/Server → Mod Features` (Owner only). They are strings, **not** booleans:
compare with `"on"`.

---

## 4. Expiry and devices

- A brand-new key has no `expired_date`. The **first successful** `/connect`
  sets it to `now + duration hours` (`duration` is chosen when the key is
  generated: 2, 5, 24, 72, 168, 336 or 720 hours).
- Once set, the expiry never moves. `/connect` after that time returns
  `{"status": false, "reason": "EXPIRED KEY"}`.
- `serial` is added to the key's device list on first use. Re-using the same
  serial is always allowed. A **new** serial on a key that already has
  `device` entries is rejected with `MAX DEVICE REACHED`.
- Reset devices with `/keys/reset?userkey=…` in the panel (session required).
- The panel exposes the same expiry in `expired_date`, `EXP` and `exdate` so
  old and new loaders both work.

---

## 5. Error / status text

Every rejection is HTTP 200 with `status:false`. Handle the `reason` text:

| `reason` | Cause |
|---|---|
| `INVALID PARAMETER` | one of `game`, `user_key`, `serial` is missing or empty |
| `Bad Parameter` | illegal characters in `game`/`serial`, or `user_key` longer than 36 chars |
| `USER OR GAME NOT REGISTERED` | key does not exist, or `game` does not match the key |
| `USER BLOCKED` | key status is not active (`1`) |
| `EXPIRED KEY` | `expired_date` is in the past |
| `MAX DEVICE REACHED` | new serial and the device limit is full |
| maintenance message | **special case:** `status:true` but **no `data` object**. Maintenance is on and `reason` holds the offline message from `/Server → Offline Message`. Treat it as "not authorised" and display the message. |

```json
{"status": false, "reason": "USER OR GAME NOT REGISTERED"}
{"status": true, "reason": "Server is Under Maintenance"}
```

---

## 6. Token check

```
real  = game + "-" + user_key + "-" + serial + "-" + STATIC_WORDS
token = md5(real)
```

`STATIC_WORDS` is the `STATIC_WORDS` environment variable on the server
(must match the loader's licence salt). Recompute `md5(real)` client-side and
compare with `data.token` if you want to detect a tampered response. Keep the
salt out of any UI/log output.

`GET /connect` with no parameters returns panel information instead:

```json
{
  "web_info": {"_client": "Anoy", "license": "Qp5KSGTquetnUkjX6UVBAURH8hTkZuLM", "version": "1.0.0"},
  "web__dev": {"author": "@libAkAudioVisiual", "telegram": "https://t.me/libAkAudioVisiual"}
}
```

---

## 7. Using it in C++

Minimal libcurl POST (no JSON library needed to see the raw reply):

```cpp
// g++ connect.cpp -o connect -lcurl
#include <curl/curl.h>
#include <iostream>
#include <string>

static size_t onData(char* p, size_t size, size_t nmemb, void* out) {
    static_cast<std::string*>(out)->append(p, size * nmemb);
    return size * nmemb;
}

std::string post(const std::string& url, const std::string& body) {
    CURL* c = curl_easy_init();
    std::string out;
    curl_easy_setopt(c, CURLOPT_URL, url.c_str());
    curl_easy_setopt(c, CURLOPT_POSTFIELDS, body.c_str());
    curl_easy_setopt(c, CURLOPT_WRITEFUNCTION, onData);
    curl_easy_setopt(c, CURLOPT_WRITEDATA, &out);
    curl_easy_setopt(c, CURLOPT_TIMEOUT, 15L);
    CURLcode rc = curl_easy_perform(c);
    if (rc != CURLE_OK) std::cerr << curl_easy_strerror(rc) << std::endl;
    curl_easy_cleanup(c);
    return out;
}

int main() {
    std::string json = post("https://anoymundo-panel.vercel.app/connect",
                            "game=PUBG&user_key=admin-2-lyK0Y&serial=DEVICE123");
    std::cout << json << std::endl;
}
```

Parsing and using the fields (with [nlohmann/json](https://github.com/nlohmann/json)):

```cpp
#include <nlohmann/json.hpp>
#include <string>

void apply(const std::string& json) {
    auto r = nlohmann::json::parse(json, nullptr, false);
    if (r.is_discarded()) return;                 // bad JSON / network error

    if (!r.value("status", false)) {
        std::string reason = r.value("reason", "UNKNOWN ERROR");
        ShowMessage(reason);                      // EXPIRED KEY, USER BLOCKED, ...
        return;
    }
    if (!r.contains("data")) {                    // maintenance mode
        ShowMessage(r.value("reason", "Server is Under Maintenance"));
        return;
    }

    const auto& d = r["data"];
    std::string modName  = d.value("modname", "");        // "VIP MOD"
    std::string status   = d.value("mod_status", "");     // "Safe" / "Anti-Cheat is High..!!"
    std::string floating = d.value("credit", "");         // floating text on the menu
    std::string expiry   = d.value("expired_date", "");   // ISO-8601 UTC
    int maxDevices       = d.value("device", 1);
    long long rng        = d.value("rng", 0LL);
    std::string token    = d.value("token", "");

    bool esp       = d.value("ESP", "off") == "on";
    bool aim       = d.value("AIM", "off") == "on";
    bool silentAim = d.value("SilentAim", "off") == "on";
    bool bullet    = d.value("BulletTrack", "off") == "on";
    bool item      = d.value("Item", "off") == "on";
    bool floatingW = d.value("Floating", "off") == "on";
    bool memory    = d.value("Memory", "off") == "on";
    bool setting   = d.value("Setting", "off") == "on";

    SetMenuTitle(modName);
    DrawFloatingText(floating);
    DrawStatusBadge(status);
    EnableFeatures(esp, aim, silentAim, bullet, item, floatingW, memory, setting);
    // verify: token == md5(game + "-" + key + "-" + serial + "-" + STATIC_WORDS)
    // and compare expiry with the current UTC time before starting the mod.
}
```

`ShowMessage`, `DrawFloatingText`, `EnableFeatures`, etc. are placeholders for
your own UI/hooks — the API itself is pure JSON over HTTPS.

**JNI / Android note:** the Java loader reads the version from
`GET /api/lib/version` (first line of plain text) and downloads the loader ZIP
from `GET /api/lib/download`. Call `/connect` from native code after your HTTP
stack is up, cache `expired_date`, and re-check on each launch.

---

## 8. Where admins change these values

| What | Where in the panel |
|---|---|
| `modname` | `/Server → Change Mod Name` |
| `mod_status` + `credit` (status text / floating text) | `/Server → Floating Text` |
| `ESP … Setting` feature flags | `/Server → Mod Features` (Owner) |
| Maintenance `status:true` + `reason` | `/Server → Server Maintenance → Offline Message` |
| Key `duration`, `device` limit, `status` | `/keys`, `/keys/generate`, `/keys/edit` |
| `STATIC_WORDS` | Vercel project env var (server-side only) |
| Loader `version` / ZIP | `/lib` |

---

## 9. Other JSON endpoints (session required)

These power the panel's DataTables and are not part of the loader contract:

| Path | Returns |
|---|---|
| `GET /keys/api?draw=1&start=0&length=10` | `draw`, `recordsTotal`, `recordsFiltered`, `data[]` of keys |
| `GET /admin/api/users?draw=1&start=0&length=10` | same shape for users |
| `GET /keys/reset?userkey=…` | clears the device list (`{"reset":true}`) |
| `GET /keys/resetAll?userkey=…&reset=1` | deletes the key (`{"deleted":true}`) |

Unauthenticated requests to panel pages redirect to `/login`.

---

## 10. Security notes

- Use HTTPS; never send `user_key` over plain HTTP.
- `STATIC_WORDS` and the Supabase service key stay **server-side only**.
- Do not treat `status:true` alone as success — maintenance mode also returns
  `status:true` but with **no `data`**.
- Feature flags are `"on"`/`"off"` strings; default to off when a field is
  missing or unrecognised.
- `rng` is a server timestamp, not a nonce: use it only for staleness/anti-replay
  heuristics.
