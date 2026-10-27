package network.lapis.cloud.shared.domain

/**
 * V1.9.232 -- limits and the type whitelist for attachments (mailing messages; since V1.9.233 also direct messages: ONE rule set,
 * extracted rather than copied). Shared by the server (authoritative) and the client (advance check only). All sizes are BINARY (MiB), matching `FileDisplay.formatFileSize`, so a file the UI shows as "9.9 MB" is never
 * refused by the server.
 */
object AttachmentRules {
    const val MAX_FILES = 5
    const val MAX_FILE_BYTES: Long = 10L * 1024 * 1024
    const val MAX_TOTAL_BYTES: Long = 20L * 1024 * 1024
    const val MAX_NAME_CHARS = 120

    const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    const val MIME_ODT = "application/vnd.oasis.opendocument.text"
    const val MIME_ODS = "application/vnd.oasis.opendocument.spreadsheet"
    const val MIME_ODP = "application/vnd.oasis.opendocument.presentation"

    /** extension (lowercase) -> canonical, server-chosen MIME type. The client-declared type is never used. */
    val ALLOWED: Map<String, String> =
        linkedMapOf(
            "pdf" to "application/pdf",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "docx" to MIME_DOCX,
            "xlsx" to MIME_XLSX,
            "pptx" to MIME_PPTX,
            "odt" to MIME_ODT,
            "ods" to MIME_ODS,
            "odp" to MIME_ODP,
            "txt" to "text/plain",
            "csv" to "text/csv",
        )

    /** The distinct MIME values (the DB CHECK constraint lists exactly these). */
    val ALLOWED_MIME_TYPES: Set<String> = ALLOWED.values.toSet()

    /** Charsets a stored text attachment may carry. */
    val TEXT_CHARSETS: Set<String> = setOf("UTF-8", "windows-1252")

    /** Last segment after the final '.', lowercase; "" if there is no dot or nothing before/after it. */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /** Value for `<input accept>`: extensions plus MIME types. */
    fun acceptAttribute(): String = (ALLOWED.keys.map { ".$it" } + ALLOWED_MIME_TYPES.sorted()).joinToString(",")
}

/**
 * Fixed, machine-readable refusal codes of the attachment routes (JSON body `{"code":"..."}`); never free text. The wire names are
 * unchanged since V1.9.232. `NOT_DRAFT` is only sent by the mailing routes; `RECIPIENT_UNAVAILABLE` and `PENDING_LIMIT` only by the
 * direct-message routes (V1.9.233).
 */
enum class AttachmentErrorCode {
    INVALID_ORIGIN,
    NOT_FOUND,
    FORBIDDEN,
    NOT_DRAFT,
    TOO_MANY,
    FILE_TOO_LARGE,
    TOTAL_TOO_LARGE,
    TYPE_NOT_ALLOWED,
    CONTENT_MISMATCH,
    MACRO_OR_EMBEDDED,
    DUPLICATE_NAME,
    INVALID_NAME,
    MULTIPLE_FILES,
    NO_FILE,
    RATE_LIMITED,
    RECIPIENT_UNAVAILABLE,
    PENDING_LIMIT,
}
