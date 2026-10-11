package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingFailureCode
import network.lapis.cloud.shared.domain.ConferenceRecordingListQuery
import network.lapis.cloud.shared.domain.ConferenceRecordingPageDto
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.rpc.IConferenceRecordingService
import network.lapis.cloud.shared.rpc.RecordingRawFilesMissingException
import network.lapis.cloud.shared.rpc.RecordingRecomputeInProgressException
import network.lapis.cloud.shared.rpc.RecordingRecomputeInsufficientDiskException
import network.lapis.cloud.shared.rpc.RecordingRecomputeNotAllowedException
import network.lapis.cloud.shared.rpc.RecordingRecomputeRateLimitedException
import network.lapis.cloud.shared.rpc.RecordingRetryNotAllowedException
import network.lapis.cloud.shared.rpc.RecordingRetryRateLimitedException
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import kotlin.math.ceil
import kotlin.time.Duration.Companion.minutes

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
 *   chronological order, with a visible red border and a fixed text per [ConferenceRecordingFailureCode]
 *   ([conferenceRecordingFailureText], V1.9.107 -- the server's `failureReason` text is no longer shown) plus a
 *   concrete next step -- never a bare red label a moderator could miss while scrolling past successful recordings.
 * - **V1.9.107 -- "Umkodierung neu starten".** A FAILED recording whose composition failed (`COMPOSE_FAILED`) and whose raw
 *   files are still on disk offers the moderator a retry ([ConferenceRecordingDto.canRetryComposition], decided by the
 *   server). While a row is PROCESSING the panel polls every [RecordingsPolling.intervalMs] (visible tab only, capped at
 *   [RecordingsPolling.maxDurationMs]) and announces status changes in a polite live region.
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
    // The panel is re-rendered from three places in ConferenceScreen -- a newer render invalidates this one's poll timer.
    val generation = RecordingsPolling.nextGeneration()
    // Stays hidden until availability is confirmed `enabled` below -- D14, see file KDoc.
    panel.hide()
    panel.h2(tr("Aufzeichnungen")) { addCssClass("h5") }
    val liveRegion = panel.div("") { addCssClasses("visually-hidden") }
    liveRegion.setAttribute("role", "status")
    liveRegion.setAttribute("aria-live", "polite")
    val statusById = mutableMapOf<String, ConferenceRecordingStatus>()
    // V1.9.109: which rows were being recomputed at the last render -- the live region announces the end of a recompute.
    val recomputeById = mutableMapOf<String, Boolean>()
    var pollTimer: Int? = null
    var pollStartedAtMs: Double? = null
    val listPanel = panel.vPanel(spacing = 8)
    val pagerRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }

    var offset = 0
    // `silent = true` is the background poll: it never clears the list before the response arrives and
    // re-renders only on a real change, so an inline <video>/keyboard focus survives a poll tick.
    lateinit var loadPage: (Boolean) -> Unit
    // `lastPage` is the page currently DISPLAYED (null once a non-silent load cleared the panel); `lastRows` survives
    // such a clear so a failed poll can still schedule the next tick.
    var lastPage: ConferenceRecordingPageDto? = null
    var lastRows: List<ConferenceRecordingDto> = emptyList()
    var requestSeq = 0

    fun schedulePoll(rows: List<ConferenceRecordingDto>) {
        pollTimer?.let { window.clearTimeout(it) }
        pollTimer = null
        if (!RecordingsPolling.isCurrent(generation)) return
        if (rows.none { it.status == ConferenceRecordingStatus.PROCESSING || it.recomputeInProgress }) {
            // The 150-min cap is per processing phase, not per panel render.
            pollStartedAtMs = null
            return
        }
        val startedAt = pollStartedAtMs ?: RecordingsPolling.nowMs().also { pollStartedAtMs = it }
        if (RecordingsPolling.nowMs() - startedAt > RecordingsPolling.maxDurationMs) return
        pollTimer =
            window.setTimeout({
                pollTimer = null
                // A removed/disposed panel has no connected element any more -- stop for good.
                if (!RecordingsPolling.isCurrent(generation) || panel.getElement()?.isConnected != true) return@setTimeout
                if (RecordingsPolling.isTabVisible()) {
                    loadPage(true)
                } else {
                    // Hidden tab: no request, but keep the single timer alive so polling resumes on return (still capped).
                    schedulePoll(rows)
                }
            }, RecordingsPolling.intervalMs.toInt())
    }

    fun announceChanges(rows: List<ConferenceRecordingDto>) {
        val message =
            rows
                .firstNotNullOfOrNull { row ->
                    val before = statusById[row.id] ?: return@firstNotNullOfOrNull null
                    if (before == row.status) return@firstNotNullOfOrNull null
                    when (row.status) {
                        ConferenceRecordingStatus.PROCESSING ->
                            gettext(
                                "Die Aufzeichnung von \"%1\" wird verarbeitet.",
                                sanitizeUntrustedI18nText(row.roomTitle),
                            )
                        ConferenceRecordingStatus.READY ->
                            gettext(
                                "Die Aufzeichnung von \"%1\" ist fertig.",
                                sanitizeUntrustedI18nText(row.roomTitle),
                            )
                        ConferenceRecordingStatus.FAILED ->
                            gettext(
                                "Die Aufzeichnung von \"%1\" ist fehlgeschlagen.",
                                sanitizeUntrustedI18nText(row.roomTitle),
                            )
                        else -> null
                    }
                }
        // V1.9.109: the end of a "Neu berechnen" -- the status stays READY, so it is announced from its own flag.
        val recomputeMessage =
            rows
                .firstNotNullOfOrNull { row ->
                    if (recomputeById[row.id] != true || row.recomputeInProgress) return@firstNotNullOfOrNull null
                    if (row.lastRecomputeFailed) {
                        gettext("Neuberechnung fehlgeschlagen – die bisherige Datei ist unverändert.")
                    } else {
                        gettext("Neuberechnung abgeschlossen.")
                    }
                }
        rows.forEach {
            statusById[it.id] = it.status
            recomputeById[it.id] = it.recomputeInProgress
        }
        val announcement = message ?: recomputeMessage
        if (announcement != null) liveRegion.content = announcement
    }

    fun renderPage(page: ConferenceRecordingPageDto) {
        lastPage = page
        lastRows = page.rows
        listPanel.removeAll()
        pagerRow.removeAll()
        announceChanges(page.rows)
        schedulePoll(page.rows)
        if (page.rows.isEmpty()) {
            listPanel.div(tr("Noch keine Aufzeichnungen.")) { addCssClasses("text-muted small") }
        } else {
            conferenceRecordingListSorted(page.rows).forEach { recording ->
                // No offset fix-up here any more -- an emptied page is recovered centrally in
                // `loadPage` below, which covers strictly more cases than the "I just deleted the
                // last row of this page" special case that used to live here (see its comment).
                renderConferenceRecordingRow(panel = listPanel, recording = recording) { loadPage(false) }
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
            loadPage(false)
        }
        val nextButton = pagerRow.button(tr("Weiter ›"), style = ButtonStyle.OUTLINESECONDARY)
        nextButton.disabled = page.offset + page.rows.size >= page.totalCount
        nextButton.onClick {
            offset += page.limit
            loadPage(false)
        }
    }

    loadPage = { silent ->
        val seq = ++requestSeq
        AppScope.launch {
            if (!silent) {
                lastPage = null
                listPanel.removeAll()
                pagerRow.removeAll()
                listPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
            }
            val page =
                guarded {
                    rpcService<IConferenceRecordingService>().listRecordings(ConferenceRecordingListQuery(offset = offset))
                }
            // A newer request (paging, retry, delete) supersedes this one -- never render a stale response.
            if (seq != requestSeq || !RecordingsPolling.isCurrent(generation)) return@launch
            if (page == null) {
                if (silent) {
                    // Transient poll failure: keep the last rendered page and try again on the next tick.
                    schedulePoll(lastRows)
                } else {
                    listPanel.removeAll()
                }
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
                loadPage(false)
                return@launch
            }
            if (silent && page == lastPage) {
                schedulePoll(page.rows)
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
        loadPage(false)
    }
}

private fun renderConferenceRecordingRow(
    panel: SimplePanel,
    recording: ConferenceRecordingDto,
    onChanged: () -> Unit,
) {
    val failed = recording.status == ConferenceRecordingStatus.FAILED
    val card =
        panel.vPanel(spacing = 4) {
            addCssClasses(if (failed) "border border-danger rounded p-2" else "border rounded p-2")
        }

    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.statusBadge(conferenceRecordingStatusLabel(recording.status), conferenceRecordingStatusColor(recording.status))
    headerRow.untrustedCardTitle(recording.roomTitle)
    headerRow.div(conferenceRecordingDurationLabel(recording.durationSeconds)) { addCssClasses("text-muted small") }

    // V1.9.107: the retry sits LEFT of "Löschen" (rendered after it in the DOM order below).
    if (recording.canRetryComposition) {
        renderConferenceRecordingRetryButton(row = headerRow, recording = recording, onChanged = onChanged)
    }
    // V1.9.109: "Neu berechnen" sits left of "Löschen" as well (READY rows; disabled with a visible reason when the raw tracks are gone).
    val recomputeHintId = "lapis-recording-recompute-hint-${recording.id}"
    if (conferenceRecordingShowsRecompute(recording)) {
        renderConferenceRecordingRecomputeButton(
            row = headerRow,
            card = card,
            recording = recording,
            hintId = recomputeHintId,
            onChanged = onChanged,
        )
    }
    if (conferenceRecordingCanDelete(
            recording = recording,
            localMemberId = AppState.session?.memberId,
            isBoardOrAdmin = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN),
        )
    ) {
        renderConferenceRecordingDeleteButton(row = headerRow, recording = recording, onDeleted = onChanged)
    }

    card.div(conferenceRecordingStartedLabel(recording.startedByDisplayName, recording.startedAt)) {
        addCssClasses("text-muted small")
    }

    if (failed) {
        // D12: FAILED is interrupting, not buried. V1.9.107: fixed text per failure code -- the server's `failureReason`
        // is no longer rendered (it could never carry more than the code does, and the code is translatable).
        val lines = conferenceRecordingFailureText(recording)
        card.div(lines.first()) { addCssClasses("text-danger small fw-bold") }
        lines.drop(1).forEach { line -> card.div(line) { addCssClasses("text-muted small") } }
    }

    if (recording.status == ConferenceRecordingStatus.PROCESSING) {
        conferenceRecordingEstimateLine(recording)?.let { line -> card.div(line) { addCssClasses("text-muted small") } }
    }

    if (recording.recomputeInProgress) {
        card.div(tr("Wird neu berechnet …")) { addCssClasses("text-muted small lapis-recording-recompute-state") }
    } else if (recording.lastRecomputeFailed) {
        card.div(tr("Neuberechnung fehlgeschlagen – die bisherige Datei ist unverändert.")) {
            addCssClasses("text-danger small lapis-recording-recompute-state")
        }
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
            video.className = "lapis-recording-player"
            video.src = mediaUrl
            el.appendChild(video)
        }

        // D9: a SEPARATE click target from inline playback -- never the same element.
        val downloadRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center") }
        downloadRow.actionLink(ActionIcon.DOWNLOAD, tr("Herunterladen"), mediaUrl, "_blank")
        recording.fileSizeBytes?.let { size ->
            downloadRow.div(conferenceRecordingFileSizeLabel(size)) { addCssClasses("text-muted small") }
        }
    }
}

/**
 * V1.9.107 "Umkodierung neu starten": [ButtonStyle.OUTLINEPRIMARY] with the refresh icon, a [confirmDialog] stating roughly how long the
 * composition takes, then `retryRecordingComposition` through [guarded]. The three typed refusals get their own toast and refresh the list
 * (the state changed under the caller); everything else goes through the central [guarded] mapping. The accessible name carries the room
 * title ([gettext] result via `setAttribute` -- never a raw `tr()` into the DOM, see the file KDoc).
 */
private fun renderConferenceRecordingRetryButton(
    row: SimplePanel,
    recording: ConferenceRecordingDto,
    onChanged: () -> Unit,
) {
    val retryButton = row.actionButton(ActionIcon.REFRESH, tr("Umkodierung neu starten"), style = ButtonStyle.OUTLINEPRIMARY)
    retryButton.addCssClass("lapis-recording-retry")
    // 44 x 44 px at every pointer type (theme.css only guarantees it for a coarse pointer); the phone-width rule lives in `.lapis-recording-retry`.
    retryButton.setStyle("min-height", "44px")
    retryButton.setStyle("min-width", "44px")
    retryButton.setAttribute("aria-label", gettext("Umkodierung neu starten: %1", sanitizeUntrustedI18nText(recording.roomTitle)))
    retryButton.onClick {
        confirmDialog(
            title = tr("Umkodierung neu starten"),
            message = conferenceRecordingRetryConfirmText(recording),
            confirmLabel = tr("Neu starten"),
            confirmIcon = ActionIcon.REFRESH,
            confirmStyle = ButtonStyle.PRIMARY,
        ) {
            retryButton.disabled = true
            AppScope.launch {
                var refused = false
                val started =
                    guarded {
                        try {
                            rpcService<IConferenceRecordingService>().retryRecordingComposition(recording.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: RecordingRetryNotAllowedException) {
                            refused = true
                            notifyError(tr("Diese Aufzeichnung kann nicht neu verarbeitet werden. Bitte Ansicht aktualisieren."))
                            null
                        } catch (e: RecordingRawFilesMissingException) {
                            refused = true
                            notifyError(tr("Die Rohaufnahmen sind nicht mehr vorhanden. Eine erneute Verarbeitung ist nicht möglich."))
                            null
                        } catch (e: RecordingRetryRateLimitedException) {
                            notifyError(tr("Bitte versuchen Sie es später erneut."))
                            null
                        }
                    }
                retryButton.disabled = false
                if (started != null) {
                    notifySuccess(tr("Umkodierung gestartet."))
                    onChanged()
                } else if (refused) {
                    onChanged()
                }
            }
        }
    }
}

/**
 * V1.9.109: does this row offer "Neu berechnen" at all -- READY, no recompute running, and either allowed right now or (for a caller who
 * may recompute) merely missing its raw tracks, in which case the button is shown DISABLED with the reason next to it.
 * [ConferenceRecordingDto.rawFilesAvailable] is only ever set for callers who may recompute, so other callers see nothing.
 */
internal fun conferenceRecordingShowsRecompute(recording: ConferenceRecordingDto): Boolean =
    recording.status == ConferenceRecordingStatus.READY &&
        !recording.recomputeInProgress &&
        (recording.canRecompute || recording.rawFilesAvailable == false)

/**
 * V1.9.109 "Neu berechnen": the same shape as [renderConferenceRecordingRetryButton] -- [ButtonStyle.OUTLINEPRIMARY], 44 x 44 px, a
 * [confirmDialog] first, then `recomputeRecording` through [guarded]. Without raw tracks the button is DISABLED (`disabled`, a `title`) and
 * the reason is visible text next to it, tied to the button with `aria-describedby` (a title alone is invisible on a touch screen). The
 * typed refusals get their own toast and refresh the list (the state changed under the caller).
 */
private fun renderConferenceRecordingRecomputeButton(
    row: SimplePanel,
    card: SimplePanel,
    recording: ConferenceRecordingDto,
    hintId: String,
    onChanged: () -> Unit,
) {
    val button = row.actionButton(ActionIcon.REFRESH, tr("Neu berechnen"), style = ButtonStyle.OUTLINEPRIMARY)
    button.addCssClass("lapis-recording-recompute")
    button.setStyle("min-height", "44px")
    button.setStyle("min-width", "44px")
    button.setAttribute("aria-label", gettext("Neu berechnen: %1", sanitizeUntrustedI18nText(recording.roomTitle)))
    if (!recording.canRecompute) {
        val reason = gettext("Die Rohspuren sind nicht mehr vorhanden.")
        button.disabled = true
        button.setAttribute("title", reason)
        button.setAttribute("aria-describedby", hintId)
        card.div(reason) {
            setAttribute("id", hintId)
            addCssClasses("text-muted small lapis-recording-recompute-hint")
        }
        return
    }
    button.onClick {
        confirmDialog(
            title = tr("Neu berechnen"),
            message = conferenceRecordingRecomputeConfirmText(recording),
            confirmLabel = tr("Neu starten"),
            confirmIcon = ActionIcon.REFRESH,
            confirmStyle = ButtonStyle.PRIMARY,
        ) {
            button.disabled = true
            AppScope.launch {
                var refused = false
                val started =
                    guarded {
                        try {
                            rpcService<IConferenceRecordingService>().recomputeRecording(recording.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: RecordingRecomputeInProgressException) {
                            refused = true
                            notifyError(tr("Die Aufzeichnung wird bereits neu berechnet."))
                            null
                        } catch (e: RecordingRecomputeRateLimitedException) {
                            refused = true
                            notifyError(tr("Zu viele Neuberechnungen – bitte später erneut versuchen."))
                            null
                        } catch (e: RecordingRecomputeInsufficientDiskException) {
                            refused = true
                            notifyError(tr("Zu wenig Speicherplatz für die Neuberechnung."))
                            null
                        } catch (e: RecordingRecomputeNotAllowedException) {
                            refused = true
                            notifyError(tr("Diese Aufzeichnung kann nicht neu berechnet werden."))
                            null
                        } catch (e: RecordingRawFilesMissingException) {
                            refused = true
                            notifyError(tr("Die Rohaufnahmen sind nicht mehr vorhanden. Eine erneute Verarbeitung ist nicht möglich."))
                            null
                        }
                    }
                button.disabled = false
                if (started != null) {
                    notifySuccess(tr("Neuberechnung gestartet."))
                    onChanged()
                } else if (refused) {
                    onChanged()
                }
            }
        }
    }
}

/** The recompute confirmation: the old video stays available until the new one is ready; with the time estimate when it is known. */
internal fun conferenceRecordingRecomputeConfirmText(recording: ConferenceRecordingDto): String {
    val title = sanitizeUntrustedI18nText(recording.roomTitle)
    val minutes = conferenceRecordingEstimateMinutes(recording)
    return if (minutes != null) {
        gettext(
            "Die Aufzeichnung von \"%1\" wird aus den Rohspuren neu berechnet (voraussichtlich bis zu %2 Minuten). " +
                "Das bisherige Video bleibt bis dahin abrufbar und wird danach ersetzt; die bisherige Fassung bleibt als frühere Version in der Dokumentenablage.",
            title,
            minutes.toString(),
        )
    } else {
        gettext(
            "Die Aufzeichnung von \"%1\" wird aus den Rohspuren neu berechnet. " +
                "Das bisherige Video bleibt bis dahin abrufbar und wird danach ersetzt; die bisherige Fassung bleibt als frühere Version in der Dokumentenablage.",
            title,
        )
    }
}

/** Fixed, translated lines for a FAILED recording, keyed by [ConferenceRecordingDto.failureCode] -- never the server's `failureReason` text. */
internal fun conferenceRecordingFailureText(recording: ConferenceRecordingDto): List<String> {
    val administration = gettext("Wenden Sie sich an die Administration.")
    return when (recording.failureCode) {
        ConferenceRecordingFailureCode.COMPOSE_FAILED ->
            listOf(
                gettext("Die Aufzeichnung wurde vollständig aufgenommen, konnte aber nicht zu einem Video zusammengesetzt werden."),
                when (recording.rawFilesAvailable) {
                    true -> gettext("Die Rohaufnahmen sind erhalten.")
                    false -> gettext("Die Rohaufnahmen sind nicht mehr vorhanden.")
                    null -> administration
                },
            )
        ConferenceRecordingFailureCode.EGRESS_TIMEOUT ->
            listOf(gettext("Der Aufzeichnungsdienst hat die Spuren nicht rechtzeitig abgeschlossen."), administration)
        ConferenceRecordingFailureCode.NO_TRACKS ->
            listOf(gettext("Es wurde keine Audio- oder Videospur aufgezeichnet."), administration)
        ConferenceRecordingFailureCode.NO_TRACK_STORED ->
            listOf(gettext("Der Aufzeichnungsdienst konnte keine Spur speichern."), administration)
        null -> listOf(gettext("Die Aufzeichnung ist fehlgeschlagen."))
    }
}

/** Minutes the composition roughly takes: half the recording length, rounded up; `null` when start or stop is unknown. */
internal fun conferenceRecordingEstimateMinutes(recording: ConferenceRecordingDto): Int? {
    val stoppedAt = recording.stoppedAt ?: return null
    val length = stoppedAt.toInstant(TimeZone.UTC) - recording.startedAt.toInstant(TimeZone.UTC)
    if (length <= 0.minutes) return null
    return ceil(length.inWholeSeconds / 120.0).toInt().coerceAtLeast(1)
}

/** The retry confirmation: with the time estimate in parentheses when both timestamps are known, without otherwise. */
internal fun conferenceRecordingRetryConfirmText(recording: ConferenceRecordingDto): String {
    val title = sanitizeUntrustedI18nText(recording.roomTitle)
    val minutes = conferenceRecordingEstimateMinutes(recording)
    return if (minutes != null) {
        gettext(
            "Die Aufzeichnung von \"%1\" wird erneut zu einem Video zusammengesetzt (voraussichtlich bis zu %2 Minuten). " +
                "Die Rohaufnahmen bleiben dabei erhalten.",
            title,
            minutes.toString(),
        )
    } else {
        gettext(
            "Die Aufzeichnung von \"%1\" wird erneut zu einem Video zusammengesetzt. Die Rohaufnahmen bleiben dabei erhalten.",
            title,
        )
    }
}

/** "Voraussichtlich fertig gegen HH:MM Uhr (Schätzung)." for a PROCESSING row after a manual retry; `null` without a start or a length. */
internal fun conferenceRecordingEstimateLine(recording: ConferenceRecordingDto): String? {
    val startedAt: LocalDateTime = recording.compositionRetryStartedAt ?: return null
    val minutes = conferenceRecordingEstimateMinutes(recording) ?: return null
    val done = (startedAt.toInstant(TimeZone.UTC) + minutes.minutes).toLocalDateTime(resolveZone(OrganizationTime.zoneId))
    return gettext("Voraussichtlich fertig gegen %1 Uhr (Schätzung).", formatTimeComponents(done.hour, done.minute))
}

/**
 * Poll settings of the Lobby's recordings panel while a row is PROCESSING -- an object (not constants) so tests can inject a short
 * interval, the tab visibility and the clock (same seam shape as [UnreadMessages]). [generation] invalidates the timer of a panel that
 * was re-rendered (three call sites in `ConferenceScreen`).
 */
internal object RecordingsPolling {
    var intervalMs: Double = 30_000.0
    var maxDurationMs: Double = 150 * 60_000.0
    var isTabVisible: () -> Boolean = { document.asDynamic().visibilityState == "visible" }
    var nowMs: () -> Double = { kotlin.js.Date.now() }
    private var generation = 0

    fun nextGeneration(): Int = ++generation

    fun isCurrent(value: Int): Boolean = value == generation

    fun resetForTest() {
        intervalMs = 30_000.0
        maxDurationMs = 150 * 60_000.0
        isTabVisible = { document.asDynamic().visibilityState == "visible" }
        nowMs = { kotlin.js.Date.now() }
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
    val deleteButton = row.actionButton(ActionIcon.DELETE, tr("Löschen"), style = ButtonStyle.OUTLINEDANGER)
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
            confirmIcon = ActionIcon.DELETE,
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
