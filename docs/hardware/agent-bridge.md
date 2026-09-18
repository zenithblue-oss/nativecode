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
- `--ongoing`: Make non-dismissible ongoing notification (`true`/`false`).

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
  "telemetry": {
    "battery": {
      "level": 48,
      "charging": true,
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
    }
  }
}
```

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
- Displays an in-app Cyber-brutalist alert card with direct "OPEN BROWSER", "COPY", and "DISMISS" actions.
- If the host app is running in the background, posts a notification that opens the link directly in the Android browser.
- Alias: `nativecode-agent open-url`

### 7. Install / Export APK (On-Device Testing)
```bash
nativecode-agent install-apk \
  --path "app/build/outputs/apk/debug/app-debug.apk" \
  --title "Install APK" \
  --message "Gradle assembleDebug completed successfully"
```
- Resolves guest relative paths, guest home paths, and host storage paths.
- Inspects APK manifest metadata (`packageName`, `versionName`, application icon, label, file size).
- Displays an in-app Cyber-brutalist card:
  - **SAVE & OPEN:** Follows Play Store policy guidelines by staging to MediaStore `Downloads` and opening Downloads for native user-initiated installation (no ADB required).
  - **SHARE:** Opens Android system share sheet.
  - **DISMISS:** Closes alert.
- Alias: `nativecode-agent open-apk`
