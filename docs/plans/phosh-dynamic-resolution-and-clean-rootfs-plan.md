# Implementation & Verification Plan: Phosh Dynamic Resolution Scaling & Clean Rootfs Deployment

**Date**: 2026-09-17  
**Target Devices**:
1. **Poco X6 Pro (`Y5WWBMJVOZSK4HU8`)**: MediaTek Dimensity 8300 Ultra, Mali-G615 MC6, Native Display `1220 x 2712` (or `1216 x 2704` with cutout)
2. **OnePlus 13R (`d30a1726`)**: Snapdragon 8 Gen 3, Adreno 750, Native Display `1264 x 2780`

---

## 1. Root Cause Analysis

### Issue A: Hardcoded Static `mode = 1264x2780` Injected from Stale Device Sideload
- **Finding**: On the Poco X6 Pro, the compositor window was queried via X11 protocol (`QueryTree` + `GetGeometry` on root window child `0x200004`) and returned **`1264x2780`**. This is the physical resolution of the **OnePlus 13R**, not the Poco X6 Pro (`1220x2712`).
- **Mechanism**:
  - `RootfsSourceResolver.kt` (lines 54–60) checks `/data/local/tmp/alpaquita_rootfs.tar.zst` in DEBUG builds before reading APK assets.
  - A stale archive timestamped `2026-09-17 18:17` was present on `/data/local/tmp/` on both devices.
  - That stale archive contained an obsolete `/usr/local/share/phosh/phoc.ini` with:
    ```ini
    [output:X11-1]
    mode = 1264x2780
    scale = 2.5
    ```
  - During onboarding, the app extracted the stale sideload archive instead of the freshly built rootfs inside the APK, reinjecting the hardcoded resolution bug.

### Issue B: Resolution Adaptation Between X11 and Phoc/Phosh
- **Mechanism**:
  - `termux-x11` creates an X11 root display window (`DisplayWidth`, `DisplayHeight`).
  - `phoc` (built on patched `libwlroots.so.12`) initializes output `X11-1` in `wlr_x11_output_create`:
    ```c
    uint32_t init_w = (x11->screen && x11->screen->width_in_pixels > 0) ? x11->screen->width_in_pixels : 1080;
    uint32_t init_h = (x11->screen && x11->screen->height_in_pixels > 0) ? x11->screen->height_in_pixels : 1920;
    wlr_output_state_set_custom_mode(&state, init_w, init_h, 0);
    ```
  - When `mode = 1264x2780` was specified in `phoc.ini`, `phoc` overrode the auto-detected screen size and forced `1264x2780`.
  - On a 1220x2712 screen, forcing 1264x2780 caused Phosh to render outside bounds at the top/right and leave over 60% of the screen as black void at the bottom, hiding the bottom home bar (`phosh-home`).
  - **Resolution**: Removing all static `mode = ...` entries from `phoc.ini` allows `libwlroots.so.12` to query the live `x11->screen->width_in_pixels` and `height_in_pixels` directly, ensuring 1:1 matching with the active X11 root window.

### Issue C: Termux:X11 Server Restart Collisions
- **Finding**: On restarting the desktop session, `start_gui.sh` previously failed with:
  `_XSERVTransSocketUNIXCreateListener() failed: server already running [exit 1]`
- **Mechanism**:
  - `termux-x11` (app_process) was alive without an active `.X0-lock` or socket in `$TMPDIR`.
  - `start_gui.sh` tried to spawn a second X server on `:0`, which crashed due to socket contention.
- **Resolution**:
  - `start_gui.sh`: Kill stale `termux-x11` PIDs if no valid socket is listening before starting `Loader`.
  - `stop_gui.sh`: Added explicit loop killing all `termux-x11` PIDs and scrubbing `/tmp` and `$TMPDIR` lock files.

### Issue D: Turnip Adreno 750 (OnePlus 13R) Exit Code 127
- **Finding**: `phoc` failed on OnePlus 13R with `error while loading shared libraries: libLLVM.so.20.1: cannot open shared object file`.
- **Mechanism**:
  - `gpu-backend.sh` and `gpu-env.sh` had added `/opt/nativecode-turnip/lib` to `LD_LIBRARY_PATH`. That directory held a Debian-compiled Mesa that linked against LLVM 20, whereas Alpaquita/Alpine uses LLVM 22.
  - The baked system package `mesa-vulkan-freedreno-26.1.6-r1` (`/usr/lib/libvulkan_freedreno.so`) is compiled natively against Alpaquita glibc and LLVM 22, but required `xcb-util-keysyms`.
- **Resolution**:
  - Add `xcb-util-keysyms` to the rootfs package manifest.
  - Configure `freedreno_icd.aarch64.json` to point to `/usr/lib/libvulkan_freedreno.so`.
  - Ensure `LD_LIBRARY_PATH` is NOT polluted with `/opt/nativecode-turnip/lib`.

---

## 2. All Related Files & Code Modifications

### 1. `tools/rootfs/rebuild_phosh_rootfs.sh`
- Ensure `xcb-util-keysyms` is installed in Step 3 (`apk add`).
- Ensure `00_mobi.Phosh.gschema.override` configures:
  ```ini
  [sm.puri.phosh]
  favorites=@as []
  app-filter-mode=@as []
  ```
- Verify `/usr/local/share/phosh/phoc.ini` contains ONLY:
  ```ini
  [core]
  #xwayland=false

  [output:Virtual-1]
  scale = 2.5

  [output:X11-1]
  scale = 2.5

  [output:WL-1]
  scale = 2.5

  [output:HEADLESS-1]
  scale = 2.5
  ```
  *(Zero `mode = ...` entries).*
- Update `/etc/profile.d/gpu-env.sh` and `/usr/local/lib/nativecode/gpu-backend.sh` with clean driver paths:
  - Adreno: `GALLIUM_DRIVER=zink`, `MESA_LOADER_DRIVER_OVERRIDE=zink`, `VK_DRIVER_FILES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json`.
  - Mali: `GALLIUM_DRIVER=zink`, `MESA_LOADER_DRIVER_OVERRIDE=zink`, `VK_DRIVER_FILES=/opt/nativecode/panvk/icd.d/panvk_kbase.json`, `LD_PRELOAD=/opt/nativecode/panvk/lib/kbase_shim.so`.
  - Compositor: `WLR_RENDERER=pixman` (CPU SHM presentation to X11).

### 2. `app/src/main/java/com/zenithblue/nativecode/onboarding/RootfsSource.kt`
- Update `PINNED_ALPAQUITA_SHA256` to the exact hash generated by `rebuild_phosh_rootfs.sh`.
- Ensure stale `/data/local/tmp/alpaquita_rootfs.tar.zst` cannot bypass verification: require matching SHA256 or ignore sideload when hash mismatches.

### 3. `app/src/main/assets/scripts/start_gui.sh`
- Enforce clean X11 server startup and stale PID termination:
  ```bash
  X11_RUNNING=false
  if "$TB" pidof termux-x11 >/dev/null 2>&1; then
    if [ -S "$TMPDIR/.X11-unix/X0" ] || [ -S "/tmp/.X11-unix/X0" ]; then
      X11_RUNNING=true
      echo "FluxLinux: Reusing existing termux-x11 server (PID=$("$TB" pidof termux-x11))"
    else
      echo "FluxLinux: Terminating stale termux-x11 server without active socket..."
      for _p in $("$TB" pidof termux-x11); do
        "$TB" kill -9 "$_p" 2>/dev/null || true
      done
      "$TB" sleep 0.5
    fi
  fi
  if ! $X11_RUNNING; then
    "$TB" rm -f "$TMPDIR/.X0-lock" "$TMPDIR/.X11-unix/X0" "/tmp/.X0-lock" "/tmp/.X11-unix/X0" 2>/dev/null || true
  fi
  ```

### 4. `app/src/main/assets/scripts/stop_gui.sh`
- Thorough process teardown including PID-based killing of `termux-x11` and removal of socket locks across both `/tmp` and `$TMPDIR`.

### 5. `termux-x11/src/main/java/com/termux/x11/LoriePreferences.java` & `Prefs.java`
- Ensure `displayResolutionMode` defaults to `"native"` on initial creation.

---

## 3. Packaging & Staging Workflow

1. **Purge Device Sideload Artifacts**:
   ```bash
   adb -s Y5WWBMJVOZSK4HU8 shell "rm -f /data/local/tmp/alpaquita_rootfs.tar.zst*"
   adb -s d30a1726 shell "rm -f /data/local/tmp/alpaquita_rootfs.tar.zst*"
   ```
2. **Rebuild Canonical Rootfs Archive**:
   ```bash
   bash tools/rootfs/rebuild_phosh_rootfs.sh
   ```
   - Target archive: `tools/rootfs/out/alpaquita_rootfs.tar.zst`
   - Asset target: `nativecode_runtime/src/main/assets/alpaquita_rootfs.tar.zst`
   - Checksum target: `nativecode_runtime/src/main/assets/alpaquita_rootfs.tar.zst.sha256`
3. **Rebuild APK**:
   ```bash
   ./gradlew clean assembleDebug
   ```

---

## 4. Retesting & Verification Procedure

### Test 1: Clean Slate Installation on Poco X6 Pro (`Y5WWBMJVOZSK4HU8`)
1. Uninstall existing app: `adb -s Y5WWBMJVOZSK4HU8 uninstall com.zenithblue.nativecode`.
2. Install freshly built APK: `adb -s Y5WWBMJVOZSK4HU8 install app/build/outputs/apk/full/debug/app-full-debug.apk`.
3. Launch app: `adb -s Y5WWBMJVOZSK4HU8 shell am start -n com.zenithblue.nativecode/.SplashActivity`.
4. Walk through onboarding UI via `android-mcp` clicks.
5. Verify rootfs extraction log shows extraction from APK asset (or verified hash) and completes to 100%.
6. Navigate to Settings -> Click "START PHOSH DESKTOP".
7. Query X11 and Phosh geometries via socket query:
   - Verify `X11_ROOT_RESOLUTION` matches Poco X6 Pro native (`1216x2712` or `1220x2712`).
   - Verify `PHOSH_WINDOW` matches the root resolution (no 1264x2780, no letterboxing).
8. Capture Android visual snapshot:
   - Confirm edge-to-edge UI.
   - Confirm bottom home bar (`phosh-home`) is visible at the very bottom of the screen.
   - Confirm full app launcher grid with icon labels and wallpaper background.
9. Verify PanVK + Zink GPU hardware acceleration:
   - Run `/opt/nativecode/panvk/bin/vulkan_graphics_test` -> verify triangle pass.
   - Run `python3 /opt/nativecode/panvk/egl_zink_probe.py` -> verify `GL_RENDERER: zink Vulkan 1.4 (Mali unknown 0xa8671030 MC6)`.

### Test 2: Clean Slate Installation on OnePlus 13R (`d30a1726`)
1. Uninstall existing app: `adb -s d30a1726 uninstall com.zenithblue.nativecode`.
2. Install freshly built APK: `adb -s d30a1726 install app/build/outputs/apk/full/debug/app-full-debug.apk`.
3. Launch app: `adb -s d30a1726 shell am start -n com.zenithblue.nativecode/.SplashActivity`.
4. Walk through onboarding UI via `android-mcp` clicks and complete rootfs extraction.
5. Navigate to Settings -> Click "START PHOSH DESKTOP".
6. Query X11 and Phosh geometries:
   - Verify `X11_ROOT_RESOLUTION` is `1264x2780`.
   - Verify `PHOSH_WINDOW` is `1264x2780`.
7. Capture visual snapshot:
   - Confirm edge-to-edge UI, live clock, top bar, app grid, and bottom home bar.
8. Verify Turnip + Zink GPU hardware acceleration:
   - Run `/opt/nativecode/panvk/bin/vulkan_graphics_test` -> verify triangle pass on Adreno 750.
   - Run `python3 /opt/nativecode/panvk/egl_zink_probe.py` -> verify `GL_RENDERER: zink Vulkan 1.4 (Turnip Adreno (TM) 750)`.
