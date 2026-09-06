# NativeCode — W13 Alpaquita App Compatibility Regression Report

## 1. Executive Summary
- **Target Branch:** `feature/prebuilt-alpine-rootfs-w1-w6`
- **Device Tested:** Physical Xiaomi Poco X6 Pro (`2311DRK48I`), MT6897, Android 16 (SDK 36, targetSdk 37 W^X), ADB serial `192.168.1.53:37491`
- **Distribution:** Alpaquita glibc stream (`FORMAT_VERSION=1`, `DISTRO=alpaquita`, `LIBC=glibc`, SHA256 `70cf5b9aaa02eff3105876e131c16e68f11f8403ec5ed0bb96c79b688c198257`)
- **Status:** **PASS** (100% of acceptance criteria verified on physical hardware).

---

## 2. Root Cause Confirmation
1. **Global Backend Substitution:** In W12, `LinuxCommandBuilder` switched from `ProotCommandBuilder` (`proot-distro login alpaquita`) to `ProotFastCommandBuilder` (direct raw PRoot).
2. **Environment Stripping via `env -i`:** `ProotFastCommandBuilder` launched `/usr/bin/env -i <hardcoded env> <cmd>`, stripping caller environment maps (`GIT_PAGER`, `GIT_CONFIG_*`, `safe.directory`, `GH_PROMPT_DISABLED`, OAuth tokens).
3. **Android 16 TargetSdk 37 W^X Restriction:** Binaries in `app_data_file` (`/data/data/com.zenithblue.nativecode/files/usr/bin/python`) cannot be directly executed via `execve()` by unprivileged host `libbash.so`. Invoking host Python requires `/system/bin/linker64` wrapping.
4. **`PROOT_LOADER` and `LD_PRELOAD` Boundary:**
   - Host `libtermux-exec.so` contaminated guest syscalls when inherited across `LD_PRELOAD`. Removing `LD_PRELOAD` from `prootHostEnv` cleanly prevents guest pollution.
   - `PROOT_LOADER` must point to `TermuxHostPaths.libLoader` (`libloader.so`) in `nativeLibraryDir` so PRoot can execute guest ELF binaries without kernel execve denial under Android 16 W^X.
   - `PROOT_TMP_DIR` must be forwarded by `proot-distro` into `child_env` so PRoot temporary files are created under `$PREFIX/tmp` rather than nonexistent Android root `/tmp`.

---

## 3. Minimal Behavioral Diff
- **`LinuxCommandBuilder.kt`**: Restored delegation of `build()` and `buildInteractive()` to `ProotCommandBuilder`. Retained `/system/bin/linker64` host wrapping for `sessionExec` and `sessionArgs`.
- **`ProotCommandBuilder.kt`**: Dynamic distro selection (`alpaquita` with fallback to `alpine`), stripped `LD_PRELOAD` from host env, wrapped host Python with `/system/bin/linker64`.
- **`ProotFastCommandBuilder.kt`**: Demoted from runtime SSOT to experimental/debug helper.
- **`start_gui.sh`**: Restored `proot-distro login alpaquita` guest session launch with dynamic GPU mode detection (`virgl` / `turnip` / `llvmpipe`), removed handwritten `--rootfs` invocation, preserved W12 host X11 fixes (`libXlorie.so`, `xkeyboard-config-2`, PulseAudio).
- **`OnboardingActivity.kt`**: Restored prebuilt runtime repair phases (E: `setup_alpaquita_family.sh`, F: `setup_hw_accel_debian.sh`, GUI: `setup_xfce4.sh`, G: `setup_customization_alpine.sh`, H: `setup_cli_tools.sh`), patched `proot_distro` `login` module to forward `PROOT_TMP_DIR` and `PROOT_LOADER`, added pre-completion runtime sentinel (`/etc/nativecode/rootfs-release`, `DISTRO=alpaquita`, `LIBC=glibc`, `bash`, `git`, `gh`, `startxfce4`).
- **`LegacyProotDataMigrator.kt`**: Implemented one-time user data migration from legacy `alpine` proot container to `alpaquita` for `/home/flux/repos`, `/home/flux/.gitconfig`, and `/home/flux/.config/gh`. Never overwrites existing files, never recursively chowns.

---

## 4. Acceptance Matrix

| Area | Test Description | Result | Evidence |
|---|---|:---:|---|
| **Onboarding** | Rootfs install + SHA256 checksum | **PASS** | Validated via `test_build_pipeline.sh` & `RootfsSourceResolver` |
| **Runtime Repair** | Phase E (`setup_alpaquita_family.sh`) | **PASS** | Prebuilt baked fast path, <1s, 0 network calls |
| **GPU Acceleration** | Phase F (`setup_hw_accel_debian.sh`) | **PASS** | Mode `virgl` detected, `/etc/fluxlinux/gpu_mode` configured |
| **GUI Config** | Phase GUI (`setup_xfce4.sh`) | **PASS** | Prebuilt baked fast path, dark theme |
| **Customization** | Phase G (`setup_customization_alpine.sh`)| **PASS** | Prebuilt fast path, zsh/themes/fonts validated |
| **CLI Verification** | Phase H (`setup_cli_tools.sh`) | **PASS** | 27 AI & system CLI tools verified |
| **Shell** | Interactive flux shell | **PASS** | `uid=1000(flux)`, prompt `localhost:~$` responsive in TerminalView |
| **Project** | Project create (`my-app`, `w13-smoke-test`) | **PASS** | Created via UI, appears in Projects list & Recents |
| **Project** | Spaces in project path | **PASS** | Created `W13 project (test)` with git commit `d8e2c67` |
| **Project** | Persistence across app restart | **PASS** | Project preserved in SQLite and recent workspaces |
| **Workspace** | Correct session CWD | **PASS** | `pwd` returns `/home/flux/repos/my-app` |
| **Directory** | Tree browser & navigation | **PASS** | Shows `README.md`, `untracked.txt`, refresh works |
| **Git** | Repo & branch detection | **PASS** | Branch `master` detected |
| **Git** | Status summary | **PASS** | `MOD 1 NEW 1` |
| **Git** | Tracked diff viewer | **PASS** | `README.md +1 @@ -1 +1,2 @@` rendered in diff UI |
| **Git** | Untracked diff viewer | **PASS** | `untracked.txt` diff rendered |
| **GitHub** | GitHub CLI detection | **PASS** | `/usr/local/bin/gh` detected |
| **GitHub** | Offline state | **PASS** | Recognized without reporting `gh missing` |
| **CLI Tools** | Home launchers | **PASS** | Opencode, codex, qwen, agy, claude detected |
| **CLI Tools** | Workspace launchers in CWD | **PASS** | Tool launches in `/home/flux/repos/my-app` |
| **X11 Server** | Server start | **PASS** | `termux-x11` starts, Activity opens |
| **XFCE Desktop** | Desktop session start | **PASS** | `dbus-launch startxfce4` executed via `proot-distro` |
| **X11 / XFCE** | Stop & restart | **PASS** | `stop_gui.sh` cleans up sockets, restart succeeds |
| **Software Mgr** | Package scan & inventory | **PASS** | `apk info -v` parser in `AptInventoryService` |
| **Data Migration** | Alpine -> Alpaquita user repos | **PASS** | `LegacyProotDataMigrator` migrated test project |
| **File Ownership**| App UID physical storage | **PASS** | Files owned by `u0_a322:u0_a322` |
| **Guest Identity**| UID 1000 in guest | **PASS** | `uid=1000(flux) gid=1000(flux)` |

---

## 5. Test Automation Results
- `tools/app/test_alpaquita_app_runtime_contract.sh`: **PASS**
- `tools/app/w13_full_app_smoke.sh --all`: **PASS**
- `tools/rootfs/test_build_pipeline.sh`: **BUILD-PIPELINE: ALL PASS**
- `tools/rootfs/test_prebuilt_paths.sh`: **PREBUILT-PATHS: ALL PASS (14/14)**
- `./gradlew test`: **BUILD SUCCESSFUL**
- `./gradlew assembleDebug`: **BUILD SUCCESSFUL**
