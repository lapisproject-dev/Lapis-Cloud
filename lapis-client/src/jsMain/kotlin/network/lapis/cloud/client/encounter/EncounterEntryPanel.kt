package network.lapis.cloud.client.encounter

import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.addCssClasses
import network.lapis.cloud.client.lapisForm
import network.lapis.cloud.client.newActionButton
import network.lapis.cloud.client.untrustedDiv
import network.lapis.cloud.client.untrustedHeading
import network.lapis.cloud.client.untrustedP
import network.lapis.cloud.shared.domain.EncounterConsentInput
import network.lapis.cloud.shared.domain.EncounterEntryInfoDto

/**
 * V1.9.62 Begegnungsraum (B2) -- the entry panel: the most important surface of the room (design team, Norman): before the first click
 * the person knows what others see of them, what leaves the room and what is stored. NOT a modal: it sits in the page, so a screen
 * reader reads it in order and nothing hides behind a backdrop.
 *
 * The panel states three plain sentences and the Art. 9 GDPR remark (taking part in a service can reveal a religious belief, "Dieser
 * Hinweis ist keine Rechtsberatung."). A NON-member additionally sees the server's consent text ([EncounterEntryInfoDto.disclaimer]):
 * headline and key points as untrusted text, the full text behind a disclosure, and a required tick box. The entry button stays disabled
 * until the box is ticked. The consent version and hash are handed to [onEnter] UNMODIFIED (the server compares them in constant time and
 * refuses a mismatch). There is no link to any streaming platform: whether the pulpit is transmitted is the operator's decision and is
 * announced in the room itself.
 *
 * [onEnter] runs inside the click's own coroutine (the form's `submit`), so the entry, the connection and -- for an office holder -- the
 * first device switch happen in one chain that starts at the user's click.
 */
internal fun SimplePanel.encounterEntryPanel(
    info: EncounterEntryInfoDto,
    onEnter: suspend (EncounterConsentInput?) -> Unit,
) {
    val box = vPanel(spacing = 8) { addCssClasses("border rounded p-3 lapis-encounter-entry") }
    box.h2(tr("Bevor Sie eintreten")) { addCssClass("h5") }
    val notes = Tag(TAG.UL, className = "lapis-encounter-notes mb-0")
    box.add(notes)
    notes.add(
        Tag(
            TAG.LI,
            content =
                tr(
                    "Wird die Kanzel übertragen, sehen Zuschauer außerhalb des Raums nur Bild und Ton der Kanzel. " +
                        "Sie selbst werden nicht übertragen.",
                ),
        ),
    )
    notes.add(
        Tag(
            TAG.LI,
            content = tr("Andere Anwesende sehen Ihren Namen, solange Sie im Raum sind. Ihre Kamera und Ihr Mikrofon bleiben aus."),
        ),
    )
    notes.add(Tag(TAG.LI, content = tr("Lapis Cloud speichert keine Anwesenheitsliste und keine Aufzeichnung dieses Raums.")))
    box.div(
        tr(
            "Die Teilnahme an einem Gottesdienst kann Rückschlüsse auf religiöse Überzeugungen zulassen (besondere Kategorie personenbezogener Daten, Art. 9 DSGVO). Dieser Hinweis ist keine Rechtsberatung.",
        ),
        className = "text-muted small",
    )

    val disclaimer = if (info.consentRequired) info.disclaimer else null
    if (disclaimer != null) {
        box.untrustedHeading(disclaimer.headline, level = 3, className = "h6 mb-0")
        disclaimer.keyPoints.forEach { point -> box.untrustedDiv(point, className = "small") }
        val details = Tag(TAG.DETAILS, className = "small")
        details.add(Tag(TAG.SUMMARY, content = tr("Vollständigen Text anzeigen")))
        details.untrustedP(disclaimer.text, className = "mt-2 mb-0")
        box.add(details)
    }
    val form = box.lapisForm()
    val consentField =
        if (disclaimer != null) {
            form.checkField(
                label = tr("Ich habe den Hinweis gelesen und möchte eintreten."),
                required = true,
                requiredMessage = tr("Bitte bestätigen Sie den Hinweis, um einzutreten."),
            )
        } else {
            null
        }
    val enterButton = newActionButton(ActionIcon.ENTER, encounterEnterLabel(info.space), ButtonStyle.PRIMARY)
    if (consentField != null) {
        enterButton.disabled = true
        consentField.subscribe { enterButton.disabled = it != "true" }
    }
    form.buttons(primary = enterButton)
    enterButton.onClick {
        form.submit(enterButton) {
            val consent =
                if (disclaimer != null) {
                    EncounterConsentInput(consentVersion = disclaimer.version, consentSha256 = disclaimer.sha256)
                } else {
                    null
                }
            onEnter(consent)
        }
    }
}
