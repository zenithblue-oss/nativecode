#!/usr/bin/env python3
"""Assemble the F-Droid bootstrap from termux-packages .debs (run as python3 -I).

  assemble_bootstrap.py --debs DIR --app-id ID --list FILE --stage DIR
                        [--jnilibs DIR] [--tar FILE] [--sde EPOCH]

Roots = packages in FILE; runtime closure resolved from each deb's Depends.
Every deb must install under /data/data/<app-id>/files/ (prefix check).
--jnilibs: usr/bin/proot->libproot.so, usr/bin/bash->libbash.so,
           usr/libexec/proot/loader->libloader.so, termux-exec->libtermux-exec.so
--tar:     deterministic `tar usr` (same layout as the Play bootstrap.tar)
"""
import argparse, io, os, re, shutil, sys, tarfile

def read_ar(path):
    out = {}
    with open(path, "rb") as f:
        assert f.read(8) == b"!<arch>\n", path
        while True:
            h = f.read(60)
            if len(h) < 60: break
            name, size = h[:16].decode().strip().rstrip("/"), int(h[48:58])
            out[name] = f.read(size)
            if size % 2: f.read(1)
    return out

def control(ar):
    name = next(n for n in ar if n.startswith("control.tar"))
    with tarfile.open(fileobj=io.BytesIO(ar[name])) as t:
        txt = t.extractfile(next(m for m in t.getmembers() if m.name in ("./control", "control"))).read().decode()
    return {k: v for k, v in re.findall(r"^([A-Za-z-]+): (.*(?:\n .*)*)", txt, re.M)}

def index(debs):
    idx = {}
    for fn in sorted(os.listdir(debs), key=lambda f: os.path.getmtime(os.path.join(debs, f))):
        if fn.endswith(".deb"):
            p = os.path.join(debs, fn); c = control(read_ar(p))
            idx[c["Package"]] = (p, c)   # newest mtime wins
    return idx

def deps(c):
    for field in ("Depends", "Pre-Depends"):
        for part in c.get(field, "").replace("\n", " ").split(","):
            part = part.strip().split("|")[0].strip()
            if part: yield re.sub(r"\s*\(.*\)", "", part)

EXCLUDE = re.compile(r"^usr/(include/|share/(man|doc|info|aclocal|pkgconfig)/|lib/(pkgconfig|cmake)/)|\.a$")

def main():
    a = argparse.ArgumentParser()
    a.add_argument("--debs", required=True); a.add_argument("--app-id", required=True)
    a.add_argument("--list", required=True); a.add_argument("--stage", required=True)
    a.add_argument("--jnilibs"); a.add_argument("--tar"); a.add_argument("--loader", help="loader.apk to place at usr/libexec/termux-x11/"); a.add_argument("--sde", type=int, default=0)
    o = a.parse_args()
    base = f"./data/data/{o.app_id}/files/"
    idx = index(o.debs)
    roots = [l.split("#")[0].strip() for l in open(o.list)]
    todo, seen = [r for r in roots if r], {}
    while todo:
        n = todo.pop()
        if n in seen: continue
        if n not in idx: sys.exit(f"missing deb for {n}")
        seen[n] = idx[n]; todo += list(deps(idx[n][1]))
    shutil.rmtree(o.stage, ignore_errors=True); os.makedirs(o.stage)
    for n, (p, c) in sorted(seen.items()):
        ar = read_ar(p)
        with tarfile.open(fileobj=io.BytesIO(ar[next(k for k in ar if k.startswith("data.tar"))])) as t:
            ms = [m for m in t.getmembers() if m.name not in (".", "./")]
            bad = [m.name for m in ms if not (m.name + "/").startswith(base) and not base.startswith(m.name + "/")]
            if bad: sys.exit(f"{n}: files outside {base}: {bad[:3]}")
            t.extractall(o.stage, members=ms, filter="tar")
    files = os.path.join(o.stage, base)
    print(f"[*] {len(seen)} packages: {' '.join(sorted(seen))}")
    if o.jnilibs:
        os.makedirs(o.jnilibs, exist_ok=True)
        te = next((f for f in ("usr/lib/libtermux-exec.so",) if os.path.exists(os.path.join(files, f))), None)
        for src, dst in (("usr/bin/proot", "libproot.so"), ("usr/bin/bash", "libbash.so"),
                         ("usr/libexec/proot/loader", "libloader.so"), (te, "libtermux-exec.so")):
            s = os.path.realpath(os.path.join(files, src)) if src else None
            if not s or not os.path.isfile(s): sys.exit(f"jniLib source missing: {src}")
            shutil.copy2(s, os.path.join(o.jnilibs, dst)); os.chmod(os.path.join(o.jnilibs, dst), 0o755)
    if o.tar:
        if o.loader:
            dst = os.path.join(files, "usr/libexec/termux-x11/loader.apk")
            os.makedirs(os.path.dirname(dst), exist_ok=True); shutil.copy2(o.loader, dst)
        for need in ("usr/bin/bash", "usr/bin/proot", "usr/bin/proot-distro", "usr/bin/python", "usr/bin/tar",
                     "usr/bin/xz", "usr/bin/curl", "usr/libexec/proot/loader", "usr/libexec/termux-x11/loader.apk"):
            if not os.path.lexists(os.path.join(files, need)): sys.exit(f"bootstrap missing {need}")
        os.makedirs(os.path.dirname(os.path.abspath(o.tar)), exist_ok=True)
        with tarfile.open(o.tar, "w", format=tarfile.GNU_FORMAT) as t:
            for dp, dn, fn in os.walk(os.path.join(files, "usr")):
                dn.sort()
                for name in sorted(fn + dn):
                    full = os.path.join(dp, name); rel = os.path.relpath(full, files)
                    if EXCLUDE.search(rel + ("/" if os.path.isdir(full) and not os.path.islink(full) else "")): continue
                    ti = t.gettarinfo(full, rel); ti.uid = ti.gid = 0; ti.uname = ti.gname = ""; ti.mtime = o.sde
                    if ti.isreg():
                        with open(full, "rb") as f: t.addfile(ti, f)
                    else: t.addfile(ti)
        print(f"[*] wrote {o.tar} ({os.path.getsize(o.tar)/1048576:.1f} MiB)")

if __name__ == "__main__": main()
