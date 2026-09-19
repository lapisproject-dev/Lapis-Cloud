package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AiAnswerOutcome
import network.lapis.cloud.shared.domain.AiIndexStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure, DOM-free coverage of [StatuteQaUi] (V1.6.1 "Fragen zur Satzung") -- the wording the design
 * team fixed, the eight screen states and the small formatting helpers. Same no-rendering-harness
 * posture as [NavVisibilityTest]/[DocumentsAuthzUiTest].
 */
class StatuteQaUiTest {
    @Test
    fun disclaimer_isExactlyTheDecidedWording() {
        assertEquals(
            "KI-generierte Zusammenfassung, kann Fehler enthalten. Maßgeblich ist der zitierte Originaltext.",
            StatuteQaUi.DISCLAIMER,
        )
    }

    @Test
    fun optInText_isTwoSentences_andNamesTheLimitOfTheRedaction() {
        val sentences = StatuteQaUi.OPT_IN_TEXT.split(". ").filter { it.isNotBlank() }
        assertEquals(2, sentences.size)
        assertTrue(StatuteQaUi.OPT_IN_TEXT.contains("Namen und andere Freitext-Angaben werden nicht erkannt"))
        assertTrue(StatuteQaUi.OPT_IN_TEXT.contains("jederzeit widerrufen"))
    }

    @Test
    fun nothingFoundText_neverSuggestsRewording() {
        val text = StatuteQaUi.NOTHING_FOUND_TEXT.lowercase()
        assertFalse(text.contains("umformul"))
        assertFalse(text.contains("anders formul"))
        assertTrue(text.contains("vorstand"))
    }

    @Test
    fun providerErrorText_namesNeitherProviderNorModel() {
        val text = StatuteQaUi.PROVIDER_ERROR_TEXT.lowercase()
        listOf("anthropic", "openai", "mistral", "claude", "gpt", "modell", "model").forEach { assertFalse(text.contains(it), it) }
    }

    @Test
    fun citationHeader_withAndWithoutLocator() {
        assertEquals("Satzung · v2 · § 7 Abs. 2", StatuteQaUi.citationHeader("Satzung", 2, "§ 7 Abs. 2"))
        assertEquals("Satzung · v3 · Seite 4", StatuteQaUi.citationHeader("Satzung", 3, "Seite 4"))
        assertEquals("Satzung · v1", StatuteQaUi.citationHeader("Satzung", 1, ""))
    }

    @Test
    fun shortenExcerpt_cutsAtTheLimit_andFlattensWhitespace() {
        assertEquals("a b c", StatuteQaUi.shortenExcerpt("a\n  b\t c"))
        val long = "x".repeat(500)
        val shortened = StatuteQaUi.shortenExcerpt(long)
        assertEquals(StatuteQaUi.EXCERPT_MAX_CHARS + 1, shortened.length)
        assertTrue(shortened.endsWith("…"))
        assertEquals("x".repeat(350), StatuteQaUi.shortenExcerpt("x".repeat(350)))
    }

    @Test
    fun counter_appearsFrom400Characters() {
        assertFalse(StatuteQaUi.counterVisible(399))
        assertTrue(StatuteQaUi.counterVisible(400))
        assertTrue(StatuteQaUi.counterVisible(500))
    }

    @Test
    fun canSubmit_requiresOptInLengthBoundsAndNoRequestInFlight() {
        assertTrue(StatuteQaUi.canSubmit(trimmedLength = 8, min = 8, max = 500, optIn = true, loading = false))
        assertTrue(StatuteQaUi.canSubmit(trimmedLength = 500, min = 8, max = 500, optIn = true, loading = false))
        assertFalse(StatuteQaUi.canSubmit(trimmedLength = 7, min = 8, max = 500, optIn = true, loading = false))
        assertFalse(StatuteQaUi.canSubmit(trimmedLength = 501, min = 8, max = 500, optIn = true, loading = false))
        assertFalse(StatuteQaUi.canSubmit(trimmedLength = 20, min = 8, max = 500, optIn = false, loading = false))
        assertFalse(StatuteQaUi.canSubmit(trimmedLength = 20, min = 8, max = 500, optIn = true, loading = true))
    }

    @Test
    fun stateFor_coversAllEightStates() {
        fun state(
            enabled: Boolean = true,
            optIn: Boolean = true,
            loading: Boolean = false,
            outcome: AiAnswerOutcome? = null,
        ) = StatuteQaUi.stateFor(enabled, optIn, loading, outcome)

        assertEquals(StatuteQaState.EMPTY, state())
        assertEquals(StatuteQaState.OPT_IN_MISSING, state(optIn = false))
        assertEquals(StatuteQaState.DISABLED, state(enabled = false))
        assertEquals(StatuteQaState.LOADING, state(loading = true))
        assertEquals(StatuteQaState.ANSWERED, state(outcome = AiAnswerOutcome.ANSWERED))
        assertEquals(StatuteQaState.NOTHING_FOUND, state(outcome = AiAnswerOutcome.NOTHING_FOUND))
        assertEquals(StatuteQaState.RATE_LIMITED, state(outcome = AiAnswerOutcome.RATE_LIMITED))
        assertEquals(StatuteQaState.PROVIDER_ERROR, state(outcome = AiAnswerOutcome.PROVIDER_UNAVAILABLE))
        assertEquals(8, StatuteQaState.entries.size)
    }

    @Test
    fun stateFor_priority_disabledBeatsEverything_loadingBeatsOptInAndOutcome() {
        assertEquals(StatuteQaState.DISABLED, StatuteQaUi.stateFor(false, false, true, AiAnswerOutcome.ANSWERED))
        assertEquals(StatuteQaState.LOADING, StatuteQaUi.stateFor(true, false, true, AiAnswerOutcome.ANSWERED))
        assertEquals(StatuteQaState.OPT_IN_MISSING, StatuteQaUi.stateFor(true, false, false, AiAnswerOutcome.ANSWERED))
    }

    @Test
    fun unindexedReasonText_mapsTheServerCodes() {
        assertEquals("Format nicht lesbar", StatuteQaUi.unindexedReasonText("UNSUPPORTED_FORMAT"))
        assertEquals("Indexierung fehlgeschlagen", StatuteQaUi.unindexedReasonText("FAILED"))
        assertEquals("Indexierung läuft", StatuteQaUi.unindexedReasonText("PENDING"))
        assertEquals("Indexierung läuft", StatuteQaUi.unindexedReasonText(null))
    }

    @Test
    fun statusMark_hasOneMarkPerIndexStatus_includingTheFifthFailedState() {
        assertEquals("indexiert", StatuteQaUi.statusMark(AiIndexStatus.INDEXED))
        assertEquals("Indexierung läuft", StatuteQaUi.statusMark(AiIndexStatus.PENDING))
        assertEquals("Format nicht lesbar", StatuteQaUi.statusMark(AiIndexStatus.UNSUPPORTED_FORMAT))
        assertEquals("Indexierung fehlgeschlagen", StatuteQaUi.statusMark(AiIndexStatus.FAILED))
        assertEquals("nicht freigegeben", StatuteQaUi.statusMark(AiIndexStatus.NOT_RELEASED))
        assertEquals(
            AiIndexStatus.entries.size,
            AiIndexStatus.entries
                .map { StatuteQaUi.statusMark(it) }
                .toSet()
                .size,
        )
    }

    @Test
    fun waitParts_roundsUp_andPicksTheUnit() {
        assertEquals(StatuteQaUi.WAIT_LESS_THAN_MINUTE to null, StatuteQaUi.waitParts(0))
        assertEquals(StatuteQaUi.WAIT_LESS_THAN_MINUTE to null, StatuteQaUi.waitParts(59))
        assertEquals(StatuteQaUi.WAIT_MINUTES to 1, StatuteQaUi.waitParts(60))
        assertEquals(StatuteQaUi.WAIT_MINUTES to 2, StatuteQaUi.waitParts(61))
        assertEquals(StatuteQaUi.WAIT_MINUTES to 60, StatuteQaUi.waitParts(3_599))
        assertEquals(StatuteQaUi.WAIT_HOURS to 1, StatuteQaUi.waitParts(3_600))
        assertEquals(StatuteQaUi.WAIT_HOURS to 2, StatuteQaUi.waitParts(3_601))
    }
}
