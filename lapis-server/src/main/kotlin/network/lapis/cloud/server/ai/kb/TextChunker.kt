package network.lapis.cloud.server.ai.kb

internal data class TextChunk(
    val index: Int,
    val sectionLabel: String?,
    val pageNumber: Int?,
    val text: String,
)

/**
 * Splits extracted page text into retrieval chunks of about [TARGET_CHARS] characters with an
 * [OVERLAP_CHARS] overlap, keeping the nearest preceding statute-style heading as the chunk's
 * `sectionLabel` (falling back to the page number as the locator when there is none).
 *
 * Heading patterns: Markdown headings (`## Title`), `§ 7 ...`, `Art. 3`/`Artikel 3`, numbered
 * headings (`1.2 Title`); a paragraph marker `(2)` is appended to the current heading
 * (`§ 7 Beitrag (2)`) or stands alone if no heading was seen yet.
 */
internal object TextChunker {
    const val TARGET_CHARS = 1_200
    const val OVERLAP_CHARS = 150
    const val MAX_CHUNKS_PER_DOCUMENT = 2_000
    private const val MAX_LABEL_CHARS = 80

    private val MARKDOWN_HEADING = Regex("^#{1,6}\\s+(.+)$")
    private val SECTION_HEADING = Regex("^§\\s*\\d+[a-z]?\\b.*$")
    private val ARTICLE_HEADING = Regex("^Art(?:ikel|\\.)?\\s*\\d+.*$", RegexOption.IGNORE_CASE)
    private val NUMBERED_HEADING = Regex("^\\d+(?:\\.\\d+)*\\.?\\s+\\p{Lu}.*$")
    private val PARAGRAPH_MARKER = Regex("^\\((\\d+)\\)")
    private val PARAGRAPH_SPLIT = Regex("\\n\\s*\\n")
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val INLINE_SPACE = Regex("[ \\t]+")

    private class Paragraph(
        val text: String,
        val page: Int?,
        val label: String?,
    )

    fun chunk(pages: List<PageText>): List<TextChunk> {
        val paragraphs = toParagraphs(pages)
        val chunks = mutableListOf<TextChunk>()
        val buffer = StringBuilder()
        var bufferLabel: String? = null
        var bufferPage: Int? = null
        var hasNewContent = false

        fun flush() {
            val text = buffer.toString().trim()
            if (text.isNotEmpty() && hasNewContent && chunks.size < MAX_CHUNKS_PER_DOCUMENT) {
                chunks += TextChunk(index = chunks.size, sectionLabel = bufferLabel, pageNumber = bufferPage, text = text)
            }
            val tail = overlapTail(text)
            buffer.clear()
            buffer.append(tail)
            hasNewContent = false
        }

        for (paragraph in paragraphs) {
            if (chunks.size >= MAX_CHUNKS_PER_DOCUMENT) break
            if (buffer.isNotEmpty() && hasNewContent && buffer.length + paragraph.text.length + 2 > TARGET_CHARS) flush()
            if (!hasNewContent) {
                bufferLabel = paragraph.label
                bufferPage = paragraph.page
            }
            if (buffer.isNotEmpty()) buffer.append("\n\n")
            buffer.append(paragraph.text)
            hasNewContent = true
        }
        if (hasNewContent) flush()
        return chunks
    }

    private fun overlapTail(text: String): String {
        if (text.length <= OVERLAP_CHARS) return ""
        val tail = text.takeLast(OVERLAP_CHARS)
        val firstSpace = tail.indexOf(' ')
        return if (firstSpace in 0 until tail.length - 1) tail.substring(firstSpace + 1) else tail
    }

    private fun toParagraphs(pages: List<PageText>): List<Paragraph> {
        val result = mutableListOf<Paragraph>()
        var section: String? = null
        var marker: String? = null
        for (page in pages) {
            val normalized = CONTROL.replace(page.text.replace("\r\n", "\n").replace('\r', '\n'), "")
            for (rawParagraph in normalized.split(PARAGRAPH_SPLIT)) {
                val lines = rawParagraph.lines().map { INLINE_SPACE.replace(it, " ").trim() }.filter { it.isNotEmpty() }
                if (lines.isEmpty()) continue
                // Label state AFTER the paragraph's first line, so "§ 7 Beitrag\n(1) ..." carries "§ 7 Beitrag".
                var labelAfterFirstLine: String? = null
                lines.forEachIndexed { lineIndex, line ->
                    val heading = headingOf(line)
                    if (heading != null) {
                        section = heading
                        marker = null
                    } else {
                        PARAGRAPH_MARKER.find(line)?.let { marker = "(${it.groupValues[1]})" }
                    }
                    if (lineIndex == 0) labelAfterFirstLine = composeLabel(section = section, marker = marker)
                }
                val body = lines.joinToString(separator = "\n")
                for (piece in splitLong(body)) {
                    result += Paragraph(text = piece, page = page.pageNumber, label = labelAfterFirstLine)
                }
            }
        }
        return result
    }

    private fun composeLabel(
        section: String?,
        marker: String?,
    ): String? =
        when {
            section != null && marker != null -> "$section $marker".take(MAX_LABEL_CHARS + 6)
            section != null -> section
            else -> marker
        }

    private fun headingOf(line: String): String? {
        MARKDOWN_HEADING.find(line)?.let { return it.groupValues[1].trim().take(MAX_LABEL_CHARS) }
        val isHeading = SECTION_HEADING.matches(line) || ARTICLE_HEADING.matches(line) || NUMBERED_HEADING.matches(line)
        // Long lines that merely START like a heading are running text, not a heading.
        return if (isHeading && line.length <= MAX_LABEL_CHARS * 2) line.take(MAX_LABEL_CHARS) else null
    }

    private fun splitLong(text: String): List<String> {
        if (text.length <= TARGET_CHARS) return listOf(text)
        val pieces = mutableListOf<String>()
        var rest = text
        while (rest.length > TARGET_CHARS) {
            var cut = rest.lastIndexOf(' ', TARGET_CHARS)
            if (cut < TARGET_CHARS / 2) cut = TARGET_CHARS
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }
}
