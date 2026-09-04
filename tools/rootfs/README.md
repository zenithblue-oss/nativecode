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

## Release Pipeline Sequence

The release flow is structured in explicit stages:

1. **build/extract (`build_alpine_rootfs.sh`)** — downloads and extracts verified base minirootfs into `$ROOTFS`.
2. **ARM64 guest assembly** — runs inside the target rootfs via `chroot /rootfs /bin/sh /assemble.sh` under an ARM64 container or on a rooted device.
3. **finalize + clean (`finalize.sh`)** — places the `/etc/nativecode/rootfs-release` marker and executes credential scrub (`clean_rootfs.sh`).
4. **verify — mandatory (`verify_rootfs.sh`)** — runs offline checks against the target tree. Any failure stops the release pipeline.
5. **measure + archive (`measure_rootfs.sh`)** — generates `.tar.zst` archive and outputs `du` breakdown.
6. **SHA256 generation** — creates checksum file alongside the archive.
7. **size gate** — enforces strict 1.45 GiB compressed archive ceiling.

## Build

```sh
# 1. Download base & generate guest scripts
tools/rootfs/build_alpine_rootfs.sh --work /tmp/nc-rootfs --out out/

# 2. Run guest assembly inside rootfs (Docker ARM64 or native chroot)
docker run --rm --privileged --platform linux/arm64 \
  -v /tmp/nc-rootfs/rootfs:/rootfs \
  -v /tmp/nc-rootfs:/pkg-lists \
  -v /tmp/nc-rootfs/opt-staging:/opt-staging \
  arm64v8/alpine:3.24.1 \
  sh -c 'cp /pkg-lists/assemble-inside.sh /rootfs/assemble.sh && chmod +x /rootfs/assemble.sh && chroot /rootfs /bin/sh /assemble.sh && rm -f /rootfs/assemble.sh'

# 3 & 4. Finalize, clean, and run mandatory verification
/tmp/nc-rootfs/finalize.sh /tmp/nc-rootfs/rootfs out/

# 5, 6 & 7. Measure, archive, sha256, and verify size gate
tools/rootfs/measure_rootfs.sh /tmp/nc-rootfs/rootfs out/
```

Never archive an interactively used phone rootfs. Pin SHA256 in
`versions.env` before release. See worker plan
`/home/user/Downloads/nativecode-alpine-prepackaged-rootfs-worker-plan.md`
for full contract (§2–§21).
