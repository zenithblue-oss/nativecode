package com.zenithblue.nativecode.cliauth

import android.content.Context
import android.os.Build
import com.zenithblue.nativecode.BuildConfig
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/** Sends privacy-minimized AI safety reports without leaving NativeCode. */
object InAppReportService {
    enum class Result { DELIVERED, QUEUED, REJECTED }

    private const val DESCRIPTION_MAX = 3500
    private const val QUEUE_MAX = 10
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val QUEUE_DIR = "nativecode_reports"
    private const val QUEUE_FILE = "report-queue.json"
    private val executor = Executors.newSingleThreadExecutor()
    private val queueLock = Any()

    fun submit(
        context: Context,
        toolId: String?,
        category: String,
        description: String,
        callback: (Result) -> Unit
    ) {
        if (description.length > DESCRIPTION_MAX) {
            callback(Result.REJECTED)
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            val report = buildReport(appContext, toolId, category, description)
            callback(if (post(report)) Result.DELIVERED else {
                enqueue(appContext, report)
                Result.QUEUED
            })
        }
    }

    /** Best-effort retry. Reports stay queued unless the server confirms 2xx. */
    fun retryQueued(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            synchronized(queueLock) {
                val reports = readQueue(appContext)
                if (reports.length() == 0) return@synchronized
                val remaining = JSONArray()
                for (i in 0 until reports.length()) {
                    val queued = reports.optJSONObject(i) ?: continue
                    val payload = queued.optJSONObject("payload") ?: continue
                    if (!post(payload)) {
                        queued.put("attempts", queued.optInt("attempts", 0) + 1)
                        remaining.put(queued)
                    }
                }
                writeQueue(appContext, remaining)
            }
        }
    }

    internal fun buildReport(
        context: Context,
        toolId: String?,
        category: String,
        description: String
    ): JSONObject {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionName = packageInfo?.versionName ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION")
            (packageInfo?.versionCode ?: 0).toLong()
        }
        return JSONObject()
            .put("appVersion", versionName)
            .put("appVersionCode", versionCode)
            .put("tool", toolId ?: "general")
            .put("category", category.take(80))
            .put("description", description.take(DESCRIPTION_MAX))
            .put("isolation", LinuxCommandBuilder.currentMethod)
            .put("diagnosticsOptIn", JSONObject().put("includeVersion", true))
    }

    private fun post(payload: JSONObject): Boolean {
        val endpoint = runCatching { URL(BuildConfig.REPORT_ENDPOINT) }.getOrNull()
            ?: return false
        if (endpoint.protocol != "https") return false
        var connection: HttpURLConnection? = null
        return try {
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun enqueue(context: Context, payload: JSONObject) {
        synchronized(queueLock) {
            val queue = readQueue(context)
            val trimmed = JSONArray()
            val first = maxOf(0, queue.length() - (QUEUE_MAX - 1))
            for (i in first until queue.length()) trimmed.put(queue.optJSONObject(i))
            trimmed.put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("createdAt", System.currentTimeMillis())
                    .put("attempts", 0)
                    .put("payload", payload)
            )
            writeQueue(context, trimmed)
        }
    }

    private fun readQueue(context: Context): JSONArray {
        val file = queueFile(context)
        if (!file.isFile) return JSONArray()
        return runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrElse { JSONArray() }
    }

    private fun writeQueue(context: Context, queue: JSONArray) {
        val file = queueFile(context)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "$QUEUE_FILE.tmp")
        temp.writeText(queue.toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(queue.toString(), Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun queueFile(context: Context): File = File(File(context.filesDir, QUEUE_DIR), QUEUE_FILE)
}
