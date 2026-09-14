# NativeCode GPU acceleration

Guest Linux GL/Vulkan uses baked Mesa. The Android app never downloads GPU drivers and never executes GPU setup scripts from the Hardware page (Play: no remote executable code).

## Backends

| Host GPU | Method | Path |
|---|---|---|
| Snapdragon / Adreno / KGSL | **Turnip KGSL (Zink)** | Guest Turnip ICD → `/dev/kgsl-3d0` |
| Mali / Xclipse / PowerVR / other | **Venus** (Zink over Venus for OpenGL) | Guest virtio ICD → vtest socket → `virgl_test_server_android --venus` → Android `libvulkan.so` |
| Venus missing / Zink fail | **VirGL (virpipe)** | Guest virpipe → host vtest (no `--venus`) |
| Nothing else | **llvmpipe (software)** | CPU |

Runtime choice is guest `gpu-launch --auto` (functional probes). Host `GpuAccelDetector` is an install hint only.

## Host servers (Hardware page)

Settings → HARDWARE → HOST SERVERS. Start/stop/restart run the baked host binaries as the **app user** (FluxLinux style). The Android app does not download GPU drivers.

| Server | Binary | How |
|---|---|---|
| Venus | `usr/bin/virgl_test_server_android --venus --socket-path $PREFIX/tmp/.virgl_test` | Guest virtio ICD talks to this socket |
| PulseAudio | `usr/bin/pulseaudio --start --load=module-native-protocol-tcp ... --exit-idle-time=-1` | Guest `PULSE_SERVER=127.0.0.1` |

Both packages are built for `com.zenithblue.nativecode` (`scripts/build_nativecode_host_servers.sh`) and assembled into `bootstrap.tar`.

Host start (app uid, FluxLinux style):

```sh
# Venus (no PREFIX/lib in LD_LIBRARY_PATH)
LD_LIBRARY_PATH=$PREFIX/opt/virglrenderer-android/lib \
  /system/bin/linker64 $PREFIX/bin/virgl_test_server_android \
  --socket-path $PREFIX/tmp/.virgl_test --venus

# PulseAudio (termux-exec, no linker64 --start re-exec)
LD_PRELOAD=$PREFIX/lib/libtermux-exec-linker-ld-preload.so \
  $PREFIX/bin/pulseaudio --daemonize=no --disallow-exit \
  --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1" \
  --exit-idle-time=-1
```

Guest Mesa virtio ICD talks to that socket (`VN_DEBUG=vtest`, `VTEST_SOCKET_NAME=/tmp/.virgl_test`). It does **not** use `/dev/dri/card0` or `renderD128`. `open(/dev/dri/card0) → EACCES` under PRoot is expected and is not the Venus path.

Login shells source baked `/etc/profile.d/flux-gpu.sh`: KGSL → Turnip, else Zink over Venus. The Android app does not run GPU setup scripts. Hardware → HOST SERVERS shows Venus only on Mali; Snapdragon is Turnip/KGSL only.

Host Venus requires `virgl_render_server` (linker64-exec'd from `libexec/`). Without it, `vkCreateInstance` can succeed while `vkEnumeratePhysicalDevices` returns 0.

## State files (guest)

```
/etc/fluxlinux/gpu_mode      turnip | venus | virgl | software
/etc/fluxlinux/gpu_vendor    adreno/snapdragon | mali | ...
/etc/fluxlinux/gpu_backend   turnip | venus | zink-venus | virgl-angle | llvmpipe
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

- No curl/wget of Turnip/Mesa tarballs
- No marketplace-style remote `install.sh` for GPU
- Hardware UI is Kotlin file/property reads only
- Venus host server starts only from the existing XFCE `start_gui.sh` path
