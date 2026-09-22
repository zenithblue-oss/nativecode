---
name: turn-website-into-app
description: A local web project whose built assets should run in an Android wrapper. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Turn Website Into App
  category: PROJECT
  group: Development
  engine: Capacitor Android + existing web build
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Turn Website Into App

**Use for:** A local web project whose built assets should run in an Android wrapper.

**Deliver:** Web source, Capacitor Android project, and debug APK when buildable.

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

### Node.js / npm, only if missing or incompatible

```sh
command -v node; command -v npm
node -p 'process.version + " " + process.arch + " " + process.platform'
npm --version
```

Prefer the supplied working toolchain at `/home/flux/.local/share/nativecode/tools/node/current/bin`. Inspect the project's `engines` and package-manager lockfile once. Do not upgrade a working Node/npm installation simply to use this skill.

If no compatible Node works, use the configured Alpaquita `nodejs`/`npm` packages when their candidate versions satisfy the project (`apk search -x nodejs`, `apk search -x npm`). Otherwise select a compatible `linux-arm64` release from [Node's official index](https://nodejs.org/dist/index.json), not an x64/musl archive. Set the exact approved version, then download and verify it:

```sh
: "${NODE_VERSION:?Set the selected official version, including v}"
node_archive="node-${NODE_VERSION}-linux-arm64.tar.xz"
node_stage=$(mktemp -d /tmp/nativecode-node.XXXXXX)
node_base=/home/flux/.local/share/nativecode/tools/node
curl -fL "https://nodejs.org/dist/${NODE_VERSION}/${node_archive}" -o "$node_stage/$node_archive"
curl -fsSL "https://nodejs.org/dist/${NODE_VERSION}/SHASUMS256.txt" -o "$node_stage/SHASUMS256.txt"
(cd "$node_stage"; grep "  ${node_archive}$" SHASUMS256.txt > selected.sha256; test -s selected.sha256; sha256sum -c selected.sha256) || exit 1
mkdir -p "$node_base"
tar -xJf "$node_stage/$node_archive" -C "$node_base"
"$node_base/node-${NODE_VERSION}-linux-arm64/bin/node" --version
```

Activate the verified version with a recoverable `current` symlink; never replace an unknown real directory. Add the chosen `bin` once to both Bash/Zsh PATH only when toolchain setup changed it, preserving other entries. Use project-local packages. Respect an existing npm/pnpm/yarn lockfile; `npm ci` is the npm locked-install route, not a command to overwrite another manager's lockfile.

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

### Capacitor packages

Reuse an existing Capacitor project. For a new wrapper, install into the web project:

```sh
npm install @capacitor/core @capacitor/android
npm install --save-dev @capacitor/cli
```

Keep all Capacitor packages on a compatible release line. Set the real built asset directory in `capacitor.config.*`; do not point it at the source directory or a development-only URL.

## Do the task

1. Build the existing web project and confirm its routes/assets work without a development server. Server-side-only features need an explicit reachable backend; do not pretend they become local merely by wrapping them.
2. Initialize Capacitor only if absent, using the user's app name/package ID and the actual `webDir`. Add Android only when its directory is absent:

```sh
npx cap add android
npm run build
npx cap sync android
```

3. Handle back navigation, safe areas, permissions, local files and responsive WebView layout. Keep existing Android/Capacitor changes and signing settings.
4. Build through the Android project's wrapper:

```sh
cd android
./gradlew assembleDebug --max-workers=2
```

5. Offer the resulting APK through `nativecode-agent install-apk`. Test on the host/authorized device; Android WebView owns graphics acceleration. Do not call a guest Chromium render a device WebView test.

## Check the actual result

Require an actual debug APK, correct app identity, assets loading in WebView and requested native/web behavior. Offline expectations must match the implemented backend design. Deliver both web source and the Android wrapper.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/ionic-team/capacitor
- https://developer.android.com/build/building-cmdline
