# Splash: real-phone check

A test plan for running the splash on a real phone and checking it against the
Lottie files in this folder. Nothing here has been run on a phone yet.

Labels used below:

- **measured from the Lottie**: rendered from `anoy_splash.json` with
  rlottie at 60 fps.
- **read from code**: taken from `SplashActivity.java`, the layout or the anims.
- **assumed**: platform behaviour that has not been checked on a phone.

The three scripts in the appendix were tested on synthetic captures only. Those
captures were Lottie frames placed on a 1080×2340 px / 420 dpi screen and a
1440×3200 px / 560 dpi screen, encoded as H.264 and extracted with the ffmpeg
command below. They have not been run on a real recording.

| Synthetic test | Result |
|---|---|
| Matching capture | Film start found at frame 41.98 (true value 42); every row within tolerance |
| `variants/anoy_splash_A1.json` played as the "phone" | Flagged at 1.40 s, as it should be |
| Injected dip to black during the hand-off | Flagged as "DIP OR FLASH" (26 % residual vs 6 % for a clean crossfade) |
| System-splash mark shifted 6 px, plus one empty frame | Both flagged |
| Fabricated gfxinfo dumps | Merged and deduplicated correctly; flagged frames dropped; a 2-frame hitch flagged |

## 0. Setup

Use a release or debug build of the current branch. The film is the same in both.

```sh
adb devices                                   # exactly one phone
PKG=com.xlox; ACT=com.xlox/.SplashActivity
adb shell getprop ro.build.version.sdk        # API level (31+ = Android 12+)
adb shell wm size; adb shell wm density       # px and dpi; dp = px * 160 / dpi
adb shell dumpsys display | grep -iE "refreshRate|fps" | head -5   # 60 / 90 / 120 Hz
# Animations at normal speed. 0 means reduced motion: the film is replaced by the still cut.
adb shell settings get global animator_duration_scale      # 1 or null
adb shell settings get global transition_animation_scale   # 1 or null (the 280 ms hand-off)
adb shell settings get global window_animation_scale
```

Turn on Wi-Fi with a good connection, so the update check finishes long before
2.05 s. Otherwise the hand-off is delayed (see 2.85 s in the table).

Install the helpers on a computer: `pip install rlottie-python pillow numpy`,
plus ffmpeg.

## 1. Start time: `am start -W`

```sh
for i in 1 2 3 4 5 6 7 8 9 10; do
  adb shell am force-stop $PKG; sleep 2
  adb shell am start -W -n $ACT | grep -E "TotalTime|WaitTime"
  sleep 5        # let the film and the hand-off finish
done
adb logcat -d | grep "Displayed $PKG"    # same numbers, from the system
```

- **TotalTime** runs until the splash's first frame, which is the static mark.
  The film starts on the first drawn frame after layout (read from code), so a
  slow start delays the film but does not shorten it.
- Report the median and the worst of the 10 runs.
- Proposed limits (assumed, not tied to any spec):
  - Under 1000 ms is good on a mid-range phone.
  - Over 2000 ms is worth a look.

## 2. Smoothness: gfxinfo and framestats

```sh
adb shell am force-stop $PKG; sleep 2
adb shell am start -n $ACT
sleep 2.0;  adb shell dumpsys gfxinfo $PKG framestats > fs1.txt   # frames up to ~2.0 s
sleep 2.5;  adb shell dumpsys gfxinfo $PKG framestats > fs2.txt   # the rest + hand-off
adb shell dumpsys gfxinfo $PKG > summary.txt                      # all frames since start
grep -E "Total frames|Janky frames|percentile" summary.txt
python framestats.py --hz 60 fs1.txt fs2.txt      # --hz = the panel rate from setup
```

- **Why two dumps:** each framestats dump keeps only the last ~120 frames, and
  the film alone is 171 frames at 60 Hz. The script merges the dumps and drops
  repeated frames. A cold start needs no `gfxinfo reset`, because the counters
  begin when the process starts.
- **To repeat a measurement:** run `adb shell dumpsys gfxinfo $PKG reset` only
  while the process is alive, then relaunch with `am start -S`, which
  force-stops first.
- **Frame time** is `FrameCompleted − IntendedVsync`. Rows with Flags ≠ 0 are
  left out, and so is the first frame, which is the first draw (`--skip 1`).

Problem thresholds (proposed). The budget is 16.7 ms at 60 Hz, 11.1 ms at 90 Hz,
or 8.3 ms at 120 Hz:

| Verdict | Janky frames (> budget) | p99 frame time | Other |
|---|---|---|---|
| GOOD | ≤ 2 % | ≤ 1.0 × budget (16.7 / 8.3 ms) | no run of 2 late frames in a row |
| WATCH | 2–5 % | ≤ 1.5 × budget (25 / 12.5 ms) | |
| PROBLEM | > 5 % | > 1.5 × budget | 2+ late frames in a row, or any frame > 3 × budget |

- **Late frames in the motion windows:** a late frame between 1.15 and 2.01 s
  (the lift and swap) is the most visible. Check those windows by eye in the
  screen recording.
- **Late frames during a hold:** a late frame in 0.52–1.15 s or 2.01–2.85 s
  usually can't be seen, because nothing moves.
- **Android 12+:** the summary also prints "Janky frames (legacy)". The script's
  count uses the plain budget rule, so it is closer to the legacy number
  (assumed).

## 3. Screen recording: `adb shell screenrecord`

```sh
adb shell am force-stop $PKG; sleep 2
adb shell screenrecord --bit-rate 20000000 --time-limit 8 /sdcard/splash.mp4 &
sleep 1
# Tap the Anoy icon on the home screen (needed for the system-splash check, see 5),
# or:  adb shell am start -n $ACT
sleep 8; adb pull /sdcard/splash.mp4
```

- **Variable frame rate:** screenrecord writes a frame only when the screen
  changes, often capped near 60 fps (assumed), and it can drop frames under
  load.
- **Use it for content and timing only.** Judge jank from section 2, not from
  the video.
- **On 90/120 Hz phones**, you can set the panel to 60 Hz for this recording so
  that the frames line up one-to-one.

### Extract frames at 60 fps and compare with the Lottie

```sh
mkdir frames && ffmpeg -i splash.mp4 -vf fps=60 frames/%05d.png
python compare_capture.py frames anoy_splash.json --density 420     # dpi from setup
# Side-by-side for eyes: phone frame vs Lottie frame at the same moment
python -c "from rlottie_python import LottieAnimation as L; a=L.from_file('anoy_splash.json'); [a.save_frame(f'lottie_{f:03d}.png', frame_num=f) for f in (0,13,24,31,69,94,100,103,107,121,170)]"
```

**How the script lines up the two (t = 0):**

- It finds the frame where the system mark's fade crosses 50 %.
- From that frame it works out the film's start frame, to a fraction of a frame.
- The fade runs 0–0.22 s and the mark is the only thing in that area, so this
  works on any screen size. If it fails, use `--t0 N`.

**What it compares** at each checkpoint, for the kicker row and the wordmark row:

- **Ink:** the sum of brightness, as a fraction of the 1.15 s frame. This tracks
  opacity, with a tolerance of ±0.06.
- **x:** the horizontal centre, in dp, relative to the screen centre. Tolerance
  ±1 dp.
- **dy:** the vertical move, in dp, from the 1.15 s position. Tolerance ±1 dp.

All three are independent of screen size. The layout is centred, and the lockup
sits at about 42 % of the height (read from code), so absolute y differs from
the 360×800 Lottie on purpose.

**Hand-off checks:**

- When the first frame changes after the film ends.
- How long the change takes.
- Whether every frame is a plain mix of the held splash and the next screen. A
  dip to black or a flash leaves more than 15 % residual.

**Known bias:** the first and last 1–2 frames of the eased fade are too faint
to detect. The measured start reads about 1 frame late and the length about
2 frames short. On the synthetic test it read +0.067 s / 250 ms, against a
true +0.050 s / 283 ms.

**What the script ignores:**

- The version label (top right, fades in 0.26–0.58 s).
- The status row (bottom, fades in from 0.60 s).
- The system bars.

None of these are in the Lottie.

## 4. What should be visible: timestamp table

- **t:** time from the first frame of the film.
- **Moves:** in dp, relative to the 1.15 s frame (on screen: px = dp × dpi / 160).
- **"Lottie frame":** the frame number in `anoy_splash.json` (60 fps).
- **Numbers:** measured from the Lottie unless the row says otherwise.

| t (s) | Lottie frame | What should be visible |
|---|---|---|
| 0.00 | 0 | Black. Only the system mark (the Anoy wordmark from `splash_mark.xml`, 288 dp box, screen centre) at 100 %. No text. Version label and status row not shown yet (read from code). |
| 0.22 | 13 | Mark fully gone (fade 0–0.22 s). "WELCOME TO" (grey #9A9AA1) at 81 %. "Anoy Loader" (white) at 21 %; its fade started at 0.12 s. Nothing has moved. |
| 0.40 | 24 | "WELCOME TO" 100 %. "Anoy Loader" 93 %. The version label is fading in (top right). |
| 0.52 | 31 | "Anoy Loader" fully in (99–100 %). Still lockup. The status row fades in from 0.60 s (read from code). |
| 1.15 | 69 | Same still lockup: kicker and wordmark at 100 %, nothing moved yet. This is the reference frame for all moves. |
| 1.40 | 84 | (Extra check.) Kicker 24 %, drifted up 6.0 dp. Wordmark lifted 7.6 dp. |
| 1.56 | 94 | Kicker 2 %, nearly at its full 8 dp drift. Wordmark lifted 12.2 dp. The swap starts here: "Hax" 0 %. |
| 1.56–2.01 | 94–121 | **Swap.** "Hax" fades in over the first 70 % (1.56–1.875 s). "Loader" fades out over the last 70 % (1.695–2.01 s). The whole wordmark slides right by 27.84 dp so that "Anoy Hax" ends centred. Easing (0.45, 0, 0.55, 1); no scale, no blur. |
| 1.67 | 100 | Kicker gone, invisible from here. Lift 13.0 dp, nearly done. "Hax" 22 %, "Loader" 100 %. Slide started (+2.9 dp). |
| 1.72 | 103 | Lift finished (13.1 dp in the Lottie; the app rounds to whole px). "Hax" 49 %, "Loader" 98 %. Slide +6.7 dp. |
| 1.785 | 107 | Middle of the swap: "Loader" 84 % and "Hax" 82 % overlap in the same slot. Slide +13.7 dp. |
| 2.01 | 121 | "Loader" 0 %. "Anoy Hax" at 100 %, centred (slide +27.8 dp). Still from here on. |
| 2.85 | 171 (end) | Film over, "Anoy Hax" held. The hand-off starts now if "Up to date" has been on screen for at least 800 ms (100 ms swap-out + 700 ms hold, read from code), that is, if the check finished by about 2.05 s. If not, "Anoy Hax" breathes (opacity 1 → 0.72 → 1, 2.4 s cycle) until the check ends. It then eases back to 100 % in 180 ms before the hand-off starts. |
| hand-off +0.00–0.30 | — | The next screen (sign-in, or the update screen) fades in on top: 0 → 100 % over 280 ms, easing (0.4, 0, 0.2, 1), no movement. The splash stays 100 % underneath, so no frame is darker than a mix of the two. The new screen is at 10 % at +50 ms, 52 % at +100 ms, 78 % at +140 ms, 94 % at +200 ms and 100 % at +280 ms (computed from the curve). It is fully on screen by +0.30 s. The real start is a little after 2.85 s, because the system has to create the next activity first (assumed: tens of ms; measure it with compare_capture.py). |

**Reduced motion** (animator scale 0, or "Remove animations"), read from code:

- The welcome lockup appears at once and holds for 1.0 s.
- Then a hard cut to "Anoy Hax", held for 0.7 s, then the hand-off.
- Nothing fades or moves.
- With transition scale 0 too, the hand-off is an instant switch.
- Compare with `anoy_splash_reduced.json`: frames 0–59 show the welcome,
  frames 60–101 show "Anoy Hax".

## 5. System splash vs our first frame

**Expected (read from code):**

- The theme `AppTheme.Starting` shows black (#000000) with
  `@drawable/splash_mark`.
- `SplashActivity` removes the system splash at once
  (`setOnExitAnimationListener(SplashScreenViewProvider::remove)`).
- Its own first frame shows the same mark (`splashMark`, 288 dp, centred) in the
  same place.
- So the change should be invisible: no jump, no size change, no dim mark, no
  empty black frame.

**Run once on Android 12+ (API 31+) and once on Android 11 or older:**

- **Android 12+:** the platform draws the system splash.
  - Launch by tapping the home-screen icon.
  - An `am start` from adb may show the splash without the icon (assumed).
  - On 13+, `adb shell am start --splashscreen-show-icon -n $ACT` should show it
    (assumed; check with `adb shell am help | grep -i splash`).
- **Android 11 or older:** androidx core-splashscreen draws the same icon in the
  window background.

```sh
# record as in 3, launching from the home-screen icon, then:
mkdir frames && ffmpeg -i splash.mp4 -vf fps=60 frames/%05d.png
python splash_frames.py frames --density 420
```

`splash_frames.py` checks every frame from the first mostly-black frame (the
system splash appears) until the film starts to fade the mark. A pass needs all
of these:

- The mark's bounding box moves or resizes by ≤ 1 dp.
- No frame is missing the mark.
- No frame shows the mark dimmed below 90 %.
- The background stays at ≤ 6/255, to allow for video compression.

Also look at the first and last frame of that run side by side. On Android 12+
the launch animation grows the splash out of the icon. Ignore those frames: the
script starts at the first mostly-black frame.

| Check | Android 12+ | Android ≤ 11 |
|---|---|---|
| Mark position/size unchanged (≤ 1 dp) | | |
| No empty or dim frame | | |
| Background black | | |
| Film starts from that frame without a jump | | |

## 6. Results sheet

| Phone / Android / Hz | TotalTime median / worst | Janky % | p99 ms | Verdict | Capture compare | Hand-off start / length / dip | Splash seam |
|---|---|---|---|---|---|---|---|
| | | | | | | | |

## Appendix: scripts

Copy each into a `.py` file next to this file. They are the exact versions
that were tested on the synthetic captures described at the top.

### compare_capture.py

```python
"""Compare a phone screen recording of the splash with the Lottie reference.

  python compare_capture.py FRAMES_DIR anoy_splash.json --density 420 [--t0 N]

FRAMES_DIR: PNGs from  ffmpeg -i rec.mp4 -vf fps=60 frames/%05d.png
--density:  adb shell wm density (dpi). --t0: film-start frame (fractional),
            only if the automatic sync fails.
Needs: pip install rlottie-python pillow numpy
"""
import argparse, functools, glob, sys
import numpy as np
from PIL import Image
from rlottie_python import LottieAnimation

S = 2                      # analysis pixels per dp
FPS = 60
CHECK = [0.0, 0.22, 0.40, 0.52, 1.15, 1.40, 1.56, 1.67, 1.72, 1.785, 2.01, 2.83]
TOL_INK, TOL_DP = 0.06, 1.0   # opacity-ish ink ratio, dp

ap = argparse.ArgumentParser()
ap.add_argument("frames"); ap.add_argument("lottie")
ap.add_argument("--density", type=float, required=True)
ap.add_argument("--t0", type=float)
a = ap.parse_args()
files = sorted(glob.glob(a.frames.rstrip("/") + "/*.png"))
if not files: sys.exit("no frames")
k = a.density / 160.0                      # device px per dp
W0, H0 = Image.open(files[0]).size
size = (round(W0 / k * S), round(H0 / k * S))
def dev(i):                                # device frame i -> luminance 0-1, S px per dp
    return _dev(int(min(max(round(i), 0), len(files) - 1)))

@functools.lru_cache(maxsize=48)
def _dev(i):
    return np.asarray(Image.open(files[i]).convert("L").resize(size, Image.BOX), np.float32) / 255

anim = LottieAnimation.from_file(a.lottie)
RW, RH = 360 * S, 800 * S
def ref(t):
    f = min(round(t * FPS), anim.lottie_animation_get_totalframe() - 1)
    im = anim.render_pillow_frame(frame_num=f, width=RW, height=RH)
    bg = Image.new("RGBA", im.size, (0, 0, 0, 255)); bg.alpha_composite(im)
    return np.asarray(bg.convert("L"), float) / 255

def markbox(img):                          # the system mark (a wordmark across the centre), below the text
    h, w = img.shape; cx, cy = w // 2, h // 2
    return img[cy - 15 * S: cy + 20 * S, cx - 50 * S: cx + 50 * S].mean()

def cross50(vals):                         # first fall through 50 % after the peak
    p = int(np.argmax(vals)); half = vals[p] / 2
    for i in range(p, len(vals) - 1):
        if vals[i] >= half > vals[i + 1]:
            return i + (vals[i] - half) / (vals[i] - vals[i + 1])
    return None

# --- sync: the system mark's fade (0-0.22 s) crossing 50 %
r50 = cross50([markbox(ref(f / FPS)) for f in range(20)])
if a.t0 is not None:
    t0 = a.t0
else:
    lum = [dev(i).mean() for i in range(len(files))]
    dark = next((i for i, v in enumerate(lum) if v < 0.1), None)
    if dark is None: sys.exit("no dark splash frame found; pass --t0")
    win = list(range(dark, min(dark + 4 * FPS, len(files))))
    d50 = cross50([markbox(dev(i)) for i in win])
    if d50 is None: sys.exit("mark fade not found; pass --t0")
    t0 = dark + d50 - r50
print(f"film t=0 at capture frame {t0:.2f} (density {a.density:g}, {W0}x{H0} px = {W0/k:.0f}x{H0/k:.0f} dp)")

def rows(img, lo, hi):                     # text row runs between lo and hi (fractions of height)
    h, w = img.shape
    prof = img[int(lo * h): int(hi * h), int(.05 * w): int(.95 * w)].max(1) > 0.25
    runs, start = [], None
    for y, on in enumerate(list(prof) + [False]):
        if on and start is None: start = y
        if not on and start is not None: runs.append([start, y]); start = None
    merged = []
    for r in runs:
        if merged and r[0] - merged[-1][1] < 3 * S: merged[-1][1] = r[1]
        else: merged.append(r)
    return [(s + int(lo * h), e + int(lo * h)) for s, e in merged]

def bands(get):
    a1, a2 = rows(get(1.15), .2, .49), rows(get(2.83), .2, .49)
    if len(a1) != 2 or len(a2) != 1: sys.exit(f"expected 2 text rows at 1.15 s and 1 at 2.83 s, got {a1} / {a2}")
    split = (a1[0][1] + a2[0][0]) // 2
    return (a1[0][0] - 16 * S, split), (split, a1[1][1] + 4 * S)

def metrics(img, band, base):
    sub = img[band[0]: band[1]]
    ink = sub.sum()
    if ink / base["ink"] < 0.05: return ink / base["ink"], None, None
    ys, xs = np.mgrid[band[0]: band[1], 0: img.shape[1]]
    cx = (xs * sub).sum() / ink / S - img.shape[1] / 2 / S
    cy = (ys * sub).sum() / ink / S
    return ink / base["ink"], cx, cy - base["cy"]

def table(get):
    out = {}
    for name, b in zip(("kicker", "wordmark"), bands(get)):
        img = get(1.15); sub = img[b[0]: b[1]]
        ys = np.mgrid[b[0]: b[1], 0: img.shape[1]][0]
        base = {"ink": sub.sum(), "cy": (ys * sub).sum() / sub.sum() / S}
        out[name] = [metrics(get(t), b, base) for t in CHECK]
    return out

R = table(ref)
D = table(lambda t: dev(t0 + t * FPS))
bad = 0
print(f"{'t (s)':>6} {'row':9} {'ink ref':>8} {'ink dev':>8} {'x ref':>7} {'x dev':>7} {'dy ref':>7} {'dy dev':>7}  result")
for name in R:
    for t, r, d in zip(CHECK, R[name], D[name]):
        f = lambda v: "   —   " if v is None else f"{v:7.2f}"
        ok = abs(r[0] - d[0]) <= TOL_INK
        if r[1] is not None and d[1] is not None:
            ok &= abs(r[1] - d[1]) <= TOL_DP and abs(r[2] - d[2]) <= TOL_DP
        elif (r[1] is None) != (d[1] is None) and max(r[0], d[0]) > 0.1:
            ok = False
        bad += not ok
        print(f"{t:6.3f} {name:9} {r[0]:8.2f} {d[0]:8.2f} {f(r[1])} {f(d[1])} {f(r[2])} {f(d[2])}  {'ok' if ok else 'CHECK'}")

# --- hand-off: when it starts, how long it takes, and whether every frame is a
# plain mix of the held splash and the next screen (a dip to black or a flash
# is not: it leaves a residual).
def body(img): h = img.shape[0]; return img[int(.05 * h): int(.95 * h)]   # no status/nav bar
held = body(dev(t0 + 2.83 * FPS))
noise = max(np.abs(body(dev(t0 + t * FPS)) - held).mean() for t in np.arange(2.3, 2.83, 0.05))
thr = 2 * noise + 0.0005
i0 = int(round(t0 + 2.85 * FPS))
start = next((i for i in range(i0, min(i0 + 3 * FPS, len(files))) if np.abs(body(dev(i)) - held).mean() > thr), None)
if start is None:
    print("hand-off: no change within 3 s of the film end (slow check? breathing?)")
else:
    end = body(dev(start + 0.8 * FPS)); dE = end - held
    done = next((j for j in range(start, int(start + 0.8 * FPS)) if np.abs(body(dev(j)) - end).mean() <= thr), None)
    worst = 0.0
    for j in range(start, done or int(start + 0.8 * FPS)):
        F = body(dev(j)) - held
        al = float((F * dE).sum() / (dE * dE).sum())
        worst = max(worst, float(np.abs(F - al * dE).mean()))
    rel = worst / float(np.abs(dE).mean())
    dip = rel > 0.15
    dur = "?" if done is None else f"{(done - start) * 1000 / FPS:.0f} ms"
    print(f"hand-off: first changed frame {(start - t0) / FPS - 2.85:+.3f} s after the film end, "
          f"settled after {dur} (expect ~280 ms x transition scale); "
          f"crossfade residual {rel * 100:.0f} % of the change (limit 15 %) -> {'DIP OR FLASH' if dip else 'clean crossfade'}")
    bad += dip
print("RESULT:", "all within tolerance" if not bad else f"{bad} row(s) to look at")
```

### splash_frames.py

```python
"""System splash -> first app frame: does the mark stay put, with no blank frame?

  python splash_frames.py FRAMES_DIR --density 420

Looks at every frame from the first mostly-black frame (the system splash)
until the mark starts to fade (the film has begun): the mark's bounding box
must not move or resize, the background must stay black, and the mark must
never vanish or dim on the way.
"""
import argparse, glob
import numpy as np
from PIL import Image
ap = argparse.ArgumentParser(); ap.add_argument("frames"); ap.add_argument("--density", type=float, required=True)
a = ap.parse_args()
files = sorted(glob.glob(a.frames.rstrip("/") + "/*.png")); k = a.density / 160
def load(i): return np.asarray(Image.open(files[i]).convert("RGB"), np.int16)
def mark(img):
    h, w, _ = img.shape; r = int(150 * k)
    box = img[h // 2 - r: h // 2 + r, w // 2 - r: w // 2 + r].max(2)
    ys, xs = np.nonzero(box > 80)
    if len(xs) == 0: return None, 0.0
    return np.array([xs.min(), ys.min(), xs.max(), ys.max()]) / k, float(box[box > 80].mean())
first = next(i for i in range(len(files)) if load(i).mean() < 25)
rows, peak = [], 0.0
for i in range(first, min(first + 240, len(files))):
    img = load(i); bb, lum = mark(img); peak = max(peak, lum)
    if bb is not None and lum < 0.9 * peak: break        # the film's mark fade has begun
    h, w, _ = img.shape
    corners = np.concatenate([img[int(.2*h):int(.3*h), :int(.15*w)].ravel(), img[int(.7*h):int(.8*h), -int(.15*w):].ravel()])
    rows.append((i, bb, lum, int(corners.max())))
print(f"system splash from frame {first}; {len(rows)} frames until the film's fade")
blank = [i for i, bb, lum, c in rows if bb is None]
dim = [i for i, bb, lum, c in rows if bb is not None and lum < 0.9 * peak]
bbs = np.array([bb for i, bb, lum, c in rows if bb is not None])
spread = (bbs.max(0) - bbs.min(0)) if len(bbs) else np.array([np.inf] * 4)
bgmax = max(c for i, bb, lum, c in rows)
print(f"mark box (dp) first {np.round(bbs[0], 1)}  spread L/T/R/B {np.round(spread, 2)}")
print(f"frames without the mark: {blank or 'none'}   dimmed: {dim or 'none'}   background max {bgmax}/255")
ok = not blank and not dim and spread.max() <= 1.0 and bgmax <= 6
print("RESULT:", "seamless" if ok else "CHECK: jump, gap or non-black background")
```

### framestats.py

```python
"""Frame times from  adb shell dumpsys gfxinfo com.xlox framestats  dumps.

  python framestats.py --hz 60 dump1.txt [dump2.txt ...] [--skip 1]

Several dumps of one launch are merged (each keeps only the last ~120 frames).
Frame time = FrameCompleted - IntendedVsync; rows with Flags != 0 are left out,
as the gfxinfo docs say. --skip drops the first N frames (the first draw).
"""
import argparse, math
ap = argparse.ArgumentParser()
ap.add_argument("dumps", nargs="+"); ap.add_argument("--hz", type=float, default=60); ap.add_argument("--skip", type=int, default=1)
a = ap.parse_args()
frames = {}
for path in a.dumps:
    cols = None
    for line in open(path, errors="replace"):
        line = line.strip().rstrip(",")
        if line.startswith("Flags,"):
            cols = line.split(","); continue
        if cols and line and line[0].isdigit():
            v = line.split(",")
            if len(v) < len(cols): continue
            r = dict(zip(cols, v))
            if r["Flags"] != "0": continue
            iv, done = int(r["IntendedVsync"]), int(r["FrameCompleted"])
            if done > iv: frames[iv] = (done - iv) / 1e6
ms = [frames[k] for k in sorted(frames)][a.skip:]
if not ms: raise SystemExit("no frames found")
budget = 1000 / a.hz
q = lambda p: sorted(ms)[min(len(ms) - 1, math.ceil(p / 100 * len(ms)) - 1)]
over = [m > budget for m in ms]
run = best = 0
for o in over: run = run + 1 if o else 0; best = max(best, run)
janky = 100 * sum(over) / len(ms)
print(f"{len(ms)} frames at {a.hz:g} Hz (budget {budget:.1f} ms)")
print(f"janky {janky:.1f} %   p50 {q(50):.1f}   p90 {q(90):.1f}   p99 {q(99):.1f}   max {max(ms):.1f} ms   "
      f"longest run over budget: {best}   frames > 2x budget: {sum(m > 2 * budget for m in ms)}")
v = "PROBLEM" if janky > 5 or q(99) > 1.5 * budget or best >= 2 or max(ms) > 3 * budget else \
    "WATCH" if janky > 2 or q(99) > budget else "GOOD"
print("verdict:", v)
```
