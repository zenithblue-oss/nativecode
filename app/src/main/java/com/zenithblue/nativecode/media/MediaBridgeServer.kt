package com.zenithblue.nativecode.media

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class MediaBridgeServer(
    private val context: Context,
    private val allowedRoots: List<File> = MediaBridgeProtocol.defaultAllowedRoots(context)
) : AutoCloseable {

    companion object {
        private const val TAG = "MediaBridgeServer"
        const val PRIMARY_SOCKET_NAME = "nativecode.media.v1"
        const val SOCKET_NAME_PREFIX = "nativecode.media.v1."
    }

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: LocalServerSocket? = null
    private var workerThread: Thread? = null
    private val activeClients = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<LocalSocket, Boolean>())

    @Volatile
    var boundSocketName: String = PRIMARY_SOCKET_NAME
        private set

    val socketName: String get() = boundSocketName

    fun start() {
        if (isRunning.getAndSet(true)) return

        val thread = Thread({
            try {
                val server = try {
                    LocalServerSocket(PRIMARY_SOCKET_NAME).also { boundSocketName = PRIMARY_SOCKET_NAME }
                } catch (e: IOException) {
                    val fallback = "$SOCKET_NAME_PREFIX${Process.myUid()}"
                    LocalServerSocket(fallback).also { boundSocketName = fallback }
                }
                serverSocket = server
                Log.i(TAG, "MediaBridgeServer listening on abstract socket @$boundSocketName")
                writeRendezvousFile()

                while (isRunning.get()) {
                    val client: LocalSocket = try {
                        server.accept()
                    } catch (e: IOException) {
                        if (!isRunning.get()) break
                        Log.e(TAG, "accept error", e)
                        continue
                    }

                    val handlerThread = Thread({ handleClient(client) }, "MediaBridgeClientHandler")
                    handlerThread.isDaemon = true
                    handlerThread.start()
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Server error", e)
                }
            } finally {
                close()
            }
        }, "MediaBridgeServerThread")

        workerThread = thread
        thread.isDaemon = true
        thread.start()
    }

    private fun handleClient(client: LocalSocket) {
        activeClients.add(client)
        try {
            // Kernel peer credential validation: same UID only
            val creds = client.peerCredentials
            if (creds == null || creds.uid != Process.myUid()) {
                Log.w(TAG, "Rejected client with unauthorized UID: ${creds?.uid}, expected ${Process.myUid()}")
                return
            }

            client.soTimeout = 10000 // 10s timeout

            val input = client.inputStream
            val output = client.outputStream

            while (isRunning.get()) {
                val request: JSONObject = try {
                    MediaBridgeProtocol.readControlFrame(input)
                } catch (e: Exception) {
                    break // Client closed or timeout
                }

                val cmd = request.optString("cmd", "")
                val response = JSONObject().apply {
                    put("v", MediaBridgeProtocol.PROTOCOL_VERSION)
                }

                when (cmd) {
                    "ping" -> {
                        response.put("ok", true)
                        response.put("ts", System.currentTimeMillis())
                    }
                    "capabilities" -> {
                        val inv = MediaCodecInventory.collectInventory()
                        response.put("ok", true)
                        response.put("capabilities", inv.toJsonObject())
                    }
                    "benchmark_file" -> {
                        val path = request.optString("path", "")
                        val modeStr = request.optString("mode", "buffer")
                        val mode = if (modeStr.equals("surface", ignoreCase = true)) {
                            MediaCodecDecodeBenchmark.OutputMode.SURFACE_IMAGE_READER
                        } else {
                            MediaCodecDecodeBenchmark.OutputMode.BUFFER_DISCARD
                        }

                        val targetFile = MediaBridgeProtocol.resolveGuestPath(context, path, mustExist = true)
                        if (targetFile == null || !targetFile.exists() || !targetFile.isFile) {
                            response.put("ok", false)
                            response.put("error", "Input video file not found: $path")
                        } else if (!MediaBridgeProtocol.isPathAllowed(targetFile, allowedRoots)) {
                            response.put("ok", false)
                            response.put("error", "Path traversal or unauthorized path rejected: $path")
                        } else {
                            val bench = MediaCodecDecodeBenchmark.runDecode(targetFile, mode)
                            response.put("ok", bench.error == null)
                            if (bench.error != null) {
                                response.put("error", bench.error)
                            }
                            response.put("codec", bench.codecName)
                            response.put("is_hardware", bench.isHardware ?: JSONObject.NULL)
                            response.put("mime", bench.mime)
                            response.put("width", bench.width)
                            response.put("height", bench.height)
                            response.put("input_samples", bench.inputSamples)
                            response.put("decoded_frames", bench.decodedFrames)
                            response.put("wall_ms", bench.wallTimeMs)
                            response.put("first_frame_ms", bench.firstFrameLatencyMs)
                            response.put("fps", bench.fps)
                            response.put("realtime_factor", bench.realtimeFactor)
                        }
                    }
                    "benchmark_encode" -> {
                        val width = request.optInt("width", 1280)
                        val height = request.optInt("height", 720)
                        val frames = request.optInt("frames", 150)
                        val fps = request.optInt("fps", 30)
                        val bitrate = request.optInt("bitrate", 4_000_000)
                        val mime = request.optString("mime", "video/avc")
                        val outputPath = request.optString("output_path", "")

                        val outputFile = if (outputPath.isNotEmpty()) {
                            val resolved = MediaBridgeProtocol.resolveGuestPath(context, outputPath, mustExist = false)
                            if (resolved == null || !MediaBridgeProtocol.isPathAllowed(resolved, allowedRoots)) {
                                response.put("ok", false)
                                response.put("error", "Path traversal or unauthorized path rejected: $outputPath")
                                MediaBridgeProtocol.writeControlFrame(output, response)
                                continue
                            }
                            resolved
                        } else {
                            null
                        }

                        val bench = MediaCodecEncodeBenchmark.runEncodeBenchmark(
                            outputFile = outputFile,
                            width = width,
                            height = height,
                            frameCount = frames,
                            fps = fps,
                            bitrate = bitrate,
                            mime = mime
                        )

                        response.put("ok", bench.error == null)
                        if (bench.error != null) {
                            response.put("error", bench.error)
                        }
                        response.put("codec", bench.codecName)
                        response.put("is_hardware", bench.isHardware ?: JSONObject.NULL)
                        response.put("mime", bench.mime)
                        response.put("width", bench.width)
                        response.put("height", bench.height)
                        response.put("input_frames", bench.inputFrames)
                        response.put("encoded_frames", bench.encodedFrames)
                        response.put("wall_ms", bench.wallTimeMs)
                        response.put("first_frame_ms", bench.firstFrameLatencyMs)
                        response.put("fps", bench.fps)
                        response.put("realtime_factor", bench.realtimeFactor)
                        response.put("output_size_bytes", bench.outputSizeBytes)
                    }
                    "encode_stream" -> {
                        val width = request.optInt("width", 1280)
                        val height = request.optInt("height", 720)
                        val fps = request.optInt("fps", 30)
                        val bitrate = request.optInt("bitrate", 4_000_000)
                        val mime = request.optString("mime", "video/avc")
                        val outputPath = request.optString("output_path", "")

                        val outputFile = if (outputPath.isNotEmpty()) {
                            MediaBridgeProtocol.resolveGuestPath(context, outputPath, mustExist = false)
                        } else {
                            null
                        }

                        if (outputFile == null || !MediaBridgeProtocol.isPathAllowed(outputFile, allowedRoots)) {
                            response.put("ok", false)
                            response.put("error", "Invalid or unauthorized output_path: $outputPath")
                            MediaBridgeProtocol.writeControlFrame(output, response)
                            continue
                        }

                        // Send ready acknowledgment so client begins streaming raw YUV bytes
                        val readyResp = JSONObject().apply {
                            put("v", MediaBridgeProtocol.PROTOCOL_VERSION)
                            put("ok", true)
                            put("ready", true)
                        }
                        MediaBridgeProtocol.writeControlFrame(output, readyResp)

                        // Stream raw YUV from socket into MediaCodec encoder
                        val bench = MediaCodecEncodeBenchmark.runEncodeStream(
                            rawInputStream = input,
                            outputFile = outputFile,
                            width = width,
                            height = height,
                            fps = fps,
                            bitrate = bitrate,
                            mime = mime
                        )

                        response.put("ok", bench.error == null)
                        if (bench.error != null) {
                            response.put("error", bench.error)
                        }
                        response.put("codec", bench.codecName)
                        response.put("is_hardware", bench.isHardware ?: JSONObject.NULL)
                        response.put("mime", bench.mime)
                        response.put("width", bench.width)
                        response.put("height", bench.height)
                        response.put("input_frames", bench.inputFrames)
                        response.put("encoded_frames", bench.encodedFrames)
                        response.put("wall_ms", bench.wallTimeMs)
                        response.put("first_frame_ms", bench.firstFrameLatencyMs)
                        response.put("fps", bench.fps)
                        response.put("realtime_factor", bench.realtimeFactor)
                        response.put("output_size_bytes", bench.outputSizeBytes)
                    }
                    "cancel" -> {
                        response.put("ok", true)
                        response.put("cancelled", true)
                    }
                    else -> {
                        response.put("ok", false)
                        response.put("error", "Unknown command: $cmd")
                    }
                }

                MediaBridgeProtocol.writeControlFrame(output, response)
                java.lang.ref.Reference.reachabilityFence(client)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client session finished: ${e.message}")
        } finally {
            activeClients.remove(client)
            try {
                client.close()
            } catch (ignored: Throwable) {}
        }
    }

    private fun writeRendezvousFile() {
        try {
            val runDir = File(context.filesDir, "run").apply { mkdirs() }
            val rendezvousFile = File(runDir, "nativecode_bridge.json")
            val json = JSONObject().apply {
                put("v", 1)
                put("host_uid", Process.myUid())
                put("media_socket", "@$socketName")
                put("agent_socket", "@nativecode.agent.v1.${Process.myUid()}")
                put("timestamp", System.currentTimeMillis())
            }
            rendezvousFile.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write rendezvous file: ${e.message}")
        }
    }

    override fun close() {
        if (!isRunning.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (ignored: Throwable) {}
        serverSocket = null
        for (client in activeClients) {
            try { client.close() } catch (ignored: Throwable) {}
        }
        activeClients.clear()
        workerThread?.interrupt()
        workerThread = null
    }
}
