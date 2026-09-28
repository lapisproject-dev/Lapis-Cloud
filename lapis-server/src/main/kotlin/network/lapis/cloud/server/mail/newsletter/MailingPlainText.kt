package network.lapis.cloud.server.mail.newsletter

import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import network.lapis.cloud.shared.rpc.BadRequestException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Welle V1.9.7 "SuperMailer" Teil A -- derives the plain-text body of a mailing message from its
 * ALREADY-SANITIZED HTML (never the raw author input -- [MailingHtmlSanitizer.sanitize] always
 * calls this on its own cleaned [Document], so every tag this function has to handle is one of
 * [MailingHtmlPolicy.ALLOWED_TAGS]).
 *
 * **Never a tracking URL** (S10 in the plan) -- this always runs against the UNREWRITTEN sanitized
 * document, before `MailingMailRenderer`'s click-rewrite pass (follow-up wave) ever touches a
 * `<a href>`. The plain-text mail a recipient's mail client shows in its preview pane must never
 * carry a tracking link, even for a recipient who consented to click tracking.
 */
object MailingPlainText {
    fun derive(sanitizedDoc: Document): String {
        val paragraphs = mutableListOf<String>()
        sanitizedDoc.body().childNodes().forEach { node -> renderBlock(node = node, out = paragraphs) }
        val text = paragraphs.filter { it.isNotBlank() }.joinToString("\n\n")

        if (text.isBlank()) throw BadRequestException("Text darf nicht leer sein.")
        if (text.length > MailingHtmlPolicy.MAX_TEXT_CHARS) {
            throw BadRequestException("Nachricht zu lang (max. ${MailingHtmlPolicy.MAX_TEXT_CHARS} Zeichen Text).")
        }
        return text
    }

    /** Appends zero or one paragraph-shaped [String] to [out] for one top-level block node. */
    private fun renderBlock(
        node: Node,
        out: MutableList<String>,
    ) {
        when {
            node is TextNode -> out += node.text().trim()
            node is Element && node.tagName() == "ul" -> out += renderList(listElement = node, ordered = false)
            node is Element && node.tagName() == "ol" -> out += renderList(listElement = node, ordered = true)
            node is Element && node.tagName() == "h2" -> out += renderInlineChildren(node).uppercase()
            node is Element -> out += renderInlineChildren(node)
            // "p"/"h3"/"blockquote" and any other allowed block tag: plain inline rendering.
        }
    }

    private fun renderList(
        listElement: Element,
        ordered: Boolean,
    ): String =
        listElement
            .children()
            .filter { it.tagName() == "li" }
            .mapIndexed { index, li ->
                val prefix = if (ordered) "${index + 1}. " else "- "
                prefix + renderInlineChildren(li)
            }.joinToString("\n")

    private fun renderInlineChildren(element: Element): String = element.childNodes().joinToString("") { renderInline(it) }.trim()

    private fun renderInline(node: Node): String =
        when {
            node is TextNode -> node.text()
            node is Element && node.tagName() == "br" -> "\n"
            node is Element && node.tagName() == "a" -> {
                val href = node.attr("href")
                val linkText = renderInlineChildren(node)
                if (linkText.isBlank() || linkText == href) href else "$linkText ($href)"
            }
            node is Element -> renderInlineChildren(node)
            else -> ""
        }
}
