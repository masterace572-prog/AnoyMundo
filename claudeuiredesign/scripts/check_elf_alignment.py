#!/usr/bin/env python3
"""Check ELF PT_LOAD alignment for Android 16 KB page-size devices.

Android 15+ devices may boot with 16 KB memory pages. The dynamic linker
refuses to dlopen() any ELF file whose PT_LOAD p_align is smaller than the
device page size, with an error such as::

    dlopen failed: "libbgmi.so" program alignment (4096) cannot be smaller
    than system page size (16384)

That looks exactly like "the lib did not load / the menu did not show up".

Works on a bare .so, a directory of .so files, or any ZIP container
(APK / AAB / AAR / JAR / loader ZIP). Standard library only.

Examples::

    python3 scripts/check_elf_alignment.py my-libbgmi.so
    python3 scripts/check_elf_alignment.py ../dist/Anoy-XLOX-loader.apk
    python3 scripts/check_elf_alignment.py --fail-on-unaligned xlox-loader.zip

Exit status is 1 when --fail-on-unaligned is set and any ELF is below the
minimum alignment, otherwise 0.
"""
from __future__ import annotations

import argparse
import glob
import os
import struct
import sys
import zipfile

PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
ZIP_SUFFIXES = (".apk", ".aab", ".aar", ".jar", ".zip")


def unpack(fmt: str, data: bytes, offset: int):
    return struct.unpack_from(fmt, data, offset)[0]


def load_alignments(data: bytes):
    """Return (is64, [(p_align, off_mod, vaddr_mod) for each PT_LOAD]) or None."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    endian = "<" if data[5] == 1 else ">"
    if data[5] not in (1, 2):
        return None
    try:
        if is64:
            phoff = unpack(endian + "Q", data, 0x20)
            phentsize = unpack(endian + "H", data, 0x36)
            phnum = unpack(endian + "H", data, 0x38)
        else:
            phoff = unpack(endian + "I", data, 0x1C)
            phentsize = unpack(endian + "H", data, 0x2A)
            phnum = unpack(endian + "H", data, 0x2C)
    except struct.error:
        return None
    min_entry = 56 if is64 else 32
    if not phoff or phentsize < min_entry or not phnum or phnum > 4096:
        return None
    if phoff + phentsize * phnum > len(data):
        return None

    out = []
    for i in range(phnum):
        at = phoff + i * phentsize
        p_type = unpack(endian + "I", data, at)
        if p_type != PT_LOAD:
            continue
        if is64:
            p_offset = unpack(endian + "Q", data, at + 8)
            p_vaddr = unpack(endian + "Q", data, at + 16)
            p_align = unpack(endian + "Q", data, at + 48)
        else:
            p_offset = unpack(endian + "I", data, at + 4)
            p_vaddr = unpack(endian + "I", data, at + 8)
            p_align = unpack(endian + "I", data, at + 28)
        out.append((p_align, p_offset, p_vaddr))
    return is64, out


def describe(data: bytes, minimum: int):
    parsed = load_alignments(data)
    if parsed is None:
        return "NOT-ELF", []
    is64, loads = parsed
    if not loads:
        return "NO-PT-LOAD", []
    worst = min(a for a, _, _ in loads)
    notes = []
    if not is64:
        notes.append("32-bit ABI (not subject to the 16 KB requirement)")
    # A binary cannot be fixed by simply rewriting p_align when its file
    # offsets and virtual addresses are not congruent modulo 16 KB.
    bad_offsets = sum(1 for _, off, va in loads
                      if (off - va) % minimum != 0)
    if bad_offsets and worst < minimum:
        notes.append(f"{bad_offsets} LOAD segment(s) not congruent mod {minimum}; "
                     "must be relinked, not patched")
    status = "ALIGNED" if worst >= minimum or not is64 else "UNALIGNED"
    notes.insert(0, f"min p_align=0x{worst:x} ({worst} bytes), {len(loads)} LOAD segment(s)")
    return status, notes


def iter_elfs(paths):
    for path in paths:
        if os.path.isdir(path):
            for found in sorted(glob.glob(os.path.join(path, "**", "*.so"), recursive=True)):
                with open(found, "rb") as handle:
                    yield found, handle.read()
            continue
        lower = path.lower()
        if lower.endswith(ZIP_SUFFIXES) and zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as archive:
                for name in archive.namelist():
                    if name.endswith(".so"):
                        yield f"{path}!{name}", archive.read(name)
            continue
        if os.path.isfile(path):
            with open(path, "rb") as handle:
                yield path, handle.read()
        else:
            print(f"  MISSING  {path}", file=sys.stderr)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("paths", nargs="+", help=".so files, directories, or APK/ZIP containers")
    parser.add_argument("--min-align", type=int, default=16384,
                        help="required PT_LOAD alignment (default: 16384)")
    parser.add_argument("--fail-on-unaligned", action="store_true",
                        help="exit 1 when any 64-bit ELF is below --min-align")
    args = parser.parse_args()

    unaligned = []
    for label, data in iter_elfs(args.paths):
        status, notes = describe(data, args.min_align)
        print(f"  {status:10s} {label}")
        for note in notes:
            print(f"              {note}")
        if status == "UNALIGNED":
            unaligned.append(label)

    print(f"\n{len(unaligned)} unaligned 64-bit ELF file(s) "
          f"(required p_align >= {args.min_align} / 0x{args.min_align:x}).")
    if unaligned:
        print("Fix: rebuild them with -Wl,-z,max-page-size=16384 "
              "-Wl,-z,common-page-size=16384 (or NDK r28+).")
    return 1 if (unaligned and args.fail_on_unaligned) else 0


if __name__ == "__main__":
    sys.exit(main())
