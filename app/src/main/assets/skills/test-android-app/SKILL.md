---
name: test-android-app
description: A debug APK/package and an explicitly authorized Android test target. Complete this task inside NativeCode using
  the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Test Android App
  category: PROJECT
  group: Development
  engine: NativeCode APK bridge + authorized ADB when needed
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Test Android App

**Use for:** A debug APK/package and an explicitly authorized Android test target.

**Deliver:** Installation/launch evidence, filtered logs, screenshots when permitted, and a reproducible bug report.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:$PATH"
uname -m
cat /etc/os-release
getconf GNU_LIBC_VERSION 2>/dev/null || ldd --version 2>&1 | head -1
```

Reuse this check when it was already done in the current task. The supplied installation records target ARM64 Alpaquita/glibc. Do not mix foreign Alpine/Debian repositories, use x86 binaries, assume CUDA, use systemd/Docker, or change NativeCode drivers. Use existing NativeCode bridges instead of opening/chmodding Android device nodes.

For a required download/extraction, reuse existing utilities. If one is missing, install only the corresponding native `curl`, `ca-certificates`, `tar`, `xz`, `unzip`, `coreutils` or `binutils` package (`ar`) in the authorized root terminal. Do not install a full compiler suite for a binary-only task.

**When executing this skill later:** install only missing task packages in the project's environment. Run `apk` changes only in NativeCode's authorized root terminal using the active distribution's repositories; return to `flux` for builds and content. Do not assume `sudo` is available. Keep existing versions that satisfy the project. Ask before large model downloads, paid/external services or destructive actions. Merely adding this Markdown to the Skills page must execute nothing.

## Install what this task needs

### ADB, only for an authorized device test

Reuse `/home/flux/.local/bin/adb` or `/opt/android-sdk/platform-tools/adb`; check `adb version` and the real binary's architecture. No ADB is needed just to offer an APK to Android's Package Installer through NativeCode.

If ADB is absent, the supplied verified ARM64 prebuilt route is:

```text
https://github.com/zhuwanhong/android-sdk-linux-arm64/releases/download/v1.0.0/android-sdk-linux-arm64-36.0.0-ours.tar.gz
SHA-256: f82700229b117aca7720c227bec1e62259071b5e1f0d60e0569244149aee357a
```

Download to a temporary directory, verify this exact artifact, inspect/extract it safely, and require an aarch64 `platform-tools/adb` that passes `adb version`. Preserve its companion files under a versioned `/opt/android-sdk/platform-tools-arm64-35.0.2` path, or an authorized user-local tool path, and expose a small `/home/flux/.local/bin/adb` wrapper. The archive label is not the embedded ADB version: record what the binary reports. Never overwrite an unknown SDK installation, install a desktop x86 binary, or compile a new ADB as a hidden fallback.

Start/reuse the user's ADB server without killing an existing session. For wireless debugging use the exact Android-provided `HOST:PAIR_PORT` with `adb pair`, then the separate `HOST:CONNECT_PORT` with `adb connect`. Require the user's authorization. No network scan, authentication bypass or raw USB/device-node workaround is allowed.

## Do the task

1. Identify the exact APK/package and requested behavior; read the project's existing tests and NativeCode bridge availability. Do not install a toolchain when only APK testing is requested.
2. Offer the APK using `nativecode-agent install-apk --path <actual-apk>`. This opens a user-confirmed Android installer; it neither silently installs nor proves launch success.
3. For automation, use `adb devices -l`, select the user's authorized serial, and use `adb -s "$SERIAL" ...` consistently. Do not choose a device merely because it is first in the list.
4. Reproduce the exact flow using the existing test harness or authorized ADB commands. For a known package, capture PID-scoped logcat where supported, test-owned screenshots with `exec-out screencap -p`, and the exact crash/system evidence needed. Do not clear all device logs or collect unrelated app data.
5. Verify launch, back/rotation/resume behavior and the requested feature. GPU rendering belongs to the Android app/host; guest GPU diagnostics are not proof of app rendering correctness. Stop only test-owned activity/processes and keep original app data unless reset was explicitly authorized.

## Check the actual result

Return device/Android/app version, reproduction steps, actual pass/fail observations, scoped logs and screenshots. Distinguish offered APK, installed package, launched activity and verified behavior. No authorized device means device testing remains unverified; it is not a reason to bypass pairing.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://developer.android.com/tools/adb
- https://developer.android.com/build/building-cmdline
