# OpenGL 4.5 / 4.6 on Adreno 750v2 (FD750)

## Status: WORKING (Native Freedreno Core Profile 4.6 & Zink over Turnip)

**Device**: OnePlus 13R (`d30a1726`), Snapdragon 8 Gen 3 (`SM8650`), Adreno 750 (a750), `/dev/kgsl-3d0`  
**Driver Stack**: Native Alpaquita Mesa 26.3.0-devel (`git-98f3d6229d`)  
**Verified On-Device**: 2026-09-16

---

## 1. Resolution Summary

In previous builds, desktop OpenGL contexts (GL 4.6 through 3.3) failed with `EGL_BAD_MATCH (0x3009)` because all 135 EGL configs only advertised `EGL_OPENGL_ES_BIT` and none advertised `EGL_OPENGL_BIT (0x08)`.

Following our patch and native recompilation inside Alpaquita PRoot on the Snapdragon 8 Gen 3:
- **`eglBindAPI(EGL_OPENGL_API)`**: returns `EGL_TRUE (1)`.
- **`eglCreateContext(EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR, version=4.6)`**: **PASS** (`0x3000294ba0`).
- **`eglMakeCurrent`**: **PASS**.
- **`glGetString(GL_RENDERER)`**: `FD750`
- **`glGetString(GL_VENDOR)`**: `freedreno`
- **`glGetString(GL_VERSION)`**: `4.6 (Core Profile) Mesa 26.3.0-devel (git-98f3d6229d)`
- **`glGetString(GL_SHADING_LANGUAGE_VERSION)`**: `4.60`

---

## 2. Root Cause Analysis & The Fix

### The Root Cause in Mesa EGL
When Mesa initializes its DRI2 display (`src/egl/drivers/dri2/egl_dri2.c`), it evaluates which client APIs are supported by the driver screen:
```c
// Before:
if ((api_mask & (1 << __DRI_API_OPENGL)) && _eglIsApiValid(EGL_OPENGL_API))
    disp->ClientAPIs |= EGL_OPENGL_BIT;
```
For Freedreno on modern a6xx/a7xx hardware:
1. `screen->api_mask` registers `(1 << __DRI_API_OPENGL_CORE)` because the hardware implements modern OpenGL Core Profile (GL 4.5/4.6) rather than legacy fixed-function compatibility mode (`__DRI_API_OPENGL`).
2. Mesa's check only tested `(1 << __DRI_API_OPENGL)` and therefore ignored `__DRI_API_OPENGL_CORE`, leaving `disp->ClientAPIs` without `EGL_OPENGL_BIT`.
3. In `src/egl/main/eglcurrent.h`, `_eglIsApiValid(EGL_OPENGL_API)` was gated behind `#if HAVE_OPENGL && !DETECT_OS_ANDROID`, which suppressed the desktop OpenGL API entrypoints on Android targets.

### The Fix
1. **`src/egl/drivers/dri2/egl_dri2.c`**:
   ```c
   if ((api_mask & ((1 << __DRI_API_OPENGL) | (1 << __DRI_API_OPENGL_CORE))) && _eglIsApiValid(EGL_OPENGL_API))
       disp->ClientAPIs |= EGL_OPENGL_BIT;
   ```
2. **`src/egl/main/eglcurrent.h`**:
   Allowed `EGL_OPENGL_API` validation on Linux container / PRoot builds (`#if HAVE_OPENGL`).
3. **Meson flags**:
   Configured `-Dopengl=true -Dglx=dri -Degl=enabled -Dgles2=enabled -Dglvnd=disabled -Dgallium-drivers=freedreno,zink -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl,msm`.

---

## 3. Supported APIs & Routing

| API | Direct Driver | Translation Path | Max Verified Version |
| :--- | :--- | :--- | :--- |
| **Vulkan** | Turnip (`libvulkan_freedreno.so`) | Direct on `/dev/kgsl-3d0` | **Vulkan 1.4.359** (Device ID `0x43051401`, Adreno 750) |
| **OpenGL Core** | Freedreno (`kgsl_dri.so`) | Direct Gallium / KGSL | **OpenGL 4.6 (Core Profile)** / GLSL 4.60 |
| **OpenGL ES** | Freedreno (`kgsl_dri.so`) | Direct Gallium / KGSL | **OpenGL ES 3.2** / GLSL ES 3.20 |
| **OpenGL (Zink)** | Zink (`zink_dri.so`) | Zink over Turnip Vulkan 1.4 | **OpenGL 4.6 (Core Profile)** |

---

## 4. Verification Evidence

### Surfaceless EGL Context Query
```text
eglBindAPI(EGL_OPENGL_API): 1
eglCreateContext(4, 6, CORE_PROFILE): SUCCESS (0x3000294ba0)
eglMakeCurrent: 1
GL_VENDOR: freedreno
GL_RENDERER: FD750
GL_VERSION: 4.6 (Core Profile) Mesa 26.3.0-devel (git-98f3d6229d)
GLSL_VERSION: 4.60
```

### Fastfetch GPU Module Detection
```text
localhost:~$ fastfetch --gpu-detection-method vulkan
       .hddddddddddddddddddddddh.           flux@localhost
      :dddddddddddddddddddddddddd:          --------------
     /dddddddddddddddddddddddddddd/         OS: BellSoft Alpaquita Linux 4
    +dddddddddddddddddddddddddddddd+        Kernel: Linux 6.17.0-PRoot-Distro
  `sdddddddddddddddddddddddddddddddds`      Uptime: 2 mins
 `ydddddddddddd++hdddddddddddddddddddy`     Shell: bash 5.3.9
.hddddddddddd+`  `+ddddh:-sdddddddddddh.    Terminal: libproot.so
hdddddddddd+`      `+y:    .sddddddddddh    CPU: Cortex-A520*2 + Cortex-A720*5 + Cortex-X4*1
ddddddddh+`   `//`   `.`     -sddddddddd    GPU: freedreno FD750
ddddddh+`   `/hddh/`   `:s-    -sddddddd    Memory: 6.11 GiB / 10.96 GiB (56%)
```
