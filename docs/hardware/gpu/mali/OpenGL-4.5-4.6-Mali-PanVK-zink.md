# OpenGL 4.5 / 4.6 on this device — UNAVAILABLE (measured, not assumed)

## Result

Every desktop-GL 4.x context request fails with `EGL_BAD_ALLOC` (0x3009) —
core, compat, and forward-compatible. (No EGL config on this path advertises
`EGL_OPENGL_BIT` at all — `eglChooseConfig` with that bit returns 0 configs —
so all desktop-GL contexts are created configless via
`EGL_MESA_configless_context`; with-config vs configless runs were identical.):

```
req GL 3.0         -> GOT [3.3 (Compatibility Profile) Mesa 26.1.6]
req GL 3.1         -> GOT [3.3 (Core Profile) Mesa 26.1.6]
req GL 3.2 core    -> GOT [3.3 (Core Profile) Mesa 26.1.6]
req GL 3.3 core    -> GOT [3.3 (Core Profile) Mesa 26.1.6]
req GL 4.0 core    -> FAIL 0x3009 (EGL_BAD_ALLOC)
req GL 4.1 core    -> FAIL 0x3009
req GL 4.2 core    -> FAIL 0x3009
req GL 4.3 core    -> FAIL 0x3009
req GL 4.4 core    -> FAIL 0x3009
req GL 4.5 core    -> FAIL 0x3009
req GL 4.6 core    -> FAIL 0x3009
req GL 4.6 compat  -> FAIL 0x3009
req ES 2.0/3.0/3.1 -> GOT [OpenGL ES 3.1 Mesa 26.1.6]
req ES 3.2         -> FAIL 0x3009
```

So there is **no on-device 4.5/4.6 extension or limit set to report**.
Fabricating one (e.g. via `MESA_GL_VERSION_OVERRIDE=4.6`) would not be
device-accurate, so it was deliberately not done.

## Why: the Vulkan feature trace explains the cap

Zink needs geometry+tessellation shader stages for GL 4.0+ (and ES 3.2).
This PanVK driver reports, on this unrecognised GPU
(conservative Valhall defaults):

```
  geometryShader      no
  tessellationShader  no
  shaderFloat64       no
  shaderClipDistance  no
  shaderCullDistance  no
  multiViewport       no
  sparseBinding       no  (+ all sparse residency: no)
```

Hence max desktop GL = 3.3 (core + compat) and max GLES = 3.1.
GL 3.3 data: `OpenGL-3.2-Mali-PanVK-zink.md` + `/tmp/gl33core.txt`.

## Difficulties getting these values (as requested)

1. No `vulkaninfo`/`glxinfo`/`eglinfo`, no Vulkan headers: fetched
   Vulkan-Headers v1.4.352 from GitHub and wrote C probes with dlopen.
2. First probes segfaulted: hand-written `VkPhysicalDeviceVulkan12/14Features`
   layouts were wrong (missing `shaderFloat16`/`shaderInt8`; the 1.4 struct
   has 22 different fields in v1.4.352). Fixed by compiling against the
   real headers (`/tmp/vkhdr`).
3. GPU unrecognised by PanVK (`unknown gpu_id 0xa8671030`), so everything
   runs on conservative defaults — the values in these files are specific
   to that fallback path, not to a fully-enabled G6xx/G7xx.
4. No native GL driver (kbase only, no panfrost/panthor DRM): GL only works
   via Zink-on-PanVK (`GALLIUM_DRIVER=zink`, mesa 26.1.6 dri `zink_dri.so`).
5. EGL surfaceless first fell back to swrast (`DRI2: failed to load driver`);
   with `GALLIUM_DRIVER=zink` the pipe driver is still Zink (renderer string
   proves it). No EGL config advertises `EGL_OPENGL_BIT`, so desktop GL
   needs configless contexts (`EGL_MESA_configless_context`); GLES works
   with ordinary configs.
6. Early probe builds kept failing on GL enum typos — fixed by reading values
   from the installed `/usr/include/GL/glcorearb.h` instead of memory.

---

## Instructions for Running OpenGL on Mali (Poco X6 Pro / Dimensity 8300)

### 1. Hardware Accelerated OpenGL via Zink over PanVK (Recommended)
To run desktop OpenGL applications up to OpenGL 3.3 Core Profile and OpenGL ES 3.1:
```bash
# Sourced automatically via /etc/profile.d/gpu-env.sh
export VK_DRIVER_FILES="/opt/nativecode/panvk/icd.d/panvk_kbase.json"
export VK_ICD_FILENAMES="$VK_DRIVER_FILES"
export LD_PRELOAD="/opt/nativecode/panvk/lib/kbase_shim.so${LD_PRELOAD:+:$LD_PRELOAD}"
export WSI_X11_TERMUX=1
export PANVK_KBASE_DRI3=raw
export MESA_VK_WSI_DEBUG=sw
export GALLIUM_DRIVER=zink
export MESA_LOADER_DRIVER_OVERRIDE=zink

# Or run via NativeCode launcher:
gpu-launch --panvk-zink <command>
```

### 2. Overriding GL Version for 4.x Compatible Apps
If an application or game strictly queries for OpenGL 4.5/4.6 and does not use unsupported geometry/tessellation stages:
```bash
export MESA_GL_VERSION_OVERRIDE=4.6
export MESA_GLSL_VERSION_OVERRIDE=460
gpu-launch --panvk-zink <command>
```
