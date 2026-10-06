package network.lapis.cloud.client.encounter

import network.lapis.cloud.shared.domain.ENCOUNTER_REACTION_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.EncounterReaction
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.option
import org.khronos.webgl.Uint8Array
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.62 -- the wire format of the reactions (trust boundary) and the clock-injected state around it. No DOM, no real time. */
class EncounterReactionsTest {
    private fun bytesOf(text: String): Uint8Array {
        val encoded = text.encodeToByteArray()
        val array = Uint8Array(encoded.size)
        encoded.forEachIndexed { index, byte -> array.asDynamic()[index] = byte.toInt() and 0xff }
        return array
    }

    // ── wire format ──────────────────────────────────────────────────────────────

    @Test
    fun everyReaction_roundTripsThroughTheWire() {
        EncounterReaction.entries.forEach { reaction ->
            assertEquals(reaction, EncounterReactionWire.decode(EncounterReactionWire.encode(reaction)), reaction.name)
        }
    }

    @Test
    fun theLongestReaction_fitsTheSizeCap_andCarriesOnlyTheReactionName() {
        val longest = EncounterReaction.entries.maxOf { EncounterReactionWire.encode(it).length }
        assertTrue(longest <= ENCOUNTER_REACTION_MAX_PAYLOAD_BYTES, "$longest bytes")
        assertEquals(20, EncounterReactionWire.encode(EncounterReaction.HAND_LOWERED).length)
        assertEquals(EncounterReaction.AMEN, EncounterReactionWire.decodeText("""{"r":"AMEN"}"""))
    }

    @Test
    fun onlyTheKeyR_isRead_anyOtherFieldIsIgnored() {
        // A forged sender or name in the payload is never looked at: the sender is the SDK identity of the packet.
        assertEquals(EncounterReaction.HAND, EncounterReactionWire.decodeText("""{"r":"HAND","sender":"someone-else","name":"x"}"""))
        assertNull(EncounterReactionWire.decodeText("""{"sender":"someone-else"}"""))
    }

    @Test
    fun unknownMalformedAndNonStringPayloads_decodeToNull_andNeverThrow() {
        listOf(
            """{"r":"WAVE"}""",
            """{"r":"amen"}""",
            """{"r":7}""",
            """{"r":null}""",
            """{"r":["AMEN"]}""",
            """["AMEN"]""",
            "\"AMEN\"",
            "AMEN",
            "{",
            "",
            "not json at all",
        ).forEach { text -> assertNull(EncounterReactionWire.decode(bytesOf(text)), text) }
    }

    @Test
    fun anOverlongPayload_isDroppedUnread() {
        val padded = """{"r":"AMEN","pad":"${"x".repeat(ENCOUNTER_REACTION_MAX_PAYLOAD_BYTES)}"}"""
        assertTrue(padded.length > ENCOUNTER_REACTION_MAX_PAYLOAD_BYTES)
        assertNull(EncounterReactionWire.decode(bytesOf(padded)))
        assertNull(EncounterReactionWire.decode(Uint8Array(64 * 1024)))
        // Invalid UTF-8 within the cap is no reaction either (and no exception).
        val invalidUtf8 = Uint8Array(4)
        listOf(0xff, 0xfe, 0xc0, 0x80).forEachIndexed { index, value -> invalidUtf8.asDynamic()[index] = value }
        assertNull(EncounterReactionWire.decode(invalidUtf8))
    }

    // ── sender-side limits ───────────────────────────────────────────────────────

    @Test
    fun amenIsLimitedToOnePerFiveSeconds_andTheHandToggleToOnePerTwo() {
        var clock = 1_000.0
        val throttle = EncounterReactionSendThrottle { clock }
        assertTrue(throttle.tryEvent())
        clock += 4_999
        assertFalse(throttle.tryEvent())
        clock += 1
        assertTrue(throttle.tryEvent())

        assertTrue(throttle.tryHandToggle())
        clock += 1_999
        assertFalse(throttle.tryHandToggle())
        clock += 1
        assertTrue(throttle.tryHandToggle())
        // the two limits are independent
        assertFalse(throttle.tryEvent())
    }

    // ── receiver-side limits ─────────────────────────────────────────────────────

    @Test
    fun moreThanTwoReactionsPerSecondFromOneSender_areDropped_others_are_unaffected() {
        var clock = 0.0
        val guard = EncounterReactionReceiveGuard { clock }
        assertTrue(guard.admit("a"))
        assertTrue(guard.admit("a"))
        assertFalse(guard.admit("a"))
        assertFalse(guard.admit("a"))
        assertTrue(guard.admit("b"), "another sender has its own budget")
        clock += 1_000
        assertTrue(guard.admit("a"), "the window moved on")
    }

    @Test
    fun theSenderTable_isBounded_andAFreshIdentityIsStillAdmitted() {
        var clock = 0.0
        val guard = EncounterReactionReceiveGuard { clock }
        repeat(500) { index ->
            clock += 1.0
            assertTrue(guard.admit("sender-$index"))
        }
        assertTrue(guard.admit("one-more"))
    }

    // ── raised hands ─────────────────────────────────────────────────────────────

    @Test
    fun hands_areListedInTheOrderTheyWentUp_andARenewalKeepsThePlace() {
        var clock = 0.0
        val hands = EncounterRaisedHands({ clock })
        hands.raise("anna")
        clock += 10_000
        hands.raise("ben")
        clock += 10_000
        hands.raise("anna") // renewal: still first
        assertEquals(listOf("anna", "ben"), hands.ordered)
        hands.lower("anna")
        assertEquals(listOf("ben"), hands.ordered)
        hands.raise("anna")
        assertEquals(listOf("ben", "anna"), hands.ordered, "a new hand goes to the end")
    }

    @Test
    fun aHandThatIsNotRenewed_lapsesAfterNinetySeconds_aRenewedOneStays() {
        var clock = 0.0
        val hands = EncounterRaisedHands({ clock })
        hands.raise("anna")
        hands.raise("ben")
        clock += 60_000
        hands.raise("ben") // renewed after 60 s
        clock += 30_001 // anna: 90.001 s without a renewal; ben: 30 s
        assertEquals(listOf("anna"), hands.expire())
        assertEquals(listOf("ben"), hands.ordered)
        assertTrue(hands.expire().isEmpty())
    }

    // ── amen announcements ───────────────────────────────────────────────────────

    @Test
    fun amenAnnouncements_areBundled_toOneEveryTenSeconds() {
        var clock = 0.0
        val announcer = EncounterEventAnnouncer({ clock })
        assertTrue(announcer.onEvent())
        repeat(20) {
            clock += 400
            assertFalse(announcer.onEvent(), "within the gap")
        }
        clock += 10_000
        assertTrue(announcer.onEvent())
    }

    // ── V1.9.67: configurable reactions ───────────────────────────────────────────────

    @Test
    fun admitReaction_dropsWhatTheRoomDoesNotAllow_andAlwaysLetsTheHandThrough() {
        val churchSet = setOf(EncounterReactionOption.HAND, EncounterReactionOption.AMEN)
        assertTrue(admitReaction(EncounterReaction.AMEN, churchSet))
        assertFalse(admitReaction(EncounterReaction.APPLAUSE, churchSet))
        assertFalse(admitReaction(EncounterReaction.HEART, churchSet))
        val assemblySet = setOf(EncounterReactionOption.HAND, EncounterReactionOption.APPLAUSE)
        assertTrue(admitReaction(EncounterReaction.APPLAUSE, assemblySet))
        assertFalse(admitReaction(EncounterReaction.AMEN, assemblySet))
        // HAND_LOWERED belongs to the hand: it passes in every room that has a hand (all of them)
        listOf(churchSet, assemblySet, setOf(EncounterReactionOption.HAND)).forEach {
            assertTrue(admitReaction(EncounterReaction.HAND, it))
            assertTrue(admitReaction(EncounterReaction.HAND_LOWERED, it))
        }
    }

    @Test
    fun anUnknownOrOldReactionName_decodesToNull_andNeverThrows() {
        listOf("THUMBS_UP", "", "amen", "HAND ", "null").forEach {
            assertNull(EncounterReactionWire.decodeText("{\"r\":\"$it\"}"), it)
        }
        assertNull(EncounterReactionWire.decodeText("{\"r\":5}"))
        assertNull(EncounterReactionWire.decodeText("[]"))
        assertEquals(EncounterReaction.APPLAUSE, EncounterReactionWire.decodeText("{\"r\":\"APPLAUSE\"}"))
        assertEquals(EncounterReaction.HEART, EncounterReactionWire.decodeText("{\"r\":\"HEART\"}"))
    }

    @Test
    fun theLongestPayload_stillFitsTheLimit() {
        EncounterReaction.entries.forEach {
            assertTrue(
                EncounterReactionWire.encode(it).length <= network.lapis.cloud.shared.domain.ENCOUNTER_REACTION_MAX_PAYLOAD_BYTES,
                it.name,
            )
        }
    }

    @Test
    fun theEventReactions_shareOneBudget_andTheHandIsNeverBlockedByThem() {
        var clock = 0.0
        val throttle = EncounterReactionSendThrottle { clock }
        assertTrue(throttle.tryEvent())
        assertFalse(throttle.tryEvent(), "applause right after an amen shares the budget")
        assertTrue(throttle.tryHandToggle(), "the hand is a separate budget")
        clock += EncounterReactionSendThrottle.EVENT_GAP_MS
        assertTrue(throttle.tryEvent())
    }

    @Test
    fun everyOption_hasItsWireReaction_andBack() {
        EncounterReactionOption.entries.forEach { assertEquals(it, it.toWire().option()) }
        assertEquals(EncounterReactionOption.HAND, EncounterReaction.HAND_LOWERED.option())
    }
}
