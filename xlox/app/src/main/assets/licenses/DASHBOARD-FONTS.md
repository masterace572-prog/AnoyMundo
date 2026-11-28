# Home / Settings typography — Manrope

Home, Settings and their navigation capsule use bundled **Manrope** at weights
400 (regular), 500 (medium) and 600 (semibold). The licence key/support blocks
retain JetBrains Mono; existing login and splash/launcher branding retain Inter.
All three licences are included beside this file.

Source: Google Fonts, `ofl/manrope/Manrope[wght].ttf` in
<https://github.com/google/fonts>.

- Exact upstream Git blob: `75274da58537d6123b14f2cd0c355ad4681fc2b3`.
- Source SHA-256: `3ae11c49db0455a3cc33e37d380f20fdb8c7f8b41dc07625c177e3d87a9d6ae6`.
- Licence: SIL Open Font License 1.1; full copyright/licence in `OFL-Manrope.txt`
  (upstream Git blob `e271172a9ed0cddc895aabb6509f1c7d880b492d`).

## Android compatibility

These are **fully instantiated static TrueType fonts**, not variable fonts.
They load through the already-pinned AppCompat font-resource support on Android
21+. No font provider, download, new application dependency or API-26 variable
font feature is required. The original font's other glyphs and licence metadata
are preserved; the fonts have not been subsetted.

Preparation used host-only FontTools **4.61.1**:

```python
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

for style, weight in [('regular', 400), ('medium', 500), ('semibold', 600)]:
    source = TTFont('Manrope-variable.ttf', recalcTimestamp=False)
    font = instantiateVariableFont(source, {'wght': weight},
                                   inplace=False, updateFontNames=True)
    assert 'fvar' not in font
    font.save('manrope_' + style + '.ttf')
```

| Resource | Bytes | SHA-256 |
|---|---:|---|
| `manrope_regular.ttf` | 97,436 | `2602c71d1f36c96da0022706f15da7d51102d19d10510b77b5a39bf861122807` |
| `manrope_medium.ttf` | 97,536 | `729037f5dc28daa949c5ce8e69d945bf14faf2649db737e6eb085c120c778dd0` |
| `manrope_semibold.ttf` | 97,584 | `2b2ab8c077256d2849465199657f042e36588a7b88d345acd2ab4e5807ebef99` |

`scripts/test_navigation.py` verifies the pinned bytes, font-table bounds,
static-only format, weights and licence. The host Android tests also load all
three resources on Android 21 and 28 for both loader brands. Host font-loading
checks are not a physical-device rasterization/accessibility sign-off.
