# Plan Review: mediacodec-vpu-and-agent-bridge — PASS 1 ITER 1

## Verdict: REVISE
## Counts: CRITICAL 2 MAJOR 4 MINOR 2 SUGGESTIONS 2

---

## Technical Evaluation Across Critical Dimensions

### 1. Universal SoC Hardware Acceleration (MediaTek vs Snapdragon)
- **Strengths:** Dynamic codec discovery via `MediaCodecList(MediaCodecList.ALL_CODECS)` avoids hardcoded vendor lists. Recognizes both Codec2 (`c2.*`) and OpenMAX (`OMX.*`) HAL backends for Qualcomm Snapdragon (`c2.qti.*`, `OMX.qcom.*`) and MediaTek Dimensity/Helio (`c2.mtk.*`, `OMX.MTK.*`).
- **Gaps:** 
  - `MediaCodecSelector.kt` does not filter out `.secure` DRM decoders (e.g. `c2.qti.avc.decoder.secure` or `OMX.MTK.VIDEO.DECODER.AVC.secure`). On devices where secure codecs precede standard codecs in `ALL_CODECS`, `firstOrNull` selects a secure decoder requiring DRM crypto buffers, crashing unencrypted decode.
  - ImageReader `PRIVATE` output draining is underspecified: ImageReader requires an explicit `HandlerThread` listener calling `acquireLatestImage()?.close()` to prevent blocking the codec output queue.

### 2. MediaCodec VPU Bridge Data Path & Resource Bounds
- **Strengths:** Strictly enforces compressed-media transport (NAL units only, Annex-B / AVCC), preventing 373+ MB/s memory bus saturation. Adopts the canonical 24-byte `NCM1` binary packet header with length limits (<= 16 MiB packet).
- **Gaps:**
  - The proposed 64 MiB queue backpressure in Java heap creates an out-of-memory risk on 2-3 GB RAM Android devices with tight `dalvik.vm.heapgrowthlimit` (192m-256m).
  - Socket reader thread must exert socket-level backpressure (pausing socket `read()`) rather than buffering unbounded packet lists in heap.

### 3. Agent Host IPC Bridge
- **Strengths:** Dedicated background server on AF_UNIX abstract namespace with kernel peer authentication (`LocalSocket.getPeerCredentials().uid == Process.myUid()`). Supports `ping`, `notify`, `status`, `toast`, and `telemetry`.
- **Gaps:**
  - Socket discovery is blocked by PRoot's `--change-id` virtualization: guest `id -u` returns `0` or `1000`, while the host binds with real Android UID (e.g. `10123`). The sanitized `guestEnv` does not pass the Android UID, and startup scripts cannot be modified under the non-regression mandate. An external rendezvous file is required.
  - Calling `Toast.makeText` from the background socket worker thread throws `RuntimeException` unless dispatched to `Looper.getMainLooper()`.
  - Android 13+ `POST_NOTIFICATIONS` permission check lacks structured error reporting to guest CLI.

### 4. Non-Regression & Subsystem Isolation
- **Strengths:** Strict package isolation in `com.zenithblue.nativecode.media` and `com.zenithblue.nativecode.agent`. Existing `TerminalService`, `BackgroundService`, `ProotFastCommandBuilder`, and launch scripts remain untouched.
- **Strengths:** Faithfully preserves the "test-before-patch" rule for Zink (`glxinfo -B` in active X11 before touching scripts).

### 5. Acceptance Gates, Benchmarking & Lifecycle
- **Strengths:** Correctly inherits gates from the base specification: `ZINK_GATE`, `CODEC_INVENTORY_GATE`, `ANDROID_DECODE_GATE`, `IPC_GATE`, `BRIDGE_FILE_GATE`, `PACKET_STREAM_GATE`, `PERFORMANCE_GATE`, and adds `AGENT_BRIDGE_GATE`.
- **Gaps:** Phase 9 proposes a separate `MediaBridgeService` with `foregroundServiceType="mediaProcessing"`, but `android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING` is not declared in `AndroidManifest.xml`, causing a `SecurityException` on Android 14+ (targetSdk 37).

### 6. Edge Cases, Security & Resource Traps
- **Strengths:** Enforces canonical path validation against sandbox escapes; requires single-session locking and bounded frame headers.
- **Gaps:** Missing deterministic `try/finally` codec release on abrupt socket disconnect, creating hardware VPU instance leak risks (4-16 instance cap on mobile SoCs).

---

## Detailed Findings

### [CRITICAL] DRM Secure Codec Selection Trap in MediaCodecSelector
- Location: `docs/plans/mediacodec-vpu-and-agent-bridge-plan.md:124-152` (`MediaCodecSelector.kt`)
- Problem: `selectHardwareDecoder()` selects the first candidate satisfying `isHardwareAccelerated && !isSoftwareOnly && isVendor` without excluding DRM secure decoders (`.secure`).
- Evidence: On Qualcomm (`c2.qti.avc.decoder.secure`, `OMX.qcom.video.decoder.avc.secure`) and MediaTek (`c2.mtk.avc.decoder.secure`), OEM codec tables register secure decoders alongside normal decoders. These return `isHardwareAccelerated = true`, `isSoftwareOnly = false`, and `isVendor = true`. If a secure codec appears first in `ALL_CODECS`, `MediaCodec.createByCodecName(info.name)` and `configure()` with non-secure ByteBuffers/ImageReader fails with native `MediaCodec.CodecException` (`ERROR_INSUFFICIENT_RESOURCE` or `CryptoException`).
- Impact: Decode benchmarks and bridges crash immediately on devices where OEM exposes secure codecs ahead of non-secure decoders.
- Required planner change: In `MediaCodecSelector.kt`, explicitly filter out secure codecs:
  ```kotlin
  val nonSecureCandidates = candidates.filter { info ->
      !info.name.endsWith(".secure", ignoreCase = true) &&
      !info.name.contains(".secure.", ignoreCase = true)
  }
  ```
  Apply this filter across both API 29+ and API 26-28 selection paths.

### [CRITICAL] Socket Name Discrepancy & PRoot Virtualized UID Discovery Gap
- Location: `docs/plans/mediacodec-vpu-and-agent-bridge-plan.md:76-78, 93-95, 187, 219` + `tools/agent_bridge/nativecode-agent`
- Problem:
  1. Java `LocalServerSocket` abstract namespace: Android's `LocalServerSocket(name)` / `LocalSocketAddress(name, Namespace.ABSTRACT)` automatically handles the leading null byte. Passing `"\0nativecode..."` causes Android JNI to prepend a second null byte (`\0\0nativecode...`), breaking Linux client connection.
  2. UID Virtualization: In PRoot (`ProotFastCommandBuilder.kt:79`), PRoot runs with `--change-id=0:0` (root) or `1000:1000` (flux). Inside PRoot, `id -u` returns `0` or `1000`, not the Android host app UID (e.g. `10123`). The host server binds to `\0nativecode.agent.v1.<hostUid>`. The plan assumes the guest CLI knows `<uid>`, but the sanitized environment (`ProotFastCommandBuilder.kt:108-124`) does not provide it, and startup scripts cannot be touched.
- Evidence: `ProotFastCommandBuilder.kt:79` (`--change-id=${if (guestUser == "root") "0:0" else "1000:1000"}`); Android SDK `LocalSocketImpl.java`.
- Impact: Guest CLI `nativecode-agent` cannot discover the socket address and fails with `ECONNREFUSED` / `ENOENT`.
- Required planner change:
  1. Specify Java constructor name without null prefix: `LocalServerSocket("nativecode.agent.v1." + Process.myUid())`.
  2. Add a rendezvous file mechanism: On bridge startup, the Android server writes an ephemeral descriptor to `File(ProjectPathResolver.prootRootfsDir(ctx), "run/nativecode_bridge.json")` (mapped to guest `/run/nativecode_bridge.json`):
     ```json
     {
       "host_uid": 10123,
       "media_socket": "@nativecode.media.v1.10123",
       "agent_socket": "@nativecode.agent.v1.10123"
     }
     ```
     `nativecode-agent` reads `/run/nativecode_bridge.json` to resolve the active socket.

### [MAJOR] JVM Heap OOM Risk in 64 MiB Queue Backpressure
- Location: `docs/plans/mediacodec-vpu-and-agent-bridge-plan.md:90, 214`
- Problem: The plan specifies a 64 MiB queue backpressure limit in JVM heap. On devices with 2-3 GB RAM where `dalvik.vm.heapgrowthlimit` is 192 MB or 256 MB, buffering up to 64 MiB of `ByteArray` objects alongside active Compose UI and X11 memory mapping risks triggering `OutOfMemoryError`.
- Evidence: `app/build.gradle.kts:33` minSdk 26; Dalvik memory limits on budget Android devices.
- Impact: App crash with `OutOfMemoryError` during sustained high-bitrate streaming.
- Required planner change:
  1. Reduce queue threshold to 8 MiB - 16 MiB (ample for 1080p frame buffering).
  2. Stream incoming packet bytes directly into `codec.getInputBuffer(index)` whenever an input buffer is dequeued.
  3. When the queue reaches the high-water mark, suspend the socket read loop (`InputStream.read` paused/blocked) to exert socket backpressure on the guest client.

### [MAJOR] UI Threading Violation for Transient Toasts & Unhandled POST_NOTIFICATIONS
- Location: `docs/plans/mediacodec-vpu-and-agent-bridge-plan.md:218-228` (`AgentBridgeServer.kt`)
- Problem:
  1. `Toast.makeText` must run on a Looper thread. Running it on the background socket thread throws `java.lang.RuntimeException: Can't toast on a thread that has not called Looper.prepare()`.
  2. On Android 13+ (API 33+, targetSdk 37), `POST_NOTIFICATIONS` is a runtime permission. If not granted, `notify()` fails silently without diagnostic feedback to the guest agent.
- Evidence: Android SDK `Toast.java`; `AndroidManifest.xml:9` (`POST_NOTIFICATIONS`); `targetSdk = 37`.
- Impact: Server thread crash on `toast` command; silent notification failure on guest `notify`.
- Required planner change:
  1. Dispatch Toasts via `Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }`.
  2. In `notify`, check `ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED`. If false, return `{"v":1,"ok":false,"error":"POST_NOTIFICATIONS permission not granted"}`.

### [MAJOR] ImageReader Surface Draining Handler & Pipeline Underspecified
- Location: `docs/plans/mediacodec-vpu-and-agent-bridge-plan.md:167-175` (Phase 3: Mode B Surface output)
- Problem: Mode B benchmarks `ImageReader` with `ImageFormat.PRIVATE`, but omits specifying the background `HandlerThread` for image acquisition and buffer release. Without prompt `acquireLatestImage()?.close()`, the `ImageReader` queue fills (capacity 2-3 images) and `MediaCodec.releaseOutputBuffer(index, true)` stalls permanently.
- Evidence: Android `ImageReader.java` documentation; hardware Surface buffer release contract.
- Impact: Mode B benchmark deadlocks after 2-3 frames.
- Required planner change: Detail the Mode B draining pipeline:
  ```kotlin
  val drainThread = HandlerThread("ImageReaderDrainThread").apply { start() }
  val drainHandler = Handler(drainThread.looper)
  val imageReader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 3)
  imageReader.setOnImageAvailableListener({ reader ->
      reader.acquireLatestImage()?.close()
  }, drainHandler)
  ```
  Ensure `drainThread.quitSafely()` and `imageReader.close()` are called in `finally`.

### [MAJOR] Missing FOREGROUND_SERVICE_MEDIA_PROCESSING Manifest Permission for Phase 9
- Location: `docs/plans/mediacodec-vpu-and-agent-bridge-plan.md:238-242` (`MediaBridgeService.kt`)
- Problem: Phase 9 proposes `MediaBridgeService.kt` with `foregroundServiceType="mediaProcessing"` on Android 14+ (targetSdk 37). This requires `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" />`, which is absent from `AndroidManifest.xml`. Starting an FGS with `mediaProcessing` without this permission throws `SecurityException`.
- Evidence: `app/src/main/AndroidManifest.xml:3-9` lacks permission; Android 14+ FGS security mandate.
- Impact: Runtime crash when launching `MediaBridgeService` in background transcode scenarios.
- Required planner change: Clarify that the initial PoC runs as a background daemon thread tied to the active app/session lifecycle without an independent FGS. Defer adding `mediaProcessing` FGS and its manifest permission until post-PoC, matching Section 12 of the base specification ("Do not add Play-facing service declarations before the PoC proves value").

### [MINOR] Missing Deterministic Codec Cleanup on Abrupt Disconnect
- Location: `docs/plans/mediacodec-vpu-and-agent-bridge-plan.md:195, 210`
- Problem: Android mobile SoCs have strict hardware instance limits (4 to 16 concurrent codecs across the OS). If a PRoot client terminates abruptly (e.g. SIGINT or crash), unhandled socket exceptions will leak the native `MediaCodec` instance, quickly exhausting the device VPU.
- Evidence: Android `MediaCodec` HAL hardware resource exhaustion behavior.
- Impact: Subsequent codec creation fails with `ERROR_INSUFFICIENT_RESOURCE` until device reboot.
- Required planner change: Mandate strict `try/finally` blocks in session worker:
  `finally { codec?.stop(); codec?.release(); imageReader?.close(); socket.close(); activeSession = null }`.

### [MINOR] Socket Read Timeout Missing
- Location: `docs/plans/mediacodec-vpu-and-agent-bridge-plan.md:76-90, 185-195`
- Problem: If a client connects and hangs without transmitting frames or control data, the worker thread remains blocked indefinitely in `read()`.
- Evidence: Android `LocalSocket.setSoTimeout()`.
- Impact: Hangs the single active session slot and blocks subsequent reconnects.
- Required planner change: Specify `socket.setSoTimeout(10000)` (10s) on all accepted sockets.

### [SUGGESTION] Rate Limiting on Agent Bridge IPC
- Location: `docs/plans/mediacodec-vpu-and-agent-bridge-plan.md:95-105`
- Detail: Implement a token bucket (max 5 notifications/sec, max 1 toast per 2s) in `AgentBridgeServer.kt` to prevent rogue scripts from freezing the UI.

### [SUGGESTION] Out-of-Band CSD (Codec Specific Data) Handling
- Location: `docs/plans/mediacodec-vpu-and-agent-bridge-plan.md:90-95, 210-215`
- Detail: Explicitly specify how out-of-band SPS/PPS (AVCC/HVCC) vs Annex-B start codes are fed to `MediaCodec` (either via `format.setByteBuffer("csd-0", ...)` in `INIT` or via packets marked with flag `0x0002`).

---

## Next Agent: Planner
## Next Action: Address the 2 CRITICAL and 4 MAJOR findings in `docs/plans/mediacodec-vpu-and-agent-bridge-plan.md` to achieve APPROVE status.
