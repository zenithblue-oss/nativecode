# W12 In-App Prebuilt Alpaquita Rootfs Integration Report

## Executive Summary

- **Status**: **PASS (PRoot Pure Sandbox)**
- **Target Hardware**: Xiaomi Poco X6 Pro (`2311DRK48I`), MediaTek Dimensity 8300 Ultra (`MT6897`), 8 cores @ 3.35 GHz, 8 GB RAM, Android 16 (`SDK 36`), App Package `com.zenithblue.nativecode`, UID `u0_a322`.
- **Target Distribution**: BellSoft Alpaquita Linux Stream (glibc) aarch64.
- **Rootfs Artifact**: `tools/rootfs/out/alpaquita_rootfs.tar.zst` (1,346,323,800 bytes, SHA256 `70cf5b9aaa02eff3105876e131c16e68f11f8403ec5ed0bb96c79b688c198257`).

---

## 1. Scope of Work Completed

### A. Keystore & Security Decoupling
- Stripped hardcoded passwords and key aliases from `app/build.gradle.kts`.
- Configured dynamic credential loading via `key.properties` or environment variables with fallback to auto-generated debug keys.
- Ignored `key.properties` and keystores in `.gitignore`.

### B. Dynamic Rootfs Delivery & Onboarding Modernization
- Implemented `RootfsSource` sealed hierarchy (`LocalFile`, `AssetPack`, `Bundled`) and `RootfsSourceResolver`.
- Created Play Asset Delivery on-demand asset pack module `:nativecode_runtime`.
- Implemented `PlayAssetDeliveryManager` using `com.google.android.play:asset-delivery-ktx:2.3.0`.
- Streamlined `OnboardingActivity.kt`: replaced obsolete host/guest provisioning scripts (`setup_termux.sh`, `setup_alpaquita_family.sh`, `setup_hw_accel_debian.sh`, `setup_xfce4.sh`) with 2-phase deployment:
  1. Extract minimal Termux host bootstrap (`bootstrap.tar`).
  2. Direct installation of prebuilt rootfs via `python proot-distro install <source> --name alpaquita`.

### C. Pure PRoot Execution & Android 16 / SDK 37 Compatibility
- Wired `LinuxCommandBuilder` to delegate to `ProotFastCommandBuilder`.
- Wrapped host ET_DYN binaries (`libproot.so`, `libbash.so`) with `/system/bin/linker64` for execution under targetSdk 37 W^X policies.
- Fixed `LD_PRELOAD=libtermux-exec.so` contamination: ensured `LD_PRELOAD` is removed in `ProotFastCommandBuilder` and `ShellCommandRunner.applyEnvironment` so PRoot internals, D-Bus, and XFCE sockets are not mangled.
- Added native library extraction for `libXlorie.so` and configured `xkeyboard-config-2` path for embedded `termux-x11`.
- Extended `AptInventoryService` to parse both Debian `dpkg-query` and Alpine/Alpaquita `apk info -v` package records.

---

## 2. In-App Verification Results on Physical Poco X6 Pro

| Feature / Subsystem | Isolation | Result | Evidence / Details |
|---|---|---|---|
| **Onboarding Flow** | PRoot | **PASS** | Auto-detected `/data/local/tmp/alpaquita_rootfs.tar.zst`, unpacked container into app private files dir in under 55s, created `profileInstalled` sentinel, navigated to `MainActivity`. |
| **Interactive Terminal** | PRoot | **PASS** | `TerminalSession` spawned interactive shell as user `flux` (UID 1000). Executed `uname -a`, `id`, `node -v` (v24.18.1), `python` (3.14.6), `git`. |
| **AI CLI Tools Detection** | PRoot | **PASS** | Settings Hub $\rightarrow$ AI CLI Tools probed installed binaries. `claude`, `codex`, `qwen`, `opencode`, and `agy` all detected as `INSTALLED`. |
| **Software Manager** | PRoot | **PASS** | Parsed package inventory via `apk info -v` and rendered categories. |
| **Termux-X11 Display Server** | Host Process | **PASS** | `com.termux.x11.Loader` started on `:0` with `libXlorie.so`, listening for X11 connections. |
| **XFCE Desktop Environment** | PRoot | **PASS** | `start_gui.sh` launched `dbus-daemon`, `xfconfd`, `xfwm4`, `xfce4-session`, and `startxfce4` under user `flux`. Desktop UI rendered in Termux-X11 client. |
| **Pure Sandbox Enforcement** | PRoot | **PASS** | 100% unprivileged execution under UID `u0_a322`. No `su`, no root, no chroot kernel requirements. |

---

## 3. What To Do Next

1. **Synthetic Kernel Release Injection in PRoot**:
   - Add `--kernel-release=\Linux\localhost\6.1.0-28-arm64\6.1.0\aarch64\localdomain\-1\` to `ProotFastCommandBuilder` flags to emulate standard distribution kernel releases for utilities like `fastfetch` and `uname`.

2. **Termux-X11 Activity Navigation Polish**:
   - Refine automatic activity switching when starting/stopping the graphical desktop to ensure smooth foreground transitions between `MainActivity` and `com.termux.x11.MainActivity`.

3. **Background Lifecycle & Power Management**:
   - Verify Android foreground service (FGS) persistence during long-running background builds and compiler jobs in PRoot.
