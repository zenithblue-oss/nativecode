# Phosh-Lite Migration: Next Steps, Actionable Tasks & Roadmap

**Date**: 2026-09-17  
**Priority Focus**: App Menu / Overview Opening & High-DPI Scale Adjustments

---

## 1. Top Priority: App Menu (Overview) & High-DPI Scaling

### Task 1.1: Fix High-DPI Scaling (Enlarge UI Elements & Fonts)
**Observation**: UI elements, text, and icons on the OnePlus 13R (1264x2780 physical resolution, ~450 DPI) currently render too small for comfortable touch interaction.

**Technical Root Causes & Solutions**:
1. **Compositor Scale**:
   - In `/usr/local/share/phosh/phoc.ini`:
     Currently configured:
     ```ini
     [output:X11-1]
     mode = 1264x2780
     scale = 2
     ```
   - At `scale = 2`, the logical resolution is `632 x 1390`. On a 6.78-inch display, this produces relatively small touch targets.
   - **Action**: Change `scale` to `2.5` or `3` in `phoc.ini`:
     ```ini
     [output:X11-1]
     mode = 1264x2780
     scale = 2.5
     ```
     At `scale = 2.5`, logical resolution becomes `505 x 1112`, providing larger, mobile-optimized buttons, top bar, and text.
2. **GTK Font & Text Scaling**:
   - Apply text scaling factor in GNOME settings:
     ```bash
     gsettings set org.gnome.desktop.interface text-scaling-factor 1.25
     gsettings set org.gnome.desktop.interface font-name 'Sans 13'
     gsettings set org.gnome.desktop.interface document-font-name 'Sans 13'
     gsettings set org.gnome.desktop.interface monospace-font-name 'Monospace 13'
     ```
3. **Cursor Size**:
   - Adjust cursor size to match the scaled UI:
     ```bash
     gsettings set org.gnome.desktop.interface cursor-size 48
     ```

---

### Task 1.2: App Menu / Overview Opening & App Grid Access
**Observation**: The user noted that the app menu / app launcher grid is not appearing.

**How Phosh App Launcher Works**:
1. **The Home Bar**:
   - In Phosh, the bottom edge contains the "Home Bar" widget (`phosh-home`).
   - Tapping or swiping UP on the bottom home bar unfolds the Overview and displays the App Grid with all application icons.
2. **Why It May Be Obscured by Default on Termux:X11**:
   - The Android system navigation bar (Back, Home, Recents buttons) sits at the bottom (`y = 2646` to `y = 2780`).
   - If Termux:X11 is not running in **Immersive Fullscreen Mode**, touches near the bottom edge are captured by Android's navigation bar rather than being routed to X11.
3. **Solution & Actions**:
   - **Option A (User Action - Recommended)**:
     In Termux:X11 Settings (opened by clicking the `X11 Settings` option or gear icon in the Termux:X11 drawer):
     - Toggle **Fullscreen: Toggle immersive mode in your device display** to **ON**.
     - Toggle **Hide display cutout** to **ON**.
     This completely hides Android's navigation bar while in X11, exposing Phosh's home bar directly to user taps and upward swipes.
   - **Option B (Keyboard / Shortcut Trigger)**:
     Phosh registers `<Super>` (Windows / Meta key) or `<Super>a` to open the app grid:
     ```bash
     # In guest, test toggling overview via D-Bus directly:
     gdbus call --session --dest sm.puri.Phosh --object-path /sm/puri/Phosh --method sm.puri.Phosh.SetOverview true
     ```
   - **Option C (App Filter Verification)**:
     Ensure the adaptive app filter remains cleared so standard Linux apps (`xfce4-terminal`, `thunar`, etc.) are shown:
     ```bash
     gsettings set sm.puri.phosh app-filter-mode '[]'
     ```

---

## 2. Phase C: Service Pruning & Resource Cleanup (Steps 10–11) [COMPLETED]

1. **Process Inspection**:
   - Live session process tree verified across OnePlus 13R and Poco X6 Pro.
   - Identified and eradicated orphaned `glycin-svg`/`glycin-image-rs` processes.
   - Suppressed `at-spi2-registryd` and `at-spi-bus-launcher` via `export NO_AT_BRIDGE=1` and `toolkit-accessibility false`.
2. **Service Verification**:
   - Clean session runs strictly with:
     - `termux-x11`
     - `dbus-daemon`
     - `phoc`
     - `phosh`
     - `pulseaudio`
     - `virgl_test_server_android`
   - Verified zero background loops for NetworkManager, ModemManager, and UPower.
3. **Clean Teardown**:
   - Updated `stop_gui.sh` with explicit iteration across all Phosh/Phoc/wvkbd/glycin/at-spi binaries.
   - Verified 0 residual processes remain upon session stop on both devices.
4. **Wallpaper Provisioning**:
   - Provisioned official GNOME 4K Adwaita dark (`adwaita-d.jpg`) and light (`adwaita-l.jpg`) wallpapers to eliminate black background.
   - Configured `picture-options 'zoom'` permanently in `packages/phosh-lite/install.sh`, `nativecode-desktop`, and `00_mobi.Phosh.gschema.override`.

---

## 3. Phase D: OnePlus 13R Full Qualification (Step 12)

Run a rapid smoke cycle on the OnePlus 13R:
1. **Launches & Restarts**: Restart session 10 times via `nativecode-desktop phosh` to verify clean startup and teardown.
2. **Touch & Gestures**:
   - Swipe down top bar -> quick settings shade opens and closes.
   - Swipe up bottom bar -> app launcher grid opens.
   - Tap application icon -> launches `xfce4-terminal`.
3. **On-Screen Keyboard (`wvkbd`)**:
   - Focus a text field or open terminal.
   - Verify `wvkbd` pops up, registers touch keys, and dismisses when unfocused.
4. **Orientation & Lifecycle**:
   - Rotate device 90 degrees and back.
   - Put NativeCode into background (switch to Android home), return to app, verify Termux:X11 reconnects and resumes rendering without crashing.

---

## 4. Phase E: Deploy to Poco X6 Pro (Mali G615) (Steps 13–16)

**Target Device**: `Y5WWBMJVOZSK4HU8` (Poco X6 Pro, MediaTek Dimensity 8300 Ultra, Mali-G615 MC6, `/dev/mali0`).

1. **Package Deployment**:
   - Copy proven compiled binaries from the OnePlus 13R:
     - `/usr/local/bin/phoc`
     - `/usr/local/bin/phosh` (and `/usr/local/libexec/phosh`)
     - `/usr/local/bin/wvkbd`
     - `/usr/local/bin/nativecode-desktop`
     - `/usr/local/lib64/libwlroots.so.12`
     - `/usr/local/lib64/libncshims.so`
     - `/usr/local/lib64/libgudev-1.0.so.0.3.0`
     - `/usr/local/lib64/libgmobile.so.0`
     - `/usr/local/lib64/libhandy-1.so.0`
     - `/usr/share/icons/Adwaita/`
     - `/usr/share/glib-2.0/schemas/`
   - Target container: Alpaquita rootfs on the Poco X6 Pro.
2. **Tier 0 Qualification**:
   - Launch with `WLR_RENDERER=pixman` on G615.
   - Confirm identical 60 FPS mobile UI.
3. **Tier 1 Qualification**:
   - Test Phoc with Zink over PanVK hardware acceleration on `/dev/mali0`.
   - Verify stability and input response.

---

## 5. Phase F: Venus Broker Fallback Qualification (Step 17)

1. Verify `virgl_test_server_android --venus` socket at `/tmp/.virgl_test`.
2. Probe Venus Vulkan and Zink over Venus as the vendor-independent safety path.

---

## 6. Phases G & H: Freeze Artifacts & Rootfs Staging (Steps 18–24)

1. **Freeze Package Closure**:
   - Record exact SHA256 hashes of all locally built artifacts (`phoc`, `phosh`, `wvkbd`, `libwlroots`, `libncshims`, `libhandy`, `libgudev`, `gmobile`).
   - Create frozen manifests in `tools/rootfs/manifests/`:
     - `phosh-lite-packages.txt`
     - `phosh-lite-files.txt`
     - `desktop-runtime-sha256.txt`
2. **Create Staging Rootfs**:
   - Duplicate current canonical rootfs to `rootfs-phosh-staging`.
   - Inspect package dependencies to ensure shared libraries needed by Phosh remain.
   - Remove XFCE-only packages:
     - `xfce4-session`
     - `xfwm4`
     - `xfdesktop`
     - `xfce4-panel`
     - `xfconf`
     - unused XFCE plugins and panel applets.
   - Inject the frozen Phosh-Lite artifacts, schemas, icons, and `nativecode-desktop` launcher.

---

## 7. Phases I–K: Rebuild Canonical Archive & Fresh Verification (Steps 25–35)

1. Rebuild canonical `alpaquita_rootfs.tar.zst` using `tools/rootfs/build_alpaquita_rootfs.sh`.
2. Compute new SHA256 and update reference in `nativecode_runtime/src/main/assets/alpaquita_rootfs.tar.zst.sha256`.
3. **Clean Installation Gates**:
   - Perform fresh extraction and first-launch test on OnePlus 13R.
   - Perform fresh extraction and first-launch test on Poco X6 Pro.
   - Verify that neither device requires any manual package installation or patching on fresh setup.
   - Confirm complete removal of XFCE from the final release image.

---

## 8. Summary of Action Items for User (From User Side)

1. **Termux:X11 Fullscreen Setting**:
   - In Termux:X11 settings on the phone, enable **Fullscreen (Immersive Mode)** and **Hide display cutout**. This ensures the bottom Android system bar does not block the Phosh home bar.
2. **Reviewing Display Scale Preference**:
   - Once we apply `scale = 2.5` and `text-scaling-factor 1.25`, test if UI elements and icons match your visual preference on the OnePlus 13R.
