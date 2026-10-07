package network.lapis.cloud.client.encounter

import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.VPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.AppState
import network.lapis.cloud.client.ConnectionStoppedKind
import network.lapis.cloud.client.DataSection
import network.lapis.cloud.client.PageHeader
import network.lapis.cloud.client.actionButton
import network.lapis.cloud.client.addCssClasses
import network.lapis.cloud.client.addWithLifecycle
import network.lapis.cloud.client.conferenceConnectErrorMessage
import network.lapis.cloud.client.conferenceConnectionStoppedNotice
import network.lapis.cloud.client.dataSection
import network.lapis.cloud.client.guarded
import network.lapis.cloud.client.livekit.DisconnectCause
import network.lapis.cloud.client.newActionButton
import network.lapis.cloud.client.notifyError
import network.lapis.cloud.client.pageHeader
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.client.sanitizeUntrustedI18nText
import network.lapis.cloud.client.untrustedP
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EncounterConsentInput
import network.lapis.cloud.shared.domain.EncounterEntryInfoDto
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import network.lapis.cloud.shared.rpc.NotFoundException
import kotlin.js.Date

/** How often a closed room is asked whether the doors have opened (the `list` rate budget is shared, so slowly and only while visible). */
private const val CLOSED_POLL_MS = 30_000

/** At most this many automatic re-entries per [REENTRY_WINDOW_MS] after an unexpected disconnect (the server's `enter` budget is 30 per minute). */
private const val REENTRY_MAX = 3
private const val REENTRY_WINDOW_MS = 5 * 60 * 1000.0

/**
 * V1.9.62 Begegnungsraum (B2) -- one room, in the three phases of [EncounterViewPhase]:
 *
 * - **Closed**: the notice of the room ([EncounterSpaceDto.closedNotice], untrusted text) or a plain sentence, and for a moderator the
 *   button "Türen öffnen". While the page is visible the room is asked every 30 s whether the doors have opened.
 * - **Entry**: [encounterEntryPanel] -- the three sentences, the Art. 9 remark and, for a non-member, the consent.
 * - **Inside**: [EncounterRoom]. "Verlassen" in the title row ends the visit.
 *
 * Lifecycle: the screen root carries the teardown as its destroy hook ([addWithLifecycle], registered BEFORE the panel is added, see
 * `ConferenceScreenRootLifecycleDomTest` for why the order matters). When the route changes the visit ends: timers and listeners stop,
 * the LiveKit session disconnects and the server is told ("leaveSpace"); a closing page does the same best effort on `pagehide`
 * (a plain RPC can be cancelled by the browser then; the server's presence poller removes a stale row in that case).
 *
 * After an UNEXPECTED disconnect (removed, token ended, the doors closed, the network) the room state is read: closed means "Der
 * Gottesdienst ist beendet."; open means one automatic re-entry (at most 3 per 5 minutes), then the button "Erneut eintreten" (the entry
 * panel again); a refusal on re-entry means "Sie wurden aus diesem Raum entfernt.".
 *
 * [opener] is the session factory ([openEncounterSession] in production; a `jsTest` hands over a fake).
 */
fun renderEncounterServiceView(
    container: SimplePanel,
    spaceId: String,
) {
    renderEncounterServiceView(container = container, spaceId = spaceId, opener = ::openEncounterSession)
}

internal fun renderEncounterServiceView(
    container: SimplePanel,
    spaceId: String,
    opener: EncounterSessionOpener,
    clock: () -> Double = { Date.now() },
) {
    val visit = EncounterVisit(spaceId = spaceId, opener = opener, clock = clock)
    val root = container.encounterViewRoot { visit.teardown() }
    visit.build(root)
}

/** The root of the screen with [onTeardown] registered as destroy hook BEFORE the panel is added (stable key, see [addWithLifecycle]). */
internal fun SimplePanel.encounterViewRoot(onTeardown: () -> Unit): VPanel =
    addWithLifecycle(
        VPanel(spacing = 14) {
            addCssClass("mx-auto")
            maxWidth = 1800.px
            width = 100.perc
            marginTop = 24.px
        },
        onDestroy = onTeardown,
    )

private class EncounterVisit(
    private val spaceId: String,
    private val opener: EncounterSessionOpener,
    private val clock: () -> Double,
) {
    private lateinit var header: PageHeader
    private lateinit var notice: Div
    private lateinit var preEntry: VPanel
    private lateinit var insidePanel: VPanel
    private lateinit var section: DataSection
    private lateinit var leaveButton: Button

    private var room: EncounterRoom? = null
    private var session: EncounterListenerSession? = null
    private var entered = false
    private var closedPhase = false
    private var tornDown = false
    private var leaving = false
    private val reentries = ArrayDeque<Double>()
    private val cleanups = mutableListOf<() -> Unit>()

    fun build(root: VPanel) {
        header = root.pageHeader(tr("Begegnungsraum"), subtitle = "")
        leaveButton = header.actionSlot.actionButton(ActionIcon.LEAVE, tr("Verlassen"), style = ButtonStyle.OUTLINESECONDARY)
        leaveButton.hide()
        leaveButton.onClick { leave() }
        notice = root.div(className = "text-muted")
        notice.setAttribute("role", "status")
        preEntry = root.vPanel(spacing = 10)
        insidePanel = root.vPanel(spacing = 10)
        section =
            preEntry.dataSection<EncounterEntryInfoDto>(
                isEmpty = { false },
                load = { guarded { rpcService<IEncounterSpaceService>().getEntryInfo(spaceId) } },
            ) { panel, info -> renderPhase(panel, info) }
        section.reload()
        startClosedPoll()
        val onPageHide: (org.w3c.dom.events.Event) -> Unit = { endBestEffort() }
        window.addEventListener("pagehide", onPageHide)
        cleanups += { window.removeEventListener("pagehide", onPageHide) }
    }

    private fun renderPhase(
        panel: SimplePanel,
        info: EncounterEntryInfoDto,
    ) {
        header.setSubtitle(sanitizeUntrustedI18nText(info.space.title))
        closedPhase = false
        when {
            info.space.archived -> panel.div(tr("Dieser Raum wurde archiviert."), className = "text-muted")
            !info.space.open -> renderClosed(panel, info.space)
            else -> panel.encounterEntryPanel(info) { consent -> enter(info.space, consent) }
        }
    }

    private fun renderClosed(
        panel: SimplePanel,
        space: EncounterSpaceDto,
    ) {
        closedPhase = true
        val box = panel.vPanel(spacing = 8) { addCssClasses("border rounded p-3 lapis-encounter-closed") }
        val text = space.closedNotice
        if (text.isNullOrBlank()) {
            box.div(
                tr("Der Raum ist geschlossen."),
                className = "fw-bold",
            )
        } else {
            box.untrustedP(text, className = "fw-bold mb-0")
        }
        if (space.canModerate) {
            val open = newActionButton(ActionIcon.OPEN_DOORS, tr("Türen öffnen"), ButtonStyle.PRIMARY)
            box.add(open)
            open.onClick {
                open.disabled = true
                AppScope.launch {
                    val opened = guarded { rpcService<IEncounterSpaceService>().openSpace(space.id) }
                    if (opened != null) section.reload() else open.disabled = false
                }
            }
        }
    }

    // ── the closed room: ask now and then whether the doors opened ───────────

    private fun startClosedPoll() {
        val handle =
            window.setInterval({
                if (closedPhase && !tornDown && !leaving && pageVisible()) AppScope.launch { checkOpened() }
            }, CLOSED_POLL_MS)
        cleanups += { window.clearInterval(handle) }
        val onVisible: (org.w3c.dom.events.Event) -> Unit = {
            if (closedPhase && !tornDown && pageVisible()) AppScope.launch { checkOpened() }
        }
        document.addEventListener("visibilitychange", onVisible)
        cleanups += { document.removeEventListener("visibilitychange", onVisible) }
    }

    private suspend fun checkOpened() {
        val fresh =
            try {
                rpcService<IEncounterSpaceService>().getSpace(spaceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return
            }
        if (fresh.open && closedPhase && !tornDown) section.reload()
    }

    private fun pageVisible(): Boolean = (document.asDynamic().visibilityState as? String) != "hidden"

    // ── entering ─────────────────────────────────────────────────────────────

    /** Runs inside the click's coroutine: enter, build the room, connect, switch the pulpit camera on -- one chain. */
    private suspend fun enter(
        space: EncounterSpaceDto,
        consent: EncounterConsentInput?,
    ) {
        if (tornDown || entered) return
        val entry =
            guarded {
                try {
                    rpcService<IEncounterSpaceService>().enterSpace(space.id, consent)
                } catch (e: ConflictException) {
                    notifyError(tr("Der Raum ist voll oder gerade geschlossen."))
                    section.reload()
                    null
                } catch (e: ForbiddenException) {
                    notifyError(tr("Sie können diesen Raum nicht betreten."))
                    null
                } catch (e: NotFoundException) {
                    notifyError(tr("Diesen Raum gibt es nicht."))
                    null
                }
            } ?: return
        if (tornDown) {
            // The screen went away while the entry was in flight: the server has a presence row now, take it back.
            runCatching { rpcService<IEncounterSpaceService>().leaveSpace(space.id) }
            return
        }
        entered = true
        notice.content = ""
        val rights =
            EncounterViewerRights(
                presenceRole = entry.presenceRole,
                canPublish = entry.canPublish,
                canPublishData = entry.canPublishData,
                selfIdentity = entry.join.identity,
                isPrivileged = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN),
            )
        val newRoom =
            EncounterRoom(
                parent = insidePanel,
                space = space,
                entry = entry,
                viewer = rights,
                clock = clock,
                onDoorsClosed = { exitRoom(message = termsFor(space.profile).eventEndedContent()) },
                onConnectionLost = { cause -> onConnectionLost(space, cause) },
            )
        val newSession = opener(entry, newRoom.callbacks)
        newRoom.bind(newSession)
        room = newRoom
        session = newSession
        closedPhase = false
        preEntry.hide()
        leaveButton.show()
        val failure = newSession.connect()
        // The visit may have ended while connect() was in flight (Verlassen, route change): then nothing of it is ours any more.
        if (tornDown || room !== newRoom) return
        if (failure != null) {
            notifyError(conferenceConnectErrorMessage(failure))
            exitRoom(message = null)
            return
        }
        newRoom.afterConnected()
    }

    // ── leaving ──────────────────────────────────────────────────────────────

    private fun leave() {
        exitRoom(message = null)
    }

    /** Ends the visit: widgets and timers go, the session disconnects, the server is told, the pre-entry view returns (freshly loaded). */
    private fun exitRoom(message: String?) {
        if (!entered) return
        leaving = true
        val endingRoom = room
        val endingSession = session
        room = null
        session = null
        entered = false
        endingRoom?.dispose()
        insidePanel.removeAll()
        leaveButton.hide()
        if (message != null) notice.content = message
        preEntry.show()
        section.reload()
        AppScope.launch {
            runCatching { endingSession?.disconnect() }
            runCatching { rpcService<IEncounterSpaceService>().leaveSpace(spaceId) }
            leaving = false
        }
    }

    private fun onConnectionLost(
        space: EncounterSpaceDto,
        cause: DisconnectCause,
    ) {
        if (!entered || leaving || tornDown) return
        val endingRoom = room
        val endingSession = session
        room = null
        session = null
        entered = false
        endingRoom?.dispose()
        insidePanel.removeAll()
        leaveButton.hide()
        AppScope.launch {
            runCatching { endingSession?.disconnect() }
            if (cause == DisconnectCause.DuplicateIdentity) {
                // V1.9.69: the same account is in this room on another device. Decided BEFORE getSpace/reentryAllowed (the two
                // devices would otherwise evict each other until the re-entry limit). Deliberately no leaveSpace: it deletes the
                // member's presence row and the other device would be thrown out by the presence poller.
                if (tornDown) return@launch
                var card: Div? = null
                card =
                    insidePanel.conferenceConnectionStoppedNotice(
                        kind = ConnectionStoppedKind.Displaced,
                        onResume = {
                            // The room view is built inside insidePanel: the card steps aside while the attempt runs and comes back
                            // if it fails.
                            card?.hide()
                            enter(space, consent = null)
                            if (entered) card?.let { insidePanel.remove(it) } else card?.show()
                            entered
                        },
                        onOverview = {
                            insidePanel.removeAll()
                            showEntryAgain("")
                        },
                    )
                return@launch
            }
            val fresh =
                try {
                    rpcService<IEncounterSpaceService>().getSpace(space.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
            if (tornDown) return@launch
            when {
                fresh == null -> showEntryAgain(tr("Die Verbindung wurde unterbrochen."))
                !fresh.open -> showEntryAgain(termsFor(fresh.profile).eventEndedContent())
                reentryAllowed() -> reenter(fresh)
                else -> showEntryAgain(tr("Die Verbindung wurde unterbrochen."))
            }
        }
    }

    private fun showEntryAgain(message: String) {
        notice.content = message
        preEntry.show()
        section.reload()
    }

    private suspend fun reenter(space: EncounterSpaceDto) {
        val refused =
            try {
                rpcService<IEncounterSpaceService>().getEntryInfo(space.id)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForbiddenException) {
                true
            } catch (e: Throwable) {
                false
            }
        if (refused) {
            showEntryAgain(tr("Sie wurden aus diesem Raum entfernt."))
            return
        }
        // No consent is sent: a guest who consented before is still on record; a missing consent comes back as a conflict and lands on
        // the entry panel (which asks again).
        enter(space, consent = null)
        if (!entered && !tornDown) showEntryAgain(tr("Die Verbindung wurde unterbrochen."))
    }

    private fun reentryAllowed(): Boolean {
        val now = clock()
        while (reentries.isNotEmpty() && now - reentries.first() > REENTRY_WINDOW_MS) reentries.removeFirst()
        if (reentries.size >= REENTRY_MAX) return false
        reentries.addLast(now)
        return true
    }

    // ── teardown ─────────────────────────────────────────────────────────────

    /** Best effort for a closing page: disconnect and tell the server (a cancelled RPC is cleaned up by the server's presence poller). */
    private fun endBestEffort() {
        if (!entered) return
        val endingSession = session
        AppScope.launch {
            runCatching { endingSession?.disconnect() }
            runCatching { rpcService<IEncounterSpaceService>().leaveSpace(spaceId) }
        }
    }

    fun teardown() {
        if (tornDown) return
        tornDown = true
        cleanups.forEach { it() }
        cleanups.clear()
        val endingRoom = room
        val endingSession = session
        val wasEntered = entered
        room = null
        session = null
        entered = false
        endingRoom?.dispose()
        if (wasEntered) {
            AppScope.launch {
                runCatching { endingSession?.disconnect() }
                runCatching { rpcService<IEncounterSpaceService>().leaveSpace(spaceId) }
            }
        }
    }
}
