# Plan: proot-parity-v2 — Product-level PRoot/Chroot Parity (Rev 3)

## Task Summary

Achieve **product-level parity** between rootless PROOT and rooted chroot without claiming impossible ptrace parity. Targets: install ~1.0–1.15× `tar -xf` to `/data/local/tmp`, launch `/bin/true` <150 ms (vs 816 ms today), CPU ~95–100%, RAM memcpy ~95–100%, syscall-heavy/seq-I/O gap documented. Delivered via (1) transactional native extractor (one-pass SHA+decompress, dirfd+flock, RENAME_NOREPLACE fresh-install-only), (2) CI golden rootfs with capability manifest + signed artifact provider, (3) direct PRoot launcher (no Python, no double shell, profiled binds, `/opt/nativecode/node-v26` unification), (4) shell/Node lazy init, (5) optional persistent broker with pdeathsig+private bind, (6) fair Debian/glibc bench suite + host thermal watchdog, (7) feature-flagged rollout. **P0 repo hygiene**: remove `app/build.gradle.kts:32-37` signing credentials from VCS before any release branch.

> Rev 3 addresses Iteration 3 REVISE: 10 primary blockers (atomicity, flock interop, TOCTOU dirfd, symlink/hardlink policy, UID injection, golden marker→capabilities, authenticated Release, data-loss replacement, broker pdeathsig, AC9 scan) + 6 PR1 benchmark corrections.

## Research Sources

- <source: app/src/main/java/com/zenithblue/nativecode/terminal/ProotCommandBuilder.kt:6-36 | current `libbash → python proot-distro → zsh -c` double shell>
- <source: bootstrap_root/.../site-packages/proot_distro/locking.py:21-72,244-272 | `ContainerLock` = flock(2) on `$PREFIX/var/lib/proot-distro/locks/<name>.lock`, shared=login/run inheritable, exclusive=install/remove/reset, non-blocking>
- <source: bootstrap_root/.../site-packages/proot_distro/constants.py:75-77 | `RUNTIME_DIR=$PREFIX/var/lib/proot-distro`, `LOCKS_DIR`, `CONTAINERS_DIR`>
- <source: bootstrap_root/.../site-packages/proot_distro/commands/install.py:140 | `with ContainerLock(install_name, exclusive=True)`>
- <source: bootstrap_root/.../site-packages/proot_distro/commands/login/__init__.py:85,519 | shared inheritable flock held entire session via exec>
- <source: app/src/main/assets/scripts/nativecode_proot_fast.sh:1-369 | direct proot model exists but uses `/opt/nodejs` legacy path>
- <source: app/src/main/java/com/zenithblue/nativecode/terminal/TermuxHostPaths.kt:9-120 | SSOT package com.zenithblue.nativecode>
- <source: app/build.gradle.kts:32-43 | **P0**: `storePassword="REDACTED"` committed — must rotate>
- <source: https://github.com/libarchive/libarchive/blob/master/libarchive/archive.h | `ARCHIVE_EXTRACT_SECURE_SYMLINKS|SECURE_NODOTDOT|SECURE_NOABSOLUTEPATHS` guard **entry paths** only>
- <source: https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run/choose-the-runner-for-a-job | `ubuntu-24.04-arm` valid hosted arm64 runner>
- <source: https://support.google.com/googleplay/android-developer/answer/9859372 | Play Asset Delivery supports large on-demand asset packs; 150 MB is product budget not arch limit>
- <source: docs/environment/proot-vs-chroot-baseline.md + proot-vs-chroot-perf-report-mediatek.md | Snapdragon vs MT6897 gaps device-dependent>
- <source: docs/environment/proot-fast-launcher-design.md:1-250 | 816 ms distro vs 104 ms plain proot vs 30 ms chroot, binds not bottleneck>
- <source: git log --oneline -20 | master d8dd6e5>

## Current Architecture

**CHROOT** `setup_debian13_chroot.sh`: `assets/rootfs/debian_13_rootfs.tar.xz → /data/local/tmp/chrootDebian13 .flux_configured` native `tar/unxz`.

**PROOT today** `flux_install.sh:163-261`: APK `debian_13_rootfs.tar.xz` (xz only, SHA `13e29f…6803`) → `sha256sum` full read → `python proot-distro install` (python tar, Deus: install holds exclusive `ContainerLock("debian")`, login holds shared) → `proot-distro login --shared-tmp -- bash /tmp/flux_setup_temp.sh` → `setup_debian_family.sh` apt + `setup_cli_tools.sh` NVM on device. Two full reads, thousands ptrace ops.

**Runtime** `ProotCommandBuilder.kt:18-31` always `arrayOf(shell,"-c","exec python $prootDistro login … -- zsh -c '…'")`. Chroot uses direct `nativecode_chroot.sh`. Fast launcher asset already implements `exec proot --kill-on-exit --link2symlink -L --change-id --rootfs --cwd` + `env -i` but not wired, still `/opt/nodejs`.

**Guest**: `setup_*.sh` + `optimize_debian_rootfs_perf.sh` run inside PRoot. Node via NVM `source nvm.sh` every shell.

## Affected Components & Dependencies

| Component | Files | Impact |
|-----------|-------|--------|
| **Signing hygiene (P0)** | `app/build.gradle.kts:32-43`, `keys/`, CI secrets | Rotate credentials, move to `gradle.properties`/`~/.gradle/gradle.properties`+env, `.gitignore` keys |
| **Rootfs asset & build** | `app/src/main/assets/rootfs/`, `app/build.gradle.kts:androidResources`, `assemble_bootstrap.py` | core in APK, dev/desktop via signed provider or Play Asset Delivery; zst+xz dual |
| **Fast installer (Kotlin+JNI)** | `flux_install.sh`, `TermuxHostPaths.kt`, `OnboardingActivity.kt:3290`, `MainActivity.kt:12771`, `ProjectPathResolver.kt` | NEW `FastRootfsInstaller.kt`, `NativeRootfsExtractor` JNI (libarchive+libzstd+liblzma, arm64-v8a NDK PIC), dirfd+flock+RENAME_NOREPLACE |
| **Proot launcher** | `ProotCommandBuilder.kt`, `LinuxCommandBuilder.kt`, `ShellCommandRunner.kt`, `nativecode_proot_fast.sh` | NEW `ProotFastCommandBuilder.kt`, profiles cli/turnip/virgl/compat, unified `/opt/nativecode/node-v26` |
| **CI golden rootfs** | `setup_debian_family.sh`, `setup_cli_tools.sh`, `optimize_debian_rootfs_perf.sh`, `.github/workflows/*` | Workflow builds core/dev/desktop arm64.tar.zst + `image.json` + signed `rootfs-manifest.json` |
| **Shell/Node** | guest `~/.zshenv/.zprofile/.zshrc`, `ChrootCommandBuilder.kt:247-283` | lazy nvm function, bundled `/opt/nativecode/node-v26` |
| **Benchmark suite** | `docs/environment/bench*` | NEW `nc-io-bench.c` (glibc arm64), `nc-membench.c`, `SafeBenchController.kt`, `bench/run-safe-suite.sh` |
| **Persistent broker** | `TerminalService.kt`, `NativeCodeFgs.kt` | `ProotSessionRunner.kt` + native pdeathsig launcher, private bind `$filesDir/run/proot-broker → /run/nativecode-host` |
| **Safety** | `DeviceRequirements.kt` | thermal/storage/RAM guards, veto grep |

## Implementation Steps (ordered, smallest correct change)

### PR0 — repo/p0-signing-rotation — Must land before any release/PR merge (no behavior change)

0. Remove `app/build.gradle.kts:32-37` passwords + `storeFile` absolute path from VCS. Replace with:
   ```kotlin
   signingConfigs {
     create("release") {
       storeFile = file(findProperty("nativecode.keystore.path") as? String ?: System.getenv("NATIVECODE_KEYSTORE") ?: "debug.keystore")
       storePassword = findProperty("nativecode.keystore.password") as? String ?: System.getenv("NATIVECODE_KEYSTORE_PASSWORD") ?: ""
       keyAlias = findProperty("nativecode.key.alias") as? String ?: System.getenv("NATIVECODE_KEY_ALIAS") ?: "nativecode"
       keyPassword = findProperty("nativecode.key.password") as? String ?: System.getenv("NATIVECODE_KEY_PASSWORD") ?: ""
     }
   }
   buildTypes { release { signingConfig = if (hasProperty("nativecode.keystore.path")||System.getenv("NATIVECODE_KEYSTORE")!=null) signingConfigs.getByName("release") else signingConfigs.getByName("debug") } }
   ```
   Add `keys/` to `.gitignore`, rotate keystore/password (`REDACTED` compromised), store new secret in GitHub Actions `NATIVECODE_KEYSTORE_BASE64` + secrets. Verify `git log --diff-filter=D` does not leak history; if needed `git filter-repo` note. CI `assembleRelease` reads env. Document in `docs/setup/signing.md`.

### PR1 — perf/proot-benchmark-v2 — Fair measurement before speedup (no behavior change) — APPROVED_TO_START

1. **Toolchain**: compile `bench/nc-io-bench` and `bench/nc-membench` as **ARM64 Debian/glibc binaries** (not NDK/Bionic). Build on `ubuntu-24.04-arm` runner with `aarch64-linux-gnu-gcc` against glibc; same ELF runs in both PRoot and chroot. Add `bench/README.md` with `file` output proof.
2. **`bench/nc-io-bench.c`** interface (single binary, subcommands):
   ```
   nc-io-bench prepare FILE --size 256M
   nc-io-bench seq-read FILE --bs 1M [--direct]
   nc-io-bench seq-write FILE --bs 1M
   nc-io-bench rand-read FILE --bs 4K --jobs 1 --time 10s
   nc-io-bench rand-write FILE --bs 4K --jobs 1 --time 10s
   nc-io-bench rand-mix FILE --bs 4K --read-pct 70 --time 10s
   nc-io-bench metadata DIR --files 4096
   ```
   Impl: `clock_gettime(CLOCK_MONOTONIC_RAW)` helper `now_ns()`, `posix_memalign(&buf,4096,1<<20)` for seq (1 MiB) and 4 KiB for rand, `pread/pwrite` loops, PRNG `xorshift64` index array **pre-generated before timing**. Reports `write_data_ns` and `fdatasync_ns` separately (call `fdatasync(fd)` after write loop, time it separately); never hide sync cost nor time dirty-cache only. Provide both `buffered` and `direct-if-supported` (`O_DIRECT`, fallback to buffered if `EINVAL`). No `drop_caches`, no `/dev/block`, no `shmget`.
3. **`bench/nc-membench.c`**: sizes 8–64 MiB, ops: `memcpy`, `memset`, `RO-scan` (sum bytes), `WO-scan`, `random-access` (permute). Pre-touch: `for(i=0;i<size;i+=4096) src[i]^=1`; timed loop `for(r=0;r<iters;++r) memcpy(dst,src,size);` prevent optimization `volatile uint64_t sink; sink ^= ((uint8_t*)dst)[size/2];` + `asm volatile(""::"r"(dst):"memory")`. Generate random indices before timing. Zero `malloc/printf/random/open/clock` inside measured loop.
4. **`bench/SafeBenchController.kt`** (host) + **`bench/run-safe-suite.sh`** (guest): Kotlin owns thermal/storage/RAM/cancellation; shell owns process-local limits. Controller: `PowerManager.currentThermalStatus` (API≥29) poll every 2s via `Handler.postDelayed` while child alive; if `THERMAL_STATUS_SEVERE|EMERGENCY` abort child (`SIGTERM`→`SIGKILL`) and report `THERMAL_ABORT`. `StatFs` freeBytes check <1 GiB abort, `ActivityManager.MemoryInfo` availMem, caps: I/O file `min(256MiB, free/10)`, RAM test `min(256MiB, avail/8)`. Shell sets `ulimit -v` / dataset caps, runs bench subcommands, writes `bench/result-schema.json`.
5. **`bench/result-schema.json`** + **`docs/perf/proot-parity-v2.md`** fingerprint template: commit, `proot -V`, `proot -v 1 /bin/true` seccomp/process_vm lines, proot-distro version, Android/kernel/SoC/filesystem/SHA/thermal, archive cache state. Ban `glmark2-*-drm`, block devices, `fio` cross-env.
6. **Gate**: same binary in both envs, warm repeat <5% variance. Document `fio shmget Function not implemented` in chroot vs `nc-io-bench` fair.

### PR2 — perf/proot-fast-extractor — Transactional native extraction (fresh-install-only, dirfd+flock+ caps)

7. **Scope rule (P0 data-loss fix)**: fast installer is **fresh-install-only**.
   ```
   target absent               → fast install allowed (RENAME_NOREPLACE)
   target present + valid      → return ALREADY_INSTALLED, never replace
   target present + incomplete → explicit repair/reset flow (user-confirmed, no auto-delete)
   ```
   Existing `flux_install.sh` already skips when valid; PR2 preserves it. No automatic `debian → debian.old → staging → debian` exchange; future upgrades use `renameat2(RENAME_EXCHANGE)` *only after* migration design that preserves `/home/flux`. Document in `FastRootfsInstaller` kdoc.
8. **Locking — reuse upstream namespace (P0)**: Do **not** use `FileChannel.lock()` on `containers/.install.lock`. Use JNI `flock(2)` on **same** file as `proot-distro.locking.ContainerLock`: `$PREFIX/var/lib/proot-distro/locks/debian.lock` (`RUNTIME_DIR/locks/debian.lock` per `constants.py:67`). Impl in `native_rootfs_lock.cpp`:
   ```cpp
   int locksFd = openat(prefixFd, "var/lib/proot-distro/locks",
                        O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
   int fd = openat(locksFd, "debian.lock",
                   O_RDWR|O_CREAT|O_CLOEXEC|O_NOFOLLOW, 0600);
   if (flock(fd, LOCK_EX|LOCK_NB)!=0) { close(fd); return CONTAINER_BUSY; }
   // keep fd open entire extraction+publish; close = unlock
   // write PID+command like locking.py:177 for diagnostics
   ```
   Open locks dir via dirfd with `O_NOFOLLOW`; never reconstruct path via `canonicalPath`. Login holds shared flock inheritable through exec (`locking.py:519`); fast install correctly blocks against both. Handle `filesystem does not support flock` → proceed unlocked (match `locking.py:182`) but log warning.
9. **Dirfd atomic publish (P0 TOCTOU fix)**: never `canonicalPath.startsWith`. Open once:
   ```cpp
   int cfd = open(containersPath, O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
   ```
   Staging: `mkdirat(cfd, ".debian.installing-<rand>",0700)` → `mkdirat(stagingFd,"rootfs",0755)`. Verify `fstatat(cfd,".debian.installing-…",AT_SYMLINK_NOFOLLOW)` is directory owned by app. Publish:
   ```cpp
   if (renameat2(cfd,".debian.installing-abc123", cfd,"debian", RENAME_NOREPLACE)<0){
     if(errno==EEXIST) return ALREADY_INSTALLED;
     if(errno==ENOSYS||errno==EINVAL) { /* fallback: renameat without flag after re-checking absent */ }
     else fail();
   }
   fsync(cfd); // parent dir durable
   ```
   No `debian → debian.old` window; `debian` never absent. Future exchange needs `RENAME_EXCHANGE` but **not implemented** in PR2. Writer keeps `cfd` alive entire operation; all file creates via `openat(dirfd,…,O_NOFOLLOW|O_CREAT)` + `fstatat(AT_SYMLINK_NOFOLLOW)` chain.
10. **Secure extraction (P0)** — distinguish entry path vs symlink content:
    - `ARCHIVE ENTRY PATH` must never escape staging (use `ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS|SECURE_NODOTDOT|SECURE_SYMLINKS` as tar-parser hints, but **custom dirfd writer is authoritative**).
    - `SYMLINK CONTENT` may be absolute (`/usr/lib/… -> /usr/lib/…`) or contain `..` — valid inside guest; danger is **following** it on host during extraction. So: store absolute target verbatim via `symlinkat(target, dirfd, name)`; never validate `!startsWith("/")`.
    - Writer invariant: before every `openat/mkdirat/symlinkat/linkat`, `fstatat(dirfd, name, AT_SYMLINK_NOFOLLOW)` to detect symlink; if symlink, abort entry. Use `O_NOFOLLOW` on opens; `readlinkat` to record. Hardlinks: validate `linkat` source is inside staging via dirfd pair, reject `../../host` and `hardlink through symlink`.
    - Reject: device nodes, FIFOs, sockets, `S_ISUID` unexpected? (warn), duplicate conflicting entry, very large file, too many entries. AC3 matrix 12 cases: `entry /absolute`, `entry ../../escape`, `symlink followed by child`, `absolute symlink target`, `relative symlink with ..`, `hardlink ../../host`, `hardlink through symlink`, `device node`, `FIFO`, `duplicate conflicting`, `very large declared`, `too many entries`.
    - Libarchive as `decompressor+tar parser` only; do not assume `archive_write_disk` flags stack with custom writer — writer enforces invariant.
11. **Bounded one-pass SHA + caps (P0)**: stream `compressed chunk → SHA256_update + decompressor → dirfd writer` (buffers 64–256 KiB streaming only, never 2–4 GB). Gate commit on `SHA==expected`; if mismatch delete staging uncommitted. Before/during extraction enforce `rootfs-manifest.json` ceilings:
    ```json
    { "schema":2, "profile":"core|dev|desktop", "compressedBytes":92381231, "maxExtractedBytes":1073741824, "maxFiles":125000, "sha256":"…", "maxSingleFile":1073741824 }
    ```
    Abort if `sum(entry.size)>maxExtractedBytes || files>maxFiles || availStorage<reserve || single>maxSingleFile`. On mismatch/corrupt `fdatasync` failure → `unlinkat` staging, return error.
12. **Fixups after verify, before publish**: `resolv.conf` from host, `/etc/hosts`, `sys_empty`, **do not** inject `getAppUid()` into guest `flux`. CI bakes `flux:x:1000:1000:…:/bin/zsh` (see PR3); installer verifies `rootfs/etc/passwd` contains flux 1000:1000, `rootfs/bin/sh→dash` exists, else fail. If legacy UID mapping proven needed later, gate behind experiment with evidence.
13. **JNI + CMake (P0 build correctness)**:
    - `app/src/main/cpp/CMakeLists.txt` imported targets (not `libs.versions.toml`):
      ```cmake
      cmake_minimum_required(VERSION 3.22)
      project(nativecode_extractor)
      add_library(archive STATIC IMPORTED)
      set_target_properties(archive PROPERTIES IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/third_party/${ANDROID_ABI}/libarchive.a")
      add_library(zstd STATIC IMPORTED)
      set_target_properties(zstd PROPERTIES IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/third_party/${ANDROID_ABI}/libzstd.a")
      add_library(lzma STATIC IMPORTED)
      set_target_properties(lzma PROPERTIES IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/third_party/${ANDROID_ABI}/liblzma.a")
      add_library(boringssl_crypto STATIC IMPORTED) # or use CommonCrypto SHA256; document choice
      set_target_properties(boringssl_crypto PROPERTIES IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/third_party/${ANDROID_ABI}/libcrypto.a")
      add_library(native_rootfs_extractor SHARED native_rootfs_extractor.cpp native_rootfs_lock.cpp native_rootfs_writer.cpp)
      target_link_libraries(native_rootfs_extractor archive zstd lzma boringssl_crypto log)
      target_compile_options(native_rootfs_extractor PRIVATE -fPIC -O2 -Wall -Werror)
      ```
    - `app/build.gradle.kts`: `ndkVersion="26.1.10909125"`, `externalNativeBuild.cmake.path file("src/main/cpp/CMakeLists.txt")`, `defaultConfig.ndk.abiFilters+="arm64-v8a"`, `packaging.jniLibs.useLegacyPackaging true` (existing), `androidResources.noCompress+=listOf("zst","xz","tar")`. Only `arm64-v8a` (matches `libproot.so`/`libbash.so`). Fallback to `python proot-distro` if `System.loadLibrary` fails; log `NativeCode: extractor native load failed`.
    - **Reproducibility**: `third_party/README.md` records source URL, commit, SHA256, build script, `CFLAGS="-fPIC -O2 -DANDROID -ffunction-sections"` , NDK API 26+, licenses (`libarchive BSD`, `xz PD`, `zstd BSD`, `boringssl ISC`). Minimal libarchive config `--enable-bsdtar=no --enable-bsdcpio=no --with-zstd --with-lzma` (tar+xz+zst only). Do not check in opaque `.a` without metadata.
    - Kotlin `NativeRootfsExtractor.kt`: `fun extract(archivePath: String, containersDirFd: Int, expectedSha: String, onProgress: (Long)->Unit): Result` streaming, progress every 1 MiB.
14. **Stale GC (P0 clock bug fix)**: on every install start, list `containers/.debian.installing-*` dirs. Each staging creation writes `staging/.boot_id` (`/proc/sys/kernel/random/boot_id`) and `staging/.started_elapsed_ms` (`SystemClock.elapsedRealtime()`). GC:
    ```kotlin
    val curBoot = File("/proc/sys/kernel/random/boot_id").readText().trim()
    val savedBoot = File(staging,".boot_id").readTextOrNull()?.trim()
    val started = File(staging,".started_elapsed_ms").readTextOrNull()?.toLongOrNull()
    val stale = savedBoot!=curBoot || (started!=null && SystemClock.elapsedRealtime()-started > 60*60*1000)
    if(stale) deleteRecursively(dirfdVerified)
    ```
    Never `File.lastModified()` vs `elapsedRealtime`. Use dirfd-verified delete depth check.
15. Keep `flux_install.sh` for `FLUX_ROOTFS_PATH` generic tarballs (python path). Fast path only for app-packaged `FLUX_ROOTFS_SHA256` compiled-in.

### PR3 — perf/prebuilt-debian-dev-rootfs — Golden rootfs with capabilities + signed artifacts

16. CI `.github/workflows/build-golden-rootfs.yml` on **`ubuntu-24.04-arm`** native (not qemu): `debian:13` base, install build deps once + `apt clean`.
17. Node 26 to **`/opt/nativecode/node-v26`** canonical (not `/opt/nodejs`), symlink `/usr/local/bin/node|npm|npx`. Apply `optimize_debian_rootfs_perf.sh --safe` as lib (`99nativecode-perf`, `policy-rc.d 101`, `C.UTF-8`, `ldconfig`, `dash` check, tmp 1777).
18. Shell speed: minimal `.zshenv` (env only), `.zcompdump` prebuilt, no plugin scans.
19. Create **` /etc/nativecode/image.json`** (replaces boolean marker):
    ```json
    { "schema":2, "imageVersion":"2026.08.24.1", "profile":"core", "capabilities":["base","git","zsh"] }
    // dev: ["base","git","zsh","compiler","node26","npm","ccache"]
    // desktop: +["xfce","mesa"]
    ```
    Bake `flux:x:1000:1000::/home/flux:/bin/zsh` + home/group/sudoers/login shell/caches in CI; installer does not inject UID.
20. Make `setup_debian_family.sh` / `setup_cli_tools.sh` capability-aware: `if jq -e '.capabilities|index("node26")' /etc/nativecode/image.json >/dev/null; then skip Node provisioning; fi` vs `base` marker boolean. Device fixups (resolv/hostname) remain tiny steps in fast installer.
21. Pack `tar --sort-name --owner=0 --group=0 --mtime=@<epoch> -I "zstd -6 -T0" -cf nativecode-debian-{core,dev,desktop}-arm64.tar.zst -C rootfs .` + `.tar.xz` fallback. Outputs: tar.zst, `rootfs-manifest.json` (with `compressedBytes/maxExtractedBytes/maxFiles/sha256/profile`), `rootfs-files.sha256`, `SBOM.txt`, `SHA256SUMS`, `image.json`.
22. **Authenticated artifacts**: `rootfs-manifest.json` signed with Ed25519 (CI private key, public key embedded in APK `assets/keys/rootfs_ed25519.pub`). Alternative A (simpler, less flexible): embed exact version+SHA in `BuildConfig.FLUX_ROOTFS_SHA256` and require app update for rootfs update. Plan implements **B**:
    ```kotlin
    interface RootfsArtifactProvider {
      fun resolve(profile: String): Artifact // {url, sha256, sig}
      fun verify(manifest: ByteArray, sig: ByteArray): Boolean // Ed25519 pub in APK
    }
    class BundledAssetProvider : RootfsArtifactProvider // core in APK
    class PlayAssetPackProvider : RootfsArtifactProvider // on-demand asset pack (preferred for Play)
    class SignedReleaseProvider : RootfsArtifactProvider // GitHub Release with sig
    ```
    APK includes only `core` (keeps APK <150 MB product budget); `dev/desktop` fetched via provider, SHA checked after signature. Document 150 MB as budget not arch limit; investigate Play Asset Delivery per <source: Play Console Help>.
23. Update `app/build.gradle.kts` `noCompress+=zst`, benchmark 4-format table `APK size | decompress time | install time` using PR2 dual-format before winner choice; keep xz fallback.
24. Reference-tree parity check (P0 gate before ship): extract same archive with current `python proot-distro` extractor and `NativeRootfsExtractor` to two temp dirs, build manifests `relative path | type | mode | size | sha256 | symlink target`, `diff -u`; expect 100% parity, ownership `0:0` vs flux diff documented only.

### PR4 — perf/proot-fast-launcher — Direct exec, no Python, no double shell

25. `ProotFastCommandBuilder.kt`: safety `PACKAGE_NAME==com.zenithblue.nativecode`, `libproot.so` exists; `loadFluxIds()` parse `${ROOTFS}/etc/passwd` `flux:x:1000:1000`; fallback 0:0 for root-exec, defer if ROOTFS absent. `buildBinds(profile)` cli: `/dev,/proc,/sys, urandom→random, $PREFIX/tmp→/tmp, $ROOTFS/tmp→/dev/shm, sys_empty→/sys/fs/selinux, storage /sdcard if readable`; turnip + `/system,/vendor,/apex,/linkerconfig,/dev/kgsl-3d0,/dev/dri`; virgl + X11 socket; compat = full distro set plus `/odm,/product,/system_ext,/data/app,dalvik-cache,$PREFIX,$HOME`. Flags `--kill-on-exit --link2symlink -L --change-id=uid:gid --rootfs=ROOTFS --cwd=HOME` + optional `--sysvipc/--kernel-release` behind flags. Env `env -i HOME/USER/LOGNAME/PATH/LANG=C.UTF-8/TERM/TMPDIR/XDG_RUNTIME_DIR/NVM_DIR/DEBIAN_FRONTEND=noninteractive/FLUX_QUIET_SHELL=1` + gpu vars; `buildGuestPath()` = `$HOME/.local/bin:$HOME/bin:$HOME/.cargo/bin:/opt/nativecode/node-v26/bin:…` host-side `File(rootfsHome+"/.nvm/versions/node").listFiles()?.sortedWith(compareBy{SemVer})?.lastOrNull()` fallback. Exec `exec proot $FLAGS $BINDS /usr/bin/env -i $ENV "$@"`, shell strings via `/bin/dash -c`, bash only when needed, interactive `zsh -l`, never `zsh -c "bash -lc"`. Keep `ProotCommandBuilder` as compat fallback.
26. Wire `LinuxCommandBuilder.kt:24-25` flag `proot_fast_launcher`; `ShellCommandRunner.kt` keep `linker64` prefix for ET_DYN. Update `TerminalService.kt`/`MainActivity.kt` workDir/profile (login vs exec). Ship `nativecode_proot_fast.sh` synced to `/opt/nativecode/node-v26`.

### PR5 — perf/proot-shell-node — Zsh + Node lazy

27. Rewrite `/home/flux/.zshenv` env-only, `.zprofile` minimal, `.zshrc` interactive `compinit -C` with cached `.zcompdump`; remove `nvm.sh` from `.zshenv`; lazy:
    ```bash
    nvm() { unset -f nvm; [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"; nvm "$@"; }
    ```
    Bundled `/opt/nativecode/node-v26` + `/usr/local/bin` symlinks; NVM optional; `~/.npmrc` `audit=false fund=false progress=false cache=$HOME/.cache/npm` internal never `/sdcard`; prefer single-binary AI CLIs; remove `--ignore-scripts` fallback (allowlist only).

### PR6 — perf/proot-session-runner — Persistent broker (depends on launcher)

28. **Broker bind**: host `$filesDir/run/proot-broker/` (0700) bind-mounted as `/run/nativecode-host/` inside guest (broker-only bind, not shared `/tmp`). Socket `broker.sock` 0600, same FS view both sides. Directory via dirfd, `mkdirat` 0700, `bind` + `listen` with `0600` chmod.
29. **Lifecycle + auth**: `ProotSessionRunner.kt` requires `proot_fast_launcher==true` (if off → fallback to `ProotFastCommandBuilder.exec`). Broker started with `TerminalService`/`NativeCodeFgs` owner `OWNER_BROKER`, `START_NOT_STICKY`, `Process.destroyForcibly` on service destroy, `DirectBoot` aware. **Parent-death**: tiny native launcher `native_proot_launcher.cpp` does `prctl(PR_SET_PDEATHSIG,SIGKILL); if(getppid()!=expected) _exit(1); execve(proot,…)`. Chain `app dies → PDEATHSIG → outer proot dies → --kill-on-exit → guest tree dies`. No `killall` by name. Auth `SO_PEERCRED` (uid==appUid) + random per-session capability token passed via env `NATIVECODE_BROKER_TOKEN`.
30. Protocol `exec{id,argv,env,cwd,pty?} → stream{stdout,stderr,exit}`; fallback to direct exec if socket missing. Warm caches on internal storage, short `$HOME/projects/<name>` not `/sdcard`.

### PR7 — perf/proot-syscall-experiments — Flagged, last

31. Count syscall classes via aggregate counters; verify `proot -V`, `proot -v 1`, seccomp filter, `process_vm_readv/writev`; if missing vendor Termux proot commit gated on Android 13–16; experiment path cache behind `proot_experimental_cache` default off; publish per-area target table, note 4K metadata cannot guarantee chroot parity.

Across PRs: never `rm -rf` outside verified `.../containers/.debian.installing-*` via dirfd, never `su/mount/umount/remount/sysctl/drop_caches/dev/block/governor/killall/pkill/system_server/SurfaceFlinger/DRM` (veto), streaming 64–256 KiB only, thermal pause via `DeviceRequirements`.

## File-Level Change Map

| File | Change | Rationale |
|------|--------|-----------|
| `app/build.gradle.kts:32-43` | **EDIT** remove hardcoded passwords, env/property signing | P0 credential rotation |
| `.gitignore` | **EDIT** add `keys/` | hygiene |
| `docs/setup/signing.md` | **NEW** CI secret setup | docs |
| `app/src/main/cpp/CMakeLists.txt` | **NEW** imported targets cmake | correct NDK PIC build |
| `app/src/main/cpp/third_party/*` | **NEW** `libarchive.a/libzstd.a/liblzma.a/libcrypto.a` + `README.md` with provenance | reproducible, minimal |
| `app/src/main/cpp/native_rootfs_extractor.cpp` | **NEW** streaming parser+SHA+ caps | PR2 |
| `app/src/main/cpp/native_rootfs_writer.cpp` | **NEW** dirfd writer O_NOFOLLOW+ symlink/hardlink | PR2 security |
| `app/src/main/cpp/native_rootfs_lock.cpp` | **NEW** flock(2) on `locks/debian.lock` | upstream interop |
| `app/src/main/cpp/native_proot_launcher.cpp` | **NEW** prctl PDEATHSIG launcher | PR6 orphan fix |
| `app/src/main/java/com/zenithblue/nativecode/terminal/FastRootfsInstaller.kt` | **NEW** fresh-install-only, RENAME_NOREPLACE, caps | PR2 core |
| `app/src/main/java/com/zenithblue/nativecode/terminal/NativeRootfsExtractor.kt` | **NEW** JNI wrapper + progress | UI |
| `app/src/main/java/com/zenithblue/nativecode/terminal/RootfsArtifactProvider.kt` | **NEW** Bundled/Play/Signed providers + Ed25519 verify | PR3 auth |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ProotFastCommandBuilder.kt` | **NEW** direct proot, profiles, `/opt/nativecode/node-v26` | PR4 |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ProotSessionRunner.kt` | **NEW** broker client, private bind, SO_PEERCRED+token, requires launcher | PR6 |
| `app/src/main/java/com/zenithblue/nativecode/terminal/SafeBenchController.kt` | **NEW** host thermal/storage/Mem poll | PR1 watchdog split |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ProjectPathResolver.kt` | **EDIT** stagingDir/brokerSocket/imageJson helpers + cleanStaleStaging boot_id | SSOT |
| `app/src/main/java/com/zenithblue/nativecode/terminal/TermuxHostPaths.kt` | **EDIT** `IMAGE_JSON=/etc/nativecode/image.json` | marker→capabilities |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ProotCommandBuilder.kt` | **EDIT** keep compat fallback | flag gate |
| `app/src/main/java/com/zenithblue/nativecode/terminal/LinuxCommandBuilder.kt` | **EDIT** route via Fast when flag | switchboard |
| `app/src/main/java/com/zenithblue/nativecode/OnboardingActivity.kt` | **EDIT** call FastRootfsInstaller, ALREADY_INSTALLED path | install flow |
| `app/src/main/java/com/zenithblue/nativecode/MainActivity.kt` | **EDIT** same | repair |
| `app/src/main/assets/scripts/nativecode_proot_fast.sh` | **UPDATE** sync Kotlin, unify node path | ADB mirror |
| `app/src/main/assets/scripts/flux_install.sh` | **EDIT** early exit when image.json capability present | no-op |
| `app/src/main/assets/scripts/setup_debian_family.sh` | **EDIT** capability check not boolean | PR3 |
| `app/src/main/assets/scripts/setup_cli_tools.sh` | **EDIT** lazy nvm, bundled node check | PR5 |
| `app/src/main/assets/rootfs/debian_13_core_arm64.tar.zst` | **NEW** core in APK; dev/desktop via Release/Play | size |
| `app/build.gradle.kts:ndkVersion+externalNativeBuild+noCompress zst` | **EDIT** NDK plumbing | build |
| `.github/workflows/build-golden-rootfs.yml` | **NEW** native arm64, image.json, signed manifest, parity | CI |
| `bench/nc-io-bench.c` | **NEW** glibc arm64, seq/rand/metadata, buffered/direct, fdatasync split | PR1 |
| `bench/nc-membench.c` | **NEW** syscall-free, pre-touch, volatile sink | PR1 |
| `bench/run-safe-suite.sh` | **NEW** guest limits | PR1 |
| `bench/result-schema.json` | **NEW** schema | SSOT |
| `docs/perf/proot-parity-v2.md` | **NEW** fingerprint template + before/after | gate |

No edits to `jniLibs` proot in early PRs; PR7 vendors only after matrix.

## Testing Strategy

**Unit (JVM + native gtest):**
- `FastRootfsInstallerTest` — fresh-only logic (absent→ok, present valid→ALREADY_INSTALLED, incomplete→repair), SHA streaming chunked, lock contention via 2 processes contending `locks/debian.lock` (flock NB), wrong SHA rejects, corrupt archive rejects, caps abort (maxBytes/maxFiles), app kill mid → staging only deleted.
- `NativeRootfsExtractorTest` + native `writer_test` — 12 zip-slip cases: absolute entry, `../`, symlink→child, absolute symlink target allowed, relative `..` target allowed but not followed, hardlink `../../host`, hardlink through symlink, device, FIFO, duplicate conflicting, very large file, too many entries. Also check dirfd invariant: `openat` with `O_NOFOLLOW` never follows planted symlink.
- `ProotFastCommandBuilderTest` — per-profile bind counts, env contains PATH/LANG/TMPDIR/gpu vars, unified `/opt/nativecode/node-v26`, never `zsh -c "bash -lc"`, safety refuses `com.termux`.
- `RootfsArtifactProviderTest` — Ed25519 verify accepts good sig rejects bad, bundled/play/signed routing.

**Instrumented / host:**
- Kill-mid-extract: `kill -9` installer at 25/50/75% → staging removed, `containers/debian` untouched, next install succeeds via `cleanStaleStaging` boot_id path.
- Archive format bench: same manifest as `.tar.xz` vs `.tar.zst` L3–6 vs `.tar` → table APK size vs decompress.
- Reference-tree parity: `python proot-distro` vs `NativeRootfsExtractor` same archive → manifests `path|type|mode|size|sha256|symlink` diff 100% identical except documented `uid:gid`.

**Device (MT6897 + Adreno750, Android 13–16):**
- Launch microbench 20-run medians cli profile, fast_launcher on, no double shell: `/bin/true` <150 ms, `/bin/sh -c true` <175 ms, `git --version` <200 ms; compat still exit 0.
- `SafeBenchController` + `nc-*` suite: sysbench cpu/stress-ng, seq 1M buffered+direct, rand 4K IOPS, metadata ops/s, dd 256 MiB, `npm/node --version`, AI CLI startup, small C compile, 100-file make vs ninja, git clone/status/diff on `/data/...` vs `/sdcard`.
- Thermal abort test: heat to `SEVERE` → controller aborts, toast, no continued stress.
- Provisioning: npm small pkg cold vs warm, TS install.
- Guards: storage <1 GiB abort, RAM cap `min(256MiB,avail/8)`, streaming buffers ≤256 KiB (extractor) vs bench 1 MiB/4 KiB documented separately.

**Regression:**
- Interactive colors/PATH/Node/npm/AI CLIs/SSH/WINCH/Ctrl-C/exit, `--shared-tmp`, storage binds.
- Install fresh/upgrade (no auto-replace)/interrupted/SHA-corrupt/`FLUX_ROOTFS_PATH` python path.
- Profiles cli/compat/turnip/virgl each 20/20 boots, `vulkaninfo` Turnip on Adreno when turnip.
- Chroot parity untouched.

**8-pass review** after PR2 and PR4/PR6; veto any `su/mount/killall` in PR2–PR6 perf diff.

## Acceptance Criteria (objective, verifiable)

- [ ] **AC0 Signing hygiene (P0)** — `git grep -n "storePassword\|keyPassword\|REDACTED"` zero hits; `app/build.gradle.kts` reads `gradle.properties`/env; GitHub secret rotated; `keys/` gitignored.
- [ ] **AC1 Fresh-destination install** — Per-SoC report (MT6897 + Adreno750). **Fresh-destination / local-archive install, 3-run median**, archive cache state recorded (cold vs warm page cache). Fast installer (core zst, `RENAME_NOREPLACE`) ≤1.15× direct `tar --use-compress-program=unzstd -xf` to `/data/local/tmp` equivalent size (±10% noise). No `rm -rf` window, no missing target.
- [ ] **AC2 No partial install** — Kill at 25/50/75% 10/10 leaves `containers/debian` untouched, next install succeeds without manual clean (stale GC boot_id path).
- [ ] **AC3 Archive security (12 cases)** — Unit rejects 6/6 escapes + hardlink escapes + device/FIFO/duplicate/oversize; accepts 3/3 legitimate (relative symlink, absolute symlink `/usr/lib→/usr/lib`, hardlink inside rootfs). Libarchive flags + dirfd writer invariant both verified; hardlink threat covered.
- [ ] **AC4 Launch latency** — MT6897 cli+fast_launcher, warm 20-run median: `/bin/true` <150 ms, `/bin/sh -c true` <175 ms, `git --version` <200 ms; `zsh -c "bash -lc"` never emitted (grep `ProotFastCommandBuilder` argv log).
- [ ] **AC5 Shell startup** — `dash -c 'echo ok'` no `nvm.sh`; `nvm` lazy loads only on invoke; `zsh -l` prompt <300 ms warm via `time zsh -ic exit` 5-run median.
- [ ] **AC6 Golden rootfs capabilities** — CI boots: `dpkg --audit` clean, `node --version` pinned 26.x at `/opt/nativecode/node-v26/bin/node` → `/usr/local/bin/node`, `99nativecode-perf` present, `C.UTF-8`, `/bin/sh→dash`, `/etc/nativecode/image.json` schema 2 with `profile`/`capabilities` correct per core/dev/desktop, `setup_*` skips based on capabilities not boolean marker, placeholder `flux:x:1000:1000` present.
- [ ] **AC7 Fair bench ratio** — Same `nc-io-bench` glibc arm64 in both envs reports seq-read/write (1M buffered+direct) + rand4k + metadata; `nc-membench` ratio vs `mbw` validated as `R_nc=proot_nc/chroot_nc`, `R_mbw=proot_mbw/chroot_mbw`, `abs(R_nc - R_mbw) ≤0.10` (not absolute throughput). `fio` absent from cross-env ratio with doc why shmget fails in chroot. Write reports `write_data_ns` + `fdatasync_ns`.
- [ ] **AC8 Profile correctness** — cli 5–7 binds, compat ~21, turnip includes `/system,/vendor,/apex,kgsl` when present, virgl includes X11 socket; each 20/20 boots exit 0.
- [ ] **AC9 Safety veto (scoped + exhaustive)** — Grep of **all added production lines in PR2–PR6** (`FastRootfsInstaller.kt`, `NativeRootfsExtractor.kt`, `ProotFastCommandBuilder.kt`, `ProotSessionRunner.kt`, `LinuxCommandBuilder.kt`, `ShellCommandRunner.kt`, `nativecode_proot_fast.sh`, `app/src/main/cpp/*`, `bench/*`, `.github/workflows/build-golden-rootfs.yml`) **excluding legacy `chroot/*` mounts** shows zero `mount|umount|remount|sysctl|drop_caches|/dev/block|governor|devfreq|swap|zram|killall|pkill|kill -9 -1|system_server|SurfaceFlinger|glmark.*drm` in device runtime code (CI workflow `mount` allowed separately, documented). Kill logic only `pid in sessionSet && uid==appUid`. Explicit file list in CI `veto.sh`.
- [ ] **AC10 Safe suite guards (buffer scope corrected)** — `SafeBenchController` aborts if free <1 GiB / thermal SEVERE / RAM cap; `run-safe-suite.sh` caps I/O 256 MiB, RAM `min(256MiB,avail/8)`, streaming **extractor/decompressor buffers ≤256 KiB**, **bench sequential buffer =1 MiB**, **rand buffer =4 KiB** (AC10 no longer claims bench buffers ≤256 KiB).
- [ ] **AC11 Flag dependency** — Four flags `proot_fast_install`, `proot_fast_launcher`, `proot_persistent_runner` (requires launcher, unavailable if launcher off → fallback), `proot_experimental_cache` (requires launcher). Each off → legacy for that layer; `fast_install` off + `fast_launcher` off → exact `python proot-distro` path; switchable without reinstall; broker lifecycle `var/run` 0700/0600 + `START_NOT_STICKY` + `NativeCodeFgs` + `PR_SET_PDEATHSIG`.
- [ ] **AC12 No regression** — `docs/environment/plans/regression-test-suite.md` P0 gates pass both devices after PR4/PR6 (elf proot, cmd true, no com.termux, chroot enter).
- [ ] **AC13 Reference-tree parity** — Same archive extracted by current python vs `NativeRootfsExtractor` → manifest parity 100% path/content/symlink-target, ownership diff documented only.

## Risks & Mitigations (NEW_RISKS)

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| libarchive escape if SECURE_* misconfigured | Low if dirfd writer authoritative | High | Dirfd `O_NOFOLLOW`+`fstatat` writer is source of truth; libarchive flags only hint; 12-case unit suite + parity test; stage under verified dirfd only |
| NDK bloat / missing arch / generic .a | Medium | APK bloat or link fail | Only `arm64-v8a` PIC `-fPIC` API26+ built via NDK; minimal tar+xz+zst config; prebuilt with provenance README + licenses; fallback to python if load fails |
| Fresh-only scope frustrates users wanting upgrade | Low | UX | Document `ALREADY_INSTALLED` toast with repair/reset explicit flow; future `RENAME_EXCHANGE` migration preserves `/home/flux` after design |
| Zstd size vs xz winner wrong | Medium | APK size / decompress | Dual-format PR2, 4-format table before PR3, keep xz fallback, `noCompress` prevents double-compress |
| Accelerator missing (seccomp/process_vm) 0.65× still | High on some kernels | Perceived regression | PR7 profile `proot -V`+`proot -v 1` evidence; vendor Termux proot commit gated on 13–16 matrix; cache flagged off |
| Lazy NVM breaks shell | Low | Shell compat | Keep legacy flag, test `nvm use`+AI CLIs, upgrade note |
| Broker orphan | Low | Battery/FGS | `filesDir/run` 0700/0600, bind to `/run/nativecode-host`, PDEATHSIG launcher, `kill-on-exit`, `START_NOT_STICKY`, SO_PEERCRED+token, refcount to FGS |
| Fio/sysbench confusion re-add | Low | Unfair ratio | Doc why shmget fails, enforce `nc-io-bench` only for ratio, ratio criterion `abs(R_nc-R_mbw)≤0.10` |
| Thermal/storage watchdog | Medium | Brick/heat | Host `SafeBenchController` aborts, storage/RAM caps, streaming only, toast |
| Proroot premature adoption | Low | Stability | Keep `NATIVE_GLIBC` future only, track #5245 not prod |
| Stale .old backup GC leak | Low | Storage | PR2 has no `debian.old` (fresh-only RENAME_NOREPLACE); future EXCHANGE later; stale `.debian.installing-*` GC via boot_id+elapsedRealtime |
| Signing rotation incomplete | High if missed | Credential leak | PR0 blocks release; history scrub if needed; CI env validation |

## Handoff to Plan Reviewer

**Prior REVISE 10 blockers — all reshaped; PR1 approved to proceed, PR2 now unblocked:**

1. **Atomicity** — Fresh-install-only `renameat2(RENAME_NOREPLACE)` on dirfd `cfd` + `fsync(cfd)`, no `debian→old` window; future upgrades deferred to `RENAME_EXCHANGE` with home preservation (not in PR2). Verify no missing-target window.
2. **Extraction security** — Dirfd `O_NOFOLLOW` writer authoritative, `canonicalPath` only sanity check; symlink content may be absolute, writer never follows symlink; hardlink escape added; libarchive flags are entry-path hints not content policy. Verify 12-case matrix + `hardlink through symlink`.
3. **CMake/NDK dual-format** — Imported targets, arm64-v8a API26+ `-fPIC` NDK-built PIC `.a`, minimal tar+xz+zst, provenance + licenses, boringssl SHA256 documented, `libs.versions.toml` not used. Verify xz+zst before PR3.
4. **APK split** — `RootfsArtifactProvider` abstraction (Bundled/PlayAssetPack/SignedRelease), core in APK, dev/desktop via signed manifest (Ed25519 pub in APK) or Play Asset Delivery; 150 MB = budget not hard limit. Verify PLAY vs Release transport.
5. **Node path** — `/opt/nativecode/node-v26` consistently in CI + `ProotFastCommandBuilder.buildGuestPath` + `nativecode_proot_fast.sh` + image.json capabilities; no `/opt/nodejs` remains. Verify semver sort Kotlin vs `sort -V`.
6. **Broker** — Private `filesDir/run/proot-broker → /run/nativecode-host` bind 0700/0600, `SO_PEERCRED+token`, `START_NOT_STICKY` + `NativeCodeFgs` refcount + `PR_SET_PDEATHSIG` native launcher + `--kill-on-exit`; requires launcher. Verify guest visibility + orphan chain `pdeathsig→outer proot→kill-on-exit`.
7. **Benchmark fairness** — Glibc arm64 binary, `seq/rand/mix/metadata` interface, `CLOCK_MONOTONIC_RAW` + `posix_memalign` + `write_data_ns/fdatasync_ns` split + buffered/direct fields, host `SafeBenchController` thermal abort, ratio `abs(R_nc-R_mbw)≤0.10`. Verify definitions.
8. **Safety AC9** — Scan **all** new production files (FastRootfsInstaller, NativeRootfsExtractor, ProotFastCommandBuilder, ProotSessionRunner, LinuxCommandBuilder, ShellCommandRunner, nativecode_proot_fast.sh, cpp, bench) excluding legacy chroot mounts; separate CI workflow mount allowed. Verify glob covers ProotFastCommandBuilder/ProotSessionRunner.
9. **UID injection** — CI bakes `flux:1000:1000`, installer verifies not injects appUid; host UID vs guest UID documented as distinct; rule do not ship conversion until proven. Verify PR4 parse.
10. **Golden marker→capabilities** — `image.json` schema 2 with `capabilities`, setup scripts capability-aware not boolean, flux home baked in CI. Verify core vs dev node skip.
11. **Stale GC** — `.boot_id` + `.started_elapsed_ms` + `elapsedRealtime` + `boot_id` comparison, not `File.lastModified`. Verify no wall-clock vs uptime mix.
12. **Auth manifest** — Ed25519 signed `rootfs-manifest.json` gates archive SHA, caps enforce before commit, caps abort on `maxExtractedBytes/maxFiles`. Verify signature flow.
13. **Signing P0** — `app/build.gradle.kts:32-37` removed, env/property inject, rotate.
14. **Parity gate** — Reference-tree 100% manifest parity required before PR2 ship.
15. **AC terminology** — `fresh-destination / local-archive install 3-run median` not "cold", buffer scope split.

**Requested verdict**: `PLAN_STATUS: APPROVED` (or `APPROVE` with `PR1:IMPLEMENT, PR2:IMPLEMENT, PR3:IMPLEMENT_AFTER_PR2, PR4:IMPLEMENT_AFTER_PR1_BASELINE, PR5:AFTER_PR4, PR6:AFTER_PR4+PDEATHSIG, PR7:EXPERIMENTAL_ONLY`) — worker may start PR1 immediately per prior approval, PR2 unblocked by this revision.

If REVISE, annotate which PR(s) still need reshaping; if BLOCK, state ptrace hard-limit unachievable target.

