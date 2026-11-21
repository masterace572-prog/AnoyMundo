# Obsolete Android test cleanup

Scope: the user-authorized retirement of unused test inputs and unverified APK
experiments. This is not a history rewrite or a production-data cleanup.

## Removed

- `test-inputs/mundo/` (the three tracked replacement-SDK test files).
- `.github/workflows/anoy-mundo-test.yml` (its only consuming workflow).
- GitHub prerelease `anoy-mundo-lv10-sdk36-test-f1132b2d`, ID `453613196`,
  including its APK, input manifest and checksum assets. Reported broken.
- GitHub prerelease `loaders-v2.4.4-webtest-21`, ID `453669457`, including both
  APKs and checksum asset. Unverified experiment; login work was cancelled.

Git tags and historical commits were retained. No accounts, database rows,
keys, production library objects or current release selections were removed.

## Executed safeguards

- A read-only `public.app_release` check immediately before deletion found
  **no references to either test release**. The sole current selection remained
  Anoy **2.4.2 / code 20**, `loaders-v2.4.2-20/Anoy-XLOX-loader.apk`.
- All **five retained stable release IDs and all asset IDs, names, sizes and
  GitHub digests** were compared before/after and remained identical.
- GitHub latest remained **`loaders-v2.4.3-21`**; panel-linked 2.4.2 and the
  code-21 rollback APKs remain available.
- All **282 original `xlox/` file hashes** still match the baseline recorded
  at `393e759`. In particular the required original Mundo AAR is unchanged.
- No live panel publication, library replacement, deployment or database write
  was part of this cleanup.
