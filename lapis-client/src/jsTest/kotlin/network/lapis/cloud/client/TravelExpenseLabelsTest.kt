package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.TravelExpenseLineKind
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
}
