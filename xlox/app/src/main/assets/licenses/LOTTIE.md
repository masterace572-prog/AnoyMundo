# Lottie animations — provenance

The app ships two small Lottie animations in `res/raw/`, both on the splash
status line (2.3.7; splash only since 2.3.8), played by `com.xlox.ui.Motion`:

| File | Use | Length | Size |
|---|---|---|---|
| `anoy_arc.json` | Quiet white arc: splash "Checking for updates…" (loop) | 2.4 s (144 frames) | ~1 KB |
| `anoy_check.json` | Blue #0A84FF ring + tick that draw in, then hold: splash "Up to date" (one-shot) | 0.7 s (42 frames) | ~2 KB |

All are **original work authored in this repository** for the loader family. They
are not derived from LottieFiles or any third-party animation. They are
generated as plain JSON by `xlox/scripts/lottie_art.py` (Python, no After
Effects), which is the source of truth: edit the script and re-run it rather
than editing the JSON by hand.

The main splash film uses real TextViews (`SplashActivity`); only the small
status line uses Lottie. The launcher/system-splash wordmarks use bundled
Inter outlines (SIL OFL 1.1, see `OFL-Inter.txt`). Anoy keeps its original
committed vectors. `xlox/scripts/brand_wordmark.py` generates the KennyPvt
flavor vectors from the same bundled `inter_semibold.ttf`, without a download.

Art direction: stroke only (1.75–2 dp), white on black, blue `#0A84FF` only
for the done check; no fills, glow, bounce or overshoot; 60 fps, simple paths
and trim paths only. Under reduced motion (animator scale 0) nothing moves:
the arc is one still frame and the one-shots show their last frame. No images, no masks, no expressions, so they render identically in
hardware mode on API 21–37.

Licence: same terms as the rest of this repository.

## Player

The animations are played by **lottie-android** 6.7.1
(https://github.com/airbnb/lottie-android).

    Copyright 2018 Airbnb, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

## Other components shipped with the app

- Material Components for Android 1.13.0 — Apache License 2.0
  (https://github.com/material-components/material-components-android)
- AndroidX AppCompat / Core / Core SplashScreen — Apache License 2.0
- Lucide icons — ISC licence, see `LUCIDE-ISC.txt`
- Inter and JetBrains Mono — SIL Open Font License 1.1, see `OFL-*.txt`
