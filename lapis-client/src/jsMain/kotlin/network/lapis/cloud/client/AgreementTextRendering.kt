package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.div
import io.kvision.html.li
import io.kvision.html.ul
import network.lapis.cloud.shared.domain.AgreementTextFormat

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

/** CSS class of the server-rendered Markdown body of an agreement text (see `theme.css`). */
internal const val AGREEMENT_BODY_CLASS = "lapis-agreement-body"

/**
 * V1.9.196 -- THE ONE rich (`innerHTML`) site for agreement texts. [renderedHtml] comes ONLY from the server (`AgreementMarkdown`: raw HTML
 * escaped, only `http(s)`/`mailto` links keep an `href`, images become text); the client never renders Markdown itself. KVision resolves a content
 * string that starts with its i18n marker, so only a string that starts with `<` is accepted (rendered HTML always does) -- anything else is
 * shown as text by [renderAgreementBody]'s fallback.
 */
internal fun Container.agreementHtml(renderedHtml: String) {
    div(content = renderedHtml, rich = true) { addCssClass(AGREEMENT_BODY_CLASS) }
}

/**
 * Shows an agreement text: a MARKDOWN text with its server-rendered HTML through [agreementHtml]; everything else (plain texts, and a MARKDOWN
 * text without usable HTML, e.g. from an older server) as element text through [renderAgreementText] -- readable, never markup.
 */
internal fun Container.renderAgreementBody(
    text: String,
    format: AgreementTextFormat = AgreementTextFormat.PLAIN,
    renderedHtml: String? = null,
) {
    if (format == AgreementTextFormat.MARKDOWN && renderedHtml != null && renderedHtml.startsWith("<")) {
        agreementHtml(renderedHtml)
    } else {
        renderAgreementText(text)
    }
}
