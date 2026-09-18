# Claude Code Guidelines for NativeCode PRoot Linux

You are running inside NativeCode's rootless PRoot Linux environment (ARM64 Alpine/Alpaquita).

## Host Interaction via Agent Bridge (`nativecode-agent`)
Always leverage `/usr/local/bin/nativecode-agent` to interact with the Android host:
- **Dev Servers / Web Previews:** When starting servers on `http://localhost:<port>`, trigger an in-app browser alert:
  `nativecode-agent open-link --url "http://localhost:3000" --title "Web Preview"`
- **APK Testing:** When an Android APK is built, trigger on-device install dialog:
  `nativecode-agent install-apk --path "path/to/app-debug.apk" --title "Install APK"`
- **Dismiss Alert:** Programmatically dismiss active on-screen alert card:
  `nativecode-agent dismiss-alert`
- **Single Active Alert Rule:** Exactly one alert can be active on screen at a time. If an alert is already visible, new alert requests fail with `alert_already_active`. Call `dismiss-alert` first or wait for the user to close it.
- **Completion Notifications:** Send a push notification when long builds/tests finish:
  `nativecode-agent notify --title "Build Complete" --message "Completed in 12s" --priority high`
- **Toasts:** Display instant status messages:
  `nativecode-agent toast --text "Running test suite..."`
- **Hardware Telemetry:** Inspect battery, thermals, and memory before heavy parallel jobs:
  `nativecode-agent telemetry`

## Hardware Acceleration
- **GPU (`gpu-launch`):** For hardware-accelerated OpenGL/Vulkan, run `gpu-launch --auto <cmd>`. Adreno uses Turnip KGSL; Mali/others use Venus virtio socket. Never access `/dev/dri/card0` (EACCES is normal under PRoot).
- **VPU Video (`nativecode-media`):** Hardware MediaCodec video encoding/decoding via `@nativecode.media.v1`. Pipe FFmpeg into `nativecode-media encode_stream` for high-speed hardware encoding.

Refer to `/etc/AGENTS.md` and `~/AGENTS.md` for complete technical details.
