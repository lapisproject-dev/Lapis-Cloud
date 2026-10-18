package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.form.check.CheckBox
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h3
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.EVENT_CAPTURE_NOTICE_VERSION
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.rpc.EventCaptureBusyException
import network.lapis.cloud.shared.rpc.EventCaptureChangeLockedException
import network.lapis.cloud.shared.rpc.EventCaptureOperatorNoticeOutdatedException
import network.lapis.cloud.shared.rpc.EventCaptureTextMissingException
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService

/*
 * V1.9.156 -- the ADMIN setting of the optional recording and live streaming in event rooms ("Veranstaltungen" section of the "Videokonferenz" card).
 *
 * Whether a recording of persons is lawful depends on the country of the instance, so Lapis Cloud does not decide it: the feature is OFF. An admin
 * switches it on only after (1) publishing a consent text of their own under "Vertragstexte" (the built-in text is a placeholder; the switch stays locked
 * while it is the live one), (2) reading the operator notice (below) and ticking that they checked the legal admissibility for their country. The board then
 * releases the recording for each event separately; everybody who enters such an event room acknowledges the consent first.
 *
 * - Switching ON opens the notice dialog (focus on "Abbrechen", Escape cancels, "Einschalten" stays locked until the box is ticked).
 * - Switching OFF asks first (the people in a running event room are affected) and is refused by the server while a recording or stream runs.
 * - A refusal is explained in words, never by a disabled control alone.
 */

private var eventCaptureSwitchSerial = 0

/** The paragraphs of the operator notice. Every one is a plain literal: the server pins the German reference text word for word (`EventCaptureNoticePinTest`). */
internal val EVENT_CAPTURE_NOTICE_PARAGRAPHS: List<String> by lazy {
    listOf(
        tr(
            "Sie sind im Begriff, die Aufzeichnung und die Live-Übertragung in den Online-Räumen von Veranstaltungen dieser Instanz zu erlauben. Aufgezeichnet und übertragen werden können Bild, Ton und Anzeigename aller Personen im Raum, nicht nur der Vortragenden. Der Vorstand entscheidet danach für jede Veranstaltung einzeln.",
        ),
        tr(
            "Ob und unter welchen Bedingungen eine solche Aufzeichnung zulässig ist, hängt vom Recht Ihres Landes ab, zum Beispiel vom Datenschutz-, Persönlichkeits- und Urheberrecht. Bei Veranstaltungen kann ein Teil der Daten besondere Kategorien betreffen, etwa die politische oder religiöse Überzeugung. Lapis Cloud nimmt diese Prüfung nicht vor und ersetzt keine Rechtsberatung.",
        ),
        tr(
            "Als Betreiber sind Sie dafür verantwortlich, dass Sie eine Rechtsgrundlage haben und dass jede Person vor dem Betreten des Raums informiert ist und, wo erforderlich, einwilligt. Lapis Cloud verlangt dafür von jeder Person eine ausdrückliche Bestätigung des Einwilligungstextes und hält sie ohne Namen und ohne Uhrzeit fest. Das ersetzt keine Prüfung, ob die Einwilligung freiwillig und ausreichend ist.",
        ),
        tr(
            "Das Programm kann nicht verhindern, dass Teilnehmende Bildschirmfotos oder eigene Mitschnitte anfertigen. Der Einwilligungstext, der angezeigt wird, muss von Ihnen vor dem Einschalten veröffentlicht werden.",
        ),
    )
}

/** Renders the section into [panel]; [onChanged] re-reads the settings (the card rebuilds itself). */
internal fun renderEventCaptureSection(
    panel: SimplePanel,
    dto: OrganizationSettingsDto,
    onChanged: () -> Unit,
) {
    val box = panel.vPanel(spacing = 6) { addCssClasses("mt-2") }
    box.h3(tr("Veranstaltungen")) { addCssClass("h6") }
    val serial = ++eventCaptureSwitchSerial
    val labelId = "lapis-event-capture-label-$serial"
    val helpId = "lapis-event-capture-help-$serial"
    val line = box.div(className = "lapis-noise-suppression-line")
    line.span(tr("Aufzeichnung und Übertragung in Veranstaltungsräumen erlauben")).setAttribute("id", labelId)
    val switch: Button = line.button("", className = "lapis-noise-switch")
    switch.setAttribute("type", "button")
    switch.setAttribute("role", "switch")
    switch.setAttribute("aria-checked", dto.eventCaptureEnabled.toString())
    switch.setAttribute("aria-labelledby", labelId)
    switch.setAttribute("aria-describedby", helpId)
    // A running feature can always be switched off (the server refuses while something runs); a feature that is off cannot be switched on while
    // the consent text is still the built-in placeholder.
    val textMissing = dto.eventCaptureTextIsPlaceholder
    switch.disabled = textMissing && !dto.eventCaptureEnabled
    val help =
        box.p(
            tr(
                "Der Vorstand erlaubt die Aufzeichnung danach für jede Veranstaltung einzeln. Jede Person im Raum stimmt vorher ausdrücklich zu und sieht im Raum, solange aufgezeichnet oder übertragen wird.",
            ),
            className = "text-muted small",
        )
    help.setAttribute("id", helpId)
    if (textMissing) {
        val warn = box.div(className = "text-warning small")
        warn.setAttribute("role", "status")
        // V1.9.158: a switch that cannot be turned on says why to assistive technology, too (the reason is the line below)
        val warnId = "lapis-event-capture-textmissing-$serial"
        warn.setAttribute("id", warnId)
        if (switch.disabled) switch.setAttribute("aria-describedby", "$helpId $warnId")
        warn.content =
            tr(
                "Der Einwilligungstext für Veranstaltungen ist noch der Platzhalter. Veröffentlichen Sie zuerst einen eigenen, rechtlich geprüften Text.",
            )
        box.link(tr("Zu den Vertragstexten"), url = "#${Routes.AGREEMENT_TEXTS}")
    }
    val acknowledged = dto.eventCaptureNoticeVersion
    if (dto.eventCaptureEnabled && acknowledged != EVENT_CAPTURE_NOTICE_VERSION) {
        val stale = box.div(className = "text-warning small")
        stale.setAttribute("role", "status")
        stale.content =
            gettext(
                "Bestätigt für Hinweis Version %1, aktuell Version %2. Bitte erneut bestätigen.",
                acknowledged ?: "-",
                EVENT_CAPTURE_NOTICE_VERSION,
            )
        val again = newActionButton(ActionIcon.APPROVE, tr("Erneut bestätigen"), ButtonStyle.OUTLINEPRIMARY)
        box.add(again)
        again.onClick { showEventCaptureNoticeDialog { eventCaptureSwitchOn(onChanged) } }
    }
    switch.onClick {
        if (dto.eventCaptureEnabled) {
            confirmDialog(
                title = tr("Aufzeichnung in Veranstaltungsräumen ausschalten?"),
                message = tr("Danach kann in keinem Veranstaltungsraum mehr aufgezeichnet oder übertragen werden."),
                confirmLabel = tr("Ausschalten"),
                confirmStyle = ButtonStyle.PRIMARY,
                focusCancel = true,
            ) { eventCaptureSwitchOff(onChanged) }
        } else {
            showEventCaptureNoticeDialog { eventCaptureSwitchOn(onChanged) }
        }
    }
}

private fun eventCaptureSwitchOff(onChanged: () -> Unit) {
    AppScope.launch {
        val saved =
            guarded {
                try {
                    rpcService<IOrganizationSettingsService>().updateEventCaptureEnabled(false, null)
                } catch (e: EventCaptureBusyException) {
                    notifyError(
                        gettext("In einem Veranstaltungsraum läuft gerade eine Aufzeichnung oder Übertragung. Beenden Sie sie zuerst."),
                    )
                    null
                }
            }
        if (saved != null) notifySuccess(gettext("Die Aufzeichnung in Veranstaltungsräumen ist ausgeschaltet."))
        onChanged()
    }
}

private fun eventCaptureSwitchOn(onChanged: () -> Unit) {
    AppScope.launch {
        val saved =
            guarded {
                try {
                    rpcService<IOrganizationSettingsService>().updateEventCaptureEnabled(true, EVENT_CAPTURE_NOTICE_VERSION)
                } catch (e: EventCaptureOperatorNoticeOutdatedException) {
                    notifyError(gettext("Der Hinweis wurde geändert. Bitte lesen Sie ihn erneut."))
                    null
                } catch (e: EventCaptureTextMissingException) {
                    notifyError(gettext("Veröffentlichen Sie zuerst den Einwilligungstext für Veranstaltungen unter Vertragstexte."))
                    null
                } catch (e: EventCaptureChangeLockedException) {
                    notifyError(gettext("Eine freigegebene Veranstaltung läuft gerade. Bitte versuchen Sie es nach ihrem Ende erneut."))
                    null
                }
            }
        if (saved != null) notifySuccess(gettext("Die Aufzeichnung in Veranstaltungsräumen ist eingeschaltet."))
        onChanged()
    }
}

/**
 * The notice dialog. "Einschalten" calls [onConfirm] only after the box is ticked; the focus starts on "Abbrechen", Escape and a click outside cancel,
 * the dialog is `role="dialog"` with `aria-modal` (Bootstrap's modal), and the foot names the version of the notice.
 */
internal fun showEventCaptureNoticeDialog(onConfirm: () -> Unit) {
    val modal = Modal(caption = tr("Aufzeichnung in Veranstaltungsräumen erlauben?"))
    EVENT_CAPTURE_NOTICE_PARAGRAPHS.forEach { paragraph -> modal.p(paragraph) }
    modal.div(gettext("Hinweis Version %1", EVENT_CAPTURE_NOTICE_VERSION), className = "text-muted small")
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
