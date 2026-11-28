package network.lapis.cloud.server.translation

/** The outcome of reading a language wish from a request (Welle V1.9.343, T2). */
internal sealed interface LanguageChoice {
    /** No usable wish (no `?lang=`, no `Accept-Language`, or only `*`): the ORIGINAL is delivered and the model is never called. */
    data object NoPreference : LanguageChoice

    /** [target] is always one of [TranslationLanguages.SUPPORTED]; [explicit] is `true` iff it came from `?lang=`. */
    data class Target(
        val target: String,
        val explicit: Boolean,
    ) : LanguageChoice
}

/**
 * Pure language negotiation for public endpoints (no Ktor types). Never throws, linear time, hard input bounds. The raw request value is never
 * returned, logged or echoed: only codes from [TranslationLanguages.SUPPORTED] leave this object.
 */
internal object LanguageNegotiation {
    /** Total length of all `Accept-Language` header lines together; longer input is treated as absent. */
    const val MAX_HEADER_CHARS = 1024
    const val MAX_ENTRIES = 32
    const val MAX_TAG_CHARS = 35

    private val TAG = Regex("^[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*$")
    private val QVALUE = Regex("^(0(\\.[0-9]{0,3})?|1(\\.0{0,3})?)$")

    /**
     * Weight-ordered (stable) language tags. `q=0` removes an entry, `*` is ignored, an entry with an invalid range or an invalid `q` is skipped.
     * Other parameters next to `q` are ignored; the parameter name `q` is case-insensitive.
     */
    fun parseAcceptLanguage(headerValues: List<String>): List<String> {
        if (headerValues.isEmpty()) return emptyList()
        val joined = headerValues.joinToString(",")
        if (joined.length > MAX_HEADER_CHARS) return emptyList()
        val weighted = ArrayList<Pair<String, Double>>()
        for (entry in joined.split(',').take(MAX_ENTRIES)) {
            val parts = entry.split(';')
            val tag = parts[0].trim()
            if (tag.isEmpty() || tag == "*" || tag.length > MAX_TAG_CHARS || !TAG.matches(tag)) continue
            var weight = 1.0
            var valid = true
            val qParam =
                parts
                    .drop(
                        1,
                    ).firstOrNull { it.substringBefore('=', missingDelimiterValue = "").trim().equals("q", ignoreCase = true) }
            if (qParam != null) {
                val raw = qParam.substringAfter('=', missingDelimiterValue = "").trim()
                val parsed = if (QVALUE.matches(raw)) raw.toDoubleOrNull() else null
                if (parsed == null) valid = false else weight = parsed
            }
            if (!valid || weight <= 0.0) continue
            weighted += tag to weight
        }
        return weighted.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * [langParams] = all values of the query parameter `lang` (`null` = the key is absent). Once the key is present it is authoritative:
     * exactly one well-formed value is resolved; an invalid, empty, too long or repeated value gives [TranslationLanguages.FALLBACK] and the
     * header is not read at all. Without the key the header decides; no usable header gives [LanguageChoice.NoPreference].
     */
    fun choose(
        langParams: List<String>?,
        acceptLanguage: List<String>,
    ): LanguageChoice {
        if (langParams != null) {
            val value = langParams.singleOrNull()
            val target =
                if (value != null && value.length <= MAX_TAG_CHARS && TAG.matches(value)) {
                    TranslationLanguages.resolveTarget(listOf(value))
                } else {
                    TranslationLanguages.FALLBACK
                }
            return LanguageChoice.Target(target = target, explicit = true)
        }
        val preferences = parseAcceptLanguage(acceptLanguage)
        if (preferences.isEmpty()) return LanguageChoice.NoPreference
        return LanguageChoice.Target(target = TranslationLanguages.resolveTarget(preferences), explicit = false)
    }
}
