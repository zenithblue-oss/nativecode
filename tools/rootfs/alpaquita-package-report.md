# Alpaquita glibc/AArch64 Package Availability Report

**Repository Source:** BellSoft Alpaquita Linux glibc `stream` & `25` (core + universe)  
**Architecture:** `aarch64`  
**Audit Date:** 2026-09-04 16:05:00 UTC  
**Total Distro Packages Found:** 5016 (stream), 7835 (25)

---

## Executive Summary & Plan Gate Finding

According to **Section 6 & 18/37 of the Alpaquita Worker Plan**:
> *"Before replacing Alpine package manifests, audit the exact Alpaquita glibc/AArch64 repositories... If any mandatory package is unavailable, stop and report the exact gap before implementing the build migration."*

### Key Package Availability Findings:

1. **Base & Build Tooling (AVAILABLE):**
   - Shell/System: `bash`, `sudo`, `shadow`, `coreutils`, `libstdc++`, `curl`, `wget`, `tar`, `xz`, `unzip`, `tzdata`, `glibc-locales` (renamed from `locales`), `ca-certificates`.
   - Toolchains: `build-base`, `gcc`, `g++`, `cmake`, `samurai` (provides `ninja`), `clang22` (renamed from `clang`), `rust`, `cargo`, `go`, `python3`, `nodejs`, `npm`.
   - Java: `liberica21-jdk` (BellSoft's native LTS JDK 21 package, replacing Alpine `openjdk21-jdk`).
   - GPU / Acceleration: `mesa`, `mesa-dri-gallium`, `mesa-gl`, `mesa-vulkan-freedreno`, `vulkan-loader`, `glycin-image-rs`, `glycin-svg`, `gdk-pixbuf`.

2. **CRITICAL GAPS / MISSING UPSTREAM IN ALPAQUITA:**
   - **XFCE4 Desktop Stack (MISSING in Alpaquita repo):** `xfce4`, `xfce4-session`, `xfce4-settings`, `xfce4-panel`, `xfce4-terminal`, `xfce4-screensaver`, `xfce4-screenshooter`, `xfdesktop`, `xfwm4`, `thunar` are **NOT packaged** in BellSoft Alpaquita glibc repositories (Alpaquita is a server/container/cloud-native Linux distro focused on Java/microservices, and does not carry desktop environments).
   - **VNC Server (MISSING):** `tigervnc` is not in core or universe.
   - **Zsh Shell (MISSING):** Only completion files exist (`*-zsh-completion`), but `zsh` package itself is missing from the glibc repository.
   - **GitHub CLI (MISSING):** `github-cli` (`gh`) is not in Alpaquita apk repositories (can be installed via official upstream glibc release binary `gh` tarball).
   - **Android Tools (MISSING in apk):** `adb` and `fastboot` are not in Alpaquita apk repositories (can be provided via Google's prebuilt Android command-line tools / platform-tools staged in `/opt/android-sdk/platform-tools`).
   - **Fonts:** `font-dejavu` exists, but `font-jetbrains-mono-nerd` does not exist as an apk package (can be staged via tarball/zip as planned).

---

## Detailed Classification Table

| Target Package | Classification | Resolved Name / Strategy | Notes |
| :--- | :--- | :--- | :--- |
| `bash` | **SAME NAME** | `bash` | Available |
| `sudo` | **SAME NAME** | `sudo` | Available |
| `shadow` | **SAME NAME** | `shadow` | Available |
| `ca-certificates` | **SAME NAME** | `ca-certificates` | Available |
| `curl` | **SAME NAME** | `curl` | Available |
| `wget` | **SAME NAME** | `wget` | Available |
| `unzip` | **SAME NAME** | `unzip` | Available |
| `tar` | **SAME NAME** | `tar` | Available |
| `xz` | **SAME NAME** | `xz` | Available |
| `tzdata` | **SAME NAME** | `tzdata` | Available |
| `locales` | **RENAMED** | `glibc-locales` | glibc locales package |
| `dbus` | **SAME NAME** | `dbus` | Available |
| `dbus-x11` | **SAME NAME** | `dbus-x11` | Available |
| `coreutils` | **SAME NAME** | `coreutils` | Available |
| `libstdc++` | **SAME NAME** | `libstdc++` | Available |
| `mesa` | **SAME NAME** | `mesa` | Available |
| `mesa-dri-gallium` | **SAME NAME** | `mesa-dri-gallium` | Available |
| `mesa-gl` | **SAME NAME** | `mesa-gl` | Available |
| `mesa-vulkan-freedreno` | **SAME NAME** | `mesa-vulkan-freedreno` | Available |
| `vulkan-loader` | **SAME NAME** | `vulkan-loader` | Available |
| `glycin-image-rs` | **SAME NAME** | `glycin-image-rs` | Available |
| `glycin-svg` | **SAME NAME** | `glycin-svg` | Available |
| `gdk-pixbuf` | **SAME NAME** | `gdk-pixbuf` | Available |
| `git` | **SAME NAME** | `git` | Available |
| `python3` | **SAME NAME** | `python3` | Available |
| `nodejs` | **SAME NAME** | `nodejs` | Available |
| `npm` | **SAME NAME** | `npm` | Available |
| `build-base` | **SAME NAME** | `build-base` | Available |
| `gcc` | **SAME NAME** | `gcc` | Available |
| `g++` | **SAME NAME** | `g++` | Available |
| `cmake` | **SAME NAME** | `cmake` | Available |
| `ninja` | **RENAMED** | `samu` | `samu` provides `cmd:ninja` in Alpaquita |
| `clang` | **RENAMED** | `clang22` | Clang 22 LLVM suite |
| `rust` | **SAME NAME** | `rust` | Available |
| `cargo` | **SAME NAME** | `cargo` | Available |
| `go` | **SAME NAME** | `go` | Available |
| `openjdk21-jdk` | **RENAMED** | `liberica21-jdk` | BellSoft Liberica JDK 21 LTS |
| `font-dejavu` | **SAME NAME** | `font-dejavu` | Available |
| `fontconfig` | **SAME NAME** | `fontconfig` | Available |
| `fastfetch` | **MISSING** | `none` | Not packaged |
| `github-cli` | **EXTERNAL ARTIFACT** | GitHub Release tarball | Official glibc arm64 release `gh` binary |
| `adb`, `fastboot` | **EXTERNAL ARTIFACT** | Android platform-tools | Staged in `/opt/android-sdk/platform-tools` |
| `font-jetbrains-mono-nerd` | **EXTERNAL ARTIFACT** | TTF zip / archive | Extracted into `/usr/share/fonts` |
| `zsh` | **MISSING / EXTERNAL** | `none` | Alpaquita only provides completion packages, no zsh binary |
| `xfce4`, `xfwm4`, `thunar`, etc. | **MISSING** | `none` | Alpaquita repositories do not provide desktop packages |
| `tigervnc` | **MISSING** | `none` | No VNC server in Alpaquita repositories |

---

## Action Plan Recommendations:

As stated in worker plan §18:
> *"Do not remove Alpine support until the Alpaquita package and runtime compatibility gates in this plan pass."*
> *"If any mandatory package is unavailable, stop and report the exact gap before implementing the build migration."*

Because Alpaquita is a specialized, headless enterprise Linux designed for cloud containers (no XFCE/GUI desktop packages in its repositories), we have two clear engineering options:
1. **Headless / CLI Developer RootFS for Alpaquita:** Build Alpaquita glibc/AArch64 specifically for CLI, AI tools, Node, Java 21, and Android builds (where glibc offers huge compatibility gains for Node and native binaries), without XFCE.
2. **Prebuilt External Binaries for GUI:** If XFCE/VNC and Zsh are strictly required on Alpaquita, their binaries and libraries would have to be compiled or staged from an external glibc repository (such as Debian ARM64 or Arch Linux ARM), or we keep Alpine 3.24 as the GUI desktop rootfs while maintaining Alpaquita for glibc development environments.
