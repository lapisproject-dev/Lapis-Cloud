package network.lapis.cloud.client

import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.rpc.IConferenceRecordingService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- the Lobby's "Aufzeichnungen"
 * section, wired into `ConferenceScreen.renderLobby`. See that file's own KDoc "V1.0
 * Videokonferenzen (Kleinsitzung), Wave 2" for the full cross-file D-item list; this file's own
 * load-bearing decisions:
 *
 * - **D9 -- lives in the LOBBY, not only inside a live call.** A recording OUTLIVES its room (the
 *   room may be long ended, even deleted from the active list) -- reachable only from inside a call
 *   would strand every finished recording the moment its meeting ends. [renderConferenceRecordingsPanel]
 *   is called from `renderLobby` on initial load, on "Aktualisieren", and again every time the
 *   screen returns from a call (a recording may just have been started/stopped there).
 * - **D9 -- inline playback and download are separate click targets.** The `<video controls>`
 *   element and the "Herunterladen" [io.kvision.html.link] are two distinct elements, never the
 *   same one -- given recordings running into hundreds of MB on a metered/mobile connection, a
 *   participant must be able to see "there is a recording" without accidentally starting a large
 *   download, and vice versa.
 * - **D12 -- FAILED is not just listed, it is prominent.** [conferenceRecordingListSorted]
 *   (`ConferenceScreen.kt`) pins every FAILED recording to the TOP of the list, ahead of
 *   chronological order, with a visible red border and the sanitized `failureReason` plus a concrete
 *   next step ("Wenden Sie sich an eine Administratorin oder einen Administrator") -- never a bare
 *   red label a moderator could miss while scrolling past successful recordings.
 * - **D13's raw-file-retention-on-FAILED promise is a SERVER-side guarantee** (this wave's poller
 *   step, `RecordingPoller.kt` KDoc "PROCESSING") -- this file's own contribution is making that
 *   promise legible: the FAILED row's copy explicitly says raw footage may still be recoverable via
 *   an administrator, rather than implying the recording is simply gone.
 * - **Availability gating mirrors `ConferenceScreen.kt`'s own D14/"the getAvailability gate"
 *   posture**: [IConferenceRecordingService.listRecordings] THROWS `ConflictException` when
 *   recording is unconfigured server-side (`ConferenceRecordingService.requireRecordingEnabled`) --
 *   this function always checks [IConferenceRecordingService.getRecordingAvailability] FIRST and
 *   hides the entire section (not a disabled/empty state) when `enabled == false`, exactly mirroring
 *   why `ConferenceScreen.kt`'s own [refreshRecordingState] never calls a recording-read method
 *   before that same check.
 *
 * **D11's partial-composition flag is a KNOWN, NOT-YET-CLOSED gap**, same caveat as
 * `ConferenceScreen.kt`'s own file KDoc -- [ConferenceRecordingDto] carries no
 * `composedFromPartialTracks`-shaped field as of this step, so a recording composed "from the
 * survivors" after an egress timeout renders with the identical "Bereit" badge as a clean one. Not
 * silently worked around here; flagged for a later step to close deliberately.
 */
fun renderConferenceRecordingsPanel(panel: SimplePanel) {
    panel.removeAll()
    // Stays hidden until availability is confirmed `enabled` below -- D14, see file KDoc.
    panel.hide()
    panel.h2(tr("Aufzeichnungen"))
    val listPanel = panel.vPanel(spacing = 8)

    AppScope.launch {
        val availability = guarded { rpcService<IConferenceRecordingService>().getRecordingAvailability() }
        if (availability == null || !availability.enabled) {
            // D14: invisible, not a disabled/confusing empty section -- see file KDoc.
            return@launch
        }
        panel.show()
        listPanel.removeAll()
        listPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        val recordings = guarded { rpcService<IConferenceRecordingService>().listRecordings() }
        listPanel.removeAll()
        if (recordings.isNullOrEmpty()) {
            listPanel.div(tr("Noch keine Aufzeichnungen.")) { addCssClasses("text-muted small") }
            return@launch
        }
        conferenceRecordingListSorted(recordings).forEach { recording ->
            renderConferenceRecordingRow(listPanel, recording)
        }
    }
}

private fun renderConferenceRecordingRow(
    panel: SimplePanel,
    recording: ConferenceRecordingDto,
) {
    val failed = recording.status == ConferenceRecordingStatus.FAILED
    val card =
        panel.vPanel(spacing = 4) {
            addCssClasses(if (failed) "border border-danger rounded p-2" else "border rounded p-2")
        }

    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.statusBadge(conferenceRecordingStatusLabel(recording.status), conferenceRecordingStatusColor(recording.status))
    headerRow.div(recording.roomTitle) { addCssClasses("fw-bold flex-grow-1") }
    headerRow.div(conferenceRecordingDurationLabel(recording.durationSeconds)) { addCssClasses("text-muted small") }

    card.div(conferenceRecordingStartedLabel(recording.startedByDisplayName, recording.startedAt)) {
        addCssClasses("text-muted small")
    }

    if (failed) {
        // D12: FAILED is interrupting, not buried -- sanitized failureReason (never raw ffmpeg/Twirp
        // text, per ConferenceRecordingDto.failureReason's own KDoc "a security boundary") plus a
        // concrete next step. D13: raw footage is retained server-side on failure, so this copy can
        // honestly point at recovery rather than implying total loss.
        card.div(recording.failureReason ?: tr("Die Aufzeichnung ist fehlgeschlagen.")) { addCssClasses("text-danger small fw-bold") }
        card.div(
            tr(
                "Die Rohaufnahmen bleiben erhalten -- wenden Sie sich an eine Administratorin oder einen " +
                    "Administrator, falls die Aufzeichnung wiederhergestellt werden soll.",
            ),
        ) { addCssClasses("text-muted small") }
    }

    val mediaUrl = recording.mediaUrl
    if (recording.status == ConferenceRecordingStatus.READY && mediaUrl != null) {
        // D9: raw-DOM <video> element, same posture ConferenceScreen.kt's own tile grid already
        // establishes for KVision/snabbdom-vs-manually-managed-media-elements -- see that file's KDoc
        // "Raw DOM for the tile grid".
        val videoContainer = card.div { addCssClasses("mt-1") }
        videoContainer.addAfterInsertHook { vnode ->
            val el = vnode.elm as? HTMLElement ?: return@addAfterInsertHook
            val video = document.createElement("video") as HTMLVideoElement
            video.controls = true
            video.style.cssText = "width:100%;max-height:360px;border-radius:6px;background:#000;"
            video.src = mediaUrl
            el.appendChild(video)
        }

        // D9: a SEPARATE click target from inline playback -- never the same element.
        val downloadRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center") }
        downloadRow.link(tr("Herunterladen"), url = mediaUrl, target = "_blank")
        recording.fileSizeBytes?.let { size ->
            downloadRow.div(conferenceRecordingFileSizeLabel(size)) { addCssClasses("text-muted small") }
        }
    }
}
