# Local ARM64 Alpaquita Rootfs Build Plan

## Objective
Build and verify the reproducible Alpaquita glibc AArch64 prebuilt rootfs artifact (`alpaquita_rootfs.tar.zst`) locally using Docker on the host machine (AMD Ryzen 5 5600H, 12 threads) instead of CI.

---

## 1. What We Did So Far

### Base Rootfs & Bootstrapping
- Pinned official Alpaquita glibc AArch64 minirootfs SHA256 (`ec5c81cb32bd6454a778aaec449ef269806d7f17b52432abe00280684136af50`) in `tools/rootfs/versions.env`.
- Unified single source of truth chroot path to `/data/local/tmp/chrootAlpine` across `setup_alpaquita_chroot.sh` and `OnboardingActivity.kt`.
- Implemented memory-safe chunked rootfs deployment (`1MB` buffer copy) in `OnboardingActivity.kt` to eliminate heap OOM.
- Verified preflight shell syntax and prebuilt paths via `test_build_pipeline.sh` and `test_prebuilt_paths.sh`.

### Source-Compiled Components & Fixes
- **Zsh 5.9**: Fixed GCC 15 / ncurses `boolcodes` const mismatch with `tools/rootfs/patches/zsh-5.9-modern-ncurses.patch`.
- **Fastfetch 2.38.0**: Verified CMake build and packaging into GUI bundle.
- **TigerVNC 1.14.1 & Xorg 21.1.14 (`Xvnc`)**:
  - Resolved missing PAM headers (`linux-pam-dev`).
  - Corrected directory overlaying by preserving `unix/xserver/hw/vnc`.
  - Passed `TIGERVNC_BUILDDIR` to resolve `config.h` and static helper libraries.
  - Linked modern binutils direct DSOs (`-lhogweed -lnettle -lgmp -lgnutls -lpam -ljpeg -lz`) into `XVNC_SYS_LIBS`.
- **XFCE 4.20 Tooling & Libraries**:
  - Integrated `xfce4-dev-tools 4.20.0` to provide `xdt-gen-visibility`.
  - Added `libxfce4windowing 4.20.0` dependency.
  - Added `libwnck 43.3` (GNOME) and `yaml-dev` for window management and desktop icons.
  - Set `--libdir=/usr/lib` to prevent 64-bit library installation split (`/usr/lib64` vs `/usr/lib`).

---

## 2. Root Cause of Current Failure
- `xfce4-session` requires the `iceauth` executable at configure time.
- Alpaquita package repositories do not package `iceauth`.
- `xfce4-session` fails with `configure: error: iceauth missing, please check your X11 installation`.

---

## 3. Local Execution Plan

### Step 1: Add `iceauth` to GUI Builder
- Update `tools/rootfs/versions.env`:
  - `ICEAUTH_VERSION=1.0.11`
  - `ICEAUTH_SHA256=9d633cf0d4d1d98e3ef02d18660372958b60a67016e8a72cd04093a8d8f8d4e9`
- Update `tools/rootfs/build_gui_bundle.sh`:
  - Add `iceauth` source compilation (`./configure --prefix=/usr --libdir=/usr/lib && make install`) before building `xfce4-session`.

### Step 2: Fix Local Cache Permissions
- Ensure files generated in Docker volume mounts have broad read/write permissions (`chmod -R a+rX "$WORK"`) to prevent build interruptions.

### Step 3: Trigger Local Build
- Run the engineering build script directly with 10 parallel jobs:
  ```bash
  JOBS=10 ./tools/rootfs/w10_fast_build.sh --skip-tests --skip-android
  ```
- Estimated time: **~30–40 minutes** on Ryzen 5 5600H.

### Step 4: Verification Contract
- Ensure `tools/rootfs/out/alpaquita_rootfs.tar.zst` and `alpaquita_rootfs.tar.zst.sha256` exist.
- Verify contents contain `/usr/bin/Xvnc`, `/usr/bin/startxfce4`, `/usr/bin/zsh`, and required desktop services.
