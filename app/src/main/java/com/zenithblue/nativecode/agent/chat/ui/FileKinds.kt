package com.zenithblue.nativecode.agent.chat.ui

/** What a viewer does with a file. The one type table: inline chat cards and the file-viewer dispatcher both read it. */
enum class FileType {
    /** png jpg webp gif bmp ico heic heif avif: decoded by Android (HEIC API 28+, AVIF API 31+). */
    IMAGE,
    /** Rendered in a script-free WebView, never decoded. */
    SVG,
    VIDEO,
    AUDIO,
    PDF,
    MARKDOWN,
    /** Rendered in a sandboxed WebView (JavaScript off, no network, no file access). */
    HTML,
    /** Code, config, logs, csv, json: the Directory editor. */
    TEXT,
    /** docx xlsx pptx odt ...: no native renderer, opened externally through the FileProvider. */
    OFFICE,
    APK,
    OTHER,
}

object FileKinds {
    private val IMAGE = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "heic", "heif", "avif")
    private val VIDEO = setOf("mp4", "webm", "mkv", "3gp", "avi", "mov", "m4v")
    private val AUDIO = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr")
    private val OFFICE = mapOf(
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "doc" to "application/msword",
        "xls" to "application/vnd.ms-excel",
        "ppt" to "application/vnd.ms-powerpoint",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
    )
    private val TEXT = setOf(
        "txt", "json", "xml", "kt", "java", "py", "rs", "c", "cpp", "h", "hpp", "sh",
        "yml", "yaml", "gradle", "kts", "properties", "conf", "ini", "js", "ts", "css", "log",
        "env", "toml", "lock", "bat", "cmd", "diff", "patch", "sql", "go", "rb", "php", "swift",
        "m", "mm", "cs", "lua", "zig", "asm", "dockerfile", "gitignore", "cmake", "csv", "tsv",
    )
    private val TEXT_NAMES = setOf("dockerfile", "makefile", "license")

    fun kindOf(name: String): FileType {
        val lower = name.lowercase()
        val ext = lower.substringAfterLast('.', "")
        return when {
            ext in IMAGE -> FileType.IMAGE
            ext == "svg" -> FileType.SVG
            ext in VIDEO -> FileType.VIDEO
            ext in AUDIO -> FileType.AUDIO
            ext == "pdf" -> FileType.PDF
            ext == "md" || ext == "markdown" -> FileType.MARKDOWN
            ext == "html" || ext == "htm" -> FileType.HTML
            ext in OFFICE -> FileType.OFFICE
            ext == "apk" -> FileType.APK
            ext in TEXT || lower in TEXT_NAMES -> FileType.TEXT
            else -> FileType.OTHER
        }
    }

    /** Kinds that get a preview card (thumbnail, poster or icon) in the chat timeline. */
    fun hasInlinePreview(type: FileType) = type == FileType.IMAGE || type == FileType.SVG || type == FileType.VIDEO || type == FileType.AUDIO || type == FileType.PDF

    /** MIME for an external ACTION_VIEW; wildcard families where Android resolves them. */
    fun mimeOf(name: String): String {
        val ext = name.lowercase().substringAfterLast('.', "")
        return OFFICE[ext] ?: when (kindOf(name)) {
            FileType.IMAGE -> "image/*"
            FileType.VIDEO -> "video/*"
            FileType.AUDIO -> "audio/*"
            FileType.PDF -> "application/pdf"
            FileType.SVG -> "image/svg+xml"
            else -> "*/*"
        }
    }
}
