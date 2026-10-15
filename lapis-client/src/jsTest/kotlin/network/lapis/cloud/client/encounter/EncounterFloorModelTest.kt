package network.lapis.cloud.client.encounter

import network.lapis.cloud.shared.domain.EncounterFloorDto
import network.lapis.cloud.shared.domain.EncounterFloorSignal
import network.lapis.cloud.shared.domain.EncounterProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.138 -- the floor state machine without a DOM: the countdown comes from the monotonic clock only, exactly three sentences are
 * announced over a floor's life, the microphone ends with the floor, and a client never decides anything on its own.
 */
class EncounterFloorModelTest {
    private class Recorder : EncounterFloorHost {
        var changes = 0
        val announced = mutableListOf<String>()
        var handsLowered = 0
        var microphoneEnds = 0

        override fun floorChanged() {
            changes++
        }

        override fun announceFloor(sentence: String) {
            announced += sentence
        }

        override fun lowerOwnHand() {
            handsLowered++
        }

        override fun endFloorMicrophone() {
            microphoneEnds++
        }
    }

    private var now = 10_000.0
    private val host = Recorder()
    private val terms = termsFor(EncounterProfile.CHURCH_SERVICE)
    private val model = EncounterFloorModel(monotonicNow = { now }, terms = terms, host = host)

    private fun tick(seconds: Int) {
        repeat(seconds) {
            now += 1_000.0
            model.onTick()
        }
    }

    @Test
    fun anOnPacket_startsTheFloorForSomebodyElse_andAnnouncesItOnce() {
        model.onSignal(EncounterFloorSignal.On(118))
        assertTrue(model.view.active)
        assertFalse(model.view.self)
        assertEquals(118, model.view.remainingSeconds)
        assertEquals(listOf("Wort erteilt"), host.announced)
        model.onSignal(EncounterFloorSignal.On(100))
        assertEquals(1, host.announced.size, "a renewal is no new floor")
        assertEquals(100, model.view.remainingSeconds)
        assertNull(model.currentGrant, "only a YOU packet makes a grant")
    }

    @Test
    fun aYouPacket_startsTheFloorForTheViewer_withAGrant_andLowersTheHand() {
        model.onSignal(EncounterFloorSignal.You(120))
        assertTrue(model.view.active && model.view.self)
        assertNotNull(model.currentGrant)
        assertEquals(120, model.currentGrant!!.remainingSeconds)
        assertEquals(1, host.handsLowered)
        assertEquals(listOf("Sie haben das Wort"), host.announced)
    }

    @Test
    fun theBroadcastThatFollowsTheYouPacket_keepsTheViewersFloor() {
        model.onSignal(EncounterFloorSignal.You(120))
        model.onSignal(EncounterFloorSignal.On(120))
        assertTrue(model.view.self, "the holder also receives the broadcast")
        assertEquals(1, host.announced.size)
        assertEquals(1, host.handsLowered)
    }

    @Test
    fun theCountdownIsTheMonotonicClockOnly_roundedUp_andNeverNegative() {
        model.onSignal(EncounterFloorSignal.On(120))
        now += 500.0
        assertEquals(120, model.view.remainingSeconds)
        now += 600.0
        assertEquals(119, model.view.remainingSeconds)
        now += 200_000.0
        assertEquals(0, model.view.remainingSeconds)
    }

    @Test
    fun exactlyThreeSentencesOverAFloorsLife_notOnePerSecond() {
        model.onSignal(EncounterFloorSignal.On(15))
        tick(14)
        assertEquals(listOf("Wort erteilt", "Noch 10 Sekunden"), host.announced)
        assertTrue(model.view.final, "the last ten seconds are emphasised")
        tick(1)
        assertFalse(model.view.active, "at 0 the floor is over")
        assertEquals(listOf("Wort erteilt", "Noch 10 Sekunden", "Wort beendet"), host.announced)
        tick(5)
        assertEquals(3, host.announced.size, "nothing more after the end")
    }

    @Test
    fun theFinalPhase_startsAtTenSeconds_notBefore() {
        model.onSignal(EncounterFloorSignal.On(30))
        tick(19)
        assertFalse(model.view.final, "11 seconds left")
        tick(1)
        assertTrue(model.view.final, "10 seconds left")
    }

    @Test
    fun anOffPacket_endsTheFloor_stopsTheViewersMicrophone_andAnnouncesTheEndOnce() {
        model.onSignal(EncounterFloorSignal.You(120))
        model.onMicrophoneSwitched(true)
        assertTrue(model.view.microphoneOn)
        model.onSignal(EncounterFloorSignal.Off)
        assertFalse(model.view.active)
        assertNull(model.currentGrant)
        assertEquals(1, host.microphoneEnds)
        assertEquals("Wort beendet", host.announced.last())
        val count = host.announced.size
        model.onSignal(EncounterFloorSignal.Off)
        assertEquals(count, host.announced.size, "an off packet without a floor says nothing")
        assertEquals(1, host.microphoneEnds)
    }

    @Test
    fun whenTheTimeIsUp_theViewersMicrophoneEnds_evenWithoutAnOffPacket() {
        model.onSignal(EncounterFloorSignal.You(3))
        model.onMicrophoneSwitched(true)
        tick(3)
        assertFalse(model.view.active)
        assertEquals(1, host.microphoneEnds)
        assertNull(model.currentGrant)
    }

    @Test
    fun somebodyElsesEnd_doesNotTouchTheViewersMicrophone() {
        model.onSignal(EncounterFloorSignal.On(2))
        tick(2)
        assertEquals(0, host.microphoneEnds)
        assertEquals("Wort beendet", host.announced.last())
    }

    @Test
    fun losingTheMicrophonePermission_endsTheViewersFloorAtOnce() {
        model.onSignal(EncounterFloorSignal.You(100))
        model.onMicrophonePermission(true)
        assertTrue(model.view.active)
        model.onMicrophonePermission(false)
        assertFalse(model.view.active)
        assertEquals(1, host.microphoneEnds)
    }

    @Test
    fun aPermissionThatIsOffWithoutAFloor_orWhileSomebodyElseSpeaks_changesNothing() {
        model.onMicrophonePermission(false)
        model.onSignal(EncounterFloorSignal.On(100))
        model.onMicrophonePermission(false)
        assertTrue(model.view.active)
        assertEquals(0, host.microphoneEnds)
    }

    @Test
    fun anExtension_resetsTheTenSecondsSentence_forTheNewLongerFloor() {
        model.onSignal(EncounterFloorSignal.On(12))
        tick(3)
        assertEquals(2, host.announced.size)
        model.onSignal(EncounterFloorSignal.On(70))
        tick(61)
        assertEquals(3, host.announced.size, "ten seconds are announced again after an extension")
    }

    @Test
    fun theSnapshotOfTheServer_isMappedOntoTheSameSignals() {
        model.onSnapshot(EncounterFloorDto(active = true, remainingSeconds = 50, self = false))
        assertTrue(model.view.active && !model.view.self)
        model.onSnapshot(EncounterFloorDto(active = true, remainingSeconds = 50, self = true))
        assertTrue(model.view.self)
        model.onSnapshot(EncounterFloorDto(active = false, remainingSeconds = 0))
        assertFalse(model.view.active)
        model.onSnapshot(EncounterFloorDto(active = true, remainingSeconds = 0))
        assertFalse(model.view.active, "an active floor with no time is no floor")
    }

    @Test
    fun resettingTheModel_endsTheMicrophone_butAnnouncesNothing() {
        model.onSignal(EncounterFloorSignal.You(100))
        val announced = host.announced.size
        model.reset()
        assertFalse(model.view.active)
        assertEquals(1, host.microphoneEnds)
        assertEquals(announced, host.announced.size)
    }

    @Test
    fun noSentenceNamesAPerson() {
        model.onSignal(EncounterFloorSignal.You(100))
        model.onSignal(EncounterFloorSignal.Off)
        model.onSignal(EncounterFloorSignal.On(5))
        tick(5)
        val allowed = setOf("Sie haben das Wort", "Wort beendet", "Wort erteilt", "Noch 10 Sekunden")
        assertTrue(host.announced.all { it in allowed }, "only the fixed sentences: ${host.announced}")
    }
}
