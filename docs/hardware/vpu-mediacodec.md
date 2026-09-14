# NativeCode VPU Hardware Acceleration Bridge

## Overview
NativeCode bridges hardware video decoding and encoding between the isolated PRoot Linux guest and the Android host process via an unprivileged `AF_UNIX` abstract socket (`@nativecode.media.v1`).

Because PRoot Linux userspace has no access to Android's `/dev/binder` or `/dev/video*` HAL nodes due to Android SELinux domain restrictions, direct hardware MediaCodec calls from glibc binaries fail. NativeCode solves this by running a zero-copy MediaCodec daemon on the Android host that communicates with guest tools (`nativecode-media`, PRoot FFmpeg).

---

## Hardware Codecs Supported

| Codec | MIME Type | Hardware Decoder | Hardware Encoder |
|---|---|---|---|
| **H.264 / AVC** | `video/avc` | `c2.mtk.avc.decoder` / `c2.qti.avc.decoder` | `c2.mtk.avc.encoder` / `c2.qti.avc.encoder` |
| **HEVC / H.265** | `video/hevc` | `c2.mtk.hevc.decoder` / `c2.qti.hevc.decoder` | `c2.mtk.hevc.encoder` / `c2.qti.hevc.encoder` |
| **AV1** | `video/av01` | `c2.mtk.av1.decoder` / `c2.qti.av1.decoder` | Software fallback (`c2.android.av1.encoder`) |
| **VP9** | `video/x-vnd.on2.vp9` | `c2.mtk.vp9.decoder` / `c2.qti.vp9.decoder` | Software fallback (`c2.android.vp9.encoder`) |

*DRM `.secure` codecs (e.g. `c2.mtk.avc.decoder.secure`) are filtered automatically to prevent non-secure surface failures.*

---

## CLI Tool: `nativecode-media`

Prepackaged in `/usr/local/bin/nativecode-media` inside the PRoot environment.

### 1. Check VPU Hardware Capabilities
```bash
nativecode-media capabilities
```
Returns JSON containing device SoC, Android API level, supported resolutions (720p, 1080p, 4K), and all hardware decoders and encoders.

### 2. Hardware VPU File Decode Benchmark
```bash
nativecode-media benchmark_file \
  --path /data/data/com.zenithblue.nativecode/files/home/test_1080p30.mp4 \
  --mode surface
```
- `--path`: Absolute host path to MP4/MKV video.
- `--mode`: `surface` (zero-copy `ImageReader` drain, ~204 FPS) or `buffer` (bytebuffer discard).
- `--mime`: Target MIME (default: `video/avc`).

### 3. Hardware VPU Encode Benchmark
```bash
nativecode-media benchmark_encode \
  --width 1280 \
  --height 720 \
  --frames 150 \
  --fps 30 \
  --bitrate 4000000 \
  --output /data/data/com.zenithblue.nativecode/cache/encoded.mp4
```
Synthesizes moving YUV patterns, feeds hardware MediaCodec encoder (`c2.mtk.avc.encoder`), and muxes to an MP4 container.

### 4. Live FFmpeg to Host VPU Streaming Encode
Pipes raw YUV frames from PRoot FFmpeg into the hardware VPU encoder:
```bash
ffmpeg -f lavfi -i testsrc=duration=5:size=1280x720:rate=30 -pix_fmt nv12 -f rawvideo - | \
  nativecode-media encode_stream \
    --width 1280 \
    --height 720 \
    --fps 30 \
    --bitrate 4000000 \
    --output /data/data/com.zenithblue.nativecode/cache/stream_encoded.mp4
```
- Raw frames travel over Linux abstract socket kernel buffer.
- MediaCodec hardware encoder writes compressed NALUs into `MediaMuxer`.
- Verified at **162.7 FPS (5.4x realtime)** on MediaTek Dimensity 8300-Ultra.

---

## Performance Reference (Poco X6 Pro / Dimensity 8300-Ultra)

| Operation | Pipeline | Resolution / Format | Measured Performance |
|---|---|---|---|
| **Decode** | MediaExtractor → Hardware MediaCodec → Surface | 1080p30 H.264 | **204.6 FPS (6.8x realtime)** |
| **Decode** | MediaExtractor → Hardware MediaCodec → Surface | 720p30 H.264 | **185.8 FPS (6.2x realtime)** |
| **Encode (Stream)** | PRoot FFmpeg raw YUV → AF_UNIX → MediaCodec → MP4 | 720p30 H.264 (4 Mbps) | **162.7 FPS (5.4x realtime)** |
