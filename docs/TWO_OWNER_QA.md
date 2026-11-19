# Two-owner / two-brand rollout evidence

Implementation branch: `arena/dda4cc57-anoymundo`. The shared panel and
licensing contract remain in place; only Owner library/APK-release authority
and Android branding/installation identities split into two fixed channels.

## Contract

| Brand | Channel | Application ID | APK filename |
|---|---|---|---|
| Anoy | `anoy` | `com.xlox` (preserved) | `Anoy-XLOX-loader.apk` |
| KennyPvt | `kenny` | `com.kennypvt` | `KennyPvt-loader.apk` |

Missing/empty public channel means Anoy, for existing installed loaders.
Unknown, duplicate and prototype-property channel values are rejected. Owner
writes ignore query-string routing and derive authority from an immutable,
unique `users.owner_channel`; conflicting body channels are refused.

Exactly two permanent Owner slots are available. A private, single-use
second-owner invitation is provisioned operationally and attributed to Anoy;
there is no Owner creation option on the panel. No production test account claims that slot. Both
Owners are protected from rename/demotion/deletion and cannot edit each other
through user management. Each can still change their own password/profile.
Keys, pricing/billing, general server settings and `/connect` are shared.

## Reproducible local checks

From `web/`: `npm ci && npm run build && npm test`.

- **213 smoke assertions**: existing UI, shared licence/key/billing/settings
  behavior, sessions, CSRF, legacy endpoints and app updates.
- **20 Node HTTP/channel/archive tests**: invitation expiry/replay/parallel
  registration; both directions of Owner/account/release isolation; 10 MiB
  signed binary upload; ZIP above the old 3 MiB cap; canonical `libbgmi.so`;
  CRC/path/ABI/entry-count/real-inflation bounds; pending-only cancellation;
  failed Storage writes; concurrent publication; bounded health probes.
- **69 PostgreSQL/PGlite checks**: migration preservation and repeatability,
  old single-owner index upgrade, safe full-schema reapplication after two
  Owners exist, account constraints, atomic publication rollback, scoped RPCs,
  independent APK version codes, unlimited Owner billing and RPC grants.
- `npm audit` and `npm audit --omit=dev --audit-level=moderate`: **0 findings**
  after upgrading `fflate` to 0.8.3. ZIP decompression itself uses a strict
  custom ZIP32 reader and bounded Node zlib; fflate writes normalized ZIPs.
- Android static checks: **13 UI gates + 16 branding/channel gates**.

The Playwright suite signs up the second Owner **only on the offline memory
server**, performs real XHR uploads (10 MiB `.so` / >3 MiB ZIP), verifies
canonical names and cross-channel denial, checks phone overflow and captures
desktop/phone pages. Generated screenshots remain ignored/artifact-only.

## Original two-channel production rollout

- Additive migration applied as **`20261119230821 / two_owner_channels`**.
  Its source is `supabase/migrations/20261119_two_owner_channels.sql`, SHA-256
  `3b64cf14b177e0ecffbe6a0cc42196e53a23cbd6b885713eda2564e96e6207f9`.
- No historical fresh-start migration was reapplied. Existing data was not
  reset, re-seeded or replaced by a starter library.
- Confirmed after migration: Anoy remains user 1; existing one licence key
  remains; current Anoy library remains row 49 / version 1.3 / BGMI 4.7.0 /
  3,051,976 bytes. The actual decoded stored bytes were rehashed and match
  `861ec67356a343770421aef223d318fe2b0648c8e8c39b4c5d0cc1c3588eb060`.
- Existing Anoy APK update remains row 14 / version 2.3.8 / code 17 /
  mandatory. GitHub APK builds do not change this choice automatically.
- Private Storage bucket `loader-releases` exists with 11 MiB object limit;
  input and expanded ZIP contents are each capped at 10 MiB by the app.
- Owner/invitation/publication RPCs deny `anon` and `authenticated`; only
  `service_role` can execute them. The legacy one-owner index is gone.
- Production deployment **`dpl_9pLBPnmAwGjqQXCF4yniyX1iQWRg`** is READY and
  PROMOTED from feature commit **`db146e2e698ab4109f38ce95fc47adcace47e493`**
  to https://anoymundo-panel.vercel.app (Node 26 / bom1).
- Live health: **15/15 tables available**. Health now requests one lightweight
  ID per table rather than counting/processing historical base64 releases.
- [Post-promotion live + browser CI](https://github.com/masterace572-prog/AnoyMundo/actions/runs/44636752089)
  passed: **25 live public checks, 0 failures**, then offline desktop/phone
  browser tests. This includes actual Anoy ZIP size/hash/canonical-entry
  verification and Kenny's independent empty library/APK channel.
- Live CDN check observed cached APK responses and an uncached function in
  bom1. Error/library/session responses remain no-store.

**Authenticated production Storage check was skipped**: the existing
`PANEL_OWNER_PASSWORD` GitHub secret is not configured. No secret was read,
new test credential installed, or session forged to bypass that. The manual
live workflow supports the real 10 MiB PUT/cleanup check once that secret is
configured, without publishing/changing a live release. Temporary local
uploads/browser accounts use the memory database only.

The valid pending Kenny Owner referral was reused, not replaced. It is
created by Anoy, unused, and expires after seven days. **Its plaintext is not
stored in this public document or in source control.** Give it privately to
the intended second Owner; use `/register`, then their own Library/App update
pages. Until then the second slot remains intentionally empty.

## Android release verification

[Dual-brand Android CI](https://github.com/masterace572-prog/AnoyMundo/actions/runs/44637854925)
**passed** for source commit **`341e2fec023331114da4806e6e0d5b687a42fc3a`**.
Both release variants compiled and linted; both passed packaged permission,
application identity/branding, minSdk 21 / targetSdk 28, native 16 KiB alignment
and release-signature verification. The SDK-checker format mismatch was fixed
with regressions for both decimal and typed-hex `aapt2` XML output, without
relaxing the required SDK values.

One public [release, `loaders-v2.4.0-18`](https://github.com/masterace572-prog/AnoyMundo/releases/tag/loaders-v2.4.0-18),
was published from that exact commit with both signed APKs and `SHA256SUMS.txt`:

| APK | Bytes | SHA-256 (matches GitHub's uploaded-asset digest) |
|---|---:|---|
| [Anoy-XLOX-loader.apk](https://github.com/masterace572-prog/AnoyMundo/releases/download/loaders-v2.4.0-18/Anoy-XLOX-loader.apk) | 5,172,132 | `e9e1205df991818c49dc175668386bc0bd18d0972c0ffcc87ee4bb937ccf054c` |
| [KennyPvt-loader.apk](https://github.com/masterace572-prog/AnoyMundo/releases/download/loaders-v2.4.0-18/KennyPvt-loader.apk) | 5,173,906 | `d005fd7651380fdd0e27c678ab7c620f8751adddb61b0e99fbbff75351bf3547` |

The release is not a draft or prerelease. Neither live Owner's APK-update
selection was changed: Anoy still advertises 2.3.8 (17), and Kenny has no
published update. Each Owner can use **Use latest build** on their own App
update page and publish when ready. Builds never reset or publish libraries.

There is no claim here of an arm64 phone, game-container, native-injection or
in-place production-device upgrade test. The original JNI namespace, Anoy
application ID/signing key, minSdk 21, targetSdk 28 and licensing salt are
preserved by the implementation and checked where tooling permits.

## Operational notes

- Supabase signed PUTs bypass Vercel's ~4.5 MB request-body ceiling. Publication
  JSON is small. Normalized outputs have deterministic hashes.
- No public Storage policies were introduced. A cancellation can delete only
  the authenticated Owner's pending paths, never a current release.
- A lost publication RPC response may have committed: the output is retained
  and the UI tells the Owner to reload/check before retrying.
- Old GitHub releases/tags are never pruned. Each Owner's **Use latest build**
  selects only their own APK filename; live update publication remains manual.
- GitHub's connection permits push/read but returned 403 for manual Actions
  dispatch/cancellation, and sandbox networking cannot download Actions log
  archives. Normal push CI and structured check annotations were used for
  verification; no GitHub credentials were requested or exposed.

## Referral/contact follow-up

- All referrals are single use. Normal-role signup now uses
  `register_account_from_referral`; deletion uses `revoke_referral`, both
  service-role-only transactional RPCs. Used/deleted referral identities are
  immutable and unique, preventing reset or reissue.
- Referral deletion is POST-only, same-origin, authority-scoped and confirmed
  in the browser. It never deletes an account or changes a balance.
- Owner selection and the long Owner-invitation notice were removed. Both
  Owner rows enforce a null account expiry; invitation expiry is independent.
- Kenny's Java resource and native Telegram contact use `crimecell`; Anoy's
  contact is unchanged. Android 2.4.1 (19) is a new release, not an overwrite.
- Current-source bootstrap credentials/hashes and the historical QA mechanism
  for publishing temporary password hashes were removed. Offline QA uses
  ephemeral passwords; production credentials were not changed. Removing text
  from the current tree does not erase earlier commits: if an old default is
  still used, rotate it privately in Settings.
- Root README contains only the project name. No private referral is recorded
  in source, public docs, CI output or release notes.
- Local validation: 213 smoke assertions, 20 channel/referral HTTP tests,
  69 PostgreSQL checks, 13 UI gates and 16 branding/channel gates passed.
  Desktop/phone browser tests passed, including confirmed referral deletion,
  failed signup with the deleted code and private one-time Owner registration.

Follow-up production evidence:

- Migration **`20261119234910 / referral_single_use`** applied. Local source
  SHA-256: `64e542d6d6fb86021ce220f8902152a4649bbff62cb407dd61ae3ec3cf05900c`.
- Deployment **`dpl_D1vUko2bDFwaKZyrJWkpYCj5PcLZ`**, source
  **`ca92e5fda0fde1a4aa4f81eee97f5cbec438502a`**, is READY/PROMOTED to the
  production panel. Post-deploy health passed with 15/15 tables.
- The specifically requested referral is revoked and absent from active
  listings. Existing user/key counts remain one each. Anoy is permanent;
  exactly one valid unused Kenny Owner invitation remains privately stored.
- Existing Anoy library 49 was rehashed from the actual stored bytes and
  still matches the original SHA-256; current APK update remains row 14 /
  code 17. No library or panel APK-update selection was replaced.
- [Web/browser QA](https://github.com/masterace572-prog/AnoyMundo/actions/runs/44640733758)
  and [dual-APK CI](https://github.com/masterace572-prog/AnoyMundo/actions/runs/44640733741)
  passed. The latter checked the compiled contact resources of both brands,
  packaged permissions, identities, SDKs, native alignment and signatures.
- [Both signed APKs, 2.4.1 (19)](https://github.com/masterace572-prog/AnoyMundo/releases/tag/loaders-v2.4.1-19)
  were published together from the exact source commit. GitHub's uploaded
  asset digests match the build checksums in the release notes.
- Authenticated production Storage and physical-device checks remain outside
  this follow-up's verified scope; no credentials were requested or read.
