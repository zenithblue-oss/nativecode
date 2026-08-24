# Plan: proot-parity-v2 — Product-level PRoot/Chroot Parity

## Task Summary

Achieve **product-level parity** between rootless PROOT and rooted chroot for the workloads NativeCode users feel, without claiming impossible ptrace parity. Targets: install time ~1.0–1.15× chroot, `true`/small command launch 80–150 ms (vs 816 ms today), interactive shell <250 ms warm, pure CPU ~95–100% chroot, pure RAM memcpy 95–100%, syscall-heavy + sequential I/O substantially improved but with honest gap. Implement via: (1) native fast rootfs extractor (one-pass SHA+decompress, transactional, atomic), (2) CI golden rootfs (pre-installed dev stack, no on-device apt), (3) direct PRoot launcher (no Python, no double shell, profiled binds), (4) shell/Node lazy init, (5) optional persistent broker, (6) fair bench suite + thermal/safety gates, (7) feature-flagged rollout. Classic ptrace cannot be made identical to chroot for 4K-metadata/GPU workloads; architecture keeps `PROOT_FAST | CHROOT | NATIVE_GLIBC(future)` backends.

## Research Sources

- <source: app/src/main/java/com/zenithblue/nativecode/terminal/ProotCommandBuilder.kt:6-36 | current PROOT path = `libbash → python proot-distro login debian --user flux -- zsh -c '...'` → double shell>
- <source: app/src/main/assets/scripts/flux_install.sh:13-261 | current install = sha256sum → python tar extraction → proot-distro login bash setup script → apt/dpkg/NVM on device>
- <source: app/src/main/assets/scripts/nativecode_proot_fast.sh:1-369 | existing fast launcher design: exec/sh/login/root-exec, profiles cli/gpu-turnip/gpu-virgl/compat, `env -i` clean env, flux UID from guest passwd>
- <source: app/src/main/java/com/zenithblue/nativecode/terminal/TermuxHostPaths.kt:9-120 | SSOT package com.zenithblue.nativecode, PREFIX/HOME/TMPDIR, fluxlinux-host.env generation, applyPackageToExtractedPrefix rewrite>
- <source: app/src/main/assets/scripts/setup_debian_family.sh:1-35 | on-device apt: `apt update && apt install sudo xfce4 xfce4-goodies dbus-x11 tigervnc git` worst-case PRoot workload>
- <source: app/src/main/assets/scripts/setup_cli_tools.sh:1-150 | NVM v0.40.5, node 26, sources nvm.sh in .zshenv/.zprofile/.zshrc → shell startup tax>
- <source: app/src/main/assets/scripts/optimize_debian_rootfs_perf.sh:1-120 | safe vs aggressive optimizations, dash check, tmp perms, apt conf 99nativecode-perf>
- <source: docs/environment/proot-vs-chroot-baseline.md:1-80 | Snapdragon baseline: sysbench 0.65×, sysbench mem 0.06×, mbw 0.90×, dd write 0.71× read 0.40×, glmark 0.32×>
- <source: docs/environment/proot-vs-chroot-perf-report-mediatek.md:1-80 | MediaTek MT6897: CPU ~1.0×, mem 0.88×, dd read 0.49× — device-dependent tax>
- <source: docs/environment/proot-fast-launcher-design.md:1-250 | launch inventory: 21 binds, measured 816 ms distro vs 104 ms plain proot vs 30 ms chroot, binds not bottleneck>
- <source: docs/environment/plans/task-01-inventory-startup.md:1-120 | Task1 gates, plain proot ~8× faster, minimal profile still slow with shell wrap>
- <source: https://proot-me.github.io/ | PRoot = ptrace-based chroot/bind/binfmt without privilege>
- <source: https://arxiv.org/pdf/2405.06085v1 — seccomp as performance optimization to avoid ptrace notification for unintercepted syscalls>
- <source: https://deepwiki.com/proot-me/proot/2.3-syscall-translation-framework | seccomp filtering system uses BPF to notify PRoot only about needed syscalls>
- <source: https://github.com/proot-me/proot — upstream ptrace implementation>
- <source: https://github.com/termux/proot / https://github.com/termux/proot-distro — Termux forks with Android patches, seccomp.h accelerator>
- <source: https://github.com/termux/termux-app/issues/5245 | Proroot LD_PRELOAD experimental zero-overhead proposal (not production ready)>
- <source: websearch PRoot ptrace seccomp 2025 | confirms proot translates syscalls through ptrace, significant I/O overhead>
- <source: git log --oneline -20 | current master d8dd6e5, prior fast launcher asset already in repo but not wired as default>

## Current Architecture

### Installation Path (PROOT vs CHROOT)

**CHROOT** `app/src/main/assets/scripts/chroot/setup_debian13_chroot.sh:15-150`:
```
APK debian_13_rootfs.tar.xz (assets/rootfs/debian_13_rootfs.tar.xz) → native tar/unxz → /data/local/tmp/chrootDebian13 .flux_configured
```
Deployed by `OnboardingActivity.kt:3290` and `MainActivity.kt:12771` via `assets.open("rootfs/debian_13_rootfs.tar.xz")` copy to $HOME.

**PROOT today** `flux_install.sh:163-261`:
```
APK → $HOME/debian_13_rootfs.tar.xz → sha256sum (full read) → python proot-distro install archive --name debian (python tar, security checks, resolv.conf fixups, UID/GID) → python proot-distro login --shared-tmp -- bash /tmp/flux_setup_temp.sh → setup_debian_family.sh apt install + setup_cli_tools.sh (NVM/node/npm 1k-file trees) on device
```
SHA is `13e29f...6803` hardcoded, ROOTFS_NAME `debian_13_rootfs.tar.xz` (xz, not zst). Two full archive reads. Container at `TermuxHostPaths.PREFIX/var/lib/proot-distro/containers/debian/rootfs` via `ProjectPathResolver.kt:16`.

### Runtime Launch Path

`ProotCommandBuilder.kt:18-31` always generates:
```kotlin
arrayOf(shell, "-c", "exec python $prootDistro login debian --user $user")
arrayOf(shell, "-c", "exec python $prootDistro login debian --user $user -- zsh -c '$escaped'")
```
`ChrootCommandBuilder.kt:33-102` uses `nativecode_chroot.sh` with base64+b64 modes, direct `sh $HELPER exec/login/b64`.

Measured launch taxonomy `docs/environment/proot-fast-launcher-design.md`:
- distro `cmd true` = 816 ms avg (596–966)
- plain proot direct `/bin/true` 5 binds = 104 ms (87–119)
- plain proot full binds = 113 ms (bind count not bottleneck)
- chroot direct = 30 ms
- distro `bash -lc exit` = 489 ms vs plain `bash -lc exit` 147 ms
Root cause: Python startup + `zsh -c "bash -lc ..."` double shell. Guest flux shell = `/bin/zsh` heavy, `/bin/sh` already dash (good).

Host env injection `HostCommandBuilder.kt:17-63` sets `PD_PROOT_BIN=libproot.so`, `PROOT_LOADER=libloader.so`, `LD_PRELOAD=libtermux-exec.so`, `PREFIX/HOME/TMPDIR/TERMUX__*`.

Existing fast launcher `nativecode_proot_fast.sh` already implements correct model: safety check package == com.zenithblue.nativecode, `load_flux_ids` from guest `/etc/passwd`, bind profiles `cli (core+storage) / gpu-turnip (+ /system,/vendor,/apex) / gpu-virgl / compat`, flags `--kill-on-exit --link2symlink -L --change-id --rootfs --cwd`, clean `env -i` with `HOME/USER/PATH/LANG/TMPDIR` + gpu vars, `exec proot $FLAGS $BINDS /usr/bin/env -i ... "$@"`. Not yet wired as default `ProotCommandBuilder` path; remains ADB asset.

### Guest Configuration

`setup_debian_family.sh` + `setup_cli_tools.sh` + `setup_hw_accel_debian.sh` + `setup_customization_debian.sh` + `optimize_debian_rootfs_perf.sh` all run *on device inside PRoot* after extraction. This drives thousands of `create/stat/rename/symlink/chmod/unlink/fsync` through ptrace. Node via NVM: `ENV_FILE=$HOME/.config/fluxlinux/cli-tools.env` sources `nvm.sh` every shell (also in `.zshenv` always loaded per zsh order), `latest=$(ls -1d $NVM_DIR/versions/node/v*)` scan per startup.

### Performance Baselines

- Snapdragon Adreno 750: stress-ng ~1.0×, mbw memcpy 0.90×, sysbench mem 0.06× (16× slower), sysbench cpu 0.65×, dd read 0.40× write 0.71×, glmark build 229 vs 725 (0.32×)
- MediaTek MT6897: cpu 1.00×, sysbench mem 0.88×, dd read 0.49× write 0.90× — confirms gaps device/kernel dependent, cannot use single sysbench number as RAM truth. `fio` fails in chroot `shmget: Function not implemented` (no SYSV IPC) so no fair 4K comparison exists.

### Safety & Persistence

`docs/environment/plans/regression-test-suite.md` + `docs/environment/chroot-adb-device-crash-postmortem.md` prohibit `glmark2-*-drm` (caused black-screen). Device-safety veto list exists per user prompt (su/mount/sysctl/dev/block/governor/killall). Terminal ownership currently fragmented: `AppTerminalService` + `ProjectTerminalService` + `BackgroundService` + `MainActivity` session lists (per `terminal_proot_optimization_plan.md`), target unified `TerminalService`.

## Affected Components & Dependencies

| Component | Files | Impact |
|-----------|-------|--------|
| **Rootfs asset & build** | `app/src/main/assets/rootfs/debian_13_rootfs.tar.xz`, `app/build.gradle.kts:androidResources noCompress xz/tar`, `assemble_bootstrap.py`, `bootstrap.tar`, `termux-packages/` | APK size, archive format choice (xz vs zst 3-6), manifest/SBOM |
| **Fast installer (Kotlin+JNI)** | `flux_install.sh`, `TermuxHostPaths.kt`, `OnboardingActivity.kt:3290`, `MainActivity.kt:12771`, `ProjectPathResolver.kt`, `ChrootSizeManager.kt` | New: `FastRootfsInstaller.kt`, `NativeRootfsExtractor` JNI (libarchive+libzstd), transactional staging `.debian.installing-<rand>/rootfs` |
| **Proot launcher** | `ProotCommandBuilder.kt`, `ChrootCommandBuilder.kt`, `LinuxCommandBuilder.kt`, `ShellCommandRunner.kt`, `nativecode_proot_fast.sh`, `TermuxHostPaths.kt` | New: `ProotFastCommandBuilder.kt`, profiles cli/package/interactive/turnip/virgl/compat |
| **CI golden rootfs** | `setup_debian_family.sh`, `setup_cli_tools.sh`, `optimize_debian_rootfs_perf.sh`, `.github/workflows/*`, `Dockerfile*` | CI pipeline builds `nativecode-debian-{core,dev,desktop}`-arm64.tar.zst with manifest |
| **Shell startup** | `setup_cli_tools.sh` env generation, guest `/home/flux/.zshenv/.zprofile/.zshrc`, `ChrootCommandBuilder.kt:247-283 launch_tool.sh` | Lazy NVM, minimal .zshenv, cached .zcompdump, dash for `sh -c` |
| **Node/npm** | `CliToolsInstaller.kt`, `ToolLauncherCatalog.kt`, `ChrootCommandBuilder.kt:308-317` | Bundled `/opt/nativecode/node-v26`, symlinks `/usr/local/bin`, lazy nvm function, npm cache on internal /data |
| **Benchmark suite** | `docs/environment/bench_quick.sh`, `bench_nongpu.sh`, `docs/environment/*perf-report*` | New: `nc-io-bench.c`, `nc-membench.c`, `bench/run-safe-suite.sh`, `bench/result-schema.json` |
| **Persistent broker** | `TerminalService.kt`, `AppTerminalService.kt`, `ProjectTerminalService.kt`, `BackgroundService.kt`, `NativeCodeFgs.kt` | Optional Unix socket broker inside app UID, session-scoped, kill-on-exit |
| **Safety/watchdog** | `DeviceRequirements.kt`, `CpuUtilizationProvider.kt`, `RootShellService.kt` | Thermal/storage/RAM checks, deny list enforcement, CID-verified kill |
| **Testing/regression** | `docs/environment/plans/regression-test-suite.md`, `docs/environment/plans/suite/` | Matrix Android 13-16 × Snapdragon/MediaTek, fresh/upgrade/interrupted/corrupt cases |
| **Dependencies** | `libproot.so` (termux/proot), `libarchive`, `libzstd` (NDK), `python proot-distro` (fallback) | Need `proot -V` + `proot -v 1` feature probe, seccomp/process_vm flags |

## Implementation Steps (ordered, smallest correct change)

### PR1 — perf/proot-benchmark-v2 — Fair measurement before any speedup (no behavior change)

1. Add `bench/nc-io-bench.c` (no SYSV IPC, no /dev/block, no root): seq-read/seq-write `pread/pwrite` 1M, rand4k-read/write/mix `prange 4KiB + PRNG`, metadata create/stat/rename/unlink N files. Single ARM64 binary runs in both guests. Dataset 128–512 MiB capped by free storage check.
2. Add `bench/nc-membench.c`: memcpy/memset/RO-scan/WO-scan/random access, tight loop with **zero syscalls** inside timed section, reports MiB/s. Document distinction: `nc-membench` = pure userspace memcpy (expected ~parity, like `mbw` 0.90×) vs `sysbench memory` = syscall/page path (shows ptrace tax, e.g. Snapdragon 0.06× vs MediaTek 0.88×); both reported, not conflated.
3. Add `bench/result-schema.json` and `bench/run-safe-suite.sh` (watchdog: check `StorageInfo` freeBytes, `MemInfo`, thermal status via `DeviceRequirements`, cancellation flag; defaults: RAM test min(256 MiB, avail/8), I/O file 256 MiB, cpu 5–10s, rand 10s; streaming only).
4. Add `docs/perf/proot-parity-v2.md` fingerprint template: commit, PRoot version (`proot -V`), `proot -v 1 /bin/true` seccomp/process_vm lines, proot-distro version, Android/kernel/SoC/filesystem/SHA/thermal. Refuse to run DRM/KMS, glmark2-drm, block device tests.
5. Wire bench into CI artifact check but do not yet gate installs. Acceptance: same binary produces results in both proot and chroot with <5% variance on repeat warm runs; `fio` removed from cross-env comparison.

### PR2 — perf/proot-fast-extractor — Transactional native extraction (install speedup, no guest changes)

6. Create `FastRootfsInstaller.kt`:
   - Inputs: `File apkAsset` (`assets/rootfs/debian_*` resolved via `OnboardingActivity` copy logic), `String expectedSha256` compiled-in (`FLUX_ROOTFS_SHA256`), `File containersDir = $PREFIX/var/lib/proot-distro/containers`.
   - State: exclusive `FileLock` on `containers/.install.lock` (close+release on all paths, `tryLock` with 30s timeout); staging dir `.debian.installing-<random>/rootfs` beneath `containers/` only; verify depth `staging.getCanonicalPath().startsWith(containersDir.canonicalPath + "/.debian.installing-")` before any delete; never `rm -rf` target directory before atomic rename.
   - Pipeline: one streaming read `compressed chunk → SHA256.update + decompressor → secure tar extraction` (buffers 64–256 KiB, no archive in RAM). Use JNI `NativeRootfsExtractor` backed by `libarchive` + `libzstd` + `liblzma` supporting **both** `.tar.xz` and `.tar.zst` (PR2 must land dual-format before PR3 needs zst). Decompressor auto-detected via magic.
   - Secure extraction: use `libarchive` flags `ARCHIVE_EXTRACT_SECURE_SYMLINKS | ARCHIVE_EXTRACT_SECURE_NODOTDOT | ARCHIVE_EXTRACT_NO_OVERWRITE` + manual checks: reject absolute paths, `..` escapes, special files (device/fifo/socket), clamp symlink resolution inside rootfs via `openat(dirfd, path, O_NOFOLLOW|O_CREAT)` + write through directory fds, symlink race defense (do not follow extracted symlink when creating next entry; `fstatat` + `readlinkat`驗證). On-disk symlink target validated `!target.startsWith("/") && !target.contains("..")` relative to dirfd.
   - SHA streaming note: decompression+extraction proceeds to staging while SHA accumulates, but **commit is gated** — if `SHA != expected`, delete staging uncommitted, never rename; staging file presence does not mean trusted. Reject on mismatch before `fsync`/commit. Security of extraction flags protects staging even before SHA gate.
   - Post-extraction fixups (reuse distro logic): `resolv.conf` from host, `/etc/hosts`, Android UID/GID registration for `flux` (parse `getAppUid` → write `/etc/passwd`/`/group`), fake system data, `sys_empty` etc. Verify `rootfs/bin/sh` exists. Do **not** bake flux UID in CI; injector writes correct per-device UID at commit time (see PR3).
   - Atomic commit: `fsync` staging tree (`syncfs` on dest fd), write marker `staging/.install_complete`, then **atomic swap without `rm -rf` window**: `rename(containers/debian, containers/debian.old.<ts>)` if exists (backup), `rename(stagingParent, containers/debian)` (stagingParent = `.debian.installing-<rand>`), `fsync` parent dir, then delete `debian.old.*`. If first rename fails (no existing), second rename is sole atomic publish. Failure at any point before final rename → delete staging only, existing `containers/debian` untouched, offer fallback `python proot-distro install` (for `FLUX_ROOTFS_PATH` user-supplied tarballs). Parent dir fsync ensures directory entry durable.
   - Accept only trusted app-packaged artifact with compiled SHA; generic `FLUX_ROOTFS_PATH` continues via upstream python path. Verify `ROOTFS_ARCHIVE` canonical path is under `$HOME` or `$PREFIX/var/lib/...` or explicit allowlist; never operate on `/` or `/data/local/tmp` chroot path.

7. JNI layer `app/src/main/cpp/native_rootfs_extractor.cpp` + `NativeRootfsExtractor.kt`: `init(archivePath, destStaging, expectedSha) -> {shaOk, bytesExtracted, error}` streaming, progress callback for UI (`onProgress(bytes)`). **Build plumbing**: `app/src/main/cpp/CMakeLists.txt` (static `libarchive.a` + `libzstd` + `liblzma` prebuilt under `app/src/main/cpp/third_party/`), `app/build.gradle.kts` add `ndkVersion = "26.1.10909125"` + `android { externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } } defaultConfig { ndk { abiFilters += "arm64-v8a" } externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static") } } } }`, `packaging.jniLibs.useLegacyPackaging true` already present. Only arm64-v8a (matches existing `libproot.so`/`libbash.so` arm64-v8a). Fallback to python path if `System.loadLibrary("native_rootfs_extractor")` fails.

8. Integrate into `OnboardingActivity.kt` and `MainActivity.kt` install flows behind flag `proot_fast_install`. Add fallback switch one tap. Add `ProjectPathResolver` helper for `stagingDir()` + `isInstalling()` + `cleanStaleStaging()`. **Stale GC**: on every install start, list `containers/.debian.installing-*` and delete those where `elapsedRealtime` since `dir.lastModified()` > 1h **or** `boot_id` (`/proc/sys/kernel/random/boot_id`) changed since `staging/.boot_id` marker written at creation; do not rely on wall-clock alone. Add tests: zip-slip, symlink escape, absolute path, device file rejection, wrong SHA, corrupt archive, app killed during extraction (process death → staging remains, `FileLock` released, next install `cleanStaleStaging` removes), low storage.

9. Keep `flux_install.sh` for legacy `FLUX_ROOTFS_PATH` path; do not delete.

### PR3 — perf/prebuilt-debian-dev-rootfs — Eliminate on-device apt (largest install-time win)

10. Create CI workflow `.github/workflows/build-golden-rootfs.yml`:
    - Runner: **native ARM64** (`ubuntu-24.04-arm` GitHub hosted) — not qemu (qemu too slow for GitHub 10m timeout). Fallback self-hosted ARM if unavailable. Base: `debian:13` (trixie) container via `run-on-arch` only for non-arm fallback, but prefer native.
    - Install once: `bash/dash/zsh sudo git curl wget ca-certificates python3 build-essential gcc g++ make ninja cmake pkg-config ccache unzip locales` + `apt clean` hygiene.
    - Node 26: extract official `node-v26-linux-arm64.tar.xz` to **`/opt/nativecode/node-v26`** (canonical; unify with PR4 `buildGuestPath` — not `/opt/nodejs`), symlink `/usr/local/bin/node|npm|npx -> /opt/nativecode/node-v26/bin/*`.
    - Apply `optimize_debian_rootfs_perf.sh --safe` as source material: `/etc/apt/apt.conf.d/99nativecode-perf` (Install-Recommends false, Acquire::Languages none), `policy-rc.d` exit 101, `C.UTF-8` locale, `ldconfig`, `update-ca-certificates`, `/bin/sh → dash` check, tmp perms 1777.
    - Generate shell speed: minimal `.zshenv` (env only), `.zcompdump` prebuilt, no plugin-update scans.
    - Cleanup: `apt clean; rm -rf /var/lib/apt/lists/* /var/cache/* /tmp/* /var/log/*; rm -f /etc/machine-id; dpkg --audit; verify ELF arch aarch64; symlink safety scan; create manifest`.
    - Pack: `tar --sort-name --owner=0 --group=0 --mtime=@<epoch> -I "zstd -6 -T0" -cf nativecode-debian-dev-arm64.tar.zst -C rootfs .` + also `.tar.xz` fallback for compatibility (upstream `proot-distro` recognizes `.tar.zst`). **Do not bake flux UID/GID**; leave placeholder `flux:x:1000:1000` to be overwritten by installer per-device.
    - Outputs: `nativecode-debian-{core,dev,desktop}-arm64.tar.zst`, `rootfs-manifest.json`, `rootfs-files.sha256`, `SBOM.txt`, `SHA256SUMS`. Upload as GitHub Release artifacts; **APK inclusion**: only `nativecode-debian-core-arm64.tar.zst` in APK (`app/src/main/assets/rootfs/`), `dev` (default onboarding) and `desktop` as separate Release downloads fetched on-demand (or bundled only for internal test builds). Keeps APK <150 MB vs 300 MB if dev bundled.
    - Profiles: `core` (Debian+bash/dash/zsh+SSL+git/curl) in APK, `dev` = core + toolchain+Ninja+ccache+Node (default onboarding, fetched from Release if not in APK), `desktop` = dev + XFCE+Mesa. AI tools payload optional separate layer.

11. Update `app/src/main/assets/rootfs/` pipeline: `assemble_bootstrap.py` consumes CI zst artifact (core in APK); `build.gradle.kts` `noCompress += listOf("zst","xz")` (already has xz/tar; add zst). Benchmark on device before final choice: same manifest packed as `.tar.xz` vs `.tar.zst` levels 3–6 vs `.tar` → table `APK size | decompress time | install time`; PR2 dual-format must land before PR3 benchmark winner decision. Expect zst production winner but keep xz fallback.

12. Adjust `setup_debian_family.sh` / `setup_cli_tools.sh` to become no-ops when marker `/etc/nativecode-golden` present; device fixups remain only tiny UID/hostname/resolv steps performed by fast installer.

### PR4 — perf/proot-fast-launcher — Direct exec, no Python, no double shell

13. Create `ProotFastCommandBuilder.kt` (Kotlin analog of `nativecode_proot_fast.sh`):
    - Safety: refuse `com.termux` PREFIX/ROOTFS, require `TERMUX_APP__PACKAGE_NAME == com.zenithblue.nativecode`, check `libproot.so` + `ROOTFS` existence.
    - UID: `loadFluxIds()` parse `${ROOTFS}/etc/passwd` `flux:x:uid:gid:home:shell`; fallback 0:0 for root-exec. Handle missing ROOTFS (fresh install) by deferring to installer before launcher use.
    - Binds: `buildBinds(profile)` per design — `cli` core: `/dev,/proc,/sys, /dev/urandom→/dev/random, $PREFIX/tmp→/tmp, $ROOTFS/tmp→/dev/shm, sys_empty→/sys/fs/selinux, storage /sdcard if readable`. `gpu-turnip` + `/system,/vendor,/apex,/linkerconfig,/dev/kgsl-3d0,/dev/dri`. `gpu-virgl` + X11 socket. `compat` = full distro set plus `/odm,/product,/system_ext,/data/app,dalvik-cache,$PREFIX,$HOME`. Document measured ~104 vs 113 ms proves binds not launch bottleneck.
    - Flags: `--kill-on-exit --link2symlink -L --change-id=uid:gid --rootfs=ROOTFS --cwd=HOME`; optional `--sysvipc` only when `NC_PROOT_SYSVIPC=1` or compat, optional `--kernel-release` only when `NC_PROOT_FAKE_UNAME=1`. Map Kotlin `pref proot_fast_launcher` + `SharedPreferences NC_PROOT_*` to shell envs `NC_PROOT_PROFILE/NC_PROOT_SYSVIPC/NC_PROOT_FAKE_UNAME` for `nativecode_proot_fast.sh` parity.
    - Env: `buildGuestEnv()` → clean `env -i HOME/USER/LOGNAME/PATH/LANG=C.UTF-8/TERM/TMPDIR/XDG_RUNTIME_DIR/NVM_DIR/DEBIAN_FRONTEND=noninteractive/FLUX_QUIET_SHELL=1` plus GPU vars; `buildGuestPath()` = `$HOME/.local/bin:$HOME/bin:$HOME/.cargo/bin:/opt/nativecode/node-v26/bin:$HOME/.nvm/versions/node/v*/bin:/usr/local/...` (**unified to `/opt/nativecode/node-v26`**, not `/opt/nodejs`) without sourcing `nvm.sh` (host-side scan of `$ROOTFS$HOME/.nvm/...` via `File(rootfsHome + "/.nvm/versions/node").listFiles()?.map{it.name}?.sortedWith(compareBy{SemVer})?.lastOrNull()` fallback to no nvm bin if absent).
    - Exec: `exec proot $FLAGS $BINDS /usr/bin/env -i $ENV_ARGS "$@"` for argv commands; `/bin/dash -c "$cmd"` for generic shell strings; `/bin/bash -c` only when bash-specific; interactive `/bin/zsh -l` or `/bin/bash -l`; never `zsh -c "bash -lc ..."`. Keep `ProotCommandBuilder` Python path as `compat` fallback.

14. Wire `LinuxCommandBuilder.kt:24-25` to route via `ProotFastCommandBuilder` when `pref proot_fast_launcher == true`, else legacy `ProotCommandBuilder`. Add `ShellCommandRunner.kt` adjustHostCmd preservation (`/system/bin/linker64` prefix for app-data ET_DYN).

15. Update `TerminalService.kt` / `MainActivity.kt` session creation to pass `workDir` + `profile` correctly: interactive via `login`, one-shot via `exec`, keep `ChrootCommandBuilder` intact for chroot method.

16. Ship updated `nativecode_proot_fast.sh` asset (already at `app/src/main/assets/scripts/nativecode_proot_fast.sh`) as ADB debug mirror.

### PR5 — perf/proot-shell-node — Zsh/bash + Node lazy redesign

17. Shell: rewrite guest `/home/flux/.zshenv` to tiny env only (source `cli-tools.env` fast path, no `compinit`); keep `.zprofile` minimal; `.zshrc` interactive only with `compinit -C` using cached `.zcompdump` generated at golden-rootfs build time; remove `nvm.sh` sourcing from `.zshenv`; instead create lazy `nvm()` function:
    ```bash
    nvm() { unset -f nvm; [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"; nvm "$@"; }
    ```
    Normal `node/npm` never loads `nvm.sh`. Direct `PATH` already contains default node bin via launcher's `buildGuestPath`.

18. Node: ensure golden rootfs has `/opt/nativecode/node-v26/bin/{node,npm,npx}` and symlinks `/usr/local/bin/*`. In `setup_cli_tools.sh`, make NVM optional: if bundled node exists, skip NVM install unless user requests version switch. Remove `~/.nvm` heavy init from interactive hot path.

19. npm hygiene: guest `/etc/npmrc` or `~/.npmrc` with `audit=false fund=false progress=false update-notifier=false cache=$HOME/.cache/npm`; document `npm install --prefer-offline --no-audit --no-fund --progress=false`. Keep cache on internal `/data/data/com.zenithblue.nativecode/files/.../`, never `/sdcard` (FUSE penalty). For bundled AI CLIs (opencode, claude, codex, grok, agy, kiro, qwen per `CliToolCatalog.kt`), prefer official single-binary dist when available over thousands-of-file JS trees; verify fewer stat calls.

20. Fix `--ignore-scripts` anti-pattern: remove generic fallback; only per-package allowlist.

21. Regression: preserve interactive colors, PATH, Node/npm, AI CLIs, SSH/git env, terminal resize/WINCH (`ChrootCommandBuilder.kt:90-92 trap`), Ctrl-C, exit codes.

### PR6 — perf/proot-session-runner — Persistent PRoot guest broker (after PR4)

22. Implement `ProotSessionRunner.kt`:
    - Unix socket **`"$PREFIX/tmp/nativecode-proot-broker.sock"` moved to `"$PREFIX/tmp"` with `0700` dir and `0600` socket + `SO_REUSEADDR=0` but note: `$PREFIX/tmp` is bind-mounted to guest `/tmp` (world-readable inside guest). Mitigate by creating socket under **`$PREFIX/var/run/nativecode-broker.sock`** (host-private, not bind-mounted) or `ctx.filesDir + "/run/broker.sock"` with `0700`; do not place world-readable broker socket on shared `/tmp`. Access only via host UID.
    - One long-lived `proot` process started via `ProotFastCommandBuilder` flags, `--cwd=/home/flux`, running broker binary (`/usr/local/bin/nativecode-broker` inside guest, small Rust/Go or sh loop) that `fork/exec` children `git/npm/gcc/codex/shell` without recreating Python+PRoot+shell each time.
    - Protocol: `exec {id, argv, env, cwd, pty?}` → `stream {stdout,stderr,exit}` with timeout, kill-on-exit inherited (`--kill-on-exit` on outer proot). Lifecycle: `ProotSessionRunner` is **dependent on `proot_fast_launcher`** (flag `proot_persistent_runner` requires `proot_fast_launcher==true`; if launcher off, broker unavailable → fallback). Broker starts with `TerminalService`/`NativeCodeFgs` owner `OWNER_BROKER`, dies when `NativeCodeFgs` owners zero, never detached daemon, `START_NOT_STICKY`, host `Process.destroyForcibly` on service destroy.
    - Keep `ProotFastCommandBuilder.exec` as fallback if broker not running or socket missing.

23. Add `ProjectPathResolver` + `DirectoryScanner` integration: warm local caches on internal storage, short project paths (`$HOME/projects/<name>`), not `/sdcard`.

24. Evaluate compiler workloads (Agent 7 scope merged): `ccache` config `~/.cache/ccache`, prefer `ninja` over `make`, test `git config core.untrackedCache true` + `feature.manyFiles true` before defaulting; **no** default `core.fsyncObjectFiles=0` / `eatmydata` / `dpkg --force-unsafe-io` (aggressive only behind flag).

### PR7 — perf/proot-syscall-experiments — Syscall acceleration & optional proot patches (feature-flagged, last)

25. Instrument development `proot` build to count syscall classes (openat/newfstatat/statx/readlinkat/getdents64/execve/mmap/mprotect/ioctl/rename/unlink) via aggregate counters (no per-syscall logging).
26. Verify on each bench device: `proot -V`, `proot -v 1 /bin/true` output, check `seccomp_filter` compile-time and runtime active, `process_vm_readv/writev` accelerator, bundled version vs Termux proot commit, kernel/Android/SoC. If accelerators missing, vendor known Termux PRoot commit with them enabled, regression test Android 13/14/15/16 (ptrace/seccomp varies, per https://github.com/termux/proot-distro/issues/567).
27. Only after profiling, experiment behind `proot_experimental_cache`: path-resolution cache, lookup cache with invalidation on mutation (`rename/unlink/chmod` flush). Remains flag `proot_experimental_cache` default off, independently falls back.
28. Document hard boundary: even with cache, `4K metadata/create/stat` cannot guarantee chroot parity; publish per-area target table vs classic limit.

Across all PRs: never recursively delete outside `.../containers/`, never start `su/mount/umount/remount/sysctl/drop_caches/dev/block/governor/gpu sysfs/swap/zram/killall/pkill/system_server/SurfaceFlinger/DRM` from performance code (veto enforced by Agent 9 review), never kill by name — only `PIDs ⊆ spawnedByNativeCode ∧ uid==appUid ∧ inSession`, streaming extraction only, no 2–4 GB buffers, thermal pause when `DeviceRequirements.thermal`.

## File-Level Change Map

| File | Change | Rationale |
|------|--------|-----------|
| `app/src/main/java/com/zenithblue/nativecode/terminal/FastRootfsInstaller.kt` | **NEW** — transactional extractor, SHA streaming, atomic rename, fallback | PR2 core: 1-pass SHA+decompress, secure extraction |
| `app/src/main/cpp/native_rootfs_extractor.cpp` + `.../h` | **NEW** JNI libarchive/libzstd extractor | Native fast path, reuse Termux NDK infra |
| `app/src/main/cpp/CMakeLists.txt` | **NEW** — cmake for native extractor, static libs | Build plumbing per reviewer gap 3 |
| `app/src/main/cpp/third_party/libarchive.a` + `libzstd.a` + `liblzma.a` | **NEW** prebuilt arm64 | Native deps |
| `app/src/main/java/com/zenithblue/nativecode/terminal/NativeRootfsExtractor.kt` | **NEW** Kotlin JNI wrapper + progress | UI progress, error mapping |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ProotFastCommandBuilder.kt` | **NEW** direct proot builder, profiles, env | PR4: eliminate Python + double shell, 80–150 ms |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ProotCommandBuilder.kt:6-37` | **EDIT** — keep as `compat` fallback, deprecate default | Preserve bug-for-bug path, flag-gated |
| `app/src/main/java/com/zenithblue/nativecode/terminal/LinuxCommandBuilder.kt:24-36` | **EDIT** — route `proot` → Fast builder when flag, else legacy | Single switchboard |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ProotSessionRunner.kt` | **NEW** persistent broker client | PR6: eliminate per-command PRoot creation (depends on fast launcher) |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ProjectPathResolver.kt` | **EDIT** — add stagingDir(), brokerSocket(), golden marker helpers, cleanStaleStaging() | Path SSOT |
| `app/src/main/java/com/zenithblue/nativecode/terminal/TermuxHostPaths.kt` | **EDIT** — add `GOLDEN_MARKER=/etc/nativecode-golden`, `FAST_INSTALL_FLAG` keys | Package truth |
| `app/src/main/java/com/zenithblue/nativecode/OnboardingActivity.kt:3290` | **EDIT** — call FastRootfsInstaller, progress callback, flag fallback | Install flow |
| `app/src/main/java/com/zenithblue/nativecode/MainActivity.kt:12771` | **EDIT** — same fast install for repairs page | Consistent repair |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ShellCommandRunner.kt` | **EDIT** — minor: expose `runCaptureExit` for bench, keep linker64 prefix | Bench harness |
| `app/src/main/assets/scripts/nativecode_proot_fast.sh` | **UPDATE** — sync with Kotlin builder (already exists), unify `/opt/nativecode/node-v26` | ADB mirror |
| `app/src/main/assets/scripts/flux_install.sh:13-197` | **EDIT** — add early exit when golden marker + fast installer present | Defer to native path |
| `app/src/main/assets/scripts/setup_debian_family.sh` | **EDIT** — no-op when `/etc/nativecode-golden` exists | Avoid on-device apt |
| `app/src/main/assets/scripts/setup_cli_tools.sh` | **EDIT** — NVM lazy, bundled node check, no forced nvm install | Shell startup |
| `app/src/main/assets/scripts/optimize_debian_rootfs_perf.sh` | **REF** — material for CI golden build (no device change) | Source of safe opts |
| `app/src/main/assets/rootfs/debian_13_core_arm64.tar.zst` | **NEW artifact** — core in APK only; dev/desktop as Release downloads | PR3 output (APK bloat fix) |
| `app/build.gradle.kts` | **EDIT** — add `ndkVersion 26.1.10909125`, `externalNativeBuild.cmake`, `abiFilters arm64-v8a`, `noCompress += "zst"` | Native build + keep zst uncompressed |
| `.github/workflows/build-golden-rootfs.yml` | **NEW** — CI build native arm64, SBOM, manifest, SHA | Automated golden |
| `bench/nc-io-bench.c` | **NEW** — fair 4K/seq/metadata bench | Fix fio SYSV IPC gap |
| `bench/nc-membench.c` | **NEW** — syscall-free RAM bench | Separate RAM vs ptrace |
| `bench/run-safe-suite.sh` | **NEW** — watchdog, thermal/storage guards | Safe bench runner |
| `bench/result-schema.json` | **NEW** — JSON schema for results | Agent 0 SSOT |
| `docs/perf/proot-parity-v2.md` | **NEW** — fingerprint template + before/after gate | No change without numbers |
| `docs/plans/proot-parity-v2-plan.md` | **NEW** — this plan |  |
| `gradle/libs.versions.toml` (if NDK) | **EDIT** — add `libarchive` prebuilt | Native dep |

No edits to `app/src/main/jniLibs/*` proot binaries in early PRs; PR7 vendors new proot only after regression matrix passes.

## Testing Strategy

**Unit (JVM):**
- `FastRootfsInstallerTest` — staging path validation, SHA streaming (chunked), atomic rename, lock contention, corrupt archive rejects, wrong SHA rejects.
- `ProotFastCommandBuilderTest` — argv generation per profile (cli/turnip/virgl/compat counts), env map contains PATH/LANG/TMPDIR/gpu vars, safety refuses `com.termux` PREFIX, `loadFluxIds` parses passwd correctly, never emits `zsh -c "bash -lc"`.
- `NativeRootfsExtractorTest` — symlink escape, absolute path, `..` traversal, device node, fifo, symlink race — all rejected; symlink inside rootfs allowed.

**Instrumented / host-side shell (no device mutation without approval):**
- Extraction cancellation: `kill -9` installer mid-stream → staging deleted, existing rootfs untouched, fallback to python path works.
- Archive format bench: same manifest packed as `.tar.xz` vs `.tar.zst` (levels 3–6) vs `.tar` → APK size vs `time unzstd/tar` on device.

**Device (two devices minimum, both envs):**
- Snapdragon Adreno 750 + MediaTek MT6897, Android 13–16 matrix per PR7.
- Launch microbench: `/bin/true` median <150 ms, `/bin/sh -c true` <175 ms, `git --version` <200 ms warm, 100/100 launch cycles exit 0 (`docs/environment/proot-fast-launcher-design.md` baselines 816 vs 104 vs 30 ms).
- Bench suite `bench/run-safe-suite.sh`: sysbench cpu, stress-ng, nc-membench, nc-io-bench seq 1M + rand 4K + metadata, dd 256 MiB, `npm --version`, `node --version`, AI CLI startup, small C compile (single file), 100-file make vs ninja, `git clone/status/diff/checkout` on internal `/data/...` vs `/sdcard` (expect internal faster).
- Provisioning: `npm install small pkg` cold vs warm cache, TypeScript project install.
- Safety guards: low storage (<1 GB) aborts, thermal `SEVERE` pauses, RAM test capped `min(256 MiB, avail/8)`.

**Regression (existing suite + new gates):**
- Interactive: colors, PATH, Node/npm, AI CLIs (opencode/codex/claude/agy/qwen/grok/kiro) via `launch_tool.sh`, SSH/git env, resize/WINCH, Ctrl-C, exit code propagation, `--shared-tmp` semantics (`$PREFIX/tmp` ↔ `/tmp`).
- Install: fresh, upgrade, interrupted, wrong SHA, corrupt archive, app killed during extraction, low storage, `FLUX_ROOTFS_PATH` override still uses python path.
- Profiles: each of cli/package/interactive/turnip/virgl/compat at least boots, `vulkaninfo` shows Turnip on Adreno when turnip profile, no DRM/KMS test ever.
- Chroot parity: chroot path untouched, chroot bench still passes.

**Review passes:**
- `review` skill 8-pass (build, plan adherence, correctness, security, performance, style, tests, docs) after PR2, PR4; veto worker if any `su/mount/killall` or `com.termux` path.

## Acceptance Criteria (objective, verifiable)

- [ ] **AC1 Install cold** — On MediaTek MT6897 + Snapdragon Adreno 750 **reported per-SoC** (or one of each), fresh proot install via fast installer (zst core/dev artifact) cold time ≤ 1.15× direct `tar --use-compress-program=unzstd -xf` to `/data/local/tmp/chrootDebian13` equivalent size (±10% measurement noise, 3 runs median, watchdog no thermal throttle). Report separately: “MT6897 0.49× read device” vs “Snapdragon 0.40× read device” context.
- [ ] **AC2 No partial install** — Forced cancellation (kill installer at 25/50/75% progress) 10/10 times leaves no `.../containers/debian/rootfs` mutation and next install succeeds without manual clean.
- [ ] **AC3 Archive security** — Unit extractor rejects 6/6 malicious tar entries (absolute path, `../`, symlink escape, device node, fifo, symlink-race) and accepts 3/3 legitimate symlinks inside rootfs.
- [ ] **AC4 Launch latency** — On MT6897 (reference device per proot-fast-launcher-design, profile `cli` + `proot_fast_launcher` on, no `zsh -c`), warm medians over 20 runs: `/bin/true` <150 ms, `/bin/sh -c true` <175 ms, `git --version` <200 ms; `zsh -c "bash -lc"` never emitted (grep argv log of `ProotFastCommandBuilder`). Compat fallback still exits 0 for same commands.
- [ ] **AC5 Shell startup** — Non-interactive `dash -c 'echo ok'` does not source `nvm.sh`; `nvm` function lazy-loads only when invoked; interactive `zsh -l` prompt <300 ms warm (measured via `time zsh -ic exit` inside proot, 5 runs median).
- [ ] **AC6 Golden rootfs** — CI artifact boots: `dpkg --audit` clean, `node --version` = pinned 26.x, `npm --version` present, `/opt/nativecode/node-v26/bin/node` exists and `/usr/local/bin/node -> /opt/nativecode/node-v26/bin/node` (unified path), `/etc/apt/apt.conf.d/99nativecode-perf` present, `locale -a` has `C.UTF-8`, `/bin/sh` → `dash`, no on-device `apt update` needed (marker `/etc/nativecode-golden` exists), placeholder flux UID overwritten by installer.
- [ ] **AC7 Fair bench** — Same `nc-io-bench` ARM64 binary runs in both proot and chroot reporting seq-read/write (1M), rand4k IOPS, metadata ops/s; `nc-membench` shows <10% delta to mbw memcpy on both devices (pure userspace parity) while `sysbench memory` still shows device-dependent tax; `fio` removed from cross-env ratio, documented why `shmget` fails in chroot.
- [ ] **AC8 Profile correctness** — `cli` boots with 5–7 binds, `compat` matches distro ~21 binds, `gpu-turnip` includes `/system,/vendor,/apex,kgsl` when present, `gpu-virgl` includes X11 socket; each profile 20/20 boots exit 0.
- [ ] **AC9 Safety veto** — Grep of **PR2–PR6 perf diff scoped to `app/src/main/java/com/zenithblue/nativecode/terminal/Fast*` + `app/src/main/cpp/native*` + `bench/*` + `.github/workflows/build-golden*`** (exclude `app/src/main/assets/scripts/chroot/*` legacy and `ChrootCommandBuilder.kt` legit mounts) shows zero occurrences of `mount|umount|remount|sysctl|drop_caches|/dev/block|governor|devfreq|swap|zram|killall|pkill|kill -1|system_server|SurfaceFlinger|glmark.*drm` outside comments, and kill logic only uses `pid in sessionSet && uid==appUid`.
- [ ] **AC10 Safe suite guards** — `run-safe-suite.sh` aborts if free storage <1 GB, pauses if thermal severe, caps I/O file to 256 MiB and RAM test to `min(256 MiB, avail/8)`, uses streaming buffers never >256 KiB.
- [ ] **AC11 Rollout flags** — Four flags `proot_fast_install`, `proot_fast_launcher`, `proot_persistent_runner` (depends on launcher), `proot_experimental_cache` (depends on launcher). Each off → legacy behavior for that layer; `fast_install` and `fast_launcher` independently off → exact legacy `python proot-distro install` + distro login; `persistent_runner` off while launcher on → launcher direct exec still used; switchable without reinstall. Document dependency: broker requires launcher.
- [ ] **AC12 No regression** — Existing `docs/environment/plans/regression-test-suite.md` P0 gates pass on both devices after PR4 and PR6 (elf proot, `nativecode_proot.sh cmd true`, no com.termux paths, chroot enter).

## Risks & Mitigations (NEW_RISKS)

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| **libarchive symlink-escape bug** — fast extractor creates files outside rootfs → security | Low if review, High impact | Vet against proot-distro `proot_cmd.py` extraction checks (path escapes, special files, dirfd, symlink race); add unit zip-slip suite; stage under `.../containers/` only, never `/` | |
| **libarchive/libzstd NDK size or missing arch** — APK bloat or arm64-only | Medium | Build only arm64-v8a (NativeCode minSdk 26, target 36, already arm64-v8a `libproot.so`); static link minimal libarchive; fallback to python path if native load fails | |
| **Golden rootfs bake misses device-specific UID** — flux UID mismatch (proot 10416 vs chroot 1000) breaks permissions | Medium | Do not bake `flux` UID; installer injects UID/GID from `getAppUid`/`getUserId` at commit time (audit how NativeCode currently maps flux per prompt Agent 2); verify `passwd` after atomic rename | |
| **Zstd vs xz tradeoff wrong** — APK too large or decompress not faster | Medium | Benchmark 4 formats table per prompt in PR1 bench; keep `.tar.xz` fallback artifact; `build.gradle.kts noCompress` ensures not double-compressed | |
| **Seccomp accelerator not active** — syscall gap remains 0.65×/0.06× on Snapdragon, users blame new code | High | PR7 profiles first; record `proot -V` + `proot -v 1` evidence per bench run; if missing, vendor Termux proot commit but gate on Android 13-16 regression matrix; keep experimental cache flagged off | |
| **Lazy NVM breaks user shell expectations** — `.zshrc` sourcing order differs | Low | Keep legacy `source ~/.nvm/nvm.sh` path behind flag; test `nvm use`, `npm`, `node --version` + AI CLIs; provide explicit upgrade note | |
| **Persistent broker orphan** — daemon survives app kill → battery/FGS violation | Low | Broker parented to `TerminalService` / `NativeCodeFgs` owner, `kill-on-exit` on outer proot, socket in app-private tmp, `SO_REUSEADDR` off, watchdog kills broker when `FGS owners=0` | |
| **FIO/SYSV IPC confusion** — future contributor re-adds fio to cross-env ratio | Low | Document in `docs/perf/proot-parity-v2.md` why fio fails in chroot (`shmget Function not implemented`), enforce `nc-io-bench` only for ratio | |
| **Low storage / thermal** — bench or install bricks device | Medium | Watchdog (`DeviceRequirements`, `ChrootSizeManager`) pre-check free RAM/storage/thermal, streaming extraction, no 2–4 GB buffers, bench caps, abort with user toast | |
| **proroot LD_PRELOAD experimental temptation** — team ships immature non-ptrace backend early | Low | Keep `NATIVE_GLIBC` as future `LinuxBackend` enum value only; track upstream issue https://github.com/termux/termux-app/issues/5245 but not production in this plan | |
| **Context7 quota / ctx7@latest failure** | Low | Note fallback to local file reads + websearch; record in NEW_RISKS per planner checklist | |

## Handoff to Plan Reviewer (Resubmission — prior REVISE addressed)

Prior REVISE gaps fixed: atomic commit now backup+rename without `rm -rf` window + parent fsync; SHA-gated commit + libarchive secure flags (`SECURE_SYMLINKS|SECURE_NODOTDOT|NO_OVERWRITE`+`O_NOFOLLOW` dirfd); CMake/NDK plumbing + dual-format PR2; core-in-APK / dev-desktop Release split; unified Node path `/opt/nativecode/node-v26`; broker depends on launcher + socket moved to `var/run` 0700; AC9 scoped grep; `nc-membench` vs `sysbench` distinction; per-SoC AC1; stale GC via `boot_id`/`elapsedRealtime`.

Validate:

1. **Atomicity fix** — Does `rename(debian→debian.old.ts)` + `rename(staging→debian)` + parent fsync + delete `old` eliminate the prior `rm -rf` window and ensure only `containers/` touched? Verify `FileLock` on `.install.lock` covers both renames.
2. **Extraction security** — Do `ARCHIVE_EXTRACT_SECURE_*` + manual dirfd/`O_NOFOLLOW` + symlink target checks fully replicate `proot-distro/proot_cmd.py` escapes? Is SHA-failed staging deleted before commit sufficient vs “writes briefly to disk” concern?
3. **Build plumbing** — Does `CMakeLists.txt` + `ndkVersion 26.1.10909125` + `abiFilters arm64-v8a` + prebuilt `libarchive.a/libzstd/liblzma` correctly build for NativeCode (already arm64-v8a `libproot.so`) with fallback to python? Is PR2 dual-format (xz+zst) before PR3 valid?
4. **APK bloat** — Is `core` in APK + `dev/desktop` as Release downloads acceptable vs Play limits; does `noCompress zst` + size table (xz vs zst 3-6) benchmarking gate the choice?
5. **Path unification** — Is `/opt/nativecode/node-v26` consistently used in CI, `ProotFastCommandBuilder.buildGuestPath`, and `nativecode_proot_fast.sh` (fix legacy `/opt/nodejs`)? Does Kotlin `File.listFiles` semver sort match shell `sort -V`?
6. **Broker dependency & socket** — Is `proot_persistent_runner requires proot_fast_launcher` documented, `var/run` socket `0700`/`0600` not on shared `/tmp` bind, `START_NOT_STICKY` + `NativeCodeFgs` refcount lifecycle correct, no orphan daemon?
7. **Benchmark fairness** — Does `nc-membench` zero-syscall note clarify ~parity expected (like mbw 0.90×) vs `sysbench` tax, and does `nc-io-bench` without SYSV/block fix the `fio shmget` unfair ratio with proper caps?
8. **Safety & flags** — Is AC9 grep correctly scoped to perf diff (excluding legacy chroot mounts) and does AC11 now state broker depends on launcher; are remaining 3 flags independently reversible? Confirm no `su/mount/governor/killall/DRM` in new code path.
9. **UID timing & CI runner** — Is placeholder flux UID left for installer injection (not baked), and does native ARM runner (not qemu) avoid timeout?

If REVISE, annotate which PR(s) still need reshaping; if APPROVE, worker may start at PR1.

If REVISE, annotate which PR(s) need reshaping; if BLOCK, state whether ptrace hard limit makes any target unachievable without chroot/proroot.

