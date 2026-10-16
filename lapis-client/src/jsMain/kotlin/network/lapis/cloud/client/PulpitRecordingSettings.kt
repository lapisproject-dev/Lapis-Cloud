package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.form.check.CheckBox
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h3
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.PULPIT_RECORDING_NOTICE_VERSION
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.PulpitRecordingNoticeOutdatedException

/*
 * V1.9.143 -- the ADMIN setting of the optional recording of the pulpit in the encounter room ("Begegnungsraum" section of the "Videokonferenz" card).
 *
 * Whether a recording of a person is lawful depends on the country of the instance, so Lapis Cloud does not decide it: the feature is OFF, and an admin
 * switches it on only after reading the operator notice (below) and ticking that they have checked the legal admissibility for their country. The
 * server pins the notice's version (`PULPIT_RECORDING_NOTICE_VERSION`) and hash; a changed notice asks for a new acknowledgment before any NEW recording
 * can start (a running one is not interrupted).
 *
 * - Switching ON opens the notice dialog (focus on "Abbrechen", Escape cancels, "Einschalten" stays locked until the box is ticked).
 * - Switching OFF is one click (Tesler: the safe direction needs no dialog); the server then also stops every running recording.
 * - The second switch (the pulpit has to agree before a start) is shown only while the feature is on.
 * - Without a recording service on this server (`pulpitRecordingInfrastructureAvailable == false`) the switch is locked and says why.
 */

private var pulpitSwitchSerial = 0

/** The paragraphs of the operator notice. Every one is a plain literal: the server pins the German reference text word for word (`PulpitRecordingNoticePinTest`). */
internal val PULPIT_RECORDING_NOTICE_PARAGRAPHS: List<String> by lazy {
    listOf(
        tr(
            "Sie sind im Begriff, die Aufzeichnung der Kanzel in den Begegnungsräumen dieser Instanz zu erlauben. Aufgezeichnet werden ausschließlich Bild und Ton der Person, die das Amt der Kanzel (in Versammlungen: des Podiums) innehat. Die Gemeinde (in Versammlungen: die Teilnehmenden) wird nicht aufgezeichnet.",
        ),
        tr(
            "Ob und unter welchen Bedingungen eine solche Aufzeichnung zulässig ist, hängt vom Recht Ihres Landes ab, zum Beispiel vom Datenschutz-, Persönlichkeits- und Urheberrecht und von Vorgaben für Religionsgemeinschaften und Versammlungen. Lapis Cloud nimmt diese Prüfung nicht vor und ersetzt keine Rechtsberatung.",
        ),
        tr(
            "Als Betreiber sind Sie dafür verantwortlich, dass Sie eine Rechtsgrundlage haben und dass die aufgezeichnete Person informiert ist und, wo erforderlich, einwilligt. Die Aufnahme wird in der Dokumentenablage gespeichert, ist nur für Vorstand und Administratoren sichtbar und bleibt bis zu ihrer Löschung bestehen.",
        ),
        tr(
            "Zeigt die Kamera Personen, die vor Ort neben der Kanzel anwesend sind, werden auch diese aufgezeichnet. Das kann die Software nicht verhindern.",
        ),
    )
}

/** Renders the section into [panel]; [onChanged] re-reads the settings (the card rebuilds itself). */
internal fun renderPulpitRecordingSection(
    panel: SimplePanel,
    dto: OrganizationSettingsDto,
    onChanged: () -> Unit,
) {
    val box = panel.vPanel(spacing = 6) { addCssClasses("mt-2") }
    box.h3(tr("Begegnungsraum")) { addCssClass("h6") }
    val serial = ++pulpitSwitchSerial
    val labelId = "lapis-pulpit-recording-label-$serial"
    val helpId = "lapis-pulpit-recording-help-$serial"
    val line = box.div(className = "lapis-noise-suppression-line")
    line.span(tr("Aufzeichnung von Kanzel bzw. Podium erlauben")).setAttribute("id", labelId)
    val switch: Button = line.button("", className = "lapis-noise-switch")
    switch.setAttribute("type", "button")
    switch.setAttribute("role", "switch")
    switch.setAttribute("aria-checked", dto.pulpitRecordingEnabled.toString())
    switch.setAttribute("aria-labelledby", labelId)
    switch.setAttribute("aria-describedby", helpId)
    val available = dto.pulpitRecordingInfrastructureAvailable
    // A running feature can always be switched off, even if the service is gone; a feature that is off cannot be switched on without a service.
    switch.disabled = !available && !dto.pulpitRecordingEnabled
    val help =
        box.p(
            if (available) {
                tr(
                    "Nur die Kamera und das Mikrofon der Person mit dem Amt der Kanzel werden aufgezeichnet, nie die Gemeinde. Zusätzlich muss die Aufzeichnung je Raum erlaubt werden.",
                )
            } else {
                tr("Der Aufzeichnungsdienst ist auf diesem Server nicht eingerichtet.")
            },
            className = "text-muted small",
        )
    help.setAttribute("id", helpId)
    val acknowledged = dto.pulpitRecordingAcknowledgedNoticeVersion
    if (dto.pulpitRecordingEnabled && acknowledged != PULPIT_RECORDING_NOTICE_VERSION) {
        val stale = box.div(className = "text-warning small")
        stale.setAttribute("role", "status")
        stale.content =
            gettext(
                "Bestätigt für Hinweis Version %1, aktuell Version %2. Bitte erneut bestätigen.",
                acknowledged ?: "-",
                PULPIT_RECORDING_NOTICE_VERSION,
            )
        val again = newActionButton(ActionIcon.APPROVE, tr("Erneut bestätigen"), ButtonStyle.OUTLINEPRIMARY)
        box.add(again)
        again.onClick { showPulpitRecordingNoticeDialog { switchOn(onChanged) } }
    }
    switch.onClick {
        if (dto.pulpitRecordingEnabled) {
            runGuardedAction(switch) {
                val saved = guarded { rpcService<IOrganizationSettingsService>().updatePulpitRecordingEnabled(false, null) }
                if (saved != null) notifySuccess(gettext("Die Aufzeichnung der Kanzel ist ausgeschaltet."))
                onChanged()
            }
        } else {
            showPulpitRecordingNoticeDialog { switchOn(onChanged) }
        }
    }
    if (dto.pulpitRecordingEnabled) renderRequireConfirmationSwitch(box, dto.pulpitRecordingRequireConfirmation, onChanged)
}

private fun switchOn(onChanged: () -> Unit) {
    AppScope.launch {
        val saved =
            guarded {
                try {
                    rpcService<IOrganizationSettingsService>().updatePulpitRecordingEnabled(true, PULPIT_RECORDING_NOTICE_VERSION)
                } catch (e: PulpitRecordingNoticeOutdatedException) {
                    notifyError(gettext("Der Hinweis wurde geändert. Bitte lesen Sie ihn erneut."))
                    null
                }
            }
        if (saved != null) notifySuccess(gettext("Die Aufzeichnung der Kanzel ist eingeschaltet."))
        onChanged()
    }
}

private fun renderRequireConfirmationSwitch(
    box: SimplePanel,
    initial: Boolean,
    onChanged: () -> Unit,
) {
    val serial = ++pulpitSwitchSerial
    val labelId = "lapis-pulpit-confirm-label-$serial"
    val line = box.div(className = "lapis-noise-suppression-line")
    line.span(tr("Zustimmung der Kanzel vor jeder Aufnahme verlangen")).setAttribute("id", labelId)
    val switch: Button = line.button("", className = "lapis-noise-switch")
    switch.setAttribute("type", "button")
    switch.setAttribute("role", "switch")
    switch.setAttribute("aria-checked", initial.toString())
    switch.setAttribute("aria-labelledby", labelId)
    switch.onClick {
        val wanted = switch.getAttribute("aria-checked") != "true"
        runGuardedAction(switch) {
            val saved =
                guarded {
                    try {
                        rpcService<IOrganizationSettingsService>().updatePulpitRecordingRequireConfirmation(wanted)
                    } catch (e: ConflictException) {
                        notifyError(gettext("Die Aufzeichnung ist nicht eingeschaltet."))
                        null
                    }
                }
            if (saved == null) {
                switch.setAttribute("aria-checked", (!wanted).toString())
            } else {
                switch.setAttribute("aria-checked", saved.pulpitRecordingRequireConfirmation.toString())
            }
            onChanged()
        }
    }
}

/**
 * The notice dialog. "Einschalten" calls [onConfirm] only after the box is ticked; the focus starts on "Abbrechen", Escape and a click outside cancel,
 * the dialog is `role="dialog"` with `aria-modal` (Bootstrap's modal), and the foot names the version of the notice.
 */
internal fun showPulpitRecordingNoticeDialog(onConfirm: () -> Unit) {
    val modal = Modal(caption = tr("Aufzeichnung der Kanzel erlauben?"))
    PULPIT_RECORDING_NOTICE_PARAGRAPHS.forEach { paragraph -> modal.p(paragraph) }
    modal.div(gettext("Hinweis Version %1", PULPIT_RECORDING_NOTICE_VERSION), className = "text-muted small")
    val box =
        CheckBox(value = false, label = tr("Ich habe die rechtliche Zulässigkeit für mein Land geprüft und übernehme die Verantwortung."))
    modal.add(box)
    val cancel =
        newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    modal.addButton(cancel)
    val confirm = newActionButton(ActionIcon.APPROVE, tr("Einschalten"), ButtonStyle.PRIMARY)
    confirm.disabled = true
    box.subscribe { checked -> confirm.disabled = !checked }
    val once = ConfirmOnce()
    confirm.onClick {
        once.run(confirm) {
            modal.hide()
            onConfirm()
        }
    }
    modal.addButton(confirm)
    modal.addAfterInsertHook { vnode ->
        (vnode.elm as? org.w3c.dom.HTMLElement)?.addEventListener("shown.bs.modal", { cancel.getElement()?.focus() })
        kotlinx.browser.window.setTimeout({ cancel.getElement()?.focus() }, 0)
    }
    modal.show()
}
