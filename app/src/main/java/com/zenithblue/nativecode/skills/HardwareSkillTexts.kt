package com.zenithblue.nativecode.skills

/** Generated from docs/hardware. Do not edit by hand. */
internal object HardwareSkillTexts {
    val files: List<Pair<String, String>> = listOf(
        "agent-bridge.md" to """
# NativeCode Host Agent IPC Bridge

## Overview
The Agent Bridge allows coding agents, background tasks, and guest CLI scripts in PRoot Linux to interact with the Android host system. Supported capabilities include:
- In-app alert dialogs to open web preview links in the default Android browser (ideal for local web servers like `localhost:3000`).
- Play-safe APK install/export alert cards for on-device app testing when no ADB devices are attached.
- System notifications with custom title, message, priority, and actions.
- Android system toasts.
- Persistent foreground task status and progress bars.
- Real-time battery, thermal, and memory telemetry.

---

## Security & Transport
- **Socket:** `AF_UNIX` Linux abstract namespace: `@nativecode.agent.v1` (with fallback to `@nativecode.agent.v1.<uid>` when another NativeCode build already owns the plain name; see the rendezvous file below).
- **Authentication:** Kernel-level peer credentials (`SO_PEERCRED`). The server verifies `peer.uid == Process.myUid()`. Sockets reject any unauthorized user IDs or host processes.
- **Framing:** 4-byte big-endian length prefix preceding UTF-8 JSON payloads (max 64 KiB).
- **Rate Limiting:** In-memory Token Bucket rate limiters protect the Android UI from floods (5 notifications/sec max, 1 toast / 2 sec max, 1 alert dialog / sec max).

---

## CLI Tool: `nativecode-agent`

Prepackaged at `/usr/local/bin/nativecode-agent`.

### 1. Ping / Healthcheck
```bash
nativecode-agent ping
```
Output:
```json
{
  "v": 1,
  "ok": true,
  "host_uid": 10322,
  "client_id": "agent-cli",
  "ts": 1789368979496
}
```

### 2. Dispatch Android Notification
```bash
nativecode-agent notify \
  --title "Build Finished" \
  --message "Cargo build succeeded in 8.4s" \
  --priority high
```
- `--title`: Notification title text.
- `--message`: Primary body text.
- `--priority`: `high`, `default`, or `low`.
- `--id`: Optional notification ID (default: 1001).
- `--ongoing`: Set non-dismissible ongoing notification (bare flag `--ongoing` or `--ongoing true|false`).

### 3. Display Android Toast
```bash
nativecode-agent toast --text "Indexing repository..."
```

### 4. Query Host Device Telemetry
```bash
nativecode-agent telemetry
```
Output:
```json
{
  "v": 1,
  "ok": true,
  "battery_percent": 81,
  "is_charging": false,
  "thermal_status": "NONE",
  "avail_mem_mb": 3412,
  "total_mem_mb": 7331,
  "low_memory": false,
  "telemetry": {
    "battery": {
      "level": 81,
      "charging": false,
      "temperature_c": 32.4
    },
    "memory": {
      "avail_mb": 3412,
      "total_mb": 7331,
      "low_memory": false
    },
    "power": {
      "power_save_mode": false,
      "interactive": true
    },
    "thermal": {
      "status": "NONE"
    }
  }
}
```
*Note: Flat keys (`battery_percent`, `avail_mem_mb`, etc.) and the structured `telemetry` object are both populated for compatibility.*

### 5. Task Progress & Status
```bash
nativecode-agent status \
  --key "hyperframes_render" \
  --text "Rendering frame 450/900" \
  --progress 50
```
Updates the persistent status indicator in the notification drawer with progress (0-100%).

### 6. Open Web Link / Preview
```bash
nativecode-agent open-link \
  --url "http://localhost:3000" \
  --title "Web Preview" \
  --message "Vite server listening on port 3000"
```
- Displays an in-app Cyber-brutalist alert card with translucent dimming over the active workspace/terminal, with direct "OPEN BROWSER", "COPY", and "DISMISS" actions.
- **Single Active Alert Rule:** Exactly one on-screen alert (link preview or APK install) may be active at any time. If an alert is already displayed, subsequent calls return:
  ```json
  {
    "v": 1,
    "ok": false,
    "error": "alert_already_active",
    "message": "An alert is already active on screen. Dismiss it first with 'nativecode-agent dismiss-alert' or wait for the user to close it."
  }
  ```
- If the host app is running in the background, posts a notification that opens the link directly in the Android browser.
- Alias: `nativecode-agent open-url`

### 7. Install / Export APK (On-Device Testing)
```bash
nativecode-agent install-apk \
  --path "app/build/outputs/apk/debug/app-debug.apk" \
  --title "Install APK" \
  --message "Gradle assembleDebug completed successfully"
```
- Supported paths:
  - Guest relative and absolute paths (`app/build/...apk`, `/home/flux/app.apk`).
  - Guest temporary directory (`/tmp/...apk`, mapped to host shared tmp).
  - External storage (`/sdcard/...`, `/storage/emulated/0/...`).
- Validates file exists, non-empty (>100 bytes), and has a valid Android package manifest.
- Subject to the **Single Active Alert Rule** (returns `alert_already_active` if another alert is already on screen).
- Displays an in-app Cyber-brutalist card:
  - **SAVE & OPEN:** Follows Play Store policy guidelines by staging to MediaStore `Downloads` and opening Downloads for native user-initiated installation (no ADB required).
  - **SHARE:** Opens Android system share sheet.
  - **DISMISS:** Closes alert.
- Alias: `nativecode-agent open-apk`

### 8. Dismiss / Cancel On-Screen Alert
```bash
nativecode-agent dismiss-alert
```
- Remotely dismisses any active on-screen alert card (web preview or APK install) without user touch.
- Output:
  ```json
  {
    "v": 1,
    "ok": true,
    "dismissed": true
  }
  ```
- If no alert is currently showing:
  ```json
  {
    "v": 1,
    "ok": true,
    "dismissed": false,
    "message": "No active alert on screen to dismiss"
  }
  ```
- Aliases: `nativecode-agent cancel-alert`, `nativecode-agent dismiss`

### 9. Share a File
```bash
nativecode-agent share-file out/report.pdf --title "Report" --message "Build output"
```
- Positional `PATH` or `--path`. Alias: `nativecode-agent send-file`.
- Started from a chat (env `NC_CHAT_THREAD`): a share card appears in that chat thread; images, video, audio and PDF open in the in-app viewer. Started from a terminal: posts a notification instead.
- Output has `"chat_card": true` or `"notified": true`.

## Finding the socket (rendezvous)
Play and F-Droid builds can run side by side and share the abstract namespace: the first app binds `@nativecode.agent.v1` / `@nativecode.media.v1`, the other binds the `.<uid>` names. Each app writes `/run/nativecode_bridge.json` in its own guest:
```json
{ "v": 1, "host_uid": 10322, "agent_socket": "@nativecode.agent.v1.10322", "media_socket": "@nativecode.media.v1.10322" }
```
`nativecode-agent` and `nativecode-media` read it first and fall back to the plain names. Settings -> HARDWARE shows the bound names, live state and whether this file is current.

## Tools MCP (chat)
Chats get a loopback MCP server (`127.0.0.1:<port>/mcp`, bearer token per chat thread) with `nc_*` orchestration tools and `browser_*` tools for the in-app browser.
""",
        "gpu-accel.md" to """
# NativeCode GPU acceleration

Guest Linux GL/Vulkan uses baked Mesa. The Hardware page never executes GPU setup scripts.

## Driver packages

| GPU | Driver | Play | F-Droid |
|---|---|---|---|
| Mali (`/dev/mali0`) | Mesa PanVK 26.3.1 (`/opt/nativecode/panvk`) | bundled asset | GitHub Releases, size + sha256 checked, after the download consent |
| Adreno (KGSL) | LFDev Turnip 27.0.0-devel-20261025-r2 (`/opt/nativecode-turnip`) | bundled asset | same |

Settings -> HARDWARE -> GPU DRIVER shows installed vs shipped sha256 (UP TO DATE / UPDATE / NOT INSTALLED / N/A). An update is applied from Settings -> Components (stop the desktop first; the old .so is kept as `.so.bak`).

## Backends

| Host GPU | Method | Path |
|---|---|---|
| Snapdragon / Adreno / KGSL | **Turnip KGSL (Zink)** | Guest Turnip ICD → `/dev/kgsl-3d0` |
| Mali (/dev/mali0 direct) | **PanVK (Zink over PanVK)** | Guest PanVK ICD → `/dev/mali0` |
| Mali (no direct access) / Xclipse / PowerVR / other | **Venus** (Zink over Venus for OpenGL) | Guest virtio ICD → vtest socket → `virgl_test_server_android --venus` → Android `libvulkan.so` |
| Venus missing / Zink fail | **VirGL (virpipe)** | Guest virpipe → host vtest (no `--venus`) |
| Nothing else | **llvmpipe (software)** | CPU |

Runtime choice is guest `gpu-launch --auto` (functional probes). Host `GpuAccelDetector` is an install hint only.

## Host servers (Hardware page)

Settings → HARDWARE → HOST SERVERS. Start/stop/restart run the host binaries as the **app user** (FluxLinux style). Venus is optional (not bundled in F-Droid builds; the page then says NOT BUNDLED).

| Server | Binary | How |
|---|---|---|
| Venus | `usr/bin/virgl_test_server_android --venus --socket-path ${'$'}PREFIX/tmp/.virgl_test` | Guest virtio ICD talks to this socket |
| PulseAudio | `usr/bin/pulseaudio --start --load=module-native-protocol-tcp ... --exit-idle-time=-1` | Guest `PULSE_SERVER=127.0.0.1` (Play, port 4713) or `127.0.0.1:<port>` (other builds, own port, AAudio sink) |

Both packages are built for `com.zenithblue.nativecode` (`scripts/build_nativecode_host_servers.sh`) and assembled into `bootstrap.tar`.

Host start (app uid, FluxLinux style):

```sh
# Venus (no PREFIX/lib in LD_LIBRARY_PATH)
LD_LIBRARY_PATH=${'$'}PREFIX/opt/virglrenderer-android/lib \
  /system/bin/linker64 ${'$'}PREFIX/bin/virgl_test_server_android \
  --socket-path ${'$'}PREFIX/tmp/.virgl_test --venus

# PulseAudio (termux-exec, no linker64 --start re-exec)
LD_PRELOAD=${'$'}PREFIX/lib/libtermux-exec-linker-ld-preload.so \
  ${'$'}PREFIX/bin/pulseaudio --daemonize=no --disallow-exit \
  --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1" \
  --exit-idle-time=-1
```

Guest Mesa virtio ICD talks to that socket (`VN_DEBUG=vtest`, `VTEST_SOCKET_NAME=/tmp/.virgl_test`). It does **not** use `/dev/dri/card0` or `renderD128`. `open(/dev/dri/card0) → EACCES` under PRoot is expected and is not the Venus path.

Login shells source baked `/etc/profile.d/flux-gpu.sh`: KGSL → Turnip, else Zink over Venus. The Android app does not run GPU setup scripts. Hardware → HOST SERVERS shows Venus only on Mali; Snapdragon is Turnip/KGSL only.

Host Venus requires `virgl_render_server` (linker64-exec'd from `libexec/`). Without it, `vkCreateInstance` can succeed while `vkEnumeratePhysicalDevices` returns 0.

## State files (guest)

```
/etc/fluxlinux/gpu_mode      turnip | panvk | venus | virgl | software
/etc/fluxlinux/gpu_vendor    adreno/snapdragon | mali | ...
/etc/fluxlinux/gpu_backend   turnip | panvk-zink | venus | zink-venus | virgl-angle | llvmpipe
/etc/fluxlinux/gpu_method    human label shown on Hardware page
```

## Hardware page

Settings → HARDWARE. Host OpenGL/Vulkan come from Android EGL (pbuffer) + `FEATURE_VULKAN_HARDWARE_VERSION`. Guest method comes from `/etc/fluxlinux/gpu_*`. No PRoot, no script exec.

| Field | Source |
|---|---|
| GPU name | `glGetString(GL_RENDERER)` e.g. Mali-G615 MC6 |
| OpenGL | `GL_VERSION` |
| GLSL | `GL_SHADING_LANGUAGE_VERSION` |
| Vulkan | packed `android.hardware.vulkan.version` |
| Method | configured `gpu_mode` (Turnip / Zink over Venus / VirGL) |

## Guest CLI

```
gpu-launch --diagnose
gpu-launch --auto <cmd>
gpu-launch --turnip <cmd>
gpu-launch --venus <cmd>
```

Shipped in the prebuilt rootfs (`/usr/local/bin/gpu-launch`). Not launched by the Android app.

## Play policy

- Play: drivers are bundled assets, no download of executable code
- No marketplace-style remote `install.sh` for GPU
- Hardware UI is Kotlin file/property reads only
- Venus host server starts only from the existing XFCE `start_gui.sh` path
""",
        "gpu/adreno/opengl_desktop_4_5_4_6.md" to """
# Desktop OpenGL 4.6 & 4.5 on Adreno 750 (FD750)

**Device**: OnePlus 13R (`d30a1726`), Snapdragon 8 Gen 3 (`SM8650`), Adreno 750, Kernel 6.1, `/dev/kgsl-3d0`  
**Driver**: `freedreno` `FD750` — `4.6 (Core Profile) Mesa 26.3.0-devel (git-98f3d6229d)`  
**GLSL Version**: `4.60`  
**Total Verified Extensions**: `226`  
**Status**: **FULLY SUPPORTED & VERIFIED** (Native Freedreno Core Profile 4.6 + Zink over Turnip Vulkan 1.4)  

---

## 1. Quick Start & Execution Instructions

### Option A: Native Freedreno OpenGL 4.6 (Direct on KGSL) — Recommended
Direct hardware acceleration with zero translation overhead. Works via EGL Core Profile (surfaceless or X11/Wayland).

```bash
# Environment variables (automatically loaded by /etc/profile.d/gpu-env.sh):
export GALLIUM_DRIVER=freedreno
export MESA_LOADER_DRIVER_OVERRIDE=kgsl
export LD_LIBRARY_PATH="/opt/nativecode-turnip/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
export LIBGL_DRIVERS_PATH="/opt/nativecode-turnip/lib/dri"

# Or run any command via the NativeCode launcher:
gpu-launch --turnip <command>
```

#### Creating an OpenGL 4.6 Core Profile Context in C / EGL:
```c
// Bind desktop OpenGL
eglBindAPI(EGL_OPENGL_API);

// Request 4.6 Core Profile (EGL_KHR_create_context)
const EGLint attribs[] = {
    EGL_CONTEXT_MAJOR_VERSION_KHR, 4,
    EGL_CONTEXT_MINOR_VERSION_KHR, 6,
    EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR,
    EGL_NONE
};
EGLContext ctx = eglCreateContext(display, config, EGL_NO_CONTEXT, attribs);
eglMakeCurrent(display, surface, surface, ctx);
```

### Option B: Zink over Turnip Vulkan 1.4 (Alternative Path)
Routes desktop OpenGL 4.6 calls through the Turnip Vulkan driver.

```bash
export GALLIUM_DRIVER=zink
export MESA_LOADER_DRIVER_OVERRIDE=zink
export VK_DRIVER_FILES="/opt/nativecode-turnip/share/vulkan/icd.d/freedreno_icd.aarch64.json"
export TU_DEBUG=noconform
export MESA_VK_WSI_DEBUG=sw
export DISPLAY=:0  # For Termux:X11
```

---

## 2. Hardware Capabilities & Verified Feature Set

| Specification / Feature | Supported | Notes |
| :--- | :---: | :--- |
| **OpenGL 4.6 Core Profile** | **YES** | Native context creation verified on hardware |
| **OpenGL 4.5 Core Profile** | **YES** | Native context creation verified on hardware |
| **OpenGL 4.0 - 4.4 Core** | **YES** | All intermediate versions supported |
| **GLSL Version** | **4.60** | Complete shading language 4.60 |
| **Compute Shaders** | **YES** | `GL_ARB_compute_shader` (up to 2048 invocations/group) |
| **SPIR-V Ingestion** | **YES** | `GL_ARB_gl_spirv`, `GL_ARB_spirv_extensions` |
| **Direct State Access (DSA)** | **YES** | `GL_ARB_direct_state_access` |
| **Multi-Draw Indirect** | **YES** | `GL_ARB_multi_draw_indirect`, `GL_ARB_indirect_parameters` |
| **Tessellation Shaders** | **YES** | `GL_ARB_tessellation_shader` |
| **Geometry Shaders** | **YES** | `GL_ARB_geometry_shader4` |
| **Shader Storage Buffer Objects (SSBO)** | **YES** | `GL_ARB_shader_storage_buffer_object` (128 MB max buffer) |
| **Atomic Counters** | **YES** | `GL_ARB_shader_atomic_counters`, `GL_ARB_shader_atomic_counter_ops` |
| **Texture Views** | **YES** | `GL_ARB_texture_view` |
| **Buffer Storage** | **YES** | `GL_ARB_buffer_storage` (coherent immutable buffers) |

---

## 3. Hardware Implementation Limits

| Limit Parameter | Value | Description |
| :--- | :---: | :--- |
| `GL_MAX_TEXTURE_SIZE` | **16,384** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_3D_TEXTURE_SIZE` | **2,048** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_CUBE_MAP_TEXTURE_SIZE` | **16,384** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_ARRAY_TEXTURE_LAYERS` | **2,048** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_RENDERBUFFER_SIZE` | **16,384** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_VERTEX_ATTRIBS` | **16** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_VERTEX_UNIFORM_COMPONENTS` | **16,384** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_VERTEX_OUTPUT_COMPONENTS` | **128** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_FRAGMENT_INPUT_COMPONENTS` | **128** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_FRAGMENT_UNIFORM_COMPONENTS` | **16,384** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_DRAW_BUFFERS` | **8** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_COLOR_ATTACHMENTS` | **8** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_SAMPLES` | **4** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS` | **80** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_UNIFORM_BUFFER_BINDINGS` | **114** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_UNIFORM_BLOCK_SIZE` | **65,536** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS` | **80** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_SHADER_STORAGE_BLOCK_SIZE` | **134,217,728** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS` | **2,048** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_COMPUTE_SHARED_MEMORY_SIZE` | **32,768** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_ELEMENTS_VERTICES` | **3,000** | Hardware limit queried via `glGetIntegerv` |
| `GL_MAX_ELEMENTS_INDICES` | **3,000** | Hardware limit queried via `glGetIntegerv` |

---

## 4. Complete Extension List (226 Extensions)

| # | Extension Name | Standard |
| :---: | :--- | :--- |
| 1 | `GL_AMD_conservative_depth` | Vendor Specific |
| 2 | `GL_AMD_depth_clamp_separate` | Vendor Specific |
| 3 | `GL_AMD_draw_buffers_blend` | Vendor Specific |
| 4 | `GL_AMD_gpu_shader_int64` | Vendor Specific |
| 5 | `GL_AMD_multi_draw_indirect` | Vendor Specific |
| 6 | `GL_AMD_query_buffer_object` | Vendor Specific |
| 7 | `GL_AMD_seamless_cubemap_per_texture` | Vendor Specific |
| 8 | `GL_AMD_shader_stencil_export` | Vendor Specific |
| 9 | `GL_AMD_shader_trinary_minmax` | Vendor Specific |
| 10 | `GL_AMD_texture_texture4` | Vendor Specific |
| 11 | `GL_AMD_vertex_shader_layer` | Vendor Specific |
| 12 | `GL_AMD_vertex_shader_viewport_index` | Vendor Specific |
| 13 | `GL_ANGLE_texture_compression_dxt3` | Vendor Specific |
| 14 | `GL_ANGLE_texture_compression_dxt5` | Vendor Specific |
| 15 | `GL_ARB_ES2_compatibility` | Khronos ARB |
| 16 | `GL_ARB_ES3_1_compatibility` | Khronos ARB |
| 17 | `GL_ARB_ES3_2_compatibility` | Khronos ARB |
| 18 | `GL_ARB_ES3_compatibility` | Khronos ARB |
| 19 | `GL_ARB_arrays_of_arrays` | Khronos ARB |
| 20 | `GL_ARB_base_instance` | Khronos ARB |
| 21 | `GL_ARB_blend_func_extended` | Khronos ARB |
| 22 | `GL_ARB_buffer_storage` | Khronos ARB |
| 23 | `GL_ARB_clear_buffer_object` | Khronos ARB |
| 24 | `GL_ARB_clear_texture` | Khronos ARB |
| 25 | `GL_ARB_clip_control` | Khronos ARB |
| 26 | `GL_ARB_compressed_texture_pixel_storage` | Khronos ARB |
| 27 | `GL_ARB_compute_shader` | Khronos ARB |
| 28 | `GL_ARB_compute_variable_group_size` | Khronos ARB |
| 29 | `GL_ARB_conditional_render_inverted` | Khronos ARB |
| 30 | `GL_ARB_conservative_depth` | Khronos ARB |
| 31 | `GL_ARB_copy_buffer` | Khronos ARB |
| 32 | `GL_ARB_copy_image` | Khronos ARB |
| 33 | `GL_ARB_cull_distance` | Khronos ARB |
| 34 | `GL_ARB_debug_output` | Khronos ARB |
| 35 | `GL_ARB_depth_buffer_float` | Khronos ARB |
| 36 | `GL_ARB_depth_clamp` | Khronos ARB |
| 37 | `GL_ARB_derivative_control` | Khronos ARB |
| 38 | `GL_ARB_direct_state_access` | Khronos ARB |
| 39 | `GL_ARB_draw_buffers` | Khronos ARB |
| 40 | `GL_ARB_draw_buffers_blend` | Khronos ARB |
| 41 | `GL_ARB_draw_elements_base_vertex` | Khronos ARB |
| 42 | `GL_ARB_draw_indirect` | Khronos ARB |
| 43 | `GL_ARB_draw_instanced` | Khronos ARB |
| 44 | `GL_ARB_enhanced_layouts` | Khronos ARB |
| 45 | `GL_ARB_explicit_attrib_location` | Khronos ARB |
| 46 | `GL_ARB_explicit_uniform_location` | Khronos ARB |
| 47 | `GL_ARB_fragment_coord_conventions` | Khronos ARB |
| 48 | `GL_ARB_fragment_layer_viewport` | Khronos ARB |
| 49 | `GL_ARB_fragment_shader` | Khronos ARB |
| 50 | `GL_ARB_framebuffer_no_attachments` | Khronos ARB |
| 51 | `GL_ARB_framebuffer_object` | Khronos ARB |
| 52 | `GL_ARB_framebuffer_sRGB` | Khronos ARB |
| 53 | `GL_ARB_get_program_binary` | Khronos ARB |
| 54 | `GL_ARB_get_texture_sub_image` | Khronos ARB |
| 55 | `GL_ARB_gl_spirv` | Khronos ARB |
| 56 | `GL_ARB_gpu_shader5` | Khronos ARB |
| 57 | `GL_ARB_gpu_shader_fp64` | Khronos ARB |
| 58 | `GL_ARB_gpu_shader_int64` | Khronos ARB |
| 59 | `GL_ARB_half_float_pixel` | Khronos ARB |
| 60 | `GL_ARB_half_float_vertex` | Khronos ARB |
| 61 | `GL_ARB_indirect_parameters` | Khronos ARB |
| 62 | `GL_ARB_instanced_arrays` | Khronos ARB |
| 63 | `GL_ARB_internalformat_query` | Khronos ARB |
| 64 | `GL_ARB_internalformat_query2` | Khronos ARB |
| 65 | `GL_ARB_invalidate_subdata` | Khronos ARB |
| 66 | `GL_ARB_map_buffer_alignment` | Khronos ARB |
| 67 | `GL_ARB_map_buffer_range` | Khronos ARB |
| 68 | `GL_ARB_multi_bind` | Khronos ARB |
| 69 | `GL_ARB_multi_draw_indirect` | Khronos ARB |
| 70 | `GL_ARB_occlusion_query2` | Khronos ARB |
| 71 | `GL_ARB_parallel_shader_compile` | Khronos ARB |
| 72 | `GL_ARB_pipeline_statistics_query` | Khronos ARB |
| 73 | `GL_ARB_pixel_buffer_object` | Khronos ARB |
| 74 | `GL_ARB_point_sprite` | Khronos ARB |
| 75 | `GL_ARB_polygon_offset_clamp` | Khronos ARB |
| 76 | `GL_ARB_post_depth_coverage` | Khronos ARB |
| 77 | `GL_ARB_program_interface_query` | Khronos ARB |
| 78 | `GL_ARB_provoking_vertex` | Khronos ARB |
| 79 | `GL_ARB_query_buffer_object` | Khronos ARB |
| 80 | `GL_ARB_robust_buffer_access_behavior` | Khronos ARB |
| 81 | `GL_ARB_robustness` | Khronos ARB |
| 82 | `GL_ARB_sample_locations` | Khronos ARB |
| 83 | `GL_ARB_sample_shading` | Khronos ARB |
| 84 | `GL_ARB_sampler_objects` | Khronos ARB |
| 85 | `GL_ARB_seamless_cube_map` | Khronos ARB |
| 86 | `GL_ARB_seamless_cubemap_per_texture` | Khronos ARB |
| 87 | `GL_ARB_separate_shader_objects` | Khronos ARB |
| 88 | `GL_ARB_shader_atomic_counter_ops` | Khronos ARB |
| 89 | `GL_ARB_shader_atomic_counters` | Khronos ARB |
| 90 | `GL_ARB_shader_bit_encoding` | Khronos ARB |
| 91 | `GL_ARB_shader_clock` | Khronos ARB |
| 92 | `GL_ARB_shader_draw_parameters` | Khronos ARB |
| 93 | `GL_ARB_shader_group_vote` | Khronos ARB |
| 94 | `GL_ARB_shader_image_load_store` | Khronos ARB |
| 95 | `GL_ARB_shader_image_size` | Khronos ARB |
| 96 | `GL_ARB_shader_objects` | Khronos ARB |
| 97 | `GL_ARB_shader_precision` | Khronos ARB |
| 98 | `GL_ARB_shader_stencil_export` | Khronos ARB |
| 99 | `GL_ARB_shader_storage_buffer_object` | Khronos ARB |
| 100 | `GL_ARB_shader_subroutine` | Khronos ARB |
| 101 | `GL_ARB_shader_texture_image_samples` | Khronos ARB |
| 102 | `GL_ARB_shader_texture_lod` | Khronos ARB |
| 103 | `GL_ARB_shader_viewport_layer_array` | Khronos ARB |
| 104 | `GL_ARB_shading_language_420pack` | Khronos ARB |
| 105 | `GL_ARB_shading_language_include` | Khronos ARB |
| 106 | `GL_ARB_shading_language_packing` | Khronos ARB |
| 107 | `GL_ARB_spirv_extensions` | Khronos ARB |
| 108 | `GL_ARB_stencil_texturing` | Khronos ARB |
| 109 | `GL_ARB_sync` | Khronos ARB |
| 110 | `GL_ARB_tessellation_shader` | Khronos ARB |
| 111 | `GL_ARB_texture_barrier` | Khronos ARB |
| 112 | `GL_ARB_texture_border_clamp` | Khronos ARB |
| 113 | `GL_ARB_texture_buffer_object` | Khronos ARB |
| 114 | `GL_ARB_texture_buffer_object_rgb32` | Khronos ARB |
| 115 | `GL_ARB_texture_buffer_range` | Khronos ARB |
| 116 | `GL_ARB_texture_compression_bptc` | Khronos ARB |
| 117 | `GL_ARB_texture_compression_rgtc` | Khronos ARB |
| 118 | `GL_ARB_texture_cube_map_array` | Khronos ARB |
| 119 | `GL_ARB_texture_filter_anisotropic` | Khronos ARB |
| 120 | `GL_ARB_texture_filter_minmax` | Khronos ARB |
| 121 | `GL_ARB_texture_float` | Khronos ARB |
| 122 | `GL_ARB_texture_gather` | Khronos ARB |
| 123 | `GL_ARB_texture_mirror_clamp_to_edge` | Khronos ARB |
| 124 | `GL_ARB_texture_multisample` | Khronos ARB |
| 125 | `GL_ARB_texture_non_power_of_two` | Khronos ARB |
| 126 | `GL_ARB_texture_query_levels` | Khronos ARB |
| 127 | `GL_ARB_texture_query_lod` | Khronos ARB |
| 128 | `GL_ARB_texture_rectangle` | Khronos ARB |
| 129 | `GL_ARB_texture_rg` | Khronos ARB |
| 130 | `GL_ARB_texture_rgb10_a2ui` | Khronos ARB |
| 131 | `GL_ARB_texture_stencil8` | Khronos ARB |
| 132 | `GL_ARB_texture_storage` | Khronos ARB |
| 133 | `GL_ARB_texture_storage_multisample` | Khronos ARB |
| 134 | `GL_ARB_texture_swizzle` | Khronos ARB |
| 135 | `GL_ARB_texture_view` | Khronos ARB |
| 136 | `GL_ARB_timer_query` | Khronos ARB |
| 137 | `GL_ARB_transform_feedback2` | Khronos ARB |
| 138 | `GL_ARB_transform_feedback3` | Khronos ARB |
| 139 | `GL_ARB_transform_feedback_instanced` | Khronos ARB |
| 140 | `GL_ARB_transform_feedback_overflow_query` | Khronos ARB |
| 141 | `GL_ARB_uniform_buffer_object` | Khronos ARB |
| 142 | `GL_ARB_vertex_array_bgra` | Khronos ARB |
| 143 | `GL_ARB_vertex_array_object` | Khronos ARB |
| 144 | `GL_ARB_vertex_attrib_64bit` | Khronos ARB |
| 145 | `GL_ARB_vertex_attrib_binding` | Khronos ARB |
| 146 | `GL_ARB_vertex_buffer_object` | Khronos ARB |
| 147 | `GL_ARB_vertex_shader` | Khronos ARB |
| 148 | `GL_ARB_vertex_type_10f_11f_11f_rev` | Khronos ARB |
| 149 | `GL_ARB_vertex_type_2_10_10_10_rev` | Khronos ARB |
| 150 | `GL_ARB_viewport_array` | Khronos ARB |
| 151 | `GL_ATI_blend_equation_separate` | Vendor Specific |
| 152 | `GL_ATI_meminfo` | Vendor Specific |
| 153 | `GL_ATI_texture_float` | Vendor Specific |
| 154 | `GL_EXT_EGL_image_storage` | EXT Standard |
| 155 | `GL_EXT_EGL_image_storage_compression` | EXT Standard |
| 156 | `GL_EXT_EGL_sync` | EXT Standard |
| 157 | `GL_EXT_abgr` | EXT Standard |
| 158 | `GL_EXT_blend_equation_separate` | EXT Standard |
| 159 | `GL_EXT_debug_label` | EXT Standard |
| 160 | `GL_EXT_demote_to_helper_invocation` | EXT Standard |
| 161 | `GL_EXT_depth_bounds_test` | EXT Standard |
| 162 | `GL_EXT_draw_buffers2` | EXT Standard |
| 163 | `GL_EXT_draw_instanced` | EXT Standard |
| 164 | `GL_EXT_framebuffer_blit` | EXT Standard |
| 165 | `GL_EXT_framebuffer_multisample` | EXT Standard |
| 166 | `GL_EXT_framebuffer_multisample_blit_scaled` | EXT Standard |
| 167 | `GL_EXT_framebuffer_object` | EXT Standard |
| 168 | `GL_EXT_framebuffer_sRGB` | EXT Standard |
| 169 | `GL_EXT_memory_object` | EXT Standard |
| 170 | `GL_EXT_memory_object_fd` | EXT Standard |
| 171 | `GL_EXT_packed_depth_stencil` | EXT Standard |
| 172 | `GL_EXT_packed_float` | EXT Standard |
| 173 | `GL_EXT_pixel_buffer_object` | EXT Standard |
| 174 | `GL_EXT_polygon_offset_clamp` | EXT Standard |
| 175 | `GL_EXT_provoking_vertex` | EXT Standard |
| 176 | `GL_EXT_shader_clock` | EXT Standard |
| 177 | `GL_EXT_shader_framebuffer_fetch` | EXT Standard |
| 178 | `GL_EXT_shader_framebuffer_fetch_non_coherent` | EXT Standard |
| 179 | `GL_EXT_shader_image_load_formatted` | EXT Standard |
| 180 | `GL_EXT_shader_integer_mix` | EXT Standard |
| 181 | `GL_EXT_texture_array` | EXT Standard |
| 182 | `GL_EXT_texture_compression_dxt1` | EXT Standard |
| 183 | `GL_EXT_texture_compression_rgtc` | EXT Standard |
| 184 | `GL_EXT_texture_compression_s3tc` | EXT Standard |
| 185 | `GL_EXT_texture_filter_anisotropic` | EXT Standard |
| 186 | `GL_EXT_texture_filter_minmax` | EXT Standard |
| 187 | `GL_EXT_texture_integer` | EXT Standard |
| 188 | `GL_EXT_texture_sRGB` | EXT Standard |
| 189 | `GL_EXT_texture_sRGB_R8` | EXT Standard |
| 190 | `GL_EXT_texture_sRGB_RG8` | EXT Standard |
| 191 | `GL_EXT_texture_sRGB_decode` | EXT Standard |
| 192 | `GL_EXT_texture_shared_exponent` | EXT Standard |
| 193 | `GL_EXT_texture_snorm` | EXT Standard |
| 194 | `GL_EXT_texture_storage` | EXT Standard |
| 195 | `GL_EXT_texture_swizzle` | EXT Standard |
| 196 | `GL_EXT_timer_query` | EXT Standard |
| 197 | `GL_EXT_transform_feedback` | EXT Standard |
| 198 | `GL_EXT_vertex_array_bgra` | EXT Standard |
| 199 | `GL_EXT_vertex_attrib_64bit` | EXT Standard |
| 200 | `GL_IBM_multimode_draw_arrays` | Vendor Specific |
| 201 | `GL_KHR_blend_equation_advanced` | Khronos KHR |
| 202 | `GL_KHR_blend_equation_advanced_coherent` | Khronos KHR |
| 203 | `GL_KHR_context_flush_control` | Khronos KHR |
| 204 | `GL_KHR_debug` | Khronos KHR |
| 205 | `GL_KHR_no_error` | Khronos KHR |
| 206 | `GL_KHR_parallel_shader_compile` | Khronos KHR |
| 207 | `GL_KHR_robust_buffer_access_behavior` | Khronos KHR |
| 208 | `GL_KHR_robustness` | Khronos KHR |
| 209 | `GL_KHR_shader_subgroup` | Khronos KHR |
| 210 | `GL_KHR_texture_compression_astc_hdr` | Khronos KHR |
| 211 | `GL_KHR_texture_compression_astc_ldr` | Khronos KHR |
| 212 | `GL_KHR_texture_compression_astc_sliced_3d` | Khronos KHR |
| 213 | `GL_MESA_framebuffer_flip_y` | Vendor Specific |
| 214 | `GL_MESA_pack_invert` | Vendor Specific |
| 215 | `GL_MESA_shader_integer_functions` | Vendor Specific |
| 216 | `GL_MESA_texture_const_bandwidth` | Vendor Specific |
| 217 | `GL_MESA_texture_signed_rgba` | Vendor Specific |
| 218 | `GL_NVX_gpu_memory_info` | Vendor Specific |
| 219 | `GL_NV_conditional_render` | Vendor Specific |
| 220 | `GL_NV_copy_image` | Vendor Specific |
| 221 | `GL_NV_depth_clamp` | Vendor Specific |
| 222 | `GL_NV_packed_depth_stencil` | Vendor Specific |
| 223 | `GL_NV_sample_locations` | Vendor Specific |
| 224 | `GL_NV_texture_barrier` | Vendor Specific |
| 225 | `GL_OES_EGL_image` | Vendor Specific |
| 226 | `GL_S3_s3tc` | Vendor Specific |
""",
        "gpu/adreno/opengles_3_2.md" to """
# OpenGL ES 3.2 on Adreno 750v2 (FD750) — Freedreno Driver

> **Source**: Enumerated on-device via Freedreno EGL/GLES (KGSL, headless) on the live GPU.
> Mesa version: 26.3.0-devel (git-98f3d6229d)
> `GL_RENDERER: FD750`, `GL_VENDOR: freedreno`, GLSL ES 3.20

---

## Capabilities

| Property | Value |
|----------|-------|
| GL_VERSION | OpenGL ES 3.2 Mesa 26.3.0-devel |
| GL_RENDERER | FD750 |
| GL_VENDOR | freedreno |
| GLSL | OpenGL ES GLSL ES 3.20 |
| Profile | ES 3.2 (max supported; desktop GL 4.x contexts rejected) |

---

## Exposed Extensions (162)

### Core ES 3.2-era feature extensions

| Extension | Notes |
|-----------|-------|
| GL_EXT_blend_minmax | |
| GL_EXT_multi_draw_arrays | |
| GL_EXT_texture_filter_anisotropic | |
| GL_EXT_texture_compression_s3tc | |
| GL_EXT_texture_compression_dxt1 | |
| GL_EXT_texture_compression_rgtc | |
| GL_EXT_texture_format_BGRA8888 | |
| GL_OES_compressed_ETC1_RGB8_texture | |
| GL_OES_depth24 | |
| GL_OES_element_index_uint | |
| GL_OES_fbo_render_mipmap | |
| GL_OES_mapbuffer | |
| GL_OES_rgb8_rgba8 | |
| GL_OES_standard_derivatives | |
| GL_OES_stencil8 | |
| GL_OES_texture_3D | |
| GL_OES_texture_float | |
| GL_OES_texture_float_linear | |
| GL_OES_texture_half_float | |
| GL_OES_texture_half_float_linear | |
| GL_OES_texture_npot | |
| GL_OES_vertex_half_float | |
| GL_EXT_draw_instanced | |
| GL_EXT_texture_sRGB_decode | |
| GL_OES_EGL_image | |
| GL_OES_depth_texture | |
| GL_OES_packed_depth_stencil | |
| GL_EXT_texture_type_2_10_10_10_REV | |
| GL_NV_conditional_render | |
| GL_OES_get_program_binary | |
| GL_APPLE_texture_max_level | |
| GL_EXT_discard_framebuffer | |
| GL_EXT_read_format_bgra | |
| GL_EXT_texture_storage | |
| GL_NV_pack_subimage | |
| GL_NV_texture_barrier | |
| GL_EXT_frag_depth | |
| GL_NV_fbo_color_attachments | |
| GL_OES_EGL_image_external | |
| GL_OES_EGL_sync | |
| GL_OES_vertex_array_object | |
| GL_OES_viewport_array | |
| GL_ANGLE_pack_reverse_row_order | |
| GL_ANGLE_texture_compression_dxt3 | |
| GL_ANGLE_texture_compression_dxt5 | |
| GL_EXT_occlusion_query_boolean | |
| GL_EXT_robustness | |
| GL_EXT_sRGB | |
| GL_EXT_texture_rg | |
| GL_EXT_unpack_subimage | |
| GL_NV_draw_buffers | |
| GL_NV_read_buffer | |
| GL_NV_read_depth | |
| GL_NV_read_depth_stencil | |
| GL_NV_read_stencil | |
| GL_APPLE_sync | |
| GL_EXT_draw_buffers | |
| GL_EXT_instanced_arrays | |
| GL_EXT_map_buffer_range | |
| GL_EXT_shadow_samplers | |
| GL_KHR_debug | |
| GL_KHR_robustness | |
| GL_KHR_texture_compression_astc_hdr | ASTC HDR |
| GL_KHR_texture_compression_astc_ldr | ASTC LDR |
| GL_NV_generate_mipmap_sRGB | |
| GL_NV_pixel_buffer_object | |
| GL_OES_depth_texture_cube_map | |
| GL_OES_required_internalformat | |
| GL_OES_surfaceless_context | |

### ES 3.1 feature extensions

| Extension | Notes |
|-----------|-------|
| GL_EXT_color_buffer_float | |
| GL_EXT_debug_label | |
| GL_EXT_sRGB_write_control | |
| GL_EXT_separate_shader_objects | |
| GL_EXT_shader_framebuffer_fetch | |
| GL_EXT_shader_group_vote | |
| GL_EXT_shader_implicit_conversions | |
| GL_EXT_shader_integer_mix | |
| GL_EXT_tessellation_point_size | |
| GL_EXT_tessellation_shader | |
| GL_ANDROID_extension_pack_es31a | Android ES 3.1 pack |
| GL_EXT_base_instance | |
| GL_EXT_compressed_ETC1_RGB8_sub_texture | |
| GL_EXT_copy_image | |
| GL_EXT_draw_buffers_indexed | |
| GL_EXT_draw_elements_base_vertex | |
| GL_EXT_gpu_shader5 | |
| GL_EXT_multi_draw_indirect | |
| GL_EXT_polygon_offset_clamp | |
| GL_EXT_primitive_bounding_box | |
| GL_EXT_render_snorm | |
| GL_EXT_shader_io_blocks | |
| GL_EXT_texture_border_clamp | |
| GL_EXT_texture_buffer | |
| GL_EXT_texture_cube_map_array | |
| GL_EXT_texture_norm16 | |
| GL_EXT_texture_view | |
| GL_KHR_blend_equation_advanced | |
| GL_KHR_blend_equation_advanced_coherent | |
| GL_KHR_context_flush_control | |
| GL_KHR_robust_buffer_access_behavior | |
| GL_NV_image_formats | |
| GL_NV_shader_noperspective_interpolation | |
| GL_OES_copy_image | |
| GL_OES_draw_buffers_indexed | |
| GL_OES_draw_elements_base_vertex | |
| GL_OES_gpu_shader5 | |
| GL_OES_primitive_bounding_box | |
| GL_OES_sample_shading | |
| GL_OES_sample_variables | |
| GL_OES_shader_io_blocks | |
| GL_OES_shader_multisample_interpolation | |
| GL_OES_tessellation_point_size | |
| GL_OES_tessellation_shader | |
| GL_OES_texture_border_clamp | |
| GL_OES_texture_buffer | |
| GL_OES_texture_cube_map_array | |
| GL_OES_texture_stencil8 | |
| GL_OES_texture_storage_multisample_2d_array | |
| GL_OES_texture_view | |

### ES 3.2 feature extensions

| Extension | Notes |
|-----------|-------|
| GL_EXT_blend_func_extended | |
| GL_EXT_buffer_storage | |
| GL_EXT_float_blend | |
| GL_EXT_geometry_point_size | |
| GL_EXT_geometry_shader | |
| GL_EXT_texture_filter_minmax | |
| GL_EXT_texture_sRGB_R8 | |
| GL_EXT_texture_sRGB_RG8 | |
| GL_KHR_no_error | |
| GL_KHR_texture_compression_astc_sliced_3d | |
| GL_NV_sample_locations | |
| GL_OES_EGL_image_external_essl3 | |
| GL_OES_geometry_point_size | |
| GL_OES_geometry_shader | |
| GL_OES_shader_image_atomic | |
| GL_EXT_clear_texture | |
| GL_EXT_clip_cull_distance | |
| GL_EXT_conservative_depth | |
| GL_EXT_disjoint_timer_query | |
| GL_EXT_multisampled_render_to_texture | |
| GL_EXT_multisampled_render_to_texture2 | |
| GL_EXT_texture_compression_s3tc_srgb | |
| GL_MESA_shader_integer_functions | |
| GL_EXT_clip_control | |
| GL_EXT_color_buffer_half_float | |
| GL_EXT_memory_object | |
| GL_EXT_memory_object_fd | |
| GL_EXT_texture_compression_bptc | |
| GL_EXT_texture_mirror_clamp_to_edge | |
| GL_KHR_parallel_shader_compile | |
| GL_EXT_EGL_image_storage | |
| GL_EXT_shader_framebuffer_fetch_non_coherent | |
| GL_MESA_framebuffer_flip_y | |
| GL_EXT_demote_to_helper_invocation | |
| GL_EXT_depth_clamp | |
| GL_EXT_texture_query_lod | |
| GL_KHR_shader_subgroup | |
| GL_MESA_sampler_objects | |
| GL_EXT_EGL_image_storage_compression | |
| GL_EXT_texture_storage_compression | |
| GL_MESA_bgra | |
| GL_MESA_texture_const_bandwidth | |
| GL_EXT_shader_clock | |
""",
        "gpu/adreno/vulkan_turnip.md" to """
# Vulkan on Adreno 750v2 (FD750) — Turnip Driver

> **Source**: Enumerated on-device via Turnip ICD (`libvulkan_freedreno.so`) on the live GPU.
> Mesa version: 26.3.0-devel (git-98f3d6229d), API version 1.4.359

---

## Physical Device

| Property        | Value |
|-----------------|-------|
| deviceName      | Turnip Adreno (TM) 750 |
| vendorID        | 0x5143 |
| deviceID        | 0x43051401 |
| deviceType      | INTEGRATED_GPU |
| apiVersion      | 1.4.359 |
| driverVersion   | 26.2.24.3 |

---

## Instance Extensions (22)

| Extension | specVersion |
|-----------|-------------|
| VK_KHR_device_group_creation | 1 |
| VK_KHR_display | 23 |
| VK_KHR_external_fence_capabilities | 1 |
| VK_KHR_external_memory_capabilities | 1 |
| VK_KHR_external_semaphore_capabilities | 1 |
| VK_KHR_get_display_properties2 | 1 |
| VK_KHR_get_physical_device_properties2 | 2 |
| VK_KHR_get_surface_capabilities2 | 1 |
| VK_KHR_surface | 25 |
| VK_KHR_surface_maintenance1 | 1 |
| VK_KHR_surface_protected_capabilities | 1 |
| VK_KHR_xcb_surface | 6 |
| VK_KHR_xlib_surface | 6 |
| VK_EXT_acquire_drm_display | 1 |
| VK_EXT_acquire_xlib_display | 1 |
| VK_EXT_debug_report | 10 |
| VK_EXT_debug_utils | 2 |
| VK_EXT_direct_mode_display | 1 |
| VK_EXT_display_surface_counter | 1 |
| VK_EXT_headless_surface | 1 |
| VK_EXT_surface_maintenance1 | 1 |
| VK_EXT_swapchain_colorspace | 5 |

---

## Device Extensions (189)

### KHR (89)

| Extension | specVersion |
|-----------|-------------|
| VK_KHR_8bit_storage | 1 |
| VK_KHR_16bit_storage | 1 |
| VK_KHR_acceleration_structure | 13 |
| VK_KHR_bind_memory2 | 1 |
| VK_KHR_buffer_device_address | 1 |
| VK_KHR_calibrated_timestamps | 1 |
| VK_KHR_compute_shader_derivatives | 1 |
| VK_KHR_copy_commands2 | 1 |
| VK_KHR_create_renderpass2 | 1 |
| VK_KHR_dedicated_allocation | 3 |
| VK_KHR_deferred_host_operations | 4 |
| VK_KHR_depth_clamp_zero_one | 1 |
| VK_KHR_depth_stencil_resolve | 1 |
| VK_KHR_descriptor_update_template | 1 |
| VK_KHR_device_group | 4 |
| VK_KHR_draw_indirect_count | 1 |
| VK_KHR_driver_properties | 1 |
| VK_KHR_dynamic_rendering | 1 |
| VK_KHR_dynamic_rendering_local_read | 1 |
| VK_KHR_external_fence | 1 |
| VK_KHR_external_fence_fd | 1 |
| VK_KHR_external_memory | 1 |
| VK_KHR_external_memory_fd | 1 |
| VK_KHR_external_semaphore | 1 |
| VK_KHR_external_semaphore_fd | 1 |
| VK_KHR_format_feature_flags2 | 2 |
| VK_KHR_fragment_shading_rate | 2 |
| VK_KHR_get_memory_requirements2 | 1 |
| VK_KHR_global_priority | 1 |
| VK_KHR_image_format_list | 1 |
| VK_KHR_imageless_framebuffer | 1 |
| VK_KHR_incremental_present | 2 |
| VK_KHR_index_type_uint8 | 1 |
| VK_KHR_internally_synchronized_queues | 1 |
| VK_KHR_line_rasterization | 1 |
| VK_KHR_load_store_op_none | 1 |
| VK_KHR_maintenance1 | 2 |
| VK_KHR_maintenance2 | 1 |
| VK_KHR_maintenance3 | 1 |
| VK_KHR_maintenance4 | 2 |
| VK_KHR_maintenance5 | 1 |
| VK_KHR_maintenance6 | 1 |
| VK_KHR_maintenance7 | 1 |
| VK_KHR_maintenance8 | 1 |
| VK_KHR_maintenance9 | 1 |
| VK_KHR_map_memory2 | 1 |
| VK_KHR_multiview | 1 |
| VK_KHR_pipeline_executable_properties | 1 |
| VK_KHR_pipeline_library | 1 |
| VK_KHR_present_id | 1 |
| VK_KHR_present_id2 | 1 |
| VK_KHR_present_wait | 1 |
| VK_KHR_present_wait2 | 1 |
| VK_KHR_push_descriptor | 2 |
| VK_KHR_ray_query | 1 |
| VK_KHR_ray_tracing_maintenance1 | 1 |
| VK_KHR_relaxed_block_layout | 1 |
| VK_KHR_robustness2 | 1 |
| VK_KHR_sampler_mirror_clamp_to_edge | 3 |
| VK_KHR_sampler_ycbcr_conversion | 14 |
| VK_KHR_separate_depth_stencil_layouts | 1 |
| VK_KHR_shader_atomic_int64 | 1 |
| VK_KHR_shader_clock | 1 |
| VK_KHR_shader_draw_parameters | 1 |
| VK_KHR_shader_expect_assume | 1 |
| VK_KHR_shader_float16_int8 | 1 |
| VK_KHR_shader_float_controls | 4 |
| VK_KHR_shader_float_controls2 | 1 |
| VK_KHR_shader_integer_dot_product | 1 |
| VK_KHR_shader_non_semantic_info | 1 |
| VK_KHR_shader_relaxed_extended_instruction | 1 |
| VK_KHR_shader_subgroup_extended_types | 1 |
| VK_KHR_shader_subgroup_rotate | 2 |
| VK_KHR_shader_subgroup_uniform_control_flow | 1 |
| VK_KHR_shader_terminate_invocation | 1 |
| VK_KHR_spirv_1_4 | 1 |
| VK_KHR_storage_buffer_storage_class | 1 |
| VK_KHR_swapchain | 70 |
| VK_KHR_swapchain_maintenance1 | 1 |
| VK_KHR_swapchain_mutable_format | 1 |
| VK_KHR_synchronization2 | 1 |
| VK_KHR_timeline_semaphore | 2 |
| VK_KHR_unified_image_layouts | 1 |
| VK_KHR_uniform_buffer_standard_layout | 1 |
| VK_KHR_variable_pointers | 1 |
| VK_KHR_vertex_attribute_divisor | 1 |
| VK_KHR_vulkan_memory_model | 3 |
| VK_KHR_workgroup_memory_explicit_layout | 1 |
| VK_KHR_zero_initialize_workgroup_memory | 1 |

### EXT (86)

| Extension | specVersion |
|-----------|-------------|
| VK_EXT_4444_formats | 1 |
| VK_EXT_attachment_feedback_loop_dynamic_state | 1 |
| VK_EXT_attachment_feedback_loop_layout | 2 |
| VK_EXT_border_color_swizzle | 1 |
| VK_EXT_calibrated_timestamps | 2 |
| VK_EXT_color_write_enable | 1 |
| VK_EXT_conditional_rendering | 2 |
| VK_EXT_conservative_rasterization | 1 |
| VK_EXT_custom_border_color | 12 |
| VK_EXT_custom_resolve | 1 |
| VK_EXT_debug_marker | 4 |
| VK_EXT_depth_clamp_control | 1 |
| VK_EXT_depth_clamp_zero_one | 1 |
| VK_EXT_depth_clip_control | 1 |
| VK_EXT_depth_clip_enable | 1 |
| VK_EXT_descriptor_buffer | 1 |
| VK_EXT_descriptor_indexing | 2 |
| VK_EXT_device_address_binding_report | 1 |
| VK_EXT_device_memory_report | 2 |
| VK_EXT_display_control | 1 |
| VK_EXT_dynamic_rendering_unused_attachments | 1 |
| VK_EXT_extended_dynamic_state | 1 |
| VK_EXT_extended_dynamic_state2 | 1 |
| VK_EXT_extended_dynamic_state3 | 2 |
| VK_EXT_external_memory_dma_buf | 1 |
| VK_EXT_filter_cubic | 3 |
| VK_EXT_fragment_density_map | 3 |
| VK_EXT_fragment_density_map_offset | 1 |
| VK_EXT_global_priority | 2 |
| VK_EXT_global_priority_query | 1 |
| VK_EXT_graphics_pipeline_library | 1 |
| VK_EXT_hdr_metadata | 3 |
| VK_EXT_host_image_copy | 1 |
| VK_EXT_host_query_reset | 1 |
| VK_EXT_image_2d_view_of_3d | 1 |
| VK_EXT_image_compression_control | 1 |
| VK_EXT_image_compression_control_swapchain | 1 |
| VK_EXT_image_drm_format_modifier | 2 |
| VK_EXT_image_robustness | 1 |
| VK_EXT_image_view_min_lod | 1 |
| VK_EXT_index_type_uint8 | 1 |
| VK_EXT_inline_uniform_block | 1 |
| VK_EXT_legacy_dithering | 2 |
| VK_EXT_legacy_vertex_attributes | 1 |
| VK_EXT_line_rasterization | 1 |
| VK_EXT_load_store_op_none | 1 |
| VK_EXT_map_memory_placed | 1 |
| VK_EXT_memory_budget | 1 |
| VK_EXT_multi_draw | 1 |
| VK_EXT_multisampled_render_to_single_sampled | 1 |
| VK_EXT_mutable_descriptor_type | 1 |
| VK_EXT_nested_command_buffer | 1 |
| VK_EXT_non_seamless_cube_map | 1 |
| VK_EXT_pipeline_creation_cache_control | 3 |
| VK_EXT_pipeline_creation_feedback | 1 |
| VK_EXT_present_timing | 3 |
| VK_EXT_primitive_restart_index | 1 |
| VK_EXT_primitive_topology_list_restart | 1 |
| VK_EXT_primitives_generated_query | 1 |
| VK_EXT_private_data | 1 |
| VK_EXT_provoking_vertex | 1 |
| VK_EXT_queue_family_foreign | 1 |
| VK_EXT_rasterization_order_attachment_access | 1 |
| VK_EXT_robustness2 | 1 |
| VK_EXT_sample_locations | 1 |
| VK_EXT_sampler_filter_minmax | 2 |
| VK_EXT_scalar_block_layout | 1 |
| VK_EXT_separate_stencil_usage | 1 |
| VK_EXT_shader_atomic_float | 1 |
| VK_EXT_shader_demote_to_helper_invocation | 1 |
| VK_EXT_shader_image_atomic_int64 | 1 |
| VK_EXT_shader_module_identifier | 1 |
| VK_EXT_shader_replicated_composites | 1 |
| VK_EXT_shader_stencil_export | 1 |
| VK_EXT_shader_subgroup_vote | 1 |
| VK_EXT_shader_uniform_buffer_unsized_array | 1 |
| VK_EXT_shader_viewport_index_layer | 1 |
| VK_EXT_subgroup_size_control | 2 |
| VK_EXT_swapchain_maintenance1 | 1 |
| VK_EXT_texel_buffer_alignment | 1 |
| VK_EXT_texture_compression_astc_hdr | 1 |
| VK_EXT_tooling_info | 1 |
| VK_EXT_transform_feedback | 1 |
| VK_EXT_vertex_attribute_divisor | 3 |
| VK_EXT_vertex_input_dynamic_state | 2 |
| VK_EXT_zero_initialize_device_memory | 1 |

### Vendor (14)

| Extension | specVersion |
|-----------|-------------|
| VK_AMD_buffer_marker | 1 |
| VK_ARM_rasterization_order_attachment_access | 1 |
| VK_GOOGLE_decorate_string | 1 |
| VK_GOOGLE_hlsl_functionality1 | 1 |
| VK_GOOGLE_user_type | 1 |
| VK_IMG_filter_cubic | 1 |
| VK_NV_compute_shader_derivatives | 1 |
| VK_QCOM_fragment_density_map_offset | 3 |
| VK_QCOM_image_processing | 1 |
| VK_QCOM_multiview_per_view_render_areas | 1 |
| VK_QCOM_multiview_per_view_viewports | 1 |
| VK_QCOM_render_pass_shader_resolve | 4 |
| VK_VALVE_fragment_density_map_layered | 1 |
| VK_VALVE_mutable_descriptor_type | 1 |

---

## Device Features (VkPhysicalDeviceFeatures)

| Feature | Supported |
|---------|-----------|
| robustBufferAccess | Yes |
| fullDrawIndexUint32 | Yes |
| imageCubeArray | Yes |
| independentBlend | Yes |
| geometryShader | Yes |
| tessellationShader | Yes |
| sampleRateShading | Yes |
| dualSrcBlend | Yes |
| logicOp | Yes |
| multiDrawIndirect | Yes |
| drawIndirectFirstInstance | Yes |
| depthClamp | Yes |
| depthBiasClamp | Yes |
| fillModeNonSolid | Yes |
| depthBounds | Yes |
| wideLines | Yes |
| largePoints | Yes |
| alphaToOne | Yes |
| multiViewport | Yes |
| samplerAnisotropy | Yes |
| textureCompressionETC2 | Yes |
| textureCompressionBC | Yes |
| textureCompressionASTC_LDR | Yes |
| occlusionQueryPrecise | Yes |
| pipelineStatisticsQuery | Yes |
| shaderFloat64 | No |
| shaderInt64 | Yes |
| shaderInt16 | Yes |
| sparseBinding | Yes |
| sparseResidencyBuffer | Yes |
| sparseResidencyImage2D | Yes |
| sparseResidencyImage3D | No |
| sparseResidencyAliased | Yes |
| variableMultisampleRate | Yes |
| inheritedQueries | Yes |

---

## Limits

| Limit | Value |
|-------|-------|
| maxImageDimension | 16384 |
| maxComputeWorkGroupCount | 65535 x 65535 x 65535 |
| maxComputeWorkGroupSize | 1024 x 1024 x 1024 |
| maxComputeWorkGroupInvocations | 2048 |
| maxFragmentInputComponents | 128 |
| maxVertexOutputComponents | 128 |
| maxTextureDimension | 16384 |
| maxPushConstantsSize | 256 bytes |
| maxBoundDescriptorSets | 7 |

---

## Memory

**Heap 0** — 8417 MB, DEVICE_LOCAL

| Type | Heap | Flags |
|------|------|-------|
| 0 | 0 | DEVICE_LOCAL \| HOST_VISIBLE \| HOST_COHERENT |
| 1 | 0 | DEVICE_LOCAL \| HOST_VISIBLE \| HOST_COHERENT \| HOST_CACHED |
| 2 | 0 | DEVICE_LOCAL \| HOST_VISIBLE \| HOST_CACHED |
| 3 | 0 | DEVICE_LOCAL \| LAZILY_ALLOCATED |

> Note: single unified memory (no discrete VRAM); all heaps are device-local.
""",
        "gpu/mali/OpenGL-3.2-Mali-PanVK-zink.md" to """
# OpenGL 3.2 feature + extension set — on-device query (Zink on PanVK)

## Context actually obtained

- Requested: desktop GL 3.2 core via EGL surfaceless (configless,
  `EGL_MESA_configless_context`), `MESA_GL_VERSION_OVERRIDE=3.2`,
  `MESA_GLSL_VERSION_OVERRIDE=150` so the driver reports the exact 3.2 view.
- Without the override the same request returns a 3.3 core context;
  the extension set is identical (215 extensions either way).
```
GL_VERSION: 3.2 (Core Profile) Mesa 26.1.6
GL_VENDOR: Mesa
GL_RENDERER: zink Vulkan 1.4(Mali unknown 0xa8671030 MC6 (MESA_PANVK))
GL_SHADING_LANGUAGE_VERSION: 1.50
GL_MAJOR_VERSION=3 GL_MINOR_VERSION=2 GL_NUM_EXTENSIONS=215
```

Maximum desktop GL on this device/driver is 3.3 core+compat (see
`OpenGL-4.5-4.6-Mali-PanVK-zink.md` for why 4.x is unavailable).

## Extensions (215)

```
  GL_AMD_conservative_depth
  GL_AMD_draw_buffers_blend
  GL_AMD_gpu_shader_int64
  GL_AMD_multi_draw_indirect
  GL_AMD_performance_monitor
  GL_AMD_query_buffer_object
  GL_AMD_seamless_cubemap_per_texture
  GL_AMD_shader_stencil_export
  GL_AMD_shader_trinary_minmax
  GL_AMD_texture_texture4
  GL_ANGLE_texture_compression_dxt3
  GL_ANGLE_texture_compression_dxt5
  GL_ARB_ES2_compatibility
  GL_ARB_ES3_1_compatibility
  GL_ARB_ES3_compatibility
  GL_ARB_arrays_of_arrays
  GL_ARB_base_instance
  GL_ARB_bindless_texture
  GL_ARB_blend_func_extended
  GL_ARB_buffer_storage
  GL_ARB_clear_buffer_object
  GL_ARB_clear_texture
  GL_ARB_clip_control
  GL_ARB_compressed_texture_pixel_storage
  GL_ARB_compute_shader
  GL_ARB_compute_variable_group_size
  GL_ARB_conditional_render_inverted
  GL_ARB_conservative_depth
  GL_ARB_copy_buffer
  GL_ARB_copy_image
  GL_ARB_debug_output
  GL_ARB_depth_buffer_float
  GL_ARB_depth_clamp
  GL_ARB_derivative_control
  GL_ARB_direct_state_access
  GL_ARB_draw_buffers
  GL_ARB_draw_buffers_blend
  GL_ARB_draw_elements_base_vertex
  GL_ARB_draw_indirect
  GL_ARB_draw_instanced
  GL_ARB_enhanced_layouts
  GL_ARB_explicit_attrib_location
  GL_ARB_explicit_uniform_location
  GL_ARB_fragment_coord_conventions
  GL_ARB_fragment_shader
  GL_ARB_framebuffer_no_attachments
  GL_ARB_framebuffer_object
  GL_ARB_framebuffer_sRGB
  GL_ARB_get_program_binary
  GL_ARB_get_texture_sub_image
  GL_ARB_gl_spirv
  GL_ARB_gpu_shader5
  GL_ARB_gpu_shader_fp64
  GL_ARB_gpu_shader_int64
  GL_ARB_half_float_pixel
  GL_ARB_half_float_vertex
  GL_ARB_indirect_parameters
  GL_ARB_instanced_arrays
  GL_ARB_internalformat_query
  GL_ARB_internalformat_query2
  GL_ARB_invalidate_subdata
  GL_ARB_map_buffer_alignment
  GL_ARB_map_buffer_range
  GL_ARB_multi_bind
  GL_ARB_multi_draw_indirect
  GL_ARB_occlusion_query2
  GL_ARB_parallel_shader_compile
  GL_ARB_pixel_buffer_object
  GL_ARB_point_sprite
  GL_ARB_polygon_offset_clamp
  GL_ARB_program_interface_query
  GL_ARB_provoking_vertex
  GL_ARB_query_buffer_object
  GL_ARB_robust_buffer_access_behavior
  GL_ARB_robustness
  GL_ARB_sample_shading
  GL_ARB_sampler_objects
  GL_ARB_seamless_cube_map
  GL_ARB_seamless_cubemap_per_texture
  GL_ARB_separate_shader_objects
  GL_ARB_shader_atomic_counter_ops
  GL_ARB_shader_atomic_counters
  GL_ARB_shader_ballot
  GL_ARB_shader_bit_encoding
  GL_ARB_shader_clock
  GL_ARB_shader_draw_parameters
  GL_ARB_shader_group_vote
  GL_ARB_shader_image_load_store
  GL_ARB_shader_image_size
  GL_ARB_shader_objects
  GL_ARB_shader_precision
  GL_ARB_shader_stencil_export
  GL_ARB_shader_storage_buffer_object
  GL_ARB_shader_subroutine
  GL_ARB_shader_texture_image_samples
  GL_ARB_shader_texture_lod
  GL_ARB_shading_language_420pack
  GL_ARB_shading_language_include
  GL_ARB_shading_language_packing
  GL_ARB_spirv_extensions
  GL_ARB_stencil_texturing
  GL_ARB_sync
  GL_ARB_texture_barrier
  GL_ARB_texture_border_clamp
  GL_ARB_texture_buffer_object
  GL_ARB_texture_buffer_object_rgb32
  GL_ARB_texture_buffer_range
  GL_ARB_texture_compression_bptc
  GL_ARB_texture_compression_rgtc
  GL_ARB_texture_cube_map_array
  GL_ARB_texture_filter_anisotropic
  GL_ARB_texture_filter_minmax
  GL_ARB_texture_float
  GL_ARB_texture_gather
  GL_ARB_texture_mirror_clamp_to_edge
  GL_ARB_texture_multisample
  GL_ARB_texture_non_power_of_two
  GL_ARB_texture_query_levels
  GL_ARB_texture_query_lod
  GL_ARB_texture_rectangle
  GL_ARB_texture_rg
  GL_ARB_texture_rgb10_a2ui
  GL_ARB_texture_stencil8
  GL_ARB_texture_storage
  GL_ARB_texture_storage_multisample
  GL_ARB_texture_swizzle
  GL_ARB_texture_view
  GL_ARB_timer_query
  GL_ARB_transform_feedback2
  GL_ARB_transform_feedback3
  GL_ARB_transform_feedback_instanced
  GL_ARB_transform_feedback_overflow_query
  GL_ARB_uniform_buffer_object
  GL_ARB_vertex_array_bgra
  GL_ARB_vertex_array_object
  GL_ARB_vertex_attrib_64bit
  GL_ARB_vertex_attrib_binding
  GL_ARB_vertex_buffer_object
  GL_ARB_vertex_shader
  GL_ARB_vertex_type_10f_11f_11f_rev
  GL_ARB_vertex_type_2_10_10_10_rev
  GL_ATI_blend_equation_separate
  GL_ATI_meminfo
  GL_ATI_texture_float
  GL_EXT_EGL_image_storage
  GL_EXT_EGL_image_storage_compression
  GL_EXT_EGL_sync
  GL_EXT_abgr
  GL_EXT_blend_equation_separate
  GL_EXT_debug_label
  GL_EXT_demote_to_helper_invocation
  GL_EXT_draw_buffers2
  GL_EXT_draw_instanced
  GL_EXT_framebuffer_blit
  GL_EXT_framebuffer_multisample
  GL_EXT_framebuffer_multisample_blit_scaled
  GL_EXT_framebuffer_object
  GL_EXT_framebuffer_sRGB
  GL_EXT_memory_object
  GL_EXT_memory_object_fd
  GL_EXT_packed_depth_stencil
  GL_EXT_packed_float
  GL_EXT_pixel_buffer_object
  GL_EXT_polygon_offset_clamp
  GL_EXT_provoking_vertex
  GL_EXT_semaphore
  GL_EXT_semaphore_fd
  GL_EXT_shader_clock
  GL_EXT_shader_image_load_formatted
  GL_EXT_shader_integer_mix
  GL_EXT_texture_array
  GL_EXT_texture_compression_dxt1
  GL_EXT_texture_compression_rgtc
  GL_EXT_texture_compression_s3tc
  GL_EXT_texture_filter_anisotropic
  GL_EXT_texture_integer
  GL_EXT_texture_sRGB
  GL_EXT_texture_sRGB_R8
  GL_EXT_texture_sRGB_RG8
  GL_EXT_texture_sRGB_decode
  GL_EXT_texture_shared_exponent
  GL_EXT_texture_snorm
  GL_EXT_texture_storage
  GL_EXT_texture_swizzle
  GL_EXT_timer_query
  GL_EXT_transform_feedback
  GL_EXT_vertex_array_bgra
  GL_EXT_vertex_attrib_64bit
  GL_IBM_multimode_draw_arrays
  GL_KHR_blend_equation_advanced
  GL_KHR_context_flush_control
  GL_KHR_debug
  GL_KHR_no_error
  GL_KHR_parallel_shader_compile
  GL_KHR_robust_buffer_access_behavior
  GL_KHR_robustness
  GL_KHR_texture_compression_astc_hdr
  GL_KHR_texture_compression_astc_ldr
  GL_KHR_texture_compression_astc_sliced_3d
  GL_MESA_pack_invert
  GL_MESA_shader_integer_functions
  GL_MESA_texture_signed_rgba
  GL_NVX_gpu_memory_info
  GL_NV_conditional_render
  GL_NV_copy_image
  GL_NV_depth_clamp
  GL_NV_packed_depth_stencil
  GL_NV_shader_atomic_int64
  GL_NV_texture_barrier
  GL_NV_timeline_semaphore
  GL_OES_EGL_image
  GL_OVR_multiview
  GL_OVR_multiview2
  GL_OVR_multiview_multisampled_render_to_texture
  GL_S3_s3tc
```

## Implementation limits

```
  GL_MAX_TEXTURE_SIZE                                        16384
  GL_MAX_3D_TEXTURE_SIZE                                     65536
  GL_MAX_CUBE_MAP_TEXTURE_SIZE                               65536
  GL_MAX_ARRAY_TEXTURE_LAYERS                                65536
  GL_MAX_TEXTURE_IMAGE_UNITS                                 32
  GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS                          32
  GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS                        160
  GL_MAX_GEOMETRY_TEXTURE_IMAGE_UNITS                        32
  GL_MAX_TESS_CONTROL_TEXTURE_IMAGE_UNITS                    0
  GL_MAX_TESS_EVALUATION_TEXTURE_IMAGE_UNITS                 0
  GL_MAX_VERTEX_ATTRIBS                                      16
  GL_MAX_VERTEX_ATTRIB_BINDINGS                              16
  GL_MAX_VERTEX_ATTRIB_RELATIVE_OFFSET                       2047
  GL_MAX_VERTEX_UNIFORM_COMPONENTS                           16384
  GL_MAX_FRAGMENT_UNIFORM_COMPONENTS                         16384
  GL_MAX_VARYING_COMPONENTS                                  64
  GL_MAX_VARYING_VECTORS                                     16
  GL_MAX_VERTEX_OUTPUT_COMPONENTS                            128
  GL_MAX_FRAGMENT_INPUT_COMPONENTS                           128
  GL_MAX_VERTEX_UNIFORM_BLOCKS                               15
  GL_MAX_GEOMETRY_UNIFORM_BLOCKS                             12
  GL_MAX_FRAGMENT_UNIFORM_BLOCKS                             15
  GL_MAX_COMBINED_UNIFORM_BLOCKS                             66
  GL_MAX_UNIFORM_BUFFER_BINDINGS                             105
  GL_MAX_UNIFORM_BLOCK_SIZE                                  1048576
  GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT                         16
  GL_MAX_COMBINED_VERTEX_UNIFORM_COMPONENTS                  3948544
  GL_MAX_COMBINED_FRAGMENT_UNIFORM_COMPONENTS                3948544
  GL_MAX_COMBINED_GEOMETRY_UNIFORM_COMPONENTS                65536
  GL_MAX_VIEWPORT_DIMS                                       16384 16384
  GL_MAX_SAMPLES                                             8
  GL_MAX_COLOR_TEXTURE_SAMPLES                               8
  GL_MAX_DEPTH_TEXTURE_SAMPLES                               8
  GL_MAX_INTEGER_SAMPLES                                     8
  GL_MAX_DRAW_BUFFERS                                        8
  GL_MAX_COLOR_ATTACHMENTS                                   8
  GL_MAX_RENDERBUFFER_SIZE                                   16384
  GL_MAX_DUAL_SOURCE_DRAW_BUFFERS                            8
  GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_ATTRIBS                 4
  GL_MAX_TRANSFORM_FEEDBACK_INTERLEAVED_COMPONENTS           64
  GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_COMPONENTS              4
  GL_MAX_GEOMETRY_OUTPUT_VERTICES                            0
  GL_MAX_GEOMETRY_TOTAL_OUTPUT_COMPONENTS                    0
  GL_MAX_GEOMETRY_INPUT_COMPONENTS                           64
  GL_MAX_GEOMETRY_OUTPUT_COMPONENTS                          64
  GL_MAX_PATCH_VERTICES                                      0
  GL_MAX_TESS_CONTROL_TOTAL_OUTPUT_COMPONENTS                0
  GL_MAX_TESS_CONTROL_INPUT_COMPONENTS                       0
  GL_MAX_TESS_CONTROL_OUTPUT_COMPONENTS                      0
  GL_MAX_TESS_EVALUATION_INPUT_COMPONENTS                    0
  GL_MAX_TESS_EVALUATION_OUTPUT_COMPONENTS                   0
  GL_MAX_TESS_GEN_LEVEL                                      0
  GL_MAX_FRAMEBUFFER_WIDTH                                   16384
  GL_MAX_FRAMEBUFFER_HEIGHT                                  16384
  GL_MAX_FRAMEBUFFER_LAYERS                                  65536
  GL_MAX_FRAMEBUFFER_SAMPLES                                 8
  GL_MAX_TEXTURE_BUFFER_SIZE                                 134217728
  GL_MAX_TEXTURE_LOD_BIAS                                    31
  GL_MAX_TEXTURE_MAX_ANISOTROPY                              16
  GL_MAX_IMAGE_UNITS                                         256
  GL_MAX_COMBINED_IMAGE_UNITS_AND_FRAGMENT_OUTPUTS           112
  GL_MAX_SUBROUTINES                                         256
  GL_MAX_SUBROUTINE_UNIFORM_LOCATIONS                        1024
  GL_MAX_DEBUG_MESSAGE_LENGTH                                4096
  GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS                      40
  GL_MAX_COMBINED_SHADER_STORAGE_BLOCKS                      40
  GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS                        0
  GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS                      16
  GL_MAX_GEOMETRY_SHADER_STORAGE_BLOCKS                      8
  GL_MAX_TESS_CONTROL_SHADER_STORAGE_BLOCKS                  8
  GL_MAX_TESS_EVALUATION_SHADER_STORAGE_BLOCKS               8
  GL_MAX_SHADER_STORAGE_BLOCK_SIZE                           2147483647
  GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT                  16
  GL_MAX_VERTEX_ATOMIC_COUNTER_BUFFERS                       0
  GL_MAX_FRAGMENT_ATOMIC_COUNTER_BUFFERS                     16
  GL_MAX_GEOMETRY_ATOMIC_COUNTERS                            0
  GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS                      1024
  GL_MAX_COMPUTE_WORK_GROUP_COUNT                            0 0 0
  GL_MAX_COMPUTE_WORK_GROUP_SIZE                             0 0 0
  GL_MAX_COMPUTE_UNIFORM_BLOCKS                              15
  GL_MAX_COMPUTE_UNIFORM_COMPONENTS                          16384
  GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS                         32
  GL_MAX_COMPUTE_IMAGE_UNIFORMS                              32
  GL_MAX_COMPUTE_ATOMIC_COUNTER_BUFFERS                      16
  GL_MAX_COMPUTE_ATOMIC_COUNTERS                             4096
  GL_MAX_COMBINED_COMPUTE_UNIFORM_COMPONENTS                 3948544
  GL_MAX_COMPUTE_SHARED_MEMORY_SIZE                          32768
  GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS                       16
  GL_SUBPIXEL_BITS                                           4
```

## Notes

- GL has no native driver here (only proprietary kbase `/dev/mali0`, no
  panfrost/panthor DRM node), so GL runs through Mesa Zink (GL-on-Vulkan)
  on top of PanVK: `GALLIUM_DRIVER=zink`, renderer string proves the path.
- Tessellation/geometry shader stage limits read 0 — no GS/TS stages
  (`geometryShader=no`, `tessellationShader=no` in Vulkan), consistent with
  the GL 3.3 cap. For the 3.3-compat profile (292 extensions, legacy
  ARB fixed-function set) see raw output `/tmp/gl33compat.txt`.
- Raw outputs: `/tmp/gl32forced.txt`, `/tmp/gl33core.txt`,
  `/tmp/gl33compat.txt`, `/tmp/gles31.txt` (GLES 3.1, 148 extensions).
""",
        "gpu/mali/OpenGL-4.5-4.6-Mali-PanVK-zink.md" to """
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
export VK_ICD_FILENAMES="${'$'}VK_DRIVER_FILES"
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
""",
        "gpu/mali/Vulkan-PanVK-Mali-0xa8671030.md" to """
# Vulkan feature + extension set — on-device query

## Device / driver identity (queried live, not from spec sheets)

```
device0: Mali unknown 0xa8671030 MC6 api 1.4.352 vid 0x13b5 did 0xa8671030 type 1 (integrated)
driverID: 20  driverName: panvk
driverInfo: Mesa 26.2.0-devel
queue families (1): q0: flags=0x7 (GFX|COMPUTE|TRANSFER) count=2 tsBits=64
memory types (3): t0 flags=0x1 heap=0 / t1 flags=0xb heap=0 / t2 flags=0x7 heap=0
memory heaps (1): heap0 = 5498 MB, flags=0x1 (DEVICE_LOCAL)
```

> `MESA: warning: panvk: unknown gpu_id (0xa8671030) or variant (0x4);`
> `continuing with conservative "Mali (unknown Valhall)" defaults — expect issues`
> The driver does not recognise this GPU (MediaTek SoC, Mali Valhall-v10 family,
> product-major 7, 6 cores → G610-class) and runs on conservative defaults.

## Instance extensions (22, loader)

```
  VK_KHR_device_group_creation (v1)
  VK_KHR_display (v23)
  VK_KHR_external_fence_capabilities (v1)
  VK_KHR_external_memory_capabilities (v1)
  VK_KHR_external_semaphore_capabilities (v1)
  VK_KHR_get_display_properties2 (v1)
  VK_KHR_get_physical_device_properties2 (v2)
  VK_KHR_get_surface_capabilities2 (v1)
  VK_KHR_surface (v25)
  VK_KHR_surface_maintenance1 (v1)
  VK_KHR_xcb_surface (v6)
  VK_KHR_xlib_surface (v6)
  VK_EXT_acquire_drm_display (v1)
  VK_EXT_acquire_xlib_display (v1)
  VK_EXT_debug_report (v10)
  VK_EXT_debug_utils (v2)
  VK_EXT_direct_mode_display (v1)
  VK_EXT_display_surface_counter (v1)
  VK_EXT_headless_surface (v1)
  VK_EXT_surface_maintenance1 (v1)
  VK_KHR_portability_enumeration (v1)
  VK_LUNARG_direct_driver_loading (v1)
```

## Device extensions (168)

```
  VK_KHR_8bit_storage (ver 1)
  VK_KHR_16bit_storage (ver 1)
  VK_KHR_bind_memory2 (ver 1)
  VK_KHR_buffer_device_address (ver 1)
  VK_KHR_calibrated_timestamps (ver 1)
  VK_KHR_copy_commands2 (ver 1)
  VK_KHR_create_renderpass2 (ver 1)
  VK_KHR_dedicated_allocation (ver 3)
  VK_KHR_depth_clamp_zero_one (ver 1)
  VK_KHR_depth_stencil_resolve (ver 1)
  VK_KHR_descriptor_update_template (ver 1)
  VK_KHR_device_group (ver 4)
  VK_KHR_draw_indirect_count (ver 1)
  VK_KHR_driver_properties (ver 1)
  VK_KHR_dynamic_rendering (ver 1)
  VK_KHR_dynamic_rendering_local_read (ver 1)
  VK_KHR_external_fence (ver 1)
  VK_KHR_external_fence_fd (ver 1)
  VK_KHR_external_memory (ver 1)
  VK_KHR_external_memory_fd (ver 1)
  VK_KHR_external_semaphore (ver 1)
  VK_KHR_external_semaphore_fd (ver 1)
  VK_KHR_format_feature_flags2 (ver 2)
  VK_KHR_get_memory_requirements2 (ver 1)
  VK_KHR_global_priority (ver 1)
  VK_KHR_image_format_list (ver 1)
  VK_KHR_imageless_framebuffer (ver 1)
  VK_KHR_index_type_uint8 (ver 1)
  VK_KHR_line_rasterization (ver 1)
  VK_KHR_load_store_op_none (ver 1)
  VK_KHR_maintenance1 (ver 2)
  VK_KHR_maintenance2 (ver 1)
  VK_KHR_maintenance3 (ver 1)
  VK_KHR_maintenance4 (ver 2)
  VK_KHR_maintenance5 (ver 1)
  VK_KHR_maintenance6 (ver 1)
  VK_KHR_maintenance7 (ver 1)
  VK_KHR_maintenance8 (ver 1)
  VK_KHR_maintenance9 (ver 1)
  VK_KHR_map_memory2 (ver 1)
  VK_KHR_multiview (ver 1)
  VK_KHR_pipeline_binary (ver 1)
  VK_KHR_pipeline_executable_properties (ver 1)
  VK_KHR_pipeline_library (ver 1)
  VK_KHR_present_id (ver 1)
  VK_KHR_present_id2 (ver 1)
  VK_KHR_present_wait (ver 1)
  VK_KHR_present_wait2 (ver 1)
  VK_KHR_push_descriptor (ver 2)
  VK_KHR_relaxed_block_layout (ver 1)
  VK_KHR_robustness2 (ver 1)
  VK_KHR_sampler_mirror_clamp_to_edge (ver 3)
  VK_KHR_sampler_ycbcr_conversion (ver 14)
  VK_KHR_separate_depth_stencil_layouts (ver 1)
  VK_KHR_shader_atomic_int64 (ver 1)
  VK_KHR_shader_clock (ver 1)
  VK_KHR_shader_draw_parameters (ver 1)
  VK_KHR_shader_expect_assume (ver 1)
  VK_KHR_shader_float16_int8 (ver 1)
  VK_KHR_shader_float_controls (ver 4)
  VK_KHR_shader_float_controls2 (ver 1)
  VK_KHR_shader_integer_dot_product (ver 1)
  VK_KHR_shader_maximal_reconvergence (ver 1)
  VK_KHR_shader_non_semantic_info (ver 1)
  VK_KHR_shader_quad_control (ver 1)
  VK_KHR_shader_relaxed_extended_instruction (ver 1)
  VK_KHR_shader_subgroup_extended_types (ver 1)
  VK_KHR_shader_subgroup_rotate (ver 2)
  VK_KHR_shader_subgroup_uniform_control_flow (ver 1)
  VK_KHR_shader_terminate_invocation (ver 1)
  VK_KHR_shader_untyped_pointers (ver 1)
  VK_KHR_spirv_1_4 (ver 1)
  VK_KHR_storage_buffer_storage_class (ver 1)
  VK_KHR_swapchain (ver 70)
  VK_KHR_swapchain_maintenance1 (ver 1)
  VK_KHR_swapchain_mutable_format (ver 1)
  VK_KHR_synchronization2 (ver 1)
  VK_KHR_timeline_semaphore (ver 2)
  VK_KHR_unified_image_layouts (ver 1)
  VK_KHR_uniform_buffer_standard_layout (ver 1)
  VK_KHR_variable_pointers (ver 1)
  VK_KHR_vertex_attribute_divisor (ver 1)
  VK_KHR_vulkan_memory_model (ver 3)
  VK_KHR_zero_initialize_workgroup_memory (ver 1)
  VK_EXT_4444_formats (ver 1)
  VK_EXT_astc_decode_mode (ver 1)
  VK_EXT_attachment_feedback_loop_dynamic_state (ver 1)
  VK_EXT_attachment_feedback_loop_layout (ver 2)
  VK_EXT_border_color_swizzle (ver 1)
  VK_EXT_buffer_device_address (ver 2)
  VK_EXT_calibrated_timestamps (ver 2)
  VK_EXT_color_write_enable (ver 1)
  VK_EXT_conditional_rendering (ver 2)
  VK_EXT_custom_border_color (ver 12)
  VK_EXT_depth_bias_control (ver 1)
  VK_EXT_depth_clamp_control (ver 1)
  VK_EXT_depth_clamp_zero_one (ver 1)
  VK_EXT_depth_clip_control (ver 1)
  VK_EXT_depth_clip_enable (ver 1)
  VK_EXT_descriptor_indexing (ver 2)
  VK_EXT_device_memory_report (ver 2)
  VK_EXT_display_control (ver 1)
  VK_EXT_dynamic_rendering_unused_attachments (ver 1)
  VK_EXT_extended_dynamic_state (ver 1)
  VK_EXT_extended_dynamic_state2 (ver 1)
  VK_EXT_extended_dynamic_state3 (ver 2)
  VK_EXT_external_memory_acquire_unmodified (ver 1)
  VK_EXT_external_memory_dma_buf (ver 1)
  VK_EXT_global_priority (ver 2)
  VK_EXT_global_priority_query (ver 1)
  VK_EXT_graphics_pipeline_library (ver 1)
  VK_EXT_hdr_metadata (ver 3)
  VK_EXT_host_image_copy (ver 1)
  VK_EXT_host_query_reset (ver 1)
  VK_EXT_image_2d_view_of_3d (ver 1)
  VK_EXT_image_drm_format_modifier (ver 2)
  VK_EXT_image_robustness (ver 1)
  VK_EXT_image_view_min_lod (ver 1)
  VK_EXT_index_type_uint8 (ver 1)
  VK_EXT_inline_uniform_block (ver 1)
  VK_EXT_legacy_dithering (ver 2)
  VK_EXT_line_rasterization (ver 1)
  VK_EXT_load_store_op_none (ver 1)
  VK_EXT_map_memory_placed (ver 1)
  VK_EXT_memory_budget (ver 1)
  VK_EXT_multisampled_render_to_single_sampled (ver 1)
  VK_EXT_mutable_descriptor_type (ver 1)
  VK_EXT_nested_command_buffer (ver 1)
  VK_EXT_non_seamless_cube_map (ver 1)
  VK_EXT_physical_device_drm (ver 1)
  VK_EXT_pipeline_creation_cache_control (ver 3)
  VK_EXT_pipeline_creation_feedback (ver 1)
  VK_EXT_pipeline_robustness (ver 1)
  VK_EXT_present_timing (ver 3)
  VK_EXT_primitive_topology_list_restart (ver 1)
  VK_EXT_private_data (ver 1)
  VK_EXT_provoking_vertex (ver 1)
  VK_EXT_queue_family_foreign (ver 1)
  VK_EXT_robustness2 (ver 1)
  VK_EXT_sampler_filter_minmax (ver 2)
  VK_EXT_scalar_block_layout (ver 1)
  VK_EXT_separate_stencil_usage (ver 1)
  VK_EXT_shader_atomic_float (ver 1)
  VK_EXT_shader_demote_to_helper_invocation (ver 1)
  VK_EXT_shader_module_identifier (ver 1)
  VK_EXT_shader_replicated_composites (ver 1)
  VK_EXT_shader_stencil_export (ver 1)
  VK_EXT_shader_subgroup_ballot (ver 1)
  VK_EXT_shader_subgroup_vote (ver 1)
  VK_EXT_shader_uniform_buffer_unsized_array (ver 1)
  VK_EXT_subgroup_size_control (ver 2)
  VK_EXT_swapchain_maintenance1 (ver 1)
  VK_EXT_texel_buffer_alignment (ver 1)
  VK_EXT_texture_compression_astc_hdr (ver 1)
  VK_EXT_tooling_info (ver 1)
  VK_EXT_transform_feedback (ver 1)
  VK_EXT_vertex_attribute_divisor (ver 3)
  VK_EXT_vertex_input_dynamic_state (ver 2)
  VK_EXT_ycbcr_2plane_444_formats (ver 1)
  VK_EXT_ycbcr_image_arrays (ver 1)
  VK_EXT_zero_initialize_device_memory (ver 1)
  VK_ARM_scheduling_controls (ver 2)
  VK_ARM_shader_core_builtins (ver 2)
  VK_ARM_shader_core_properties (ver 1)
  VK_GOOGLE_decorate_string (ver 1)
  VK_GOOGLE_hlsl_functionality1 (ver 1)
  VK_GOOGLE_user_type (ver 1)
  VK_VALVE_mutable_descriptor_type (ver 1)
```

## Core features

### Vulkan 1.0 (55 features)

```
  robustBufferAccess                                   YES
  fullDrawIndexUint32                                  YES
  imageCubeArray                                       YES
  independentBlend                                     YES
  geometryShader                                       no
  tessellationShader                                   no
  sampleRateShading                                    YES
  dualSrcBlend                                         YES
  logicOp                                              YES
  multiDrawIndirect                                    YES
  drawIndirectFirstInstance                            YES
  depthClamp                                           YES
  depthBiasClamp                                       YES
  fillModeNonSolid                                     YES
  depthBounds                                          no
  wideLines                                            YES
  largePoints                                          YES
  alphaToOne                                           no
  multiViewport                                        no
  samplerAnisotropy                                    YES
  textureCompressionETC2                               YES
  textureCompressionASTC_LDR                           YES
  textureCompressionBC                                 no
  occlusionQueryPrecise                                YES
  pipelineStatisticsQuery                              no
  vertexPipelineStoresAndAtomics                       no
  fragmentStoresAndAtomics                             YES
  shaderImageGatherExtended                            YES
  shaderStorageImageExtendedFormats                    YES
  shaderStorageImageMultisample                        no
  shaderStorageImageReadWithoutFormat                  YES
  shaderStorageImageWriteWithoutFormat                 YES
  shaderUniformBufferArrayDynamicIndexing              YES
  shaderSampledImageArrayDynamicIndexing               YES
  shaderStorageBufferArrayDynamicIndexing              YES
  shaderStorageImageArrayDynamicIndexing               YES
  shaderClipDistance                                   no
  shaderCullDistance                                   no
  shaderFloat64                                        no
  shaderInt64                                          YES
  shaderInt16                                          YES
  shaderResourceResidency                              no
  shaderResourceMinLod                                 no
  sparseBinding                                        no
  sparseResidencyBuffer                                no
  sparseResidencyImage2D                               no
  sparseResidencyImage3D                               no
  sparseResidency2Samples                              no
  sparseResidency4Samples                              no
  sparseResidency8Samples                              no
  sparseResidency16Samples                             no
  sparseResidencyAliased                               no
  variableMultisampleRate                              no
  inheritedQueries                                     no
```

### Vulkan 1.1

```
multiview                                            YES
  shaderDrawParameters                                 YES
  samplerYcbcrConversion                               YES
  variablePointers                                     YES
  storageBuffer16BitAccess                             YES
  uniformAndStorageBuffer16BitAccess                   YES
  storagePushConstant16                                YES
  storageInputOutput16                                 YES
```

### Vulkan 1.2

```
drawIndirectCount                                    YES
  storageBuffer8BitAccess                              YES
  uniformAndStorageBuffer8BitAccess                    YES
  shaderFloat16                                        YES
  shaderInt8                                           YES
  shaderBufferInt64Atomics                             YES
  shaderSharedInt64Atomics                             YES
  descriptorIndexing                                   YES
  runtimeDescriptorArray                               YES
  samplerFilterMinmax                                  YES
  scalarBlockLayout                                    YES
  imagelessFramebuffer                                 YES
  uniformBufferStandardLayout                          YES
  shaderSubgroupExtendedTypes                          YES
  separateDepthStencilLayouts                          YES
  hostQueryReset                                       YES
  timelineSemaphore                                    YES
  bufferDeviceAddress                                  YES
  vulkanMemoryModel                                    YES
  shaderOutputViewportIndex                            no
  subgroupBroadcastDynamicId                           YES
```

### Vulkan 1.3

```
robustImageAccess                                    YES
  inlineUniformBlock                                   YES
  pipelineCreationCacheControl                         YES
  descriptorBindingInlineUniformBlockUpdateAfterBind   YES
  privateData                                          YES
  shaderDemoteToHelperInvocation                       YES
  shaderTerminateInvocation                            YES
  subgroupSizeControl                                  YES
  computeFullSubgroups                                 YES
  synchronization2                                     YES
  textureCompressionASTC_HDR                           YES
  shaderZeroInitializeWorkgroupMemory                  YES
  dynamicRendering                                     YES
  shaderIntegerDotProduct                              YES
  maintenance4                                         YES
```

### Vulkan 1.4

```
globalPriorityQuery                                  YES
  shaderSubgroupRotate                                 YES
  shaderSubgroupRotateClustered                        YES
  shaderFloatControls2                                 YES
  shaderExpectAssume                                   YES
  rectangularLines                                     YES
  bresenhamLines                                       YES
  smoothLines                                          no
  stippledRectangularLines                             no
  stippledBresenhamLines                               no
  stippledSmoothLines                                  no
  vertexAttributeInstanceRateDivisor                   YES
  vertexAttributeInstanceRateZeroDivisor               YES
  indexTypeUint8                                       YES
  dynamicRenderingLocalRead                            YES
  maintenance5                                         YES
  maintenance6                                         YES
  pipelineProtectedAccess                              no
  pipelineRobustness                                   YES
  hostImageCopy                                        YES
  pushDescriptor                                       YES
```

## How obtained

- No `vulkaninfo` on device. Wrote `/opt/nativecode/panvk/ext_probe.c` (instance +
  device enumeration, 168 extensions) and `/tmp/vkfeat.c` + `/tmp/vkfeat10.c`
  (features 1.0–1.4, compiled against real Vulkan-Headers v1.4.352).
- Env: `LD_LIBRARY_PATH=/opt/nativecode/panvk/lib`
  `VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/panvk_kbase.json`.
- Raw outputs: `/tmp/vk_ext_full.txt`, `/tmp/vk_feat_full.txt`, `/tmp/vk_feat10_full.txt`.
""",
        "vpu-mediacodec.md" to """
# NativeCode VPU Hardware Acceleration Bridge

## Overview
NativeCode bridges hardware video decoding and encoding between the isolated PRoot Linux guest and the Android host process via an unprivileged `AF_UNIX` abstract socket (`@nativecode.media.v1`).

Because PRoot Linux userspace has no access to Android's `/dev/binder` or `/dev/video*` HAL nodes due to Android SELinux domain restrictions, direct hardware MediaCodec calls from glibc binaries fail. NativeCode solves this by running a zero-copy MediaCodec daemon on the Android host that communicates with guest tools (`nativecode-media`, PRoot FFmpeg).

---

## Hardware Codecs Supported

| Codec | MIME Type | Hardware Decoder | Hardware Encoder |
|---|---|---|---|
| **H.264 / AVC** | `video/avc` | `c2.mtk.avc.decoder` / `c2.qti.avc.decoder` | `c2.mtk.avc.encoder` / `c2.qti.avc.encoder` |
| **HEVC / H.265** | `video/hevc` | `c2.mtk.hevc.decoder` / `c2.qti.hevc.decoder` | `c2.mtk.hevc.encoder` / `c2.qti.hevc.encoder` |
| **AV1** | `video/av01` | `c2.mtk.av1.decoder` / `c2.qti.av1.decoder` | Software fallback (`c2.android.av1.encoder`) |
| **VP9** | `video/x-vnd.on2.vp9` | `c2.mtk.vp9.decoder` / `c2.qti.vp9.decoder` | Software fallback (`c2.android.vp9.encoder`) |

*DRM `.secure` codecs (e.g. `c2.mtk.avc.decoder.secure`) are filtered automatically to prevent non-secure surface failures.*

---

## CLI Tool: `nativecode-media`

Prepackaged in `/usr/local/bin/nativecode-media` inside the PRoot environment.

### 1. Check VPU Hardware Capabilities
```bash
nativecode-media capabilities
```
Returns JSON containing device SoC, Android API level, supported resolutions (720p, 1080p, 4K), and all hardware decoders and encoders.

### 2. Hardware VPU File Decode Benchmark
```bash
nativecode-media benchmark_file \
  --path "/home/flux/test_1080p30.mp4" \
  --mode surface
```
- `--path`: Video file path (guest `/home/flux/...`, `/tmp/...`, `/sdcard/...`, or relative path).
- `--mode`: `surface` (zero-copy `ImageReader` drain, ~204 FPS) or `buffer` (bytebuffer discard).
- `--mime`: Target MIME (default: `video/avc`).

### 3. Hardware VPU Encode Benchmark
```bash
nativecode-media benchmark_encode \
  --width 1280 \
  --height 720 \
  --frames 150 \
  --fps 30 \
  --bitrate 4000000 \
  --output "/home/flux/encoded.mp4"
```
Synthesizes moving YUV patterns, feeds hardware MediaCodec encoder (`c2.mtk.avc.encoder` or `c2.qti.avc.encoder`), and muxes to an MP4 container.

### 4. Live FFmpeg to Host VPU Streaming Encode
Pipes raw YUV frames from PRoot FFmpeg into the hardware VPU encoder:
```bash
ffmpeg -f lavfi -i testsrc=duration=5:size=1280x720:rate=30 -pix_fmt nv12 -f rawvideo - | \
  nativecode-media encode_stream \
    --width 1280 \
    --height 720 \
    --fps 30 \
    --bitrate 4000000 \
    --output "/home/flux/stream_encoded.mp4"
```
- Raw frames travel over Linux abstract socket kernel buffer.
- MediaCodec hardware encoder writes compressed NALUs into `MediaMuxer`.
- Verified at **162.7 FPS (5.4x realtime)** on MediaTek Dimensity 8300-Ultra and hardware encoding on Snapdragon 8 Gen 3.

---

## Allowed Paths & Guest Path Resolution

The host MediaBridge automatically resolves guest-relative and container paths to host storage:
- **Guest Home & Workspaces:** `/home/flux/...`, `~/...`, and relative paths resolve inside the PRoot rootfs.
- **Guest Tmp:** `/tmp/...` resolves to the shared host tmp directory (`${'$'}PREFIX/tmp/`).
- **External Storage / Downloads:** `/sdcard/...` and `/storage/emulated/0/...` resolve to Android shared user storage.
- **Host App Sandbox:** `/data/data/com.zenithblue.nativecode/...` paths are permitted.
- **Security:** Arbitrary paths outside app sandbox and external storage (e.g. `/system`, `/vendor`, `/data/system`) are rejected with `Path traversal or unauthorized path rejected`.

---

## Performance Reference (Poco X6 Pro / Dimensity 8300-Ultra)

| Operation | Pipeline | Resolution / Format | Measured Performance |
|---|---|---|---|
| **Decode** | MediaExtractor → Hardware MediaCodec → Surface | 1080p30 H.264 | **204.6 FPS (6.8x realtime)** |
| **Decode** | MediaExtractor → Hardware MediaCodec → Surface | 720p30 H.264 | **185.8 FPS (6.2x realtime)** |
| **Encode (Stream)** | PRoot FFmpeg raw YUV → AF_UNIX → MediaCodec → MP4 | 720p30 H.264 (4 Mbps) | **162.7 FPS (5.4x realtime)** |
""",
    )
}
