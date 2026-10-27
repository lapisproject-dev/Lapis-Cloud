package network.lapis.cloud.server.attachments

import network.lapis.cloud.shared.domain.AttachmentRules
import java.text.Normalizer
import java.util.Locale

private val WINDOWS_RESERVED_NAMES: Set<String> =
    buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (n in 1..9) {
            add("COM$n")
            add("LPT$n")
        }
    }
private const val FORBIDDEN_NAME_CHARS = "\"<>:*?|"
private const val MAX_KEPT_EXTENSION_CHARS = 20

/**
 * V1.9.232 -- the display name of a mailing attachment. Pure; `null` means "no usable name" ([network.lapis.cloud.shared.domain.AttachmentErrorCode.INVALID_NAME]).
 * The result is display metadata and the `Content-Disposition` file name of the mail only -- it never touches a path (the storage key is server-generated).
 *
 * 1. only the part after the last `/` or `\`; 2. NFC; 3. removed: C0/C1 controls and DEL, Unicode format characters (bidi overrides
 * U+202A..U+202E / U+2066..U+2069, ZWJ/ZWSP, BOM), lone surrogates and `" < > : * ? |`; other separator characters (NBSP, ...) become a plain
 * space; 4. spaces and dots trimmed at both ends; 5. a reserved Windows device name (CON, NUL, COM1, ...) as stem gets the prefix `_`;
 * 6. at most [AttachmentRules.MAX_NAME_CHARS] characters, shortening the stem and keeping the extension; 7. a name that is only an
 * extension (`.pdf`) is invalid.
 */
internal fun sanitizeAttachmentName(raw: String): String? {
    val lastPart = raw.substringAfterLast('/').substringAfterLast('\\')
    val normalized = Normalizer.normalize(lastPart, Normalizer.Form.NFC)
    val cleaned = StringBuilder(normalized.length)
    var index = 0
    while (index < normalized.length) {
        val codePoint = normalized.codePointAt(index)
        index += Character.charCount(codePoint)
        when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.UNASSIGNED -> Unit
            Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> cleaned.append(' ')
            else -> if (codePoint >= 0x80 || FORBIDDEN_NAME_CHARS.indexOf(codePoint.toChar()) < 0) cleaned.appendCodePoint(codePoint)
        }
    }
    val leadTrimmed = cleaned.toString().trimStart(' ')
    // Only an extension (".pdf", "..pdf"): nothing before the first real dot.
    if (leadTrimmed.startsWith('.') && !leadTrimmed.trimStart('.').contains('.')) return null
    var name = leadTrimmed.trim { it == ' ' || it == '.' }
    if (name.isEmpty()) return null
    val firstDot = name.indexOf('.')
    val stemOfFirst = if (firstDot >= 0) name.substring(0, firstDot) else name
    if (stemOfFirst.trimEnd(' ').uppercase(Locale.ROOT) in WINDOWS_RESERVED_NAMES) name = "_$name"
    if (name.length > AttachmentRules.MAX_NAME_CHARS) {
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && name.length - dot in 2..(MAX_KEPT_EXTENSION_CHARS + 1)) name.substring(dot) else ""
        val stem = name.substring(0, name.length - extension.length)
        name = stem.take(AttachmentRules.MAX_NAME_CHARS - extension.length).trimEnd(' ', '.') + extension
    }
    return name.ifEmpty { null }
}

/** Case-insensitive duplicate key of a sanitized display name (NFC + `lowercase(Locale.ROOT)`), at most the column length. */
internal fun attachmentNameKey(sanitizedName: String): String =
    Normalizer.normalize(sanitizedName, Normalizer.Form.NFC).lowercase(Locale.ROOT).take(AttachmentRules.MAX_NAME_CHARS)
