---
name: install-hyperframes
description: Install and operate HyperFrames on NativeCode ARM64 for deterministic HTML video rendering, strict Turnip/Zink browser-GPU validation, and NativeCode MediaCodec hardware encoding when supported.
metadata:
  short-description: Install HyperFrames with GPU and hardware video paths
---

# Install HyperFrames

HyperFrames renders HTML compositions frame by frame in Chromium and assembles them with FFmpeg. Treat browser rendering and video encoding as separate acceleration stages:

~~~text
HTML/GSAP/CSS/Three.js → Chromium + WebGL GPU capture → frames → encoder → MP4/WebM
~~~

On NativeCode, browser graphics use Turnip Vulkan through Zink. The final encoder must be verified independently; an application starting is not proof of acceleration.

## Prerequisites and detection

This is a rootless ARM64 (aarch64) Alpaquita/Alpine-style PRoot guest with glibc and no systemd. Use NativeCode bridges instead of raw Android devices:

- gpu-launch --auto for browser graphics
- nativecode-media for Android MediaCodec hardware decode/encode
- nativecode-agent for telemetry, previews, and status
- no direct /dev/dri, /dev/video*, KGSL, binder, or USB access

HyperFrames needs Node.js 22+, Chromium, FFmpeg/ffprobe, fontconfig, and fonts required by the composition. Docker is not required for local rendering. It is a CLI tool, so do not create a Phosh desktop entry; use nativecode-agent open-link for previews.

~~~sh
uname -m
node --version 2>/dev/null || true
npm --version 2>/dev/null || true
command -v hyperframes || true
hyperframes --version 2>/dev/null || true
command -v chromium || command -v google-chrome || true
command -v ffmpeg || true
command -v ffprobe || true
fc-match sans 2>/dev/null || true
gpu-launch --diagnose
nativecode-media capabilities
~~~

Reject x86_64 binaries. Verify an existing tool's real path and ELF architecture before reinstalling it.

## Install the latest CLI

The tested npm release is HyperFrames 0.8.60 and it requires Node >=22:

~~~sh
npm view hyperframes version engines --json
npm install --global hyperframes@latest
hyperframes --version
~~~

For a reproducible project, use a pinned CLI script or npx hyperframes@approved-version. HyperFrames' upstream authoring skills can be refreshed from GitHub when requested:

~~~sh
npx skills add heygen-com/hyperframes
npx hyperframes skills update
~~~

Do not copy an old skill snapshot over the current installation. The installed CLI is authoritative; use hyperframes command --help.

## Install FFmpeg without building

Prefer the configured Alpaquita package:

~~~sh
apk search | grep -E '^ffmpeg($|-)'
apk policy ffmpeg
~~~

If no compatible ARM64 package exists, use a verified prebuilt binary rather than compiling. The tested fallback is the BtbN Linux ARM64 GPL build for the FFmpeg 9.0 line:

~~~text
URL: https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n9.0-latest-linuxarm64-gpl-9.0.tar.xz
SHA-256: 796f86435604248a58253adc3f92ae3865a15a7d99777b6e901e92f55b3a3d30
~~~

Download and verify before activation. Keep a versioned tree under /opt/ffmpeg/<version>-arm64 and expose only verified commands in /home/flux/.local/bin:

~~~sh
mkdir -p /tmp/hyperframes-ffmpeg
curl -fL --retry 3 -o /tmp/hyperframes-ffmpeg/ffmpeg-arm64.tar.xz \
  'https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n9.0-latest-linuxarm64-gpl-9.0.tar.xz'
printf '%s  %s\n' \
  796f86435604248a58253adc3f92ae3865a15a7d99777b6e901e92f55b3a3d30 \
  /tmp/hyperframes-ffmpeg/ffmpeg-arm64.tar.xz | sha256sum -c -
mkdir -p /tmp/hyperframes-ffmpeg/extracted
tar -xJf /tmp/hyperframes-ffmpeg/ffmpeg-arm64.tar.xz -C /tmp/hyperframes-ffmpeg/extracted
~~~

Keep ffmpeg and ffprobe from the same build. Check both:

~~~sh
file /opt/ffmpeg/current/bin/ffmpeg /opt/ffmpeg/current/bin/ffprobe
ldd /opt/ffmpeg/current/bin/ffmpeg 2>/dev/null || true
ffmpeg -version | head -3
ffprobe -version | head -2
~~~

An encoder named *_vulkan, *_vaapi, *_nvenc, or *_amf is not automatically usable with Android Adreno; test it before selecting it.

## Bash and Zsh PATH

Put this idempotent block in /home/flux/.bashrc and /home/flux/.zshrc as applicable. Preserve unrelated PATH entries:

~~~sh
# NativeCode user-installed executables
case ":$PATH:" in
  *":/home/flux/.local/bin:"*) ;;
  *) export PATH="/home/flux/.local/bin:$PATH" ;;
esac
~~~

Verify fresh shells:

~~~sh
bash -lc 'command -v hyperframes; command -v ffmpeg; command -v ffprobe; hyperframes --version'
zsh -lc 'command -v hyperframes; command -v ffmpeg; command -v ffprobe; hyperframes --version'
file "$(command -v ffmpeg)" "$(command -v ffprobe)"
~~~

## GPU-backed Chromium capture

Use the NativeCode Chromium wrapper. It must call gpu-launch --auto, select ANGLE Vulkan/Zink, enable GPU, and disable software rasterization:

~~~sh
exec gpu-launch --auto /opt/google/chrome/google-chrome \
  --no-sandbox --enable-gpu --disable-software-rasterizer \
  --ignore-gpu-blocklist --use-gl=angle --use-angle=vulkan "$@"
~~~

If discovery selects another browser, set:

~~~sh
export HYPERFRAMES_BROWSER_PATH=/home/flux/.local/bin/google-chrome
~~~

Run a GPU-only check. Accept only browserGpuMode probe → hardware and a renderer containing ANGLE, Zink, Vulkan, and Turnip. Reject SwiftShader, llvmpipe, Software, or a missing renderer:

~~~sh
gpu-launch --diagnose
HYPERFRAMES_BROWSER_PATH=/home/flux/.local/bin/google-chrome \
  hyperframes render ./project --browser-gpu --no-gpu --output /tmp/hyperframes-gpu-check.mp4
~~~

Here --browser-gpu enables Chromium/WebGL GPU capture. --no-gpu intentionally disables HyperFrames' separate FFmpeg encoder probe while retaining browser GPU. HyperFrames --gpu targets NVENC, VideoToolbox, AMF, VA-API, or QSV, not NativeCode MediaCodec; do not use it as proof of Adreno video encoding.

A regular Chrome build may lack HeadlessExperimental.beginFrame and fall back to screenshot capture. That is acceptable when the GPU probe is hardware-backed. An ARM64-compatible chrome-headless-shell may improve capture performance, but verify its architecture and Turnip/Zink renderer before using it.

## NativeCode hardware encode/decode

Inspect codecs first:

~~~sh
nativecode-media capabilities
~~~

Accept an encoder only when it reports is_hardware:true and software_only:false. The tested Snapdragon/Adreno device exposes hardware AVC and HEVC through c2.qti.avc.encoder and c2.qti.hevc.encoder; AV1 and VP9 encoders are software-only. Capabilities vary by device, Android build, resolution, and profile.

HyperFrames does not directly hand frames to MediaCodec. For a hardware-encoded deliverable, render a PNG sequence with browser GPU enabled, convert frames to NV12, and stream them to the bridge:

~~~sh
hyperframes render ./project \
  --browser-gpu --no-gpu --format png-sequence --output /tmp/hyperframes-frames

ffmpeg -hide_banner -loglevel error \
  -framerate 30 -pattern_type glob -i '/tmp/hyperframes-frames/*.png' \
  -pix_fmt nv12 -f rawvideo - |
  nativecode-media encode_stream \
  --width 1920 --height 1080 --fps 30 --bitrate 8000000 \
  --mime video/avc --output /tmp/hyperframes-h264.mp4
~~~

Use --mime video/hevc only after capabilities proves a hardware HEVC encoder. Retain the MediaBridge JSON and probe the result:

~~~sh
ffprobe -v error -select_streams v:0 \
  -show_entries stream=codec_name,width,height,avg_frame_rate,nb_frames \
  -of default=nw=1 /tmp/hyperframes-h264.mp4
~~~

The bridge output is normally video-only. For audio, mix separately and remux with -c:v copy; do not re-encode the hardware video in software. If the required codec, profile, dimensions, or color format is unsupported, report it instead of silently using libx264/libx265.

Test source-video hardware decode separately:

~~~sh
nativecode-media benchmark_file --path /path/to/source.mp4 --mode surface
~~~

Require hardware decoding and nonzero decoded frames. Browser playback, MediaCodec decoding, browser WebGL, and final encoding are separate claims.

## Fonts, preview, and smoke test

Check language coverage before rendering; fallback can change line wrapping:

~~~sh
fc-match sans
fc-match 'Noto Sans'
fc-list :lang=ja family | head
fc-list :lang=hi family | head
~~~

Install only required Noto or other language families through Install Fonts, preserve licenses, and prefer local fonts for reproducibility. HyperFrames may cache Google Fonts; record the cache and families used.

~~~sh
hyperframes doctor --json
hyperframes check . --json
hyperframes preview
~~~

If preview does not open automatically:

~~~sh
nativecode-agent open-link \
  --url 'http://localhost:3000' \
  --title 'HyperFrames preview' \
  --message 'Open the local HyperFrames preview'
~~~

Run a short render with --browser-gpu --no-gpu, inspect the hardware WebGL renderer, probe the output with ffprobe, and separately run one AVC or HEVC MediaBridge encode. Use --workers 1 or --low-memory-mode when telemetry reports low memory or elevated temperature; each worker is a separate Chrome process.

~~~sh
nativecode-agent telemetry
nativecode-agent status --key hyperframes --text 'GPU capture and hardware-encode validation running' --progress 10
~~~

Do not leave orphaned Chrome, FFmpeg, or MediaBridge processes. Do not run an unbounded retry loop.

## Completion record

Record:

- HyperFrames and Node versions
- Chromium path and GPU renderer
- gpu-launch --diagnose result
- FFmpeg/ffprobe version, architecture, and checksum
- font families and language coverage
- render format, workers, and output probe
- NativeCode MIME, codec name, is_hardware, software_only, dimensions, and measured rate
- whether audio was remuxed without re-encoding video
- unsupported codecs or headless-capture limitations

References:

- https://github.com/heygen-com/hyperframes
- https://github.com/heygen-com/hyperframes/blob/main/docs/guides/rendering.mdx
- https://github.com/heygen-com/hyperframes/blob/main/docs/packages/cli.mdx
- https://github.com/heygen-com/hyperframes/blob/main/packages/engine/README.md
- https://ffmpeg.org/download.html
- https://github.com/BtbN/FFmpeg-Builds/releases
- https://github.com/notofonts

