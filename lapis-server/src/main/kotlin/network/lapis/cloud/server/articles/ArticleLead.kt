package network.lapis.cloud.server.articles

import java.text.Normalizer
import java.util.Locale

/**
 * V1.9.130 -- decides whether the public article page shows the excerpt as a separate lead paragraph. An excerpt that merely repeats the
 * beginning of the body (typical for imported articles, whose first paragraph IS the excerpt) would otherwise appear twice.
 *
 * Pure and read-only: it only inspects the already sanitized HTML produced by [ArticleMarkdown.render]; the lead itself is still rendered
 * as an escaped text node. Cost is linear in the body length, once per request, outside any transaction.
 *
 * Known, intended edge cases: a shortened excerpt ("... harter Auf...") counts as a prefix of "... harter Aufprall" (that is how truncated
 * excerpts look); a body that starts with a heading before the repeated sentence does NOT start with the excerpt, so the lead is shown.
 */
internal object ArticleLead {
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&[A-Za-z0-9#]+;")
    private val WHITESPACE = Regex("\\s+")

    /** Visible text of [html]: tags become spaces, the common entities are decoded (`&amp;` protected from the unknown-entity sweep), unknown entities become spaces. */
    fun textOf(html: String): String =
        html
            .replace(TAG, " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "\u0000")
            .replace(ENTITY, " ")
            .replace("\u0000", "&")

    /** NFC, lower-cased, every non-letter/digit becomes a space, whitespace collapsed and trimmed. */
    fun norm(s: String): String {
        val folded =
            Normalizer
                .normalize(s, Normalizer.Form.NFC)
                .lowercase(Locale.ROOT)
                .map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString("")
        return folded.replace(WHITESPACE, " ").trim()
    }

    /** `true` when the lead should be shown: the normalized excerpt is non-empty and the normalized body text does not start with it. */
    fun shouldShow(
        excerpt: String,
        renderedBodyHtml: String,
    ): Boolean {
        val e = norm(excerpt)
        if (e.isEmpty()) return false
        return !norm(textOf(renderedBodyHtml)).startsWith(e)
    }
}
