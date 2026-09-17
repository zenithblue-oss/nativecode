# Phosh-Lite Migration: Completed Work & Technical Architecture

**Date**: 2026-09-17  
**Status**: Phase B1 & B2 Complete, Phase B3 Verified On Screen  
**Hardware Platforms**:
1. **OnePlus 13R (`d30a1726`)**: Snapdragon 8 Gen 3 (`SM8650`), Adreno 750, Kernel 6.1, `/dev/kgsl-3d0` (Primary qualification device)
2. **Poco X6 Pro (`Y5WWBMJVOZSK4HU8`)**: MediaTek Dimensity 8300 Ultra (`MT6897`), Mali-G615 MC6, Kernel 6.1, `/dev/mali0` (Secondary qualification device)

---

## 1. How We Interact with Both Devices

Because NativeCode runs on Android with `targetSdkVersion 37` under a PRoot guest environment, interaction requires a specific architecture to bridge host commands, guest commands, and graphical validation.

### A. Android Permission & UID Sandbox Constraints
- An `adb shell` session executes under Android system UID `2000` (`shell`).
- NativeCode's app directory (`/data/data/com.zenithblue.nativecode/`) is owned by application UID `10461` (`u0_a461`) with mode `700` (`drwx------`).
- Direct `adb shell` commands cannot access the app data directory, cannot `cd` into `/data/data/com.zenithblue.nativecode`, and cannot launch `proot` directly due to SELinux and Linux UID restrictions.
- `run-as com.zenithblue.nativecode` is blocked by Android 16 SELinux (`Permission denied: No match for app with uid 10461, seinfo default:targetSdkVersion=37:fromRunAs`).
- Therefore, commands must be executed within the app's established process boundary.

### B. The Guest HTTP Micro-Bridge Architecture
To execute commands within the live guest environment (`flux` user inside Alpaquita PRoot):
1. A lightweight Python HTTP server (`/home/flux/guest_bridge.py`) was deployed into the guest container listening on loopback port `9090`.
2. Port forwarding is established from the developer machine to the device:
   ```bash
   adb -s d30a1726 forward tcp:9090 tcp:9090
   ```
3. A local Python runner on the developer host (`/tmp/guest_run.py`) issues HTTP POST requests to `http://127.0.0.1:9090/exec` containing shell commands.
4. The guest server executes the command via `subprocess.run` as user `flux` (uid 1000) inside the Alpaquita glibc rootfs, with full access to `/usr/local/bin`, `/usr/bin`, compiler toolchains, and `/tmp`.
5. The exit code, stdout, and stderr are serialized to JSON and returned directly to the host context.
6. Reverse port forwarding (`adb -s d30a1726 reverse tcp:8000 tcp:8000`) allows the guest to pull files, scripts, and patches hosted on the local machine via `curl http://127.0.0.1:8000/...`.

### C. Android MCP Graphical Verification (Vision + Touch)
1. **Visual State Capture**: We invoke `android-mcp_Snapshot` with `use_vision: true` to capture the real physical rendering of the screen. This provides proof of what Termux:X11 is displaying (e.g. status bar, wallpaper, pull-down shade, lockscreen keypad).
2. **Touch & Gesture Injection**: We use `android-mcp_Click`, `android-mcp_Swipe`, `android-mcp_Type`, and `android-mcp_Press` to simulate real user touch input on the running graphical session.
3. **Termux:X11 Activity Management**:
   - Termux:X11 runs as an Android Activity (`com.zenithblue.nativecode/com.termux.x11.MainActivity`).
   - The X11 display server is launched by `start_gui.sh` via `app_process` on `:0` with a UNIX domain socket at `/tmp/.X11-unix/X0`.
   - The desktop environment connects to `DISPLAY=:0`.

---

## 2. Complete List of Packages & Libraries Built on OnePlus 13R

Every binary and library was compiled natively inside the Alpaquita glibc environment on the OnePlus 13R to ensure binary compatibility:

### 1. `wayland 1.26.0` & `wayland-protocols 1.49`
- Native build of Wayland client, server, and scanner headers.
- Installed to `/usr/local/bin/wayland-scanner`, `/usr/local/lib64/libwayland-*.so`.

### 2. `wlroots 0.17.4` (Patched for Android PRoot & Termux:X11)
- **Source**: `/tmp/wlroots/`
- **Build System**: Meson + Ninja (`meson setup build --prefix=/usr/local -Dexamples=false`)
- **Key Patches Applied**:
  - `backend/x11/backend.c`: Added graceful fallback to `have_dri3 = false` when DRI3 DRM FD query fails (Android PRoot lacks `/dev/dri/*` nodes). Enabled `have_shm = true`. Fixed `xcb_intern_atom` to pass `only_if_exists = false` so window management atoms (`WM_PROTOCOLS`, `_NET_WM_NAME`) are created without `BadAtom` errors.
  - `render/egl.c`: In `wlr_egl_create_with_drm_fd`, added fallback to `EGL_PLATFORM_SURFACELESS_MESA` (`0x31DD`). Added NULL check for `EGL_NO_DEVICE_EXT` on `eglQueryDeviceStringEXT` to prevent crashes when DRM device extension is absent.
  - `render/gles2/renderer.c`: Enabled GLES2 renderer to export `WLR_BUFFER_CAP_SHM | WLR_BUFFER_CAP_DATA_PTR` so X11 swapchains initialize on SHM. Added `DRM_FORMAT_XRGB8888` and `DRM_FORMAT_ARGB8888` fallback formats.
  - `backend/x11/output.c`: **Critical presentation engine fix**: Termux:X11 implements `MIT-SHM` but lacks the X11 `Present` extension complete notify events. We implemented an `xcb_copy_area` fallback from the SHM pixmap to `output->win` combined with immediate `wlr_output_send_frame(&output->wlr_output)` emission. This unlocked continuous 60 FPS rendering.
  - `types/wlr_layer_shell_v1.c`: Clamped zero-size layer surfaces (`desired_width == 0` or `desired_height == 0`) to 1px instead of emitting fatal protocol error 1, preventing GTK client disconnects.
- **Installed Artifacts**: `/usr/local/lib64/libwlroots.so.12`, `/usr/local/include/wlr/*`, `/usr/local/lib64/pkgconfig/wlroots.pc`.

### 3. `phoc v0.43.0` (Mobile Wayland Compositor)
- **Source**: `/tmp/phoc/`
- **Build System**: Meson + Ninja (`meson setup build --prefix=/usr/local -Dtests=false -Dman=false`)
- **Dependencies resolved**: Built against patched `wlroots`, `gmobile`, and minimal shims for `libinput` and `gnome-desktop-3.0`.
- **Installed Artifacts**: `/usr/local/bin/phoc`, `/usr/local/share/phosh/phoc.ini`, `/usr/local/share/glib-2.0/schemas/sm.puri.phoc.gschema.xml`.

### 4. `wvkbd-mobintl` (On-Screen Mobile Keyboard)
- **Source**: `/tmp/wvkbd/`
- **Build System**: Make (`make -j4`)
- **Dependencies**: Cairo, Pango, PangoCairo, libxkbcommon, wayland-client.
- **Installed Artifacts**: `/usr/local/bin/wvkbd`.

### 5. `libgudev-1.0 238`
- **Source**: `/tmp/libgudev/`
- **Build System**: Meson + Ninja
- **Dependencies**: `eudev`
- **Installed Artifacts**: `/usr/local/lib64/libgudev-1.0.so.0.3.0`, pkg-config definition.

### 6. `gmobile 0.7.3`
- **Source**: `/tmp/gmobile/`
- **Build System**: Meson + Ninja with JSON-GLib
- **Installed Artifacts**: `/usr/local/lib64/libgmobile.so.0`, headers, pkg-config definition.

### 7. `libhandy-1 1.9.0`
- **Source**: `/tmp/libhandy/`
- **Build System**: Meson + Ninja (`-Dintrospection=disabled -Dexamples=false -Dtests=false`)
- **Installed Artifacts**: `/usr/local/lib64/libhandy-1.so.0`, headers, pkg-config definition.

### 8. `libncshims.so` (Universal Desktop Shims)
- **Source**: `/tmp/shims/shims.c`
- **Build**: `gcc -shared -fPIC /tmp/shims/shims.c -o /usr/local/lib64/libncshims.so $(pkg-config --cflags --libs glib-2.0 gobject-2.0 gio-2.0 gtk+-3.0)`
- **Functionality Provided**:
  - `GnomeXkbInfo`: Minimal GObject providing keyboard layout names.
  - `GnomeWallClock`: Concrete GObject with 1-second timer notifying `notify::clock` with formatted local time.
  - `GcrSecureEntryBuffer`: Concrete GType derived from `GTK_TYPE_ENTRY_BUFFER`, enabling GTK Builder to construct Phosh lockscreen and auth dialogs without crashing.
  - `GcrPrompt` and `GcrSystemPrompter`: Clean stub interface and prompter instance.
  - `BluetoothClient` & `BluetoothDevice`: GObject stubs returning empty device lists and disabled adapter state.
  - `libinput` & `wlroots DRM session`: Stubs satisfying dynamic link symbols.
  - `libfeedback`: `lfb_init`, `lfb_event_trigger_feedback_async`, feedback profiles.
  - `NetworkManager` (`libnm`): Client async initialization that returns clean disabled error without hanging the shell.
  - `ModemManager` (`mm-glib`): Modem proxy stubs.
  - `callaudiod` (`libcallaudio`): Audio routing stubs.
  - `upower`: Returns cleanly disabled error, handled gracefully by Phosh battery status bar.

### 9. `phosh v0.43.0` (Mobile Graphical Shell)
- **Source**: `/tmp/phosh/`
- **Build System**: Meson + Ninja (`-Dtests=false -Dman=false -Dgtk_doc=false -Dbindings-lib=false -Dlockscreen-plugins=false -Dquick-setting-plugins=false -Dtools=false -Dcallui-i18n=false --prefix=/usr/local`)
- **Modifications**:
  - `meson.build`: Disabled `subdir('calendar-server')` (removes Evolution Data Server dependency).
  - Linked against `/usr/local/lib64/libncshims.so`.
- **Installed Artifacts**: `/usr/local/libexec/phosh`, symlink at `/usr/local/bin/phosh`, plugins in `/usr/local/lib64/phosh/plugins`, schemas in `/usr/local/share/glib-2.0/schemas/sm.puri.phosh.*.xml`.

### 10. System Dependencies & Schemas Installed
- `pulseaudio-dev` 17.0 (via apk)
- `linux-pam-dev` 1.7.1 (via apk)
- `p11-kit-dev` 0.26.2 (via apk)
- `gsettings-desktop-schemas 51.0` (installed and compiled)
- `mutter-schemas` (`org.gnome.mutter.keybindings`, `org.gnome.mutter.wayland`)
- `gnome-settings-daemon-47.2` schemas (`org.gnome.settings-daemon.peripherals.touchscreen`, etc.)
- `gnome-shell-schemas-47.9` (`org.gnome.shell.keybindings`, `org.gnome.shell.window-switcher`, etc.)
- `adwaita-icon-theme-47.0` (installed to `/usr/share/icons/Adwaita`, icon cache compiled)

---

## 3. All Scripts & Configurations Modified/Created

### A. Desktop Session Switcher: `/usr/local/bin/nativecode-desktop`
```bash
#!/bin/sh
# NativeCode Desktop Selector (Phosh-Lite / XFCE)

DESKTOP_CHOICE="${1:-}"
if [ -z "$DESKTOP_CHOICE" ] && [ -f /etc/nativecode/desktop_choice ]; then
    DESKTOP_CHOICE=$(cat /etc/nativecode/desktop_choice)
fi
[ -z "$DESKTOP_CHOICE" ] && DESKTOP_CHOICE="phosh"

case "$DESKTOP_CHOICE" in
    xfce|xfce4)
        exec /usr/bin/startxfce4
        ;;
    phosh|phosh-lite|*)
        export WLR_BACKENDS=x11
        export WLR_RENDERER=pixman
        export XDG_RUNTIME_DIR=/tmp/runtime-flux
        mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"
        export WAYLAND_DISPLAY=wayland-0
        exec dbus-run-session phoc -C /usr/local/share/phosh/phoc.ini -E "phosh -U" > /tmp/phosh_session.log 2>&1
        ;;
esac
```
- A symlink was created at `/usr/local/bin/startxfce4 -> /usr/local/bin/nativecode-desktop`. Because `/usr/local/bin` precedes `/usr/bin` in `$PATH`, clicking "START XFCE DESKTOP" in the NativeCode app UI runs Phosh-Lite transparently.
- Setting `/etc/nativecode/desktop_choice` to `xfce` allows instantly reverting to standard XFCE for rescue.

### B. Phoc Output Configuration: `/usr/local/share/phosh/phoc.ini`
```ini
[core]
#xwayland=false

[output:Virtual-1]
mode = 1264x2780
scale = 2

[output:X11-1]
mode = 1264x2780
scale = 2

[output:WL-1]
mode = 1264x2780
scale = 2

[output:HEADLESS-1]
mode = 1264x2780
scale = 2
```
- Sets output to the physical screen resolution of OnePlus 13R (`1264x2780`) at scale 2, rendering completely edge-to-edge without letterboxing.

### C. GSettings Configurations Applied
```bash
# Set Adwaita icon theme
gsettings set org.gnome.desktop.interface icon-theme "Adwaita"

# Set desktop and lockscreen wallpapers
sudo cp /usr/share/backgrounds/xfce/xfce-blue.jpg /usr/local/share/backgrounds/gnome/background.png
sudo chmod 644 /usr/local/share/backgrounds/gnome/background.png
gsettings set org.gnome.desktop.background picture-uri 'file:///usr/local/share/backgrounds/gnome/background.png'
gsettings set org.gnome.desktop.background picture-uri-dark 'file:///usr/local/share/backgrounds/gnome/background.png'
gsettings set org.gnome.desktop.screensaver picture-uri 'file:///usr/local/share/backgrounds/gnome/background.png'

# Disable automatic screen lock and idle blanking
gsettings set org.gnome.desktop.lockdown disable-lock-screen true
gsettings set org.gnome.desktop.screensaver lock-enabled false
gsettings set org.gnome.desktop.session idle-delay 0

# App menu: clear mobile form-factor filter so all Linux apps appear in the launcher grid
gsettings set sm.puri.phosh app-filter-mode '[]'

# Favorites: populate bottom launcher bar
gsettings set sm.puri.phosh favorites "['xfce4-terminal.desktop', 'thunar.desktop']"
```

---

## 4. Verification Evidence Summary

1. **Tier 0 Compositor (Pixman)**: Running at 60 FPS verified via continuous `output_commit_buffer` logs committing `size=720x1440` and `size=1264x2780` buffers.
2. **Tier 1 Compositor (Freedreno FD750)**: Verified hardware acceleration on Adreno 750 with Mesa 26.3 GLES 3.2.
3. **Full-Screen UI**: Verified via live Android vision snapshot at `02:51`. Screen displays the blue wallpaper, top bar with live clock `Thu Sep 17 02:51`, status icons, and mouse pointer with zero black border bars.
4. **Interactive Pull-Down Shade**: Verified via snapshot at `02:43`. The top bar swipes down smoothly to reveal quick toggles (Cellular, Wi-Fi, Bluetooth, Portrait, Silent), brightness and audio sliders, and notification tray.
5. **Interactive Lockscreen & Unlock**: Verified via snapshot at `02:53` showing the full numeric PIN keypad and successful unlock to the desktop.
