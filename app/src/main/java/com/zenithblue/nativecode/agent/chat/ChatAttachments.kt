package com.zenithblue.nativecode.agent.chat

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Composer attachments (plan 9.2.4): copy a pick into the thread's cwd inside the PRoot rootfs (the chat's
 * isolation) as `attach_<ts>_<safe-name>`. Logic copied from `MainActivity.handleAttachment`. Limits from
 * t3code `chatAttachment.ts`.
 */
object ChatAttachments {
    const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
    const val MAX_FILE_BYTES = 50L * 1024 * 1024
    const val MAX_PER_MESSAGE = 100
    const val MAX_PROMPT_CHARS = 120_000
    const val HOME = "/home/flux"
    val IMAGE_MIMES = setOf("image/gif", "image/jpeg", "image/png", "image/webp")

    private val io = Executors.newSingleThreadExecutor { Thread(it, "chat-attach").apply { isDaemon = true } }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun isInlineImage(mime: String?) = mime?.lowercase(Locale.US) in IMAGE_MIMES
    fun limitFor(mime: String?) = if (isInlineImage(mime)) MAX_IMAGE_BYTES else MAX_FILE_BYTES

    /** Null = ok, else the refusal text for the composer. */
    fun checkMessage(text: String, attachmentCount: Int): String? = when {
        text.length > MAX_PROMPT_CHARS -> "Message too long (max ${MAX_PROMPT_CHARS / 1000}k characters)"
        attachmentCount > MAX_PER_MESSAGE -> "Too many attachments (max $MAX_PER_MESSAGE)"
        else -> null
    }

    fun tooLarge(mime: String?) = "File too large (max ${limitFor(mime) / (1024 * 1024)} MB${if (isInlineImage(mime)) " for images" else ""})"

    fun safeName(raw: String?, mime: String?, imageOnly: Boolean, ts: Long): String {
        val safe = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').take(96)
        val fallback = if (imageOnly) "image.${imageExt(mime)}" else "file"
        return "attach_${ts}_${safe.ifBlank { fallback }}"
    }

    fun imageExt(mime: String?): String {
        val m = mime?.lowercase(Locale.US) ?: return "jpg"
        return when {
            m.contains("png") -> "png"
            m.contains("webp") -> "webp"
            m.contains("gif") -> "gif"
            m.contains("jpeg") || m.contains("jpg") -> "jpg"
            m.startsWith("image/") -> m.substringAfterLast('/').substringBefore(';').ifBlank { "jpg" }
            else -> "jpg"
        }
    }

    fun guestPath(cwd: String?, name: String) = "${cwd?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: HOME}/$name"

    /** Host file behind a PRoot guest path (shared storage paths are bound as-is). */
    fun hostFile(ctx: Context, guestPath: String): File =
        if (guestPath.startsWith("/storage") || guestPath.startsWith("/sdcard")) File(guestPath)
        else ProjectPathResolver.resolve(ctx, guestPath, "proot")

    /** Blocking copy (IO thread). Over-limit picks are refused before anything lands in the guest. */
    fun copy(ctx: Context, uri: Uri, cwd: String?, imageOnly: Boolean): Result<ChatAttachment> = runCatching {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        if (imageOnly && !mime.startsWith("image/")) error("Not an image")
        val display = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
        }.getOrNull()
        val name = safeName(display, mime, imageOnly, System.currentTimeMillis())
        val guest = guestPath(cwd, name)
        val target = hostFile(ctx, guest)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val limit = limitFor(mime)
        var size = 0L
        try {
            (cr.openInputStream(uri) ?: error("Failed to read selection")).use { inp ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        size += n
                        if (size > limit) error(tooLarge(mime))
                        out.write(buf, 0, n)
                    }
                }
            }
            if (size <= 0L) error("Selected file is empty")
            if (!part.renameTo(target)) error("Could not write into Linux")
        } finally {
            part.delete()
        }
        ChatAttachment(display?.substringAfterLast('/')?.ifBlank { null } ?: name, mime, size, guest, target.absolutePath)
    }

    /** [copy] on a background thread; [cb] on the main thread with the attachment or an error message. */
    fun copyAsync(ctx: Context, uri: Uri, cwd: String?, imageOnly: Boolean, cb: (ChatAttachment?, String?) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val r = copy(app, uri, cwd, imageOnly)
            main.post { cb(r.getOrNull(), r.exceptionOrNull()?.let { it.message ?: "Copy failed" }) }
        }
    }
}
