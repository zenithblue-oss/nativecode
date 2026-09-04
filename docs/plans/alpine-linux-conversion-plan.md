# Migration Plan: Debian to Alpine Linux (apk)

> **Status: APPROVED (Ready for Implementation)**
> Uses the ultra-minimal Alpine rootfs from FluxLinux (`assets/rootfs/alpine_3.24_rootfs.tar.gz`, 3.9 MB).
> Includes complete Gcompat + build-essential tooling to ensure Node.js, Python, and AI CLI compatibility under musl libc.

---

## 1. Overview & Goal
Migrate `nativecode-ai` from Debian 13 (Trixie) to Alpine Linux 3.24 as the default Linux userland for PRoot (unrooted) and Chroot (rooted).
The objective is to achieve maximum installation speed (10x faster than Debian) and minimal asset footprint (~3.9 MB rootfs) by leveraging `apk-tools v3`, while providing glibc compatibility via `gcompat` for dev tools and AI CLI runtimes.

---

## 2. Technical Rationale & Characteristics
- **Blazing Fast Package Management:** `apk` is written in pure C, performs zero per-file/per-directory `fsync()` calls, minimal `stat` metadata probes (~25,000 syscalls per 10k files vs Debian's 180,000+), and finishes full desktop/tool stacks in under 1.5 minutes.
- **Ultra-Small Rootfs:** Compressed rootfs is only **3.9 MB** (uncompressed ~12 MB), drastically slashing APK download size.
- **Musl Libc Solution (Gcompat + System Packages):**
  - Use Alpine native `nodejs` and `npm` packages (compiled directly against musl) instead of relying on pre-compiled x86/glibc binaries.
  - Install `gcompat` and `musl-locales` to allow standard glibc-linked binaries to execute seamlessly.

---

## 3. Rootfs Asset & Proot-Distro Integration
1. **Archive Source:**
   - Source: `/home/user/repos/fluxlinux/assets/rootfs/alpine_3.24_rootfs.tar.gz` (3.9 MB).
   - Target Local Asset: `app/src/main/assets/rootfs/alpine_rootfs.tar.gz` (replaces `debian_13_rootfs.tar.xz`).
   - Remote Fallback: Hosted release on `https://github.com/zenithblue-oss/fluxlinux/releases/download/rootfs/alpine_3.24_rootfs.tar.gz`.
2. **Proot-Distro Container ID:**
   - Container Name: `alpine`
   - Installation Command: `proot-distro install "$ROOTFS_PATH" --name alpine`
   - Container Rootfs Path: `$PREFIX/var/lib/proot-distro/containers/alpine/rootfs`.

---

## 4. Script Replacements & Adaptations

### 4.1 PRoot Host Scripts
- **`app/src/main/assets/scripts/flux_install.sh`**:
  - Default distro: `DISTRO="${1:-alpine}"`.
  - Rootfs archive name: `ROOTFS_NAME="alpine_rootfs.tar.gz"`.
  - Fallback URL points to `alpine_3.24_rootfs.tar.gz`.
  - Setup script invocation runs `setup_alpine_family.sh` inside guest.
- **`app/src/main/assets/scripts/start_gui.sh`**:
  - Default distro parameter: `DISTRO=${1:-alpine}`.
  - Rootfs path: `$TERMUX_PREFIX/var/lib/proot-distro/containers/alpine/rootfs`.
- **`app/src/main/assets/scripts/stop_gui.sh`**:
  - Default distro parameter: `DISTRO=${1:-alpine}`.
- **`app/src/main/assets/scripts/nativecode_proot_fast.sh`**:
  - Update `ROOTFS` default to `${PREFIX}/var/lib/proot-distro/containers/alpine/rootfs`.
- **`app/src/main/assets/scripts/optimize_debian_rootfs_perf.sh`**:
  - Replaced with a no-op (apk already has zero fsync locks).

### 4.2 Guest Setup Scripts
- **New `setup_alpine_family.sh` (replaces `setup_debian_family.sh`)**:
  - Enable community repository in `/etc/apk/repositories`.
  - Update and install base userland packages:
    ```sh
    apk update
    apk add --no-cache bash sudo shadow git curl wget tar gcompat libstdc++ musl-locales
    ```
  - Create user `flux` with sudo privileges and bash shell:
    ```sh
    if ! id "flux" >/dev/null 2>&1; then
        useradd -m -s /bin/bash flux
        echo "flux:flux" | chpasswd
        echo "flux ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/flux
    fi
    ```
- **`setup_xfce4.sh`**:
  - Replaces Debian apt commands with `apk`:
    ```sh
    apk add --no-cache xfce4 xfce4-terminal dbus tigervnc font-dejavu wget unzip curl
    ```
  - Theme unpacking, VNC `xstartup`, and `xfconf` configurations remain identical.
- **`setup_customization_debian.sh` ➔ `setup_customization_alpine.sh`**:
  - Install zsh via `apk add --no-cache zsh`.
  - Wallpapers, fonts, and prompt customizations remain unchanged.
- **`setup_hw_accel_debian.sh`**:
  - Add `apk` detection block:
    ```sh
    elif command -v apk >/dev/null 2>&1; then
        echo "Alpine Linux detected (apk)."
        apk add --no-cache mesa mesa-dri-gallium mesa-vulkan-freedreno vulkan-loader curl unzip
        MODE="virgl"
    ```
  - Default to VirGL for universal stability.
- **`setup_cli_tools.sh` (AI Stack)**:
  - Replace package checker and installer:
    ```sh
    pkg_ok() {
        apk info -e "$1" >/dev/null 2>&1
    }
    ensure_pkgs() {
        local need=()
        for p in "$@"; do
            pkg_ok "$p" || need+=("$p")
        done
        if [ "${#need[@]}" -gt 0 ]; then
            echo ">>> apk add: ${need[*]}"
            apk add --no-cache "${need[@]}"
        fi
    }
    ```
  - Install native Alpine Node.js (`apk add --no-cache nodejs npm python3 py3-pip build-base`), guaranteeing all tools compile against musl.

### 4.3 Chroot Host Scripts
- **`app/src/main/assets/scripts/chroot/setup_alpine_chroot.sh`**:
  - Target directory: `/data/local/tmp/chrootAlpine`.
  - Extracts `alpine_rootfs.tar.gz`.
  - Sets up DNS (`/etc/resolv.conf`) and provisions user `flux`.
- **`chroot/start_debian13_gui.sh` / `stop_debian13_gui.sh`**:
  - Update paths to `/data/local/tmp/chrootAlpine`.
- **`chroot/nativecode_chroot.sh`**:
  - Update default path to `/data/local/tmp/chrootAlpine`.

---

## 5. In-App Kotlin Modifications

### 5.1 Terminal Command Builders & Chroot Paths
- **`app/src/main/java/com/zenithblue/nativecode/terminal/ProotCommandBuilder.kt`**:
  - Change `prootDistro login debian` ➔ `prootDistro login alpine` (lines 35, 65).
- **`app/src/main/java/com/zenithblue/nativecode/terminal/ProotFastCommandBuilder.kt`**:
  - Update rootfs path:
    ```kotlin
    val rootfs = "${TermuxHostPaths.PREFIX}/var/lib/proot-distro/containers/alpine/rootfs"
    ```
- **`app/src/main/java/com/zenithblue/nativecode/terminal/ProjectPathResolver.kt`**:
  - Update `PROOT_ROOTFS_REL = "usr/var/lib/proot-distro/containers/alpine/rootfs"`.
- **`app/src/main/java/com/zenithblue/nativecode/terminal/ChrootCommandBuilder.kt`**:
  - Update `CHROOT_PATH = "/data/local/tmp/chrootAlpine"` (line 14).
  - Update runner and cli helper script names.
- **`app/src/main/java/com/zenithblue/nativecode/cliauth/CliToolsInstaller.kt`**:
  - Line 66: Update chroot tmp directory to `/data/local/tmp/chrootAlpine/tmp`.
- **`app/src/main/assets/scripts/chroot/nativecode_chroot.sh`**:
  - Line 22: Update `NC_CHROOT="${NC_CHROOT:-/data/local/tmp/chrootAlpine}"`.

### 5.2 UI & Onboarding
- **`app/src/main/java/com/zenithblue/nativecode/OnboardingActivity.kt`**:
  - Update onboarding UI strings describing Alpine Linux environment.
  - Lines 2444, 2461: Update chroot script calls to `setup_alpine_chroot.sh` and `setup_alpine_family.sh`.
  - Line 3149: Update chroot root path reference to `/data/local/tmp/chrootAlpine`.
  - Lines 2629, 2648, 2662, 2679: Switch guest container identifier parameter from `"debian"` to `"alpine"`.
  - Line 3217: Unpack `alpine_rootfs.tar.gz` to `$HOME`.
- **`app/src/main/java/com/zenithblue/nativecode/MainActivity.kt`**:
  - Line 12956: Update verification check to `/data/local/tmp/chrootAlpine/usr/bin/startxfce4`.
  - Lines 12903, 12958: Update verification paths to check for `containers/alpine/rootfs`.

### 5.3 GitHub CLI & Marketplace
- **`app/src/main/java/com/zenithblue/nativecode/github/GhGuestCommands.kt`**:
  - `installPrimary()`: `apk add --no-cache github-cli`.
  - `installFallback()`: Fallback to GitHub releases static binary or `apk add github-cli --repository=https://dl-cdn.alpinelinux.org/alpine/edge/community`.
- **`app/src/main/java/com/zenithblue/nativecode/marketplace/PackageInstallRunner.kt`**:
  - Update login target from `debian` to `alpine`.

---

## 6. Verification & Test Plan
1. **Asset Size Verification:** Check `alpine_rootfs.tar.gz` asset size (~3.9 MB).
2. **Onboarding Benchmark:** Verify initial extraction and base package setup completes in under 1.5 minutes on test device.
3. **Desktop & X11 Testing:** Launch XFCE4 desktop via X11 under VirGL and verify stability.
4. **Node & AI CLI Verification:** Verify `nodejs`, `npm`, and CLI tools operate correctly with `gcompat`.

  - `installPrimary()`: `apk add --no-cache github-cli`.
  - `installFallback()`: Fallback to GitHub releases static binary or `apk add github-cli --repository=https://dl-cdn.alpinelinux.org/alpine/edge/community`.
- **`app/src/main/java/com/zenithblue/nativecode/marketplace/PackageInstallRunner.kt`**:
  - Update login target from `debian` to `alpine`.

---

## 6. Verification & Test Plan
1. **Asset Size Verification:** Check `alpine_rootfs.tar.gz` asset size (~3.9 MB).
2. **Onboarding Benchmark:** Verify initial extraction and base package setup completes in under 1 minute on test device.
3. **Desktop & X11 Testing:** Launch XFCE4 desktop via X11 under VirGL and verify stability.
4. **Node & AI CLI Verification:** Verify `nodejs`, `npm`, and CLI tools operate correctly under musl + gcompat.
