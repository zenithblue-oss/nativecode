---
name: build-android-app
description: Existing Android project or a brief for a new Android-only application. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Build Android App
  category: PROJECT
  group: Development
  engine: Kotlin / Jetpack Compose + project Gradle wrapper
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Build Android App

**Use for:** Existing Android project or a brief for a new Android-only application.

**Deliver:** Editable project source, tests, debug APK when buildable, and build log.

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

### Android build tools, installed inline through the existing NativeCode route

```sh
command -v java; command -v javac
java -version
printf 'SDK: '; test -d /opt/android-sdk && printf '/opt/android-sdk\n'
find /opt/android-sdk/build-tools -maxdepth 2 -name aapt2 -type f 2>/dev/null
```

Reuse NativeCode's development pack and `/opt/android-sdk`, not a desktop x86 SDK bundle. If the pack is absent/incomplete, use NativeCode's existing development-pack install/repair control; this is a native application facility, **not a prerequisite skill**. Inspect the checkout's existing setup script only for a concrete missing component. There is no documented `nativecode-agent install-sdk` command; do not invent one or reimplement pack delivery.

Set `ANDROID_HOME=/opt/android-sdk` and `ANDROID_SDK_ROOT=/opt/android-sdk` in the task shell when that path exists. Select a JDK compatible with the project's Gradle/Android Gradle Plugin, preserve the checked-in wrapper, and install only SDK platforms/build-tools actually referenced by the project through the existing compatible pack mechanism. Add an NDK only for native code and only with ARM64-compatible host executables.

The supplied path `/opt/android-sdk/build-tools/36.1.0/aapt2` may be the working ARM64 override. Verify it with `file` and its version first; preserve an existing `android.aapt2FromMavenOverride` pointing to the working file rather than downloading an x86 Maven aapt2. Do not rewrite build scripts just because the version differs. If the native pack mechanism cannot supply a required component, report that exact component; do not replace the user's runtime.

## Do the task

1. Inspect the existing app/module, Gradle wrapper, manifests, Kotlin/Compose versions and tests. For a new app, create a minimal Kotlin/Compose project compatible with the installed Android pack; no Android Studio/emulator requirement.
2. Implement the requested screens and behavior, including small-screen layout, accessibility, loading/error states and storage/permission handling. Preserve the application's architecture and package identity.
3. Build with the existing wrapper and conservative workers; choose the real variant/module rather than assuming every project is named `app`:

```sh
./gradlew --version
./gradlew :app:assembleDebug --max-workers=2
```

4. Run the relevant existing unit/lint tasks that the checkout provides. Inspect the produced APK and logs; a Gradle success message without an APK is not delivery.
5. Offer the actual APK with `nativecode-agent install-apk --path <actual-apk>`. The app renders on the Android host; do not wrap Gradle/JDK with `gpu-launch`. Use an authorized connected device for on-device behavior tests when available; distinguish those from local unit tests.

## Check the actual result

Require nonempty APK, matching package/variant, real build/test exit codes and editable source. Verify installation/launch only if the user completed the installer or an authorized device test succeeded. Record missing SDK components without claiming a successful build.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://developer.android.com/build/building-cmdline
