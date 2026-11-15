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

- **Against the app** (`verify.py`, rlottie, every frame): each layer's
  movement matches `applyTime()` within 0.01 px, opacity within ~1 % (8-bit
  rounding). The wait loop matches `breathe()` (whole line, reversed return
  curve) within ~1 %.
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
