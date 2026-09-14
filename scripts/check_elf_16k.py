#!/usr/bin/env python3
"""Fail if any ELF64 PT_LOAD alignment is below 16 KB. ELF32 is always a fail."""
from __future__ import annotations

import os
import struct
import sys
import zipfile

PT_LOAD = 1
MIN_ALIGN = 0x4000


def elf_min_load(data: bytes) -> tuple[int, int] | None:
    if data[:4] != b"\x7fELF":
        return None
    ei_class = data[4]
    if ei_class == 2:
        e_phoff = struct.unpack_from("<Q", data, 32)[0]
        e_phentsize = struct.unpack_from("<H", data, 54)[0]
        e_phnum = struct.unpack_from("<H", data, 56)[0]
        fmt = "<IIQQQQQQ"
    elif ei_class == 1:
        e_phoff = struct.unpack_from("<I", data, 28)[0]
        e_phentsize = struct.unpack_from("<H", data, 42)[0]
        e_phnum = struct.unpack_from("<H", data, 44)[0]
        fmt = "<IIIIIIII"
    else:
        return None
    aligns: list[int] = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        chunk = data[off : off + e_phentsize]
        if len(chunk) < e_phentsize:
            break
        if ei_class == 2:
            p_type, _, _, _, _, _, _, p_align = struct.unpack_from(fmt, chunk)
        else:
            p_type, _, _, _, _, _, _, p_align = struct.unpack_from(fmt, chunk)
        if p_type == PT_LOAD:
            aligns.append(p_align)
    return ei_class, min(aligns) if aligns else 0


def check_blob(label: str, data: bytes) -> str | None:
    info = elf_min_load(data)
    if info is None:
        return None
    ei_class, min_align = info
    if ei_class != 2:
        return f"{label}: ELF{32 if ei_class == 1 else ei_class} (not 64-bit)"
    if min_align < MIN_ALIGN:
        return f"{label}: LOAD align {hex(min_align)} < 0x4000"
    return None


def walk_path(path: str) -> list[str]:
    bad: list[str] = []
    if path.endswith(".apk") or path.endswith(".aar") or path.endswith(".zip"):
        with zipfile.ZipFile(path) as z:
            for name in z.namelist():
                if name.endswith(".so") or "/lib/" in name:
                    err = check_blob(f"{path}::{name}", z.read(name))
                    if err:
                        bad.append(err)
        return bad
    if os.path.isfile(path):
        with open(path, "rb") as fh:
            err = check_blob(path, fh.read())
        return [err] if err else []
    for root, _, files in os.walk(path):
        for fn in files:
            if not fn.endswith(".so"):
                continue
            p = os.path.join(root, fn)
            with open(p, "rb") as fh:
                err = check_blob(p, fh.read())
            if err:
                bad.append(err)
    return bad


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: check_elf_16k.py PATH [PATH...]", file=sys.stderr)
        return 2
    bad: list[str] = []
    for arg in sys.argv[1:]:
        bad.extend(walk_path(arg))
    if bad:
        print("16 KB ELF alignment FAILED:")
        for line in bad:
            print(" ", line)
        return 1
    print("16 KB ELF alignment OK:", " ".join(sys.argv[1:]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
