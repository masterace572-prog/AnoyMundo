# Lottie animations — provenance

The app ships three Lottie animations in `res/raw/`:

| File | Use | Length | Size |
|---|---|---|---|
| `anoy_mark.json` | Splash mark: ring draws, key notch, collapse to a dot, breathing loop | 1.8 s (108 frames) | ~5 KB |
| `anoy_sync.json` | Update gate "Checking for updates" loop | 1.2 s (72 frames) | ~2 KB |
| `anoy_success.json` | Update gate "You're up to date" one-shot | 0.7 s (42 frames) | ~3 KB |

All three are **original work authored in this repository** for Anoy Loader.
They are not derived from LottieFiles or any third-party animation. They are
generated as plain JSON by `xlox/scripts/lottie_art.py` (Python, no After
Effects), which is the source of truth: edit the script and re-run it rather
than editing the JSON by hand.

Art direction: monochrome white strokes on black, blue `#0A84FF` used once per
animation as an accent (the glint in `anoy_mark`, the outward pulse in
`anoy_success`), 60 fps, simple shapes and trim paths only. No images, no
masks, no expressions, so they render identically in hardware mode on
API 21–37.

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
