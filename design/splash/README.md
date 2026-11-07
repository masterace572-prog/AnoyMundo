# Anoy splash film as Lottie (reference only)

**Not used by the app.** The app plays this film natively in
`xlox/.../SplashActivity.java` (`applyTime`), with the real font, exact curves,
the live version label and status line, the wait for the update check, the
reduced-motion switch and the 0.28 s crossfade to login. Keep that the source
of truth; these files are for design review, QA reference, a website or
marketing preview, and other platforms.

| File | What | Length |
|---|---|---|
| `anoy_splash.json` | "Welcome To Anoy Loader" -> "Anoy Hax", with the system logo crossfade | 2.85 s, frames 0-170 @ 60 fps, 28 KB |
| `anoy_splash_wait.json` | Whole "Anoy Hax" line breathes 100 % -> 72 % -> 100 % (set the player to loop) | 2.4 s, 8 KB |
| `anoy_splash_reduced.json` | Reduced motion: still welcome 1 s, hard cut, still "Anoy Hax" 0.7 s | 1.7 s, 23 KB |
| `preview.png` | Sample frames | |

360 x 800 canvas, text outlined from `res/font/inter_semibold.ttf` (no font
embedded), black `bg` layer (`--transparent` to drop it).

The "Anoy" | "Loader" gap is one space at 40 dp with -0.03 em letter spacing
(`measureText(" ")` = 8.88 dp), because `SplashActivity` sets the slot margin
in code; the 8 dp in `activity_splash.xml` is overridden. On a phone the app
rounds it to whole device pixels (9 px at 1x, 27 px at 3x).

## Regenerate

```
pip install fonttools uharfbuzz
F=../../xlox/app/src/main/res/font/inter_semibold.ttf
python gen_splash.py $F main    anoy_splash.json
python gen_splash.py $F loop    anoy_splash_wait.json
python gen_splash.py $F reduced anoy_splash_reduced.json
```

If the splash timing in `SplashActivity` ever changes, update the constants in
`gen_splash.py` to match - never hand-edit the JSON.

## Verified (2026-11-15)

- **Against the app's code** (`verify.py`, rlottie at 4x, every frame; not a
  device capture). Expected values: SplashActivity's timeline constants and
  easings, plus the horizontal layout computed from the font with the app's
  own rules (gap = `measureText(" ")`, Android letter spacing, centring,
  `recentreDx`). Results after the 8.88 dp gap fix: absolute x within 0.04 px,
  vertical moves within 0.01 px (lift assumed 13 dp), opacity within ~1.2 %
  (8-bit rounding). The waiting loop and reduced file match the same way.
  Reduced cut (2026-11-16): players disagree on the out-point frame (rlottie
  draws a layer on it; lottie-web hides it), so the reduced file no longer
  uses layer in/out points. Every layer runs 0 to the end of the film, and the
  frame-60 cut is made with held opacity keys (kicker and "Loader" 100 -> 0,
  "Hax" 0 -> 100). Frames 60-101 now match at 0.00 % opacity. rlottie and
  lottie-web give the same result on frames 59/60/61 (measured, a fresh
  player for each frame). No file uses in/out points to hide anything that
  should not be seen.
- **lottie-web 5.13 vs rlottie** (canvas renderer, frames 13-170 incl. 72, 94,
  107, 121 where the lift and slide overlap): positions within 0.24 px,
  opacity within ~1 %.
- **lottie-android 6.7.1** (source check, not a device render): split X/Y
  position parses into separate X and Y animations
  (`AnimatableSplitDimensionPathValue`), and playback ends at `op - 0.01`, so
  the last frame shows "Anoy Hax".
- **Not checked:** lottie-ios, and real devices. Before sharing widely, open
  `anoy_splash.json` in each player you will use and compare frames 72, 94,
  107 and 121.

The ~13 px lift is estimated here; on the phone the app measures it from the
real layout, so a pixel of difference is expected and is not an error.

## Variants (comparison only, not in the app)

`variants/` holds Lottie-only alternatives for side-by-side review. None of
them is applied to the app; the app's timing is unchanged.

| File | Change from the app's film |
|---|---|
| `anoy_splash_A1.json` | Kicker exit (fade + 8 dp drift) and the wordmark lift use cubic-bezier(0.2, 0, 0, 1) instead of (0.4, 0, 0.2, 1) |
| `anoy_splash_A2.json` | Kicker fade-in 0-0.46 s (was 0.40), wordmark fade-in 0.12-0.58 s (was 0.52) |
| `anoy_splash_A1A2.json` | Both |

All 2.85 s, 171 frames. Regenerate with `--variant=A1|A2|A1A2` (main only);
check with `python verify.py variants/anoy_splash_A1.json --variant A1`.
