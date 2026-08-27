package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingListQuery
import network.lapis.cloud.shared.domain.ConferenceRecordingPageDto
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
 * - **Paginated, with the SAME pager `MemberAdministrationScreen.kt` uses.** The list is
 *   server-paginated ([IConferenceRecordingService.listRecordings] returns a
 *   [ConferenceRecordingPageDto]) and reuses [pagerLabel] verbatim rather than growing a second
 *   "x-y von z" formatter. The offset lives in this function's own closure, so it survives paging
 *   but resets whenever the Lobby re-renders the section -- which is exactly right: every one of
 *   `ConferenceScreen.kt`'s three call sites re-renders because something CHANGED (a recording was
 *   just started/stopped, or the user pressed "Aktualisieren"), and the newest rows are on page 1.
 * - **Deleting is a moderator action, behind a confirm dialog.** The "Löschen" button appears only
 *   when [conferenceRecordingCanDelete] holds (terminal status AND moderator/privileged standing,
 *   mirroring the server's own gate -- the server remains the real authority), and always goes
 *   through [confirmDialog] first, the same destructive-action shape `DocumentsScreen.kt` already
 *   establishes for its own document delete. The copy names the consequence precisely and BRANCHES
 *   ON STATUS -- a READY recording keeps a soft-deleted, administration-recoverable file in the
 *   Dokumentenablage, a FAILED one has no archived file at all and is simply gone -- while both say
 *   plainly that the raw footage is destroyed. See
 *   [renderConferenceRecordingDeleteButton]'s own KDoc and
 *   [IConferenceRecordingService.deleteRecording].
 *
 * **D11's partial-composition flag is a KNOWN, NOT-YET-CLOSED gap**, same caveat as
 * `ConferenceScreen.kt`'s own file KDoc -- [ConferenceRecordingDto] carries no
 * `composedFromPartialTracks`-shaped field as of this step, so a recording composed "from the
 * survivors" after an egress timeout renders with the identical "Bereit" badge as a clean one. Not
 * silently worked around here; flagged for a later step to close deliberately.
 *
 * Every user-visible string here goes through `tr()`/`gettext()` into a KVision widget builder and
 * NEVER into a raw DOM property -- writing a `tr()` result straight onto `.textContent`/`.title`
 * leaks KVision's internal `"###KvI18nS###"` marker verbatim (see `ConferenceScreen.kt`'s
 * `resolvedA11yText` KDoc for the full mechanism). The `<video>` element below is the one raw-DOM
 * construction in this file, and it carries no translated text at all.
 */
fun renderConferenceRecordingsPanel(panel: SimplePanel) {
    panel.removeAll()
    // Stays hidden until availability is confirmed `enabled` below -- D14, see file KDoc.
    panel.hide()
    panel.h2(tr("Aufzeichnungen"))
    val listPanel = panel.vPanel(spacing = 8)
    val pagerRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }

    var offset = 0
    lateinit var loadPage: () -> Unit

    fun renderPage(page: ConferenceRecordingPageDto) {
        listPanel.removeAll()
        pagerRow.removeAll()
        if (page.rows.isEmpty()) {
            listPanel.div(tr("Noch keine Aufzeichnungen.")) { addCssClasses("text-muted small") }
        } else {
            conferenceRecordingListSorted(page.rows).forEach { recording ->
                // No offset fix-up here any more -- an emptied page is recovered centrally in
                // `loadPage` below, which covers strictly more cases than the "I just deleted the
                // last row of this page" special case that used to live here (see its comment).
                renderConferenceRecordingRow(panel = listPanel, recording = recording) { loadPage() }
            }
        }
        // Deliberately rendered even for an empty page, matching `MemberAdministrationScreen.kt`'s
        // own pager (which never hides itself either): hiding it was how a moderator could end up
        // stranded on a page past the end with no visible way back. `pagerLabel` already renders
        // "Keine Treffer" for a genuinely empty result, so an unconditional pager costs nothing.
        pagerRow.span(pagerLabel(offset = page.offset, pageSize = page.limit, totalCount = page.totalCount))
        val backButton = pagerRow.button(tr("‹ Zurück"), style = ButtonStyle.OUTLINESECONDARY)
        backButton.disabled = page.offset <= 0
        backButton.onClick {
            offset = (offset - page.limit).coerceAtLeast(0)
            loadPage()
        }
        val nextButton = pagerRow.button(tr("Weiter ›"), style = ButtonStyle.OUTLINESECONDARY)
        nextButton.disabled = page.offset + page.rows.size >= page.totalCount
        nextButton.onClick {
            offset += page.limit
            loadPage()
        }
    }

    loadPage = {
        AppScope.launch {
            listPanel.removeAll()
            pagerRow.removeAll()
            listPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
            val page =
                guarded {
                    rpcService<IConferenceRecordingService>().listRecordings(ConferenceRecordingListQuery(offset = offset))
                }
            if (page == null) {
                listPanel.removeAll()
                return@launch
            }
            // An empty page while rows demonstrably exist means this offset is past the end -- the
            // moderator just deleted the last row of a page, or somebody else's concurrent
            // deletions moved the end underneath them. Rendering "Noch keine Aufzeichnungen." here
            // would be a lie AND would leave no obvious way back, so clamp to the LAST valid page
            // start and re-fetch instead. Terminates by construction: an empty page means
            // `offset >= totalCount`, so the clamped offset is always strictly smaller.
            val lastPageStart = ((page.totalCount - 1) / page.limit) * page.limit
            if (page.rows.isEmpty() && page.totalCount > 0 && page.offset > lastPageStart) {
                offset = lastPageStart
                loadPage()
                return@launch
            }
            renderPage(page)
        }
    }

    AppScope.launch {
        val availability = guarded { rpcService<IConferenceRecordingService>().getRecordingAvailability() }
        if (availability == null || !availability.enabled) {
            // D14: invisible, not a disabled/confusing empty section -- see file KDoc.
            return@launch
        }
        panel.show()
        loadPage()
    }
}

private fun renderConferenceRecordingRow(
    panel: SimplePanel,
    recording: ConferenceRecordingDto,
    onDeleted: () -> Unit,
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

    if (conferenceRecordingCanDelete(
            recording = recording,
            localMemberId = AppState.session?.memberId,
            isBoardOrAdmin = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN),
        )
    ) {
        renderConferenceRecordingDeleteButton(row = headerRow, recording = recording, onDeleted = onDeleted)
    }

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

/**
 * The destructive-action shape `DocumentsScreen.kt`'s own document delete already establishes,
 * applied to a recording: [ButtonStyle.OUTLINEDANGER], a [confirmDialog] naming the consequence
 * before anything happens, [guarded] around the RPC call (so a `Forbidden`/`Conflict` from the
 * server's own -- authoritative -- gate surfaces as a toast rather than a silent no-op), and a
 * success toast plus [onDeleted] only when the call actually returned.
 *
 * The confirm copy distinguishes the parts of what deletion does, because they genuinely differ,
 * and it BRANCHES ON STATUS, because for a FAILED recording one of those parts does not exist at
 * all:
 * - Always, for both statuses: the recording ENTRY is gone for good (its `conference_recording` row
 *   is hard-deleted -- `28-conference-recording.kuml.kts` forbids a soft-delete column on that
 *   table), and the RAW per-track footage is irreversibly removed from disk
 *   (`ConferenceRecordingService.deleteRawDirectory`, deliberately ignoring `keepRaw` for an
 *   explicit, confirmed deletion).
 * - READY only: the archived video FILE is merely soft-deleted in the Dokumentenablage and stays
 *   recoverable by an administrator.
 * - FAILED: `documentId` is `null` -- composition never succeeded, so there is no archived file to
 *   fall back on and the deletion is simply irreversible. Saying so plainly matters more here than
 *   anywhere else in this file, because the FAILED card RIGHT NEXT to this button promises "Die
 *   Rohaufnahmen bleiben erhalten" -- true of the automatic retention that promise describes, and
 *   precisely what this button is about to undo on purpose.
 *
 * Promising less than that would be alarming; promising more would be false.
 */
private fun renderConferenceRecordingDeleteButton(
    row: SimplePanel,
    recording: ConferenceRecordingDto,
    onDeleted: () -> Unit,
) {
    val deleteButton = row.button(tr("Löschen"), style = ButtonStyle.OUTLINEDANGER)
    deleteButton.onClick {
        confirmDialog(
            title = tr("Aufzeichnung löschen"),
            message =
                if (recording.status == ConferenceRecordingStatus.FAILED) {
                    gettext(
                        "Die fehlgeschlagene Aufzeichnung von \"%1\" wirklich löschen? Der Eintrag verschwindet " +
                            "endgültig aus dieser Liste, und die Rohaufnahmen werden dabei unwiderruflich von der " +
                            "Festplatte entfernt. Es gibt keine archivierte Videodatei, auf die stattdessen " +
                            "zurückgegriffen werden könnte -- auch die Administration kann diese Aufzeichnung " +
                            "danach nicht mehr wiederherstellen.",
                        recording.roomTitle,
                    )
                } else {
                    gettext(
                        "Die Aufzeichnung von \"%1\" wirklich löschen? Der Eintrag verschwindet endgültig aus " +
                            "dieser Liste, und die Rohaufnahmen werden dabei unwiderruflich von der Festplatte " +
                            "entfernt; die archivierte Videodatei bleibt in der Dokumentenablage als gelöscht " +
                            "markiert erhalten und kann nur noch von der Administration wiederhergestellt werden.",
                        recording.roomTitle,
                    )
                },
            confirmLabel = tr("Löschen"),
        ) {
            deleteButton.disabled = true
            AppScope.launch {
                val deleted = guarded { rpcService<IConferenceRecordingService>().deleteRecording(recording.id) }
                deleteButton.disabled = false
                if (deleted != null) {
                    notifySuccess(tr("Aufzeichnung gelöscht."))
                    onDeleted()
                }
            }
        }
    }
}
