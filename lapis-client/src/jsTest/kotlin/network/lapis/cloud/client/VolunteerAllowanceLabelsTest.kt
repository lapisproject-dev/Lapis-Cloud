package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.VolunteerAllowanceCategory
import network.lapis.cloud.shared.domain.VolunteerAllowanceDeclarationSource
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
import network.lapis.cloud.shared.domain.VolunteerAllowanceSelfDeclarationDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- covers every pure, DOM-independent
 * function in `VolunteerAllowanceLabels.kt`, same scope posture as [TravelExpenseLabelsTest] (no
 * rendering harness exists in this module).
 */
class VolunteerAllowanceLabelsTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark", "light")

    // Same untranslated-marker prefix `tr()` returns in this DOM-free test harness, see
    // [SidebarLabelsTest]'s own `kvI18nMarker`.
    private val kvI18nMarker = "###KvI18nS###"

    @Test
    fun volunteerAllowanceStatusLabel_isNonBlankForEveryValue() {
        VolunteerAllowancePaymentStatus.entries.forEach { status ->
            assertTrue(volunteerAllowanceStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    // Jobs-Ruling: EXECUTED must never read as a bare technical status word.
    @Test
    fun volunteerAllowanceStatusLabel_executedReadsAsBookedForPayout() {
        assertEquals(
            "${kvI18nMarker}Zur Auszahlung gebucht",
            volunteerAllowanceStatusLabel(VolunteerAllowancePaymentStatus.EXECUTED),
        )
    }

    // APPROVED is factually always a failed booking attempt (chk_vap_execution_error_state) -- must
    // never read as a plain success/"Genehmigt".
    @Test
    fun volunteerAllowanceStatusLabel_approvedIsNotTheBareWordGenehmigt() {
        assertFalse(volunteerAllowanceStatusLabel(VolunteerAllowancePaymentStatus.APPROVED).endsWith("Genehmigt"))
    }

    @Test
    fun volunteerAllowanceStatusColor_isAlwaysASemanticColor() {
        VolunteerAllowancePaymentStatus.entries.forEach { status ->
            assertTrue(volunteerAllowanceStatusColor(status) in semanticColors)
        }
    }

    @Test
    fun volunteerAllowanceCategoryLabel_isNonBlankAndDistinctPerCategory() {
        val labels = VolunteerAllowanceCategory.entries.map { volunteerAllowanceCategoryLabel(it) }
        labels.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(labels.size, labels.toSet().size, "category labels must be distinct")
    }

    @Test
    fun volunteerAllowanceCategoryIcon_isAlwaysAFontAwesomeClassAndDistinctPerCategory() {
        val icons = VolunteerAllowanceCategory.entries.map { volunteerAllowanceCategoryIcon(it) }
        icons.forEach { assertTrue(it.startsWith("fas fa-")) }
        assertEquals(icons.size, icons.toSet().size, "category icons must be distinct (Kare's 3,4-fach-Argument)")
    }

    @Test
    fun volunteerAllowanceCategoryColor_isAlwaysASemanticColorAndDistinctPerCategory() {
        val colors = VolunteerAllowanceCategory.entries.map { volunteerAllowanceCategoryColor(it) }
        colors.forEach { assertTrue(it in semanticColors) }
        assertEquals(colors.size, colors.toSet().size, "category colors must be distinct")
    }

    // ---- the mandatory sentences, verbatim ----------------------------------------------------

    @Test
    fun volunteerAllowanceRemainingLabel_neverSaysRestbetrag() {
        assertFalse(volunteerAllowanceRemainingLabel().contains("Restbetrag"))
    }

    @Test
    fun volunteerAllowanceForeignOrgsDisclaimer_isTheExactWording() {
        assertEquals("${kvI18nMarker}Andere Organisationen sind Lapis Cloud nicht bekannt.", volunteerAllowanceForeignOrgsDisclaimer())
    }

    @Test
    fun volunteerAllowancePayoutDisclaimer_reusesTravelExpensePayoutDisclaimerVerbatim() {
        assertEquals(travelExpensePayoutDisclaimer(), volunteerAllowancePayoutDisclaimer())
    }

    @Test
    fun volunteerAllowanceForeignSubjectDeclarationHint_mentionsTheReceivingPerson() {
        assertTrue(volunteerAllowanceForeignSubjectDeclarationHint().contains("empfangende Person"))
    }

    @Test
    fun volunteerAllowanceSelfDeclarationPendingRepostMessage_isNonBlank() {
        assertTrue(volunteerAllowanceSelfDeclarationPendingRepostMessage().isNotBlank())
    }

    // Regression guard for the whole module, not just volunteerAllowanceRemainingLabel -- "Restbetrag"
    // must never leak through ANY label/message function (Kay/Jobs-Ruling).
    @Test
    fun noLabelOrMessageFunctionEverSaysRestbetrag() {
        val allTexts =
            VolunteerAllowancePaymentStatus.entries.map { volunteerAllowanceStatusLabel(it) } +
                VolunteerAllowanceCategory.entries.map { volunteerAllowanceCategoryLabel(it) } +
                listOf(
                    volunteerAllowanceRemainingLabel(),
                    volunteerAllowanceForeignOrgsDisclaimer(),
                    volunteerAllowanceForeignSubjectDeclarationHint(),
                    volunteerAllowanceSelfDeclarationPendingRepostMessage(),
                    volunteerAllowancePayoutDisclaimer(),
                ) +
                VolunteerAllowancePostingErrorCode.entries.map { volunteerAllowancePostingErrorMessage(it.wireCode) } +
                listOf(volunteerAllowancePostingErrorMessage(null), volunteerAllowancePostingErrorMessage("unknown_code"))
        allTexts.forEach { text -> assertFalse(text.contains("Restbetrag"), "must not contain 'Restbetrag': $text") }
    }

    // ---- volunteerAllowanceDeclarationBadge -------------------------------------------------

    @Test
    fun volunteerAllowanceDeclarationBadge_inAppMentionsLapisConfirmation() {
        val dto =
            VolunteerAllowanceSelfDeclarationDto(
                id = "d1",
                memberId = "m1",
                category = VolunteerAllowanceCategory.HONORARY,
                calendarYear = 2026,
                source = VolunteerAllowanceDeclarationSource.IN_APP,
                declaredAt = LocalDateTime(2026, 3, 1, 10, 0),
                signedOn = null,
                recordedByDisplayName = "Anna Musterfrau",
            )
        val badge = volunteerAllowanceDeclarationBadge(dto)
        assertTrue(badge.isNotBlank())
        assertFalse(badge.contains("Papierform"))
    }

    @Test
    fun volunteerAllowanceDeclarationBadge_onPaperMentionsSignedOnAndRecordedBy() {
        val dto =
            VolunteerAllowanceSelfDeclarationDto(
                id = "d2",
                memberId = "m1",
                category = VolunteerAllowanceCategory.HONORARY,
                calendarYear = 2026,
                source = VolunteerAllowanceDeclarationSource.ON_PAPER,
                declaredAt = LocalDateTime(2026, 3, 1, 10, 0),
                signedOn = LocalDate(2026, 2, 20),
                recordedByDisplayName = "Max Vorstand",
            )
        val badge = volunteerAllowanceDeclarationBadge(dto)
        assertTrue(badge.contains("Papierform"))
        assertTrue(badge.contains("Max Vorstand"))
    }

    // ---- volunteerAllowancePostingErrorMessage / parseVolunteerAllowancePostingErrorCode ----

    @Test
    fun parseVolunteerAllowancePostingErrorCode_matchesEveryDeclaredWireCode() {
        VolunteerAllowancePostingErrorCode.entries.forEach { code ->
            assertEquals(code, parseVolunteerAllowancePostingErrorCode(code.wireCode))
        }
    }

    @Test
    fun parseVolunteerAllowancePostingErrorCode_unknownCodeIsNull() {
        assertNull(parseVolunteerAllowancePostingErrorCode("something_unheard_of"))
    }

    @Test
    fun volunteerAllowancePostingErrorMessage_nullRawIsAGenericMessage() {
        assertTrue(volunteerAllowancePostingErrorMessage(null).isNotBlank())
    }

    @Test
    fun volunteerAllowancePostingErrorMessage_neverLeaksTheRawWireCode() {
        (VolunteerAllowancePostingErrorCode.entries.map { it.wireCode } + listOf(null, "unknown_code")).forEach { raw ->
            val message = volunteerAllowancePostingErrorMessage(raw)
            VolunteerAllowancePostingErrorCode.entries.forEach { code ->
                assertFalse(
                    message.contains(code.wireCode),
                    "message for raw=$raw must never contain the raw wire code ${code.wireCode}",
                )
            }
        }
    }

    @Test
    fun volunteerAllowancePostingErrorMessage_isNonBlankAndDistinctForEveryDeclaredCode() {
        val messages = VolunteerAllowancePostingErrorCode.entries.map { volunteerAllowancePostingErrorMessage(it.wireCode) }
        messages.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(messages.size, messages.toSet().size, "every wire code must map to a distinct message")
    }
}
