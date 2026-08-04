package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PostingSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Accounting UI wave -- covers only the pure, DOM-independent label/color functions local to
 * `LedgerScreen.kt` ([ledgerAccountTypeLabel]/[ledgerAccountTypeColor]/[postingSideLabel]/
 * [postingSideColor]/[journalEntryStatusLabel]/[journalEntryStatusColor]), same scope posture as
 * [MeetingsScreenTest]/[CommitteesScreenTest] (no DOM/rendering test harness exists in this
 * module). [GemeinnuetzigkeitSphere]/[ReserveType]/[DonorCategory] labels live in the shared
 * `AccountingLabels.kt` and are covered by [AccountingLabelsTest] instead.
 */
class LedgerScreenTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    @Test
    fun ledgerAccountTypeLabel_isNonBlankForEveryValue() {
        LedgerAccountType.entries.forEach { type ->
            assertTrue(ledgerAccountTypeLabel(type).isNotBlank(), "expected a non-blank label for $type")
        }
    }

    @Test
    fun ledgerAccountTypeColor_isARealBootstrapHueForEveryValue() {
        LedgerAccountType.entries.forEach { type ->
            val color = ledgerAccountTypeColor(type)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $type, got \"$color\"")
        }
    }

    @Test
    fun postingSideLabel_isSollHabenNeverDebitCredit() {
        // D8: literal "Soll"/"Haben" everywhere, never "Debit"/"Credit" or the raw enum names.
        assertEquals("Soll", postingSideLabel(PostingSide.DEBIT))
        assertEquals("Haben", postingSideLabel(PostingSide.CREDIT))
    }

    @Test
    fun postingSideColor_isARealBootstrapHueForEveryValue() {
        PostingSide.entries.forEach { side ->
            val color = postingSideColor(side)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $side, got \"$color\"")
        }
    }

    @Test
    fun journalEntryStatusLabel_isNonBlankForEveryValue() {
        JournalEntryStatus.entries.forEach { status ->
            assertTrue(journalEntryStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun journalEntryStatusColor_isARealBootstrapHueForEveryValue() {
        JournalEntryStatus.entries.forEach { status ->
            val color = journalEntryStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    // Design decision D1: DRAFT = "warning" (not-yet-final, matches the existing POSTPONED-shaped
    // convention), POSTED = "success" (settled/terminal-good, matches HELD/RESOLVED/ADOPTED).
    @Test
    fun journalEntryStatusColor_draftIsWarningPostedIsSuccess() {
        assertEquals("warning", journalEntryStatusColor(JournalEntryStatus.DRAFT))
        assertEquals("success", journalEntryStatusColor(JournalEntryStatus.POSTED))
    }

    @Test
    fun journalEntryStatusLabel_draftIsEntwurfPostedIsGebucht() {
        assertEquals("Entwurf", journalEntryStatusLabel(JournalEntryStatus.DRAFT))
        assertEquals("Gebucht", journalEntryStatusLabel(JournalEntryStatus.POSTED))
    }
}
