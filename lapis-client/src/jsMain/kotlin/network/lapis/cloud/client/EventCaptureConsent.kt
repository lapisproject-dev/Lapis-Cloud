package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.VPanel
import kotlinx.coroutines.CompletableDeferred
import network.lapis.cloud.client.encounter.renderEncounterConsentPreview
import network.lapis.cloud.shared.domain.EncounterConsentLayout
import network.lapis.cloud.shared.domain.EventCaptureNoticeDto
import org.w3c.dom.HTMLElement
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
