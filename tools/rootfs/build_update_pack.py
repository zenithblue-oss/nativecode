#!/usr/bin/env python3
"""Build the small 1.8.0 runtime update pack for existing (1.7.x) installs.

Applied by app-side Kotlin file I/O (RuntimeUpdatePack.kt); nothing in it runs in the guest.
Contents: opencode 2.2.7 (release tarball, sha256 pinned), GitHub CLI, rebuilt zsh.

  build_update_pack.py --dl DIR --zsh FILE --out DIR
DIR must hold opencode-linux-arm64.tar.gz and gh_<ver>_linux_arm64.tar.gz.
Output: OUT/nativecode_update.tgz and OUT/nativecode_update.tgz.sha256 (USTAR, root-relative paths).
"""
import argparse, gzip, hashlib, io, os, sys, tarfile

OPENCODE_SHA256 = "c1682057f0815cb2abdd6e00305f1146ebaf1c167f0335cdb74ddc80008120e7"
GH_VERSION = "2.104.0"
GH_SHA256 = "cb34cefc6f4e77b90731374a2b737516bb8f3033c6ca46aa949e5344f68069e3"
NAME = "nativecode_update.tgz"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def member_bytes(tgz, member):
    with tarfile.open(tgz, "r:gz") as t:
        f = t.extractfile(member)
        return f.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dl", required=True)
    ap.add_argument("--zsh", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    oc = os.path.join(a.dl, "opencode-linux-arm64.tar.gz")
    gh = os.path.join(a.dl, f"gh_{GH_VERSION}_linux_arm64.tar.gz")
    for path, want in ((oc, OPENCODE_SHA256), (gh, GH_SHA256)):
        got = sha256(path)
        if got != want:
            sys.exit(f"sha256 mismatch {path}: {got} != {want}")

    files = [
        ("home/flux/.opencode/bin/opencode", member_bytes(oc, "opencode")),
        ("usr/local/bin/gh", member_bytes(gh, f"gh_{GH_VERSION}_linux_arm64/bin/gh")),
        ("usr/bin/zsh-5.9", open(a.zsh, "rb").read()),
        ("usr/bin/zsh", open(a.zsh, "rb").read()),  # a plain file: the old one may be a PRoot .l2s symlink
    ]
    os.makedirs(a.out, exist_ok=True)
    out = os.path.join(a.out, NAME)
    with open(out, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0) as gz, \
            tarfile.open(fileobj=gz, mode="w", format=tarfile.USTAR_FORMAT) as t:
        for d in ("home/flux/.opencode", "home/flux/.opencode/bin"):
            ti = tarfile.TarInfo(d)
            ti.type, ti.mode = tarfile.DIRTYPE, 0o755
            t.addfile(ti)
        for name, data in files:
            ti = tarfile.TarInfo(name)
            ti.size, ti.mode = len(data), 0o755
            t.addfile(ti, io.BytesIO(data))
    digest = sha256(out)
    with open(out + ".sha256", "w") as f:
        f.write(f"{digest}  {NAME}\n")
    print(f"{out} {os.path.getsize(out)} bytes sha256={digest}")


if __name__ == "__main__":
    main()
