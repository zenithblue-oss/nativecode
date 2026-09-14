# Plan: NativeCode MediaCodec Hardware VPU Bridge & Agent Host IPC Bridge (Universal Snapdragon & MediaTek) — Rev 2

## Document Information
- **Status:** REVISED / READY FOR IMPLEMENTATION APPROVAL
- **Revision:** 2 (Incorporates Pass 1 Plan Review: DRM secure codec exclusion, PRoot UID discovery via rendezvous file, UI main-thread toasts, ImageReader drain pipeline, heap OOM bounds, bounded socket timeouts, and deterministic cleanup)
- **Author:** Senior Systems & Android Runtime Engineer
- **Target App:** `com.zenithblue.nativecode` (minSdk 26, targetSdk 37)
- **Host Target:** Android ARM64-v8a (Universal: Qualcomm Snapdragon, MediaTek Dimensity/Helio, Samsung Exynos, Google Tensor)
- **Guest Target:** BellSoft Alpaquita Linux Stream (glibc) / Alpine / Debian in PRoot
- **Primary Source Specification:** `/home/user/Downloads/NEXT_WORKER_MEDIACODEC_PROOT_BRIDGE.md` (Included in full below)

---

## 1. Executive Summary & Core Mandates

### 1.1 Non-Regression & Zero-Side-Effect Mandate
Strict requirement: **Do NOT regress, alter, or touch any existing subsystems.**
- All existing terminal managers, X11 desktop sessions, onboarding sequences, chroot/proot runners, package installation services, and git/github integrations remain completely intact.
- Bridge code is strictly isolated inside new, dedicated packages:
  - `com.zenithblue.nativecode.media` (Hardware VPU Bridge)
  - `com.zenithblue.nativecode.agent` (Agent Host IPC Bridge)
- No modification to existing launch scripts (`start_gui.sh`, `nativecode_proot_fast.sh`) unless Phase 1 ZINK_GATE on a physical device explicitly proves a failure in normal GUI mode (test-before-patch).
- No new Play Store-facing Foreground Service declarations in `AndroidManifest.xml` during PoC; daemon runs in background worker thread tied to session lifetime.

### 1.2 Two Bridges to Implement
1. **MediaCodec Hardware VPU Video Bridge:**
   - Enables PRoot Linux guest to offload H.264/HEVC/AV1 decode and encode to the Android hardware VPU via Android `MediaCodec`.
   - Transports **compressed media only** (not raw YUV across IPC, avoiding 373+ MB/s memory bus saturation).
   - Decodes directly to Android-owned `Surface` or discard mode.
2. **Agent Host IPC Bridge:**
   - Runs a background server inside the native Android app runtime.
   - Allows autonomous AI agents, CLI scripts, and background subagents running inside PRoot to:
     - Ping the Android host application for connectivity and responsiveness.
     - Dispatch native Android notifications to the user via `NotificationManager` (with custom channels and priority).
     - Update live status indicators (e.g. "Agent compiling kernel...", "Agent reviewing plan...") in the Android notification shade or app UI.
     - Display transient Android Toasts (safely posted to Main Looper).
     - Query host telemetry (battery level, thermal throttling status, memory pressure).

### 1.3 Universal SoC Architecture (MediaTek + Snapdragon + Generic Android)
- **Universal Codec Discovery:** Never hardcode Qualcomm (`qti` / `qcom`) or MediaTek (`mtk`). Codec discovery uses `MediaCodecList(MediaCodecList.ALL_CODECS)`.
- **Hardware Filtering:** On API 29+, inspect `info.isHardwareAccelerated && !info.isSoftwareOnly && info.isVendor`. On API 26-28 fallback, filter out software prefixes (`c2.android.*`, `OMX.google.*`, `OMX.ffmpeg.*`) and match vendor signatures (`qti`, `qcom`, `mtk`, `mediatek`, `exynos`, `samsung`, `hisilicon`, `kirin`).
- **DRM Secure Codec Exclusion (P0 Guard):** Qualcomm (`c2.qti.avc.decoder.secure`) and MediaTek (`c2.mtk.avc.decoder.secure`) place secure DRM codecs in `ALL_CODECS`. These report hardware accelerated and vendor-supported, but throw `CodecException` / `CryptoException` if initialized with standard ByteBuffers or non-secure surfaces. Selection logic strictly filters out any codec whose name contains `.secure`.
- **Format Agnosticism:** MediaTek and Snapdragon have divergent proprietary internal tiled YUV formats (e.g., MediaTek proprietary tiled `0x7F000xxx` vs Qualcomm NV12). The bridge completely neutralizes this by keeping decoded frames Android-side on a hardware `Surface` or `ImageReader` with `PRIVATE` format, bypassing all vendor-specific buffer layout mismatches.

---

## 2. Architecture & Data Flow

### 2.1 MediaCodec VPU Bridge Architecture

```text
+-------------------------------------------------------------------------+
| PRoot Linux Guest (Alpaquita / Debian / Alpine)                         |
|                                                                         |
|   +-----------------------------------------------------------------+   |
|   | Client / Agent / FFmpeg Demuxer                                 |   |
|   | 1. Reads `/run/nativecode_bridge.json` to obtain host socket    |   |
|   | 2. Connects to abstract Unix socket `@nativecode.media.v1.<uid>`|   |
|   | 3. Feeds compressed NAL units (H.264 / HEVC Annex-B or AVCC)    |   |
|   +-----------------------------------------------------------------+   |
+------------------------------------+------------------------------------+
                                     |
                                     | AF_UNIX local stream socket
                                     | Linux abstract namespace:
                                     | "@nativecode.media.v1.<hostUid>"
                                     | Length-prefixed framing (NCM1)
                                     v
+-------------------------------------------------------------------------+
| NativeCode Android Host Runtime (com.zenithblue.nativecode)             |
|                                                                         |
|   +-----------------------------------------------------------------+   |
|   | MediaBridgeServer (Background Thread / Dedicated ServerSocket)  |   |
|   | - Kernel peer credential check: peer.uid == Process.myUid()     |   |
|   | - 10s socket read timeout (soTimeout) to prevent stale hangs    |   |
|   | - Command dispatcher (ping, capabilities, benchmark_file,       |   |
|   |   open_decoder, queue_packet, queue_eos, close)                 |   |
|   +--------------------------------+--------------------------------+   |
|                                    |                                    |
|                                    v                                    |
|   +-----------------------------------------------------------------+   |
|   | Universal Hardware Codec Selector (Snapdragon & MediaTek)       |   |
|   | - MediaCodecList(ALL_CODECS) dynamic probe                      |   |
|   | - Filters out `.secure` DRM decoders                            |   |
|   | - MTK: c2.mtk.*, OMX.MTK.*                                      |   |
|   | - QCOM: c2.qti.*, OMX.qcom.*                                    |   |
|   +--------------------------------+--------------------------------+   |
|                                    |                                    |
|                                    v                                    |
|   +-----------------------------------------------------------------+   |
|   | android.media.MediaCodec (Hardware Pipeline)                    |   |
|   | - Mode A: Buffer discard mode                                   |   |
|   | - Mode B: ImageReader PRIVATE + HandlerThread Drain Loop        |   |
|   | - Deterministic cleanup in try/finally (stop/release)           |   |
|   +--------------------------------+--------------------------------+   |
|                                    |                                    |
+------------------------------------+------------------------------------+
                                     |
                                     v
                  +--------------------------------------+
                  | Qualcomm / MediaTek Hardware VPU HAL |
                  +--------------------------------------+
```

### 2.2 Agent Host IPC Bridge Architecture

```text
+-------------------------------------------------------------------------+
| PRoot Linux Guest Environment                                           |
|                                                                         |
|   +-----------------------------------------------------------------+   |
|   | AI Agent / CLI Subagent / Background Script                     |   |
|   | 1. Reads `/run/nativecode_bridge.json`                          |   |
|   | 2. Calls CLI helper `nativecode-agent notify "Build Done"`      |   |
|   |    or connects via Python/Bash to abstract domain socket        |   |
|   +--------------------------------+--------------------------------+   |
+------------------------------------+------------------------------------+
                                     |
                                     | AF_UNIX local stream socket
                                     | Linux abstract namespace:
                                     | "@nativecode.agent.v1.<hostUid>"
                                     | Length-prefixed JSON framing
                                     v
+-------------------------------------------------------------------------+
| NativeCode Android Host Runtime (com.zenithblue.nativecode)             |
|                                                                         |
|   +-----------------------------------------------------------------+   |
|   | AgentBridgeServer (Background Service / Dedicated Thread)       |   |
|   | - Kernel peer credential check: peer.uid == Process.myUid()     |   |
|   | - Bounded JSON parser (<= 64 KiB)                               |   |
|   | - Rate limiter: max 5 notifications/sec, 1 toast per 2 sec      |   |
|   | - Token Bucket Flood Protection                                 |   |
|   +--------------------------------+--------------------------------+   |
|                                    |                                    |
|         +--------------------------+--------------------------+         |
|         |                          |                          |         |
|         v                          v                          v         |
|   +-----------+             +-------------+            +------------+   |
|   | Ping /    |             | System      |            | UI Toast   |   |
|   | Health    |             | Notification|            | & Telemetry|   |
|   | Responder |             | Manager     |            | Provider   |   |
|   +-----------+             +-------------+            +------------+   |
|                               (API 33+ perm              (Posted to     |
|                                POST_NOTIF                 Main Looper   |
|                                check)                     safely)       |
|                                    |                                    |
|                                    v                                    |
|                     +-------------------------------+                   |
|                     | User Notification Shade / UI  |                   |
|                     +-------------------------------+                   |
+-------------------------------------------------------------------------+
```

---

## 3. Rendezvous & PRoot Virtualized UID Resolution

PRoot virtualizes the guest user identity (`--change-id=0:0` or `1000:1000`), so `id -u` inside PRoot returns `1000` (or `0`), whereas the host Android kernel process runs as `u0_aXYZ` (UID e.g. `10123`).

To allow PRoot clients and agents to seamlessly locate and connect to the host abstract sockets without hardcoding or leaking environment variables:
1. **Rendezvous File (`/run/nativecode_bridge.json`):**
   When `MediaBridgeServer` or `AgentBridgeServer` starts, it writes a small JSON file into the guest rootfs runtime directory (`$prootRootfsDir/run/nativecode_bridge.json`):
   ```json
   {
     "v": 1,
     "host_uid": 10123,
     "media_socket": "@nativecode.media.v1.10123",
     "agent_socket": "@nativecode.agent.v1.10123",
     "created_at": 1773739200
   }
   ```
2. **Abstract Socket Construction:**
   - On the Android host side, `LocalServerSocket("nativecode.media.v1." + Process.myUid())` creates an abstract namespace socket without any leading null byte in the Java API string.
   - On the Linux guest side (Python / C / netcat), the client connects using ` nativecode.media.v1.<host_uid>` (or `@nativecode.media.v1.<host_uid>`).
3. **Peer Credential Authentication:**
   Despite PRoot virtualizing the guest UID to 1000, the Linux kernel's `SO_PEERCRED` / `socket.peerCredentials` on the host side reliably reports the true host Android UID (`10123`).
   `socket.peerCredentials.uid == Process.myUid()` evaluates to `true` and guarantees fail-closed isolation against any malicious third-party app.

---

## 4. Protocol & Framing Specifications

### 4.1 MediaCodec VPU Bridge Protocol
1. **Transport:** `AF_UNIX` in Linux abstract namespace:
   - Java Host: `LocalServerSocket("nativecode.media.v1." + Process.myUid())`
   - Linux Guest: ` nativecode.media.v1.<host_uid>`
   - Socket timeout: `socket.setSoTimeout(10000)` (10 seconds bounded read timeout).
2. **Peer Authentication:**
   - `LocalSocket.getPeerCredentials()`
   - MUST require: `peerCredentials.uid == Process.myUid()`
   - Non-matching UID is disconnected immediately with zero data read.
3. **Control Framing (JSON):**
   - Format: `[4 bytes uint32 big-endian payload_length][UTF-8 JSON bytes]`
   - Hard limit: Maximum control frame size = 64 KiB.
   - Core Control Messages:
     - `{"v":1,"cmd":"ping"}` -> `{"v":1,"ok":true,"ts":123456789}`
     - `{"v":1,"cmd":"capabilities"}` -> `{"v":1,"ok":true,"soc":"...","codecs":[...]}`
     - `{"v":1,"cmd":"benchmark_file","path":"...","mode":"decode","mime":"video/avc"}`
     - `{"v":1,"cmd":"cancel"}`
4. **Packet Streaming Framing (`NCM1` Binary Header):**
   - 24-byte fixed binary header:
     - Magic: `NCM1` (4 bytes, 0x4E 0x43 0x4D 0x31)
     - Version: `uint8` (1)
     - Type: `uint8` (0x01 = INIT, 0x02 = PACKET, 0x03 = EOS, 0x04 = CLOSE, 0x80 = ACK, 0x81 = ERROR)
     - Flags: `uint16` big-endian (0x0001 = KEYFRAME, 0x0002 = CSD / Parameter Set)
     - Stream ID: `uint32` big-endian
     - PTS: `uint64` big-endian (presentation timestamp in microseconds)
     - Payload Length: `uint32` big-endian (max 16 MiB per packet)
   - Followed immediately by raw compressed NAL unit bytes.
5. **Queue Backpressure & Heap Protection:**
   - Maximum queued payload threshold is capped at **16 MiB** (lowered from 64 MiB to prevent Dalvik heap growth OOM on 2-3 GB RAM devices).
   - The reader streams packet bytes directly into `codec.getInputBuffer(index)` whenever an input buffer is dequeued.
   - If no input buffer is available and queued payload exceeds 16 MiB, the server thread pauses reading from the socket `InputStream`, providing natural TCP/socket-level backpressure to the guest sender.

### 4.2 Agent Host IPC Bridge Protocol
1. **Transport:** `AF_UNIX` in Linux abstract namespace:
   - Java Host: `LocalServerSocket("nativecode.agent.v1." + Process.myUid())`
   - Linux Guest: ` nativecode.agent.v1.<host_uid>`
   - Socket timeout: 10s timeout.
2. **Peer Authentication:**
   - `LocalSocket.getPeerCredentials()`
   - MUST require: `peerCredentials.uid == Process.myUid()`.
3. **Framing:**
   - `[4 bytes uint32 big-endian length][UTF-8 JSON payload]` (max 64 KiB).
4. **Rate Limiting:**
   - Token-bucket rate limiter enforces max 5 notification dispatches per second and max 1 toast per 2 seconds, preventing PRoot scripts from flooding the UI.
5. **Command Catalog:**
   - **Ping / Handshake:**
     - Request: `{"v":1,"cmd":"ping","client_id":"agent-worker-1"}`
     - Response: `{"v":1,"ok":true,"app_version":"...","battery":88,"thermal_headroom":1.0}`
   - **Notification Dispatch:**
     - Request:
       ```json
       {
         "v": 1,
         "cmd": "notify",
         "id": 101,
         "tag": "agent_build",
         "title": "Build Complete",
         "message": "Alpaquita rootfs compiled successfully in 4m 12s.",
         "priority": "high",
         "ongoing": false
       }
       ```
     - Permission Check (API 33+): Check `ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED`. If denied, response returns `{"v":1,"ok":false,"error":"POST_NOTIFICATIONS permission not granted"}`.
     - Response on success: `{"v":1,"ok":true,"posted":true}`.
   - **Status Update (Ongoing Status in Notification Shade / Telemetry Card):**
     - Request:
       ```json
       {
         "v": 1,
         "cmd": "status",
         "key": "agent_task",
         "text": "Running test 42 of 150 (Memory benchmark)...",
         "progress": 28
       }
       ```
     - Response: `{"v":1,"ok":true}`.
   - **Transient Toast:**
     - Request: `{"v":1,"cmd":"toast","text":"Agent test checkpoint reached"}`
     - Implementation: Dispatched safely to Android Main Looper via `Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }`.
     - Response: `{"v":1,"ok":true}`.
   - **Telemetry Query:**
     - Request: `{"v":1,"cmd":"telemetry"}`
     - Response:
       ```json
       {
         "v": 1,
         "ok": true,
         "battery_level": 92,
         "is_charging": true,
         "thermal_status": "NONE",
         "available_memory_mb": 4096
       }
       ```

---

## 5. Universal SoC Implementation Details

### 5.1 Qualcomm Snapdragon vs MediaTek Dimensity/Helio Mapping
| Dimension | Qualcomm Snapdragon | MediaTek Dimensity / Helio |
|-----------|---------------------|----------------------------|
| Codec2 Component Prefix | `c2.qti.avc.decoder`, `c2.qti.hevc.decoder` | `c2.mtk.avc.decoder`, `c2.mtk.hevc.decoder` |
| Legacy OMX Component Prefix | `OMX.qcom.video.decoder.avc`, `OMX.qcom.video.decoder.hevc` | `OMX.MTK.VIDEO.DECODER.AVC`, `OMX.MTK.VIDEO.DECODER.HEVC` |
| Codec2 Encoder Prefix | `c2.qti.avc.encoder`, `c2.qti.hevc.encoder` | `c2.mtk.avc.encoder`, `c2.mtk.hevc.encoder` |
| DRM Secure Codecs to Exclude | `c2.qti.avc.decoder.secure`, `OMX.qcom.video.decoder.avc.secure` | `c2.mtk.avc.decoder.secure`, `OMX.MTK.VIDEO.DECODER.AVC.secure` |
| Color Format Traps (Direct Buffers) | Often NV12 (`COLOR_FormatYUV420SemiPlanar`) | Often proprietary tiled YUV (`0x7F000xxx` MTK format constants) |
| Mitigation Strategy | Output directly to Surface / ImageReader `PRIVATE` | Output directly to Surface / ImageReader `PRIVATE` |

### 5.2 Dynamic Codec Selection Algorithm (`MediaCodecSelector.kt`)
```kotlin
package com.zenithblue.nativecode.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

object MediaCodecSelector {

    fun selectHardwareDecoder(mime: String): MediaCodecInfo? {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        val candidates = codecList.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

        // P0 CRITICAL GUARD: Filter out DRM secure decoders (.secure).
        // Secure decoders crash with CryptoException/insufficient resources when
        // initialized with standard non-DRM ByteBuffers or non-secure Surfaces.
        val nonSecureCandidates = candidates.filter { info ->
            val name = info.name.lowercase()
            !name.endsWith(".secure") && !name.contains(".secure.")
        }

        if (Build.VERSION.SDK_INT >= 29) {
            // API 29+: Use canonical framework hardware attributes
            return nonSecureCandidates.firstOrNull { info ->
                info.isHardwareAccelerated && !info.isSoftwareOnly && info.isVendor
            } ?: nonSecureCandidates.firstOrNull { info ->
                info.isHardwareAccelerated && !info.isSoftwareOnly
            }
        }

        // API 26-28 Fallback: Detect vendor hardware signatures and eliminate software implementations
        val softwarePrefixes = listOf("c2.android.", "omx.google.", "omx.ffmpeg.", "c2.google.")
        val hardwareVendorSignatures = listOf("qti", "qcom", "mtk", "mediatek", "exynos", "samsung", "hisilicon", "kirin")

        val nonSoftware = nonSecureCandidates.filter { info ->
            val nameLower = info.name.lowercase()
            softwarePrefixes.none { nameLower.startsWith(it) }
        }

        return nonSoftware.firstOrNull { info ->
            val nameLower = info.name.lowercase()
            hardwareVendorSignatures.any { nameLower.contains(it) }
        } ?: nonSoftware.firstOrNull()
    }
}
```

### 5.3 Surface / ImageReader Drain Pipeline (`ImageReaderDrainPipeline.kt`)
To prevent the 2-3 frame buffer queue from deadlocking during Mode B (Surface/ImageReader decode benchmark), the pipeline explicitly manages a dedicated draining thread:
```kotlin
class ImageReaderDrainPipeline(width: Int, height: Int) : AutoCloseable {
    private val drainThread = android.os.HandlerThread("ImageReaderDrain").apply { start() }
    private val handler = android.os.Handler(drainThread.looper)

    val imageReader: android.media.ImageReader = android.media.ImageReader.newInstance(
        width, height, android.graphics.ImageFormat.PRIVATE, 3
    ).apply {
        setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.close()
        }, handler)
    }

    val surface: android.view.Surface get() = imageReader.surface

    override fun close() {
        imageReader.close()
        drainThread.quitSafely()
    }
}
```

---

## 6. Phased Execution Plan & Acceptance Gates

### Phase 0: Baseline & Hygiene Check
- Record current git branch, commit hash, and uncommitted status.
- Verify existing debug build:
  `./gradlew :app:assembleDebug`
  `./gradlew :app:testDebugUnitTest`
- Guarantee: Zero regressions or alterations to existing working tree.

### Phase 1: Real Zink Production Path Verification
- **ZINK_GATE:**
  - Launch NativeCode GUI via normal product path.
  - Run `glxinfo -B` inside X11 session.
  - If output shows `Zink -> Turnip -> Adreno` (or appropriate Vulkan driver): **PASS**.
  - Only patch Turnip environment variables (`GALLIUM_DRIVER=zink`) if and only if normal GUI falls back to `llvmpipe`/`softpipe`.

### Phase 2: Universal MediaCodec Capability Inventory
- Implement `app/src/main/java/com/zenithblue/nativecode/media/MediaCodecInventory.kt`.
- Probe priority MIME types: `video/avc`, `video/hevc`, `video/x-vnd.on2.vp9`, `video/av01`.
- Dynamically query `VideoCapabilities` (resolutions, framerates, alignments).
- Generate diagnostics JSON: `files/diagnostics/media_codec_inventory_<timestamp>.json`.
- **CODEC_INVENTORY_GATE:** Hardware AVC decoder confirmed on API 29+. If not, STOP.

### Phase 3: Android-Only Hardware Decode Benchmark
- Implement instrumentation test: `MediaCodecDecodeBenchmarkTest.kt`.
- Use test source files (H.264 1080p30 30s, HEVC 1080p30 30s).
- Benchmark both:
  - Mode A: Buffer output (drain and discard).
  - Mode B: Surface / ImageReader `PRIVATE` output with `ImageReaderDrainPipeline`.
- Measure: wall time, CPU time, FPS, first-frame latency, thermals.
- Compare against baseline software FFmpeg running inside PRoot.
- **ANDROID_DECODE_GATE:** Hardware decode reliable, 5/5 runs pass EOS, >= 1.5x throughput or >= 30% CPU reduction.

### Phase 4: Hardware Encoder Capability / Benchmark
- Inventory hardware AVC/HEVC encoders on Snapdragon and MediaTek.
- Test 1280x720@30 H.264 5 Mbps encode (300 frames) where hardware encoder is available.

### Phase 5: PRoot ↔ Android IPC Proof
- Implement:
  - `app/src/main/java/com/zenithblue/nativecode/media/MediaBridgeProtocol.kt`
  - `app/src/main/java/com/zenithblue/nativecode/media/MediaBridgeServer.kt`
- AF_UNIX abstract socket: `@nativecode.media.v1.<uid>`.
- Strict kernel peer authentication: `peer.uid == Process.myUid()`.
- Rendezvous file written to `/run/nativecode_bridge.json`.
- Test 1,000 ping RTTs, throughput on 64 KiB, 1 MiB, 4 MiB buffers.
- **IPC_GATE:** PRoot connects, same UID verified, framing reliable, clean close/cancel.

### Phase 6: End-to-End File-Path Decode Bridge
- Command: `benchmark_file(path, mime)`.
- Path security: Canonical path allowlist strictly limited to app-owned storage (`files/usr/tmp/`, `files/home/`). Reject `..`, symlink escapes, `/system`, `/vendor`.
- MediaExtractor + hardware MediaCodec decodes file entirely on Android host side; returns structured JSON metrics to PRoot.
- Strict `try/finally` resource cleanup: stops/releases `MediaCodec` and `MediaExtractor` on any error or client disconnect.
- **BRIDGE_FILE_GATE:** 5/5 runs pass on H.264 (and HEVC where available), no leaked codecs, clean reconnect.

### Phase 7: Compressed Packet Streaming Bridge
- Binary framing (`NCM1` protocol): `OPEN_DECODER`, `QUEUE_PACKET`, `QUEUE_EOS`, `CLOSE_DECODER`.
- Streams compressed packets across socket; hardware VPU drains to Android Surface.
- Backpressure control: Queue limited to 16 MiB with socket read pause on high-water mark.
- **PACKET_STREAM_GATE:** PRoot client streams H.264 bitstream, reaching exact expected output frame count and clean EOS.

### Phase 8: Agent Host IPC Bridge Implementation
- Implement:
  - `app/src/main/java/com/zenithblue/nativecode/agent/AgentBridgeProtocol.kt`
  - `app/src/main/java/com/zenithblue/nativecode/agent/AgentBridgeServer.kt`
  - Guest CLI client: `tools/agent_bridge/nativecode-agent` (Python / Shell executable).
- Background server started with app / terminal session; listens on `@nativecode.agent.v1.<uid>`.
- Authenticates peer UID equals app UID.
- Notification handling:
  - Creates dedicated channel `agent_notifications` ("Agent Alerts & Progress").
  - Verifies `POST_NOTIFICATIONS` permission on Android 13+.
  - Posts user-visible notifications with optional progress and actions.
  - Dispatches Toasts on Main Looper.
- Exposes host telemetry (battery, thermals, memory).
- **AGENT_BRIDGE_GATE:** Guest agent can send ping, post notification, and update ongoing status with < 5 ms latency and zero background battery drain when idle.

### Phase 9: Security Hardening & Zero-Copy Feasibility
- Scope daemon strictly to background worker thread tied to session lifetime; no premature Play Store FGS declarations.
- Ensure all resources (codecs, sockets, buffers, notifications) clean up deterministically on client disconnect or cancellation.
- Explore `AHardwareBuffer` Android-side GPU surface feasibility without leaking unshared Linux glibc assumptions.

---

## 7. Verification Strategy & Deliverables

### 7.1 Unit & Instrumentation Tests
1. **Unit Tests (`app/src/test/java/com/zenithblue/nativecode/`):**
   - `media/MediaBridgeProtocolTest.kt` (frame parsing, length bounds, malformed packet rejection)
   - `media/CodecSelectionTest.kt` (Snapdragon vs MediaTek selection logic, `.secure` DRM exclusion, software codec exclusion)
   - `media/AllowedPathTest.kt` (path traversal, symlink escapes, sandbox restrictions)
   - `agent/AgentBridgeProtocolTest.kt` (JSON command parsing, schema validation, rate limit)
2. **Android Instrumentation Tests (`app/src/androidTest/java/com/zenithblue/nativecode/`):**
   - `media/MediaCodecInventoryTest.kt` (Hardware codec detection on physical device)
   - `media/MediaCodecDecodeBenchmarkTest.kt` (5-run hardware decode benchmark)
   - `media/MediaBridgeServerTest.kt` (AF_UNIX socket connection, peer UID check, file benchmark RPC)
   - `agent/AgentBridgeServerTest.kt` (Agent socket ping, notification dispatch, telemetry response)

### 7.2 Deliverable Artifacts
- Source code in `app/src/main/java/com/zenithblue/nativecode/media/` and `app/src/main/java/com/zenithblue/nativecode/agent/`.
- Test suites in `app/src/test/` and `app/src/androidTest/`.
- Guest CLI tools in `tools/media_bridge/` and `tools/agent_bridge/`.
- Benchmark results in `docs/perf/results/` (JSON schema compliant).

---

## 8. Full Reference Specification Verbatim

The complete specification from `/home/user/Downloads/NEXT_WORKER_MEDIACODEC_PROOT_BRIDGE.md` is incorporated below in its entirety to ensure absolute fidelity to all historical decisions, non-goals, and gate criteria.

```markdown
# NEXT WORKER — NativeCode MediaCodec ↔ PRoot Hardware Video Bridge

Repository: `zenithblue-oss/nativecode-ai`
Target app: `com.zenithblue.nativecode`
Current SDK baseline: minSdk 26, targetSdk 36
Primary device baseline: SM8650 / Snapdragon 8 Gen 3 / Adreno 750
Guest baseline: BellSoft Alpaquita Linux Stream (glibc), ARM64, PRoot

## Objective

Build the smallest safe proof that NativeCode's PRoot can request Android hardware video decode/encode through the NativeCode Android process.

Already proven and NOT to be repeated:

- ARM NEON works inside PRoot.
- Turnip Vulkan works through `/dev/kgsl-3d0`.
- Zink can drive Turnip/Adreno.
- Current Turnip exposes no Vulkan Video extensions/queues.
- VA-API is not available in the current FFmpeg/userspace stack.
- OpenCL has no platform.
- Qualcomm `/dev/video*` codec candidates are visible but return `EACCES`.
- FFmpeg already has `h264_v4l2m2m` / `hevc_v4l2m2m` wrappers, so rebuilding FFmpeg does not solve the VPU permission blocker.

AOSP currently has a `neverallow` blocking normal app domains from read/write access to `video_device` except the DeviceAsWebcam service. Direct V4L2 access is therefore not a production architecture for a normal Play-distributed NativeCode app.

Target architecture:

```text
Alpaquita PRoot
      |
      | AF_UNIX local RPC
      | compressed media / control
      v
NativeCode Android process
      |
      | android.media.MediaCodec
      v
Android codec framework
      |
      v
Qualcomm hardware VPU
```

Do NOT make AHardwareBuffer or transparent FFmpeg integration prerequisites.

---

# 1. Read first

Read the current implementations before editing:

```text
AGENTS.md
app/build.gradle.kts
app/src/main/AndroidManifest.xml

app/src/main/java/com/zenithblue/nativecode/terminal/TerminalService.kt
app/src/main/java/com/zenithblue/nativecode/NativeCodeFgs.kt
app/src/main/java/com/zenithblue/nativecode/FgsLeaseRegistry.kt

app/src/main/cpp/CMakeLists.txt

app/src/main/assets/scripts/start_gui.sh
app/src/main/assets/scripts/setup_hw_accel_debian.sh
app/src/main/assets/scripts/nativecode_proot_fast.sh
app/src/main/java/com/zenithblue/nativecode/terminal/ProotFastCommandBuilder.kt

docs/environment/adb-shell-access.md
docs/environment/proot-vs-chroot-perf-report.md
docs/perf/proot-parity-v2.md
```

Also read the supplied GPU/VPU final reports.

Do not assume older Debian plans match the installed Alpaquita container.

---

# 2. Non-goals / hard stop list

Do not:

```text
chmod/chown /dev/video*
disable SELinux
use setenforce
request root as the product solution
rebuild FFmpeg for VA-API
rebuild FFmpeg for V4L2
force Vulkan Video extensions
pursue OpenCL
install libva
implement libhybris
send every raw decoded frame back to PRoot as the main design
start with AHardwareBuffer-to-glibc interop
fork FFmpeg before bridge benchmarks prove value
open a TCP/HTTP media server
accept arbitrary shell commands
accept arbitrary filesystem paths
reuse TerminalService's specialUse declaration to hide media-processing work
```

The worker should stop at failed gates rather than forcing dead architectures.

---

# 3. Why the bridge must transport compressed media, not raw frames

Raw YUV420 bandwidth is large:

```text
1080p30 ~= 93 MB/s
4K30    ~= 373 MB/s
4K60    ~= 746 MB/s
```

The intended long-term data path is:

```text
PRoot -> compressed H.264/HEVC/AV1 -> MediaCodec VPU
```

For encode:

```text
Android-owned raw/GPU surface -> MediaCodec encoder -> compressed packets -> PRoot
```

A raw-frame return path may exist for diagnostics only.

---

# 4. Phase 0 — baseline

Before edits:

```text
record branch
record commit
record git status
```

Run baseline:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Preserve unrelated changes.

Follow all `AGENTS.md` approval/state-change rules.

---

# 5. Phase 1 — verify/fix the real Zink production path

Current repo state is inconsistent:

`nativecode_proot_fast.sh` and `ProotFastCommandBuilder.kt` already include:

```bash
MESA_LOADER_DRIVER_OVERRIDE=zink
GALLIUM_DRIVER=zink
VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
TU_DEBUG=noconform
MESA_VK_WSI_DEBUG=sw
```

But the active Turnip branches in `start_gui.sh` and the generated `gpu-launch` in `setup_hw_accel_debian.sh` currently omit `GALLIUM_DRIVER=zink`.

The device report showed:

```text
MESA_LOADER_DRIVER_OVERRIDE=zink alone -> llvmpipe in surfaceless EGL
GALLIUM_DRIVER=zink                    -> Zink -> Turnip -> Adreno 750
```

## 5.1 Test before patch

Launch NativeCode GUI through the normal product path.

Inside that real X11 session, run an existing renderer query such as:

```bash
glxinfo -B
```

Capture:

```text
OpenGL vendor
OpenGL renderer
OpenGL version
GALLIUM_DRIVER
MESA_LOADER_DRIVER_OVERRIDE
VK_ICD_FILENAMES
```

### ZINK_GATE

If normal GUI already renders as Zink/Turnip/Adreno:

```text
PASS
```

Do not patch just because the headless probe required `GALLIUM_DRIVER`.

If normal GUI reports llvmpipe/softpipe, patch active Turnip launch paths.

At minimum inspect/patch as needed:

```text
app/src/main/assets/scripts/start_gui.sh
app/src/main/assets/scripts/setup_hw_accel_debian.sh
app/src/main/assets/scripts/chroot/start_debian13_gui.sh
```

Active Turnip environment should be consistent:

```bash
export MESA_LOADER_DRIVER_OVERRIDE=zink
export GALLIUM_DRIVER=zink
export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
export TU_DEBUG=noconform
export MESA_VK_WSI_DEBUG=sw
```

Do not regress VirGL:

```bash
GALLIUM_DRIVER=virpipe
```

Retest the actual GUI path.

---

# 6. Phase 2 — MediaCodec capability inventory

Do this before any socket bridge.

Suggested production-independent class:

```text
app/src/main/java/com/zenithblue/nativecode/media/MediaCodecInventory.kt
```

Suggested instrumentation test:

```text
app/src/androidTest/java/com/zenithblue/nativecode/media/MediaCodecInventoryTest.kt
```

Use:

```kotlin
MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
```

Priority MIME types:

```text
video/avc
video/hevc
video/x-vnd.on2.vp9
video/av01
```

For every matching codec record:

```text
name
canonicalName when available
encoder/decoder
alias
vendor
hardwareAccelerated
softwareOnly
maxSupportedInstances
colorFormats
profileLevels
```

NativeCode minSdk is 26.

For API 29+ use:

```kotlin
isHardwareAccelerated
isSoftwareOnly
isVendor
isAlias
canonicalName
```

For API 26-28:

```text
hardware classification = UNKNOWN
```

Do not infer hardware solely from codec name.

## 6.1 VideoCapabilities

Record:

```text
width range
height range
frame-rate range
bitrate range
width alignment
height alignment
max instances
```

Probe:

```text
1280x720@30
1920x1080@30
1920x1080@60
3840x2160@30
3840x2160@60
```

Use:

```kotlin
areSizeAndRateSupported()
```

API 29+:

```kotlin
getSupportedPerformancePoints()
```

A null performance-point list is not failure.

## 6.2 Output

Write app-private JSON:

```text
files/diagnostics/media_codec_inventory_<timestamp>.json
```

Include:

```text
manufacturer
model
SDK
SoC manufacturer/model where available
NativeCode git commit
all priority codec entries
```

### CODEC_INVENTORY_GATE

Proceed only if a confirmed hardware AVC decoder exists on API 29+.

If not:

```text
STOP_MEDIA_BRIDGE_NO_HW_AVC
```

---

# 7. Phase 3 — Android-only hardware decode benchmark

Prove MediaCodec before IPC.

Suggested:

```text
app/src/androidTest/java/com/zenithblue/nativecode/media/MediaCodecDecodeBenchmarkTest.kt
```

Use the SAME sources as the PRoot FFmpeg baseline when possible:

```text
H.264 1080p30, 30 s
HEVC 1080p30, 30 s
```

Do not commit large binaries unless necessary.

## 7.1 Select an exact codec

Do not assume `createDecoderByType()` chose hardware.

Use inventory result and:

```kotlin
MediaCodec.createByCodecName(codecName)
```

Record exact codec.

## 7.2 Feed with MediaExtractor

Use:

```kotlin
MediaExtractor
```

Select the video track and use its exact `MediaFormat`, including `csd-*`.

Do not manually reconstruct SPS/PPS/VPS for this file-based benchmark.

## 7.3 Output modes

Benchmark where supported:

### A. Buffer output

Configure without a Surface.

Drain/release output immediately.

Label:

```text
MEDIACODEC_BUFFER_OUTPUT
```

### B. Surface/ImageReader output

Use a PRIVATE/hardware-oriented ImageReader Surface where supported.

Acquire/close images promptly to avoid backpressure.

Do not CPU-read all pixels.

Label:

```text
MEDIACODEC_SURFACE_OUTPUT
```

If unsupported, record exact failure; do not fail the entire PoC.

## 7.4 Loop requirements

The decode loop must:

```text
queue extractor samples
drain output continuously
handle output format changed
queue EOS
drain EOS
count frames
release codec/extractor/surface cleanly
```

Use bounded dequeue timeouts.

No busy-spin.

No infinite loop.

## 7.5 Metrics

Warm once, then at least 5 measured runs.

Record:

```text
codec name
hardware flag
source MIME
resolution/fps
input samples
decoded frames
wall time
decoded fps
realtime factor
codec create/config/start latency
first-frame latency
CPU time where practical
thermal before/after
errors
```

Correctness:

```text
EOS reached
expected frame count approximately matches
no CodecException
```

## 7.6 Compare same source to PRoot FFmpeg

Rerun the existing PRoot software decode using the exact same file.

Report:

```text
MediaCodec wall
MediaCodec CPU
FFmpeg wall
FFmpeg CPU
thermal delta
```

### ANDROID_DECODE_GATE

Continue only if hardware decode is reliable and worthwhile.

Suggested threshold:

```text
>=1.5x wall-throughput improvement
OR
>=30% CPU-time reduction at similar wall time
OR
clear thermal/power reduction while sustaining realtime
```

CPU/power savings count even if software decode is already realtime.

---

# 8. Phase 4 — encoder capability / optional benchmark

After decode gate passes.

Inventory hardware AVC/HEVC encoders:

```text
profiles
levels
bitrate modes
size/rate
color formats
max instances
```

First encode PoC may use ByteBuffer YUV if hardware encoder advertises:

```text
COLOR_FormatYUV420Flexible
```

Start:

```text
1280x720@30
300 frames
H.264 5 Mbps
```

Then HEVC if supported.

Record:

```text
codec
frames
output bytes
wall
CPU
fps
thermal
```

Do not claim x264/x265 quality equivalence.

Surface-input encode is later.

---

# 9. Phase 5 — prove PRoot ↔ Android IPC first

Do not send media yet.

Suggested files:

```text
app/src/main/java/com/zenithblue/nativecode/media/MediaBridgeProtocol.kt
app/src/main/java/com/zenithblue/nativecode/media/MediaBridgeServer.kt
```

Do not use TCP.

Use Android AF_UNIX.

Preferred first transport:

```text
Linux abstract namespace
```

Android `LocalServerSocket(String)` creates an abstract UNIX socket.

Logical name:

```text
nativecode.media.v1.<appUid>
```

PRoot client connects using:

```text
"\0nativecode.media.v1.<uid>"
```

Use a temporary Python or C client in PRoot.

## 9.1 Peer authentication

Immediately after accept:

```kotlin
socket.peerCredentials
```

Require:

```text
peer uid == Process.myUid()
```

PRoot guest `id` may display 1000, but the kernel peer credential should be the host Android app UID.

If not:

```text
STOP_IPC_CREDENTIAL_MISMATCH
```

Do not weaken auth.

## 9.2 Protocol

No Java serialization.

No shell commands.

Use versioned framing:

```text
uint32 big-endian length
UTF-8 JSON
```

Hard limits:

```text
control frame <= 64 KiB
bounded read timeout
bounded write timeout
one active session initially
```

Initial commands:

```json
{"v":1,"cmd":"ping"}
{"v":1,"cmd":"capabilities"}
{"v":1,"cmd":"benchmark_file","path":"...","mode":"decode","mime":"video/avc"}
{"v":1,"cmd":"cancel"}
```

Responses:

```json
{"v":1,"ok":true}
```

## 9.3 Path security

For file-based PoC, allow only canonical paths under approved NativeCode-owned roots such as:

```text
/data/data/com.zenithblue.nativecode/files/usr/tmp/
/data/data/com.zenithblue.nativecode/files/home/
```

Reject:

```text
..
symlink escape
/system
/vendor
other app data
arbitrary shared storage
```

unless later explicitly designed.

## 9.4 IPC microbench

Before codec RPC:

```text
1000 ping RTTs
64 KiB throughput
1 MiB throughput
4 MiB throughput
```

Record wall/CPU.

### IPC_GATE

PASS only if:

```text
PRoot connects
peer UID equals app UID
framing is reliable
cancel/close works
no hang
```

If abstract sockets are blocked, do NOT switch to TCP.

Fallback only after actual failure:

```text
JNI/native filesystem AF_UNIX socket
app-private path
0600
SO_PEERCRED validation
```

---

# 10. Phase 6 — end-to-end file-path decode bridge

First product-shaped flow:

```text
PRoot
  -> benchmark_file(path)
Android MediaBridgeServer
  -> MediaExtractor
  -> hardware MediaCodec
  -> discard or PRIVATE Surface
  -> JSON result
PRoot
```

No media bytes cross the socket yet.

This isolates codec performance from media transport.

Return structured fields:

```text
selected codec
hardware flag
mime
width
height
fps
frames
wall_ms
cpu_ms
decoded_fps
realtime_factor
first_frame_ms
status
```

Log full errors privately; return concise error codes.

### BRIDGE_FILE_GATE

Require:

```text
5/5 H.264 successful
5/5 HEVC successful when available
no leaked codecs
no hanging sockets
cancel terminates quickly
clean reconnect
```

---

# 11. Phase 7 — compressed packet streaming

Only after file bridge passes.

Do NOT stream raw decoded frames.

Add commands:

```text
OPEN_DECODER
QUEUE_PACKET
QUEUE_EOS
CLOSE_DECODER
```

Use binary payloads, not base64.

Suggested envelope:

```text
4 bytes magic: NCM1
1 byte version
1 byte type
2 bytes flags
4 bytes stream id
8 bytes pts_us
4 bytes payload length
payload
```

Limits:

```text
packet <= 16 MiB
queued compressed bytes <= 64 MiB
backpressure required
```

No whole-file buffering.

Transport codec-specific data exactly.

Do not assume Annex-B vs AVCC/HVCC.

Output remains Android-owned:

```text
discard
or
PRIVATE Surface
```

Return only frame/event metadata.

### PACKET_STREAM_GATE

PASS when a PRoot client streams the known H.264 source and reaches the same expected output frame count + EOS as file decode.

---

# 12. Phase 8 — service lifecycle only after bridge is worth keeping

Do not place long-running codec loops in `MainActivity`.

After gates pass, add:

```text
app/src/main/java/com/zenithblue/nativecode/media/MediaBridgeService.kt
```

Do not overload `TerminalService`.

For long-running conversion/transcoding, Android 15+ provides:

```text
foregroundServiceType="mediaProcessing"
```

and:

```text
android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING
```

TargetSdk is 36.

Android limits background `mediaProcessing` FGS use to about 6 hours per 24h.

Stop service on:

```text
completion
cancel
fatal error
last-client disconnect with no active work
timeout
```

Do not reuse the terminal `specialUse` Play declaration for media conversion.

For playback, `mediaPlayback` is the semantic type, but playback is not the first milestone.

Do not add Play-facing service declarations before the PoC proves value.

---

# 13. Phase 9 — Android-side AHardwareBuffer feasibility

Do NOT start here.

After packet bridge passes:

```text
MediaCodec
 -> ImageReader PRIVATE Surface
 -> Image
 -> HardwareBuffer/AHardwareBuffer
```

Record:

```text
width/height
format
HardwareBuffer present
buffer ID on API31+
```

Do not CPU-lock PRIVATE buffers.

If native code takes an `AHardwareBuffer` reference:

```text
acquire before Image lifetime ends
release after use
```

Optional later Android-side Vulkan import:

```text
VK_ANDROID_external_memory_android_hardware_buffer
```

Do not assume guest Turnip can import the Android object directly.

---

# 14. Critical AHardwareBuffer caveat

Android provides:

```text
AHardwareBuffer_sendHandleToUnixSocket()
AHardwareBuffer_recvHandleFromUnixSocket()
```

and documents AHardwareBuffer cross-process sharing as zero-copy.

But the Alpaquita PRoot guest is glibc Linux.

`AHardwareBuffer_recvHandleFromUnixSocket()` is an Android NDK API.

Therefore do not assume a guest binary can simply link and call it.

Potential later research:

```text
keep AHardwareBuffer Android-side
Android Vulkan processing + return compressed output
export/import native DMABUF FDs + exact plane/modifier metadata
Bionic helper process
custom native interop bridge
```

None block the MediaCodec PoC.

---

# 15. Preferred eventual architectures

## Playback

```text
PRoot/control
 -> NativeCode MediaCodec
 -> Android Surface
 -> display
```

No raw frames back to PRoot.

## Transcode without Linux raw-frame filters

```text
compressed input
 -> MediaCodec decoder
 -> Android Surface/GPU processing
 -> MediaCodec encoder
 -> compressed output
```

Keep raw frames Android-side.

## Linux FFmpeg filter graph

This is the hard case.

If the Linux pipeline needs every raw frame, hardware decode may lose much of its benefit through copies.

Do not promise transparent acceleration for arbitrary FFmpeg filters.

## Future transparent FFmpeg

Only after bridge benchmarks prove value, research:

```text
custom FFmpeg decoder/encoder wrapper
custom AVHWDeviceContext
or nativecode-media external CLI/protocol
```

Not in this worker.

---

# 16. Bridge security

Mandatory:

```text
android:exported=false for production service
AF_UNIX only
peer credential validation
same app UID only
versioned protocol
strict message bounds
strict packet bounds
strict queue bounds
canonical path allowlist
timeouts
cancellation
single session initially
no shell execution
no arbitrary file reads
no TCP listener
no Java serialization
```

Session state should track:

```text
session id
peer uid
codec name
mime
state
created time
last activity
queued bytes
```

On disconnect:

```text
stop/release MediaCodec
release MediaExtractor
release Surface/ImageReader
free buffers
remove session
```

---

# 17. Resource limits

Initial PoC safety bounds:

```text
max sessions = 1
max control message = 64 KiB
max compressed packet = 16 MiB
max queued compressed payload = 64 MiB
bounded idle timeout
bounded codec dequeue timeout
resolution <= codec capability and conservative hard cap
```

Validate length before allocation.

---

# 18. Threading

Never run codec loops on main thread.

Use:

```text
server accept thread
one session worker
codec loop thread
```

Keep first implementation synchronous/simple.

No busy-spin.

Do not add libraries just for concurrency unless already used and clearly needed.

---

# 19. Benchmark matrix

Use the same physical device and exact source files.

H.264:

```text
PRoot software FFmpeg
Android MediaCodec buffer output
Android MediaCodec Surface output
bridge file decode
bridge packet-stream decode
```

HEVC: same.

Only after stable 1080p30:

```text
1080p60
4K30
AV1
hardware encode
```

---

# 20. Measurement method

For performance:

```text
1 warm-up
5 measured runs minimum
2 s thermal rest between sustained runs
thermal before/after
```

Report:

```text
min
median
max
MAD
```

Separate:

```text
codec create/config/start latency
steady-state
total wall
first frame
CPU time
```

Do not declare wins when variance is comparable to the delta.

---

# 21. Required tests

Unit tests:

```text
MediaBridgeProtocolTest
CodecSelectionTest
AllowedPathTest
FrameBoundsTest
SessionStateTest
```

Malformed cases:

```text
negative length
oversize
unknown protocol version
unknown command
path traversal
symlink escape
invalid codec
invalid resolution
packet before OPEN
packet after EOS
double close
```

Instrumentation:

```text
MediaCodecInventoryTest
MediaCodecDecodeBenchmarkTest
MediaBridgeServerTest
```

Lifecycle:

```text
disconnect mid-session
cancel
unsupported MIME
invalid file
codec error
EOS
reconnect
server stop
```

Build:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```

Run only the relevant instrumentation subset if full device suite is expensive.

---

# 22. Expected deliverables

Approximate PoC changes:

```text
app/src/main/java/com/zenithblue/nativecode/media/
  MediaCodecInventory.kt
  MediaCodecSelector.kt
  MediaCodecBenchmark.kt
  MediaBridgeProtocol.kt
  MediaBridgeServer.kt

app/src/test/java/com/zenithblue/nativecode/media/
  MediaBridgeProtocolTest.kt
  CodecSelectionTest.kt
  AllowedPathTest.kt

app/src/androidTest/java/com/zenithblue/nativecode/media/
  MediaCodecInventoryTest.kt
  MediaCodecDecodeBenchmarkTest.kt
  MediaBridgeServerTest.kt

tools/media_bridge/
  nativecode_media_client.py
  README.md

docs/perf/
  media-codec-bridge-<device>-<date>.md

docs/perf/results/
  media-codec-inventory-<device>-<date>.json
  media-codec-benchmark-<device>-<date>.json
```

Only add later when gates justify them:

```text
MediaBridgeService.kt
mediaProcessing FGS manifest entries
AHardwareBuffer JNI
Android Vulkan interop
custom FFmpeg integration
```

---

# 23. Result JSON

Minimum:

```json
{
  "schema": 1,
  "nativecode_commit": "",
  "device": {
    "manufacturer": "",
    "model": "",
    "sdk": 0,
    "soc_manufacturer": "",
    "soc_model": ""
  },
  "source": {
    "path": "",
    "sha256": "",
    "mime": "",
    "width": 0,
    "height": 0,
    "fps": 0.0,
    "frames": 0
  },
  "codec": {
    "name": "",
    "canonical_name": "",
    "hardware_accelerated": null,
    "software_only": null,
    "vendor": null
  },
  "mode": "",
  "runs": [],
  "median": {},
  "notes": []
}
```

Keep raw runs.

---

# 24. Final report

```markdown
# NativeCode MediaCodec Bridge PoC — Results

## Commit / device

## Zink production path
- renderer before
- patch if needed
- renderer after

## Codec inventory
| MIME | codec | HW | decoder/encoder | size/rate | profiles |

## Android-only decode
| source | codec | output mode | wall | CPU | fps | thermal | status |

## IPC
| test | result |
| peer UID |
| ping RTT |
| payload throughput |

## PRoot bridge
| source | codec | mode | frames | wall | CPU | status |

## Software comparison
| workload | FFmpeg PRoot | MediaCodec | ratio | CPU delta |

## Security/lifecycle
- peer auth
- malformed protocol
- traversal
- disconnect
- cancel
- cleanup

## Decision
STOP_AFTER_POC
or
PROCEED_TO_PACKET_STREAM
or
PROCEED_TO_ANDROID_ZERO_COPY
or
PROCEED_TO_FFMPEG_INTEGRATION
```

---

# 25. Acceptance gates

## ZINK_GATE

Normal NativeCode GUI must show:

```text
Zink -> Turnip -> Adreno
```

or no Zink patch is accepted.

## CODEC_INVENTORY_GATE

Confirmed hardware AVC decoder.

## ANDROID_DECODE_GATE

```text
5/5 complete
EOS
correct frame count
confirmed hardware decoder
stable resource cleanup
```

## IPC_GATE

```text
PRoot connects over AF_UNIX
peer UID validated
1000 ping operations reliable
```

## BRIDGE_FILE_GATE

```text
5/5 file-path H.264 decode
5/5 HEVC when available
cancel works
no leak/hang
```

## PACKET_STREAM_GATE

Compressed H.264 packet stream from PRoot reaches expected output frames + EOS.

## PERFORMANCE_GATE

Proceed beyond PoC only when MediaCodec materially improves:

```text
throughput
CPU
or thermal/power
```

Otherwise stop.

---

# 26. Research basis

Official Android codec inventory and performance:

```text
https://developer.android.com/reference/android/media/MediaCodecList
https://developer.android.com/media/optimize/performance/codec
https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities
```

API 29+ provides hardware/software/vendor classification and performance points.

Android AHardwareBuffer:

```text
https://developer.android.com/ndk/reference/group/a-hardware-buffer
```

Android documents AHardwareBuffer cross-process sharing and AF_UNIX send/receive APIs since API 26, plus EGL/Vulkan interop.

ImageReader/AImageReader:

```text
https://developer.android.com/ndk/reference/group/media
```

PRIVATE ImageReader output can expose AHardwareBuffer for hardware/GPU use without ordinary CPU-readable pixel access.

FFmpeg MediaCodec caveat:

```text
https://ffmpeg.org/ffmpeg-codecs.html
```

Current FFmpeg documentation notes NDK MediaCodec can operate without a JVM but may fail outside the JVM environment due to Android framework service dependencies, especially after Android 15.

AOSP app-domain V4L2 blocker:

```text
https://android.googlesource.com/platform/system/sepolicy/+/android16-qpr2-release/private/app.te
```

Normal app domains are blocked from read/write access to `video_device`, except DeviceAsWebcam.

Android AF_UNIX:

```text
https://developer.android.com/reference/android/net/LocalServerSocket
https://developer.android.com/reference/android/net/LocalSocket
https://developer.android.com/reference/android/net/LocalSocketAddress.Namespace
```

`LocalServerSocket(String)` uses Linux abstract namespace; `LocalSocket.getPeerCredentials()` exposes peer credentials.

Foreground media processing:

```text
https://developer.android.com/develop/background-work/services/fgs/service-types
https://developer.android.com/about/versions/15/behavior-changes-15
```

Android 15+ provides `mediaProcessing` FGS with a shared ~6-hour/24h background limit.

---

# 27. Exact execution order

```text
1. Read repo + supplied reports.
2. Baseline build/tests.
3. Test real X11 Zink renderer.
4. Patch active Zink env only if real GUI falls to llvmpipe.
5. Implement MediaCodec inventory.
6. Run inventory on the physical device.
7. Implement Android-only H.264 decode benchmark.
8. Compare same source against PRoot FFmpeg.
9. If beneficial, implement AF_UNIX ping/capabilities.
10. Prove same-UID peer credentials.
11. Add file-path hardware decode.
12. Run 5x H.264/HEVC bridge matrix.
13. Add compressed packet streaming only after file bridge passes.
14. Stop and report before AHardwareBuffer/FFmpeg integration unless explicitly asked to continue.
```

Do not turn this worker into a custom FFmpeg rewrite.

---

# 28. Starter prompt

> Work in `zenithblue-oss/nativecode-ai`. Read `AGENTS.md` and this NEXT WORKER file before editing. Previous tests already prove NEON, Turnip/KGSL, Vulkan filters, no Vulkan Video, VA-API/OpenCL dead ends, and Android permission denial for direct V4L2 VPU nodes. Do not repeat or bypass those results.
>
> First verify the actual NativeCode X11 Turnip renderer. `nativecode_proot_fast.sh` already sets `GALLIUM_DRIVER=zink`, but `start_gui.sh` and the generated `gpu-launch` Turnip branches currently appear to omit it. Patch only if the real GUI path falls to llvmpipe; preserve VirGL behavior.
>
> Then implement a gated Android MediaCodec PoC. Start with `MediaCodecList(ALL_CODECS)` inventory and an instrumentation benchmark that explicitly selects a hardware H.264/HEVC decoder and decodes the exact 1080p30 source used by the PRoot FFmpeg benchmark. Measure wall time, CPU, frame count, first-frame latency, and thermals. Guard API 29-only methods because minSdk is 26.
>
> Only if MediaCodec provides a material benefit, add an experimental `MediaBridgeServer` using Android AF_UNIX `LocalServerSocket`. PRoot must connect through the abstract namespace. Require `socket.peerCredentials.uid == Process.myUid()` before accepting commands. Use a bounded versioned length-prefixed protocol, no Java serialization, no shell execution, no TCP, and canonical-path allowlisting.
>
> Prove `ping` and `capabilities` first, then file-path hardware decode. After 5/5 reliable H.264/HEVC runs, add compressed packet streaming. Keep decoded raw frames Android-side.
>
> Do not implement direct `/dev/video*`, VA-API, Vulkan Video, OpenCL, libhybris, guest AHardwareBuffer interop, or a custom FFmpeg backend in this worker. Stop after packet-stream proof and produce a detailed performance report plus raw JSON evidence.

```
