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

/** V1.9.96 -- the pure parts of the bell: the window timer, the sound decision, the packet rule, the bar order and the WebAudio recipe. */
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
    fun theWebAudioRecipe_isQuietAndShort() {
        assertTrue(ENCOUNTER_BELL_PEAK_GAIN > 0.0 && ENCOUNTER_BELL_PEAK_GAIN <= 1.0, "never louder than full scale")
        assertEquals(ENCOUNTER_BELL_PARTIAL_RATIOS.size, ENCOUNTER_BELL_PARTIAL_AMPLITUDES.size)
        assertTrue(encounterBellTotalSeconds() < 5.0, "shorter than the sign: ${encounterBellTotalSeconds()}")
        assertTrue(encounterBellTotalSeconds() > 4.0)
        assertTrue(ENCOUNTER_BELL_PARTIAL_AMPLITUDES.all { it > 0.0 })
    }

    @Test
    fun theTerms_decideWhetherARoomHasABell_andNothingElseDoes() {
        assertNotNull(termsFor(network.lapis.cloud.shared.domain.EncounterProfile.CHURCH_SERVICE).bellLabel())
        assertEquals(null, termsFor(network.lapis.cloud.shared.domain.EncounterProfile.ASSEMBLY).bellLabel())
    }
}
