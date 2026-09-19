package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AiAnswerOutcome
import network.lapis.cloud.shared.domain.AiIndexStatus

/** The eight states of the statute Q&A screen (Design-Team decision, V1.6.1). */
enum class StatuteQaState {
    /** Feature on, opted in, nothing asked yet. */
    EMPTY,

    /** Feature on but the member has not opted in: the input is locked. */
    OPT_IN_MISSING,

    /** Server has the AI layer off: reachable only by a direct URL, shows a plain notice. */
    DISABLED,

    /** A question is in flight: indeterminate progress, input locked, never streaming. */
    LOADING,

    /** Last answer carries a summary and citations. */
    ANSWERED,

    /** Last answer had no evidence: calm notice naming the searched documents, no rewording advice. */
    NOTHING_FOUND,

    /** Rate limit hit: names the waiting time. */
    RATE_LIMITED,

    /** Provider failed: one neutral line, no provider or model name. */
    PROVIDER_ERROR,
}

/**
 * Pure, DOM-free logic and wording of the statute Q&A screen -- extracted so `jsTest` can pin it
 * without a rendering harness (same posture as [DocumentsAuthzUi]/[NavVisibility]). The German
 * strings are the gettext msgids: the screen passes them through `tr(...)`, so every one of them
 * must exist in `messages.pot` and all seven language catalogs (`verifyI18nCatalogParity`).
 */
object StatuteQaUi {
    /** From this many characters on the input shows a counter; below it the field stays quiet. */
    const val COUNTER_THRESHOLD = 400

    /** Excerpt length shown per citation -- mirrors the server's `AiConfig.maxExcerptChars`. */
    const val EXCERPT_MAX_CHARS = 350

    /** Exact wording decided by the design team; shown under every answer in normal type size (no box, no warning colour). */
    const val DISCLAIMER = "KI-generierte Zusammenfassung, kann Fehler enthalten. Maßgeblich ist der zitierte Originaltext."

    /** Two sentences, shown next to the (never pre-selected) consent switch. */
    const val OPT_IN_TEXT =
        "Wenn Sie zustimmen, wird Ihre Frage - nach dem Entfernen erkennbarer Kennungen wie E-Mail-Adressen, IBAN und " +
            "Telefonnummern - zusammen mit passenden Auszügen aus den freigegebenen Dokumenten an einen externen " +
            "KI-Anbieter übermittelt. Namen und andere Freitext-Angaben werden nicht erkannt, schreiben Sie deshalb keine " +
            "personenbezogenen Daten in Ihre Frage; die Zustimmung können Sie hier jederzeit widerrufen."

    const val NOTHING_FOUND_TEXT =
        "Dazu wurde in den durchsuchten Dokumenten keine Fundstelle gefunden. " +
            "Bitte wenden Sie sich bei Fragen zur Satzung an den Vorstand."
    const val RATE_LIMITED_TEXT = "Sie haben das Fragenlimit erreicht. Bitte versuchen Sie es in %1 erneut."
    const val PROVIDER_ERROR_TEXT = "Die KI-Auskunft ist gerade nicht erreichbar. Bitte versuchen Sie es später erneut."
    const val DISABLED_TEXT = "Diese Funktion ist auf diesem Server nicht verfügbar."
    const val OPT_IN_REQUIRED_TEXT = "Bitte stimmen Sie oben zu, um Fragen stellen zu können."
    const val LOADING_TEXT = "Die Auskunft wird erstellt ..."

    const val REASON_UNSUPPORTED = "Format nicht lesbar"
    const val REASON_FAILED = "Indexierung fehlgeschlagen"
    const val REASON_PENDING = "Indexierung läuft"
    const val MARK_INDEXED = "indexiert"
    const val MARK_NOT_RELEASED = "nicht freigegeben"

    const val WAIT_LESS_THAN_MINUTE = "weniger als einer Minute"
    const val WAIT_MINUTES = "%1 Min."
    const val WAIT_HOURS = "%1 Std."

    /**
     * `Titel · v2 · § 7 Abs. 2` -- the citation header. The locator is left out when the stored
     * record has none (an empty string), never replaced by a placeholder.
     */
    fun citationHeader(
        documentTitle: String,
        versionNumber: Int,
        locator: String,
    ): String = listOf(documentTitle, "v$versionNumber", locator).filter { it.isNotBlank() }.joinToString(separator = " · ")

    /** Flattens whitespace and shortens to [max] characters with an ellipsis. */
    fun shortenExcerpt(
        text: String,
        max: Int = EXCERPT_MAX_CHARS,
    ): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length > max) flat.take(max).trimEnd() + "…" else flat
    }

    fun counterVisible(length: Int): Boolean = length >= COUNTER_THRESHOLD

    /** May the submit button be pressed? Mirrors the server's own length bounds (which stay the authority). */
    fun canSubmit(
        trimmedLength: Int,
        min: Int,
        max: Int,
        optIn: Boolean,
        loading: Boolean,
    ): Boolean = optIn && !loading && trimmedLength in min..max

    fun stateFor(
        featureEnabled: Boolean,
        optIn: Boolean,
        loading: Boolean,
        lastOutcome: AiAnswerOutcome?,
    ): StatuteQaState =
        when {
            !featureEnabled -> StatuteQaState.DISABLED
            loading -> StatuteQaState.LOADING
            !optIn -> StatuteQaState.OPT_IN_MISSING
            lastOutcome == AiAnswerOutcome.ANSWERED -> StatuteQaState.ANSWERED
            lastOutcome == AiAnswerOutcome.NOTHING_FOUND -> StatuteQaState.NOTHING_FOUND
            lastOutcome == AiAnswerOutcome.RATE_LIMITED -> StatuteQaState.RATE_LIMITED
            lastOutcome == AiAnswerOutcome.PROVIDER_UNAVAILABLE -> StatuteQaState.PROVIDER_ERROR
            else -> StatuteQaState.EMPTY
        }

    /** The reason text (a msgid) for a not-indexable document of the scope, from the server's stable code. */
    fun unindexedReasonText(code: String?): String =
        when (code) {
            "UNSUPPORTED_FORMAT" -> REASON_UNSUPPORTED
            "FAILED" -> REASON_FAILED
            else -> REASON_PENDING
        }

    /** The admin mark (a msgid) for a knowledge-base entry's state. */
    fun statusMark(status: AiIndexStatus): String =
        when (status) {
            AiIndexStatus.INDEXED -> MARK_INDEXED
            AiIndexStatus.PENDING -> REASON_PENDING
            AiIndexStatus.UNSUPPORTED_FORMAT -> REASON_UNSUPPORTED
            AiIndexStatus.FAILED -> REASON_FAILED
            AiIndexStatus.NOT_RELEASED -> MARK_NOT_RELEASED
        }

    /**
     * Human waiting time for a rate-limit hint as `(msgid, amount)`; `amount` is `null` for the
     * "less than a minute" wording that carries no number.
     */
    fun waitParts(seconds: Int): Pair<String, Int?> =
        when {
            seconds < 60 -> WAIT_LESS_THAN_MINUTE to null
            seconds < 3_600 -> WAIT_MINUTES to (seconds + 59) / 60
            else -> WAIT_HOURS to (seconds + 3_599) / 3_600
        }
}
