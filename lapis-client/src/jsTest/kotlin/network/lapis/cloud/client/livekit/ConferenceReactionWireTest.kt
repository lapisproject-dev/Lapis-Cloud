package network.lapis.cloud.client.livekit

import network.lapis.cloud.shared.domain.ConferenceReaction
import org.khronos.webgl.Uint8Array
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.127 -- the reaction wire format and its two flood guards. */
class ConferenceReactionWireTest {
    private fun bytes(text: String): Uint8Array = TextEncoder().encode(text)

    @Test
    fun allEightReactionsRoundTrip() {
        ConferenceReaction.entries.forEach { reaction ->
            assertEquals(reaction, ConferenceReactionWire.decode(ConferenceReactionWire.encode(reaction)))
        }
        assertEquals(8, ConferenceReaction.entries.size)
        listOf("LAUGH", "ASTONISHED", "QUESTION").forEach { name ->
            assertEquals(ConferenceReaction.valueOf(name), ConferenceReactionWire.decodeText("""{"r":"$name"}"""))
        }
        // the longest payload stays under the 32-byte cap
        val longest = ConferenceReaction.entries.maxOf { ConferenceReactionWire.encode(it).length }
        assertTrue(longest <= 32)
    }

    @Test
    fun theEncodedPayloadIsExactlyTheOneKey() {
        assertEquals("""{"r":"APPLAUSE"}""", TextDecoder().decode(ConferenceReactionWire.encode(ConferenceReaction.APPLAUSE)))
    }

    @Test
    fun theHandIsNoReaction_andUnknownNamesAreDropped() {
        assertNull(ConferenceReactionWire.decodeText("""{"r":"HAND"}"""))
        assertNull(ConferenceReactionWire.decodeText("""{"r":"HAND_LOWERED"}"""))
        assertNull(ConferenceReactionWire.decodeText("""{"r":"applause"}"""))
        assertNull(ConferenceReactionWire.decodeText("""{"r":"BOMB"}"""))
        // encounter-only names are no conference reactions
        assertNull(ConferenceReactionWire.decodeText("""{"r":"CRY"}"""))
    }

    @Test
    fun anExtraFieldMakesThePayloadInvalid_soNoForgedSenderIsEverLookedAt() {
        assertNull(ConferenceReactionWire.decodeText("""{"r":"HEART","sender":"x"}"""))
        assertNull(ConferenceReactionWire.decodeText("""{"r":"HEART","name":"Anna"}"""))
        assertNull(ConferenceReactionWire.decodeText("""{"x":"HEART"}"""))
        assertNull(ConferenceReactionWire.decodeText("""{}"""))
    }

    @Test
    fun aPayloadOver32Bytes_isRefusedUnread() {
        val padded = """{"r":"APPLAUSE"}""" + " ".repeat(17)
        assertEquals(33, padded.length)
        assertNull(ConferenceReactionWire.decodeText(padded))
        assertNull(ConferenceReactionWire.decode(bytes(padded)))
        assertEquals(ConferenceReaction.APPLAUSE, ConferenceReactionWire.decodeText("""{"r":"APPLAUSE"}""" + " ".repeat(16)))
    }

    @Test
    fun garbageNeverThrows() {
        assertNull(ConferenceReactionWire.decodeText(""))
        assertNull(ConferenceReactionWire.decodeText("not json"))
        assertNull(ConferenceReactionWire.decodeText("[1]"))
        assertNull(ConferenceReactionWire.decodeText("""{"r":1}"""))
        assertNull(ConferenceReactionWire.decodeText("""{"r":null}"""))
        assertNull(ConferenceReactionWire.decodeText("""{"r":["APPLAUSE"]}"""))
        // invalid UTF-8 (a lone continuation byte)
        val invalid = Uint8Array(arrayOf<Byte>(0x7B, 0x22, 0x72, 0x22, 0x3A, 0x22, 0x80.toByte(), 0x22, 0x7D))
        assertNull(ConferenceReactionWire.decode(invalid))
    }

    @Test
    fun theSendThrottle_allowsOnePerSecond() {
        var now = 0.0
        val throttle = ConferenceReactionSendThrottle { now }
        assertTrue(throttle.tryEvent())
        now = 999.0
        assertFalse(throttle.tryEvent())
        now = 1_000.0
        assertTrue(throttle.tryEvent())
    }

    @Test
    fun theReceiveGuard_dropsMoreThanFivePerFiveSecondsPerSender() {
        var now = 0.0
        val guard = ConferenceReactionReceiveGuard({ now })
        repeat(5) { assertTrue(guard.admit("a"), "reaction $it") }
        assertFalse(guard.admit("a"))
        // another sender has a budget of its own
        assertTrue(guard.admit("b"))
        now = 5_000.0
        assertTrue(guard.admit("a"))
    }

    @Test
    fun theReceiveGuard_isBoundedToSixtyFourSenders() {
        var now = 0.0
        val guard = ConferenceReactionReceiveGuard({ now }, maxSenders = 64)
        repeat(5) { guard.admit("first") }
        assertFalse(guard.admit("first"))
        repeat(64) { guard.admit("other-$it") }
        // "first" was the oldest and was forgotten: its budget is fresh again
        now += 1.0
        assertTrue(guard.admit("first"))
    }
}
