---
name: make-video-from-slides
description: A presentation/PDF or ordered images, per-slide timing and optional narration/music. Complete this task inside
  NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make Video From Slides
  category: PROJECT
  group: Video and audio
  engine: Rendered slides/images + FFmpeg
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make Video From Slides

**Use for:** A presentation/PDF or ordered images, per-slide timing and optional narration/music.

**Deliver:** Video slideshow, rendered slide assets and timing manifest.

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

### Office renderer, only when the input/output needs it

Reuse `libreoffice` or `soffice` if it opens the selected format. Otherwise inspect `apk search 'libreoffice*'` in the active Alpaquita repositories and install the matching package/components and required fonts through the authorized root terminal. A filename in an archive is not proof of an ARM64-compatible office build. Do not install a generic x86 desktop tarball or modify NativeCode's display stack to make it run.

Use a unique profile for each conversion, preserve the original and write into a task output directory:

```sh
mkdir -p output
lo_profile=$(mktemp -d /tmp/nativecode-office.XXXXXX)
# Substitute the actual source file and requested output format.
libreoffice "-env:UserInstallation=file://$lo_profile" \
  --headless --convert-to pdf --outdir output input.docx
```

For page-image inspection, check `pdfinfo` and `pdftoppm`. If missing, install the native repository's `poppler-utils` package only for that preview step; run `pdftoppm -png -r 120 output/<actual-name>.pdf output/page` and inspect every produced page.

Use a bounded timeout appropriate to the document, inspect exit status and verify the new file. `--headless` conversion is not a GPU workload. For interactive inspection, use the existing NativeCode desktop and `gpu-launch --auto libreoffice <actual-file>` only when the app supports the selected route; do not start a new desktop session. If no compatible renderer is available, preserve the editable source and report that visual conversion is blocked instead of faking a rendered result.

### Slide/page image preparation

If input is already a numbered image sequence, skip office/PDF tools. For a PPTX, use the inline office renderer to export a PDF; for a PDF, reuse it directly. Install the active repository's `poppler-utils` package only when `pdftoppm`/`pdfinfo` is absent, then render pages at the required size into a task directory. Preserve original slide order and editable deck source.

## Do the task

1. Read the actual slide deck/images, desired aspect ratio, duration per slide, transitions and supplied narration/music. Do not claim slide animations survived conversion to static images.
2. Render only when needed and inspect all slide/page images for missing fonts, charts and crops. Normalize dimensions and frame order without overwriting source slides.
3. Build a timing manifest. Use narration duration or the user's timings; pad/hold slides intentionally and do not stretch audio silently.
4. Compose the frame sequence with the selected transitions using FFmpeg, then use the NativeCode hardware encoder for a supported final video. Mix and remux audio separately.
5. Inspect the finished first/last slide, transitions, readable text, total duration and synchronization. Return the source deck, timing manifest, rendered assets and actual video.

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

Require correct page order/count, intended timing, readable slide content, expected streams and an actual hardware encode response when claimed. A rendered slideshow is not an editable PPTX; keep both artifacts distinct.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/FFmpeg/FFmpeg
- https://github.com/LibreOffice/core
