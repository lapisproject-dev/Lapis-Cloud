package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.li
import io.kvision.html.ul

/** One block of an agreement text: a running paragraph or a bullet list. The parser knows nothing else, so nothing but text can ever be built. */
internal sealed interface AgreementBlock {
    data class Paragraph(
        val text: String,
    ) : AgreementBlock

    data class Bullets(
        val items: List<String>,
    ) : AgreementBlock
}

private val BULLET_MARKERS = listOf("- ", "• ")

private fun bulletText(line: String): String? {
    val trimmed = line.trimStart()
    val marker = BULLET_MARKERS.firstOrNull { trimmed.startsWith(it) } ?: return null
    return trimmed.removePrefix(marker).trim()
}

/**
 * Splits a plain agreement text into [AgreementBlock]s (V1.9.134): a blank line ends a block, a line that starts with `- ` or `• ` starts a
 * list item, an indented line after an item continues it (the placeholder wraps its items that way), any other line belongs to the running
 * paragraph (its lines are joined with a space). Pure and total: every input yields blocks, no input yields markup.
 */
internal fun parseAgreementBlocks(raw: String): List<AgreementBlock> {
    val blocks = mutableListOf<AgreementBlock>()
    val paragraph = mutableListOf<String>()
    val items = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) blocks += AgreementBlock.Paragraph(paragraph.joinToString(" "))
        paragraph.clear()
    }

    fun flushItems() {
        if (items.isNotEmpty()) blocks += AgreementBlock.Bullets(items.toList())
        items.clear()
    }

    for (line in raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
        val bullet = bulletText(line)
        when {
            line.isBlank() -> {
                flushParagraph()
                flushItems()
            }
            bullet != null -> {
                flushParagraph()
                items += bullet
            }
            items.isNotEmpty() && line.first().isWhitespace() -> items[items.lastIndex] = items.last() + " " + line.trim()
            else -> {
                flushItems()
                paragraph += line.trim()
            }
        }
    }
    flushParagraph()
    flushItems()
    return blocks
}

/**
 * Builds the text of an agreement into this container -- paragraphs and bullet lists, shared by the registration, the FRIEND registration
 * and the administration preview. The text is untrusted (an admin typed it): it goes through [sanitizeUntrustedI18nText] first (KVision's
 * own i18n markers must not resolve) and then only ever becomes element TEXT -- never `rich`, never `innerHTML`.
 */
internal fun Container.renderAgreementText(raw: String) {
    parseAgreementBlocks(sanitizeUntrustedI18nText(raw)).forEach { block ->
        when (block) {
            is AgreementBlock.Paragraph -> untrustedP(block.text)
            is AgreementBlock.Bullets -> {
                val list = ul()
                block.items.forEach { item -> list.li(item) }
            }
        }
    }
}
