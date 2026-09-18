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
- **Socket:** `AF_UNIX` Linux abstract namespace: `@nativecode.agent.v1` (with fallback to `@nativecode.agent.v1.<uid>`).
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
