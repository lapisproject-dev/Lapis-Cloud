package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.11 "Reisekostenabrechnung für Vorstand und Funktionsträger" -- covers every pure,
 * DOM-independent function in `TravelExpenseLabels.kt`, same scope posture as
 * [ContributionReliefLabelsTest] (no rendering harness exists in this module).
 */
class TravelExpenseLabelsTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark", "light")

    @Test
    fun travelExpenseStatusLabel_isNonBlankForEveryValue() {
        TravelExpenseReportStatus.entries.forEach { status ->
            assertTrue(travelExpenseStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    // Zhuo/Jobs-Ruling: EXECUTED must never read as a bare "Ausgeführt" -- see class KDoc.
    @Test
    fun travelExpenseStatusLabel_executedIsNotTheBareWordAusgefuehrt() {
        assertFalse(travelExpenseStatusLabel(TravelExpenseReportStatus.EXECUTED).endsWith("Ausgeführt"))
    }

    // APPROVED is factually always a failed booking attempt (chk_ter_execution_error_state) --
    // must never read as a plain success/"Genehmigt".
    @Test
    fun travelExpenseStatusLabel_approvedIsNotTheBareWordGenehmigt() {
        assertFalse(travelExpenseStatusLabel(TravelExpenseReportStatus.APPROVED).endsWith("Genehmigt"))
    }

    @Test
    fun travelExpenseStatusColor_isAlwaysASemanticColor() {
        TravelExpenseReportStatus.entries.forEach { status ->
            assertTrue(travelExpenseStatusColor(status) in semanticColors)
        }
    }

    @Test
    fun travelExpenseLineKindLabel_isNonBlankForEveryValue() {
        TravelExpenseLineKind.entries.forEach { kind ->
            assertTrue(travelExpenseLineKindLabel(kind).isNotBlank())
        }
    }

    @Test
    fun travelExpenseLineKindColor_isAlwaysASemanticColor() {
        TravelExpenseLineKind.entries.forEach { kind ->
            assertTrue(travelExpenseLineKindColor(kind) in semanticColors)
        }
    }

    @Test
    fun travelExpenseLineKindIcon_isAlwaysAFontAwesomeClass() {
        TravelExpenseLineKind.entries.forEach { kind ->
            assertTrue(travelExpenseLineKindIcon(kind).startsWith("fas fa-"))
        }
    }

    @Test
    fun travelExpensePayoutDisclaimer_isNonBlank() {
        assertTrue(travelExpensePayoutDisclaimer().isNotBlank())
    }

    @Test
    fun receiptIcon_knownMimeTypesMapToDistinctIcons() {
        assertEquals("fas fa-file-pdf", receiptIcon("application/pdf"))
        assertEquals("fas fa-file-image", receiptIcon("image/jpeg"))
        assertEquals("fas fa-file-image", receiptIcon("image/png"))
        assertEquals("fas fa-file", receiptIcon("application/octet-stream"))
    }

    @Test
    fun receiptSizeLabel_choosesTheRightUnit() {
        assertEquals("512 B", receiptSizeLabel(512))
        assertEquals("2 KB", receiptSizeLabel(2048))
        assertEquals("3 MB", receiptSizeLabel(3 * 1024 * 1024))
    }

    // ---- travelExpenseStepStates ------------------------------------------------------------

    @Test
    fun travelExpenseStepStates_hasExactlyFourPillsForEveryStatus() {
        TravelExpenseReportStatus.entries.forEach { status ->
            assertEquals(4, travelExpenseStepStates(status).size, "expected 4 pills for $status")
        }
    }

    @Test
    fun travelExpenseStepStates_approvedIsAnIntermediateStateNotAnEndState() {
        val states = travelExpenseStepStates(TravelExpenseReportStatus.APPROVED)
        assertEquals(TravelExpenseStepState.CURRENT, states[2])
        assertEquals(TravelExpenseStepState.FUTURE, states[3])
    }

    @Test
    fun travelExpenseStepStates_executedIsTheOnlyStatusWithAllFourPillsPastOrCurrent() {
        val states = travelExpenseStepStates(TravelExpenseReportStatus.EXECUTED)
        assertEquals(TravelExpenseStepState.CURRENT, states.last())
        assertTrue(states.dropLast(1).all { it == TravelExpenseStepState.PAST })
    }

    // ---- travelExpensePostingErrorMessage / parseTravelExpensePostingErrorCode -------------

    @Test
    fun parseTravelExpensePostingErrorCode_matchesEveryDeclaredWireCode() {
        TravelExpensePostingErrorCode.entries.forEach { code ->
            assertEquals(code, parseTravelExpensePostingErrorCode(code.wireCode))
        }
    }

    @Test
    fun parseTravelExpensePostingErrorCode_unknownCodeIsNull() {
        assertNull(parseTravelExpensePostingErrorCode("something_unheard_of"))
    }

    @Test
    fun travelExpensePostingErrorMessage_nullRawIsAGenericMessage() {
        assertTrue(travelExpensePostingErrorMessage(null).isNotBlank())
    }

    @Test
    fun travelExpensePostingErrorMessage_neverLeaksTheRawWireCode() {
        (TravelExpensePostingErrorCode.entries.map { it.wireCode } + listOf(null, "unknown_code")).forEach { raw ->
            val message = travelExpensePostingErrorMessage(raw)
            TravelExpensePostingErrorCode.entries.forEach { code ->
                assertFalse(message.contains(code.wireCode), "message for raw=$raw must never contain the raw wire code ${code.wireCode}")
            }
        }
    }

    @Test
    fun travelExpensePostingErrorMessage_isNonBlankForEveryDeclaredCode() {
        TravelExpensePostingErrorCode.entries.forEach { code ->
            assertTrue(travelExpensePostingErrorMessage(code.wireCode).isNotBlank())
        }
    }

    // ---- V1.9.122 -----------------------------------------------------------------------------

    @Test
    fun theAddableKinds_areMileageAndTheFourReceiptedKinds_neverDayOrLegacy() {
        assertEquals(5, TRAVEL_EXPENSE_ADDABLE_KINDS.size)
        assertFalse(TravelExpenseLineKind.DAY_ALLOWANCE in TRAVEL_EXPENSE_ADDABLE_KINDS)
        assertFalse(TravelExpenseLineKind.PER_DIEM in TRAVEL_EXPENSE_ADDABLE_KINDS)
        assertFalse(TravelExpenseLineKind.RECEIPTED in TRAVEL_EXPENSE_ADDABLE_KINDS)
    }

    @Test
    fun everyKindHasADistinctLabelAndIcon() {
        val kinds = TravelExpenseLineKind.entries
        assertEquals(kinds.size, kinds.map { travelExpenseLineKindLabel(it) }.toSet().size)
        assertEquals(kinds.size, kinds.map { travelExpenseLineKindIcon(it) }.toSet().size)
    }

    @Test
    fun payoutChoiceLabels_areNonBlankAndDifferent() {
        val labels = TravelExpensePayoutChoice.entries.map { travelExpensePayoutChoiceLabel(it) }
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(2, labels.toSet().size)
    }

    @Test
    fun theThreeNewPostingErrorCodes_haveMessages_andMatchTheServerLiterals() {
        listOf(
            "travel_advance_account_not_configured",
            "donation_waiver_account_not_configured",
            "donation_waiver_account_not_income_type",
        ).forEach { wire ->
            assertTrue(parseTravelExpensePostingErrorCode(wire) != null, "$wire is a declared code")
            assertTrue(travelExpensePostingErrorMessage(wire).isNotBlank())
        }
    }

    @Test
    fun weekdayLabels_coverTheWholeWeek() {
        val week = (5..11).map { resolvedAttributeText(travelExpenseWeekdayLabel(kotlinx.datetime.LocalDate(2026, 1, it))) }
        assertEquals(7, week.toSet().size)
        assertEquals("Montag", week.first())
    }

    // ── V1.9.123 SEPA ──

    @Test
    fun travelExpenseSepaBlockerLabel_isNonBlankAndDistinctForEveryBlocker() {
        val labels =
            network.lapis.cloud.shared.domain.TravelExpenseSepaBlocker.entries
                .map { travelExpenseSepaBlockerLabel(it) }
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.size, labels.toSet().size, "two blockers must not read the same")
    }

    @Test
    fun travelExpenseSepaErrorMessage_neverShowsTheRawCode() {
        listOf(
            "execution_date_past",
            "execution_date_too_far",
            "execution_date_not_target2_day",
            "debtor_account_missing",
            "rate_limited",
        ).forEach {
            assertFalse(travelExpenseSepaErrorMessage(it).contains("_"), it)
        }
        assertEquals(travelExpenseSepaErrorMessage("anything_unknown"), travelExpenseSepaErrorMessage("http_500"))
    }

    @Test
    fun travelExpenseSepaSelectionSumCents_isTheExactSum() {
        assertEquals(0L, travelExpenseSepaSelectionSumCents(emptyMap()))
        assertEquals(12_505L, travelExpenseSepaSelectionSumCents(mapOf("a" to 8_000L, "b" to 4_505L)))
    }

    @Test
    fun travelExpenseSepaHolderDiffers_comparesTrimmedAndCaseInsensitively() {
        assertFalse(travelExpenseSepaHolderDiffers(" erika mustermann ", "Erika Mustermann"))
        assertTrue(travelExpenseSepaHolderDiffers("Max Mustermann", "Erika Mustermann"))
        assertFalse(travelExpenseSepaHolderDiffers(null, "Erika Mustermann"))
    }
}
