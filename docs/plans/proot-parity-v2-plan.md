# Plan: proot-parity-v2 — Product-level PRoot/Chroot Parity (Rev 5)

## Task Summary

Achieve **product-level parity** between rootless PROOT and rooted chroot without claiming impossible ptrace parity. Targets: install ~1.0–1.15× `tar -xf` to `/data/local/tmp` (durability-fair), launch `/bin/true` <150 ms (vs 816 ms today), CPU ~95–100%, RAM memcpy ~95–100%, syscall-heavy/seq-I/O gap documented. Delivered via (1) transactional native extractor with per-component dirfd+flock, safe logical symlink resolution (40 hops, guest-root clamp, re-walk O_NOFOLLOW), deferred hardlink copies, hostile-target handling, `syncfs→RENAME_NOREPLACE→fsync(parent)` fresh-install-only, (2) CI golden rootfs with capability manifest + native Ed25519 verified manifest + preserved numeric ownership, (3) direct PRoot launcher argv-only typed API + profiled binds + sysdata fake-proc parity (`/opt/nativecode/node-v26` unification), (4) shell/Node lazy init, (5) persistent broker via socketpair inherited FD + PDEATHSIG after fork + CLOEXEC (not env token, not SO_PEERCRED PID), (6) fair Debian/glibc bench suite + host DI thermal watchdog (no heating), (7) fail-closed release signing (password/upload-key/app-signing-key distinguished). **P0 repo hygiene**: remove `app/build.gradle.kts:32-37` credentials from VCS.

> Rev 5 addresses PASS 7 REVISE: safe symlink resolution (not blanket reject), deferred hardlink copies not linkat, hostile-target fresh-install states, sysdata fake-proc parity, typed argv API, socketpair lifecycle (PDEATHSIG after fork, CLOEXEC, no SO_PEERCRED PID), AC1 durability fairness, PR0 key rotation wording, plus AC20-24. PR0/PR1 UNBLOCKED, PR2/PR4/PR6 require these small contract fixes (architecture already APPROVED).

## Research Sources

- <source: app/src/main/java/com/zenithblue/nativecode/terminal/ProotCommandBuilder.kt:6-36 | current `libbash → python proot-distro → zsh -c` double shell>
- <source: bootstrap_root/.../site-packages/proot_distro/locking.py:21-72,244-272 | `ContainerLock` flock(2) on `$PREFIX/var/lib/proot-distro/locks/<name>.lock`>
- <source: bootstrap_root/.../site-packages/proot_distro/constants.py:75-77 | `RUNTIME_DIR=$PREFIX/var/lib/proot-distro`, `LOCKS_DIR`, `CONTAINERS_DIR`>
- <source: bootstrap_root/.../site-packages/proot_distro/commands/install.py:49-282 | `write_resolv_conf_at()`, `write_hosts_at()`, `register_android_ids_at()`, `setup_fake_sysdata()`>
- <source: bootstrap_root/.../site-packages/proot_distro/helpers/rootfs.py:38-134 | `register_android_ids` aid_* append>
- <source: bootstrap_root/.../site-packages/proot_distro/sysdata.py:247-310 | `setup_fake_sysdata()` creates `containers/debian/sysdata/{sys_empty,loadavg,stat,uptime,version,sysctl_*}` + bind conditions>
- <source: bootstrap_root/.../site-packages/proot_distro/commands/login/__init__.py:85,384,519 | shared inheritable flock, fake sysdata>
- <source: https://github.com/proot-me/proot/blob/master/doc/proot/manual.rst | `--change-id=uid:gid` virtualizes app-owned files as 1000:1000>
- <source: https://developer.android.com/reference/java/security/Signature | `Signature.getInstance("Ed25519")` only API 33+ → minSdk 26 must use bundled BoringSSL>
- <source: https://man7.org/linux/man-pages/man7/unix.7.html | SO_PEERCRED on socketpair reports credentials at socketpair() creation, not eventual broker PID; SO_PASSCRED/SCM_CREDENTIALS for per-message sender creds>
- <source: https://man7.org/linux/man-pages/man2/PR_SET_PDEATHSIG.2const.html | PDEATHSIG cleared in newly forked child, survives execve; must set after fork>
- <source: app/src/main/assets/scripts/nativecode_proot_fast.sh:1-369 | direct proot model exists but uses `/opt/nodejs` legacy path>
- <source: app/build.gradle.kts:32-43 | **P0**: `storePassword="REDACTED"` committed>
- <source: https://github.com/libarchive/libarchive/blob/master/libarchive/archive.h | `ARCHIVE_EXTRACT_*` via `archive_write_disk_set_options()` — irrelevant with custom writer>
- <source: https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run/choose-the-runner-for-a-job | `ubuntu-24.04-arm` valid>
- <source: https://support.google.com/googleplay/android-developer/answer/9859372 | Play Asset Delivery on-demand packs; 150 MB product budget>
- <source: docs/environment/proot-vs-chroot-baseline.md + proot-vs-chroot-perf-report-mediatek.md | Snapdragon vs MT6897>
- <source: docs/environment/proot-fast-launcher-design.md:1-250 | 816 ms distro vs 104 ms plain proot vs 30 ms chroot>
- <source: git log --oneline -20 | master e015a8d (rev4)>

## Current Architecture

**CHROOT** `setup_debian13_chroot.sh`: `assets/rootfs/debian_13_rootfs.tar.xz → /data/local/tmp/chrootDebian13 .flux_configured` native `tar/unxz` with preserved numeric ownership.

**PROOT today** `flux_install.sh:163-261`: APK `debian_13_rootfs.tar.xz` (xz only, SHA `13e29f…6803`) → `sha256sum` full read → `python proot-distro install` (exclusive `ContainerLock("debian")`, login shared inheritable) → `login --shared-tmp -- bash` → `setup_debian_family.sh` apt + `setup_cli_tools.sh` NVM on device. Two reads, thousands ptrace ops. Post-extract: `install.py:273-282` 4 fixups + `sysdata.py` sys_empty/fake proc.

**Runtime** `ProotCommandBuilder.kt:18-31` always `arrayOf(shell,"-c","exec python $prootDistro login … -- zsh -c '…'")` (host shell re-parse) plus `toolExec.second.split(Regex("\\s+"))` quoting loss. Chroot uses direct `nativecode_chroot.sh`. Fast launcher asset already does `exec proot --kill-on-exit --link2symlink -L --change-id --rootfs --cwd` + `env -i` but not wired, checks only old `sys_empty`, not per-container `containers/debian/sysdata/*`, still `/opt/nodejs`.

**Guest**: `setup_*.sh` + `optimize_debian_rootfs_perf.sh` inside PRoot. Node via NVM `source nvm.sh` every shell; `jq` not guaranteed core.

## Affected Components & Dependencies

| Component | Files | Impact |
|-----------|-------|--------|
| **Signing hygiene (P0-1)** | `app/build.gradle.kts:32-43`, `keys/`, CI secrets, `docs/setup/signing.md` | Fail-closed release signing, distinguish password/upload-key/app-signing-key, rotate appropriate key, `.gitignore` keys |
| **Rootfs asset & build** | `app/src/main/assets/rootfs/`, `app/build.gradle.kts:androidResources` | core in APK, dev/desktop via verified provider or Play Asset Delivery; zst+xz dual; `--numeric-owner` 1000:1000 |
| **Fast installer (Kotlin+JNI)** | `flux_install.sh`, `TermuxHostPaths.kt`, `OnboardingActivity.kt:3290`, `MainActivity.kt:12771`, `ProjectPathResolver.kt` | NEW `FastRootfsInstaller.kt`, `NativeRootfsExtractor` JNI (libarchive+libzstd+liblzma, arm64-v8a NDK PIC), per-component dirfd, flock before GC, safe symlink resolve (40 hops, clamp, re-walk), deferred hardlink copies, hostile-target states, syncfs→RENAME_NOREPLACE→fsync |
| **Proot launcher (PR4)** | `ProotCommandBuilder.kt` (existing refactor), `LinuxCommandBuilder.kt`, `ShellCommandRunner.kt`, `nativecode_proot_fast.sh` | EDIT `ProotFastCommandBuilder.kt` typed `buildExec/buildShell` argv-only, profile binds + sysdata `containers/debian/sysdata/*` fake proc parity, unified `/opt/nativecode/node-v26` |
| **CI golden rootfs** | `setup_debian_family.sh`, `setup_cli_tools.sh`, `.github/workflows/*` | Workflow builds core/dev/desktop arm64.tar.zst + `image.json` + signed `rootfs-manifest.json` (raw-byte verify), `nativecode-has-cap`, numeric owner |
| **Shell/Node** | guest `~/.zshenv/.zprofile/.zshrc` | lazy nvm function, bundled `/opt/nativecode/node-v26` |
| **Benchmark suite** | `docs/environment/bench*` | NEW `nc-io-bench.c` (glibc arm64), `nc-membench.c`, `SafeBenchController.kt` + `ThermalStatusProvider` DI, `bench/run-safe-suite.sh` (split durability metrics) |
| **Persistent broker (PR6)** | `TerminalService.kt`, `NativeCodeFgs.kt` | `ProotSessionRunner.kt` + `native_proot_launcher.cpp` socketpair inherited FD 3, PDEATHSIG after fork, CLOEXEC, no SO_PEERCRED PID assumption |
| **Safety** | `DeviceRequirements.kt` | thermal/storage/RAM guards, veto grep |

## Implementation Steps (ordered, smallest correct change)

### PR0 — repo/p0-signing-rotation — Must land before any release/PR merge

0. **Fail-closed release signing**: replace `app/build.gradle.kts:32-43`:
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
    }
    ```
    Gate throw only when `gradle.startParameter.taskNames.any{it.contains("Release",ignoreCase=true)}` if strict config breaks debug. Add `keys/` to `.gitignore`. **Key rotation wording**: `exposed passwords → rotate immediately`; `Play upload key exposed → use Play upload-key reset process`; `Play app-signing key → do NOT casually replace local keystore (Play App Signing manages it) — rotate appropriate key per actual Play signing model`. Store new secret `NATIVECODE_KEYSTORE_BASE64` + env in GitHub Actions. Verify `git log --diff-filter=D` history. Document in `docs/setup/signing.md`. **AC19**: `assembleRelease` without creds MUST FAIL, `assembleDebug` MUST PASS. **AC0**: `git grep storePassword` zero.

### PR1 — perf/proot-benchmark-v2 — APPROVED

1. **Toolchain**: compile `bench/nc-io-bench` + `bench/nc-membench` as **ARM64 Debian/glibc binaries** (not NDK/Bionic). Build on `ubuntu-24.04-arm` with `aarch64-linux-gnu-gcc` against glibc; same ELF both envs. Add `bench/README.md` `file` proof.
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
    `clock_gettime(CLOCK_MONOTONIC_RAW)` `now_ns()`, `posix_memalign(&buf,4096,1<<20)` seq 1 MiB / 4 KiB rand, `pread/pwrite` loops, `xorshift64` pre-generated indices. Report `write_data_ns` + `fdatasync_ns` separately. Provide `buffered` + `direct-if-supported` (`O_DIRECT` fallback if `EINVAL`). No `drop_caches`, no `/dev/block`, no `shmget`.
3. **`bench/nc-membench.c`**: 8–64 MiB, `memcpy/memset/RO-scan/WO-scan/random`, pre-touch `for(i=0;i<size;i+=4096) src[i]^=1`, timed `memcpy` loop, `volatile sink` + `asm volatile`, indices pre-generated, zero syscalls inside loop.
4. **`bench/SafeBenchController.kt`** + **`bench/run-safe-suite.sh`** + **`ThermalStatusProvider` DI**:
    ```kotlin
    interface ThermalStatusProvider { fun current(): Int }
    class AndroidThermalStatusProvider(val power: PowerManager): ThermalStatusProvider { override fun current() = power.currentThermalStatus }
    class FakeThermalStatusProvider(var status: Int): ThermalStatusProvider
    ```
    Poll every 2s; if `SEVERE|EMERGENCY` abort child `SIGTERM→SIGKILL` report `THERMAL_ABORT`. `StatFs` free<1 GiB abort, `ActivityManager.MemoryInfo` availMem, caps `I/O min(256MiB,free/10)` `RAM min(256MiB,avail/8)`. Shell sets `ulimit` + runs bench. **Test uses `Fake(SEVERE)`** — never intentionally heat phone to SEVERE; real device only passively observes thermal during short runs. **Durability fairness**: `nc-io-bench` reports split `extract_ms|publish_ms|durability_ms|total_install_ms` so AC1 can compare extraction/publish before durability vs equivalent durability on both sides; reference `tar -xf` not penalized by unrelated dirty `/data` `syncfs`.
5. **`bench/result-schema.json`** + **`docs/perf/proot-parity-v2.md`** fingerprint: commit, `proot -V`, `proot -v 1 /bin/true` seccomp/process_vm, proot-distro version, Android/kernel/SoC/filesystem/SHA/thermal, archive cache state. Ban `glmark2-drm`, block devices, `fio`.
6. **Gate**: same binary both envs warm repeat <5% variance.

### PR2 — perf/proot-fast-extractor — Transactional native extraction (fresh-install-only, per-component dirfd+flock+ caps + safe symlink/hardlink + durability)

7. **Scope + hostile-target (PASS 7-3)**: fast installer is **fresh-install-only**, but do not collapse every `EEXIST` into `ALREADY_INSTALLED`:
    ```
    debian absent                                          → FAST_INSTALL
    debian = real directory + valid rootfs (image.json ok) → ALREADY_INSTALLED
    debian = real directory + incomplete rootfs             → NEEDS_EXPLICIT_REPAIR (user-confirmed, no auto-delete)
    debian = symlink / file / FIFO / unexpected type        → HOSTILE_OR_CORRUPT_STATE → fail closed, do not follow or auto-delete
    ```
    Validate `debian` via `fstatat(containersFd,"debian",AT_SYMLINK_NOFOLLOW)` + `S_ISDIR` + ownership + marker; `RENAME_NOREPLACE` already prevents replacement, this gives correct `EEXIST` interpretation. No `debian→debian.old→staging→debian` exchange; future `RENAME_EXCHANGE` only after home-preserving migration.

8. **Locking — per-component descent + lock-before-GC**: Use JNI `flock(2)` on `$PREFIX/var/lib/proot-distro/locks/debian.lock`:
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
    // openat(locksFd,"debian.lock",O_RDWR|O_CREAT|O_CLOEXEC|O_NOFOLLOW,0600) + flock LOCK_EX|LOCK_NB keep open entire extraction+publish
    ```
    Walk stepwise; `O_NOFOLLOW` only guards last component. Acquire exclusive lock **before** stale GC.

9. **Dirfd atomic publish no fallback**: keep `containersFd` alive entire op. Staging: `mkdirat(containersFd,".debian.installing-<rand>",0700)` → `mkdirat(stagingFd,"rootfs",0755)` → verify `fstatat(...AT_SYMLINK_NOFOLLOW)` dir owned by app. Hostile-target check above. Publish:
    ```cpp
    if (syscall(__NR_renameat2, containersFd, stagingName, containersFd, "debian", RENAME_NOREPLACE)<0){
      switch(errno){ case EEXIST: return classifyExistingTarget(); case ENOSYS: case EINVAL: return FAST_PUBLISH_UNSUPPORTED; default: return INSTALL_ERROR; }
    }
    fsync(containersFd);
    ```
    No `debian→old` window; no plain `renameat()` fallback — preserves never half-published/never replaced.

10. **Secure extraction — single authority + safe symlink resolution (PASS 7-1)**: Do not use `ARCHIVE_EXTRACT_SECURE_*` parser hints (libarchive disk writer only). Define `libarchive=decompress/parse`, `NativeRootfsWriter=single authority`. **Safe symlink resolution** (current upstream behavior):
    ```
    guest /lib -> /usr/lib (symlink)
    guest /lib/foo entry
      ↓ readlinkat(existing parent symlinks)
      ↓ resolve target logically:
          absolute target => reset logical path to guest root (/)
          ".." => pop one component, but never above guest root (clamp)
          maximum hops = 40 (ELOOP if exceeded)
      ↓ re-walk resulting physical components via openat(O_DIRECTORY|O_NOFOLLOW) dirfd walk
      ↓ openat(final name, O_NOFOLLOW) for write
    ```
    Rule: **Never KERNEL-FOLLOW an archive-created symlink while writing**, but logically resolve existing symlink parents via `readlinkat`, clamp, then re-walk `O_NOFOLLOW`. Distinguish `unsafe: openat(parent,"link/child",…)` vs `safe: readlinkat→clamp→dirfd walk→openat`. `SYMLINK CONTENT` may be absolute (`/usr/lib→/usr/lib`) or `..` — valid, stored verbatim via `symlinkat(target,dirfd,name)` never validated `!startsWith("/")`; danger is following later. Second `O_NOFOLLOW` walk rejects race where resolved directory was swapped for symlink between resolution and open. `ARCHIVE ENTRY PATH` must never escape staging. Hardlinks via deferred copies (see next).

11. **Hardlinks — deferred copies not linkat (PASS 7-2)**: Do **not** use `linkat()` for rootfs hardlinks (PRoot-Distro copies). Match upstream:
    ```
    tar hardlink entry
      ↓ validate linkname components (no absolute escape, no .. above root)
      ↓ defer (queue {linkname, link_target_name})
      ↓ finish regular-file extraction
      ↓ for each deferred: safe-resolve source (per step 10 logic) must be regular file, not symlink; safe-resolve destination parent; copy bytes+mode+mtime into new inode (read source fd, write dest fd)
    ```
    Source must be regular file (`S_ISREG` via `fstatat(...AT_SYMLINK_NOFOLLOW)`), not symlink; linkat would preserve inode sharing and allow later mutation of one entry to affect another. Defer until regular files exist. Also validates AC13A parity.

12. **12+ case matrix**: `entry /absolute` reject, `entry ../../escape` reject, `symlink → valid in-rootfs target then child ACCEPT (child written to resolved target)`, `symlink chain designed to escape guest / ACCEPT symlink object but resolution clamped inside`, `absolute symlink target allowed` (verified), `relative symlink with .. allowed but clamped`, `hardlink ../../host` reject, `hardlink through symlink` (resolve source via safe logic, reject if not regular), `device` reject, `FIFO` reject, `duplicate conflicting entry` policy, `very large declared` abort, `too many entries` abort. AC3 updated:
    ```
    symlink -> valid in-rootfs target, then child → ACCEPT child at resolved target
    symlink chain escape attempt → ACCEPT symlink object, resolution remains clamped inside, child not escaping
    ```

13. **Bounded one-pass SHA + caps**: stream `compressed chunk → SHA256_update + decompressor → dirfd writer` (64–256 KiB streaming). Gate commit on `SHA==expected`; mismatch → dirfd-verified `unlinkat` staging. Enforce verified manifest ceilings:
    ```json
    { "schema":2, "profile":"core|dev|desktop", "compressedBytes":92381231, "maxExtractedBytes":1073741824, "maxFiles":125000, "sha256":"…", "maxSingleFile":1073741824 }
    ```
    Abort if `sum(size)>maxExtractedBytes||files>maxFiles||avail<reserve||single>maxSingleFile`. On mismatch/corrupt `fdatasync` fail → unlinkat.

14. **Fixups after verify, before durability flush**: ordered `install.py:273-282` + `rootfs.py:38-134`:
    ```
    01 verify archive SHA
    02 validate rootfs structure (flux 1000:1000 present, /bin/sh→dash)
    03 safely replace /etc/resolv.conf (unlink then write, dirfd, handle symlink image per rootfs.py:42)
    04 safely replace /etc/hosts (unlink then write per rootfs.py:57)
    05 safely register Android aid_* UID/GID records (aid_<user>:x:<uid>:<gid> + groups per rootfs.py:76-134) — distinct from flux=1000:1000
    06 create complete fake sysdata compatible with current PRoot-Distro (setup_fake_sysdata: containers/debian/sysdata/*)
    07 validate flux=1000:1000 still
    08 validate /bin/sh→dash
    09 durability flush (syncfs)
    10 RENAME_NOREPLACE publish + fsync(parent)
    ```
    `flux` remains `1000:1000` (`--change-id` virtualizes) plus `aid_*`.

15. **Durability flush before publish**: before `renameat2`:
    ```cpp
    if (syncfs(rootfsFd)!=0) return INSTALL_ERROR;
    // then renameat2(RENAME_NOREPLACE)
    // then fsync(containersFd)
    ```
    Ordering: `all data→SHA→fixups→syncfs→rename→fsync(parent)`. Rename alone gives process-kill atomicity; syncfs adds power-loss durability. AC1 compares split metrics (see PR1-4) so `syncfs` dirty unrelated `/data` not unfairly fails AC1.

16. **JNI + CMake**:
    - `app/src/main/cpp/CMakeLists.txt` imported targets:
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
    - `app/build.gradle.kts`: `ndkVersion="26.1.10909125"`, `externalNativeBuild.cmake.path`, `abiFilters arm64-v8a`, `packaging.jniLibs.useLegacyPackaging true`, `androidResources.noCompress+=listOf("zst","xz","tar")`. Only `arm64-v8a`. Fallback python if `System.loadLibrary` fails.
    - **Reproducibility**: `third_party/README.md` source URL/commit/SHA256/build script/CFLAGS `-fPIC -O2 -DANDROID` NDK API 26+ licenses (`libarchive BSD`, `xz PD`, `zstd BSD`, `boringssl ISC`). Minimal libarchive `--enable-bsdtar=no --enable-bsdcpio=no --with-zstd --with-lzma`.
    - `NativeRootfsExtractor.kt`: `fun extract(archiveFd:Int, containersDirFd:Int, verified:VerifiedRootfsArtifact, onProgress:(Long)->Unit):Result` — takes verified only.

17. **Stale GC with lock held**: GC after exclusive lock. Each staging writes `.boot_id` + `.started_elapsed_ms` (`SystemClock.elapsedRealtime()`). GC via dirfd-verified delete must be under `containers/.debian.installing-*`.

18. Keep `flux_install.sh` for `FLUX_ROOTFS_PATH` generic tarballs (python path). Fast path only for `VerifiedRootfsArtifact`.

### PR3 — perf/prebuilt-debian-dev-rootfs

19. CI `.github/workflows/build-golden-rootfs.yml` on **`ubuntu-24.04-arm`**: `debian:13` base.
20. Node 26 to **`/opt/nativecode/node-v26`** canonical, symlink `/usr/local/bin/node|npm|npx`.
21. Create **`/etc/nativecode/image.json`**:
    ```json
    { "schema":2, "imageVersion":"2026.08.24.1", "profile":"core", "capabilities":["base","git","zsh"] }
    // dev: ["base","git","zsh","compiler","node26","npm","ccache"] desktop: +["xfce","mesa"]
    ```
    Bake `flux:x:1000:1000::/home/flux:/bin/zsh` + home/group/sudoers/caches; ensure `root` files `0:0`, `/home/flux` `1000:1000`; installer does not inject UID. Add helper **`nativecode-has-cap`** (not `jq`):
    ```bash
    #!/bin/sh
    python3 -c "import json,sys; d=json.load(open('/etc/nativecode/image.json')); sys.exit(0 if sys.argv[1] in d.get('capabilities',[]) else 1)" "$1"
    ```
    `setup_debian_family.sh`/`setup_cli_tools.sh` become `if nativecode-has-cap node26; then …; fi`.
22. **Pack with numeric ownership**:
    ```bash
    mkdir -p rootfs/home/flux && chown 1000:1000 rootfs/home/flux
    tar --sort-name --numeric-owner --mtime="@${SOURCE_DATE_EPOCH}" -I "zstd -6 -T0" -cf nativecode-debian-{core,dev,desktop}-arm64.tar.zst -C rootfs .
    ```
    Do not `--owner=0 --group=0`. Chroot preserves real ownership; proot virtualizes via `--change-id`. CI `tar tv` check + runtime `stat 1000:1000` both chroot+proot.
23. **Outputs**: tar.zst, `rootfs-manifest.json` (schema, imageVersion, minAppVersionCode, arch, profile, artifact, compressedBytes, maxExtractedBytes, maxFiles, maxSingleFile, sha256, originAllowlist), `rootfs-files.sha256`, `SBOM.txt`, `SHA256SUMS`, `image.json`, `rootfs-manifest.json.sig` (Ed25519 raw 64B).
24. **Authenticated before parse + Ed25519 on API26**:
    ```kotlin
    data class VerifiedRootfsManifest(val schema:Int,val imageVersion:String,val profile:Profile,val arch:String,val sha256:ByteArray,val compressedBytes:Long,val maxExtractedBytes:Long,val maxFiles:Long,val maxSingleFile:Long,val artifactName:String,val minAppVersionCode:Int,val origin:String)
    sealed interface VerifiedRootfsArtifact { data class Bundled(val manifest:VerifiedRootfsManifest,val assetPath:String): VerifiedRootfsArtifact; data class Signed(val manifest:VerifiedRootfsManifest,val downloadUrl:String): VerifiedRootfsArtifact; data class PlayDelivered(val manifest:VerifiedRootfsManifest,val assetPack:String): VerifiedRootfsArtifact }
    ```
    Flow: `raw manifest bytes + sig → JNI bundled BoringSSL ED25519_verify over EXACT raw bytes (pub in assets/keys/rootfs_ed25519.pub) → parse JSON → validate schema/profile/arch/minAppVersion/size/artifact/origin allowlisted (e.g. https://github.com/zenithblue-oss/nativecode-ai/releases/) → download archive allowlist only → FastRootfsInstaller takes VerifiedRootfsArtifact` → stream SHA compare signed sha256. Do not use `Signature.getInstance("Ed25519")` (API33+) nor system BoringSSL. Record `imageVersion` for anti-rollback (P1).
25. Update `app/build.gradle.kts` `noCompress+=zst`, 4-format table using PR2 dual-format before winner; keep xz fallback; 150 MB product budget.
26. **Reference-tree parity split**:
    - AC13A **Extraction parity**: path/type/mode/size/SHA/symlink/hardlink 100% identical (ownership 0:0 vs flux documented only for extraction).
    - AC13B **Installed-container compatibility**: full FastRootfsInstaller (01-10 fixups) vs full proot-distro install → `resolv.conf`/`hosts`/`aid_*`/`fake sysdata`/`bin/sh→dash`/`flux 1000:1000`/`launch`/`apt/dpkg status` compatible.

### PR4 — perf/proot-fast-launcher — Direct exec + sysdata parity + typed API

27. `ProotFastCommandBuilder.kt` is **edit/refactor of existing file** (not new file). **Must build argv-only typed API**, never host shell string:
    ```kotlin
    fun buildExec(guestArgv: List<String>, cwd: String, profile: Profile): List<String>
    fun buildShell(shellString: String, cwd: String, profile: Profile): List<String>
    // buildExec: argv = [libproot, --kill-on-exit, --link2symlink, -L, --change-id=1000:1000, --rootfs, --cwd, --bind=…, /usr/bin/env, -i, ENV..., guestArgv]
    // buildShell: argv = buildExec(listOf("/bin/dash","-c",shellString), cwd, profile) // shellString as ONE argv
    ```
    Fixes `toolExec.second.split(Regex("\\s+"))` quoting loss (`spaces/'/"/$ /; /& /unicode/newline`). Start via `ProcessBuilder(argv)` no intermediate shell. Safety `PACKAGE_NAME==com.zenithblue.nativecode`, `loadFluxIds()` parse `${ROOTFS}/etc/passwd` flux 1000:1000 fallback 0:0 defer if absent. `buildBinds(profile)` + `buildGuestEnv()` clean `env -i …` + gpu vars + `buildGuestPath()` unified `/opt/nativecode/node-v26`. **Only** explicit guest shell string reaches guest `dash -c`.
28. **Sysdata fake-proc parity (PASS 7-4)**: implement per `sysdata.py:247-310` and current PRoot-Distro conditional binds:
    ```
    containers/debian/sysdata/sys_empty          → /sys/fs/selinux  (always when dir exists, validate lstat+O_NOFOLLOW+st_nlink==1)
    when real host entry unreadable (access R_OK fails):
      sysdata/loadavg  → /proc/loadavg
      sysdata/stat     → /proc/stat
      sysdata/uptime   → /proc/uptime
      sysdata/version  → /proc/version
      sysdata/sysctl_entry_cap_last_cap → /proc/sys/kernel/cap_last_cap
      sysdata/sysctl_inotify_max_user_watches → /proc/sys/fs/inotify/max_user_watches
      sysdata/sysctl_kernel_overflowuid → /proc/sys/kernel/overflowuid
      sysdata/sysctl_kernel_overflowgid → /proc/sys/kernel/overflowgid
    ```
    Validate each fake file `lstat`/`O_NOFOLLOW` + `st_nlink==1` (upstream requires, prevents guest hardlink planting). Current checked-in `ProotFastCommandBuilder` only looked for old global `sys_empty`; fix to per-container `containers/debian/sysdata/*` plus conditional `/proc` substitutes. Part of AC8/AC13B.

29. Wire `LinuxCommandBuilder.kt:24-25` flag `proot_fast_launcher`; `ShellCommandRunner.kt` keep `linker64` prefix. Update `TerminalService.kt`/`MainActivity.kt` workDir/profile (login vs exec). Ship `nativecode_proot_fast.sh` synced to `/opt/nativecode/node-v26` as ADB debug mirror — Kotlin typed argv is canonical.

### PR5 — perf/proot-shell-node — Zsh + Node lazy — APPROVED

30. Rewrite `/home/flux/.zshenv` env-only, `.zprofile` minimal, `.zshrc` interactive `compinit -C` with cached `.zcompdump`; remove `nvm.sh` from `.zshenv`; lazy `nvm() { unset -f nvm; [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"; nvm "$@"; }`. Bundled `/opt/nativecode/node-v26` + symlinks; NVM optional; `~/.npmrc` audit/fund/progress cache internal never `/sdcard`; prefer single-binary AI CLIs; remove `--ignore-scripts` fallback.

### PR6 — perf/proot-session-runner — Persistent broker (depends on launcher) — socketpair lifecycle fix

31. **Bind**: host `$filesDir/run/proot-broker/` (0700) bind-mounted as `/run/nativecode-host/` inside guest via broker-only bind (not shared `/tmp`). Socket `broker.sock` 0600 same FS both sides (optional fallback when socketpair primary channel not used for PTY).

32. **Lifecycle + socketpair inherited FD (PASS 7-6)**: `ProotSessionRunner.kt` requires `proot_fast_launcher==true`. **Do not** `SO_PEERCRED(pid==broker PID)` — credentials on socketpair are those at `socketpair()` creation time, not eventual broker PID (man7 unix.7). The endpoint itself is capability.

    Correct JNI `native_startBroker()`:
    ```cpp
    int sv[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, sv);
    pid_t pid = fork();
    if(pid==0){
      close(sv[0]);
      dup2(sv[1], 3); // CONTROL_FD for guest broker
      close(sv[1]);
      // PDEATHSIG must be set AFTER fork (child's setting was cleared by fork, survives execve per PR_SET_PDEATHSIG.2const)
      prctl(PR_SET_PDEATHSIG, SIGKILL);
      if(getppid()!=expectedParentPid) _exit(1);
      execve(proot, argv, envp);
    } else {
      close(sv[1]);
      // parent holds sv[0] as hostEnd ParcelFileDescriptor
    }
    // guest broker after start: fcntl(CONTROL_FD, F_SETFD, FD_CLOEXEC) before spawning any guest git/npm/shell/compiler
    ```
    Broker started with `TerminalService`/`NativeCodeFgs` owner `OWNER_BROKER`, `START_NOT_STICKY`, `Process.destroyForcibly` on destroy, `DirectBoot` aware. Chain `app dies → PDEATHSIG → outer proot dies → --kill-on-exit → guest dies`. Guest broker marks control FD `FD_CLOEXEC` before `exec`'ing child commands (otherwise children inherit privileged channel). If filesystem socket needed for PTY/reconnect, use `SO_PASSCRED`/`SCM_CREDENTIALS` per-message (man7 unix.7) with one-time challenge via inherited pipe/FD (not env) and erase secret immediately, never propagate to `exec`'d children. Env token leak via `/proc/<pid>/environ` avoided.

33. Protocol `exec{id,argv,env,cwd,pty?}→stream{stdout,stderr,exit}` over socketpair FD 3; fallback to direct exec if broken. Warm caches internal.

### PR7 — perf/proot-syscall-experiments — Flagged, last — EXPERIMENTAL-ONLY

34. Count syscall classes via aggregate counters; verify `proot -V`, `proot -v 1`, seccomp, `process_vm_readv/writev`; if missing vendor Termux proot commit gated on Android 13–16; experiment path cache behind `proot_experimental_cache` default off; publish per-area target table, note 4K metadata cannot guarantee chroot parity.

Across PRs: never `rm -rf` outside verified `containers/.debian.installing-*` via dirfd, never `su/mount/umount/remount/sysctl/drop_caches/dev/block/governor/killall/pkill/system_server/SurfaceFlinger/DRM` (veto), streaming 64–256 KiB only, thermal pause via `ThermalStatusProvider`.

## File-Level Change Map

| File | Change | Rationale |
|------|--------|-----------|
| `app/build.gradle.kts:32-43` | **EDIT** fail-closed signing (providers+env, throw if !hasReleaseSigning on release) + distinguish password/upload-key/app-signing-key | P0-1 + PASS7 key wording |
| `.gitignore` | **EDIT** add `keys/` | hygiene |
| `docs/setup/signing.md` | **NEW** CI secret + Play App Signing model note | docs |
| `app/src/main/cpp/CMakeLists.txt` | **NEW** imported targets cmake | NDK PIC |
| `app/src/main/cpp/third_party/*` | **NEW** `libarchive.a/libzstd.a/liblzma.a/libcrypto.a` + `README.md` provenance | reproducible minimal |
| `app/src/main/cpp/native_rootfs_extractor.cpp` | **NEW** streaming parser+SHA+caps | PR2 |
| `app/src/main/cpp/native_rootfs_writer.cpp` | **NEW** per-component dirfd + safe symlink resolution (40 hops clamp re-walk) + deferred hardlink copies + hostile-target validate | PASS7 PR2-1/2/3 |
| `app/src/main/cpp/native_rootfs_lock.cpp` | **NEW** `descend_no_symlink` + flock before GC | P0-2 |
| `app/src/main/cpp/native_rootfs_crypto.cpp` | **NEW** bundled BoringSSL Ed25519 raw-byte verify | PR3 API26 |
| `app/src/main/cpp/native_proot_launcher.cpp` | **NEW** socketpair+fork+PDEATHSIG after fork+FD_CLOEXEC, control FD 3 | PASS7 PR6 |
| `app/src/main/java/com/zenithblue/nativecode/terminal/FastRootfsInstaller.kt` | **NEW** VerifiedRootfsArtifact, RENAME_NOREPLACE no fallback, syncfs, aid_* 01-10, hostile-target states | PR2 core |
| `app/src/main/java/com/zenithblue/nativecode/terminal/NativeRootfsExtractor.kt` | **NEW** JNI wrapper + progress, takes verified | UI |
| `app/src/main/java/com/zenithblue/nativecode/terminal/RootfsArtifactProvider.kt` | **NEW** Bundled/Play/Signed + allowlist | PR3 |
| `app/src/main/java/com/zenithblue/nativecode/terminal/RootfsManifestVerifier.kt` | **NEW** raw-byte verify → VerifiedRootfsManifest | PR3 |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ProotFastCommandBuilder.kt` | **EDIT** typed `buildExec/buildShell` argv-only, sysdata `containers/debian/sysdata/*` fake proc + `st_nlink==1` check, fix `/opt/nodejs` → `/opt/nativecode/node-v26` | PASS7 PR4-4/5 |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ProotSessionRunner.kt` | **NEW** socketpair inherited FD 3, no env token, no SO_PEERCRED PID, CLOEXEC before children | PASS7 PR6 |
| `app/src/main/java/com/zenithblue/nativecode/terminal/SafeBenchController.kt` | **NEW** host thermal/storage/Mem + ThermalStatusProvider DI + split durability metrics | PR1 |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ThermalStatusProvider.kt` | **NEW** interface Android/Fake | testing |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ProjectPathResolver.kt` | **EDIT** stagingDir/brokerSocket/imageJson + lock-before-GC + sysdata path helper | SSOT |
| `app/src/main/java/com/zenithblue/nativecode/terminal/TermuxHostPaths.kt` | **EDIT** `IMAGE_JSON=/etc/nativecode/image.json` + sysdata path | capabilities |
| `app/src/main/java/com/zenithblue/nativecode/terminal/ProotCommandBuilder.kt` | **EDIT** keep compat fallback, deprecate default | flag |
| `app/src/main/java/com/zenithblue/nativecode/terminal/LinuxCommandBuilder.kt` | **EDIT** route via Fast when flag | switchboard |
| `app/src/main/java/com/zenithblue/nativecode/OnboardingActivity.kt` | **EDIT** call FastRootfsInstaller with VerifiedRootfsArtifact | install flow |
| `app/src/main/java/com/zenithblue/nativecode/MainActivity.kt` | **EDIT** same | repair |
| `app/src/main/assets/scripts/nativecode_proot_fast.sh` | **UPDATE** sync Kotlin typed argv, unify node path, debug mirror note | PR4 |
| `app/src/main/assets/scripts/flux_install.sh` | **EDIT** early exit when image.json capability present | no-op |
| `app/src/main/assets/scripts/setup_debian_family.sh` | **EDIT** `nativecode-has-cap` not `jq` | PR3 |
| `app/src/main/assets/scripts/setup_cli_tools.sh` | **EDIT** lazy nvm, bundled node check, use has-cap | PR5 |
| `app/src/main/assets/rootfs/debian_13_core_arm64.tar.zst` | **NEW** core in APK with `--numeric-owner` 1000:1000 | size+ownership |
| `app/build.gradle.kts:ndkVersion+externalNativeBuild+noCompress zst` | **EDIT** NDK plumbing | build |
| `.github/workflows/build-golden-rootfs.yml` | **NEW** native arm64, image.json, signed manifest, numeric-owner test, parity split | CI |
| `bench/nc-io-bench.c` | **NEW** glibc arm64, seq/rand/metadata, buffered/direct, fdatasync split + durability split | PR1 + PASS7 AC1 |
| `bench/nc-membench.c` | **NEW** syscall-free, pre-touch, volatile sink | PR1 |
| `bench/run-safe-suite.sh` | **NEW** guest limits | PR1 |
| `bench/result-schema.json` | **NEW** schema with extract/publish/durability_ms | SSOT |
| `docs/perf/proot-parity-v2.md` | **NEW** fingerprint template + before/after + durability metrics | gate |

No edits to `jniLibs` proot in early PRs; PR7 vendors only after matrix. **Note**: `ProotFastCommandBuilder.kt` is edit/refactor of existing file, not creation.

## Testing Strategy

**Unit (JVM + native gtest):**
- `FastRootfsInstallerTest` — fresh-only hostile states (absent→FAST, valid→ALREADY, incomplete→REPAIR, symlink/file→HOSTILE), SHA streaming, caps, `FAST_PUBLISH_UNSUPPORTED` no fallback, kill mid → staging only, lock-before-GC, `descend_no_symlink` rejects symlink at any intermediate, aid_* appended, syncfs before rename, `st_nlink==1` check.
- `NativeRootfsWriterTest` — 12 zip-slip + safe symlink resolution: `lib→/usr/lib` then child `/lib/foo` → resolved to `usr/lib/foo` inside rootfs (ACCEPT), symlink chain escape clamped to root (ACCEPT symlink object, resolution stays inside), 40 hops ELOOP, deferred hardlink copy verifies source regular file, hardlink through symlink reject (source not regular), `O_NOFOLLOW` re-walk rejects race-swapped dir.
- `ProotFastCommandBuilderTest` — typed `buildExec` no shell metachar, `buildShell` adds single `-c` arg, per-profile REQUIRED/OPTIONAL/FORBIDDEN binds + sysdata binds validated via `lstat`+`st_nlink==1` mock, env contains PATH/LANG/TMPDIR/gpu, unified `/opt/nativecode/node-v26` not `/opt/nodejs`, spaces/`'`/`"`/`$`/`;`/`&`/unicode/newline in guestArgv not reparsed, never `zsh -c "bash -lc"`, safety refuses `com.termux`.
- `RootfsManifestVerifierTest` — raw-byte Ed25519 via bundled BoringSSL on API26, bad sig reject, parse after verify only, allowlist origin reject arbitrary URL, minAppVersion, arch mismatch.
- `ThermalStatusProviderTest` — Fake SEVERE → THERMAL_ABORT promptly, no heating, passive observe only.

**Instrumented / host:**
- Kill-mid: `kill -9` at 25/50/75% → staging removed, `containers/debian` untouched (hostile-target check not bypassed), next install succeeds.
- `descend_no_symlink` symlink attack at `var`/`lib` → fails `INSTALL_ERROR`.
- Archive format bench: same manifest `.tar.xz` vs `.tar.zst` L3–6 vs `.tar` → table.
- **AC13A/B parity**: extraction 100% manifest diff (AC20/21), installed-container 10-point check (AC23).
- Tar ownership: `tar --list --numeric-owner` `home/flux 1000:1000` + runtime `stat 1000:1000` both chroot+proot.
- Broker FD lifetime: after broker start, `ls /proc/<broker>/fd` missing control FD in child `git`/`npm` (CLOEXEC), socketpair endpoint is capability, `SO_PEERCRED` PID not assumed.

**Device (MT6897 + Adreno750, Android 13–16):**
- Launch microbench 20-run medians cli+fast_launcher typed argv: `/bin/true` <150ms, `/bin/sh -c true` <175ms, `git --version` <200ms; argv log shows `buildExec` not `sh -c "exec proot"`.
- `SafeBenchController` + `nc-*` suite: sysbench cpu/stress-ng, seq 1M buffered+direct, rand 4K IOPS, metadata, dd 256MiB, npm/node, AI CLI, small C compile, 100-file make vs ninja, git clone/status/diff `/data/...` vs `/sdcard` (passive thermal only).
- Thermal DI: `FakeThermalStatusProvider` NONE→MODERATE→SEVERE → child terminates promptly, no new bench, `THERMAL_ABORT`; no heating.
- Provisioning npm small pkg cold vs warm, TS install.
- Durability fairness: report `extract_ms` `publish_ms` `durability_ms` `total_install_ms`; AC1 compares extraction/publish before durability or equivalent durability on both sides; reference `tar --numeric-owner -xf` vs fast installer with same syncfs boundary.
- Guards: storage <1GiB abort, RAM cap `min(256MiB,avail/8)`, extractor ≤256KiB bench seq1MiB rand4KiB.

**Regression:**
- Interactive colors/PATH/Node/npm/AI CLIs/SSH/WINCH/Ctrl-C/exit, `--shared-tmp`, storage binds, sysdata `/proc` binds readable condition.
- Install fresh/hostile/incomplete/interrupted/SHA-corrupt/`FLUX_ROOTFS_PATH` python path.
- Profiles cli/compat/turnip/virgl each 20/20 boots, `vulkaninfo` Turnip on Adreno.
- Chroot parity untouched.
- Signing: `assembleRelease` without creds fails, `assembleDebug` passes (AC19).

**8-pass review** after PR2 and PR4/PR6; veto any `su/mount/killall` in PR2–PR6 perf diff scoped exhaustive.

## Acceptance Criteria (objective, verifiable)

- [ ] **AC0 Signing hygiene** — `git grep storePassword` zero; `assembleRelease` without `nativecode.keystore.*`/`NATIVECODE_KEYSTORE*` MUST FAIL (GradleException when Release task), `assembleDebug` MUST PASS; `keys/` gitignored; exposed password rotated, upload-key via Play reset if needed, app-signing key not casually replaced.
- [ ] **AC1 Fresh-destination install (durability-fair)** — Per-SoC (MT6897+Adreno750) fresh-destination/local-archive 3-run median `RENAME_NOREPLACE` with split metrics `extract_ms`/`publish_ms`/`durability_ms`/`total_install_ms`; comparison uses extraction/publish before durability or equivalent `syncfs` on both sides; fast ≤1.15× `tar --numeric-owner --use-compress-program=unzstd -xf` to `/data/local/tmp` (±10% noise), archive cache state recorded, no missing target, no fallback rename.
- [ ] **AC2 No partial install** — Kill at 25/50/75% 10/10 leaves `containers/debian` untouched (lock+syncfs+hostile check), next install succeeds after boot_id GC, no manual clean.
- [ ] **AC3 Archive security (12 cases + per-component + safe resolve)** — Unit rejects `entry /absolute`, `entry ../../escape`, device/FIFO/duplicate/oversize; accepts `relative symlink`, `absolute symlink /usr/lib→/usr/lib`; also `descend_no_symlink` rejects symlink at any intermediate `var/lib/proot-distro` component; hardlink through symlink rejected (source not regular); 40 hops ELOOP.
- [ ] **AC4 Launch latency + typed argv** — MT6897 cli+fast_launcher warm 20-run: `/bin/true`<150ms `/bin/sh -c true`<175ms `git --version`<200ms; `ProotFastCommandBuilder` exposes `buildExec(List<String>)` and `buildShell(String)` typed API — grep `buildExec(` present, `split(Regex.*\\s+)` absent for tool exec, `ProcessBuilder.*libproot` present, host `shell -c.*proot` absent; `zsh -c "bash -lc"` never emitted; `toolExec` quoting preserved.
- [ ] **AC5 Shell startup** — `dash -c 'echo ok'` no `nvm.sh`; `nvm` lazy only on invoke; `zsh -l` prompt <300ms warm 5-run median.
- [ ] **AC6 Golden rootfs capabilities + ownership + has-cap** — CI boots: `dpkg --audit` clean, `node` pinned 26.x at `/opt/nativecode/node-v26`→`/usr/local/bin/node`, `99nativecode-perf`, `C.UTF-8`, `/bin/sh→dash`, `/etc/nativecode/image.json` schema2 profile/capabilities correct, `setup_*` uses `nativecode-has-cap` not `jq`/boolean, `tar --numeric-owner` preserves `/home/flux 1000:1000` (archive `tar tv` check) and runtime `stat 1000:1000` in **both** chroot and PROOT (`--change-id` virtualizes).
- [ ] **AC7 Fair bench ratio** — Same glibc arm64 `nc-io-bench` both envs seq 1M buffered+direct+rand4k+metadata; `R_nc=proot_nc/chroot_nc` `R_mbw=proot_mbw/chroot_mbw` `abs(R_nc-R_mbw)≤0.10`; `nc-membench` zero-syscall; write reports `write_data_ns`+`fdatasync_ns`; `fio` absent with doc why shmget fails.
- [ ] **AC8 Profile correctness (semantic + sysdata)** — CLI REQUIRED `/dev` `/proc` `/sys` `/dev/urandom:/dev/random` host `tmp:/tmp`; OPTIONAL WHEN AVAILABLE `/dev/shm` selinux stub storage; FORBIDDEN `/system` `/vendor` `/apex` dalvik-cache full PREFIX; each profile 20/20 boots exit 0; turnip includes `/system,/vendor,/apex,kgsl` when present, virgl includes X11 socket; **sysdata**: `containers/debian/sysdata/sys_empty→/sys/fs/selinux` validated `lstat+O_NOFOLLOW+st_nlink==1`, plus conditional `sysdata/{loadavg,stat,uptime,version,sysctl_*}→/proc/*` when real host entry unreadable, same readable-real-entry condition as PRoot-Distro; not exact 5–7 count.
- [ ] **AC9 Safety veto (scoped exhaustive)** — Grep of **all added production lines in PR2–PR6** (`FastRootfsInstaller.kt`, `NativeRootfsExtractor.kt`, `RootfsManifestVerifier.kt`, `ProotFastCommandBuilder.kt` (edit), `ProotSessionRunner.kt`, `LinuxCommandBuilder.kt`, `ShellCommandRunner.kt`, `nativecode_proot_fast.sh`, `app/src/main/cpp/*` inc `native_rootfs_crypto.cpp`+`native_proot_launcher.cpp`, `bench/*`, `.github/workflows/build-golden-rootfs.yml` **runtime** vs CI workflow mount separately) zero `mount|umount|remount|sysctl|drop_caches|/dev/block|governor|devfreq|swap|zram|killall|pkill|system_server|SurfaceFlinger|glmark.*drm` in device runtime code; kill logic only `pid in sessionSet && uid==appUid`; file list in `veto.sh`.
- [ ] **AC10 Safe suite guards (corrected buffers + DI thermal no heating)** — `SafeBenchController` via `ThermalStatusProvider` aborts if free<1GiB / thermal SEVERE / RAM cap; caps I/O 256MiB RAM `min(256MiB,avail/8)`; streaming extractor/decompressor ≤256KiB bench seq1MiB rand4KiB; test uses `FakeThermalStatusProvider(SEVERE)` NONE→MODERATE→SEVERE → `THERMAL_ABORT` promptly, real device only passively observes thermal during short runs; no intentional heating to SEVERE.
- [ ] **AC11 Flag dependency + socketpair lifecycle** — Flags `proot_fast_install`, `proot_fast_launcher`, `proot_persistent_runner` (requires launcher, fallback), `proot_experimental_cache` (requires launcher); each off→legacy; broker `filesDir/run/proot-broker→/run/nativecode-host` 0700/0600 but **primary channel is socketpair inherited FD 3** not path socket; `START_NOT_STICKY`+`NativeCodeFgs`+`PR_SET_PDEATHSIG after fork`+`--kill-on-exit` no orphan; control FD `FD_CLOEXEC` before spawning guest commands.
- [ ] **AC12 No regression** — `docs/environment/plans/regression-test-suite.md` P0 gates pass both devices after PR4/PR6.
- [ ] **AC13A Extraction parity** — NativeRootfsExtractor vs `proot-distro install_from_local_file` same archive → manifests `path|type|mode|size|SHA|symlink|hardlink` 100% identical, ownership `0:0` vs flux documented only for extraction.
- [ ] **AC13B Installed-container compatibility** — Full FastRootfsInstaller (01-10 fixups inc sysdata) vs full proot-distro install → `resolv.conf`/`hosts`/`aid_*`/`fake sysdata`/`bin/sh→dash`/`flux 1000:1000`/`launch`/`apt/dpkg status` all compatible, launch succeeds.
- [ ] **AC14 Lock/path security** — Every component `PREFIX→var→lib→proot-distro→locks/containers` opened via `descend_no_symlink` `O_DIRECTORY|O_NOFOLLOW`; GC executes only after exclusive `debian.lock` held; `O_NOFOLLOW` last-component-only limitation documented.
- [ ] **AC15 Publish fallback** — `renameat2(RENAME_NOREPLACE)` ENOSYS/EINVAL → returns `FAST_PUBLISH_UNSUPPORTED`, staging discarded, legacy python path used, **no plain `renameat()` fallback** race; never half-published/replaced.
- [ ] **AC16 Proot-distro fixup parity** — Reproduces `write_resolv_conf`, `write_hosts`, `register_android_ids` (aid_*), `setup_fake_sysdata` semantics per `install.py:273-282`+`rootfs.py`+`sysdata.py`; `flux 1000:1000` stays plus aid_*.
- [ ] **AC17 Rootfs signature compat (API26)** — `SignedReleaseProvider` Ed25519 via bundled `libcrypto.a` JNI `native_rootfs_crypto.cpp` passes on API26 emulator/device, not `Signature.getInstance("Ed25519")` API33+; raw manifest bytes verified before parse, allowlist origin only, `minAppVersionCode` validated, `VerifiedRootfsManifest` enforced.
- [ ] **AC18 Chroot ownership** — Golden tar `--numeric-owner` preserves `/home/flux 1000:1000`; `stat` in both chroot and PROOT reports `1000:1000` (proot via `--change-id` virtualization).
- [ ] **AC19 Release signing fail-closed** — `assembleRelease` without prod creds fails (GradleException when Release task), `assembleDebug` passes; never silently debug-signs release; password/upload-key/app-signing-key rotation distinguished.
- [ ] **AC20 Safe symlink resolution** — Existing parent symlinks logically resolved with guest-root clamp and ≤40 hops; resolved components re-walked `O_NOFOLLOW` before write; legitimate `/lib→/usr/lib` child case `lib/foo` written to `usr/lib/foo` inside rootfs passes; escape chain clamped.
- [ ] **AC21 Hardlink parity** — Hardlinks deferred and materialized as independent regular-file copies after safe re-resolution of both endpoints; no `linkat` rootfs hardlink creation; source must be regular file not symlink; mutation of one not affecting another.
- [ ] **AC22 Hostile target** — Existing `containers/debian` that is not validated real directory (symlink/file/FIFO) fails closed `HOSTILE_OR_CORRUPT_STATE`; no follow/delete/reset automatically; `EEXIST` correctly classified.
- [ ] **AC23 Fast sysdata parity** — Direct launcher binds validated `containers/debian/sysdata/*` fake proc/sys entries with same readable-real-entry conditions as PRoot-Distro (`sys_empty→/sys/fs/selinux` + `loadavg/stat/uptime/version/sysctl_*→/proc/*` when unreadable, `st_nlink==1` check).
- [ ] **AC24 Broker FD lifetime** — `socketpair` created by native launcher `native_proot_launcher.cpp`; `PR_SET_PDEATHSIG` set in child after fork (cleared by fork, survives execve), `getppid` verified, `execve(proot…)`; broker marks control FD `FD_CLOEXEC` before spawning any guest command; `SO_PEERCRED` PID not used as broker-identity proof (`SO_PEERCRED` on socketpair reports creation-time creds, `SCM_CREDENTIALS` only if per-message sender creds needed).

## Risks & Mitigations (NEW_RISKS)

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Symlink escape via logical resolution bug | Low if 40 hops + clamp + re-walk | High | Safe resolve with guest-root clamp, 40 hops, re-walk `O_NOFOLLOW`, 12+case suite inc `/lib→/usr/lib` child, race-swapped dir rejected by second `O_NOFOLLOW` |
| Hardlink inode sharing mutation | Low if deferred copy | High | Deferred copies via safe source/dest resolve, source must be regular file, no `linkat`, test mutation isolation |
| Hostile symlink/file at debian path | Medium if manually created | High fail-closed | Validate `fstatat(AT_SYMLINK_NOFOLLOW)` `S_ISDIR` + marker + ownership; `HOSTILE_OR_CORRUPT_STATE` fail closed, no auto-delete |
| Sysdata hardlink planting | Low | High | Validate `lstat`+`O_NOFOLLOW`+`st_nlink==1` per upstream, only bind when real host entry unreadable |
| Broker FD leak to children | High if no CLOEXEC | Security | `fcntl(FD_CLOEXEC)` before spawning guest commands, socketpair endpoint is capability not path |
| PDEATHSIG not set in child | High if set in parent | Orphan | Set `PR_SET_PDEATHSIG` after fork in child, verify `getppid`, survives execve per man7 |
| SO_PEERCRED PID misuse | Medium | Auth bypass | Do not use `SO_PEERCRED.pid==broker PID` on socketpair; private inherited socketpair is capability, use `SCM_CREDENTIALS` only if needed |
| Escape if dirfd not per-component | Low if descend_no_symlink | High | Walk each component `O_NOFOLLOW`, test symlink at `var`/`lib` level |
| RENAME_NOREPLACE not supported | Low (ext4/f2fs ok) | Install fail | Return `FAST_PUBLISH_UNSUPPORTED`, discard staging, fallback legacy python, no plain rename race |
| NDK bloat / generic .a | Medium | APK bloat | Only arm64-v8a PIC `-fPIC` API26+ minimal tar+xz+zst, provenance README, fallback python |
| Fresh-only UX | Low | UX | `ALREADY_INSTALLED`/`NEEDS_EXPLICIT_REPAIR`/`HOSTILE` distinct toasts + explicit repair/reset |
| Zstd vs xz winner wrong | Medium | Size/time | Dual-format PR2, 4-format table before PR3, keep xz fallback |
| Accelerator missing | High some kernels | Perceived regression | Profile `proot -V`+`proot -v 1` evidence, vendor gate 13-16, cache flagged off |
| Lazy NVM breaks shell | Low | Compat | Keep flag, test `nvm use`+AI CLIs |
| Fio/sysbench confusion | Low | Unfair ratio | Doc why shmget fails, enforce nc-io-bench, ratio `abs(R_nc-R_mbw)≤0.10` |
| Thermal/storage watchdog | Medium | Brick/heat | DI Fake provider test, passive observe on device, storage/RAM caps, streaming only |
| Proroot premature | Low | Stability | `NATIVE_GLIBC` future only |
| Stale GC race | Low | Storage/corruption | Lock-before-GC, boot_id+elapsedRealtime via dirfd |
| Signing rotation / Play key mis-rotation | High if mishandled | Update disaster | Fail-closed, distinguish password/upload-key/app-signing-key, Play reset process, AC19, history scrub |
| Ed25519 API26 compat | Medium if platform API used | Verify fails on 26 | Bundled BoringSSL JNI, test on API26 emulator |
| Raw-before-parse bypass | High if parse before verify | Malicious manifest | Verify raw bytes before parse, allowlist origin, minAppVersion, arch check |
| Tar ownership 0:0 destroys flux | High if --owner=0 | Chroot breaks | Use --numeric-owner, test archive + runtime both envs |
| AC1 durability unfair | Medium if syncfs penalized | False fail | Split extract/publish/durability metrics, compare extraction/publish before durability or equivalent durability both sides |

## Handoff to Plan Reviewer

**PASS 7 REVISE → 6 PRs require small contract fixes, architecture already APPROVED, PR0/PR1 UNBLOCKED:**

1. **PR2 safe symlink resolution (P0)** — Replace blanket "never follow" with logical resolve: `readlinkat` existing parents → `absolute ⇒ reset to guest root` / `.. ⇒ pop clamped` / `≤40 hops` → re-walk `O_DIRECTORY|O_NOFOLLOW` dirfd; second `O_NOFOLLOW` rejects race-swapped dir. Verify `AC20` `/lib→/usr/lib` child passes, escape clamped, AC13A still 100%.
2. **PR2 hardlinks deferred copies (P0)** — Do not `linkat`; queue hardlink entries, validate `linkname` components, finish regular files, safe-resolve source (must be `S_ISREG` not symlink) + dest parent, copy bytes/mode/mtime to new inode. Verify `AC21` mutation isolation, no `linkat` in writer.
3. **PR2 hostile-target (P0)** — Classify `containers/debian` via `fstatat(AT_SYMLINK_NOFOLLOW)` into `FAST_INSTALL`/`ALREADY_INSTALLED`/`NEEDS_EXPLICIT_REPAIR`/`HOSTILE_OR_CORRUPT_STATE` fail-closed; `RENAME_NOREPLACE` already prevents replacement. Verify `AC22` symlink/file/FIFO at debian fails closed.
4. **PR4 sysdata parity (P0)** — Per-container `containers/debian/sysdata/*` binds: `sys_empty→/sys/fs/selinux` + conditional `loadavg/stat/uptime/version/sysctl_*→/proc/*` when real host entry unreadable, validated `lstat+O_NOFOLLOW+st_nlink==1` (prevents guest hardlink planting). Verify `AC23`/`AC13B` 10-point check. Clarify `ProotFastCommandBuilder.kt` is **edit/refactor** of existing file.
5. **PR4 typed argv API (P0)** — Expose `buildExec(guestArgv:List<String>)` and `buildShell(shellString:String)`; `buildExec` never `split(Regex("\\s+"))`; only `buildShell` adds `dash -c <one argv>`; fixes `toolExec.second.split("\\s+")` quoting loss (`spaces/'/"/$/; /&/unicode/newline`). Verify `AC4` grep.
6. **PR6 socketpair lifecycle (P0)** — `native_proot_launcher.cpp` creates `socketpair`, `fork`, child `close(hostEnd) dup2(childEnd,3) close(childEnd) prctl(PR_SET_PDEATHSIG,SIGKILL) after fork (cleared by fork, survives execve) verify `getppid` then `execve(proot)`, parent `close(childEnd)`. Guest broker `fcntl(CONTROL_FD,F_SETFD,FD_CLOEXEC)` before spawning child commands. Do not use `SO_PEERCRED.pid==broker PID` (creation-time creds per man7 unix.7); `SCM_CREDENTIALS` only if per-message needed. Verify `AC24`.
7. **AC1 durability fairness** — `SafeBenchController` reports `extract_ms|publish_ms|durability_ms|total_install_ms`; AC1 compares extraction/publish before durability or applies equivalent `syncfs` to reference `tar --numeric-owner -xf` so unrelated dirty `/data` not unfairly fails. Verify split metrics in `result-schema.json`.
8. **PR0 key rotation wording** — Distinguish `exposed passwords → rotate`, `Play upload key → Play reset`, `Play app-signing key → do NOT casually replace`, per actual Play signing model. Verify `docs/setup/signing.md`.
9. **AC14-24 added** — `AC20` safe symlink resolution, `AC21` hardlink deferred copies, `AC22` hostile target, `AC23` sysdata parity, `AC24` broker FD lifetime with PDEATHSIG after fork + CLOEXEC.

**Requested verdict**: `PLAN_STATUS: APPROVED` (`PR0:START` `PR1:START` `PR2:START after symlink/hardlink contract added (no redesign)` `PR3:AFTER PR2` `PR4:AFTER PR1 (sysdata+typed API)` `PR5:AFTER PR4` `PR6:AFTER PR4 with socketpair lifecycle` `PR7:EXPERIMENTAL ONLY`) — worker may start PR0+PR1 immediately; PR2 architecturally sound after these last contract details (last redesign needed). If REVISE, annotate which PR(s) still need reshaping; if BLOCK, state hard limit.

If REVISE, annotate which PR(s) still need reshaping; if BLOCK, state ptrace hard-limit unachievable target.

