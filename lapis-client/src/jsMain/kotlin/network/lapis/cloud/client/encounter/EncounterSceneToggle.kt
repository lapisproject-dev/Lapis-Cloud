package network.lapis.cloud.client.encounter

import kotlinx.browser.localStorage
import kotlinx.browser.window

/**
 * One of exactly TWO browser-storage keys of the encounter client (plus the device ids of the picker, which live under the video
 * conference's own keys): "the scene is off". No room, no person, no time.
 */
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

/**
 * V1.9.96 -- the second of the two keys: "the bell sound is on in this browser". It exists ONLY after the person switched the sound on in a
 * church-service room (default: off, and switching off removes the key), so its mere existence is a local hint that this browser has been
 * in such a room and chose the sound -- an honest limit, documented in `docs/architecture/encounter-space.adoc`. No room, no person, no time.
 */
internal const val ENCOUNTER_BELL_SOUND_ON_KEY = "lapis.encounter.bellSoundOn"

/** Whether the person switched the bell sound on in this browser. A storage that throws means "off". */
internal fun encounterBellSoundOnStored(): Boolean =
    try {
        localStorage.getItem(ENCOUNTER_BELL_SOUND_ON_KEY) == "1"
    } catch (e: Throwable) {
        false
    }

/** Remembers the person's own choice: `on` writes the key, off removes it again. Called only from the switch's change handler. */
internal fun storeEncounterBellSoundOn(on: Boolean) {
    try {
        if (on) localStorage.setItem(ENCOUNTER_BELL_SOUND_ON_KEY, "1") else localStorage.removeItem(ENCOUNTER_BELL_SOUND_ON_KEY)
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
