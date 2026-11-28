package network.lapis.cloud.server.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import network.lapis.cloud.server.events.EventText

/** Which structure an answer may contain, per content type. Events are plain text: nothing at all. */
internal data class StructurePolicy(
    val allowMarkdownLinks: Boolean,
    val allowHtmlLikeMarkup: Boolean,
) {
    companion object {
        /** Event texts: no Markdown, no HTML (see `EventText`). */
        val PLAIN_TEXT = StructurePolicy(allowMarkdownLinks = false, allowHtmlLikeMarkup = false)
    }
}

/**
 * Pure validation of a model answer (no I/O). `null` means DISCARD -- a doubtful answer is never repaired, because the only safe repair is
 * the original text. The reason is deliberately not returned as text (it could embed model output).
 */
internal object TranslationValidator {
    private val DIGITS = Regex("""\d+""")
    private val HTML_LIKE = Regex("<\\s*/?\\s*[a-zA-Z!]")
    private val FENCE = Regex("""^```(?:json)?\s*\n(.*)\n```$""", RegexOption.DOT_MATCHES_ALL)

    private val HARD_LIMIT =
        mapOf(
            TranslatableField.TITLE to 600,
            TranslatableField.SUMMARY to 900,
            TranslatableField.COVER_IMAGE_ALT to 1500,
            TranslatableField.DESCRIPTION to 20_000,
        )

    class SourceField(
        /** The normalized original (also what was hashed). */
        val original: String,
        val masked: LinkPlaceholders.Masked,
    )

    /**
     * @return the translated fields with the links put back, or `null` to discard the whole answer.
     */
    fun validate(
        source: Map<TranslatableField, SourceField>,
        raw: String,
        policy: StructurePolicy = StructurePolicy.PLAIN_TEXT,
    ): Map<TranslatableField, String>? {
        val parsed = parseStrict(raw = raw, expectedKeys = source.keys.map(TranslationPrompt::keyOf).toSet()) ?: return null
        val result = LinkedHashMap<TranslatableField, String>()
        for ((field, src) in source) {
            val answer = parsed[TranslationPrompt.keyOf(field)] ?: return null
            result[field] = validateField(field = field, src = src, answer = answer, policy = policy) ?: return null
        }
        return result
    }

    private fun parseStrict(
        raw: String,
        expectedKeys: Set<String>,
    ): Map<String, String>? {
        var text = raw.trim()
        FENCE.matchEntire(text)?.let { text = it.groupValues[1].trim() }
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        if (element.keys != expectedKeys) return null
        val values = LinkedHashMap<String, String>()
        for ((key, value) in element) {
            val primitive = value as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            values[key] = primitive.content
        }
        return values
    }

    private fun validateField(
        field: TranslatableField,
        src: SourceField,
        answer: String,
        policy: StructurePolicy,
    ): String? {
        val multiline = field == TranslatableField.DESCRIPTION
        // 1. Control and bidi characters out; an empty result for a non-empty source is a failure.
        val normalized = if (multiline) EventText.normalizeMultiline(answer) else EventText.normalizeSingleLine(answer)
        if (normalized.isEmpty() && src.original.isNotEmpty()) return null

        // 2. Tokens: exactly the source's tokens, each exactly once, no stray bracket, no invented token.
        val expectedTokens = src.masked.placeholders.keys
        val tokens = LinkPlaceholders.tokensIn(normalized)
        if (tokens.size != expectedTokens.size || tokens.toSet() != expectedTokens) return null
        if (LinkPlaceholders.hasStrayBracket(normalized)) return null

        // 3. No link, e-mail address or dangerous scheme outside a token.
        if (LinkPlaceholders.containsLinkLike(normalized)) return null

        // 4. Structure: no HTML-like markup, no Markdown link, no autolink -- unless the source had at least as many occurrences.
        val maskedSource = src.masked.text
        if (!policy.allowHtmlLikeMarkup && HTML_LIKE.findAll(normalized).count() > HTML_LIKE.findAll(maskedSource).count()) return null
        if (!policy.allowMarkdownLinks && normalized.countOccurrences("](") > maskedSource.countOccurrences("](")) return null
        if (normalized.contains("<${LinkPlaceholders.OPEN}") && !maskedSource.contains("<${LinkPlaceholders.OPEN}")) return null

        // 5. Paragraph and line structure of a multi-line field is unchanged.
        if (multiline) {
            val expected = EventText.paragraphs(EventText.normalizeMultiline(maskedSource)).map { it.size }
            val actual = EventText.paragraphs(normalized).map { it.size }
            if (expected != actual) return null
        }

        // 6. Every digit sequence survives (multiset). This may discard a correct translation ("20 Uhr" -> "8 pm"); that is intended.
        if (DIGITS
                .findAll(maskedSource)
                .map { it.value }
                .toList()
                .sorted() !=
            DIGITS
                .findAll(normalized)
                .map { it.value }
                .toList()
                .sorted()
        ) {
            return null
        }

        // 7. Put the links back, then check the length against both the relative and the hard limit.
        val restored = LinkPlaceholders.restore(maskedText = normalized, placeholders = src.masked.placeholders)
        val relativeLimit = maxOf(3 * src.original.length, src.original.length + 200)
        if (restored.length > relativeLimit || restored.length > (HARD_LIMIT[field] ?: return null)) return null
        return restored
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }
}
