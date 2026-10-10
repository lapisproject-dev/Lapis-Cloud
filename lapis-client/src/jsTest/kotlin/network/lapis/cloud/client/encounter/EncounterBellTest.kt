package network.lapis.cloud.client.encounter

import network.lapis.cloud.client.livekit.encounterBellPacketAccepted
import network.lapis.cloud.shared.domain.ENCOUNTER_BELL_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.ENCOUNTER_BELL_PAYLOAD
import network.lapis.cloud.shared.domain.EncounterReactionOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.96 -- the pure parts of the bell: the window timer, the sound decision, the packet rule, the bar order and the sound constants. */
class EncounterBellTest {
    @Test
    fun theTimer_showsFirst_extendsInsideTheWindow_andShowsAgainAfterIt() {
        var now = 1_000.0
        val timer = EncounterBellTimer(now = { now }, displayMs = 5_000)
        assertEquals(EncounterBellOutcome.SHOW, timer.onPacket())
        now += 4_999.0
        assertEquals(EncounterBellOutcome.EXTEND, timer.onPacket())
        now += 4_999.0
        assertEquals(EncounterBellOutcome.EXTEND, timer.onPacket(), "the window was restarted by the extension")
        now += 5_000.0
        assertEquals(EncounterBellOutcome.SHOW, timer.onPacket())
    }

    @Test
    fun theSoundDecision_needsTheSwitchAndAVisiblePage() {
        assertTrue(encounterBellShouldSound(soundOn = true, pageVisible = true))
        assertFalse(encounterBellShouldSound(soundOn = true, pageVisible = false))
        assertFalse(encounterBellShouldSound(soundOn = false, pageVisible = true))
        assertFalse(encounterBellShouldSound(soundOn = false, pageVisible = false))
    }

    @Test
    fun aBellPacket_isAcceptedOnlyFromTheServerAndOfTheFixedSize() {
        assertFalse(
            encounterBellPacketAccepted(fromParticipant = true, payloadLength = ENCOUNTER_BELL_PAYLOAD.length),
            "a participant cannot forge it",
        )
        assertFalse(encounterBellPacketAccepted(fromParticipant = false, payloadLength = 0))
        assertFalse(encounterBellPacketAccepted(fromParticipant = false, payloadLength = ENCOUNTER_BELL_MAX_PAYLOAD_BYTES + 1))
        assertTrue(encounterBellPacketAccepted(fromParticipant = false, payloadLength = ENCOUNTER_BELL_PAYLOAD.length))
        assertEquals(7, ENCOUNTER_BELL_PAYLOAD.length)
    }

    @Test
    fun inTheOverflowOrder_theBellMovesIntoTheSheetBeforeTheBlessing_andBothBeforeTheChat() {
        val order = encounterOverflowOrder(EncounterReactionOption.entries, blessing = true, bell = true)
        val bell = order.indexOf(EncounterControlSlot.Bell)
        val blessing = order.indexOf(EncounterControlSlot.Blessing)
        val chat = order.indexOf(EncounterControlSlot.Chat)
        assertTrue(bell in 0 until blessing, "bell before blessing: $order")
        assertTrue(blessing < chat, "blessing before chat: $order")
        assertTrue(order.indexOf(EncounterControlSlot.CloseDoors) < bell)
        assertFalse(EncounterControlSlot.Bell in encounterOverflowOrder(EncounterReactionOption.entries, blessing = true))
        assertFalse(EncounterControlSlot.Bell in encounterOverflowOrder(EncounterReactionOption.entries))
    }

    @Test
    fun theBell_neverStandsNextToAModerationControl() {
        val bell = EncounterControlSlot.Bell
        val broadcast = EncounterControlSlot.Broadcast
        assertTrue(
            bellNeverAdjacentToModeration(
                listOf(EncounterControlSlot.Hand, bell, EncounterControlSlot.Blessing, EncounterControlSlot.Chat, broadcast),
            ),
        )
        assertFalse(bellNeverAdjacentToModeration(listOf(bell, broadcast)))
        assertFalse(bellNeverAdjacentToModeration(listOf(broadcast, bell)))
        assertTrue(bellNeverAdjacentToModeration(emptyList()))
    }

    @Test
    fun theBellIsInTheLiturgyGroup() {
        assertEquals(EncounterControlGroup.LITURGY, encounterControlGroup(EncounterControlSlot.Bell))
    }

    @Test
    fun theSoundConstants_keepTheLevelQuiet_andTheFileUrlsPlain() {
        assertTrue(ENCOUNTER_BELL_PEAK_GAIN > 0.0 && ENCOUNTER_BELL_PEAK_GAIN <= 0.25, "never louder than the synthesis was")
        assertTrue(ENCOUNTER_BLESSING_GAIN > 0.0 && ENCOUNTER_BLESSING_GAIN < ENCOUNTER_BELL_PEAK_GAIN, "the blessing is quieter")
        assertEquals(0.25, ENCOUNTER_SOUND_FADE_S)
        assertEquals(1500, ENCOUNTER_SOUND_MAX_LATENESS_MS)
        assertTrue(ENCOUNTER_PROBE_LENGTH_S > 0.0 && ENCOUNTER_PROBE_FADE_S > 0.0)
        assertTrue(ENCOUNTER_SOUND_MAX_BYTES in 100_000..1_000_000, "a limit that holds the 48 KB files and stops a large answer")
        listOf(ENCOUNTER_CALL_BELL_URL, ENCOUNTER_BLESSING_BELL_URL).forEach { url ->
            assertTrue(url.startsWith("/assets/encounter-sounds-v1/"), url)
            assertFalse('?' in url || '#' in url, "no query and no fragment: $url")
            assertTrue(url.endsWith(".mp3"), url)
        }
        assertEquals(2, setOf(ENCOUNTER_CALL_BELL_URL, ENCOUNTER_BLESSING_BELL_URL).size)
    }

    @Test
    fun theTerms_decideWhetherARoomHasABell_andNothingElseDoes() {
        assertNotNull(termsFor(network.lapis.cloud.shared.domain.EncounterProfile.CHURCH_SERVICE).bellLabel())
        assertEquals(null, termsFor(network.lapis.cloud.shared.domain.EncounterProfile.ASSEMBLY).bellLabel())
    }
}
