package network.lapis.cloud.server.agreementtexts

import network.lapis.cloud.server.articles.ArticleMarkdown
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.AgreementTextRules
import org.commonmark.renderer.text.TextContentRenderer

/**
 * V1.9.196 -- THE ONLY place an agreement text's Markdown source is turned into HTML. It reuses the sanitizing [ArticleMarkdown] pipeline
 * (raw HTML escaped as text, only `http://`, `https://` and `mailto:` links keep their `href`, images become their alt text, headings shifted to
 * h2-h4), capped at [AgreementTextRules.MAX_CHARS] -- the same limit the write path enforces, so the rendered text can never be shorter than the
 * hashed text. Single line breaks are kept (`<br />`), as the plain-text display always did.
 *
 * Privacy rule: this object never logs; the text is not an input to anything but the renderer.
 */
internal object AgreementMarkdown {
    private val plainRenderer: TextContentRenderer = TextContentRenderer.builder().build()

    /** Sanitized HTML of a Markdown [body]. */
    fun render(
        body: String,
        maxChars: Int = AgreementTextRules.MAX_CHARS,
    ): String = ArticleMarkdown.render(markdown = body, maxChars = maxChars, softBreakAsLineBreak = true)

    /**
     * `true` if the parsed [body] holds any block other than plain paragraphs (heading incl. Setext, list, quote, code block incl. indented,
     * thematic break, HTML block). Used for the short dialog texts; the line regex alone misses Setext headings, `***`, indented code.
     */
    fun hasBlockNodes(body: String): Boolean =
        try {
            var node = ArticleMarkdown.parse(markdown = body, maxChars = AgreementTextRules.MAX_CHARS).firstChild
            var found = false
            while (node != null && !found) {
                if (node !is org.commonmark.node.Paragraph) found = true
                node = node.next
            }
            found
        } catch (_: StackOverflowError) {
            true
        }

    /**
     * [render], or `null` if the text is nested deeper than [ArticleMarkdown.MAX_NESTING_DEPTH] (checked on the parsed tree, not by waiting for
     * a `StackOverflowError`), e.g. 20,000 nested quote markers. Used where a text is ACCEPTED (save, publish, preview): a text that is refused
     * here is never stored, and the parser limits can never silently clamp an accepted text (they start above the depth limit). The
     * `StackOverflowError` catch stays as a second safety net. Nothing about the text is logged.
     */
    fun renderOrNull(
        body: String,
        maxChars: Int = AgreementTextRules.MAX_CHARS,
    ): String? =
        ArticleMarkdown
            .parseWithinDepthLimit(markdown = body, maxChars = maxChars)
            ?.let { document -> ArticleMarkdown.renderDocument(document = document, softBreakAsLineBreak = true) }

    /**
     * Like [renderOrNull] but WITHOUT the depth refusal: the read paths of already stored texts (tender description, travel contact hint). A text
     * stored before V1.9.337 may be nested deeper than [ArticleMarkdown.MAX_NESTING_DEPTH]; it is still shown, the limit applies when saving
     * and in the preview only. `null` only if rendering overflows the stack.
     */
    fun renderForDisplayOrNull(
        body: String,
        maxChars: Int = AgreementTextRules.MAX_CHARS,
    ): String? =
        try {
            render(body = body, maxChars = maxChars)
        } catch (_: StackOverflowError) {
            null
        }

    /** Plain text without Markdown characters (for the headline and key points of an encounter or event notice). */
    fun toPlainText(body: String): String =
        plainRenderer.render(ArticleMarkdown.parse(markdown = body, maxChars = AgreementTextRules.MAX_CHARS)).trim()

    /** The HTML for [format], or `null` for [AgreementTextFormat.PLAIN] (the client shows plain text as text). */
    fun htmlFor(
        format: AgreementTextFormat,
        body: String,
        maxChars: Int = AgreementTextRules.MAX_CHARS,
    ): String? = if (format == AgreementTextFormat.MARKDOWN) render(body = body, maxChars = maxChars) else null
}
