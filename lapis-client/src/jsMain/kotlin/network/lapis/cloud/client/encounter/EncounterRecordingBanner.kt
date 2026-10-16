package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.span
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.untrustedDiv

/**
 * V1.9.143 -- the band that tells EVERYBODY in the room that the pulpit is being recorded. It is built once and only shown or hidden afterwards
 * (nothing is rebuilt, nothing is re-focused). It is the honest side of the recording: as long as a recording runs, the band is in the picture --
 * in every scene, in the full screen (it sits in the room's band area) and on a narrow screen (the short form "Aufnahme" stays).
 *
 * - Quiet: no `role="status"` of its own (the room's persistent polite region announces the change), no animation at all (the symbol is a plain ring with a dot, drawn by the icon font), so there is nothing to switch off
 *   for `prefers-reduced-motion`. The state is never colour alone: symbol AND words.
 * - The long form says what is recorded ("Nur die Kanzel wird aufgezeichnet"); the notice of the operator ([roomNotice], the `REC_ROOM` text,
 *   untrusted) follows below it.
 * - The fixed sentences "Aufzeichnung gestartet" / "Aufzeichnung beendet" go to the room's persistent polite region ([announce]) only when the state
 *   CHANGES; the band itself is not a live region that re-reads on every render.
 */
internal class EncounterRecordingBanner(
    host: Container,
    private val terms: EncounterTerms,
    roomNotice: String?,
    private val announce: (String) -> Unit,
) {
    val root: Div = host.div(className = "lapis-encounter-band lapis-encounter-recording d-flex flex-wrap align-items-center gap-2")
    private val longText: Span
    private val shortText: Span

    private var active = false

    init {
        val dot = Tag(TAG.I, className = "${ActionIcon.PULPIT_RECORD.css} lapis-encounter-recording-dot")
        dot.setAttribute("aria-hidden", "true")
        root.add(dot)
        longText = root.span(terms.recordingBanner(), className = "lapis-encounter-recording-text fw-semibold")
        shortText = root.span(terms.recordingBannerShort(), className = "lapis-encounter-recording-short fw-semibold")
        if (!roomNotice.isNullOrBlank()) root.untrustedDiv(roomNotice, className = "lapis-encounter-recording-notice small w-100")
        root.setAttribute("hidden", "")
    }

    /** `true` while the band is in the picture (test aid). */
    val visible: Boolean get() = root.getAttribute("hidden") == null

    /** Shows or hides the band; announces the change once. Idempotent. */
    fun render(isActive: Boolean) {
        if (isActive == active) return
        active = isActive
        if (isActive) root.removeAttribute("hidden") else root.setAttribute("hidden", "")
        announce(if (isActive) terms.recordingStartedAnnouncement() else terms.recordingEndedAnnouncement())
    }
}
