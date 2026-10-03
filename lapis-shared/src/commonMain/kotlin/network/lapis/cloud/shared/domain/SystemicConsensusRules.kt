package network.lapis.cloud.shared.domain

/**
 * V1.9.39 -- rules of Systemic Consensus shared by server (authoritative) and client (live validation).
 */
object SystemicConsensusRules {
    /** Limit in UTF-16 units AND code points (column is `VARCHAR(1000)` on H2 and PostgreSQL). */
    const val MAX_RATIONALE_LENGTH = 1000
    const val MAX_RATIONALE_LINE_BREAKS = 20

    /**
     * Upper tenth of the scale, rounded up, purely integer: `ceil(0.9 * scaleMax)` without floating point
     * (`ceil(0.9 * 10)` would yield 10 in floating point). A maximum resistance at or above this value
     * is a "strong objection". `scaleMax >= 1`.
     */
    fun strongObjectionThreshold(scaleMax: Int): Int {
        require(scaleMax >= 1) { "scaleMax must be >= 1" }
        return ((9L * scaleMax + 9L) / 10L).toInt()
    }

    /** `null`/blank -> [PublicTextNormalization.Empty] (= remove); otherwise [PublicTextRules] plus the UTF-16 length limit. */
    fun normalizeRationale(raw: String?): PublicTextNormalization {
        if (raw == null) return PublicTextNormalization.Empty
        val result =
            PublicTextRules.normalize(
                raw = raw,
                maxCodePoints = MAX_RATIONALE_LENGTH,
                maxLineBreaks = MAX_RATIONALE_LINE_BREAKS,
            )
        if (result is PublicTextNormalization.Ok && result.text.length > MAX_RATIONALE_LENGTH) {
            return PublicTextNormalization.TooLong
        }
        return result
    }
}
