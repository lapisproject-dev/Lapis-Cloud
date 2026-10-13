package network.lapis.cloud.client.livekit

import network.lapis.cloud.shared.domain.ConferenceHandQueueDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.127 -- the strict hands packet, the queue view with its epoch rule, and the moderators' announcement throttle. */
class ConferenceHandQueueTest {
    private val epoch = "0123456789abcdef"
    private val a = "11111111-1111-1111-1111-111111111111"
    private val b = "22222222-2222-2222-2222-222222222222"
    private val guest = "evg-33333333-3333-3333-3333-333333333333"

    private fun packet(
        ids: String = "\"$a\",\"$b\"",
        seq: String = "3",
        e: String = epoch,
        v: String = "1",
        extra: String = "",
    ) = """{"v":$v,"e":"$e","seq":$seq,"ids":[$ids]$extra}""".encodeToByteArray()

    // -- packet ------------------------------------------------------------------------------------------------------------------------

    @Test
    fun aValidPacketBecomesTheDto() {
        val dto = assertNotNull(conferenceHandsPacket(fromParticipant = false, payload = packet(ids = "\"$a\",\"$guest\"")))
        assertEquals(ConferenceHandQueueDto(epoch = epoch, seq = 3, identities = listOf(a, guest)), dto)
        assertEquals(emptyList(), conferenceHandsPacket(false, packet(ids = ""))?.identities)
    }

    @Test
    fun aPacketWithASendingParticipantIsAForgery() {
        assertNull(conferenceHandsPacket(fromParticipant = true, payload = packet()))
    }

    @Test
    fun everyDeviationIsDropped_andNothingThrows() {
        assertNull(conferenceHandsPacket(false, packet(v = "2")))
        assertNull(conferenceHandsPacket(false, packet(v = "\"1\"")))
        assertNull(conferenceHandsPacket(false, packet(extra = ",\"x\":1")))
        assertNull(conferenceHandsPacket(false, packet(e = "XYZ")))
        assertNull(conferenceHandsPacket(false, packet(e = "0123456789ABCDEF")))
        assertNull(conferenceHandsPacket(false, packet(seq = "-1")))
        assertNull(conferenceHandsPacket(false, packet(seq = "\"3\"")))
        assertNull(conferenceHandsPacket(false, packet(seq = "1.5")))
        assertNull(conferenceHandsPacket(false, packet(ids = "\"$a\",\"$a\"")))
        assertNull(conferenceHandsPacket(false, packet(ids = "\"not-an-id\"")))
        assertNull(conferenceHandsPacket(false, packet(ids = "\"ABCDEFAB-1111-1111-1111-111111111111\"")))
        assertNull(conferenceHandsPacket(false, packet(ids = "1")))
        assertNull(conferenceHandsPacket(false, "".encodeToByteArray()))
        assertNull(conferenceHandsPacket(false, "garbage".encodeToByteArray()))
        assertNull(conferenceHandsPacket(false, """{"v":1,"e":"$epoch","seq":1}""".encodeToByteArray()))
        assertNull(conferenceHandsPacket(false, """[1]""".encodeToByteArray()))
    }

    @Test
    fun a101stIdentityAndAnOversizedPacketAreDropped() {
        fun uuid(i: Int) = "00000000-0000-0000-0000-" + i.toString(16).padStart(12, '0')
        val hundred = (1..100).joinToString(",") { "\"${uuid(it)}\"" }
        assertEquals(100, conferenceHandsPacket(false, packet(ids = hundred))?.identities?.size)
        val tooMany = (1..101).joinToString(",") { "\"${uuid(it)}\"" }
        assertNull(conferenceHandsPacket(false, packet(ids = tooMany)))
        assertNull(conferenceHandsPacket(false, packet(ids = "\"" + "a".repeat(7000) + "\"")))
    }

    @Test
    fun theWorstCasePacketOfTheServer_isAccepted() {
        val hundredGuests = (1..100).joinToString(",") { "\"evg-00000000-0000-0000-0000-" + it.toString(16).padStart(12, '0') + "\"" }
        val bytes = packet(ids = hundredGuests)
        assertTrue(bytes.size <= 6144, "size ${bytes.size}")
        assertEquals(100, conferenceHandsPacket(false, bytes)?.identities?.size)
    }

    // -- view --------------------------------------------------------------------------------------------------------------------------

    private fun dto(
        seq: Long,
        vararg ids: String,
        e: String = epoch,
    ) = ConferenceHandQueueDto(epoch = e, seq = seq, identities = ids.toList())

    @Test
    fun anOlderOrEqualSeqInTheSameEpoch_isRejected_aNewEpochAlwaysWins() {
        val view = ConferenceHandQueueView(self = a)
        assertTrue(view.accept(dto(5, a, b)))
        assertEquals(listOf(a, b), view.order)
        assertEquals(false, view.accept(dto(4, b)))
        assertEquals(false, view.accept(dto(5, b)))
        assertEquals(listOf(a, b), view.order)
        assertTrue(view.accept(dto(6, b, a)))
        assertEquals(2, view.positionOfSelf())
        // the server restarted: seq starts again, the epoch differs
        assertTrue(view.accept(dto(0, e = "fedcba9876543210")))
        assertEquals(emptyList(), view.order)
        assertNull(view.positionOfSelf())
    }

    @Test
    fun aLateJoinerRestoresTheOrderFromOneSnapshot() {
        val view = ConferenceHandQueueView(self = guest)
        view.accept(dto(41, a, b, guest))
        assertEquals(listOf(a, b, guest), view.order)
        assertEquals(3, view.positionOfSelf())
    }

    @Test
    fun sameEpochAndTheHandIsGone_meansLoweredByOthers_neverARaiseAgain() {
        val view = ConferenceHandQueueView(self = a)
        view.accept(dto(2, a))
        view.accept(dto(3, b))
        assertEquals(SelfHandAction.MARK_LOWERED_BY_OTHERS, view.reconcileSelf(believedRaised = true, raisedInEpoch = epoch))
    }

    @Test
    fun aNewEpochAndTheHandIsGone_meansTheServerRestarted_soTheHandIsRaisedAgain() {
        val view = ConferenceHandQueueView(self = a)
        view.accept(dto(2, a))
        view.accept(dto(0, e = "fedcba9876543210"))
        assertEquals(SelfHandAction.RAISE_AGAIN, view.reconcileSelf(believedRaised = true, raisedInEpoch = epoch))
        assertEquals(SelfHandAction.RAISE_AGAIN, view.reconcileSelf(believedRaised = true, raisedInEpoch = null))
    }

    @Test
    fun nothingToDoWhenTheHandIsInTheQueueOrNotRaised() {
        val view = ConferenceHandQueueView(self = a)
        view.accept(dto(2, a))
        assertEquals(SelfHandAction.NONE, view.reconcileSelf(believedRaised = true, raisedInEpoch = epoch))
        assertEquals(SelfHandAction.NONE, view.reconcileSelf(believedRaised = false, raisedInEpoch = null))
        view.accept(dto(3, b))
        assertEquals(SelfHandAction.NONE, view.reconcileSelf(believedRaised = false, raisedInEpoch = null))
    }

    // -- announcements -----------------------------------------------------------------------------------------------------------------

    @Test
    fun aFirstHandIsAnnouncedAtOnce_theNextOnesAreCollectedAndSummarised() {
        var now = 100_000.0
        val throttle = HandAnnouncementThrottle({ now })
        assertEquals("Hand gehoben: Anna", throttle.onRaised(listOf("Anna")))
        now += 2_000
        assertNull(throttle.onRaised(listOf("Ben")))
        assertNull(throttle.onRaised(listOf("Cora")))
        assertEquals(2, throttle.pending)
        assertNull(throttle.flush(), "the gap is not over")
        now += 8_000
        assertEquals("2 neue Wortmeldungen", throttle.flush())
        assertEquals(0, throttle.pending)
        assertNull(throttle.flush())
    }

    @Test
    fun aSingleCollectedHandIsNamed_andTheDelayIsReported() {
        var now = 0.0
        val throttle = HandAnnouncementThrottle({ now })
        throttle.onRaised(listOf("Anna"))
        now = 4_000.0
        assertNull(throttle.onRaised(listOf("Ben")))
        assertEquals(6_000.0, throttle.delayUntilFlushMs())
        now = 10_000.0
        assertEquals("Hand gehoben: Ben", throttle.flush())
        assertNull(throttle.delayUntilFlushMs())
    }

    @Test
    fun clearDropsWhatWaits() {
        var now = 0.0
        val throttle = HandAnnouncementThrottle({ now })
        throttle.onRaised(listOf("Anna"))
        now = 1.0
        throttle.onRaised(listOf("Ben"))
        throttle.clear()
        now = 20_000.0
        assertNull(throttle.flush())
    }
}
