---
name: install-moneyprinter
description: Install and run MoneyPrinterTurbo on NativeCode ARM64 with its locked Python environment, FFmpeg path, WebUI launcher, provider boundaries, and separately verified GPU and MediaCodec workflows.
metadata:
  short-description: Install MoneyPrinterTurbo for ARM64
---

# Install MoneyPrinterTurbo

Install the latest upstream MoneyPrinterTurbo short-video application and its pinned Python dependencies. Keep three acceleration claims separate:

~~~text
Streamlit WebUI → Chromium/Turnip GPU for display
Python/MoviePy/faster-whisper → CPU unless a supported backend is proven
Final video encode → FFmpeg software or NativeCode MediaCodec hardware post-process
~~~

MoneyPrinterTurbo's built-in FFmpeg codec list targets libx264, NVENC, AMF, QSV, Media Foundation, and VideoToolbox. Android Adreno does not provide NVENC/AMF/QSV in this guest. Do not configure an unavailable codec and call the fallback accelerated.

## NativeCode constraints and detection

The target is rootless ARM64 (aarch64) Alpaquita/Alpine-style PRoot with glibc and no systemd. Use apk only for compatible guest packages and NativeCode bridges for Android hardware. Never access raw /dev/dri, /dev/video*, KGSL, binder, or USB nodes.

Detect existing components:

~~~sh
uname -m
python3 --version
uv --version
command -v moneyprinterturbo || true
command -v ffmpeg
ffmpeg -version | head -1
fc-match sans
gpu-launch --diagnose
nativecode-media capabilities
~~~

Reject x86_64 binaries and Python environments built for another architecture. Do not install Docker just for this app; local deployment is the supported route in this guest.

## Select and obtain the upstream release

Use the official repository:

~~~text
https://github.com/harry0703/MoneyPrinterTurbo
~~~

At the verified setup date, the latest release is v1.3.7 and pyproject.toml declares version 1.3.7 with Python >=3.11. Prefer the release tag over an unreviewed moving branch. Verify the tag, archive checksum, and source manifest. If a shallow Git clone reports missing objects, preserve the failed checkout and use the GitHub tag source archive; do not activate a partial tree.

A verified source archive at setup time was:

~~~text
https://github.com/harry0703/MoneyPrinterTurbo/archive/refs/tags/v1.3.7.tar.gz
SHA-256: 8253255062d5100f030f344575e543c8739091ca5f449c4727c2f715f0571081
~~~

Keep the application versioned and provide a stable path:

~~~sh
mkdir -p /opt/moneyprinterturbo /tmp/moneyprinterturbo
curl -fL --retry 3 -o /tmp/moneyprinterturbo/v1.3.7.tar.gz \
  'https://github.com/harry0703/MoneyPrinterTurbo/archive/refs/tags/v1.3.7.tar.gz'
printf '%s  %s\n' \
  8253255062d5100f030f344575e543c8739091ca5f449c4727c2f715f0571081 \
  /tmp/moneyprinterturbo/v1.3.7.tar.gz | sha256sum -c -
mkdir -p /tmp/moneyprinterturbo/extracted
tar -xzf /tmp/moneyprinterturbo/v1.3.7.tar.gz -C /tmp/moneyprinterturbo/extracted
mv /tmp/moneyprinterturbo/extracted/MoneyPrinterTurbo-1.3.7 /opt/moneyprinterturbo/v1.3.7
ln -s /opt/moneyprinterturbo/v1.3.7 /opt/moneyprinterturbo/current
~~~

If current already exists, inspect its version before changing it and preserve it under a versioned backup.

## Python environment and locked dependencies

MoneyPrinterTurbo's primary dependency manifest is pyproject.toml and uv.lock. Use Python 3.11 on ARM64 even when a newer system Python is present; this is the upstream recommended compatibility target:

~~~sh
uv python install 3.11
cd /opt/moneyprinterturbo/current
~~~

Install the production lockfile and the declared TwelveLabs extra. Do not install the development group merely to run the app:

~~~sh
UV_CACHE_DIR=/tmp/mpt-uv-cache-fresh \
UV_LINK_MODE=copy \
uv sync --frozen --no-dev --extra twelvelabs --link-mode=copy
~~~

The fresh cache and copy mode matter in PRoot: cross-filesystem hardlinks can leave package files unreadable. If a previous sync is broken, move its explicit .venv directory to a versioned recovery name and recreate it; do not delete it blindly.

The current core pins include MoviePy 2.2.1, Streamlit 1.59.1, faster-whisper 1.1.0, Edge TTS 7.2.7, FastAPI 0.136.3, LiteLLM 1.86.2, and the other versions in the upstream lockfile. Verify imports and the project configuration:

~~~sh
/opt/moneyprinterturbo/current/.venv/bin/python - <<'PY'
import sys
from app.config import config
from app.utils import utils
print("python", sys.version.split()[0])
print("project_version", config.project_version)
print("ffmpeg", utils.get_ffmpeg_binary())
print("ffmpeg_ready", utils.check_ffmpeg_ready())
PY
~~~

Do not download Whisper, TTS, music, or image models during installation unless that feature is selected. Package extras are not model weights. Keep API keys empty until the user supplies and authorizes a provider.

## FFmpeg and fonts

MoneyPrinterTurbo resolves FFmpeg in this order: app configuration, PATH, imageio-ffmpeg, then the literal ffmpeg fallback. Make the verified ARM64 FFmpeg the first PATH result and optionally set app.ffmpeg_path to its absolute path in config.toml:

~~~sh
command -v ffmpeg
ffmpeg -version | head -3
command -v ffprobe
fc-match sans
fc-match 'Noto Sans'
~~~

Install required language families through Install Fonts and retain licensing information. Font fallback changes subtitle wrapping and can change the output.

Do not confuse an FFmpeg encoder list with working hardware. An FFmpeg build can contain h264_nvenc or h264_amf while the Android guest has no corresponding device. Test the actual output and record the selected encoder.

## Bash, Zsh, and launcher PATH

Both /home/flux/.bashrc and /home/flux/.zshrc should contain one idempotent block:

~~~sh
# NativeCode user-installed executables
case ":$PATH:" in
  *":/home/flux/.local/bin:"*) ;;
  *) export PATH="/home/flux/.local/bin:$PATH" ;;
esac
~~~

Provide /home/flux/.local/bin/moneyprinterturbo as the application launcher. It should run /opt/moneyprinterturbo/current/webui.sh with the verified project venv, bind to 127.0.0.1 by default, wait for /_stcore/health, and call nativecode-agent open-link. Launch it in an isolated process group so stopping the menu entry does not leave Streamlit orphaned.

Verify fresh shells:

~~~sh
bash -lc 'command -v moneyprinterturbo; command -v ffmpeg'
zsh -lc 'command -v moneyprinterturbo; command -v ffmpeg'
sh -n /home/flux/.local/bin/moneyprinterturbo
~~~

## Browser preview

MoneyPrinterTurbo's interface is a local Streamlit WebUI, not a native desktop window. Do not add it to the Phosh menu. Start the server, then open the URL in the Android browser:

~~~sh
MPT_WEBUI_HOST=127.0.0.1 MPT_WEBUI_PORT=8501 moneyprinterturbo
~~~

The browser opened by the NativeCode link uses the existing Chromium Turnip wrapper. Verify the browser renderer separately with the Chromium skill; the Streamlit process itself does not render video frames through the GPU.

If NativeCode does not open the browser automatically, use:

~~~sh
nativecode-agent open-link \
  --url 'http://127.0.0.1:8501' \
  --title 'MoneyPrinterTurbo' \
  --message 'MoneyPrinterTurbo WebUI is ready'
~~~

## GPU, transcription, and hardware video

Run the diagnostic before a heavy job:

~~~sh
gpu-launch --diagnose
nativecode-media capabilities
nativecode-agent telemetry
~~~

The WebUI browser can use Chromium's Turnip/Zink GPU path when opened through the verified Chromium wrapper. That accelerates browser display and any explicitly GPU-backed browser preview; it does not make MoviePy, Python composition, or FFmpeg libx264 hardware-encoded.

faster-whisper uses CTranslate2. On this ARM64 NativeCode guest, do not force CUDA or another unavailable accelerator. Start with CPU settings that match the installed wheel and explicitly selected model. A model download is a separate, potentially large operation; record its source and checksum when reproducibility matters.

MoneyPrinterTurbo's built-in video_codec setting accepts its upstream whitelist. Do not set h264_nvenc, h264_amf, h264_qsv, or h264_videotoolbox unless the exact encoder and hardware device are proven. The application may fall back to libx264 after an encoder failure; log and report that fallback.

NativeCode hardware encoding is a separate post-processing route because MoneyPrinterTurbo does not directly call nativecode-media. For a final MP4, first obtain the generated video and inspect width, height, frame rate, and audio. Then stream decoded NV12 frames to MediaCodec:

~~~sh
nativecode-media capabilities
ffprobe -v error -select_streams v:0 \
  -show_entries stream=width,height,avg_frame_rate \
  -of default=nw=1 /path/to/moneyprinter-output.mp4

ffmpeg -hide_banner -loglevel error -i /path/to/moneyprinter-output.mp4 \
  -pix_fmt nv12 -f rawvideo - |
  nativecode-media encode_stream \
  --width 1080 --height 1920 --fps 30 --bitrate 8000000 \
  --mime video/avc --output /tmp/moneyprinter-video-hw.mp4
~~~

Replace dimensions and FPS with the probed values. Use HEVC only when capabilities reports a hardware HEVC encoder. Retain the bridge response and require is_hardware:true and software_only:false. Remux the original audio with -c:v copy if needed; never re-encode the hardware video in a software pass. This route performs guest decoding with FFmpeg and Android hardware encoding; it is not proof of hardware decoding.

For source-video decoding, separately test:

~~~sh
nativecode-media benchmark_file --path /path/to/source.mp4 --mode surface
~~~

Do not accept software output as a hardware result. On the tested device, AVC and HEVC encoders are hardware-backed, while AV1 and VP9 encoders reported by the bridge are software-only.

## Smoke tests and completion

Use bounded tests without API keys or model downloads:

~~~sh
/opt/moneyprinterturbo/current/.venv/bin/python -c \
  'from app.config import config; print(config.project_version)'
MPT_WEBUI_HOST=127.0.0.1 MPT_WEBUI_PORT=18501 \
  /opt/moneyprinterturbo/current/webui.sh
~~~

From another shell, require:

~~~sh
curl -fsS http://127.0.0.1:18501/_stcore/health
~~~

Stop the server after the smoke test. Before a real generation, check telemetry and disk space, then run one short generation with a selected provider and asset source. Keep worker/thread counts conservative on this device; MoneyPrinterTurbo's FFmpeg thread setting is not a hardware-encode switch.

Record:

- upstream tag and source checksum
- Python and uv versions
- lockfile sync command and enabled extras
- FFmpeg/ffprobe path, version, and architecture
- font families and language coverage
- WebUI URL and health result
- Chromium GPU renderer, if tested
- selected LLM/TTS/material provider and whether credentials were supplied
- Whisper model/device, if selected
- final video encoder, MediaBridge JSON, and whether audio was remuxed
- any explicit CPU fallback or unsupported hardware route

References:

- https://github.com/harry0703/MoneyPrinterTurbo
- https://github.com/harry0703/MoneyPrinterTurbo/blob/main/README-en.md
- https://github.com/harry0703/MoneyPrinterTurbo/blob/main/pyproject.toml
- https://github.com/harry0703/MoneyPrinterTurbo/blob/main/requirements.txt
- https://github.com/harry0703/MoneyPrinterTurbo/blob/main/app/services/video.py
