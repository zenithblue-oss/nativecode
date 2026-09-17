# Phosh-Lite & Universal GPU Migration Handoff

**Date**: 2026-09-17  
**Branch**: `feature/prebuilt-alpine-rootfs-w1-w6`  
**Latest Baseline Git Commit**: `0f6e8bf`  
**Target Devices**:
1. **OnePlus 13R (`d30a1726` / `192.168.1.38:44029`)**: Qualcomm Snapdragon 8 Gen 3 (`SM8650`), Adreno 750, Kernel 6.1, `/dev/kgsl-3d0` (Verified locked 60 FPS, 1.24W idle)
2. **Poco X6 Pro (`Y5WWBMJVOZSK4HU8`)**: MediaTek Dimensity 8300 Ultra (`MT6897`), Mali-G615 MC6, Kernel 6.1, `/dev/mali0` (Verified edge-to-edge portrait, PanVK GPU probed)

---

## 1. Documentation Map & Index

The following exhaustive records have been produced and are tracked in the repository:

| Document | Path | Contents & Role |
|---|---|---|
| **Completed Architecture** | [`docs/phosh-migration-completed-details.md`](phosh-migration-completed-details.md) | Device bridge architecture, all built binaries/shims (`libncshims.so`), X11 presentation patches, visual verification evidence. |
| **Actionable Roadmap** | [`docs/phosh-migration-next-steps-and-todos.md`](phosh-migration-next-steps-and-todos.md) | Next steps checklist, touch event handling, DPI scaling, service pruning, and testing matrix. |
| **Packages & Settings** | [`docs/phosh-lite-packages-and-settings.md`](phosh-lite-packages-and-settings.md) | Complete file inventory of `packages/phosh-lite/`, runtime Alpine dependencies, GSettings profile, GPU matrix, and the resolution/landscape fix. |
| **System Baseline** | [`docs/baseline/oneplus-13r-baseline.md`](baseline/oneplus-13r-baseline.md) | Initial Phase A metrics: 243 packages, 105,459 files, 244 MB PSS, disk usage, and process tree. |
| **Ported Artifacts** | [`packages/phosh-lite/`](../packages/phosh-lite/) | Tarball bundle (`phosh-lite-bundle.tar.gz`), Adwaita icon theme (`adwaita-icons.tar.gz`), and automated deployment installer (`install.sh`). |

---

## 2. Summary of Completed Milestones (Phases B1 – E)

### A. Compositor & Shell Runtime Build & Deployment
1. **`wlroots 0.17.4`**:
   - Patched `backend/x11/backend.c`: Added graceful fallback when DRI3 DRM node queries fail; enabled SHM swapchains; fixed `xcb_intern_atom` `only_if_exists = false` to eliminate `BadAtom` window manager failures.
   - Patched `render/egl.c`: Added fallback to `EGL_PLATFORM_SURFACELESS_MESA` (`0x31DD`) and safeguarded `eglQueryDeviceStringEXT` against NULL pointers when DRM extensions are absent.
   - Patched `render/gles2/renderer.c`: Enabled SHM buffer capabilities and added `XRGB8888`/`ARGB8888` fallback formats.
   - Patched `backend/x11/output.c`: **Critical Presentation Engine & Performance Fixes**:
     - Fallback from SHM pixmap to `output->win` via `xcb_copy_area`.
     - Replaced 1000 FPS CPU-spinning loop with a locked 16ms (60 FPS) event loop timer (`output->frame_timer` via `wl_event_source_timer_update`), dropping idle power from **~7 W to ~1.2 W**.
     - Replaced hardcoded `1024x768` fallback in `wlr_x11_output_create` with dynamic query of `x11->screen->width_in_pixels` and `height_in_pixels`.
   - Patched `types/wlr_layer_shell_v1.c`: Clamped zero-size layer surfaces to output dimensions, preventing GTK Builder protocol aborts.
2. **`phoc v0.43.0` & `phosh v0.43.0`**:
   - Built natively inside Alpaquita glibc on Snapdragon 8 Gen 3.
   - Universal shims library `/usr/local/lib64/libncshims.so` provides concrete implementations for `GcrSecureEntryBuffer`, `GnomeWallClock` (1s live tick), `GcrPrompt`, `BluetoothClient`, `libfeedback`, `libnm`, `mm-glib`, `callaudiod`, and `upower`.
   - `home.c` & `home.ui`:
     - Increased `PHOSH_HOME_BAR_HEIGHT` from 15px to 40px to ensure the touch handle remains fully accessible above the Android gesture navigation pill.
     - Unconditionally accepted touch release events (`on_home_released`) regardless of single/multi-pointer gesture flags.
     - Connected overview trigger watcher `/tmp/phosh_trigger`.
   - `drag-surface.c`:
     - Fixed upstream bug where `priv->margin_folded` was assigned twice instead of updating `priv->margin_unfolded`.
3. **Application Launcher Grid & Desktop Integration**:
   - Patched `/usr/share/applications/*.desktop` to include `Phosh;GNOME;` in `OnlyShowIn` so system utilities (`Thunar`, `Xfce Terminal`, `Bulk Rename`) appear in the grid.
   - Cleared mobile-only adaptive app filtering (`gsettings set sm.puri.phosh app-filter-mode '[]'`).
   - Cleared dock favorites (`gsettings set sm.puri.phosh favorites '[]'`) to show clean titles in the launcher grid.
   - Installed complete `Adwaita` icon theme into `/usr/share/icons/Adwaita` and compiled schemas.

### B. Device Qualification Status
1. **OnePlus 13R (Adreno 750 / Snapdragon 8 Gen 3)**:
   - **Status**: **PASS (Locked 60 FPS, Edge-to-Edge, Touch & Gestures Qualified)**.
   - UI scaling set to `scale = 2.5` in `phoc.ini` (`1264x2780` physical resolution).
   - Touch gestures verified: top shade pull-down, lockscreen PIN keypad, bottom home bar tap/swipe, app grid launch (`Terminal`), and carousel window switching.
   - Idle power verified at ~1.24 W.
2. **Poco X6 Pro (Mali-G615 MC6 / MediaTek Dimensity 8300 Ultra)**:
   - **Status**: **PASS (Edge-to-Edge Portrait, Full App Grid, Touch & App Launch Qualified)**.
   - Successfully deployed portable bundle `packages/phosh-lite/` and installed runtime dependencies.
   - Auto-detected native portrait display geometry via patched `libwlroots.so.12` without letterboxing.
   - Termux:X11 preferences configured: `displayResolutionMode: native`, `fullscreen: true`, `hideCutout: true`, `touchMode: 3`.
   - PanVK probed on hardware GPU (`vendorID: 0x13b5`, `deviceID: 0xa8671030`, Vulkan 1.4.352).

---

## 3. What Is Left (Actionable Tasks for Next Agent)

The migration is now ready to move from mutable on-device testing to canonical rootfs repackaging and clean APK qualification.

### Task 1: Phase C — Service Pruning & Background Daemon Sanitization
**Goal**: Ensure zero background CPU drain, no hanging dbus services, and no orphaned helper processes.
1. Inspect process trees on both devices:
   ```bash
   ps aux | grep -E 'glycin|dbus|at-spi|sensor|geoclue'
   ```
2. Disable unnecessary background GLib / GNOME services that attempt to launch in PRoot:
   - `glycin-svg` / `glycin-image-rs`: Ensure image loaders terminate immediately after thumbnailing and do not spin in seccomp loops (set `G_MESSAGES_DEBUG=""` and verify sandbox policy).
   - Ensure `at-spi2-registryd` and `at-spi-bus-launcher` do not loop if accessibility is disabled:
     ```bash
     gsettings set org.gnome.desktop.interface toolkit-accessibility false
     ```
   - Verify that running `stop_gui.sh` leaves exactly 0 residual processes across both host and guest.

---

### Task 2: Phase G — Purge XFCE & Build Clean Phosh-Lite Rootfs
**Goal**: Eliminate all legacy XFCE packages from the rootfs, permanently install the Phosh-Lite bundle, and generate a new canonical `alpaquita_rootfs.tar.zst`.

1. **Remove XFCE Packages from Staging Rootfs**:
   Execute inside the rootfs chroot / PRoot:
   ```bash
   apk del --no-cache \
       xfce4-session \
       xfwm4 \
       xfdesktop \
       xfce4-panel \
       xfce4-settings \
       xfce4-appfinder \
       xfconf \
       tumbler \
       garcon \
       libxfce4ui \
       libxfce4util
   ```
2. **Clean Stale Files & Temporary Build Directories**:
   - Remove `/tmp/*` build artifacts (`/tmp/phosh`, `/tmp/wlroots`, `/tmp/phoc`).
   - Remove old XFCE menu caches and sessions: `/etc/xdg/xfce4`, `/usr/share/backgrounds/xfce`.
3. **Install Phosh-Lite into Rootfs**:
   - Run `packages/phosh-lite/install.sh` against the clean rootfs to unpack:
     - `/usr/local/bin/phoc`
     - `/usr/local/bin/phosh`
     - `/usr/local/bin/wvkbd-mobintl`
     - `/usr/local/bin/nativecode-desktop`
     - `/usr/local/lib64/*` (`libwlroots.so.12`, `libhandy-1`, `libgmobile`, `libncshims.so`, etc.)
     - `/usr/local/share/phosh/phoc.ini`
     - `/usr/share/icons/Adwaita`
     - `/usr/share/glib-2.0/schemas/*` (compiled via `glib-compile-schemas`)
4. **Package New Canonical Rootfs**:
   - Archive the cleaned rootfs to `alpaquita_rootfs.tar.zst` using standard compression settings.
   - Update SHA256 checksums in `tools/rootfs/` and app asset manifests.

---

### Task 3: Phase H & K — Clean APK Installation & Dual-Device Verification
**Goal**: Verify a fresh user out-of-the-box experience on both Mali and Adreno devices without manual intervention.

1. **Uninstall Existing NativeCode App**:
   ```bash
   # OnePlus 13R
   adb -s 192.168.1.38:44029 uninstall com.zenithblue.nativecode
   # Poco X6 Pro
   adb -s Y5WWBMJVOZSK4HU8 uninstall com.zenithblue.nativecode
   ```
2. **Build and Install Updated NativeCode APK**:
   - Build debug/release APK containing the new `MainActivity.kt` ("START PHOSH DESKTOP") and updated `stop_gui.sh`.
   - Install APK on both devices:
     ```bash
     adb -s <serial> install -r app-build-output.apk
     ```
3. **First-Boot Verification Loop**:
   - Launch NativeCode on both devices.
   - Complete initial Linux runtime onboarding (installs clean Phosh rootfs).
   - Navigate to Settings -> Tap **`START PHOSH DESKTOP`**.
   - Assert:
     - Termux:X11 launches directly into portrait full screen (no landscape letterboxing).
     - Live clock, battery percentage, and WiFi icons appear on top bar.
     - Tapping / swiping up the bottom home bar opens the app grid with full titles.
     - Virtual keyboard (`wvkbd`) appears when inputting text in Terminal.
     - Tapping **`STOP PHOSH DESKTOP`** completely tears down all processes and returns cleanly to the Settings page.
     - Idle power remains ~1.2 W with 0% runaway CPU usage.

---

## 4. Key Files to Reference

- `packages/phosh-lite/install.sh`: Master installation script for any rootfs.
- `packages/phosh-lite/phosh-lite-bundle.tar.gz`: Precompiled portable binary and library bundle.
- `app/src/main/assets/scripts/stop_gui.sh`: Clean process teardown script.
- `app/src/main/java/com/zenithblue/nativecode/MainActivity.kt`: UI entry points for Graphical Desktop cards.
- `docs/phosh-lite-packages-and-settings.md`: Configuration matrix and landscape fix documentation.
