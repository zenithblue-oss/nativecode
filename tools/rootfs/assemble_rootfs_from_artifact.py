#!/usr/bin/env python3
"""Assemble the 1.8.0 runtime rootfs from the previous verified artifact when no
aarch64 container/qemu host is available (build_alpaquita_rootfs.sh needs one).

Streams the old alpaquita_rootfs.tar.zst and swaps only the AI CLI trees, gh, zsh and a few
managed files; every other byte is copied verbatim. All replaced pieces are real aarch64 builds:
  - CLIs installed by their own installers in a scratch HOME on a device guest (new_home.tar)
  - opencode 2.3.0 from the pinned GitHub release tarball -> ~/.opencode/bin
  - gh from the pinned release tarball, zsh rebuilt with BROKEN_KILL_ESRCH / SIGSUSPEND forced off

  assemble_rootfs_from_artifact.py --old OLD.tar.zst --new-home new_home.tar --dl DIR \
      --zsh FILE --update-pack-sha SHA --out DIR [--level 19]
"""
import argparse, hashlib, io, os, subprocess, sys, tarfile, time

OPENCODE_SHA256 = "3207fb5cdec448d479242c0f1a14f4f7bdfcad3a79209b5eb8f9003725e93711"
GH_VERSION = "2.104.0"
GH_SHA256 = "cb34cefc6f4e77b90731374a2b737516bb8f3033c6ca46aa949e5344f68069e3"
HOME = "./home/flux"
SCRATCH_HOME = "/tmp/ncb/home"
# old CLI trees, replaced wholesale (the directory entries themselves are kept)
DROP_PREFIXES = (
    f"{HOME}/.local/bin/",
    f"{HOME}/.local/lib/node_modules/",
    f"{HOME}/.local/share/claude/",
    f"{HOME}/.grok/",
)
CLI_ENV = (
    "# Managed by NativeCode prebuilt rootfs\n"
    'export PATH="$HOME/.opencode/bin:$HOME/.local/bin:$HOME/.grok/bin:$HOME/bin:$HOME/.cargo/bin:$PATH"\n'
)
NPMRC = "prefix=/home/flux/.local\n"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def tgz_member(tgz, member):
    with tarfile.open(tgz, "r:gz") as t:
        return t.extractfile(member).read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--old", required=True)
    ap.add_argument("--new-home", required=True)
    ap.add_argument("--dl", required=True)
    ap.add_argument("--zsh", required=True)
    ap.add_argument("--update-pack-sha", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--level", default="19")
    a = ap.parse_args()

    oc_tgz = os.path.join(a.dl, "opencode-linux-arm64.tar.gz")
    gh_tgz = os.path.join(a.dl, f"gh_{GH_VERSION}_linux_arm64.tar.gz")
    for path, want in ((oc_tgz, OPENCODE_SHA256), (gh_tgz, GH_SHA256)):
        if sha256(path) != want:
            sys.exit(f"sha256 mismatch: {path}")
    opencode = tgz_member(oc_tgz, "opencode")
    gh = tgz_member(gh_tgz, f"gh_{GH_VERSION}_linux_arm64/bin/gh")
    zsh = open(a.zsh, "rb").read()

    os.makedirs(a.out, exist_ok=True)
    out_path = os.path.join(a.out, "alpaquita_rootfs.tar.zst")
    now = int(time.time())
    build_id = time.strftime("%Y.%m.%d.1", time.gmtime(now))

    dec = subprocess.Popen(["zstd", "-dc", "--long=31", a.old], stdout=subprocess.PIPE)
    enc = subprocess.Popen(["zstd", f"-{a.level}", "-T0", "-q", "-f", "-o", out_path], stdin=subprocess.PIPE)
    tin = tarfile.open(fileobj=dec.stdout, mode="r|")
    tout = tarfile.open(fileobj=enc.stdin, mode="w|", format=tarfile.GNU_FORMAT, bufsize=1 << 20)

    seen_dirs = set()
    flux_names = {"uname": "", "gname": ""}
    replaced, dropped = [], 0

    def clone(m, data):
        ti = tarfile.TarInfo(m.name)
        ti.mode, ti.uid, ti.gid, ti.uname, ti.gname, ti.mtime = m.mode, m.uid, m.gid, m.uname, m.gname, now
        ti.size = len(data)
        return ti

    def emit_bytes(name, data, mode, mtime=now):
        ti = tarfile.TarInfo(name)
        ti.size, ti.mode, ti.uid, ti.gid, ti.mtime = len(data), mode, 1000, 1000, mtime
        ti.uname, ti.gname = flux_names["uname"], flux_names["gname"]
        tout.addfile(ti, io.BytesIO(data))

    def emit_dir(name, mode=0o755):
        if name in seen_dirs:
            return
        seen_dirs.add(name)
        ti = tarfile.TarInfo(name)
        ti.type, ti.mode, ti.uid, ti.gid, ti.mtime = tarfile.DIRTYPE, mode, 1000, 1000, now
        ti.uname, ti.gname = flux_names["uname"], flux_names["gname"]
        tout.addfile(ti)

    for m in tin:
        if m.name == f"{HOME}/.local" and m.isdir():
            flux_names = {"uname": m.uname, "gname": m.gname}
        if m.isdir():
            seen_dirs.add(m.name)
        if any(m.name.startswith(p) for p in DROP_PREFIXES):
            dropped += 1
            continue
        if m.name == "./usr/local/bin/gh":
            tout.addfile(clone(m, gh), io.BytesIO(gh)); replaced.append(m.name); continue
        if m.name == "./usr/bin/zsh-5.9":
            tout.addfile(clone(m, zsh), io.BytesIO(zsh)); replaced.append(m.name); continue
        if m.name == f"{HOME}/.config/fluxlinux/cli-tools.env":
            data = CLI_ENV.encode()
            tout.addfile(clone(m, data), io.BytesIO(data)); replaced.append(m.name); continue
        if m.name == "./etc/nativecode/rootfs-release":
            text = tin.extractfile(m).read().decode()
            lines = [l for l in text.splitlines() if not l.startswith(("BUILD_ID=", "RUNTIME_REV="))]
            lines.insert(5, f"BUILD_ID={build_id}")
            lines.append("RUNTIME_REV=1.8.0")
            data = ("\n".join(lines) + "\n").encode()
            tout.addfile(clone(m, data), io.BytesIO(data)); replaced.append(m.name); continue
        if m.isreg():
            tout.addfile(m, tin.extractfile(m))
        else:
            tout.addfile(m)

    # new CLI trees from the device-built scratch HOME
    with tarfile.open(a.new_home, "r|") as tn:
        for m in tn:
            if m.name == "." or m.name == "./":
                continue
            rel = m.name[2:] if m.name.startswith("./") else m.name
            name = f"{HOME}/{rel}"
            if m.isdir():
                emit_dir(name, m.mode & 0o7777 or 0o755)
                continue
            ti = tarfile.TarInfo(name)
            ti.mode, ti.uid, ti.gid, ti.mtime = m.mode & 0o7777, 1000, 1000, m.mtime
            ti.uname, ti.gname = flux_names["uname"], flux_names["gname"]
            if m.issym():
                ti.type, ti.linkname = tarfile.SYMTYPE, m.linkname.replace(SCRATCH_HOME, "/home/flux")
                tout.addfile(ti)
            elif m.isreg():
                ti.size = m.size
                tout.addfile(ti, tn.extractfile(m))
            else:
                sys.exit(f"unexpected member type in new home: {m.name} {m.type!r}")

    emit_dir(f"{HOME}/.opencode")
    emit_dir(f"{HOME}/.opencode/bin")
    emit_bytes(f"{HOME}/.opencode/bin/opencode", opencode, 0o755)
    emit_bytes(f"{HOME}/.npmrc", NPMRC.encode(), 0o644)
    emit_bytes("./etc/nativecode/update-pack-1.8.0.sha256", (a.update_pack_sha + "\n").encode(), 0o644, 0)

    tout.close()
    enc.stdin.close()
    if enc.wait() != 0 or dec.wait() != 0:
        sys.exit("zstd failed")
    digest = sha256(out_path)
    with open(out_path + ".sha256", "w") as f:
        f.write(f"{digest}  alpaquita_rootfs.tar.zst\n")
    print(f"dropped {dropped} old CLI entries; replaced {replaced}")
    print(f"{out_path} {os.path.getsize(out_path)} bytes sha256={digest} build_id={build_id}")


if __name__ == "__main__":
    main()
