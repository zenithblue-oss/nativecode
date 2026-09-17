# Phosh-Lite Packages, Libraries & Configuration Settings

**Target Platforms**:
- OnePlus 13R (Qualcomm Snapdragon 8 Gen 3, Adreno 750, Kernel 6.1, `/dev/kgsl-3d0`)
- Poco X6 Pro (MediaTek Dimensity 8300 Ultra, Mali-G615 MC6, Kernel 6.1, `/dev/mali0`)

**Bundle Location in Repo**: `packages/phosh-lite/phosh-lite-bundle.tar.gz`  
**Installation Script**: `packages/phosh-lite/install.sh`

---

## 1. Complete Package & Binary Inventory

| Binary / Library | Path | Role & Optimizations |
|---|---|---|
| **phoc** | `/usr/local/bin/phoc` | Phone Wayland compositor (v0.43.0). Patched for direct scanout & multi-surface rendering. |
| **phosh** | `/usr/local/bin/phosh` -> `/usr/local/libexec/phosh` | Mobile shell (v0.43.0). Features 40px touch handle, live clock, pull-down shade, lockscreen, app launcher grid. |
| **phosh-session** | `/usr/local/bin/phosh-session` | Session initiator script configuring Wayland environment. |
| **wvkbd-mobintl** | `/usr/local/bin/wvkbd-mobintl` | Virtual on-screen Wayland keyboard (installed symlink `/usr/local/bin/wvkbd`). |
| **nativecode-desktop** | `/usr/local/bin/nativecode-desktop` | Session selector & launcher. Symlinked to `/usr/local/bin/startxfce4`. Auto-detects Adreno (`kgsl-3d0`) and Mali (`mali0`). |
| **libwlroots.so.12** | `/usr/local/lib64/libwlroots.so.12` | Wayland compositor backend (0.17.4). Patched with: 1) 16ms (60 FPS) frame timer; 2) XCB SHM fallback; 3) EGL Surfaceless mesa; 4) zero-dimension layer clamping. |
| **libhandy-1.so.0** | `/usr/local/lib64/libhandy-1.so.0` | Mobile GTK3 widget library (1.9.0) powering carousel, leaflets, pagers. |
| **libgmobile.so.0** | `/usr/local/lib64/libgmobile.so.0` | Mobile device abstraction library (0.7.3) for notches, cutouts, gestures. |
| **libgudev-1.0.so.0** | `/usr/local/lib64/libgudev-1.0.so.0` | GObject UDev bindings (238) providing device enumeration. |
| **libjson-glib-1.0.so.0** | `/usr/local/lib64/libjson-glib-1.0.so.0` | JSON parser library for device profiles and settings. |
| **libncshims.so** | `/usr/local/lib64/libncshims.so` | NativeCode universal desktop shim satisfying: `GnomeWallClock`, `GcrSecureEntryBuffer`, `GcrPrompt`, `BluetoothClient`, `libfeedback`, `libnm`, `mm-glib`, `callaudiod`, `upower`. |

---

## 2. Configuration & Resource Files

| Configuration File | Path | Description |
|---|---|---|
| **Compositor Config** | `/usr/local/share/phosh/phoc.ini` | Screen modes, DPI scale (`scale = 2.5`), orientation, outputs. |
| **Compiled Schemas** | `/usr/local/share/glib-2.0/schemas/` | GSettings schemas: `sm.puri.phosh`, `sm.puri.phoc`, `org.gnome.mutter`, `org.gnome.desktop.*`. |
| **DBus Auth Config** | `/etc/dbus-1/session-local.conf` | Grants `ANONYMOUS` and `EXTERNAL` auth across Android UID / PRoot isolation boundaries. |
| **Application Entries** | `/usr/share/applications/*.desktop` | Configured with `OnlyShowIn=XFCE;Phosh;GNOME;` so all applications appear in Phosh launcher grid. |
| **Wallpapers & Assets** | `/usr/local/share/backgrounds/gnome/` | Desktop & lockscreen background wallpapers. |
| **Icon Theme** | `/usr/share/icons/Adwaita/`, `/usr/local/share/icons/hicolor/` | Full Adwaita icon theme (`packages/phosh-lite/adwaita-icons.tar.gz`) and Phosh symbolic status icons. |

---

## 3. Required Alpine/Alpaquita Shared Packages

Installed automatically by `packages/phosh-lite/install.sh`:
- `xcb-util-renderutil` (libxcb-render-util)
- `libpulse` (PulseAudio client)
- `libpulse-mainloop-glib` (PulseAudio GLib mainloop binding)
- `polkit-elogind-libs` (PolKit client)
- `libelogind` (Login/session management)
- `libsecret` (Secret service API)

---

## 4. Termux:X11 Scaling & Display Configuration

To ensure edge-to-edge rendering, responsive gestures, and no navigation bar interference:
```bash
# Set display mode to native device resolution
am broadcast -a com.termux.x11.CHANGE_PREFERENCE -p com.zenithblue.nativecode --es displayResolutionMode native

# Enable immersive fullscreen & hide cutout
am broadcast -a com.termux.x11.CHANGE_PREFERENCE -p com.zenithblue.nativecode --ez fullscreen true --ez hideCutout true

# Set direct touchscreen touch mode (3)
am broadcast -a com.termux.x11.CHANGE_PREFERENCE -p com.zenithblue.nativecode --es touchMode 3
```

---

## 5. Runtime GSettings Profile

Applied during `install.sh`:
```bash
# App grid: remove adaptive mobile filter so all standard Linux tools appear
gsettings set sm.puri.phosh app-filter-mode '[]'

# Favorites: empty favorites so all apps appear in the main grid with full titles
gsettings set sm.puri.phosh favorites '[]'

# Appearance & Lockscreen
gsettings set org.gnome.desktop.interface icon-theme "Adwaita"
gsettings set org.gnome.desktop.lockdown disable-lock-screen true
gsettings set org.gnome.desktop.screensaver lock-enabled false
gsettings set org.gnome.desktop.session idle-delay 0
```

---

## 6. Hardware GPU Acceleration Matrix

### OnePlus 13R (Adreno 750 / Snapdragon 8 Gen 3)
```bash
export MESA_LOADER_DRIVER_OVERRIDE=zink
export GALLIUM_DRIVER=zink
export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
export TU_DEBUG=noconform
export MESA_VK_WSI_DEBUG=sw
```

### Poco X6 Pro (Mali-G615 MC6 / MediaTek Dimensity 8300 Ultra)
```bash
export MESA_LOADER_DRIVER_OVERRIDE=zink
export GALLIUM_DRIVER=zink
export VK_DRIVER_FILES=/opt/nativecode/panvk/icd.d/panvk_kbase.json
export VK_ICD_FILENAMES=/opt/nativecode/panvk/icd.d/panvk_kbase.json
[ -f /opt/nativecode/panvk/lib/kbase_shim.so ] && export LD_PRELOAD="/opt/nativecode/panvk/lib/kbase_shim.so${LD_PRELOAD:+:$LD_PRELOAD}"
export WSI_X11_TERMUX=1
export PANVK_KBASE_DRI3=raw
export MESA_VK_WSI_DEBUG=sw
```

---

## 7. Screen Orientation & Auto-Resolution Findings (Preventing Landscape Bug)

### Root Cause of Landscape Bug
1. **Wlroots Default Mode**:
   - In `wlroots/backend/x11/output.c`, `wlr_x11_output_create` originally initialized new outputs with:
     ```c
     wlr_output_state_set_custom_mode(&state, 1024, 768, 0);
     ```
   - When `mode` was omitted from `phoc.ini`, the compositor fell back to `1024x768` (width > height, 4:3 landscape ratio).
2. **Phosh Monitor Classification**:
   - Phosh's rotation engine (`rotateinfo.c`) calculates:
     ```c
     monitor_is_landscape = ((double)monitor->width / (double)monitor->height) > 1.0;
     ```
   - Because `1024 / 768 > 1.0`, Phosh identified the display as a landscape monitor, locked the UI into landscape mode, and restricted rendering to a 768px vertical band, leaving the rest of the physical phone display black.

### Permanent Fix Applied in `libwlroots.so.12`
`backend/x11/output.c` was patched to query the root X11 screen dimensions directly:
```c
uint32_t init_w = (x11->screen && x11->screen->width_in_pixels > 0) ? x11->screen->width_in_pixels : 1080;
uint32_t init_h = (x11->screen && x11->screen->height_in_pixels > 0) ? x11->screen->height_in_pixels : 1920;
wlr_output_state_set_custom_mode(&state, init_w, init_h, 0);
```
With this fix:
- On OnePlus 13R, the output automatically initializes to `1264x2780` (portrait).
- On Poco X6 Pro, the output automatically initializes to `1080x1920` or `1220x2712` (portrait).
- `phoc.ini` no longer requires device-specific `mode = ...` entries, allowing dynamic full-screen adaptation across any phone model.

