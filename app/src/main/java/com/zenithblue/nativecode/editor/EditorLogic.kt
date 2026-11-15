package com.zenithblue.nativecode.editor

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** File extension / name -> TextMate scope, matching assets/textmate/languages.json. */
object EditorLanguages {
    private val byExt = mapOf(
        "kt" to "source.kotlin", "kts" to "source.kotlin",
        "java" to "source.java",
        "js" to "source.js", "jsx" to "source.js", "mjs" to "source.js", "cjs" to "source.js",
        "ts" to "source.ts", "tsx" to "source.ts", "mts" to "source.ts", "cts" to "source.ts",
        "py" to "source.python", "pyw" to "source.python",
        "json" to "source.json", "jsonc" to "source.json", "json5" to "source.json",
        "md" to "text.html.markdown", "markdown" to "text.html.markdown",
        "sh" to "source.shell", "bash" to "source.shell", "zsh" to "source.shell",
        "html" to "text.html.basic", "htm" to "text.html.basic",
        "xml" to "text.xml", "svg" to "text.xml", "xsd" to "text.xml", "plist" to "text.xml",
        "css" to "source.css",
        "c" to "source.c", "h" to "source.c",
        "cpp" to "source.cpp", "cc" to "source.cpp", "cxx" to "source.cpp", "hpp" to "source.cpp", "hh" to "source.cpp",
        "go" to "source.go",
        "rs" to "source.rust",
        "yaml" to "source.yaml.1.2", "yml" to "source.yaml.1.2",
        // ponytail: no dedicated TOML grammar bundled; the ini grammar colours tables, keys, strings and comments.
        "toml" to "source.ini", "ini" to "source.ini", "properties" to "source.ini", "conf" to "source.ini", "cfg" to "source.ini",
        "dockerfile" to "source.dockerfile",
    )
    private val byName = mapOf(
        "dockerfile" to "source.dockerfile",
        ".bashrc" to "source.shell", ".zshrc" to "source.shell", ".profile" to "source.shell", ".bash_profile" to "source.shell",
    )

    /** TextMate scope for [fileName], or null for plain text. */
    fun scopeFor(fileName: String): String? {
        val lower = fileName.lowercase()
        byName[lower]?.let { return it }
        if (lower.startsWith("dockerfile.")) return "source.dockerfile"
        val ext = lower.substringAfterLast('.', "")
        return if (ext.isEmpty() || lower.endsWith(".")) null else byExt[ext]
    }
}

enum class EditMode { EDIT, READ_ONLY_LARGE, READ_ONLY_BINARY, READ_ONLY_NO_WRITE }

/** Size / content rules for the built-in editor. */
object EditorLimits {
    /** Editable up to here (plan 16). */
    const val EDIT_MAX_BYTES = 2L * 1024 * 1024
    /** Above EDIT_MAX_BYTES and up to here the editor opens read-only; beyond it the old truncating viewer is used. */
    const val VIEW_MAX_BYTES = 8L * 1024 * 1024

    /** A NUL byte in the first 8 KB marks binary content. */
    fun looksBinary(head: ByteArray, len: Int = head.size): Boolean {
        for (i in 0 until minOf(len, 8192)) if (head[i] == 0.toByte()) return true
        return false
    }

    /** Strict UTF-8 decode, or null when the bytes are not valid UTF-8 (saving would corrupt them). */
    fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    fun modeFor(length: Long, head: ByteArray, canWrite: Boolean): EditMode = when {
        looksBinary(head) -> EditMode.READ_ONLY_BINARY
        length > EDIT_MAX_BYTES -> EditMode.READ_ONLY_LARGE
        !canWrite -> EditMode.READ_ONLY_NO_WRITE
        else -> EditMode.EDIT
    }
}

/** What the file looked like when opened, to detect an agent or another process changing it. */
data class DiskStamp(val modified: Long, val length: Long) {
    companion object {
        fun of(f: File) = DiskStamp(f.lastModified(), f.length())
    }
}

/** Atomic save: temp file in the same directory, fsync, copy mode, rename over the target. */
object AtomicSave {
    fun write(target: File, bytes: ByteArray) {
        val real = if (target.exists()) target.canonicalFile else target.absoluteFile
        val dir = real.parentFile ?: throw java.io.IOException("no parent dir for $real")
        val tmp = File.createTempFile(".${real.name}.", ".tmp", dir)
        try {
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            if (real.exists()) {
                try {
                    Files.setPosixFilePermissions(tmp.toPath(), Files.getPosixFilePermissions(real.toPath()))
                } catch (_: Exception) { /* non-POSIX fs: keep the temp file's default mode */ }
            }
            try {
                Files.move(tmp.toPath(), real.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), real.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }
}
