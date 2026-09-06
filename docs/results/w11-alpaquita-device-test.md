# W11 Alpaquita Rootfs Physical Device Test Report

## Executive Summary

- **Status**: **PASS (W11-A PRoot & W11-B Chroot)**
- **Target Hardware**: Xiaomi Poco X6 Pro (`2311DRK48I`), MediaTek Dimensity 8300 Ultra (`MT6897`), 8 cores @ 3.35 GHz, 8 GB RAM, Android 16 (`SDK 36`), KernelSU `uid=0(root)`.
- **Target App**: NativeCode (`com.zenithblue.nativecode`), App UID `u0_a322` (`10322`).
- **Artifact Tested**: `tools/rootfs/out/alpaquita_rootfs.tar.zst`
  - **Size**: 1,346,323,800 bytes (~1.25 GiB)
  - **SHA256**: `70cf5b9aaa02eff3105876e131c16e68f11f8403ec5ed0bb96c79b688c198257`
  - **Uncompressed On-Device Footprint**: 3.9 GiB

---

## 1. Test Matrix Results

| Test Section | Isolation | Stage | Outcome | Details / Timing |
|--------------|-----------|-------|---------|------------------|
| Checksum Verification | Host / ADB | Preflight | **PASS** | On-device `sha256sum` matches expected hash |
| Fresh Installation | PRoot | Base | **PASS** | `flux_install.sh` extracted in **52s** |
| Release Sentinel | PRoot | Release | **PASS** | `/etc/nativecode/rootfs-release` verified (`DISTRO=alpaquita`, `LIBC=glibc`, `ARCH=aarch64`) |
| Phase E | PRoot | Guest Setup | **PASS** | `setup_alpaquita_family.sh`: fast-path prebuilt repair (<1s) |
| Phase F | PRoot | GPU Accel | **PASS** | `setup_hw_accel_debian.sh`: VirGL configured (<1s) |
| Phase GUI | PRoot | Desktop | **PASS** | `setup_xfce4.sh`: prebuilt configuration (<1s) |
| Phase G | PRoot | Polish | **PASS** | `setup_customization_alpine.sh`: Oh-My-Zsh & dark theme (<1s) |
| Phase H | PRoot | AI CLIs | **PASS** | `setup_cli_tools.sh`: prebuilt verification in **14s** |
| Binary Execution (27/27) | PRoot | Flux User | **PASS** | Every CLI, compiler, and GUI binary executed successfully |
| Desktop Session Launch | PRoot | Flux User | **PASS** | `Xvnc :1`, `xfce4-session`, `glycin-image-rs` running concurrently |
| Container Re-run | PRoot | Flux User | **PASS** | Warm login & offline execution verified |
| Host File Ownership | PRoot | Host FS | **PASS** | **0 unowned files**; 100% owned by app UID `u0_a322` |
| Host Tool Probes | Root Shell | Preflight | **PASS** | Root shell exposes `sha256sum` and `zstd v1.5.7` |
| Fresh Chroot Extract | Chroot | Base | **PASS** | `setup_alpaquita_chroot.sh` extracted in **30s** |
| Release Sentinel | Chroot | Release | **PASS** | `/etc/nativecode/rootfs-release` matches SSOT |
| Chroot Onboarding (E-H) | Chroot | Root/Flux | **PASS** | Phases E, F, GUI, G, H completed in **6s** |
| Binary Execution (27/27) | Chroot | Flux User | **PASS** | All 27 binaries executed under native Linux chroot |
| Guest File Ownership | Chroot | Guest FS | **PASS** | Real UID `1000:1000` for `/home/flux` |
| Clean Teardown | Chroot | Host FS | **PASS** | Reverse lazy unmount of `/dev`, `/proc`, `/sys`, `/run` |

---

## 2. In-Guest Binary Execution Evidence (27/27 Validated)

Both PRoot and Chroot executed the complete binary suite under non-root user `flux`:

```text
=== MATRIX TEST OUTPUT ===
PASS | Arch                 | aarch64
PASS | Glibc                | ld.so (Alpaquita Linux) stable release version 2.43.
PASS | Zsh                  | zsh 5.9 (aarch64-unknown-linux-gnu)
PASS | Fastfetch            | fastfetch 2.38.0 (aarch64)
PASS | Xvnc                 | Xvnc TigerVNC 1.14.1 - built Sep  6 2026 04:17:13
PASS | Node                 | v24.18.1
PASS | Npm                  | 12.0.2
PASS | GitHub CLI           | gh version 2.67.0 (2025-02-11)
PASS | GCC                  | gcc (Alpaquita 15.2.0) 15.2.0
PASS | G++                  | g++ (Alpaquita 15.2.0) 15.2.0
PASS | CMake                | cmake version 4.3.4
PASS | Ninja                | 1.9.0
PASS | Clang                | Alpaquita clang version 22.1.8
PASS | Rustc                | rustc 1.97.0 (2d8144b78 2026-07-07) (Alpaquita Linux Rust 1.97.0-r0)
PASS | Cargo                | cargo 1.97.0 (c980f4866 2026-06-30) (Alpaquita Linux Rust 1.97.0-r0)
PASS | Go                   | go version go1.26.7 linux/arm64
PASS | Java                 | openjdk version "21.0.12.1" 2026-08-18 LTS
PASS | Javac                | javac 21.0.12.1
PASS | OpenCode             | 1.18.27
PASS | Codex                | codex-cli 0.153.2
PASS | Qwen                 | 0.23.0
PASS | AGY                  | 1.1.27
PASS | Claude               | 2.1.263 (Claude Code)
PASS | Grok                 | grok 1.0.13 (5e9a58528b76)
PASS | Kiro-CLI (Stripped)  | kiro-cli 2.21.1
PASS | Thunar               | thunar 4.20.0 (Xfce 4.20)
PASS | Xfwm4                | xfwm4 version 4.20.0 (Xfce 4.20)
PASS | Xfce4-session        | xfce4-session 4.20.0 (Xfce 4.20)
PASS | Xfce4-terminal       | xfce4-terminal 1.1.4 (Xfce 4.20)
PASS | Xfce4-panel          | xfce4-panel 4.20.0 (Xfce 4.20)
```

### Key Verification Notes:
- **Stripped Kiro Binaries**: `kiro-cli 2.21.1` executed with no issues, proving that symbol stripping for size compliance did not break the binary.
- **OpenCode Native Runtime**: `opencode 1.18.27` executed natively using its bundled Linux ARM64 binary.
- **Xvnc Server**: TigerVNC 1.14.1 successfully initialized the display socket, compiled XKB keymaps with `xkbcomp`, and listened on port 5901.
- **XFCE 4.20 Desktop**: Window manager (`xfwm4`), session manager (`xfce4-session`), file manager (`thunar`), terminal (`xfce4-terminal`), and panel (`xfce4-panel`) all loaded with zero missing shared libraries.

---

## 3. Discovered Defects & Fixes Applied

1. **Adjacent SHA256 Verification in `flux_install.sh`**:
   - *Issue*: `flux_install.sh` skipped verification if `ROOTFS_SHA256` was not pre-set in the environment.
   - *Fix*: Added `load_adjacent_sha()` to read `$ROOTFS_ARCHIVE.sha256` and fail-closed when installing Alpaquita.
   - *Test*: Added missing-SHA, wrong-SHA, and valid-SHA assertions to `tools/rootfs/test_build_pipeline.sh`.

2. **Missing GUI Shared Libraries in Base Rootfs**:
   - *Issue*: Runtime packages `libice`, `libsm`, `libxcvt`, `libdisplay-info`, `vte3`, `yaml`, `xkbcomp`, `xrdb`, `libxkbfile`, and `xkeyboard-config` were present in build dependencies but missing from `packages-alpaquita-base.txt`.
   - *Fix*: Added these packages to `tools/rootfs/packages-alpaquita-base.txt` and added assertions in `tools/rootfs/verify_rootfs.sh`.

3. **OpenCode Postinstall Symlink**:
   - *Issue*: `npm install -g opencode-ai` with `--ignore-scripts` did not link `node_modules/opencode-ai/node_modules/opencode-linux-arm64/bin/opencode` to `bin/opencode.exe`.
   - *Fix*: Added self-healing hook in `setup_cli_tools.sh` and explicit `postinstall.mjs` run in `build_alpaquita_rootfs.sh`.

4. **PATH Resolution in `setup_alpaquita_family.sh`**:
   - *Issue*: Minimal shells entering chroot did not include `/usr/local/bin` in PATH, causing `command -v gh` check to fail.
   - *Fix*: Prepended standard `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin` to PATH in `setup_alpaquita_family.sh`.

5. **Device Runner Automation**:
   - *Artifact*: Created `tools/rootfs/w11_device_smoke.sh` and modular scripts in `tools/rootfs/device_runners/` to test both PRoot and Chroot without quoting or shell-escape defects.

---

## 4. Conclusion & Next Steps

The Alpaquita glibc AArch64 rootfs artifact is **fully functional on real Android hardware**.
- PRoot extraction and runtime configuration complete in < 1 minute.
- Physical filesystem ownership is 100% app-contained (no recursive chown).
- Size on disk is 3.9 GiB uncompressed, within mobile storage budgets.

**Ready for Play Asset Delivery (PAD) implementation**:
1. Asset Pack 1: `nativecode-runtime` (~1.25 GiB, below the 1.5 GB individual asset pack limit).
2. Asset Pack 2 (On-Demand): `nativecode-android-dev` (Android SDK 36, NDK r29, Kotlin, Gradle).
