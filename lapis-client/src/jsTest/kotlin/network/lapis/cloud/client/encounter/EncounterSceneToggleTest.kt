package network.lapis.cloud.client.encounter

import kotlinx.browser.localStorage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.62 -- the ONE storage key of the encounter client and its failure behaviour (private window, blocked storage). */
class EncounterSceneToggleTest {
    @BeforeTest
    fun clean() {
        localStorage.removeItem(ENCOUNTER_SCENE_OFF_KEY)
    }

    @AfterTest
    fun restore() {
        localStorage.removeItem(ENCOUNTER_SCENE_OFF_KEY)
    }

    @Test
    fun theKey_andTheValue_areExactlyTheDocumentedOnes() {
        assertEquals("lapis.encounter.sceneOff", ENCOUNTER_SCENE_OFF_KEY)
        storeEncounterSceneOff(true)
        assertEquals("1", localStorage.getItem(ENCOUNTER_SCENE_OFF_KEY))
        assertTrue(encounterSceneOffStored())
        storeEncounterSceneOff(false)
        assertNull(localStorage.getItem(ENCOUNTER_SCENE_OFF_KEY), "switching it on again removes the key")
        assertFalse(encounterSceneOffStored())
    }

    @Test
    fun aStorageThatThrows_meansTheSceneIsOn_withoutACrash() {
        val proto = js("Storage.prototype")
        val realGet = proto.getItem
        val realSet = proto.setItem
        val realRemove = proto.removeItem
        try {
            val boom = { _: dynamic -> throw IllegalStateException("blocked") }
            proto.getItem = boom
            proto.setItem = { _: dynamic, _: dynamic -> throw IllegalStateException("blocked") }
            proto.removeItem = boom
            assertFalse(encounterSceneOffStored())
            storeEncounterSceneOff(true)
            storeEncounterSceneOff(false)
        } finally {
            proto.getItem = realGet
            proto.setItem = realSet
            proto.removeItem = realRemove
        }
    }
}
