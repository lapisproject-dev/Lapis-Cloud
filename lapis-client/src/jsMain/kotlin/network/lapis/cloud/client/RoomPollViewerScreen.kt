package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.RoomPollStateDto
import network.lapis.cloud.shared.domain.RoomPollStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.w3c.dom.events.Event

/**
 * Room polls, wave B -- the page a stream viewer opens from the link or QR code (`#/room-poll/<roomId>`): one question, big answer buttons, the
 * result afterwards. It is the ONLY thing this route does: it never joins the room, never loads LiveKit and never shows the room's name; the
 * viewer keeps watching the stream on its own platform. The server decides on every call who may see what (the link grants nothing); every
 * refusal looks the same here ("Zu diesem Raum läuft kein Stream.").
 */
internal class RoomPollViewerHandle(
    val dispose: () -> Unit,
    /** Tests: the controller's recheck, as the button calls it. */
    val recheck: () -> Unit,
)

/** The browser seams of the page (visibility, connectivity, clock, random): a test replaces them. */
internal interface RoomPollViewerEnv {
    val scheduler: ConferenceVoteScheduler
    val scope: CoroutineScope
    val random: () -> Double

    fun isHidden(): Boolean

    /** Registers the three triggers; returns the function that removes them again. */
    fun listen(
        onVisibility: () -> Unit,
        onOnline: () -> Unit,
        onOffline: () -> Unit,
    ): () -> Unit
}

internal object BrowserRoomPollViewerEnv : RoomPollViewerEnv {
    override val scheduler: ConferenceVoteScheduler = BrowserVoteScheduler
    override val scope: CoroutineScope = AppScope
    override val random: () -> Double = { kotlin.random.Random.nextDouble() }

    override fun isHidden(): Boolean = document.asDynamic().visibilityState == "hidden"

    override fun listen(
        onVisibility: () -> Unit,
        onOnline: () -> Unit,
        onOffline: () -> Unit,
    ): () -> Unit {
        val visibility: (Event) -> Unit = { onVisibility() }
        val online: (Event) -> Unit = { onOnline() }
        val offline: (Event) -> Unit = { onOffline() }
        document.addEventListener("visibilitychange", visibility)
        window.addEventListener("online", online)
        window.addEventListener("offline", offline)
        return {
            document.removeEventListener("visibilitychange", visibility)
            window.removeEventListener("online", online)
            window.removeEventListener("offline", offline)
        }
    }
}

internal const val ROOM_POLL_VIEWER_CLASS = "lapis-room-poll-viewer"
internal const val ROOM_POLL_VIEWER_OPTION_CLASS = "lapis-room-poll-viewer-option"

internal fun renderRoomPollViewerScreen(
    parent: Container,
    roomId: String?,
    authenticated: Boolean,
    rpc: RoomPollViewerRpc = RoomPollViewerRpc(),
    env: RoomPollViewerEnv = BrowserRoomPollViewerEnv,
): RoomPollViewerHandle {
    val validId = isRoomPollRoomId(roomId)
    val root = parent.vPanel(spacing = 12) { addCssClass(ROOM_POLL_VIEWER_CLASS) }
    root.pageHeader(title = tr("Umfrage im Stream"))
    // the one live region: only changes noticed by polling are written into it; it is cleared first so the same sentence twice is still read
    val liveRegion =
        root.div("") {
            addCssClass("visually-hidden")
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
        }
    val note = root.div("") { addCssClasses("text-danger lapis-room-poll-viewer-note") }
    note.setAttribute("role", "alert")
    note.hide()
    val body = root.vPanel(spacing = 12) { addCssClass("lapis-room-poll-viewer-body") }

    var state: RoomPollStateDto? = null
    var received = false
    var unavailable = !validId && authenticated
    var needsLogin = !authenticated
    var shownPhase: RoomPollViewerPhase? = null
    var bodySlice: Any? = null
    var info: String? = null
    var busy = false
    var disposed = false
    var paintRef: () -> Unit = {}
    var stopListening: (() -> Unit)? = null
    var startTimer: Int? = null
    lateinit var controller: RoomPollViewerController

    fun phase(): RoomPollViewerPhase =
        when {
            needsLogin -> RoomPollViewerPhase.LOGIN
            unavailable -> RoomPollViewerPhase.UNAVAILABLE
            else -> roomPollViewerPhase(state)
        }

    fun announce(text: String) {
        liveRegion.content = ""
        liveRegion.content = text
    }

    fun showNote(text: String?) {
        if (text == null) {
            note.hide()
        } else {
            note.content = text
            note.show()
        }
    }

    fun resultBlock(container: Container) {
        val current = state ?: return
        val poll = current.poll ?: return
        val result = current.result ?: return
        if (poll.status != RoomPollStatus.CLOSED) return
        container.tag(TAG.H2, content = sanitizeUntrustedI18nText(poll.question), className = "h5 mb-0")
        container.roomPollResultView(poll, result)
    }

    fun answer(optionId: String) {
        val poll = state?.poll ?: return
        if (busy || disposed) return
        busy = true
        showNote(null)
        // one tap locks the page: the chosen button is marked and every button is off until the answer is back
        body.getChildren().filterIsInstance<Tag>().forEach { button ->
            if (button.getAttribute("data-room-poll-option") != null) {
                if (button.getAttribute("data-room-poll-option") == optionId) button.setAttribute("aria-pressed", "true")
                button.setAttribute("disabled", "")
            }
        }
        env.scope.launch {
            try {
                controller.adopt(rpc.answer(roomId!!, poll.id, optionId))
            } catch (e: ConflictException) {
                // Kilua carries only the TYPE: a double answer, a poll that just ended and a rate limit all arrive as a conflict. Ask the state.
                try {
                    val fresh = rpc.state(roomId!!)
                    val closed = fresh.poll?.status != RoomPollStatus.OPEN || fresh.poll?.id != poll.id
                    if (fresh.voted) {
                        info = gettext("Ihre Stimme wurde bereits gezählt.")
                    } else if (closed) {
                        info = gettext("Die Umfrage wurde gerade beendet.")
                    } else {
                        showNote(gettext("Die Antwort konnte nicht gesendet werden. Bitte versuchen Sie es erneut."))
                    }
                    controller.adopt(fresh)
                } catch (e2: Throwable) {
                    showNote(gettext("Die Antwort konnte nicht gesendet werden. Bitte versuchen Sie es erneut."))
                }
                bodySlice = null
            } catch (e: ForbiddenException) {
                unavailable = true
                bodySlice = null
            } catch (e: UnauthenticatedException) {
                needsLogin = true
                bodySlice = null
            } catch (e: Throwable) {
                showNote(gettext("Die Antwort konnte nicht gesendet werden. Bitte versuchen Sie es erneut."))
                bodySlice = null
            } finally {
                busy = false
            }
            if (!disposed) paintRef()
        }
    }

    fun paintBody(current: RoomPollViewerPhase) {
        body.removeAll()
        when (current) {
            RoomPollViewerPhase.LOGIN -> {
                body.div(gettext("Bitte melden Sie sich an, um an der Umfrage teilzunehmen."), className = "lapis-room-poll-viewer-login")
                body.actionButton(ActionIcon.SIGN_IN_CENTRAL, tr("Anmelden"), style = ButtonStyle.PRIMARY) {
                    onClick {
                        rememberRoomPollReturn(roomId)
                        navigateTo(Routes.LOGIN)
                    }
                }
            }
            RoomPollViewerPhase.UNAVAILABLE -> {
                body.div(gettext("Zu diesem Raum läuft kein Stream."), className = "lapis-room-poll-viewer-unavailable")
                if (validId) {
                    body.actionButton(ActionIcon.REFRESH, tr("Erneut prüfen"), style = ButtonStyle.OUTLINESECONDARY) {
                        onClick { controller.recheck() }
                    }
                }
                // a result that was already on screen stays below the sentence
                resultBlock(body)
            }
            RoomPollViewerPhase.LOADING -> body.div(gettext("Wird geladen …"), className = "text-muted")
            RoomPollViewerPhase.NO_POLL ->
                body.div(gettext("Gerade läuft keine Umfrage. Diese Seite aktualisiert sich selbst."), className = "text-muted")
            RoomPollViewerPhase.OPEN -> {
                val poll = state?.poll
                if (poll != null) {
                    info?.let { body.div(it, className = "text-muted") }
                    body.tag(TAG.H2, content = sanitizeUntrustedI18nText(poll.question), className = "h4 lapis-room-poll-viewer-question")
                    poll.options.sortedBy { it.position }.forEach { option ->
                        val button =
                            body.tag(
                                TAG.BUTTON,
                                content = sanitizeUntrustedI18nText(option.text),
                                className = "btn btn-outline-primary w-100 text-start $ROOM_POLL_VIEWER_OPTION_CLASS",
                            )
                        button.setAttribute("type", "button")
                        button.setAttribute("aria-pressed", "false")
                        button.setAttribute("data-room-poll-option", option.id)
                        button.onClick { answer(option.id) }
                    }
                }
            }
            RoomPollViewerPhase.ANSWERED -> {
                val row = body.div(className = "d-flex align-items-center gap-2 lapis-room-poll-viewer-counted")
                val icon = row.tag(TAG.SPAN, className = ActionIcon.APPROVE.css)
                icon.setAttribute("aria-hidden", "true")
                row.div(info ?: gettext("Ihre Stimme wurde gezählt."), className = "fw-semibold")
                // the chosen option is never shown, not after a reload either (the server does not send it and nothing is stored here)
                body.div(gettext("Das Ergebnis erscheint hier, sobald die Umfrage beendet ist."), className = "text-muted")
            }
            RoomPollViewerPhase.CLOSED -> {
                info?.let { body.div(it, className = "text-muted") }
                resultBlock(body)
            }
        }
    }

    fun paint() {
        if (disposed) return
        val current = phase()
        val poll = state?.poll
        val slice = listOf(current, poll?.id, poll?.status, state?.voted, state?.result?.totalAnswerCount, info)
        if (slice != bodySlice) {
            bodySlice = slice
            paintBody(current)
        }
        // the tab title says that a poll waits for an answer; every other phase takes it away again
        PageTitle.setSubtitle(if (current == RoomPollViewerPhase.OPEN) gettext("Umfrage läuft") else null)
        shownPhase = current
    }

    fun onState(fresh: RoomPollStateDto) {
        if (disposed) return
        val before = phase()
        if (fresh.poll?.id != state?.poll?.id) info = null
        state = fresh
        unavailable = false
        val first = !received
        received = true
        val next = phase()
        // an answer the person gave is confirmed with the existing sentence; changes noticed by polling use their own texts
        val text =
            if (before == RoomPollViewerPhase.OPEN && next == RoomPollViewerPhase.ANSWERED) {
                gettext("Ihre Stimme wurde gezählt.")
            } else {
                roomPollViewerAnnouncement(before, next, first)
            }
        text?.let { announce(it) }
        paint()
    }

    controller =
        RoomPollViewerController(
            roomId = roomId.orEmpty(),
            rpc = rpc,
            isHidden = env::isHidden,
            scheduler = env.scheduler,
            scope = env.scope,
            random = env.random,
            onState = ::onState,
            onRefused = { unauthenticated ->
                if (!disposed) {
                    if (unauthenticated) needsLogin = true else unavailable = true
                    paint()
                }
            },
        )

    // The lifecycle hooks are registered BEFORE the widget is added (see KvisionLifecycle.kt). Insert and destroy can run more than once (a
    // language change rebuilds the DOM from the same widget objects): both are idempotent and the page restarts cleanly.
    val anchor = Div()
    root.addWithLifecycle(
        anchor,
        onInsert = {
            if (authenticated && validId && stopListening == null && !disposed) {
                consumeRoomPollReturn()
                stopListening =
                    env.listen(
                        onVisibility = { controller.onVisibilityChanged() },
                        onOnline = { controller.onOnline() },
                        onOffline = { controller.onOffline() },
                    )
                // not inside the insert patch: the first state would re-render the page in the middle of the patch that inserts it
                startTimer =
                    env.scheduler.schedule(0) {
                        startTimer = null
                        if (!disposed) controller.start()
                    }
            }
        },
        onDestroy = {
            startTimer?.let { env.scheduler.cancel(it) }
            startTimer = null
            stopListening?.invoke()
            stopListening = null
            controller.stop()
        },
    )
    paintRef = ::paint
    paint()
    return RoomPollViewerHandle(
        dispose = {
            if (!disposed) {
                disposed = true
                startTimer?.let { env.scheduler.cancel(it) }
                startTimer = null
                stopListening?.invoke()
                stopListening = null
                controller.stop()
                PageTitle.setSubtitle(null)
            }
        },
        recheck = { controller.recheck() },
    )
}
