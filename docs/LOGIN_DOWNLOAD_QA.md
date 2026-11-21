# Game web-login return / library-wait fixes

## Scope and diagnosis limits

Version 2.4.3 (21) uses the original `xlox/app/libs/mundo-release.aar` unchanged
(SHA-256 `368293d93076aea5db252d307614ba10a23bae84eb298830e6dab42aa3f90ba0`).
The experimental uploaded AAR is not linked, patched, moved, or deleted. Its
workflow remains quarantined. Both normal brands retain their packages,
signers, JNI/licensing contract, contacts, icons, and per-Owner release channels.
No backend migration, production deploy or live panel selection is needed.

The report is: Twitter web login succeeds in a browser, but does not complete
inside the virtual game; library waits are intermittent on a stable connection.
There is no device trace or reproduction yet. Code inspection found these gaps:

- The original SDK's alias advertises `fbconnect`, `imobile1450`, and
  `tencent1106545419`. Its intermediary accepts a direct `fbconnect` intent;
  the other branch expects a private wrapper containing an inner intent. A
  real browser returns a direct VIEW URI, not that wrapper. The new bridge uses
  public, existing SDK APIs to resolve/launch a matching exported activity of
  the already-running virtual BGMI, default user 0. `twittersdk://callback` is
  also registered. Facebook delegates unchanged to the original intermediary.
- A direct return is dropped if BGMI is not running, no exported virtual handler
  matches, or data is malformed/unrelated. It never starts outside BGMI or a new
  game session. The game/provider SDK remains responsible for OAuth validation.
- This repairs a supported callback path; it **does not force the game's own
  embedded browser**, intercept web credentials, bypass provider policy, or
  prove that the affected game version uses one of these schemes. If that game
  returns a different scheme or an HTTPS App Link, obtain only its redacted
  scheme/host/path before adding a narrow route. Do not register a web catch-all.
- The library sheet previously used one shared queue for silent and interactive
  work. Disposing it did not close network I/O; old retries could occupy the
  queue. Integer-percent/48-KiB progress filtering could also report a stall
  while bytes continued arriving. These are corrected, without claiming every
  slow request was caused by them.

## Implemented download behaviour

- A screen owns a cancellable request and Future. Disposing it cancels both,
  disconnecting even a request waiting for headers. Backoff wakes immediately.
- Silent checks have a separate queue. Interactive downloads/installs remain
  serialized so temporary/staging paths cannot race.
- Two attempts maximum; 20-second total metadata budget; 90-second total transfer
  budget; each connect/read limited to 8/12 seconds and the remaining budget.
  Deadlines close the active socket and span redirects/retries.
- HTTPS-only redirects; each abandoned/error connection is released. Fresh
  metadata still forbids cache reuse. Binary requests no longer force CDN cache
  misses; announced checksums still reject stale/different bytes.
- A checksum mismatch is not retried against the same stale metadata. The UI's
  retry action fetches a fresh manifest first. Existing verified installation
  is retained on failed transfers.
- One successful library manifest also remembers the supported BGMI version;
  a separate identical home-screen request is no longer needed. Last known
  game-support information remains available offline.
- Slow copy identifies server wait, transfer inactivity, or local installation.
  Byte progress is sampled for UI drawing, not filtered by whole percentages.
- Timing logs separate metadata, response-header wait, and verification/install.
  Login return logs record result names only, never URI, tokens or Intent extras.

## Automated checks

`python3 xlox/scripts/test_runtime_fixes.py`:

- Seven Android/source wiring/parser guards (dependency identity, manifest/alias merge,
  narrow scheme filter, callback privacy/scope, cancellation, one-manifest flow).
- Fifteen JVM regression groups execute the production `GameLoginRouter`,
  `LibraryHttp`, and `UpdateRequest` classes. They cover callback preservation,
  Facebook delegation, stopped/foreign/private activity rejection, malformed
  data, binary-vs-metadata caching, redirect cleanup/downgrade rejection, header
  cancellation, deadline during a blocked body read, interruptible backoff,
  retry after timeout, and stale-timer isolation.
- CI also applies APK mode to both actual merged manifests, checking the bridge,
  inherited provider filters, export setting and declaration order.

Local runs passed these checks and the existing 14 UI gates, 20 branding/channel
checks and 12 artwork tests. Full APK build/lint/signature evidence is recorded
only once CI completes. These are not substitutes for the device checks below.

## Device acceptance checklist (not yet performed)

Use the original-Mundo 2.4.3 build, not the quarantined SDK experiment. Back up
important app/container data before any uninstall/clear-data; neither is required
for the normal in-place update. Do not share account passwords or licence keys.

1. Record phone model, Android version, BGMI version and browser. In virtual BGMI,
   start Twitter login, finish the official provider flow, and verify the same
   running virtual game completes login. Outside BGMI must not open/restart.
2. Repeat after cancelling web login; retry should start a fresh transaction.
   Check Facebook login still works. Check normal Telegram/other links unchanged.
3. If Twitter still fails, provide a redacted activity-routing trace around the
   return (scheme/host/path only; remove query and fragment), plus result-only
   `GameLoginReturn` log lines. Never paste the full OAuth URL, tokens, password,
   cookies, licence key or an unredacted bug report.
4. Re-download the current library. Check connecting, byte progress, installing
   and completion states. Repeat once warm. Timings can differ due to server,
   Storage/CDN, TLS and local I/O even with stable access-network throughput.
5. Leave/close the app during a check; reopen and retry. A disposed request must
   not hold the new UI behind its old retry budget. Verify a moving download
   below 1% progress per six seconds is not labelled stalled.
6. Test offline/timeout/redirect failure/corrupt payload; no unverified library
   should replace the installed copy, and a retry must re-read the manifest.
7. Verify Anoy never reads Kenny releases and vice versa; no live release choices
   change merely by downloading/installing either APK.
