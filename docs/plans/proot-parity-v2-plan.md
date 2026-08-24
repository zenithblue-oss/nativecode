# Plan: proot-parity-v2 — Product-level PRoot/Chroot Parity (Rev 4)

## Task Summary

Achieve **product-level parity** between rootless PROOT and rooted chroot without claiming impossible ptrace parity. Targets: install ~1.0–1.15× `tar -xf` to `/data/local/tmp`, launch `/bin/true` <150 ms (vs 816 ms today), CPU ~95–100%, RAM memcpy ~95–100%, syscall-heavy/seq-I/O gap documented. Delivered via (1) transactional native extractor (one-pass SHA+decompress, per-component dirfd+flock, RENAME_NOREPLACE fresh-install-only, syncfs durability), (2) CI golden rootfs with capability manifest + native Ed25519 verified manifest + preserved numeric ownership, (3) direct PRoot launcher argv-only no host-shell reparse + profiled binds (`/opt/nativecode/node-v26` unification), (4) shell/Node lazy init, (5) optional persistent broker via socketpair + pdeathsig (not env token), (6) fair Debian/glibc bench suite + host DI thermal watchdog, (7) fail-closed release signing + feature-flagged rollout. **P0 repo hygiene**: remove `app/build.gradle.kts:32-37` signing credentials from VCS, fail-closed.

> Rev 4 addresses Iteration 5 REVISE (PASS 5): P0-1 fail-closed signing, P0-2 per-component O_NOFOLLOW + lock-before-GC, P0-3 no rename fallback, P0-4 restore aid_* fixups, P0-5 libarchive responsibility split + durability + 6 PR3/PR4/PR6/AC corrections. PR1 already APPROVED.

## Research Sources

- <source: app/src/main/java/com/zenithblue/nativecode/terminal/ProotCommandBuilder.kt:6-36 | current `libbash → python proot-distro → zsh -c` double shell>
- <source: bootstrap_root/.../site-packages/proot_distro/locking.py:21-72,244-272 | `ContainerLock` flock(2) on `$PREFIX/var/lib/proot-distro/locks/<name>.lock`, shared=login/run inheritable, exclusive=install, non-blocking>
- <source: bootstrap_root/.../site-packages/proot_distro/constants.py:75-77 | `RUNTIME_DIR=$PREFIX/var/lib/proot-distro`, `LOCKS_DIR`, `CONTAINERS_DIR`>
- <source: bootstrap_root/.../site-packages/proot_distro/commands/install.py:49-282 | `write_resolv_conf_at()`, `write_hosts_at()`, `register_android_ids_at()`, `setup_fake_sysdata()` sequence; new review P0-4 restores aid_*>
- <source: bootstrap_root/.../site-packages/proot_distro/helpers/rootfs.py:38-134 | `register_android_ids` appends `aid_<user>:x:<uid>:<gid>` + groups, not flux→appUid rewrite>
- <source: bootstrap_root/.../site-packages/proot_distro/commands/login/__init__.py:85,384,519 | shared inheritable flock, fake sysdata>
- <source: https://github.com/proot-me/proot/blob/master/doc/proot/manual.rst | `--change-id=uid:gid` virtualizes app-owned files as 1000:1000 without chown>
- <source: https://developer.android.com/reference/java/security/Signature | `Signature.getInstance("Ed25519")` only platform-provided from API 33+, so minSdk 26 must use bundled BoringSSL>
- <source: app/src/main/assets/scripts/nativecode_proot_fast.sh:1-369 | direct proot model exists but uses `/opt/nodejs` legacy path>
- <source: app/build.gradle.kts:32-43 | **P0**: `storePassword="REDACTED"` committed — must rotate, fail-closed per review>
- <source: https://github.com/libarchive/libarchive/blob/master/libarchive/archive.h | `ARCHIVE_EXTRACT_*` via `archive_write_disk_set_options()` — irrelevant when using custom dirfd writer>
- <source: https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run/choose-the-runner-for-a-job | `ubuntu-24.04-arm` valid>
- <source: https://support.google.com/googleplay/android-developer/answer/9859372 | Play Asset Delivery on-demand packs; 150 MB product budget>
- <source: docs/environment/proot-vs-chroot-baseline.md + proot-vs-chroot-perf-report-mediatek.md | Snapdragon vs MT6897 device-dependent>
- <source: docs/environment/proot-fast-launcher-design.md:1-250 | 816 ms distro vs 104 ms plain proot vs 30 ms chroot>
- <source: git log --oneline -20 | master 980ca24>

## Current Architecture

**CHROOT** `setup_debian13_chroot.sh`: `assets/rootfs/debian_13_rootfs.tar.xz → /data/local/tmp/chrootDebian13 .flux_configured` native `tar/unxz` with preserved numeric ownership.

**PROOT today** `flux_install.sh:163-261`: APK `debian_13_rootfs.tar.xz` (xz only, SHA `13e29f…6803`) → `sha256sum` full read → `python proot-distro install` (holds exclusive `ContainerLock("debian")`, login holds shared inheritable) → `proot-distro login --shared-tmp -- bash /tmp/flux_setup_temp.sh` → `setup_debian_family.sh` apt + `setup_cli_tools.sh` NVM on device. Two reads, thousands ptrace ops. Post-extract does 4 fixups per `install.py:273-282` + `rootfs.py:38-134`.

**Runtime** `ProotCommandBuilder.kt:18-31` always `arrayOf(shell,"-c","exec python $prootDistro login … -- zsh -c '…'")` (host shell re-parse). Chroot uses direct `nativecode_chroot.sh`. Fast launcher asset already implements `exec proot --kill-on-exit --link2symlink -L --change-id --rootfs --cwd` + `env -i` but not wired, still `/opt/nodejs`.

**Guest**: `setup_*.sh` + `optimize_debian_rootfs_perf.sh` run inside PRoot. Node via NVM `source nvm.sh` every shell; `jq` not guaranteed core.

## Affected Components & Dependencies

| Component | Files | Impact |
|-----------|-------|--------|
| **Signing hygiene (P0-1)** | `app/build.gradle.kts:32-43`, `keys/`, CI secrets | Fail-closed release signing, rotate, `.gitignore` keys, debug still builds |
| **Rootfs asset & build** | `app/src/main/assets/rootfs/`, `app/build.gradle.kts:androidResources`, `assemble_bootstrap.py` | core in APK, dev/desktop via verified provider or Play Asset Delivery; zst+xz dual; `--numeric-owner` |
| **Fast installer (Kotlin+JNI)** | `flux_install.sh`, `TermuxHostPaths.kt`, `OnboardingActivity.kt:3290`, `MainActivity.kt:12771`, `ProjectPathResolver.kt` | NEW `FastRootfsInstaller.kt`, `NativeRootfsExtractor` JNI (libarchive+libzstd+liblzma, arm64-v8a NDK PIC), per-component dirfd, flock before GC, syncfs, RENAME_NOREPLACE no fallback, aid_* fixups |
| **Proot launcher** | `ProotCommandBuilder.kt`, `LinuxCommandBuilder.kt`, `ShellCommandRunner.kt`, `nativecode_proot_fast.sh` | NEW `ProotFastCommandBuilder.kt` argv-only, profiles cli/turnip/virgl/compat, unified `/opt/nativecode/node-v26` |
| **CI golden rootfs** | `setup_debian_family.sh`, `setup_cli_tools.sh`, `optimize_debian_rootfs_perf.sh`, `.github/workflows/*` | Workflow builds core/dev/desktop arm64.tar.zst + `image.json` + signed `rootfs-manifest.json` (raw-byte verify), `nativecode-has-cap` helper, numeric owner |
| **Shell/Node** | guest `~/.zshenv/.zprofile/.zshrc`, `ChrootCommandBuilder.kt:247-283` | lazy nvm function, bundled `/opt/nativecode/node-v26` |
| **Benchmark suite** | `docs/environment/bench*` | NEW `nc-io-bench.c` (glibc arm64), `nc-membench.c`, `SafeBenchController.kt` + `ThermalStatusProvider` DI, `bench/run-safe-suite.sh` |
| **Persistent broker** | `TerminalService.kt`, `NativeCodeFgs.kt` | `ProotSessionRunner.kt` + native `native_proot_launcher.cpp` pdeathsig, **socketpair inherited FD** not env token, private bind fallback |
| **Safety** | `DeviceRequirements.kt` | thermal/storage/RAM guards, veto grep, DI thermal test |

## Implementation Steps (ordered, smallest correct change)

### PR0 — repo/p0-signing-rotation — Must land before any release/PR merge (no behavior change)

0. **Fail-closed release signing (P0-1 fix)**: replace `app/build.gradle.kts:32-43`:
    ```kotlin
    val releaseKeystore = providers.gradleProperty("nativecode.keystore.path")
        .orElse(providers.environmentVariable("NATIVECODE_KEYSTORE"))
    val releaseKeystorePwd = providers.gradleProperty("nativecode.keystore.password")
        .orElse(providers.environmentVariable("NATIVECODE_KEYSTORE_PASSWORD"))
    val releaseKeyAlias = providers.gradleProperty("nativecode.key.alias")
        .orElse(providers.environmentVariable("NATIVECODE_KEY_ALIAS"))
    val releaseKeyPwd = providers.gradleProperty("nativecode.key.password")
        .orElse(providers.environmentVariable("NATIVECODE_KEY_PASSWORD"))
    val hasReleaseSigning = releaseKeystore.isPresent && releaseKeystorePwd.isPresent

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystore.get())
                storePassword = releaseKeystorePwd.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPwd.get()
            }
        }
    }
    buildTypes {
        release {
            if (!hasReleaseSigning) {
                throw GradleException("Release signing credentials are required for assembleRelease (set nativecode.keystore.* or NATIVECODE_KEYSTORE*)")
            }
            signingConfig = signingConfigs.getByName("release")
        }
        // debug uses default debug keystore, always builds
    }
    ```
    Alternative if strict at config time breaks debug: gate throw only when `gradle.startParameter.taskNames.any{it.contains("Release", ignoreCase=true)}`. Add `keys/` to `.gitignore`, rotate `REDACTED` (if Play App Signing in use, handle as upload-key rotation per Play model, not app-signing key replacement), store new secret `NATIVECODE_KEYSTORE_BASE64` + env in GitHub Actions. Verify `git log --diff-filter=D` history, `git filter-repo` note if needed. Document in `docs/setup/signing.md`. **AC19**: `assembleRelease` without creds MUST FAIL, `assembleDebug` MUST PASS.

### PR1 — perf/proot-benchmark-v2 — Fair measurement before speedup — APPROVED

1. **Toolchain**: compile `bench/nc-io-bench` and `bench/nc-membench` as **ARM64 Debian/glibc binaries** (not NDK/Bionic). Build on `ubuntu-24.04-arm` with `aarch64-linux-gnu-gcc` against glibc; same ELF in both PRoot and chroot. Add `bench/README.md` with `file` proof.
2. **`bench/nc-io-bench.c`**:
    ```
    nc-io-bench prepare FILE --size 256M
    nc-io-bench seq-read FILE --bs 1M [--direct]
    nc-io-bench seq-write FILE --bs 1M
    nc-io-bench rand-read FILE --bs 4K --jobs 1 --time 10s
    nc-io-bench rand-write FILE --bs 4K --jobs 1 --time 10s
    nc-io-bench rand-mix FILE --bs 4K --read-pct 70 --time 10s
    nc-io-bench metadata DIR --files 4096
    ```
    Impl: `clock_gettime(CLOCK_MONOTONIC_RAW)` `now_ns()`, `posix_memalign(&buf,4096,1<<20)` seq 1 MiB / 4 KiB rand, `pread/pwrite` loops, `xorshift64` pre-generated indices before timing. Report `write_data_ns` + `fdatasync_ns` separately (`fdatasync(fd)` timed). Provide `buffered` + `direct-if-supported` (`O_DIRECT` fallback if `EINVAL`). No `drop_caches`, no `/dev/block`, no `shmget`.
3. **`bench/nc-membench.c`**: 8–64 MiB, `memcpy/memset/RO-scan/WO-scan/random`, pre-touch `for(i=0;i<size;i+=4096) src[i]^=1`, timed `memcpy` loop, `volatile sink` + `asm volatile(""::"r"(dst):"memory")`, indices pre-generated, zero syscalls inside loop.
4. **`bench/SafeBenchController.kt`** + **`bench/run-safe-suite.sh`** + **`ThermalStatusProvider` DI (P0 thermal fix)**:
    ```kotlin
    interface ThermalStatusProvider { fun current(): Int }
    class AndroidThermalStatusProvider(val power: PowerManager): ThermalStatusProvider {
        override fun current() = power.currentThermalStatus // API>=29
    }
    class FakeThermalStatusProvider(var status: Int): ThermalStatusProvider
    ```
    Controller polls `provider.current()` every 2s via `Handler.postDelayed` while child alive; if `SEVERE|EMERGENCY` abort child `SIGTERM→SIGKILL`, report `THERMAL_ABORT`. `StatFs` free<1 GiB abort, `ActivityManager.MemoryInfo` availMem, caps `I/O min(256MiB, free/10)` `RAM min(256MiB, avail/8)`. Shell sets `ulimit` + runs bench subcommands. **Test** uses `FakeThermalStatusProvider(SEVERE)` to trigger abort — never intentionally heat phone to SEVERE. Real device runs only passively observe thermal during short runs.
5. **`bench/result-schema.json`** + **`docs/perf/proot-parity-v2.md`** fingerprint: commit, `proot -V`, `proot -v 1 /bin/true` seccomp/process_vm, proot-distro version, Android/kernel/SoC/filesystem/SHA/thermal, archive cache state. Ban `glmark2-drm`, block devices, `fio`.
6. **Gate**: same binary both envs warm repeat <5% variance. Document `fio shmget Function not implemented` in chroot.

### PR2 — perf/proot-fast-extractor — Transactional native extraction (fresh-install-only, per-component dirfd+flock+ caps + durability)

7. **Scope**: fresh-install-only:
    ```
    target absent               → fast install allowed (RENAME_NOREPLACE)
    target present + valid      → ALREADY_INSTALLED never replace
    target present + incomplete → explicit repair/reset user-confirmed
    ```
    No `debian→debian.old→staging→debian` exchange; future `RENAME_EXCHANGE` only after home-preserving migration design.

8. **Locking — reuse upstream + per-component descent (P0-2)**: Do not use `FileChannel.lock()` on `.install.lock`. Use JNI `flock(2)` on `$PREFIX/var/lib/proot-distro/locks/debian.lock` (`RUNTIME_DIR/locks/debian.lock` per `constants.py:67`). Implement helper that validates every component:
    ```cpp
    int descend_no_symlink(int rootFd, std::initializer_list<const char*> parts){
      int fd = dup(rootFd);
      for(const char* part: parts){
        int next = openat(fd, part, O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
        if(next<0){ close(fd); return -1; }
        close(fd); fd = next;
      }
      return fd;
    }
    int prefixFd = open("/data/data/com.zenithblue.nativecode/files", O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
    int runtimeFd = descend_no_symlink(prefixFd, {"var","lib","proot-distro"});
    int locksFd = descend_no_symlink(runtimeFd, {"locks"});
    int containersFd = descend_no_symlink(runtimeFd, {"containers"});
    // locksFd -> openat("debian.lock", O_RDWR|O_CREAT|O_CLOEXEC|O_NOFOLLOW,0600)
    // flock(fd, LOCK_EX|LOCK_NB) keep open entire extraction+publish
    ```
    Open `PREFIX` itself via `open(...,O_NOFOLLOW)` and walk stepwise; `O_NOFOLLOW` only guards last component, so walk is required per upstream pattern. Login shared flock inheritable (`locking.py:519`) still interops. Filesystem no-flock → proceed unlocked with warning (match `locking.py:182`). **Acquire exclusive lock BEFORE stale GC** — once held, no normal PRoot-Distro login/install can race with GC.

9. **Dirfd atomic publish no fallback (P0-2+P0-3)**: never `canonicalPath.startsWith`. Keep `containersFd` alive entire op. Staging: `mkdirat(containersFd,".debian.installing-<rand>",0700)` → `mkdirat(stagingFd,"rootfs",0755)` → verify `fstatat(...AT_SYMLINK_NOFOLLOW)` is dir owned by app. Publish:
    ```cpp
    if (syscall(__NR_renameat2, containersFd, stagingName,
                containersFd, "debian", RENAME_NOREPLACE)<0){
      switch(errno){
        case EEXIST: return ALREADY_INSTALLED;
        case ENOSYS: case EINVAL: return FAST_PUBLISH_UNSUPPORTED; // delete staging, fallback legacy python
        default: return INSTALL_ERROR;
      }
    }
    // NO plain renameat() fallback — preserves never half-published/never replaced
    fsync(containersFd);
    ```
    No `debian→old` window; `debian` never absent. Future `RENAME_EXCHANGE` not in PR2.

10. **Secure extraction — single authority (P0-5)**: **Do not use `ARCHIVE_EXTRACT_SECURE_*` parser hints**. Those flags are for `archive_write_disk` (libarchive disk writer) and are meaningless when doing `libarchive read → custom dirfd writer`. Define:
    ```
    libarchive responsibility: compression detection, xz/zstd decompress, tar parse, expose archive_entry metadata/data
    NativeRootfsWriter responsibility: path validation, absolute-entry reject, .. reject, safe per-component descent, O_NOFOLLOW, duplicate policy, symlink creation without following, hardlink validation, special-file reject, size/count limits
    ```
    Writer invariant: before every `openat/mkdirat/symlinkat/linkat`, `fstatat(dirfd,name,AT_SYMLINK_NOFOLLOW)` detect symlink → abort entry; use `O_NOFOLLOW` on opens; `readlinkat` record; `ARCHIVE ENTRY PATH` must never escape staging; `SYMLINK CONTENT` may be absolute (`/usr/lib→/usr/lib`) or contain `..` — valid, stored verbatim via `symlinkat(target,dirfd,name)` never validated `!startsWith("/")`; danger is following it later. Hardlinks validate source inside staging via dirfd pair, reject `../../host` + `through symlink`. Reject device/FIFO/socket, duplicate conflicting, very large, too many. 12-case matrix: `entry /absolute`, `entry ../../escape`, `symlink→child`, `absolute symlink target allowed`, `relative .. allowed but not followed`, `hardlink ../../host`, `hardlink through symlink`, `device`, `FIFO`, `duplicate conflicting`, `very large declared`, `too many entries`.

11. **Bounded one-pass SHA + caps**: stream `compressed chunk → SHA256_update + decompressor → dirfd writer` (64–256 KiB streaming, never GB). Gate commit on `SHA==expected`; mismatch → delete staging uncommitted (dirfd-verified `unlinkat`). Enforce `rootfs-manifest.json` ceilings (from verified manifest):
    ```json
    { "schema":2, "profile":"core|dev|desktop", "compressedBytes":92381231, "maxExtractedBytes":1073741824, "maxFiles":125000, "sha256":"…", "maxSingleFile":1073741824 }
    ```
    Abort if `sum(size)>maxExtractedBytes||files>maxFiles||avail<reserve||single>maxSingleFile`. Mismatch/corrupt/`fdatasync` fail → unlinkat staging.

12. **Fixups after verify, before durability flush (P0-4)** — restore upstream `install.py:273-282` + `rootfs.py:38-134` semantics exactly, ordered:
    ```
    01 verify archive SHA
    02 validate rootfs structure (flux 1000:1000 present, /bin/sh→dash exists)
    03 safely replace /etc/resolv.conf (unlink then write, dirfd, handle symlink image case per rootfs.py:42)
    04 safely replace /etc/hosts (unlink then write per rootfs.py:57)
    05 safely register Android aid_* UID/GID records (append aid_<user>:x:<uid>:<gid> + aid_<group>:x:<gid>:root,aid_<user> per rootfs.py:76-134) — distinct from flux=1000:1000
    06 create complete fake sysdata compatible with current PRoot-Distro (setup_fake_sysdata)
    07 validate flux=1000:1000 still
    08 validate /bin/sh→dash
    09 durability flush (syncfs)
    10 RENAME_NOREPLACE publish + fsync(parent)
    ```
    `flux` remains `1000:1000` (PRoot `--change-id=1000:1000` virtualizes app-owned files per manual.rst) plus `aid_*` integration records. Do not `getAppUid()`→flux rewrite. If legacy UID proven needed, gated experiment with evidence.

13. **Durability flush before publish**: before `renameat2`, ensure:
    ```cpp
    if (syncfs(rootfsFd)!=0) return INSTALL_ERROR; // rootfsFd = dirfd of staging rootfs
    // then renameat2(RENAME_NOREPLACE)
    // then fsync(containersFd)
    ```
    Ordering: `all data written → SHA verified → fixups → syncfs → renameat2 → fsync(parent)`. Rename alone gives process-kill atomicity; syncfs adds power-loss durability.

14. **JNI + CMake (P0 build correctness)**:
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
      add_library(boringssl_crypto STATIC IMPORTED)
      set_target_properties(boringssl_crypto PROPERTIES IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/third_party/${ANDROID_ABI}/libcrypto.a")
      add_library(native_rootfs_extractor SHARED native_rootfs_extractor.cpp native_rootfs_lock.cpp native_rootfs_writer.cpp native_rootfs_crypto.cpp native_proot_launcher.cpp)
      target_link_libraries(native_rootfs_extractor archive zstd lzma boringssl_crypto log)
      target_compile_options(native_rootfs_extractor PRIVATE -fPIC -O2 -Wall -Werror)
      ```
    - `app/build.gradle.kts`: `ndkVersion="26.1.10909125"`, `externalNativeBuild.cmake.path file("src/main/cpp/CMakeLists.txt")`, `defaultConfig.ndk.abiFilters+="arm64-v8a"`, `packaging.jniLibs.useLegacyPackaging true`, `androidResources.noCompress+=listOf("zst","xz","tar")`. Only `arm64-v8a`. Fallback to python if `System.loadLibrary` fails.
    - **Reproducibility**: `third_party/README.md` source URL/commit/SHA256/build script/CFLAGS `-fPIC -O2 -DANDROID` NDK API 26+ licenses (`libarchive BSD`, `xz PD`, `zstd BSD`, `boringssl ISC`). Minimal libarchive `--enable-bsdtar=no --enable-bsdcpio=no --with-zstd --with-lzma` (tar+xz+zst only). Do not check in opaque `.a` without metadata.
    - `NativeRootfsExtractor.kt`: `fun extract(archiveFd: Int, containersDirFd: Int, verified: VerifiedRootfsArtifact, onProgress:(Long)->Unit): Result` — takes verified object not `File+SHA` string.
15. **Stale GC with lock held (P0-2 clock fix)**: GC runs **after** exclusive `debian.lock` acquired. Each staging creation writes `staging/.boot_id` (`/proc/sys/kernel/random/boot_id`) and `staging/.started_elapsed_ms` (`SystemClock.elapsedRealtime()`). GC:
    ```kotlin
    // caller already holds flock exclusive
    val curBoot = File("/proc/sys/kernel/random/boot_id").readText().trim()
    for(staging in listInstallings(containersFd)){
      val savedBoot = readViaDirfd(staging,".boot_id")?.trim()
      val started = readViaDirfd(staging,".started_elapsed_ms")?.toLongOrNull()
      val stale = savedBoot!=curBoot || (started!=null && SystemClock.elapsedRealtime()-started>3600000)
      if(stale) deleteRecursively(dirfdVerified)
    }
    ```
    Never `File.lastModified()` vs `elapsedRealtime`. Use dirfd-verified delete depth check (must be under `containers/.debian.installing-*`).

16. Keep `flux_install.sh` for `FLUX_ROOTFS_PATH` generic tarballs (python path). Fast path only for `VerifiedRootfsArtifact`.

### PR3 — perf/prebuilt-debian-dev-rootfs — Golden rootfs with capabilities + signed verified manifest

17. CI `.github/workflows/build-golden-rootfs.yml` on **`ubuntu-24.04-arm`**: `debian:13` base, install deps once + `apt clean`.
18. Node 26 to **`/opt/nativecode/node-v26`** canonical (not `/opt/nodejs`), symlink `/usr/local/bin/node|npm|npx`. Apply `optimize_debian_rootfs_perf.sh --safe` as lib.
19. Shell speed: minimal `.zshenv` (env only), `.zcompdump` prebuilt, no plugin scans.
20. Create **`/etc/nativecode/image.json`** (not boolean marker):
    ```json
    { "schema":2, "imageVersion":"2026.08.24.1", "profile":"core", "capabilities":["base","git","zsh"] }
    // dev: ["base","git","zsh","compiler","node26","npm","ccache"]
    // desktop: +["xfce","mesa"]
    ```
    Bake `flux:x:1000:1000::/home/flux:/bin/zsh` + home/group/sudoers/login shell/caches in CI; ensure `root/system` files `0:0`, `/home/flux` `1000:1000`; installer does not inject UID. Add helper **`nativecode-has-cap`** (not `jq`) to core:
    ```bash
    #!/bin/sh
    # usage: nativecode-has-cap node26
    python3 -c "import json,sys; d=json.load(open('/etc/nativecode/image.json')); sys.exit(0 if sys.argv[1] in d.get('capabilities',[]) else 1)" "$1"
    ```
    or if python not core, tiny `busybox sh` + `grep -q "\"$1\""` helper. `setup_debian_family.sh`/`setup_cli_tools.sh` become: `if nativecode-has-cap node26; then echo skip; else install_node; fi`.

21. **Pack with numeric ownership preserved (P0 ownership fix)**:
    ```bash
    mkdir -p rootfs/home/flux && chown 1000:1000 rootfs/home/flux
    # ensure root files 0:0 already
    tar --sort-name --numeric-owner --mtime="@${SOURCE_DATE_EPOCH}" -I "zstd -6 -T0" -cf nativecode-debian-{core,dev,desktop}-arm64.tar.zst -C rootfs .
    # + .tar.xz fallback
    ```
    Do not `--owner=0 --group=0` (destroys `/home/flux` 1000:1000). Then chroot preserves real ownership; proot virtualizes via `--change-id=1000:1000` per manual.rst. CI test:
    ```bash
    tar tvf rootfs.tar.zst | awk '$1~/^d/ && $6=="./home/flux" {print $2}' # must 1000/1000
    # also /etc/passwd 0:0, /usr/bin/bash 0:0
    ```
    Runtime test: `stat -c %u:%g /home/flux` → `1000:1000` in both chroot and PROOT.

22. **Outputs**: tar.zst, `rootfs-manifest.json` (with `schema, imageVersion, minAppVersionCode, arch, profile, artifact, compressedBytes, maxExtractedBytes, maxFiles, maxSingleFile, sha256, originAllowlist`), `rootfs-files.sha256`, `SBOM.txt`, `SHA256SUMS`, `image.json`, signature `rootfs-manifest.json.sig` (Ed25519 raw 64B).

23. **Authenticated before parse + Ed25519 on API26 (P0-Ed25519)**:
    ```kotlin
    data class VerifiedRootfsManifest(
      val schema: Int, val imageVersion: String, val profile: Profile, val arch: String,
      val sha256: ByteArray, val compressedBytes: Long, val maxExtractedBytes: Long,
      val maxFiles: Long, val maxSingleFile: Long, val artifactName: String,
      val minAppVersionCode: Int, val origin: String
    )
    sealed interface VerifiedRootfsArtifact {
      data class Bundled(val manifest: VerifiedRootfsManifest, val assetPath: String): VerifiedRootfsArtifact
      data class Signed(val manifest: VerifiedRootfsManifest, val downloadUrl: String): VerifiedRootfsArtifact
      data class PlayDelivered(val manifest: VerifiedRootfsManifest, val assetPack: String): VerifiedRootfsArtifact
    }
    ```
    Flow:
    ```
    download raw manifest bytes (no parse) + signature bytes
          ↓ JNI bundled BoringSSL Ed25519 verify over EXACT raw bytes (pub in APK assets/keys/rootfs_ed25519.pub)
          ↓ only now parse JSON
          ↓ validate: schema/profile/arch==aarch64||arm64-v8a/minAppVersionCode/size caps/artifact name/origin allowlisted (e.g. https://github.com/zenithblue-oss/nativecode-ai/releases/)
          ↓ download archive (allowlist origin only; never arbitrary URL from manifest)
          ↓ FastRootfsInstaller takes VerifiedRootfsArtifact (never File+SHA string)
          ↓ stream SHA → compare signed sha256
    ```
    Impl: `RootfsManifestVerifier.kt` → JNI `native_rootfs_crypto.cpp` → bundled `libcrypto.a` `ED25519_verify`. Do not use `Signature.getInstance("Ed25519")` (needs API33+) nor system BoringSSL. Record installed `imageVersion` for future anti-rollback (P1). Trust contract: PR2 fast path accepts **only** `VerifiedRootfsArtifact`; generic `FLUX_ROOTFS_PATH` continues via python path.

24. Update `app/build.gradle.kts` `noCompress+=zst`, benchmark 4-format table `APK size | decompress time | install time` using PR2 dual-format before winner; keep xz fallback. Document 150 MB as product budget not arch limit; investigate Play Asset Delivery.

25. **Reference-tree parity split into two gates (AC13A/B)**: extract same archive with current `python proot-distro install_from_local_file` and `NativeRootfsExtractor` to two temp dirs, build manifests `path|type|mode|size|sha256|symlink target|hardlink`:
    - AC13A **Extraction parity**: path/type/mode/size/SHA/symlink/hardlink 100% identical (ownership diff `0:0` vs flux documented only for extraction comparison).
    - AC13B **Installed-container compatibility**: full `FastRootfsInstaller` (with 01-10 fixups) vs full `proot-distro install` → check `resolv.conf`, `hosts`, `aid_*` passwd/group, `fake sysdata`, `bin/sh→dash`, `flux user`, `launch succeeds`, `apt/dpkg status` compatible. Install-time parity requires both.

### PR4 — perf/proot-fast-launcher — Direct exec, no Python, no double shell — APPROVED WITH ARGV REQUIREMENT

26. `ProotFastCommandBuilder.kt` **must build argv-only (P0 argv requirement)**, never host shell string `exec proot $FLAGS…`:
    ```kotlin
    val argv = mutableListOf<String>()
    argv += TermuxHostPaths.libProot(ctx).absolutePath // libproot.so
    argv += "--kill-on-exit"; argv += "--link2symlink"; argv += "-L"
    argv += "--change-id=$uid:$gid" // uid/gid from /etc/passwd flux 1000:1000
    argv += "--rootfs=$rootfs"; argv += "--cwd=$home"
    for(b in binds) argv += "--bind=$b"
    argv += "/usr/bin/env"; argv += "-i"
    env.forEach{(k,v)-> argv += "$k=$v"}
    argv += guestArgv // e.g. /bin/dash, /bin/true, /bin/zsh -l
    // start via ProcessBuilder(argv) + env clear, no intermediate shell
    ```
    Safety `PACKAGE_NAME==com.zenithblue.nativecode`, `libproot.so` exists; `loadFluxIds()` parse `${ROOTFS}/etc/passwd` `flux:x:1000:1000` fallback 0:0 defer if absent. `buildBinds(profile)` per design; `buildGuestEnv()` clean `env -i …` + gpu vars; `buildGuestPath()` unified `/opt/nativecode/node-v26`. **Only** explicit guest shell string reaches guest ` /bin/dash -c <single-arg>`; never `zsh -c "bash -lc"`. Keep `ProotCommandBuilder` compat fallback. Add tests for spaces/`'`/`"`/`$`/`;`/`&`/unicode/newline in project path — no host shell reparse.

27. Wire `LinuxCommandBuilder.kt:24-25` flag `proot_fast_launcher`; `ShellCommandRunner.kt` keep `linker64` prefix for ET_DYN app-data executables. Update `TerminalService.kt`/`MainActivity.kt` workDir/profile (login vs exec). Ship `nativecode_proot_fast.sh` synced to `/opt/nativecode/node-v26`, but clarify it is ADB debug mirror — Kotlin argv is canonical.

### PR5 — perf/proot-shell-node — Zsh + Node lazy — APPROVED

28. Rewrite `/home/flux/.zshenv` env-only, `.zprofile` minimal, `.zshrc` interactive `compinit -C` with cached `.zcompdump`; remove `nvm.sh` from `.zshenv`; lazy:
    ```bash
    nvm() { unset -f nvm; [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"; nvm "$@"; }
    ```
    Bundled `/opt/nativecode/node-v26` + `/usr/local/bin` symlinks; NVM optional; `~/.npmrc` `audit=false fund=false progress=false cache=$HOME/.cache/npm` internal never `/sdcard`; prefer single-binary AI CLIs; remove `--ignore-scripts` fallback (allowlist only).

### PR6 — perf/proot-session-runner — Persistent broker (depends on launcher) — REVISE auth

29. **Bind**: host `$filesDir/run/proot-broker/` (0700) bind-mounted as `/run/nativecode-host/` inside guest via broker-only bind (not shared `/tmp`). Socket `broker.sock` 0600 same FS both sides, dirfd `mkdirat` 0700.

30. **Lifecycle**: `ProotSessionRunner.kt` requires `proot_fast_launcher==true` (fallback to direct exec). Broker started with `TerminalService`/`NativeCodeFgs` owner `OWNER_BROKER`, `START_NOT_STICKY`, `Process.destroyForcibly` on destroy, `DirectBoot` aware. **Parent-death**: `native_proot_launcher.cpp` does `prctl(PR_SET_PDEATHSIG,SIGKILL); if(getppid()!=expected) _exit(1); execve(proot,…)`. Chain `app dies → PDEATHSIG → outer proot dies → --kill-on-exit → guest dies`. No `killall`.

31. **Authentication via socketpair not env (P0 PR6 fix)**: Do not `SO_PEERCRED(uid==appUid)+NATIVECODE_BROKER_TOKEN` env (same UID all guest processes, env leaks via `/proc/<pid>/environ` and propagates to children). Instead create connected `socketpair(AF_UNIX, SOCK_STREAM, 0, sv)` **before** starting PRoot:
    ```
    Host fd A ←→ socketpair ←→ fd B (passed as inherited FD 3 to guest broker via native launcher)
    Host ProotSessionRunner holds A
    Guest broker execs with B as command channel (no filesystem socket needed for primary channel)
    ```
    Arbitrary guest process cannot connect to anonymous socketpair by pathname. Impl: `native_proot_launcher.cpp` creates `socketpair`, `fcntl(FD_CLOEXEC)` clear on child side, `dup2(sv[1],3)`, exec proot with broker that reads FD 3. For optional filesystem socket (PTY/reconnect), use `SO_PEERCRED` exact expected PID + one-time random challenge delivered via inherited pipe/FD (not env), broker erases bootstrap secret immediately and never propagates to `exec`'d children.

32. Protocol `exec{id,argv,env,cwd,pty?}→stream{stdout,stderr,exit}`; fallback to direct exec if socketpair broken. Warm caches internal, short `$HOME/projects/<name>` not `/sdcard`.

### PR7 — perf/proot-syscall-experiments — Flagged, last — EXPERIMENTAL-ONLY

33. Count syscall classes via aggregate counters; verify `proot -V`, `proot -v 1`, seccomp, `process_vm_readv/writev`; if missing vendor Termux proot commit gated on Android 13–16; experiment path cache behind `proot_experimental_cache` default off; publish per-area target table, note 4K metadata cannot guarantee chroot parity.

Across PRs: never `rm -rf` outside verified `.../containers/.debian.installing-*` via dirfd, never `su/mount/umount/remount/sysctl/drop_caches/dev/block/governor/killall/pkill/system_server/SurfaceFlinger/DRM` (veto), streaming 64–256 KiB only, thermal pause via `ThermalStatusProvider`.

## File-Level Change Map

| File | Change | Rationale |
|------|--------|-----------|
| `app/build.gradle.kts:32-43` | **EDIT** fail-closed signing (providers.gradleProperty+env, throw if !hasReleaseSigning on release) | P0-1 never debug-sign release |
| `.gitignore` | **EDIT** add `keys/` | hygiene |
| `docs/setup/signing.md` | **NEW** CI secret setup + Play App Signing note | docs |
| `app/src/main/cpp/CMakeLists.txt` | **NEW** imported targets cmake | NDK PIC |
| `app/src/main/cpp/third_party/*` | **NEW** `libarchive.a/libzstd.a/liblzma.a/libcrypto.a` + `README.md` provenance | reproducible minimal |
| `app/src/main/cpp/native_rootfs_extractor.cpp` | **NEW** streaming parser+SHA+caps | PR2 |
| `app/src/main/cpp/native_rootfs_writer.cpp` | **NEW** per-component dirfd writer `descend_no_symlink` `O_NOFOLLOW` | P0-2/5 |
| `app/src/main/cpp/native_rootfs_lock.cpp` | **NEW** `descend_no_symlink` + flock on `locks/debian.lock` before GC | P0-2 |
| `app/src/main/cpp/native_rootfs_crypto.cpp` | **NEW** bundled BoringSSL Ed25519 verify raw bytes | PR3 API26 |
| `app/src/main/cpp/native_proot_launcher.cpp` | **NEW** `socketpair` + prctl PDEATHSIG + fd inheritance | PR6 |
| `app/src/main/java/com/zenithblue/nativecode/terminal/FastRootfsInstaller.kt` | **NEW** VerifiedRootfsArtifact, RENAME_NOREPLACE no fallback, syncfs, aid_* 01-10 | PR2 core P0-3/4 |
| `app/src/main/java/com/zenithblue/nativecode/terminal/NativeRootfsExtractor.kt` | **NEW** JNI wrapper + progress, takes verified | UI |
| `app/src/main/java/com/zenithblue/nativecode/terminal/RootfsArtifactProvider.kt` | **NEW** Bundled/Play/Signed + allowlist origin | PR3 |
| `app/src/main/java/com/zenithblue/nativecode/terminal/RootfsManifestVerifier.kt` | **NEW** raw-byte verify → VerifiedRootfsManifest, minAppVersion, arch | PR3 P0 |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ProotFastCommandBuilder.kt` | **NEW** argv-only ProcessBuilder, no host shell, unified node path | PR4 |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ProotSessionRunner.kt` | **NEW** socketpair inherited FD, no env token, requires launcher | PR6 |
| `app/src/main/java/com/zenithblue/nativecode/terminal/SafeBenchController.kt` | **NEW** host thermal/storage/Mem + ThermalStatusProvider DI | PR1 |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ThermalStatusProvider.kt` | **NEW** interface Android/Fake | testing |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ProjectPathResolver.kt` | **EDIT** stagingDir/brokerSocket/imageJson + lock-before-GC boot_id | SSOT |
| `app/src/main/java/com/zenithblue/nativecode/terminal/TermuxHostPaths.kt` | **EDIT** `IMAGE_JSON=/etc/nativecode/image.json` | capabilities |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ProotCommandBuilder.kt` | **EDIT** keep compat fallback | flag |
| `app/src/main/java/com/zenithblue/nativecode/terminal/LinuxCommandBuilder.kt` | **EDIT** route via Fast when flag | switchboard |
| `app/src/main/java/com/zenithblue/nativecode/OnboardingActivity.kt` | **EDIT** call FastRootfsInstaller with VerifiedRootfsArtifact | install flow |
| `app/src/main/java/com/zenithblue/nativecode/MainActivity.kt` | **EDIT** same | repair |
| `app/src/main/assets/scripts/nativecode_proot_fast.sh` | **UPDATE** sync Kotlin argv, unify node path, debug mirror note | PR4 |
| `app/src/main/assets/scripts/flux_install.sh` | **EDIT** early exit when image.json capability present | no-op |
| `app/src/main/assets/scripts/setup_debian_family.sh` | **EDIT** `nativecode-has-cap` not `jq` boolean | PR3 |
| `app/src/main/assets/scripts/setup_cli_tools.sh` | **EDIT** lazy nvm, bundled node check, use has-cap | PR5 |
| `app/src/main/assets/rootfs/debian_13_core_arm64.tar.zst` | **NEW** core in APK with `--numeric-owner` 1000:1000 | size+ownership |
| `app/build.gradle.kts:ndkVersion+externalNativeBuild+noCompress zst` | **EDIT** NDK plumbing | build |
| `.github/workflows/build-golden-rootfs.yml` | **NEW** native arm64, image.json, signed manifest, numeric-owner test, parity | CI |
| `bench/nc-io-bench.c` | **NEW** glibc arm64, seq/rand/metadata, buffered/direct, fdatasync split | PR1 |
| `bench/nc-membench.c` | **NEW** syscall-free, pre-touch, volatile sink | PR1 |
| `bench/run-safe-suite.sh` | **NEW** guest limits | PR1 |
| `bench/result-schema.json` | **NEW** schema | SSOT |
| `docs/perf/proot-parity-v2.md` | **NEW** fingerprint template + before/after | gate |

No edits to `jniLibs` proot in early PRs; PR7 vendors only after matrix.

## Testing Strategy

**Unit (JVM + native gtest):**
- `FastRootfsInstallerTest` — fresh-only (absent→ok, present valid→ALREADY_INSTALLED), SHA streaming, caps abort, `FAST_PUBLISH_UNSUPPORTED` on ENOSYS (no fallback), kill mid → staging only, lock-before-GC ordering, `descend_no_symlink` rejects symlink component, aid_* appended correctly, syncfs called before rename.
- `NativeRootfsWriterTest` — 12 zip-slip cases + per-component symlink test (plant symlink at `var`/`lib`/`proot-distro` level → descent fails), `O_NOFOLLOW` never follows, hardlink through symlink rejected.
- `ProotFastCommandBuilderTest` — argv list contains no shell metachar, per-profile required/optional/forbidden binds (not count), env contains PATH/LANG/TMPDIR/gpu, unified `/opt/nativecode/node-v26`, spaces/`'`/`"`/`$`/`;`/`&`/unicode/newline in path not reparsed, never `zsh -c "bash -lc"`, safety refuses `com.termux`.
- `RootfsManifestVerifierTest` — raw-byte Ed25519 verify passes on API26 (bundled BoringSSL), bad sig rejects, parse after verify only, allowlist origin rejects arbitrary URL, minAppVersion rejects old, arch mismatch rejects.
- `ThermalStatusProviderTest` — Fake SEVERE → controller aborts `THERMAL_ABORT`, no heating.

**Instrumented / host:**
- Kill-mid: `kill -9` at 25/50/75% → staging removed, `containers/debian` untouched, next install succeeds via `fast_publish_unsupported` path or lock-before-GC.
- `descend_no_symlink` symlink attack: create symlink at intermediate `var/lib` → `open` fails, installer reports `INSTALL_ERROR` not escape.
- Archive format bench: same manifest as `.tar.xz` vs `.tar.zst` L3–6 vs `.tar` → table.
- **AC13A/B parity**: extraction 100% manifest diff, installed-container 10-point check (resolv/hosts/aid_*/sysdata/sh/flux/launch/apt).
- Tar ownership: `tar --list --numeric-owner` check `home/flux 1000:1000` + runtime `stat 1000:1000` both chroot+proot.

**Device (MT6897 + Adreno750, Android 13–16):**
- Launch microbench 20-run medians cli+fast_launcher: `/bin/true` <150ms, `/bin/sh -c true` <175ms, `git --version` <200ms; argv log no shell reparse.
- `SafeBenchController` + `nc-*` suite: sysbench cpu/stress-ng, seq 1M buffered+direct, rand 4K IOPS, metadata, dd 256MiB, npm/node, AI CLI, small C compile, 100-file make vs ninja, git clone/status/diff `/data/...` vs `/sdcard` (passive thermal observe only).
- Thermal DI test: `FakeThermalStatusProvider` transitions NONE→MODERATE→SEVERE → child terminates promptly, no new bench begins, `THERMAL_ABORT`.
- Provisioning npm small pkg cold vs warm, TS install.
- Guards: storage <1GiB abort, RAM cap `min(256MiB,avail/8)`, extractor ≤256KiB bench seq1MiB rand4KiB.

**Regression:**
- Interactive colors/PATH/Node/npm/AI CLIs/SSH/WINCH/Ctrl-C/exit, `--shared-tmp`, storage binds.
- Install fresh/upgrade-no-replace/interrupted/SHA-corrupt/`FLUX_ROOTFS_PATH` python path.
- Profiles cli/compat/turnip/virgl each 20/20 boots, `vulkaninfo` Turnip on Adreno.
- Chroot parity untouched.
- Signing: `assembleRelease` without creds fails, `assembleDebug` passes (AC19).

**8-pass review** after PR2 and PR4/PR6; veto any `su/mount/killall` in PR2–PR6 perf diff scoped exhaustive.

## Acceptance Criteria (objective, verifiable)

- [ ] **AC0 Signing hygiene** — `git grep storePassword` zero; `assembleRelease` without `nativecode.keystore.*`/`NATIVECODE_KEYSTORE*` MUST FAIL (GradleException), `assembleDebug` MUST PASS; keys gitignored; secret rotated.
- [ ] **AC1 Fresh-destination install** — Per-SoC (MT6897+Adreno750) fresh-destination/local-archive 3-run median `RENAME_NOREPLACE` ≤1.15× `tar --numeric-owner --use-compress-program=unzstd -xf` to `/data/local/tmp` (±10% noise), archive cache state recorded, no missing target, no fallback rename.
- [ ] **AC2 No partial install** — Kill at 25/50/75% 10/10 leaves `containers/debian` untouched (lock held + syncfs ordering), next install succeeds after boot_id GC, no manual clean.
- [ ] **AC3 Archive security (12 cases + per-component)** — Unit rejects 6/6 escapes + hardlink/ device/FIFO/duplicate/oversize; accepts 3/3 legitimate (relative/absolute symlink, hardlink inside); also `descend_no_symlink` rejects symlink at any intermediate `var/lib/proot-distro` component; hardlink through symlink rejected.
- [ ] **AC4 Launch latency + argv** — MT6897 cli+fast_launcher warm 20-run: `/bin/true`<150ms `/bin/sh -c true`<175ms `git --version`<200ms; `ProotFastCommandBuilder` builds `List<String>` argv (no host `sh -c "exec proot…"`) — grep `ProcessBuilder.*libproot` present, `shell -c.*proot` absent for host; `zsh -c "bash -lc"` never emitted.
- [ ] **AC5 Shell startup** — `dash -c 'echo ok'` no `nvm.sh`; `nvm` lazy only on invoke; `zsh -l` prompt <300ms warm 5-run median.
- [ ] **AC6 Golden rootfs capabilities + ownership** — CI boots: `dpkg --audit` clean, `node` pinned 26.x at `/opt/nativecode/node-v26`→`/usr/local/bin/node`, `99nativecode-perf`, `C.UTF-8`, `/bin/sh→dash`, `/etc/nativecode/image.json` schema2 profile/capabilities correct, `setup_*` uses `nativecode-has-cap` not `jq`/boolean, `tar --numeric-owner` preserves `/home/flux 1000:1000` (archive `tar tv` check) and runtime `stat 1000:1000` in **both** chroot and PROOT (`--change-id` virtualizes).
- [ ] **AC7 Fair bench ratio** — Same glibc arm64 `nc-io-bench` both envs seq 1M buffered+direct+rand4k+metadata; `R_nc=proot_nc/chroot_nc` `R_mbw=proot_mbw/chroot_mbw` `abs(R_nc-R_mbw)≤0.10`; `nc-membench` zero-syscall; write reports `write_data_ns`+`fdatasync_ns`; `fio` absent with doc why shmget fails.
- [ ] **AC8 Profile correctness (semantic)** — CLI REQUIRED `/dev` `/proc` `/sys` `/dev/urandom:/dev/random` host `tmp:/tmp`; OPTIONAL WHEN AVAILABLE `/dev/shm` selinux stub storage; FORBIDDEN `/system` `/vendor` `/apex` dalvik-cache full PREFIX; each profile 20/20 boots exit 0; turnip includes `/system,/vendor,/apex,kgsl` when present, virgl includes X11 socket; not exact 5–7 count.
- [ ] **AC9 Safety veto (scoped exhaustive)** — Grep of **all added production lines in PR2–PR6** (`FastRootfsInstaller.kt`, `NativeRootfsExtractor.kt`, `RootfsManifestVerifier.kt`, `ProotFastCommandBuilder.kt`, `ProotSessionRunner.kt`, `LinuxCommandBuilder.kt`, `ShellCommandRunner.kt`, `nativecode_proot_fast.sh`, `app/src/main/cpp/*` inc `native_rootfs_crypto.cpp`, `bench/*`, `.github/workflows/build-golden-rootfs.yml` **runtime** vs CI workflow mount separately) zero `mount|umount|remount|sysctl|drop_caches|/dev/block|governor|devfreq|swap|zram|killall|pkill|system_server|SurfaceFlinger|glmark.*drm` in device runtime code; kill logic only `pid in sessionSet && uid==appUid`; file list in `veto.sh`.
- [ ] **AC10 Safe suite guards (corrected buffers + DI thermal)** — `SafeBenchController` via `ThermalStatusProvider` aborts if free<1GiB / thermal SEVERE / RAM cap; caps I/O 256MiB RAM `min(256MiB,avail/8)`; streaming extractor/decompressor ≤256KiB bench seq1MiB rand4KiB; test uses `FakeThermalStatusProvider(SEVERE)` transitions NONE→MODERATE→SEVERE → `THERMAL_ABORT` promptly, no heating device.
- [ ] **AC11 Flag dependency + socketpair** — Flags `proot_fast_install`, `proot_fast_launcher`, `proot_persistent_runner` (requires launcher, fallback), `proot_experimental_cache` (requires launcher); each off→legacy; broker `filesDir/run/proot-broker→/run/nativecode-host` 0700/0600, socketpair inherited FD 3 not env token, `START_NOT_STICKY`+`NativeCodeFgs`+`PR_SET_PDEATHSIG`+`--kill-on-exit` no orphan.
- [ ] **AC12 No regression** — `docs/environment/plans/regression-test-suite.md` P0 gates pass both devices after PR4/PR6.
- [ ] **AC13A Extraction parity** — NativeRootfsExtractor vs `proot-distro install_from_local_file` same archive → manifests `path|type|mode|size|SHA|symlink|hardlink` 100% identical, ownership `0:0` vs staged diff documented only for extraction.
- [ ] **AC13B Installed-container compatibility** — Full FastRootfsInstaller (01-10 fixups) vs full proot-distro install → `resolv.conf`/`hosts`/`aid_*` passwd-group/`fake sysdata`/`bin/sh→dash`/`flux 1000:1000`/`launch`/`apt/dpkg status` all compatible, launch succeeds.
- [ ] **AC14 Lock/path security** — Every component `PREFIX→var→lib→proot-distro→locks/containers` opened via `descend_no_symlink` `O_DIRECTORY|O_NOFOLLOW`; GC executes only after exclusive `debian.lock` held; `O_NOFOLLOW` last-component-only limitation documented.
- [ ] **AC15 Publish fallback** — `renameat2(RENAME_NOREPLACE)` ENOSYS/EINVAL → returns `FAST_PUBLISH_UNSUPPORTED`, staging discarded, legacy python path used, **no plain `renameat()` fallback** race; never half-published/replaced.
- [ ] **AC16 Proot-distro fixup parity** — Reproduces `write_resolv_conf`, `write_hosts`, `register_android_ids` (aid_*), `setup_fake_sysdata` semantics per `install.py:273-282`+`rootfs.py`; `flux 1000:1000` stays plus aid_*.
- [ ] **AC17 Rootfs signature compat (API26)** — `SignedReleaseProvider` Ed25519 via bundled `libcrypto.a` JNI `native_rootfs_crypto.cpp` passes on API26 emulator/device, not `Signature.getInstance("Ed25519")` API33+; raw manifest bytes verified before parse, allowlist origin only, `minAppVersionCode` validated, `VerifiedRootfsManifest` enforced.
- [ ] **AC18 Chroot ownership** — Golden tar `--numeric-owner` preserves `/home/flux 1000:1000`; `stat` in both chroot and PROOT reports `1000:1000` (proot via `--change-id` virtualization).
- [ ] **AC19 Release signing fail-closed** — `assembleRelease` without prod creds fails (GradleException), `assembleDebug` passes; never silently debug-signs release.

## Risks & Mitigations (NEW_RISKS)

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Escape if dirfd not per-component | Low if descend_no_symlink | High | Walk each component `O_NOFOLLOW`, test symlink at `var`/`lib` level, writer invariant, 12-case suite |
| RENAME_NOREPLACE not supported on FS | Low (ext4/f2fs ok) | Install fail | Return FAST_PUBLISH_UNSUPPORTED, discard staging, fallback legacy python, no plain rename race |
| NDK bloat / generic .a | Medium | APK bloat | Only arm64-v8a PIC `-fPIC` API26+ minimal tar+xz+zst, provenance README, fallback python |
| Fresh-only UX | Low | UX | ALREADY_INSTALLED toast + explicit repair/reset, future EXCHANGE with home preserve |
| Zstd vs xz winner wrong | Medium | Size/time | Dual-format PR2, 4-format table before PR3, keep xz fallback |
| Accelerator missing | High some kernels | Perceived regression | Profile `proot -V`+`proot -v 1` evidence, vendor gate 13-16, cache flagged off |
| Lazy NVM breaks shell | Low | Compat | Keep flag, test `nvm use`+AI CLIs |
| Broker orphan / auth leak | Low | Battery/security | socketpair inherited FD 3 not env, PDEATHSIG+kill-on-exit, erase bootstrap secret, SO_PEERCRED PID challenge if filesystem socket fallback |
| Fio/sysbench confusion | Low | Unfair ratio | Doc why shmget fails, enforce nc-io-bench, ratio `abs(R_nc-R_mbw)≤0.10` |
| Thermal/storage watchdog | Medium | Brick/heat | DI Fake provider test, passive observe on device, storage/RAM caps, streaming only |
| Proroot premature | Low | Stability | NATIVE_GLIBC future only |
| Stale GC race | Low | Storage/corruption | Lock-before-GC, boot_id+elapsedRealtime via dirfd |
| Signing rotation / Play key | High if mishandled | Update disaster | Fail-closed, Play App Signing upload-key rotation note, history scrub, AC19 |
| Ed25519 API26 compat | Medium if platform API used | Verify fails on 26 | Bundled BoringSSL JNI, test on API26 emulator |
| Raw-before-parse bypass | High if parse before verify | Malicious manifest | Verify raw bytes before parse, allowlist origin, minAppVersion, arch check |
| Tar ownership 0:0 destroys flux | High if --owner=0 | Chroot breaks | Use --numeric-owner, test archive + runtime both envs |

## Handoff to Plan Reviewer

**Iteration 5 REVISE → all 5 PR2 blockers + 6 PR3/PR4/PR6/AC fixes applied:**

1. **P0-1 Signing fail-closed** — `hasReleaseSigning` providers API, `signingConfigs.create` only if present, `buildTypes.release` throws `GradleException` if missing when Release task requested; `assembleDebug` always passes; `AC19` added. Verify no debug-sign fallback.
2. **P0-2 Per-component O_NOFOLLOW** — `descend_no_symlink` walks `PREFIX/var/lib/proot-distro/locks/containers` stepwise `O_DIRECTORY|O_NOFOLLOW`, not single `openat("var/lib/...")`; lock acquired **before** GC. Verify `var`/`lib` symlink attack rejected.
3. **P0-3 No rename fallback** — `syscall(__NR_renameat2,RENAME_NOREPLACE)` on `ENOSYS/EINVAL` → `FAST_PUBLISH_UNSUPPORTED` discard staging/legacy fallback, no `renameat()` race. Verify `AC15` never half-published.
4. **P0-4 aid_* fixup** — Ordered 01-10 includes `write_resolv_conf`+`write_hosts`+`register_android_ids` (aid_* not flux rewrite)+`setup_fake_sysdata` + validations + `syncfs`+`renameat2`+`fsync(parent)`. Verify `AC16`/`AC13B` compatibility.
5. **P0-5 Libarchive split + durability** — Remove `ARCHIVE_EXTRACT_SECURE_*` claim; define libarchive=decompress/parse, writer=single authority; add `syncfs(rootfsFd)` before publish then `fsync(parent)`. Verify 3-step durability `all data→SHA→fixups→syncfs→rename→fsync`.
6. **PR3 Ed25519 API26** — `RootfsManifestVerifier.kt`→JNI `native_rootfs_crypto.cpp`→bundled `libcrypto.a` Ed25519, test on API26 emulator; `AC17` added. Verify not `Signature.getInstance("Ed25519")`.
7. **PR3 raw-before-parse** — Download raw bytes+sig → verify raw → parse → validate schema/profile/arch/minAppVersion/size/artifact/origin allowlist → download archive → SHA compare; `VerifiedRootfsManifest` + `VerifiedRootfsArtifact` sealed (Bundled/Signed/PlayDelivered) trust boundary; `FastRootfsInstaller` takes verified only.
8. **PR3 jq→has-cap** — `nativecode-has-cap` helper (python3 or shell grep) in core, `setup_*.sh` use it not `jq`; `AC6` checks.
9. **Golden tar numeric-owner** — `tar --numeric-owner --mtime` preserves `home/flux 1000:1000` + `0:0` root files; CI+runtime tests `chroot 1000:1000` and `proot 1000:1000` via `--change-id` virtualization (`AC18`).
10. **AC13 split** — `AC13A` extraction parity path/type/mode/sha/symlink/hardlink 100%; `AC13B` installed-container compatibility 10-point check.
11. **PR4 argv-only** — `ProotFastCommandBuilder` builds `List<String>` `ProcessBuilder(libproot, --kill-on-exit, …)` no host `sh -c`, only guest `dash -c <single-arg>` for explicit shell strings; spaces/`'`/`$`/unicode test. Verify `AC4`.
12. **AC8 semantic** — CLI REQUIRED `/dev /proc /sys urandom→random tmp:/tmp` + OPTIONAL `/dev/shm selinux storage` + FORBIDDEN `/system /vendor /apex`; not exact 5–7 count.
13. **PR6 socketpair auth** — Anonymous `socketpair` inherited FD 3 primary channel, no env token leak via `/proc/environ`, no same-UID phantom connect; filesystem socket fallback uses `SO_PEERCRED` exact PID + one-time challenge via inherited pipe, secret erased never propagated; `PR_SET_PDEATHSIG` lifecycle unchanged.
14. **Thermal DI** — `ThermalStatusProvider` Android/Fake, test `Fake(SEVERE)` → `THERMAL_ABORT` promptly no heating; device only passive observe; `AC10` updated.
15. **AC14-19** — Added lock/path GC, publish fallback, fixup parity, sig compat, chroot ownership, release signing fail-closed.

**Requested verdict**: `PLAN_STATUS: APPROVED` (`PR0:IMPLEMENT fail-closed` `PR1:IMPLEMENT APPROVED` `PR2:IMPLEMENT after 5 contract fixes` `PR3:IMPLEMENT_AFTER_PR2 with verified manifest+numeric-owner` `PR4:IMPLEMENT_AFTER_PR1 argv-only` `PR5:IMPLEMENT_AFTER_PR4` `PR6:IMPLEMENT_AFTER_PR4 socketpair` `PR7:EXPERIMENTAL_ONLY`) — worker may start PR0+PR1 immediately; PR2 unblocked for implementation without further redesign.

If REVISE, annotate which PR(s) still need reshaping; if BLOCK, state ptrace hard-limit unachievable target.

