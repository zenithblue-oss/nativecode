# GPU Drivers Status & Agent Handoff (Mali & Adreno)

**Date**: 2026-09-16  
**Target Devices**:
1. **OnePlus 13R (`d30a1726`)**: Snapdragon 8 Gen 3 (`SM8650`), Adreno 750, Kernel 6.1, `/dev/kgsl-3d0`
2. **Poco X6 Pro (`Y5WWBMJVOZSK4HU8`)**: MediaTek Dimensity 8300 Ultra (`MT6897`), Mali-G615 MC6 (v11 CSF), Kernel 6.1, `/dev/mali0`

---

## 1. Executive Summary

| GPU Component | OnePlus 13R (Adreno 750) | Poco X6 Pro (Mali-G615) | Notes |
| :--- | :--- | :--- | :--- |
| **Vulkan** | **WORKING**: Turnip Vulkan 1.4 (`Mesa 26.3.0-devel`, git-`98f3d6229d`) | **WORKING**: PanVK Vulkan 1.4 (`libvulkan_panfrost_kbase.so` + `kbase_shim.so`) | Both run directly on hardware nodes (`/dev/kgsl-3d0`, `/dev/mali0`). Pass device creation, buffer mapping, memory write/readback, and 10/10 compute runs. |
| **OpenGL ES** | **WORKING**: Direct Freedreno GLES 3.2 (`FD750`, Mesa 26.3.0-devel) | **WORKING**: Zink over PanVK GLES 3.1 (`zink Vulkan 1.4 Mali unknown 0xa8671030 MC6`) | Native GLES contexts created and verified via surfaceless EGL pbuffers. |
| **Desktop OpenGL** | **Capped at GLES 3.2** (EGL configs expose only `EGL_OPENGL_ES_BIT`; desktop core GL requires rebuild or Zink) | **GLES 3.1 via Zink** (direct Gallium Panfrost on v11 CSF remains experimental stub) | See Section 2 for detailed root cause and exact fix steps. |
| **Venus (VirtIO)** | Server starts; socket `/tmp/.virgl_test` active | Server starts; socket `/tmp/.virgl_test` active | Hardware paths (`turnip` and `panvk-zink`) take priority over Venus fallback. |

---

## 2. Analysis of User Issues & Screenshots

### Issue 1: "panvk not working on mali all" (Screenshot 1)
**User Image Evidence**:
> *"panvk - not present anywhere (/system, /vendor contain zero Panfrost/panvk blobs). Android on this SoC uses ARM's proprietary Valkoff driver (vulkan.mali.so + libGLES_mali.so)... So the ceiling here is Vulkan 1.3 via the ARM blob - no 1.4, no raytracing beyond the software KHR RT path, no panvk."*

**Diagnosis & Reality**:
- **Why this was reported**: The agent/tool inside the terminal checked the **Android host system partitions** (`/system` and `/vendor`). Android host indeed only ships ARM's proprietary closed-source driver (Valkoff r44p1 Vulkan 1.3).
- **Where PanVK actually lives**: PanVK in NativeCode is **NOT** an Android system HAL driver; it is a **guest userspace driver** compiled into the PRoot Linux filesystem (`/opt/nativecode/panvk/lib/libvulkan_panfrost_kbase.so`). It speaks directly to `/dev/mali0` using the kernel Kbase UAPI 1.21 via `kbase_shim.so`.
- **Actual Runtime Status**:
  Running the guest loader test via `gpu-launch --panvk /opt/nativecode/panvk/bin/vulkan_probe` and `gpu-launch --panvk /opt/nativecode/panvk/bin/vulkan_compute_test` proves:
  ```text
  Device Name:   Mali unknown 0xa8671030 MC6
  Vendor ID:     0x13b5 (ARM)
  Device ID:     0xa8671030
  API Version:   1.4.352
  Compute Acceptance Result: 10/10 runs passed (0x12345678)
  ```
  And Zink over PanVK initializes:
  ```text
  GL_RENDERER: zink Vulkan 1.4(Mali unknown 0xa8671030 MC6 (MESA_PANVK))
  GL_VERSION:  OpenGL ES 3.1 Mesa 26.1.6
  ```
- **Action for Next Agent**: Ensure default guest environment automatically sets `VK_DRIVER_FILES=/opt/nativecode/panvk/icd.d/panvk_kbase.json` and `LD_PRELOAD=/opt/nativecode/panvk/lib/kbase_shim.so` (e.g., via `/etc/profile.d/gpu.sh`) so commands like `vulkaninfo` and `fastfetch` resolve PanVK out-of-the-box without requiring the `gpu-launch --panvk` wrapper.

---

### Issue 2: "in adreno turnip is working but freedreno is capped at 3.2, so no 4.6" (Screenshot 2)
**User Image Evidence**:
> *"Every one of the 135 EGL configs advertises only EGL_OPENGL_ES*, none advertise EGL_OPENGL_BIT (0x08) - so GL contexts aren't even creatable here... Zink also fails: failed to create dri2 screen. So: no, GL 4.6 is not available in this environment - this Mesa build (26.3.0-devel, the Android/KgSL-flavored one) only exposes the GLES driver."*

**Diagnosis & Root Cause**:
1. **Meson Build Flags**:
   When building LFDev Mesa 26.3 on the OnePlus 13R, the flags specified:
   ```bash
   -Dgles1=disabled -Dgles2=enabled -Degl=enabled -Dglvnd=disabled -Dglx=dri
   ```
   In Mesa, when `-Degl=enabled` is configured without explicitly requesting desktop OpenGL API support in EGL (or when the build defaults to embedded/GLES profile), EGL surfaces and configs only register `EGL_OPENGL_ES_BIT` / `EGL_OPENGL_ES2_BIT` / `EGL_OPENGL_ES3_BIT`. None register `EGL_OPENGL_BIT` (desktop OpenGL).
2. **Freedreno Hardware Capability**:
   The hardware (Adreno 750 / a7xx) and Mesa's `freedreno` Gallium driver **fully support Desktop OpenGL 4.5 / 4.6**. The restriction is purely in the EGL build configuration that was compiled on the device.
3. **Zink over Turnip**:
   Turnip provides Vulkan 1.4 on Adreno 750. Zink translates full Desktop OpenGL 4.6 onto Turnip. However, running Zink directly against a DRI2/DRM screen fails inside PRoot because `/dev/dri` does not exist on Android KGSL devices. Zink requires either:
   - `MESA_VK_WSI_DEBUG=sw` + `DISPLAY=:0` (Termux:X11 display), or
   - Surfaceless platform with software WSI (`EGL_PLATFORM=surfaceless`).

---

## 3. What Was Accomplished in This Session

1. **Diagnosed Root Cause of Driver Failure**:
   - Discovered that precompiled LFDev Debian packages require Debian GLVND and Debian glibc packages, which crashed on BellSoft Alpaquita Linux due to missing `libsensors.so.5` and DRI symbol mismatches.
   - Discovered that precompiled LFDev Alpine packages were linked against `libc.musl-aarch64.so.1`, incompatible with Alpaquita glibc.

2. **Natively Compiled LFDev Mesa 26.3 on OnePlus 13R**:
   - Set up build dependencies (`meson`, `samurai`, `llvm20-dev`, `py3-mako`, `glslang`, `xcb-util-keysyms-dev`, etc.) inside the OnePlus 13R's PRoot environment.
   - Cloned official `lfdevs/mesa-for-android-container` at commit `98f3d6229d61452cef80f8563af7c56ae599dc14` (tag `mesa-26.3.0-devel-20260824`).
   - Successfully compiled LFDev Mesa using all 8 cores of the Snapdragon 8 Gen 3:
     - `libvulkan_freedreno.so` (Turnip Vulkan 1.4)
     - `kgsl_dri.so` / `msm_dri.so` (Freedreno DRI drivers)
     - `libgallium-26.3.0-devel.so` (Freedreno Gallium core)
     - `libEGL.so.1` & `libGLESv2.so.2` (Mesa EGL/GLES)
     - `libGL.so.1` (Mesa OpenGL)
   - Tested loading all libraries via Python `ctypes` directly on the device: all returned **PASS**.
   - Verified direct Freedreno surfaceless context:
     ```text
     GL_RENDERER: FD750
     GL_VERSION:  OpenGL ES 3.2 Mesa 26.3.0-devel (git-98f3d6229d)
     GL_VENDOR:   freedreno
     ```

3. **Packaging & Distribution Pipeline**:
   - Packaged the native 13R-compiled bundle into `/opt/nativecode-turnip`.
   - Added runtime dependency packages into the Alpaquita rootfs (`llvm20-libs`, `xcb-util-keysyms`, `mesa-egl`, `mesa-gles`, `mesa-vulkan-virtio`).
   - Repacked `alpaquita_rootfs.tar.zst` using `zstd -T0 -19`.
   - Updated SHA-256 pins in:
     - `tools/rootfs/versions.env`
     - `nativecode_runtime/src/main/assets/alpaquita_rootfs.tar.zst.sha256`
     - `app/src/main/java/com/zenithblue/nativecode/onboarding/RootfsSource.kt`
   - Rebuilt full debug APK (`app-full-debug.apk`) using 12 host cores.
   - Pushed rootfs archive and installed fresh APK on both connected devices.

4. **Runtime Verification**:
   - **OnePlus 13R (`d30a1726`)**:
     - `Turnip Vulkan 1.4` PASS (`Turnip Adreno (TM) 750`, Driver `0x6802063`, API `1.4.359`)
     - `Freedreno GLES 3.2` PASS (`FD750`, OpenGL ES 3.2 Mesa 26.3.0-devel)
   - **Poco X6 Pro (`Y5WWBMJVOZSK4HU8`)**:
     - `PanVK Vulkan 1.4` PASS (`Mali unknown 0xa8671030 MC6`, API `1.4.352`)
     - `PanVK Compute` PASS (10/10 deterministic compute runs)
     - `Zink over PanVK GLES 3.1` PASS (`OpenGL ES 3.1 Mesa 26.1.6`)

---

## 4. Next Steps for Next Agent

### Priority 1: Unlock Desktop OpenGL 4.5 / 4.6 on Adreno 750
To expose desktop OpenGL (GL 4.5 / 4.6) instead of being restricted to GLES 3.2:
1. **Option A: Rebuild LFDev Mesa on OnePlus 13R with Desktop GL enabled in EGL**:
   - In `meson setup`, add `-Dopengl=true` (or remove `-Dgles1=disabled` if it suppresses desktop GL table in EGL).
   - Verify that `eglChooseConfig` with `EGL_RENDERABLE_TYPE` containing `EGL_OPENGL_BIT (0x08)` succeeds and returns valid configs.
2. **Option B: Zink over Turnip for Desktop GL 4.6**:
   - Turnip already provides full Vulkan 1.4 on the Adreno 750.
   - Ensure `MESA_LOADER_DRIVER_OVERRIDE=zink` and `GALLIUM_DRIVER=zink` use `MESA_VK_WSI_DEBUG=sw` and run against an X11 display (`DISPLAY=:0`) or headless surfaceless platform.
   - Zink exposes full Desktop OpenGL 4.6 over Turnip Vulkan.

### Priority 2: Automate PanVK Environment on Mali Device
1. Create `/etc/profile.d/gpu-env.sh` inside rootfs:
   ```bash
   if [ -e /dev/mali0 ] && [ ! -e /dev/kgsl-3d0 ]; then
       export VK_DRIVER_FILES="/opt/nativecode/panvk/icd.d/panvk_kbase.json"
       export LD_PRELOAD="/opt/nativecode/panvk/lib/kbase_shim.so${LD_PRELOAD:+:$LD_PRELOAD}"
   elif [ -e /dev/kgsl-3d0 ]; then
       export VK_DRIVER_FILES="/opt/nativecode-turnip/icd/freedreno_icd.aarch64.json"
   fi
   ```
2. Symlink or copy `panvk_kbase.json` and `freedreno_icd.aarch64.json` into `/usr/share/vulkan/icd.d/` so standard `vulkaninfo` finds the driver without manual flags.

### Priority 3: Complete Gallium Panfrost on v11 CSF (Mali-G615)
- PanVK + Zink currently provides hardware-accelerated Vulkan 1.4 and OpenGL ES 3.1.
- Direct Gallium Panfrost over Kbase for Valhall v10/v11 CSF is still missing full kernel submission/fencing logic in upstream Mesa. Keep Zink over PanVK as the primary OpenGL accelerator for Mali.

---

## 5. Key File Locations & Artifacts

- **Compiled Adreno Bundle**: `tools/rootfs/mesa-adreno/cache/nativecode-turnip-alpaquita.tar.gz` (SHA: `29576eeb1f7a0b2c1d96865d53b5922c7e6237309157664f9373aaab6fd0295f`)
- **Host Staged Adreno Bundle**: `tools/rootfs/mesa-adreno/out/nativecode-turnip/`
- **Canonical Rootfs Archive**: `tools/rootfs/out/alpaquita_rootfs.tar.zst` (SHA: `54f5bf6a26765d9f212751b7c55a86eb20d3fe4dc0f1f4dd5f97ee0a843cb680`)
- **Asset Rootfs Copy**: `nativecode_runtime/src/main/assets/alpaquita_rootfs.tar.zst`
- **Built APK**: `app/build/outputs/apk/full/debug/app-full-debug.apk`
- **GPU Backend Script**: `tools/rootfs/gpu-backend.sh` and `tools/rootfs/overlay/alpaquita/usr/local/lib/nativecode/gpu-backend.sh`
- **Guest Launcher**: `tools/rootfs/overlay/alpaquita/usr/local/bin/gpu-launch`
