package network.lapis.cloud.server.translation

/**
 * Links never travel through the model: URLs, `www.` hosts, e-mail addresses and the schemes `mailto:`, `tel:`, `javascript:` and `data:` are
 * replaced by opaque tokens (`⟦L1⟧` for links, `⟦E1⟧` for e-mail addresses) before the text is sent, and put back byte for byte after the
 * answer has been validated. A model can therefore neither change nor invent a link.
 */
internal object LinkPlaceholders {
    const val OPEN = '⟦'
    const val CLOSE = '⟧'

    private val LINK = Regex("""(?i)\b(?:https?://|www\.|mailto:|tel:|javascript:|data:)[^\s<>"'⟦⟧]+""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val TOKEN = Regex("""⟦[LE]\d+⟧""")
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}"

    class Masked(
        val text: String,
        /** token -> original, in order of appearance. */
        val placeholders: Map<String, String>,
    )

    /** Source text can never contain a token of its own: the two bracket characters are swapped for ordinary ones first. */
    fun neutralize(text: String): String = text.replace(OPEN, '[').replace(CLOSE, ']')

    fun mask(text: String): Masked {
        val neutral = neutralize(text)
        val placeholders = LinkedHashMap<String, String>()
        var linkCount = 0
        var emailCount = 0
        val sb = StringBuilder()
        var index = 0
        while (index < neutral.length) {
            val link = LINK.find(neutral, index)
            val email = EMAIL.find(neutral, index)
            val next =
                when {
                    link == null -> email
                    email == null -> link
                    email.range.first < link.range.first -> email
                    else -> link
                } ?: break
            var matched = next.value
            if (next === link) {
                // trailing sentence punctuation is not part of the link
                val trimmed = matched.trimEnd { it in TRAILING_PUNCTUATION }
                if (trimmed.isEmpty()) {
                    sb.append(neutral, index, next.range.last + 1)
                    index = next.range.last + 1
                    continue
                }
                matched = trimmed
            }
            sb.append(neutral, index, next.range.first)
            val token = if (next === link) "$OPEN" + "L${++linkCount}" + "$CLOSE" else "$OPEN" + "E${++emailCount}" + "$CLOSE"
            placeholders[token] = matched
            sb.append(token)
            index = next.range.first + matched.length
        }
        if (index < neutral.length) sb.append(neutral, index, neutral.length)
        return Masked(text = sb.toString(), placeholders = placeholders)
    }

    /** Every token of [masked] output, in order. */
    fun tokensIn(text: String): List<String> = TOKEN.findAll(text).map { it.value }.toList()

    /** `true` if [text] contains an opening or closing bracket that is NOT part of a well-formed token. */
    fun hasStrayBracket(text: String): Boolean = text.replace(TOKEN, "").any { it == OPEN || it == CLOSE }

    /** `true` if a link, e-mail address or dangerous scheme appears in [maskedText] outside a token. */
    fun containsLinkLike(maskedText: String): Boolean {
        val without = maskedText.replace(TOKEN, " ")
        return LINK.containsMatchIn(without) || EMAIL.containsMatchIn(without)
    }

    fun restore(
        maskedText: String,
        placeholders: Map<String, String>,
    ): String {
        var result = maskedText
        placeholders.forEach { (token, original) -> result = result.replace(token, original) }
        return result
    }
}
