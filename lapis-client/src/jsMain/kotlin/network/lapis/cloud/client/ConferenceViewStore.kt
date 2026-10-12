package network.lapis.cloud.client

import kotlinx.browser.window
import network.lapis.cloud.shared.conference.ConferenceView

/** `localStorage` key of the remembered view (per browser). */
internal const val CONFERENCE_VIEW_STORAGE_KEY = "lapis.conference.view"

/**
 * V1.9.115 -- remembers the choice GALLERY / SPEAKER of the live conference in the browser. A per-viewer convenience: every read and
 * write is in a try/catch (a private window, blocked site data or a quota can throw), the view then simply is the gallery. Only values of
 * `conferenceViewStorageValue` are written -- never a person, never a pinned identity.
 */
internal object ConferenceViewStore {
    fun read(): ConferenceView =
        try {
            ConferenceViewController.fromStorage(window.localStorage.getItem(CONFERENCE_VIEW_STORAGE_KEY))
        } catch (_: Throwable) {
            ConferenceView.GALLERY
        }

    /** Writes [value] (`GALLERY` / `SPEAKER`); `null` is ignored. */
    fun write(value: String?) {
        if (value == null) return
        try {
            window.localStorage.setItem(CONFERENCE_VIEW_STORAGE_KEY, value)
        } catch (_: Throwable) {
            // a viewer without storage simply starts in the gallery next time
        }
    }
}
