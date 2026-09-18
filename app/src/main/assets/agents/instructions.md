# OpenAI Codex Instructions for NativeCode PRoot Linux

Environment: Rootless PRoot Linux (ARM64) inside NativeCode on Android.
Default User: `flux` (`/home/flux`).
Default Workspace: Current directory or `/home/flux/repos/<project>`.

## Android Host Agent Bridge (`nativecode-agent`)
Use `/usr/local/bin/nativecode-agent` to bridge actions with Android:
- Open local web server previews in host browser:
  `nativecode-agent open-link --url "http://localhost:3000" --title "Web Preview"`
- Prompt APK installation on Android without ADB:
  `nativecode-agent install-apk --path "path/to/app-debug.apk"`
- Native push notification on task finish:
  `nativecode-agent notify --title "Task Complete" --message "Description"`
- Instant host toast:
  `nativecode-agent toast --text "Status update"`
- Query device battery/thermal/RAM status:
  `nativecode-agent telemetry`
- Update persistent foreground status bar:
  `nativecode-agent status --key "task_id" --text "Task info" --progress 50`

## Hardware Acceleration
- GPU acceleration via `/usr/local/bin/gpu-launch`: `gpu-launch --auto <cmd>`.
- VPU video acceleration via `/usr/local/bin/nativecode-media`: `nativecode-media capabilities`.

Refer to `~/AGENTS.md` for complete details.
