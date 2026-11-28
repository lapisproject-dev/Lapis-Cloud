package network.lapis.cloud.server.translation

import network.lapis.cloud.server.routes.PublicLanguage

/**
 * The ONE source of truth for the translation languages: the public-site language list ([PublicLanguage], the hreflang source). A test pins
 * that it equals `MembershipDecisionTexts.SUPPORTED`.
 */
internal object TranslationLanguages {
    val SUPPORTED: List<String> = PublicLanguage.entries.map { it.code }
    const val FALLBACK = "en"
    private const val SOURCE_DEFAULT = "de"
    private const val MAX_TAG_LENGTH = 35
    private const val MAX_PREFERENCES = 20

    /** Endonym of a supported code (used in the system prompt; never user data). */
    fun endonym(code: String): String = PublicLanguage.parse(code)?.nativeName ?: error("unsupported language code")

    /**
     * [preferences]: already weight-ordered language tags ("en-US", "fr", "*"). Primary subtag, lower-case, at most 35 characters, at most 20
     * entries; the first supported one wins; none -> [FALLBACK]. Never throws.
     */
    fun resolveTarget(preferences: List<String>): String {
        for (raw in preferences.take(MAX_PREFERENCES)) {
            val tag = raw.trim()
            if (tag.isEmpty() || tag.length > MAX_TAG_LENGTH) continue
            val primary = tag.substringBefore('-').substringBefore('_').lowercase()
            if (primary in SUPPORTED) return primary
        }
        return FALLBACK
    }

    /** Unknown/empty -> "de". */
    fun normalizeSource(code: String?): String {
        val normalized = code?.trim()?.lowercase()
        return if (normalized != null && normalized in SUPPORTED) normalized else SOURCE_DEFAULT
    }
}
