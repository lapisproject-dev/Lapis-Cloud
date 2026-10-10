package network.lapis.cloud.client.encounter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.95: the pure timer logic of the blessing display. */
class EncounterBlessingTest {
    private var now = 1_000_000.0

    @Test
    fun theFirstPacket_shows_aSecondPacketDuringTheWindow_extends_neverOpensASecondDisplay() {
        val timer = EncounterBlessingTimer(now = { now })
        assertEquals(EncounterBlessingOutcome.SHOW, timer.onPacket())
        now += 3_000.0
        assertEquals(EncounterBlessingOutcome.EXTEND, timer.onPacket())
        now += 3_000.0
        assertEquals(EncounterBlessingOutcome.EXTEND, timer.onPacket(), "still inside the restarted window")
    }

    @Test
    fun theWindowRestartsAtTheLastPacket_andEndsSevenSecondsAfterIt() {
        val start = now
        val timer = EncounterBlessingTimer(now = { now })
        timer.onPacket()
        now = start + 3_000.0
        timer.onPacket()
        assertTrue(timer.isVisibleAt(start + 9_999.0))
        assertFalse(timer.isVisibleAt(start + 10_000.0), "visible until 3 s + 7 s, not a moment longer")
        assertFalse(timer.isVisibleAt(start + 10_001.0))
    }

    @Test
    fun aPacketAfterTheWindow_showsAgain() {
        val timer = EncounterBlessingTimer(now = { now })
        assertEquals(EncounterBlessingOutcome.SHOW, timer.onPacket())
        now += ENCOUNTER_BLESSING_DISPLAY_MS.toDouble()
        assertFalse(timer.isVisibleAt(now))
        assertEquals(EncounterBlessingOutcome.SHOW, timer.onPacket(), "announced again")
    }

    @Test
    fun theWindow_isSevenSeconds_quietAndShort() {
        assertEquals(7_000, ENCOUNTER_BLESSING_DISPLAY_MS)
        assertTrue(ENCOUNTER_BLESSING_DISPLAY_MS in 6_000..8_000)
    }
}
