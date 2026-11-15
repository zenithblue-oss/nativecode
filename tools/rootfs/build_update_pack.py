#!/usr/bin/env python3
"""Build the 1.8.0 runtime update pack (Play on-demand pack `nativecode_update_core`).

The 1.8.0 `nativecode_runtime` pack is byte-identical to 1.7.1's, so Play never patches it on update.
Everything new in the 1.8.0 runtime reaches BOTH upgraded users and fresh installs through this pack,
applied by app-side Kotlin file I/O (RuntimeUpdatePack.kt); nothing in it runs in the guest.

Contents:
  - opencode 2.3.0        release tarball, sha256 pinned          -> ~/.opencode/bin/opencode
  - GitHub CLI 2.104.0    release tarball, sha256 pinned          -> /usr/local/bin/gh
  - zsh 5.9               rebuilt (see zsh_update_build.sh)       -> /usr/bin/zsh, /usr/bin/zsh-5.9
  - OpenSSH 10.6p2        files of the Alpaquita apk packages     -> /usr/bin/ssh*, /usr/sbin/sshd, /usr/lib/ssh, /etc/ssh, libedit
  - ccusage 20.0.49       @ccusage/ccusage-linux-arm64, npm sha512 -> ~/.local/share/nativecode/ccusage/ccusage

  build_update_pack.py --dl DIR --overlay update-overlay.tar --out DIR
DIR must hold opencode-linux-arm64.tar.gz, gh_<ver>_linux_arm64.tar.gz and ccusage-linux-arm64-<ver>.tgz.
update-overlay.tar comes from build_update_overlay.sh (dereferenced regular files: usr/bin/zsh-5.9 + the openssh files).
Output: OUT/nativecode_update.tgz and OUT/nativecode_update.tgz.sha256 (USTAR, root-relative paths,
regular files and directories only: the app-side extractor refuses anything else).
"""
import argparse, base64, gzip, hashlib, io, os, sys, tarfile

OPENCODE_SHA256 = "3207fb5cdec448d479242c0f1a14f4f7bdfcad3a79209b5eb8f9003725e93711"
GH_VERSION = "2.104.0"
GH_SHA256 = "cb34cefc6f4e77b90731374a2b737516bb8f3033c6ca46aa949e5344f68069e3"
CCUSAGE_VERSION = "20.0.49"  # must equal UsageModel.VERSION
# npm dist.integrity of @ccusage/ccusage-linux-arm64@20.0.49; must equal the arm64-v8a pin in UsageRepository.kt
CCUSAGE_INTEGRITY = "sha512-c1/4mP5kRNnq65HIoG6F5gvzETAYoocVuDO5N6gaP0n4vR5g58iIe2KVkoERemmKsXVWpI0cp6bsLYfTvFFmRQ=="
NAME = "nativecode_update.tgz"

CCUSAGE_PATH = "home/flux/.local/share/nativecode/ccusage/ccusage"
# folders the pack may have to create; every other parent already exists in the 1.7.1 rootfs
NEW_DIRS = (
    "home/flux/.opencode", "home/flux/.opencode/bin",
    "home/flux/.local/share/nativecode", "home/flux/.local/share/nativecode/ccusage",
    "etc/ssh", "usr/lib/ssh",
)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def member_bytes(tgz, member):
    with tarfile.open(tgz, "r:gz") as t:
        return t.extractfile(member).read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dl", required=True)
    ap.add_argument("--overlay", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    oc = os.path.join(a.dl, "opencode-linux-arm64.tar.gz")
    gh = os.path.join(a.dl, f"gh_{GH_VERSION}_linux_arm64.tar.gz")
    cc = os.path.join(a.dl, f"ccusage-linux-arm64-{CCUSAGE_VERSION}.tgz")
    for path, want in ((oc, OPENCODE_SHA256), (gh, GH_SHA256)):
        got = sha256(path)
        if got != want:
            sys.exit(f"sha256 mismatch {path}: {got} != {want}")
    cc_raw = open(cc, "rb").read()
    got = "sha512-" + base64.b64encode(hashlib.sha512(cc_raw).digest()).decode()
    if got != CCUSAGE_INTEGRITY:
        sys.exit(f"npm integrity mismatch {cc}: {got} != {CCUSAGE_INTEGRITY}")

    files = [
        ("home/flux/.opencode/bin/opencode", member_bytes(oc, "opencode"), 0o755),
        ("usr/local/bin/gh", member_bytes(gh, f"gh_{GH_VERSION}_linux_arm64/bin/gh"), 0o755),
        (CCUSAGE_PATH, member_bytes(cc, "package/bin/ccusage"), 0o755),
    ]
    with tarfile.open(a.overlay, "r") as ov:
        for m in ov:
            if m.isdir():
                continue
            if not m.isreg():
                sys.exit(f"overlay member is not a regular file: {m.name}")
            name = m.name[2:] if m.name.startswith("./") else m.name
            data = ov.extractfile(m).read()
            if name == "usr/bin/zsh-5.9":
                files.append((name, data, 0o755))
                files.append(("usr/bin/zsh", data, 0o755))  # a plain file: the old one may be a PRoot .l2s symlink
            else:
                files.append((name, data, m.mode & 0o755 | 0o644))
    names = [n for n, _, _ in files]
    if len(set(names)) != len(names):
        sys.exit("duplicate path in the update pack")
    for need in ("usr/sbin/sshd", "usr/bin/ssh", "usr/bin/ssh-keygen", "usr/bin/zsh", "usr/lib/libedit.so.0"):
        if need not in names:
            sys.exit(f"overlay is missing {need}")

    os.makedirs(a.out, exist_ok=True)
    out = os.path.join(a.out, NAME)
    with open(out, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0) as gz, \
            tarfile.open(fileobj=gz, mode="w", format=tarfile.USTAR_FORMAT) as t:
        for d in NEW_DIRS:
            ti = tarfile.TarInfo(d)
            ti.type, ti.mode = tarfile.DIRTYPE, 0o755
            t.addfile(ti)
        for name, data, mode in sorted(files):
            if len(name) > 100:
                sys.exit(f"path too long for USTAR name field: {name}")
            ti = tarfile.TarInfo(name)
            ti.size, ti.mode = len(data), mode
            t.addfile(ti, io.BytesIO(data))
    digest = sha256(out)
    with open(out + ".sha256", "w") as f:
        f.write(f"{digest}  {NAME}\n")
    print(f"{out} {os.path.getsize(out)} bytes sha256={digest} files={len(files)}")


if __name__ == "__main__":
    main()
