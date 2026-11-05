#!/usr/bin/env python3
"""
One-off UI refresh helper for the converted Handlebars views.

The original CI4 templates carried per-page <style> blocks full of gradients,
glass effects and font overrides. The modern flat design lives in one place
(public/assets/css/app.css), so this script removes those inline styles and
rewrites the decorative class names into semantic, gradient-free ones.

The generated .hbs files are committed, so this script is NOT needed at build
time. It is kept for traceability. Usage:  python3 scripts/refresh-ui.py
"""
import re
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
VIEWS = ROOT / "views"

GRADIENT_TOKEN = re.compile(
    r"^(?:bg-gradient-to-\w+"
    r"|from-[\w-]+|to-[\w-]+|via-[\w-]+"
    r"|hover:(?:bg-gradient-to-\w+|from-[\w-]+|to-[\w-]+|via-[\w-]+)"
    r"|bg-clip-text|text-transparent|animate-gradient)$"
)
DARK_FROM = re.compile(r"^from-[\w]+-(?:500|600|700|800|900)$")
RENAME = {
    "glass-card": "ui-card",
    "glass-effect": "ui-card",
    "gradient-bg": "ui-icon",
    "gradient-bg-2": "ui-icon ui-icon-teal",
    "gradient-bg-3": "ui-icon ui-icon-amber",
    "gradient-bg-4": "ui-icon ui-icon-blue",
    "btn-gradient": "ui-btn",
    "btn-gradient-2": "ui-btn",
    "shimmer": "ui-tint",
}


def rewrite_classes(match):
    raw = match.group(1)
    tokens = raw.split()
    has_gradient = any(t.startswith("bg-gradient-to-") for t in tokens)
    is_dark = any(DARK_FROM.match(t) for t in tokens)

    out = []
    for token in tokens:
        if GRADIENT_TOKEN.match(token):
            continue
        for replacement in RENAME.get(token, token).split():
            if replacement not in out:
                out.append(replacement)

    if has_gradient:
        semantic = "ui-btn" if is_dark else "ui-tint"
        if semantic not in out:
            out.append(semantic)
    return 'class="' + " ".join(out) + '"'


def main():
    for path in sorted(VIEWS.glob("*.hbs")):
        text = path.read_text(encoding="utf-8")
        original = text

        # One shared design system: no per-page <style> blocks.
        text = re.sub(r"[ \t]*<style>.*?</style>\s*", "", text, flags=re.S)
        # Fonts are self-hosted by app.css; drop the Google Fonts requests.
        text = re.sub(
            r"[ \t]*<link[^>]+fonts\.googleapis\.com[^>]*>\s*",
            "",
            text,
            flags=re.I,
        )
        # Decorative gradient classes -> flat semantic classes.
        text = re.sub(r'class="([^"]*)"', rewrite_classes, text)
        # Leftover PHP short echo from the CI4 settings view.
        text = text.replace(
            "<?= old('fullname') ?: ($user->fullname ?: '') ?>",
            "{{form.fullname}}",
        )
        # Legacy Bootstrap icon markup inside a plain-text notification.
        text = text.replace("<i class='bi bi-wallet'></i> ", "")

        if text != original:
            path.write_text(text, encoding="utf-8")
            print(f"updated {path.name}")
        else:
            print(f"unchanged {path.name}")


if __name__ == "__main__":
    main()
