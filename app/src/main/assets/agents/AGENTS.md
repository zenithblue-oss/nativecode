# NativeCode Agent Environment & Hardware Bridge Guidelines

You are executing inside a rootless Linux PRoot guest environment hosted within the **NativeCode** Android application on an ARM64 device.

---

## 1. Environment & Container Architecture

- **Operating System:** Alpine / Alpaquita Linux (aarch64).
- **Active Users:**
  - Standard user: `flux` (`/home/flux`). Default for interactive terminals, projects, and CLI tools.
  - Root user: `root` (`/root`). Used for administrative package installation (`apk add`).
- **Project Workspaces:** Standard project directory is `/home/flux/repos/<project>` or your current working directory.
- **Network Namespace:** Shared directly with the Android host.
  - Binding to `127.0.0.1` or `0.0.0.0` on any unprivileged port (e.g. `3000`, `5173`, `8000`, `8080`) is immediately reachable on the host device at `http://localhost:<port>`.
- **Sandbox Restrictions:**
  - **No systemd:** Do not run `systemctl` or commands expecting a systemd init daemon.
  - **No raw HAL nodes:** Android SELinux restricts direct access to `/dev/binder`, `/dev/video*`, and `/dev/dri/card0`. Do not attempt to mount or chmod these nodes.
  - **No kernel modules:** `modprobe` and loop devices are unavailable.
  - **Hardware acceleration:** Access GPU and VPU hardware through the dedicated NativeCode bridges detailed below.

---

## 2. NativeCode Host Agent Bridge (`nativecode-agent`)

The Host Agent Bridge connects guest Linux processes to the Android UI and system services over an unprivileged `AF_UNIX` abstract socket (`@nativecode.agent.v1`).

CLI Binary: `/usr/local/bin/nativecode-agent` (already in `$PATH`).

### 2.1 Web Previews & Local Servers (`open-link`)
When starting dev servers (Vite, Next.js, Flask, FastAPI, Express, etc.), use `open-link` to prompt the user to open the preview in their Android browser:
```bash
nativecode-agent open-link \
  --url "http://localhost:3000" \
  --title "Web Preview" \
  --message "Vite dev server running on port 3000"
```
- **In-App Behavior:** Displays an interactive Cyber-brutalist alert card with **OPEN BROWSER**, **COPY**, and **DISMISS** buttons.
- **Background Behavior:** If NativeCode is minimized, posts a high-priority Android notification opening the URL directly in Chrome/system browser.
- **Alias:** `nativecode-agent open-url`

### 2.2 On-Device Android App Testing & Installation (`install-apk`)
When you build an Android application (e.g. `./gradlew assembleDebug` or `cargo apk build`), use `install-apk` to prompt installation on the host device without requiring ADB:
```bash
nativecode-agent install-apk \
  --path "app/build/outputs/apk/debug/app-debug.apk" \
  --title "Build Complete" \
  --message "app-debug.apk built in 14.2s"
```
- **Behavior:** Resolves relative and absolute paths, parses APK metadata, stages the APK to Android MediaStore `Downloads`, and presents an in-app card with **SAVE & OPEN** (launches Android Package Installer) and **SHARE** sheet actions.
- **Alias:** `nativecode-agent open-apk`

### 2.3 Notifications (`notify`)
Dispatch native Android notifications when long-running operations (builds, test suites, large git clones) finish:
```bash
nativecode-agent notify \
  --title "Compilation Finished" \
  --message "Cargo build finished with 0 errors" \
  --priority high
```
- `--priority`: `high`, `default`, or `low`.
- `--id`: Optional integer notification ID (default: `1001`).
- `--ongoing`: `true` or `false` (for ongoing background processes).

### 2.4 System Toasts (`toast`)
Display lightweight, non-blocking on-screen status updates on Android:
```bash
nativecode-agent toast --text "Running tests across 4 packages..."
```

### 2.5 Device Hardware Telemetry (`telemetry`)
Query real-time thermal, battery, and memory state before initiating resource-heavy tasks:
```bash
nativecode-agent telemetry
```
Output schema:
```json
{
  "v": 1,
  "ok": true,
  "telemetry": {
    "battery": {
      "level": 78,
      "charging": true,
      "temperature_c": 31.5
    },
    "memory": {
      "avail_mb": 4120,
      "total_mb": 7331,
      "low_memory": false
    },
    "power": {
      "power_save_mode": false,
      "interactive": true
    }
  }
}
```
*Tip: If `battery.temperature_c` > 42.0°C or `memory.low_memory` is true, reduce build concurrency (e.g. `make -j2` instead of `-j8`).*

### 2.6 Background Task Progress (`status`)
Update the persistent Android foreground service notification with progress during multi-step jobs:
```bash
nativecode-agent status \
  --key "data_sync" \
  --text "Downloading assets (45/100)" \
  --progress 45
```

### 2.7 Healthcheck (`ping`)
```bash
nativecode-agent ping
```

---

## 3. GPU Hardware Acceleration (`gpu-launch`)

NativeCode provides hardware-accelerated OpenGL and Vulkan via baked Mesa drivers and host bridge daemons.

CLI Binary: `/usr/local/bin/gpu-launch` (already in `$PATH`).

### 3.1 Hardware Backends
- **Qualcomm Snapdragon / Adreno:** Direct Turnip Vulkan driver over kernel `/dev/kgsl-3d0` node + Zink OpenGL over Turnip.
  - Run: `gpu-launch --turnip <command>` or `gpu-launch --auto <command>`
- **MediaTek Dimensity / Mali / Exynos / Tensor:** Venus virtio-gpu ICD communicating with host Android Vulkan via `$PREFIX/tmp/.virgl_test`.
  - Run: `gpu-launch --venus <command>` or `gpu-launch --auto <command>`
- **Fallback:** VirGL (`--virgl`) or llvmpipe software rasterizer (`--software`).

### 3.2 Diagnostics
```bash
gpu-launch --diagnose
```
*Note: Under PRoot, accessing `/dev/dri/card0` returns `EACCES`. This is expected by Android SELinux design and does not affect Turnip (KGSL) or Venus (vtest socket).*

---

## 4. VPU MediaCodec Hardware Video Acceleration (`nativecode-media`)

Hardware video decoding and encoding are bridged to Android's native `MediaCodec` HAL via an unprivileged `AF_UNIX` abstract socket (`@nativecode.media.v1`).

CLI Binary: `/usr/local/bin/nativecode-media` (already in `$PATH`).

### 4.1 Capabilities
Check host hardware encoders and decoders (AVC/H.264, HEVC/H.265, AV1, VP9):
```bash
nativecode-media capabilities
```

### 4.2 Hardware Video Decoding
Benchmark or run hardware-accelerated video decoding:
```bash
nativecode-media benchmark_file --path "/home/flux/sample.mp4" --mode surface
```
- Measured performance on modern SoCs reaches **200+ FPS (6x+ realtime)**.

### 4.3 High-Speed Video Encoding with Guest FFmpeg
Stream raw video frames directly from guest FFmpeg into Android's hardware MediaCodec encoder:
```bash
ffmpeg -f lavfi -i testsrc=duration=10:size=1280x720:rate=30 -pix_fmt nv12 -f rawvideo - | \
  nativecode-media encode_stream \
    --width 1280 \
    --height 720 \
    --fps 30 \
    --bitrate 4000000 \
    --output "/home/flux/output.mp4"
```
- Operates at **160+ FPS (5x+ realtime)** on mid-to-flagship SoCs, offloading heavy CPU encoding.

---

## 5. Agent Workflow Best Practices

1. **Keep User Informed:** Send a `nativecode-agent toast` on task start and a `nativecode-agent notify` when lengthy tasks complete.
2. **Expose Web Services:** Immediately call `nativecode-agent open-link --url "http://localhost:<port>"` after starting web servers or dev previews so the user can preview with one tap.
3. **Automate APK Testing:** After compiling Android apps, invoke `nativecode-agent install-apk --path "..."` so the user can test the build natively.
4. **Monitor Device Health:** Use `nativecode-agent telemetry` before running large parallel builds, LLM models, or renders.
5. **No Long-Lived Zombie Daemons:** Avoid infinite busy-wait loops or unmanaged background processes that drain the device battery.
