package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MemberFinderBand
import network.lapis.cloud.shared.domain.MemberFinderInterest
import network.lapis.cloud.shared.domain.MemberFinderOutgoingState
import network.lapis.cloud.shared.domain.MemberFinderPurpose
import network.lapis.cloud.shared.rpc.MemberFinderConsentOutdatedException
import network.lapis.cloud.shared.rpc.MemberFinderLimitReachedException
import network.lapis.cloud.shared.rpc.MemberFinderRateLimitedException
import network.lapis.cloud.shared.rpc.NotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.145 -- the pure texts and rules of the member finder client. */
class MemberFinderLabelsTest {
    private fun plain(text: String) = resolvedAttributeText(text)

    @Test
    fun theBand_isOnlyEverAWord_neverANumber() {
        assertEquals("5 bis 9 Teilnehmende", plain(memberFinderBandLabel(MemberFinderBand.B5_9)))
        assertEquals("10 bis 19 Teilnehmende", plain(memberFinderBandLabel(MemberFinderBand.B10_19)))
        assertEquals("20 oder mehr Teilnehmende", plain(memberFinderBandLabel(MemberFinderBand.B20_PLUS)))
        assertTrue(plain(memberFinderBandLabel(null)).startsWith("Noch nicht sichtbar"))
    }

    @Test
    fun aSingleInterest_fixesThePurpose_andBothOffersTwo() {
        assertEquals(emptyList(), memberFinderPurposesFor(null))
        assertEquals(listOf(MemberFinderPurpose.GROUP_FOUNDING), memberFinderPurposesFor(MemberFinderInterest.GROUP_FOUNDING))
        assertEquals(listOf(MemberFinderPurpose.MEETUP), memberFinderPurposesFor(MemberFinderInterest.MEETUP))
        assertEquals(2, memberFinderPurposesFor(MemberFinderInterest.BOTH).size)
    }

    @Test
    fun everyPurposeAndStateHasItsOwnText() {
        MemberFinderPurpose.entries.forEach {
            assertTrue(
                plain(memberFinderRequestTemplate(it)).startsWith("Ein Mitglied aus Ihrer Region"),
            )
        }
        assertEquals(
            MemberFinderPurpose.entries.size,
            MemberFinderPurpose.entries
                .map { plain(memberFinderPurposeLabel(it)) }
                .toSet()
                .size,
        )
        assertEquals(
            MemberFinderOutgoingState.entries.size,
            MemberFinderOutgoingState.entries
                .map { plain(memberFinderOutgoingStateLabel(it)) }
                .toSet()
                .size,
        )
        assertEquals("Beides", plain(memberFinderInterestLabel(MemberFinderInterest.BOTH)))
    }

    @Test
    fun theNoteCount_followsTheServersNormalisation() {
        assertEquals(0, memberFinderNoteCount("   "))
        assertEquals(3, memberFinderNoteCount("a\n\nb"))
        assertEquals(3, memberFinderNoteCount("  a   b "))
        assertTrue(memberFinderNoteSendable("x".repeat(200)))
        assertFalse(memberFinderNoteSendable("x".repeat(201)))
        assertEquals(201, memberFinderNoteCount("x".repeat(201)), "over the limit the raw length is shown")
        assertTrue(memberFinderNoteCount("x".repeat(MEMBER_FINDER_NOTE_WARN_AT)) >= MEMBER_FINDER_NOTE_WARN_AT)
    }

    @Test
    fun everyTypedRefusal_hasAFixedSentence_andOtherFailuresHaveNone() {
        assertNotNull(memberFinderErrorText(MemberFinderConsentOutdatedException()))
        assertNotNull(memberFinderErrorText(MemberFinderLimitReachedException()))
        assertNotNull(memberFinderErrorText(MemberFinderRateLimitedException()))
        assertNull(memberFinderErrorText(NotFoundException("gone")), "a missing request is handled by the caller")
        assertNull(memberFinderErrorText(IllegalStateException("x")))
    }
}
