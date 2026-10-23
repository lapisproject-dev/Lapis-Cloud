package network.lapis.cloud.client.encounter

import io.kvision.html.ButtonStyle
import io.kvision.modal.Modal
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.newActionButton
import network.lapis.cloud.client.renderAgreementBody
import network.lapis.cloud.client.untrustedDiv
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.PulpitRecordingTextsDto
import org.w3c.dom.HTMLElement

/**
 * V1.9.143 -- the dialog in which the person at the pulpit agrees (or not) to being recorded. It opens when the server asks (a packet with the ask flag)
 * or when the pulpit person starts the recording themselves.
 *
 * - One short text: the `REC_PULPIT` text of the operator ([PulpitRecordingTextsDto.pulpitText], untrusted text), nothing else.
 * - The keyboard focus starts on "Nicht aufzeichnen": the safe answer is the default one, and a key press that was meant for something else cannot
 *   agree to being recorded. Escape, the close button and a click outside all mean "no" ([onDecline]).
 * - Agreeing sends the label and hash of the text exactly as the server delivered them ([PulpitRecordingTextsDto]); the client never computes a hash.
 * - Both answers run at most once.
 *
 * The dialog lives at body level, like every confirmation of the client; the room leaves the full screen before it opens it.
 */
internal fun showPulpitRecordingConsent(
    terms: EncounterTerms,
    texts: PulpitRecordingTextsDto,
    onAccept: (PulpitRecordingTextsDto) -> Unit,
    onDecline: () -> Unit,
) {
    val modal = Modal(caption = terms.recordingConsentTitle())
    // V1.9.196: a MARKDOWN text arrives as server-rendered HTML (shown through the one rich site of the agreement renderer); plain text stays text.
    if (texts.pulpitFormat == AgreementTextFormat.MARKDOWN && texts.pulpitHtml != null) {
        modal.renderAgreementBody(text = texts.pulpitText, format = texts.pulpitFormat, renderedHtml = texts.pulpitHtml)
    } else {
        modal.untrustedDiv(texts.pulpitText)
    }
    var decided = false
    val decline = newActionButton(ActionIcon.CANCEL, terms.recordingConsentDecline(), ButtonStyle.SECONDARY)
    decline.onClick {
        if (!decided) {
            decided = true
            modal.hide()
            onDecline()
        }
    }
    modal.addButton(decline)
    val accept = newActionButton(ActionIcon.APPROVE, terms.recordingConsentAccept(), ButtonStyle.PRIMARY)
    accept.onClick {
        if (!decided) {
            decided = true
            accept.disabled = true
            modal.hide()
            onAccept(texts)
        }
    }
    modal.addButton(accept)
    modal.addAfterInsertHook { vnode ->
        val element = vnode.elm as? HTMLElement
        // Two moments, because Bootstrap decides the first (see confirmDialog): the focus goes to the safe answer.
        element?.addEventListener("shown.bs.modal", { decline.getElement()?.focus() })
        // Any other way to close the dialog (Escape, the close button, a click outside) is a "no".
        element?.addEventListener(
            "hidden.bs.modal",
            {
                if (!decided) {
                    decided = true
                    onDecline()
                }
            },
        )
        kotlinx.browser.window.setTimeout({ decline.getElement()?.focus() }, 0)
    }
    modal.show()
}
