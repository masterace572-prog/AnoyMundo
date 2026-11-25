# BGMI Air — 2.4.3 implementation notes

**Restored baseline:** the owner ended the split-APK/crash investigation and
requested the released 2.4.3 APK + OBB flow. The loader's application/build
inputs match public source `f8f5884`; split preparation, native-copy repair and
root/crash-report diagnostics have been removed. Air keeps the original
library-dependent **Coming soon** behavior, not a new permanent disable switch.
The separately delivered 20 MiB panel limit remains. See the
[rollback scope](../docs/RESTORE_2.4.3.md).

Included in the owner-requested **2.4.3 (versionCode 21)** replacement, alongside
sliding Home / Settings navigation. The separate `claudeuiredesign/` copy, panel,
database, native licence/JNI implementation, original signer, SDK versions and
live library/APK selections are unchanged. No game library is bundled in the APK.

The current OTA installer requires a strictly higher code. An installed code-20
loader can upgrade; an installed code-21 loader needs a manual same-signer APK
update, without uninstalling or clearing container data. Release/build evidence
and the navigation changes are documented in [RELEASE-2.4.3.md](RELEASE-2.4.3.md).

## Game identities and availability

| Game | Installed game package | Downloaded native payload |
|---|---|---|
| BGMI | `com.pubg.imobile` | `libbgmi.so` |
| BGMI Air | `com.krafton.bgmilite` | `libbgmilite.so` |

Use **`libbgmilite.so`**, not `libbgmiair.so`. These are not interchangeable aliases.
Both Anoy and KennyPvt share these game mappings; their application IDs, release
channels, artwork, contacts and licensing remain separate as before.

- Home has separate game cards and named Launch/Stop/Install actions. The Air
  card uses the owner-supplied image; it does not replace either loader's branding.
- Without an installed, readable Air file passing the existing basic ELF check,
  its state/action is **Coming soon**, disabled. A version label or empty file
  cannot enable it. A debug launch stand-in is explicitly debug/preview-only.
- After installing a bundle containing Air's library, Air becomes launchable if
  the game is installed and the normal licence, activation and permission checks
  pass. Otherwise its action offers **Install BGMI Air**, or asks for the existing
  prerequisites. An installed library is not proof of ABI/linker/game compatibility.
- BGMI always keeps its own file. An Air-only extracted archive is not a BGMI
  replacement; the generic legacy scan excludes Air's filename.
- A later successfully installed BGMI-only bundle removes the old Air payload,
  returning Air to Coming soon. Failed network/download verification does not
  count as installing such a withdrawal. Installation still replaces individual
  files; this is **not** a new all-files transactional rollback system.

## ZIP to prepare when you are ready

```text
library-release.zip
├── libbgmi.so
├── libbgmilite.so
└── optional-shared-dependency.so
```

Put **both game files at the ZIP root with these exact names**. Keep BGMI's
working arm64 binary in every normal bundle. Do not rename either game file to
`lib.so`, and **do not upload Air alone as a raw `.so`**: the existing panel's
single-file normalization contract treats a raw library as BGMI.

The current panel normalizer preserves valid arm64 `.so` siblings next to the
identified BGMI payload. Android can also find wrapped layouts, but the panel
can drop non-sibling files during normalization; the root layout above avoids
that ambiguity. The retained panel limit is **20 MiB for the uploaded file**
and **20 MiB for total expanded contents** (20,971,520 bytes each), not 20 MiB
per game. The loader restoration does not reverse that separate upload rollout.

When publication is separately authorized, publish the bundle in the correct
Owner/channel with a new library version. Users must download/install that
release through the existing gate, or Settings → Re-download library, before
Air unlocks. Merely placing a file on the server does not modify an installation.
The existing transfer/hash checks and installed-version persistence remain in
`Downtwo`; no new network endpoint or per-game release/version API was invented.

## Two-game warning and independent operation

The chosen policy is **warn, wait, then allow both games**—not force-stop the first.

1. Launching the first game uses the normal flow.
2. If another virtual game is already running, a scrollable warning explains
   extra RAM/CPU/GPU usage, lag, heat, battery consumption, Android low-memory
   termination, and possible game/loader crashes.
3. **OK, launch anyway** is disabled for ten full monotonic seconds. Cancel is
   available immediately. Finishing the timer **never auto-launches**.
4. Confirmation rechecks the selected package, licence, activation, library and
   permissions. A second check immediately before the container call catches a
   different game starting during a long OBB copy.
5. Back, outside tap, dragging away, backgrounding or Activity recreation does
   not retain consent. A new attempt gets a fresh warning. No consent is stored.
6. Both games may then run. Each Stop kills only its own virtual package; Stop
   remains usable when a licence expires. Running-state hits/misses are per game.

Only one preparation/permission/launch operation is processed at a time. The
chosen target cannot be switched underneath an asynchronous copy. The same
package is preserved through permission-page recreation and the expiring,
one-shot game-files restart marker. Restart does not preserve concurrency consent.
Home's automatic library recheck and manual re-download are guarded while either
game is running, to avoid replacing a library in use.

### Game files and login boundaries requiring a device

- `AppManager` accepts a package: Air uses its own source/destination OBB
  folders and the same verified-copy pipeline. BGMI's OBB names are not reused.
  This requires a main OBB. The owner reports that a normal game APK + OBB works
  and has stopped the split-APK experiment; this is not an independent device
  test of either flavor or proof that all Air distributions use expansion files.
  Split/asset-pack installs are outside this restored baseline's supported path.
- The existing contained web-login hooks are **BGMI-specific**, not blindly
  installed in Air processes. Air's provider IDs, callback schemes and container
  behavior have not been supplied or phone-validated.
- A bare legacy external callback is refused as `AMBIGUOUS_GAME` when both games
  run, rather than potentially dispatching Air's login data to BGMI. BGMI's private,
  scoped WebView return path is unchanged; Facebook still delegates to the original
  SDK. Until provider QA is complete, finish login before starting the second game.
  This safeguard is not a claim that Air social login has been implemented/tested.
- Real arm64 game launch, the downloaded libraries' dependencies/ELF alignment,
  memory-pressure behavior and provider login still require device QA.

## Code map

| File | Responsibility |
|---|---|
| `utils/GameTarget.java` | Package/library identities and bounded process selection; rejects conflicting game identities. |
| `utils/GameLibraryFiles.java` | Filesystem-only ELF/ZIP-tree selection; Air has no BGMI fallback. |
| `utils/LibManager.java` | Per-game canonical paths, optional bundle install/removal, separate error files. Default callers still mean BGMI. |
| `utils/Downtwo.java` | Existing verified transfer invokes the bundle installer before saving release metadata. |
| `BoxApplication.java` | Selects the virtual process's own library and diagnostics; BGMI-only web hooks remain scoped. |
| `MAct.java`, `layout/page_home.xml` | Two cards/actions, selected-target copy/launch/stop, availability and update guards. |
| `utils/GameRunState.java` | Independent running-state grace/debounce for each game. |
| `utils/ConcurrentLaunchGuard.java`, `layout/sheet_concurrent_games.xml` | Testable ten-second single-use confirmation and scrollable warning. |
| `utils/StorageAccess.java` | Persists and consumes the chosen game for the existing restart continuation. |
| `utils/GameLoginRouter.java`, `GameLoginCallbackActivity.java` | Refuse unbound legacy return dispatch when another supported game is running. |
| `drawable-nodpi/ic_game_bgmi_air.png` | 224×224 derivative of the supplied image; [provenance](artwork/BGMI-AIR.md). |

## Automated verification

```sh
cd xlox
python3 -B scripts/ui_quality_gate.py
python3 -B scripts/check_branding.py
python3 -B scripts/test_runtime_fixes.py
# The last command also runs scripts/test_dual_game.py.
```

The runtime entrypoint needs a JDK, or `JAVA` plus `ECJ_JAR` for the documented
JRE/compiler fallback. It does not install tools or call live services. The new
suite compiles production game-selection, filesystem, library-install, running
state, warning and restart-marker classes against temporary test-only Android
adapters. Those adapters are never packaged. It exercises actual logic, **not**
a compiled APK, Android permission UI, native linker or the container SDK.

At completion of the original Air feature (before this release/navigation task):
14/14 UI source gates; 20/20 branding/channel source
gates; existing 12 wiring tests and 15+15 JVM regression groups; new 6 wiring
tests and 17 dual-game JVM regression groups. Application Java was also parsed
as Java 8 syntax. Test logs and change-isolation evidence are in ignored
`../qa-logs/bgmiair/`. That feature-only pass had no Android SDK/ADB/emulator;
it was not an APK build, Android runtime test, native compilation or screenshot.
See the separate release record for subsequent CI compilation / view tests.

## Required real-device acceptance QA

Run on a disposable, authorized arm64 test device for **both loader flavors**;
keep real licence/provider credentials out of captures and logs.

| Scenario | Expected result |
|---|---|
| Existing BGMI installation/library only | BGMI unchanged; Air disabled Coming soon with supplied icon. |
| Empty/truncated/non-ELF Air file | Air stays Coming soon; BGMI is not replaced with Air. |
| Valid two-file bundle | Both canonical files retain their own bytes; Air card refreshes without restarting Home. |
| Next release omits Air | After successful installation, old Air file is gone and Coming soon returns. |
| Air missing from device but library present | Install BGMI Air opens `com.krafton.bgmilite`, not the BGMI store page. |
| First launch of each | Only that package's files are prepared/copied; only that game's library loads. Confirm real Air OBB/asset format. |
| BGMI running → launch Air; reverse direction | Warning each direction; confirmation disabled at 0–9.999 s, enabled at ≥10 s; no automatic launch. |
| Cancel / Back / dismiss / background / recreation | No second launch; retry starts a fresh ten-second wait. |
| Wait complete → explicit confirm | Both may run; Stop BGMI leaves Air alone and Stop Air leaves BGMI alone. |
| Other game starts during a long copy | Warning is still required before the final container launch. |
| Permissions / denial / restart | Same game resumes only after permission grants; denial launches neither; restart never switches Air to BGMI. |
| Expired licence / activation failure / invalid library | No new game launch; existing game can still be stopped. |
| Either game running → re-download | Clear stop-games message; no active library replacement from Home. |
| Each game crashes/closes independently | Only its own state returns to Launch after the existing debounce/grace. |
| Social login alone and with the other game open | Validate actual provider/SDK routes; no cross-game callbacks, external relaunch, or assumed success. |
| Narrow phone, large text, tablet, reduced motion | Both named actions and warning Cancel remain reachable; Home actions slide with Home and are offscreen in settled Settings. |

## Suggested follow-ups — not implemented

1. **RAM/free-storage context** beside the warning, with measured values rather
   than an invented universal safe-RAM threshold. Prefer one game on low-memory devices.
2. **Per-game supported versions and release notes** in a deliberately extended
   manifest/panel contract. The current global library version and BGMI-only
   `game_version` must not be mislabelled as verified Air compatibility.
3. **Optional “Stop the other game and switch”** alongside “Launch anyway,” if
   you want a safer shortcut later. Do not silently replace the selected policy.
4. **Air-specific asset and social-login validation** on a real installation,
   followed by scoped compatibility work before announcing broad support.
