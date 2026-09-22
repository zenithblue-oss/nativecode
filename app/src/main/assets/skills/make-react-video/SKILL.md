---
name: make-react-video
description: An existing Remotion composition or an explicit request for React-based video authoring. Complete this task inside
  NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make React Video
  category: PROJECT
  group: Video and audio
  engine: Remotion for existing React video projects
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make React Video

**Use for:** An existing Remotion composition or an explicit request for React-based video authoring.

**Deliver:** React composition, render settings, preview frames and final video when supported.

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

### NativeCode Chromium, only for browser rendering/testing

Reuse `/home/flux/.local/bin/chromium` or `/home/flux/.local/bin/google-chrome`. The supplied working browser tree is `/opt/google/chrome/`; its wrapper already selects NativeCode graphics. Do not replace that wrapper or append `--disable-gpu`. Check the real executable's architecture, shared libraries and `--version` once.

If no usable browser exists, the supplied installation uses this ARM64 package source:

```text
https://dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb
```

Download to a disposable directory; inspect the package with `ar`, extract its `control.tar.*` and `data.tar.*` without executing maintainer scripts, and require `Architecture: arm64` plus an aarch64 ELF. Record the actual downloaded version and checksum: this is a moving URL, not a pinned archive. Install only missing display/font/NSS libraries from the **native** package repositories based on control metadata and `ldd`. Preserve any existing browser, then place the verified `opt/google/chrome` tree at `/opt/google/chrome` using the authorized root terminal. No Debian repository or dpkg setup is needed.

When creating a missing wrapper, use the supplied NativeCode route, not generic desktop flags:

```sh
#!/bin/sh
exec gpu-launch --auto /opt/google/chrome/google-chrome \
  --no-sandbox --enable-gpu --disable-software-rasterizer \
  --ignore-gpu-blocklist --use-gl=angle --use-angle=vulkan "$@"
```

Save it as `/home/flux/.local/bin/chromium` only when that path is absent, make it executable and expose it in Bash/Zsh PATH. Reuse an existing known-good wrapper unchanged. Select Wayland/Ozone only with the live NativeCode Phosh socket; do not force a stale display. The supplied PRoot route needs `--no-sandbox`; confine automation to approved/local content with a fresh profile, not credentials or the user's normal browser profile. Do not weaken other security settings.

Use `gpu-launch --diagnose`, then a real WebGL draw/readback or the application's own renderer report. Accept only the actual device-backed renderer; reject SwiftShader/llvmpipe as a GPU result. Headless HTML success alone is not GPU evidence. Do not create Phosh launchers as a side effect of a content task.

### FFmpeg / ffprobe, only if missing

```sh
command -v ffmpeg; command -v ffprobe
ffmpeg -version | head -3
ffprobe -version | head -2
```

Reuse the supplied working ARM64 FFmpeg and matching ffprobe (including `/opt/ffmpeg/current/bin` when present). If missing, inspect `apk search -x ffmpeg` / `apk policy ffmpeg`, then install the compatible native package from the authorized root terminal. If unavailable, the supplied verified recipe uses [BtbN's FFmpeg ARM64 builds](https://github.com/BtbN/FFmpeg-Builds/releases): select `linuxarm64`, verify that release's published checksum, extract into a versioned `/opt/ffmpeg` directory and expose its two binaries in `/home/flux/.local/bin`. Do not reuse an old checksum for a changed `latest` asset or silently source-build/emulate x86.

Test the exact filter/codec required. The presence of `h264_nvenc`, VA-API or another encoder in an FFmpeg listing is not proof that Android exposes that hardware. NativeCode MediaCodec is the separate bridge described below, not an FFmpeg `--gpu` switch.

### Fonts, only for the requested languages

Check `fc-match sans` and `fc-match 'Noto Sans'`; for a specific language use `fc-list :lang=hi family` (replace `hi` as appropriate). Install `fontconfig` and only the required font families from the active native repositories (`apk search '*noto*'`, then the exact available package). Alternatively use licensed font files supplied by the user in the project. Refresh with `fc-cache -f` and record the resolved family. Do not silently substitute fonts that change layout, bundle unrelated fonts, or download every language family.

### Remotion packages

Keep an existing Remotion project's lockfile and aligned package versions. For a specifically requested new Remotion project, use the upstream starter (`npm init video`) in a new directory, or install matching `remotion` and `@remotion/cli` packages into the chosen project. Confirm the package license fits the intended use; do not silently switch engines for an existing composition.

Reuse the NativeCode Chromium wrapper. Check the installed CLI's documented browser-executable, browser-graphics and image-sequence options with `npx remotion render --help`; browser/renderer native helpers must be ARM64/glibc compatible. Do not download x86 browser/compositor helpers or assume generic Linux support proves this guest works. Test one frame before a full render; a precise ABI blocker is preferable to an invented compatibility patch.

## Do the task

1. Read the existing composition, assets, props schema, frame rate, duration and dimensions. Keep frame-driven React animation deterministic and assets local or explicitly approved.
2. Implement the requested scene/timing changes using the installed Remotion APIs. Do not re-scaffold an existing project or install a separate agent-skill collection.
3. Start the existing Studio preview on localhost, wait for readiness and use NativeCode's `open-link`. Render a test still with the actual composition ID and check layout/media resolution.
4. Render with the verified browser wrapper and hardware graphics route. For MediaCodec final encoding, use the installed version's supported image-sequence output and the media section below; do not assume a guessed `--mediacodec` flag exists.
5. Export/mix audio through the supported composition route and remux without re-encoding hardware video. Keep native-renderer compatibility, browser graphics and final codec claims separate.

## NativeCode graphics and hardware use

Run `gpu-launch --diagnose` once for this rendering job, reuse the existing wrappers and select `--auto`. Use explicit Turnip/PanVK/Venus only when the live diagnostic and installed launcher support that route. Do not hard-code an Adreno renderer on a Mali device, alter global ICDs, or disable GPU to hide a failure.

Validate the actual application workload and record its renderer/device. SwiftShader, llvmpipe, a software-only route, a version string or a bare PASS in the launcher are not enough. If required hardware rendering fails, save the log, try one evidence-based correction in the task's configuration, then report the specific blocker; CPU substitution needs an explicit user choice. CPU-only parsing/build steps remain CPU steps.

## NativeCode hardware video path

Treat graphics, frame composition, video encoding and decoding as separate stages. Before a heavy operation:

```sh
nativecode-agent telemetry
nativecode-media capabilities
```

For hardware encoding require the actual selected codec to report `is_hardware:true` and `software_only:false`. NativeCode's `encode_stream` accepts raw NV12 frames; it does not automatically integrate into MoviePy/Remotion/HyperFrames. Use an existing compatible frame export and this bridge; do not modify NativeCode or invent an FFmpeg encoder.

For a normalized, zero-padded PNG sequence, use the actual dimensions/rate (the numbers here are an example), and preserve pipe failures:

```bash
set -o pipefail
mkdir -p output
ffmpeg -hide_banner -loglevel error \
  -framerate 30 -i 'frames/%06d.png' -an -pix_fmt nv12 -f rawvideo - | \
  nativecode-media encode_stream \
    --width 1080 --height 1920 --fps 30 --bitrate 8000000 \
    --mime video/avc --output "$PWD/output/video-only.mp4"
```

Normalize frame names in the task directory if the renderer uses another naming scheme; verify frame count/order and that every frame matches the dimensions. Do not store an enormous raw-video intermediate. For an existing video, replace the frame input with `-i input.mp4` and use the probed frame rate; identify FFmpeg decoding as CPU unless separately proven otherwise.

If audio is required, mix it separately and remux without re-encoding the hardware video:

```sh
ffmpeg -n -i output/video-only.mp4 -i narration.wav \
  -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -shortest output/final.mp4
ffprobe -v error -show_streams -show_format -of json output/final.mp4
```

Set the audio duration intentionally before using `-shortest`. Probe and decode the final file, inspect sampled frames and A/V sync, and retain the encoder response. For a separate source decode test use `nativecode-media benchmark_file --path <actual-video> --mode surface`. That benchmark does not make browser playback or FFmpeg decoding hardware-backed. If the requested codec/size is unsupported, report it; do not silently output a CPU encode as a hardware result.

## Check the actual result

Require actual frame/video output, correct timing/dimensions, deterministic seeking, audio sync and editable React source. If the native helper or image-sequence route is unavailable, report that exact limitation without claiming a rendered deliverable or rewriting the app/backend.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/remotion-dev/remotion
- https://www.remotion.dev/docs/miscellaneous/linux-dependencies
