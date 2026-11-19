package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.MeetingRewardBlockedReason
import network.lapis.cloud.shared.domain.MeetingRewardLineReason
import network.lapis.cloud.shared.domain.MeetingRewardOwnLineDto
import network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeKind
import network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeStatus
import network.lapis.cloud.shared.domain.MeetingRewardUnavailableReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** V1.9.316 -- the pure texts of the LTR recognition for Sitzungen: every enum value has a text, a refusal never shows a raw server text. */
class MeetingRewardClientSupportTest {
    @Test
    fun everyEnumValueHasANonBlankLabel() {
        MeetingRewardLineReason.entries.forEach { assertTrue(meetingRewardReasonLabel(it).isNotBlank(), "reason $it") }
        MeetingRewardUnavailableReason.entries.forEach { assertTrue(meetingRewardUnavailableText(it).isNotBlank(), "unavailable $it") }
        MeetingRewardBlockedReason.entries.forEach { assertTrue(meetingRewardBlockedText(it).isNotBlank(), "blocked $it") }
        MeetingRewardPolicyChangeKind.entries.forEach { assertTrue(meetingRewardChangeKindLabel(it).isNotBlank(), "kind $it") }
        MeetingRewardPolicyChangeStatus.entries.forEach { assertTrue(meetingRewardChangeStatusLabel(it).isNotBlank(), "status $it") }
    }

    @Test
    fun aRefusalGetsItsGermanTextAndAnUnknownOneOnlyTheFallback() {
        assertEquals("Dafür ist ein zweiter Admin nötig.", meetingRewardErrorText("A second administrator is required"))
        assertEquals("Die Abrechnung wurde bereits freigegeben.", meetingRewardErrorText("The settlement was already released"))
        assertEquals(meetingRewardFallbackText(), meetingRewardErrorText(""))
        assertEquals(meetingRewardFallbackText(), meetingRewardErrorText(null))
        // a raw server text is never echoed
        val unknown = meetingRewardErrorText("SQL error near Tender-Test 12.50")
        assertEquals(meetingRewardFallbackText(), unknown)
        assertNotEquals("SQL error near Tender-Test 12.50", unknown)
    }

    @Test
    fun theLedgerTextNamesTheCommitteeTheDateAndOnlyAPositiveBonus() {
        val withBonus =
            meetingRewardLedgerText(
                MeetingRewardOwnLineDto("m", "Vorstand", LocalDate(2026, 10, 20), null, 15.0.toDecimal(), 5.0.toDecimal(), "l"),
            )
        assertTrue("Vorstand" in withBonus)
        assertTrue("Zuschlag" in withBonus)
        val without =
            meetingRewardLedgerText(
                MeetingRewardOwnLineDto("m", "Vorstand", LocalDate(2026, 10, 20), null, 10.0.toDecimal(), 0.0.toDecimal(), "l"),
            )
        assertTrue("Vorstand" in without)
        assertTrue("Zuschlag" !in without)
        assertTrue("Anerkennung für Sitzungsteilnahme" in without)
    }

    @Test
    fun theWordingIsAlwaysRecognitionNeverAPaymentWord() {
        val forbidden = listOf("Vergütung", "Honorar", "Lohn", "Gehalt")
        val texts =
            MeetingRewardLineReason.entries.map { meetingRewardReasonLabel(it) } +
                MeetingRewardUnavailableReason.entries.map { meetingRewardUnavailableText(it) } +
                MeetingRewardBlockedReason.entries.map { meetingRewardBlockedText(it) }
        texts.forEach { text -> forbidden.forEach { assertTrue(it !in text, "\"$text\" contains \"$it\"") } }
    }
}
