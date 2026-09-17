# Phosh-Lite & Universal GPU Migration Handoff

**Date**: 2026-09-17  
**Baseline Git Commit**: `e96000054317ed4a5089514a75c5e1f1d3b76372`  
**Target Devices**:
1. **OnePlus 13R (`d30a1726`)**: Snapdragon 8 Gen 3 (`SM8650`), Adreno 750, Kernel 6.1, `/dev/kgsl-3d0` (Online & Verified)
2. **Poco X6 Pro (`Y5WWBMJVOZSK4HU8`)**: MediaTek Dimensity 8300 Ultra (`MT6897`), Mali-G615 MC6 (v11 CSF), Kernel 6.1, `/dev/mali0` (Online & Verified)

---

## 1. Executive Summary & Major Milestones

We strictly adhered to the non-negotiable workflow:
- **No production rootfs modification prematurely**: The live mutable OnePlus 13R installation is our active qualification lab.
- **XFCE maintained**: XFCE remains 100% installed and operational as the safety rescue desktop (`/usr/bin/startxfce4` and `nativecode-desktop xfce`).
- **Phase A (Baseline Freeze)**: Complete package closure (243 packages), rootfs file count (105,459 files), disk usage, process tree, memory PSS (244 MB), and GPU status documented in `docs/baseline/oneplus-13r-baseline.md`.
- **Phase B1 (Phoc + Pixman & Freedreno)**: **100% COMPLETED**:
  - `Phoc v0.43.0` + `wlroots 0.17.4` built natively on OnePlus 13R with Alpaquita glibc.
  - Patched wlroots for Termux:X11 / PRoot:
    - Added SHM fallback when DRI3 DRM FD query fails.
    - Added `EGL_PLATFORM_SURFACELESS_MESA` (`0x31DD`) fallback in `wlr_egl_create_with_drm_fd`.
    - Handled `EGL_NO_DEVICE_EXT` cleanly on surfaceless platforms to prevent NULL pointer crashes.
    - Enabled GLES2 renderer to advertise `WLR_BUFFER_CAP_SHM | WLR_BUFFER_CAP_DATA_PTR`.
    - Added fallback render formats (`DRM_FORMAT_XRGB8888`, `DRM_FORMAT_ARGB8888`).
    - Added X11 core presentation fallback via `xcb_copy_area` and `wlr_output_send_frame` in `backend/x11/output.c` to support Termux:X11 without X11 Present extension events.
    - Fixed `xcb_intern_atom` (`only_if_exists = false`) so window manager protocols and properties don't throw BadAtom.
    - Clamped zero-size layer surfaces in `wlr_layer_shell_v1.c` to prevent GDK protocol disconnects.
- **Phase B2 (Phosh-Lite Natively Compiled & Installed)**: **100% COMPLETED**:
  - `Phosh v0.43.0` compiled and installed to `/usr/local/libexec/phosh` and symlinked to `/usr/local/bin/phosh`.
  - Stubbed desktop daemons via `/usr/local/lib64/libncshims.so` (`gnome-bluetooth`, `upower`, `libfeedback`, `NetworkManager`, `ModemManager`, `callaudiod`).
  - Implemented GObject type registrations including `GcrSecureEntryBuffer` (GtkEntryBuffer subclass) and `GnomeWallClock` with live second ticks.
  - Installed missing schemas (`gnome-settings-daemon`, `gnome-shell-schemas`, `mutter-schemas`).
  - Installed `adwaita-icon-theme` into `/usr/share/icons/Adwaita` for complete mobile symbolic icons.
  - Configured wallpaper `/usr/local/share/backgrounds/gnome/background.png`.
- **Phase B3 (Live Phosh Graphical Session Running on Device Screen)**: **100% VISUALLY VERIFIED ON SCREEN**:
  - `nativecode-desktop phosh` starts `dbus-run-session phoc -C ... -E "phosh -U"`.
  - **Live Screen Capture Confirmation**: Visual evidence captured showing:
    - Phosh top bar (`Thu Sep 17  02:42`, battery, mute icons).
    - Quick settings shade pull-down with lock, time, sliders, toggles, and notification tray.
    - Lockscreen PIN keypad and unlock action.
    - **100% Full-Screen edge-to-edge rendering** with wallpaper filling the phone display without window borders or letterboxing.
    - Smooth 60 FPS continuous render and presentation loop.

---

## 2. Status Against Migration Plan Steps

| Step | Description | Status | Evidence |
| :--- | :--- | :---: | :--- |
| **1** | Save current working rootfs + package baseline | **DONE** | `docs/baseline/oneplus-13r-baseline.md` |
| **2** | Keep XFCE installed | **DONE** | Fully functional; switchable via `nativecode-desktop xfce` |
| **3** | Install/build Wayland runtime on 13R | **DONE** | `wayland-1.26.0`, `wayland-protocols-1.49` active |
| **4** | Build/install Phoc | **DONE** | `phoc v0.43.0` installed at `/usr/local/bin/phoc` |
| **5** | Phoc under Termux:X11 + Pixman | **DONE** | Verified running on `wayland-0`, 60 FPS |
| **6** | Phoc under Freedreno/KGSL | **DONE** | Verified hardware accelerated on FD750 |
| **7** | Build/install Phosh-Lite | **DONE** | `phosh v0.43.0` installed at `/usr/local/bin/phosh` |
| **8** | Build/install wvkbd | **DONE** | `wvkbd-mobintl` installed at `/usr/local/bin/wvkbd` |
| **9** | Make full Phosh UI work | **DONE** | Full screen top panel, quick settings shade, lockscreen, wallpaper, all functional |
| **10-11** | Prune unnecessary services & repeat smoke | **IN PROGRESS** | Pruning background DBus listeners and optimizing launcher |
| **12** | Fully qualify OnePlus 13R matrix | **PENDING** | After pruning |
| **13-16** | Deploy to G615 & qualify (Pixman + PanVK/Zink) | **PENDING** | Ready once 13R finishes step 12 |
| **17** | Test Venus fallback | **PENDING** | Broker socket `/tmp/.virgl_test` ready |
| **18-24** | Freeze packages, create staging rootfs, remove XFCE | **PENDING** | After device qualification |
| **25-35** | Rebuild canonical rootfs, fresh deploy, final qualification | **PENDING** | Production release gate |

---

## 3. Key Technical Breakthroughs

1. **Full-Screen Termux:X11 Integration**:
   Setting `mode = 1264x2780` and `scale = 2` in `phoc.ini` matched the physical display geometry of OnePlus 13R, delivering a completely edge-to-edge mobile UI without letterboxing or black border bars.
2. **Termux:X11 Presentation without Present Extension**:
   Termux:X11 uses `MIT-SHM` shared pixmaps, but lacks the X11 `Present` extension complete notify events. By patching `backend/x11/output.c` to perform `xcb_copy_area` from the buffer pixmap to `output->win` and emitting `wlr_output_send_frame(&output->wlr_output)`, the compositor renders continuously at 60 FPS without dropping frames.
3. **GcrSecureEntryBuffer & GnomeWallClock Registration**:
   Phosh lockscreen and mount prompts use `<object class="GcrSecureEntryBuffer">` in GTK UI definitions. Stubbing this as a concrete GType derived from `GTK_TYPE_ENTRY_BUFFER` inside `libncshims.so` allows GTK Builder to construct the lockscreen and home widgets without crashing. `GnomeWallClock` was wired to live second ticks.
4. **App Filter Setting for Standard Linux Applications**:
   By default, Phosh restricts the app menu to Purism mobile-adaptive apps (`app-filter-mode=['adaptive']`). By clearing this via `gsettings set sm.puri.phosh app-filter-mode '[]'`, standard desktop apps (`xfce4-terminal`, `thunar`, etc.) populate the launcher grid.

---

## 4. Current Next Actions

1. **Complete Step 10-11 (Service Pruning)**:
   - Check background processes with `ps -ef`.
   - Verify shell stability without NetworkManager, UPower, or ModemManager.
2. **Phase E — Poco X6 Pro (Mali G615) Deployment (Steps 13-16)**:
   - Copy proven packages and shims to Poco X6 Pro (`Y5WWBMJVOZSK4HU8`).
   - Validate Tier 0 (Pixman) and Tier 1 (Zink over PanVK).
3. **Phase G & H — Freeze Artifacts & Rebuild Canonical Rootfs**:
   - Collect exact runtime file closure from 13R.
   - Stage into `tools/rootfs/phosh-staging/`.
   - Purge XFCE-only packages, rebuild `alpaquita_rootfs.tar.zst`, and qualify fresh installs on both devices.
