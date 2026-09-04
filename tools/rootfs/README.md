# Prebuilt Alpine rootfs (3.24 / aarch64)

One rootfs for PRoot (`proot-distro install … --name alpine`) and rooted
chroot (`setup_alpine_chroot.sh`). First boot runs setup phases E/F/GUI/G/H
as **runtime repair only** — no package downloads for baked components.

## Layout

- `versions.env` — pinned versions (Alpine 3.24.1, Node 24, SDK 36,
  build-tools 36.1.0, NDK r29, Kotlin 2.1.0, Gradle 9.2.1, cmdline-tools 15859902)
- `packages-alpine-base.txt` — base/E/desktop/GUI/F/G + AI runtime + github-cli
- `packages-alpine-dev.txt` — general/web dev (no Flutter, no gtk+3.0-dev)
- `rootfs-common.sh` — baked to `/usr/local/lib/nativecode/rootfs-common.sh`
- `build_alpine_rootfs.sh` — download minirootfs → guest assembly → marker
- `verify_rootfs.sh` — offline verifier (binaries, SDK paths, scrub)
- `clean_rootfs.sh` — credential scrub gate (fails on leak)
- `measure_rootfs.sh` — `du` report + `tar.zst` + 1.45 GiB gate

## Build

```sh
tools/rootfs/build_alpine_rootfs.sh --work /tmp/nc-rootfs --out out/
# then run guest assembly inside rootfs (see script output), then finalize
```

Never archive an interactively used phone rootfs. Pin SHA256 in
`versions.env` before release. See worker plan
`/home/user/Downloads/nativecode-alpine-prepackaged-rootfs-worker-plan.md`
for full contract (§2–§21).
