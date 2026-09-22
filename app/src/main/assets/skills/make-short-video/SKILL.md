---
name: make-short-video
description: A topic or supplied script, desired duration, language and owned/licensed media; narration optional. Complete
  this task inside NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make Short Video
  category: PROJECT
  group: Video and audio
  engine: MoneyPrinterTurbo; local-assets/no-extra-LLM-key route first
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make Short Video

**Use for:** A topic or supplied script, desired duration, language and owned/licensed media; narration optional.

**Deliver:** Portrait MP4, script, captions, asset manifest and editable task configuration.

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

### Python environment, only if needed

Reuse a compatible project environment and its lockfile. Check `python3 --version`, `python3 -m venv --help` and `uv --version` when uv exists. If Python/pip/venv is missing, inspect the configured Alpaquita packages, then install only the missing `python3`, `py3-pip` and `py3-virtualenv` components in the authorized root terminal. Do not replace the system interpreter or use global `pip` installs.

```sh
# Run in the task project as flux; preserve an existing .venv.
if [ ! -x .venv/bin/python ]; then python3 -m venv .venv; fi
.venv/bin/python -m pip --version
```

Use `.venv/bin/python -m pip` for the package commands below. For an existing uv project, use its lockfile and `UV_LINK_MODE=copy uv sync --frozen` rather than changing package versions with pip. NativeCode PRoot may need copy mode instead of cross-filesystem hardlinks.

Select an interpreter supported by the chosen packages and ARM64 wheels; do not force the newest system Python on a package that does not support it. The supplied environment includes uv at `/home/flux/.local/bin/uv`. When needed and authorized, use `uv python install 3.11` and `uv venv --python 3.11 <new-task-venv>`; do not overwrite an unrelated environment. If uv itself is missing, its [official installation guide](https://docs.astral.sh/uv/getting-started/installation/) supplies the matching `aarch64-unknown-linux-gnu` binary; verify the release before activation. No required step calls another skill.

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

### MoneyPrinterTurbo: reuse the supplied working app

Check `/home/flux/.local/bin/moneyprinterturbo`, `/opt/moneyprinterturbo/current`, its `.venv/bin/python`, `pyproject.toml`, `uv.lock` and current config. Do not assume there is a `moneyprinterturbo generate` CLI; the supplied launcher starts the WebUI.

If the application is absent, the supplied source artifact is:

```text
https://github.com/harry0703/MoneyPrinterTurbo/archive/refs/tags/v1.3.7.tar.gz
SHA-256: 8253255062d5100f030f344575e543c8739091ca5f449c4727c2f715f0571081
```

Verify/download/extract this exact archive into a versioned `/opt/moneyprinterturbo/v1.3.7` directory through the authorized root terminal; preserve an existing version and current symlink. Use the installed uv and the matching upstream lockfile, not a collection of independently upgraded packages:

```sh
uv python install 3.11
cd /opt/moneyprinterturbo/current
UV_CACHE_DIR=/tmp/mpt-uv-cache-fresh UV_LINK_MODE=copy \
  uv sync --frozen --no-dev --extra twelvelabs --link-mode=copy
.venv/bin/python -c 'from app.config import config; print(config.project_version)'
```

That command is the supplied tested production dependency route, including its declared extra; it does not authorize model downloads or API calls. Keep the app's own environment separate from the user's project and preserve existing settings/secrets. Set `app.ffmpeg_path` only to the already verified ARM64 FFmpeg if discovery needs it.

Reuse `moneyprinterturbo`; if the wrapper is absent, run the existing checkout's `webui.sh` with its own environment. Bind to localhost and retain a task-owned process/PID. Check Streamlit's `/_stcore/health` before opening the page. No Phosh menu or NativeCode app changes are needed.

## Do the task

1. Read the topic/brief, target duration/aspect ratio and supplied media. Use the active coding model to write an editable script and shot list; do not require a second LLM API by default.
2. Prefer approved local footage/images. Select only a supported narration/TTS route: user-supplied audio, an already configured service/model, or an explicit approved provider. Inspect the installed task configuration/schema before assuming an input feature. Do not promise fully offline generation unless the selected pipeline actually supports it.
3. Reuse the ready WebUI or launch `MPT_WEBUI_HOST=127.0.0.1 MPT_WEBUI_PORT=8501 moneyprinterturbo`. Check health, then use NativeCode's `open-link` for the WebUI. For automation use the installed app's real API/schema, not invented command flags or a made-up endpoint.
4. Generate a small trial before the full short. Keep task config, script, captions, local media paths and output locations. Review framing, caption wrapping, narration timing and asset rights.
5. Inspect the actual composition/encoder logs. Browser GPU acceleration does not accelerate MoviePy or CTranslate2. Use the NativeCode media route when hardware final encoding is required and the pipeline can provide frames; do not rewrite MoneyPrinterTurbo or NativeCode as part of this task. If the upstream output was already software-encoded, report that stage and avoid an unnecessary second lossy encode merely to claim GPU use.

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

Require a real playable portrait video, intended script/footage/captions, appropriate narration or disclosed no-narration result, audio sync and asset/config provenance. Report local/online providers, actual inference device and final encoder separately. Any bridge post-processing is hardware final encoding, not proof that the entire creation pipeline was GPU-accelerated.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/harry0703/MoneyPrinterTurbo/blob/main/README-en.md
- https://github.com/FFmpeg/FFmpeg
