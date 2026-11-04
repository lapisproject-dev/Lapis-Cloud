package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.core.onEvent
import io.kvision.form.check.checkBox
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.VPanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import network.lapis.cloud.client.encounter.renderEncounterConsentPreview
import network.lapis.cloud.shared.domain.EncounterConsentLayout
import network.lapis.cloud.shared.domain.EventCaptureAcknowledgmentInput
import network.lapis.cloud.shared.domain.EventCaptureNoticeDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.KeyboardEvent

/**
 * V1.9.156 -- which capture consent a person already acknowledged in THIS tab, per event: a lost connection must reconnect without a new dialog as long as
 * the text did not change, while a NEW text (version or hash) always asks again. Memory only: nothing is stored in the browser, a reload asks again.
 */
internal object EventCaptureConsentMemory {
    private val seen = HashMap<String, String>()

    private fun fingerprint(notice: EventCaptureNoticeDto) = "${notice.versionLabel}|${notice.sha256}"

    fun remember(
        eventId: String,
        notice: EventCaptureNoticeDto,
    ) {
        seen[eventId] = fingerprint(notice)
    }

    /** The notice that was ACTUALLY acknowledged (the dialog may have reloaded a newer text than the one it was opened with). */
    fun remember(
        eventId: String,
        input: EventCaptureAcknowledgmentInput,
    ) {
        seen[eventId] = "${input.consentVersion}|${input.consentSha256}"
    }

    fun covers(
        eventId: String,
        notice: EventCaptureNoticeDto,
    ): Boolean = seen[eventId] == fingerprint(notice)

    fun forget(eventId: String) {
        seen.remove(eventId)
    }

    /** Called by `AppState.setSession` when the identity changes (sign-out, another account): a consent belongs to the person who gave it. */
    fun clear() {
        seen.clear()
    }
}

/** The consent text as the person is shown it: the headline, the key points, the full text behind a disclosure -- always untrusted element text. */
internal fun Container.renderEventCaptureNotice(notice: EventCaptureNoticeDto) {
    renderEncounterConsentPreview(
        EncounterConsentLayout.Split(headline = notice.headline, keyPoints = notice.keyPoints, text = notice.fullText),
        format = notice.format,
        renderedHtml = notice.renderedHtml,
    )
}

/**
 * V1.9.156 -- the entry screen of a member (or a guest WITH an account) into a released event room. Not a modal: it sits in the page, so a screen reader
 * reads it in order and nothing hides behind a backdrop. The focus starts on the heading, Escape and "Abbrechen" cancel, "Teilnehmen" stays disabled until
 * every box is ticked.
 *
 * It states what the room does ([notice]: the headline, three key points, the full text behind a disclosure) and asks for the consent in one box. A guest
 * account ([withGuestNotice]) sees the capture variant of the guest notice below it (it must not say that nothing is recorded) with its own box, on the
 * SAME screen: two decisions, two boxes, one button.
 *
 * Suspends until the person confirms (`true`) or cancels (`false`); the coroutine ends with the screen on a route change.
 */
internal suspend fun awaitEventCaptureNotice(
    host: SimplePanel,
    notice: EventCaptureNoticeDto,
    withGuestNotice: Boolean,
): Boolean {
    val decision = CompletableDeferred<Boolean>()
    host.removeAll()
    // The panel is created first and added with its hook (addWithLifecycle): a hook registered after the panel went into the host would come too late.
    val block = VPanel(spacing = 8) { addCssClasses("lapis-event-capture-consent") }
    val heading = block.h2(tr("Aufzeichnung und Übertragung in diesem Raum")) { addCssClass("h5") }
    heading.setAttribute("tabindex", "-1")
    block.renderEventCaptureNotice(notice)
    val form = block.lapisForm()
    val consentField =
        form.checkField(
            label = tr("Ich willige ein, dass mein Bild und mein Ton aufgezeichnet und live übertragen werden dürfen."),
            required = true,
            requiredMessage = tr("Bitte bestätigen Sie die Einwilligung, um teilzunehmen."),
        )
    val guestField =
        if (withGuestNotice) {
            // The one sentence of the capture variant -- [network.lapis.cloud.shared.domain.EventGuestNoticeCapture.TEXT] word for word (`EventCaptureBuiltinTextPinTest` checks the client source).
            form.panel.div(tr("Ihr Name ist für alle Teilnehmenden sichtbar. Ihr Mikrofon ist zu Beginn aus.")) {
                addCssClasses("alert alert-info mb-0")
                setAttribute("role", "note")
            }
            form.checkField(
                label = tr("Ich habe den Hinweis gelesen."),
                required = true,
                requiredMessage = tr("Bitte bestätigen Sie den Hinweis."),
            )
        } else {
            null
        }
    val joinButton = newActionButton(ActionIcon.ENTER, tr("Teilnehmen"), ButtonStyle.PRIMARY)
    val cancelButton = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.OUTLINESECONDARY)
    joinButton.disabled = true

    fun syncJoin() {
        joinButton.disabled = consentField.value != "true" || (guestField != null && guestField.value != "true")
    }
    consentField.subscribe { syncJoin() }
    guestField?.subscribe { syncJoin() }
    form.buttons(primary = joinButton, cancel = cancelButton)
    cancelButton.onClick { decision.complete(false) }
    joinButton.onClick {
        ConferenceJoinLeaveSounds.unlockFromGesture()
        NoiseSuppressionWarmup.unlockFromGesture()
        form.submit(joinButton, restoreDisabled = { false }) { decision.complete(true) }
    }
    host.addWithLifecycle(
        block,
        onInsert = { vnode ->
            (vnode.elm as? HTMLElement)?.addEventListener(
                "keydown",
                { event -> if ((event as? KeyboardEvent)?.key == "Escape") decision.complete(false) },
            )
            heading.getElement()?.focus()
        },
    )
    return decision.await()
}

/** V1.9.280 -- where the consent is asked: before the entry (a full screen, [awaitEventCaptureNotice]) or INSIDE the room after a release was switched on. */
internal enum class EventCaptureConsentMode { ENTRY, IN_ROOM }

/** What the server answered to an in-room acknowledgment. The dialog closes ONLY on [ACCEPTED]. */
internal enum class InRoomAcceptResult {
    ACCEPTED,

    /** The text changed meanwhile: the dialog reloads it, resets the box and says so. */
    OUTDATED,

    /** Anything else (throttled, refused, network): the dialog stays open; the general message was already shown by the caller. */
    FAILED,
}

/** The handle of an open in-room consent dialog. */
internal class InRoomConsentHandle(
    private val closeAction: () -> Unit,
    private val isOpenCheck: () -> Boolean,
    private val focusAction: () -> Unit,
) {
    /** `true` while the dialog is in the document. */
    val isOpen: Boolean get() = isOpenCheck()

    /** Closes the dialog (after a server-accepted acknowledgment). Idempotent. */
    fun close() = closeAction()

    /** Removes the dialog, its listeners and timers (leaving the room, a route change). Idempotent; the same as [close]. */
    fun dispose() = closeAction()

    /** Puts the focus back on the heading (a second ask while the dialog is already open). */
    fun focusHeading() = focusAction()
}

/**
 * V1.9.280 -- the question INSIDE an event room: "from now on this room may be recorded -- do you agree?" A blocking dialog: nobody can stay in the room
 * without the answer, so there is no way to dismiss it other than the two buttons. **Escape does nothing** (`preventDefault`): a dialog that Escape
 * closed would leave the person in a room they have not agreed to. The focus starts on the heading, Tab stays inside the dialog.
 *
 * Video and sound are NOT touched: the backdrop is a SIBLING element that is only darkened (no `filter`, no `backdrop-filter`, no `transform` on anything
 * that contains the call). The primary button is `aria-disabled` + `aria-describedby` while the box is unticked (R158, never the native `disabled`).
 *
 * [onAccept] talks to the server and returns what it said; the dialog closes only on [InRoomAcceptResult.ACCEPTED]. On
 * [InRoomAcceptResult.OUTDATED] the text is reloaded with [reloadNotice] and the box is reset. [onDecline] leaves the room (no question, no server
 * call of its own). At most one dialog per [parent]: the caller keeps the handle and calls [InRoomConsentHandle.focusHeading] on a second ask.
 */
internal fun showInRoomCaptureConsent(
    parent: Container,
    notice: EventCaptureNoticeDto,
    onAccept: suspend (EventCaptureAcknowledgmentInput) -> InRoomAcceptResult,
    onDecline: () -> Unit,
    reloadNotice: suspend () -> EventCaptureNoticeDto? = { null },
): InRoomConsentHandle {
    var open = true
    var current = notice
    var busy = false
    val serial = ++inRoomConsentSerial
    val headingId = "lapis-event-capture-ask-heading-$serial"
    val reasonId = "lapis-event-capture-ask-reason-$serial"
    // The scrim is a sibling of the dialog: it darkens, it never filters what is behind it (video and sound stay as they are).
    val wrapper = Div(className = "lapis-event-capture-ask-wrapper")
    wrapper.div(className = "lapis-event-capture-ask-scrim") { setAttribute("aria-hidden", "true") }
    val dialog =
        wrapper.vPanel(spacing = 8) {
            addCssClasses("lapis-event-capture-ask lapis-event-capture-consent")
            setAttribute("role", "dialog")
            setAttribute("aria-modal", "true")
            setAttribute("aria-labelledby", headingId)
        }
    val heading = dialog.h2(tr("Ab jetzt darf dieser Raum aufgezeichnet werden")) { addCssClass("h5") }
    heading.setAttribute("id", headingId)
    heading.setAttribute("tabindex", "-1")
    dialog.div(
        tr(
            "Bevor aufgezeichnet oder übertragen wird, braucht es die Zustimmung aller, die im Raum sind. Ohne Ihre Zustimmung können Sie nicht im Raum bleiben.",
        ),
    )
    val noticeHost = dialog.vPanel(spacing = 4)
    noticeHost.renderEventCaptureNotice(current)
    val check =
        dialog.checkBox(
            value = false,
            label = tr("Ich willige ein, dass mein Bild und mein Ton aufgezeichnet und live übertragen werden dürfen."),
        )
    val outdated =
        dialog.div(tr("Der Hinweistext wurde inzwischen geändert. Bitte lesen Sie ihn erneut.")) {
            addCssClasses("text-warning small")
        }
    outdated.setAttribute("role", "status")
    outdated.visible = false
    val reason =
        dialog.div(tr("Bitte setzen Sie den Haken, um im Raum zu bleiben.")) {
            addCssClasses("text-muted small")
            setAttribute("id", reasonId)
        }
    val accept = newActionButton(ActionIcon.APPROVE, tr("Zustimmen und bleiben"), ButtonStyle.PRIMARY)
    val decline = newActionButton(ActionIcon.CANCEL, tr("Ablehnen und Raum verlassen"), ButtonStyle.OUTLINESECONDARY)
    dialog.hPanel(spacing = 8) {
        add(accept)
        add(decline)
    }

    fun sync() {
        val ticked = check.value
        accept.setAttribute("aria-disabled", if (ticked && !busy) "false" else "true")
        if (ticked) accept.removeAttribute("aria-describedby") else accept.setAttribute("aria-describedby", reasonId)
        reason.visible = !ticked
    }
    sync()
    check.onEvent { change = { sync() } }

    fun focusHeading() {
        heading.getElement()?.focus()
    }

    var keyListener: ((org.w3c.dom.events.Event) -> Unit)? = null

    fun teardown() {
        if (!open) return
        open = false
        keyListener?.let { listener -> wrapper.getElement()?.removeEventListener("keydown", listener) }
        keyListener = null
        parent.remove(wrapper)
        wrapper.dispose()
    }

    accept.onClick {
        // aria-disabled blocks nothing by itself: the handler does. An unticked box answers with the reason (already visible), a running request is not repeated.
        if (!check.value || busy) return@onClick
        busy = true
        sync()
        AppScope.launch {
            val result = onAccept(EventCaptureAcknowledgmentInput(consentVersion = current.versionLabel, consentSha256 = current.sha256))
            busy = false
            if (!open) return@launch
            when (result) {
                InRoomAcceptResult.ACCEPTED -> teardown()
                InRoomAcceptResult.OUTDATED -> {
                    reloadNotice()?.let { fresh ->
                        current = fresh
                        noticeHost.removeAll()
                        noticeHost.renderEventCaptureNotice(fresh)
                    }
                    check.value = false
                    outdated.visible = true
                    sync()
                }
                InRoomAcceptResult.FAILED -> sync()
            }
        }
    }
    decline.onClick {
        // No second question: the way out is the normal way out of the room.
        teardown()
        onDecline()
    }
    keyListener = { event ->
        val key = event as? KeyboardEvent
        when (key?.key) {
            // A dialog that Escape closed would leave the person in a room they have not agreed to: Escape does nothing.
            "Escape" -> key.preventDefault()
            "Tab" -> trapFocus(container = dialog.getElement(), event = key)
        }
    }
    parent.addWithLifecycle(
        wrapper,
        onInsert = { vnode ->
            (vnode.elm as? HTMLElement)?.addEventListener("keydown", keyListener)
            focusHeading()
        },
        onDestroy = { teardown() },
    )
    return InRoomConsentHandle(closeAction = ::teardown, isOpenCheck = { open }, focusAction = ::focusHeading)
}

private var inRoomConsentSerial = 0

/** Keeps Tab / Shift+Tab inside [container]: from the last control back to the first and the other way round. */
private fun trapFocus(
    container: HTMLElement?,
    event: KeyboardEvent,
) {
    container ?: return
    val focusable =
        container
            .querySelectorAll("button, input, a[href], [tabindex]:not([tabindex='-1'])")
            .asList()
            .filterIsInstance<HTMLElement>()
            .filter { !it.hasAttribute("disabled") }
    if (focusable.isEmpty()) {
        event.preventDefault()
        return
    }
    val first = focusable.first()
    val last = focusable.last()
    val active = document.activeElement
    if (event.shiftKey && (active === first || active === container || !container.contains(active))) {
        event.preventDefault()
        last.focus()
    } else if (!event.shiftKey && (active === last || !container.contains(active))) {
        event.preventDefault()
        first.focus()
    }
}
