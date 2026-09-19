package network.lapis.cloud.server.ai.safety

/**
 * Turns model output (untrusted text) into safe plain text. The client renders the result
 * exclusively as text nodes (never `rich = true`/`innerHTML`/a Markdown renderer); this is the
 * second, server-side layer.
 *
 * Removes C0/C1 control characters (keeping `\n`), bidi/zero-width formatting characters (used for
 * text spoofing), and anything that parses as an HTML/XML tag; normalizes line breaks; collapses
 * blank runs; caps the length. No Markdown or HTML is interpreted.
 */
internal object PlainTextSanitizer {
    private val TAG = Regex("""</?[A-Za-z!?][^>]*>""")
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]")
    private val FORMATTING = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2069\\uFEFF]")
    private val BLANK_RUNS = Regex("\\n{3,}")
    private val SPACE_RUNS = Regex("[ \\t]{2,}")

    fun sanitize(
        text: String,
        maxChars: Int,
    ): String {
        var cleaned = text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ')
        cleaned = CONTROL.replace(cleaned, "")
        cleaned = FORMATTING.replace(cleaned, "")
        cleaned = TAG.replace(cleaned, "")
        cleaned = SPACE_RUNS.replace(cleaned, " ")
        cleaned = BLANK_RUNS.replace(cleaned, "\n\n").trim()
        if (cleaned.length > maxChars) cleaned = cleaned.take(maxChars).trimEnd() + "…"
        return cleaned
    }
}
