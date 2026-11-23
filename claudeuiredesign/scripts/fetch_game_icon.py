#!/usr/bin/env python3
"""
2.3.1: bundle the BGMI game icon once.

Downloads the game's icon from Wikipedia (316 x 316 WebP), converts it to a
224 x 224 PNG (56 dp at xxxhdpi) and writes it to
app/src/main/res/drawable-nodpi/ic_game_bgmi.png. The app never loads it at
runtime. If the PNG is already in the tree this does nothing, so after the
first CI build commits it the network is not needed again.

Run from xlox/:  python3 scripts/fetch_game_icon.py
"""
import io
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

URL = "https://upload.wikimedia.org/wikipedia/en/6/63/Battleground_Mobile_India.webp"
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/res/drawable-nodpi/ic_game_bgmi.png"
SIZE = 224


UA = "AnoyBuild/2.3.1 (https://github.com/masterace572-prog/AnoyMundo; ci@users.noreply.github.com) python-urllib"
# The same file: the original, then Wikimedia's own resize of it.
SOURCES = [
    URL,
    "https://upload.wikimedia.org/wikipedia/en/thumb/6/63/Battleground_Mobile_India.webp/316px-Battleground_Mobile_India.webp.png",
    "https://en.wikipedia.org/wiki/Special:FilePath/Battleground_Mobile_India.webp",
]


def download():
    notes = []
    for url in SOURCES:
        for attempt in range(1, 3):
            try:
                request = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "image/*,*/*"})
                with urllib.request.urlopen(request, timeout=30) as response:
                    data = response.read()
                if len(data) >= 1024:
                    print(f"downloaded {url}")
                    return data, notes
                notes.append(f"{url} -> {len(data)} bytes")
            except urllib.error.HTTPError as e:
                notes.append(f"{url} -> HTTP {e.code}")
            except Exception as e:  # noqa: BLE001
                notes.append(f"{url} -> {type(e).__name__}: {e}")
            time.sleep(2 * attempt)
        # curl as a second client (different TLS stack / headers).
        try:
            out = subprocess.run(["curl", "-sSL", "--retry", "2", "-A", UA, "-o", "-", "-w", "", url],
                                 capture_output=True, timeout=60)
            if out.returncode == 0 and len(out.stdout) >= 1024 and not out.stdout.lstrip().startswith(b"<"):
                print(f"downloaded {url} (curl)")
                return out.stdout, notes
            notes.append(f"curl {url} -> rc {out.returncode}, {len(out.stdout)} bytes {out.stderr[:120]!r}")
        except Exception as e:  # noqa: BLE001
            notes.append(f"curl {url} -> {e}")
    return b"", notes


def load_pillow():
    try:
        from PIL import Image
        return Image
    except ImportError:
        pass
    # Ubuntu 24.04 runners refuse a plain pip install (PEP 668).
    for extra in (["--user", "--break-system-packages"], ["--break-system-packages"], ["--user"]):
        r = subprocess.run([sys.executable, "-m", "pip", "install", "--quiet", *extra, "Pillow"],
                           capture_output=True, text=True)
        if r.returncode == 0:
            import importlib
            import site
            importlib.invalidate_caches()
            user_site = site.getusersitepackages()
            if user_site not in sys.path:
                sys.path.append(user_site)
            try:
                from PIL import Image
                return Image
            except ImportError:
                continue
    return None


def convert(data: bytes) -> str:
    """Writes OUT from the downloaded bytes; returns how."""
    Image = load_pillow()
    if Image is not None:
        image = Image.open(io.BytesIO(data))
        print(f"source {image.format} {image.size[0]}x{image.size[1]}")
        if image.size[0] < 128 or image.size[1] < 128:
            raise RuntimeError(f"unexpectedly small icon {image.size}")
        image = image.convert("RGBA")
        side = min(image.size)
        left = (image.size[0] - side) // 2
        top = (image.size[1] - side) // 2
        image = image.crop((left, top, left + side, top + side)).resize((SIZE, SIZE), Image.LANCZOS)
        image.save(OUT, "PNG", optimize=True)
        return "Pillow"
    # ImageMagick ships on the GitHub runners.
    src = OUT.with_suffix(".src")
    src.write_bytes(data)
    try:
        for tool in (["magick"], ["convert"]):
            try:
                r = subprocess.run([*tool, str(src), "-gravity", "center", "-resize", f"{SIZE}x{SIZE}^",
                                    "-extent", f"{SIZE}x{SIZE}", "-strip", "PNG32:" + str(OUT)],
                                   capture_output=True, text=True)
            except FileNotFoundError:
                continue
            if r.returncode == 0 and OUT.is_file():
                return "ImageMagick"
        raise RuntimeError("no Pillow and no ImageMagick to convert the WebP")
    finally:
        src.unlink(missing_ok=True)


def main() -> int:
    if OUT.is_file() and OUT.stat().st_size > 1024:
        print(f"{OUT.relative_to(ROOT)} already bundled ({OUT.stat().st_size} bytes)")
        return 0
    data, notes = download()
    if len(data) < 1024:
        # stdout, so GitHub shows it as an annotation on the run.
        print("::error::Could not download the BGMI icon: " + " | ".join(notes))
        return 1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    try:
        how = convert(data)
    except Exception as e:  # noqa: BLE001
        OUT.unlink(missing_ok=True)
        print(f"::error::Could not convert the BGMI icon: {type(e).__name__}: {e}")
        return 1
    print(f"wrote {OUT.relative_to(ROOT)} ({OUT.stat().st_size} bytes, {SIZE}x{SIZE}, {how})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
