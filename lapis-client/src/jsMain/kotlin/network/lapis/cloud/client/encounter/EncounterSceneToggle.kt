package network.lapis.cloud.client.encounter

import kotlinx.browser.localStorage
import kotlinx.browser.window

/** The ONE browser-storage key of the whole encounter client: "the scene is off". No room, no person, no time. */
internal const val ENCOUNTER_SCENE_OFF_KEY = "lapis.encounter.sceneOff"

/** Whether the person switched the decorative scene off on this device. A storage that throws (private window, blocked) means "on". */
internal fun encounterSceneOffStored(): Boolean =
    try {
        localStorage.getItem(ENCOUNTER_SCENE_OFF_KEY) == "1"
    } catch (e: Throwable) {
        false
    }

internal fun storeEncounterSceneOff(off: Boolean) {
    try {
        if (off) localStorage.setItem(ENCOUNTER_SCENE_OFF_KEY, "1") else localStorage.removeItem(ENCOUNTER_SCENE_OFF_KEY)
    } catch (e: Throwable) {
        // storage unavailable: the choice then only lasts for this visit
    }
}

/** High-contrast and forced-colours modes hide the scene on their own: a decorative picture must never compete with the content there. */
internal fun encounterSceneForcedOff(): Boolean =
    try {
        window.matchMedia("(forced-colors: active)").matches || window.matchMedia("(prefers-contrast: more)").matches
    } catch (e: Throwable) {
        false
    }
