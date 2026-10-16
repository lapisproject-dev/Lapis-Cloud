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
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AgreementTextKindOverviewDto
import network.lapis.cloud.shared.domain.MEMBER_FINDER_NOTICE_VERSION
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.rpc.IAgreementTextAdminService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.MemberFinderNoticeOutdatedException

/*
 * V1.9.145 -- the ADMIN setting of the optional member finder (a section of the "Videokonferenz" card, below the pulpit recording; see F1 in the CHANGELOG).
 *
 * Party membership is a special category of data, so the finder is OFF until an admin has read the operator notice and ticked that they checked the
 * legal admissibility for their country. The server pins the notice's version (`MEMBER_FINDER_NOTICE_VERSION`); a changed notice asks for a new
 * acknowledgment. Same grammar as the pulpit recording (`PulpitRecordingSettings.kt`), with ONE difference: switching OFF asks first, because it
 * deletes every request, block and band and ends every consent.
 *
 * - Switching ON opens the notice dialog (focus on "Abbrechen", Escape cancels, "Einschalten" stays locked until the box is ticked).
 * - While the built-in consent text is still the placeholder, a warning says so, in the section and in the dialog.
 */

private var finderSwitchSerial = 0

/** The paragraphs of the operator notice. Every one is a plain literal: the server pins the German reference text word for word (`MemberFinderNoticePinTest`). */
internal val MEMBER_FINDER_NOTICE_PARAGRAPHS: List<String> by lazy {
    listOf(
        tr(
            "Sie sind im Begriff, den Mitglieder-Finder für diese Instanz einzuschalten. Mitglieder, die ausdrücklich einwilligen, können damit andere Mitglieder derselben Region (die ersten zwei Ziffern der Postleitzahl) finden. Namen werden erst nach beidseitiger Zustimmung sichtbar.",
        ),
        tr(
            "Eine Parteimitgliedschaft ist ein besonders geschütztes Datum (Art. 9 DSGVO und ähnliche Regelungen anderer Länder). Zulässigkeit, Einwilligung, Informationspflichten und die Notwendigkeit einer Datenschutz-Folgenabschätzung sind je Land verschieden. Lapis Cloud ist keine Rechtsberatung.",
        ),
        tr(
            "Als Betreiber sind Sie dafür verantwortlich, dass eine Rechtsgrundlage besteht und die Mitglieder informiert werden. Ersetzen Sie den eingebauten Einwilligungstext in der Verwaltung der Vertragstexte durch eine rechtlich geprüfte Fassung.",
        ),
        tr("Ich habe die rechtliche Zulässigkeit für mein Land geprüft und übernehme die Verantwortung."),
    )
}

/** Renders the section into [panel]; [onChanged] re-reads the settings (the card rebuilds itself). */
internal fun renderMemberFinderSection(
    panel: SimplePanel,
    dto: OrganizationSettingsDto,
    onChanged: () -> Unit,
) {
    val box = panel.vPanel(spacing = 6) { addCssClasses("mt-2") }
    box.h3(tr("Mitglieder-Finder")) { addCssClass("h6") }
    val serial = ++finderSwitchSerial
    val labelId = "lapis-member-finder-label-$serial"
    val helpId = "lapis-member-finder-help-$serial"
    val line = box.div(className = "lapis-noise-suppression-line")
    line.span(tr("Mitglieder-Finder erlauben")).setAttribute("id", labelId)
    val switch: Button = line.button("", className = "lapis-noise-switch")
    switch.setAttribute("type", "button")
    switch.setAttribute("role", "switch")
    switch.setAttribute("aria-checked", dto.memberFinderEnabled.toString())
    switch.setAttribute("aria-labelledby", labelId)
    switch.setAttribute("aria-describedby", helpId)
    box
        .p(
            tr(
                "Mitglieder können sich nach ausdrücklicher Einwilligung gegenseitig finden. Namen werden erst nach beidseitiger Zustimmung sichtbar.",
            ),
            className = "text-muted small",
        ).setAttribute("id", helpId)

    // Whether the built-in consent text is still the placeholder: read from the overview of the agreement texts (ADMIN only, like this card).
    val warning = box.vPanel(spacing = 4)
    var placeholderActive: Boolean? = null
    warning
        .dataSection<AgreementTextKindOverviewDto>(
            isEmpty = { false },
            load = {
                guarded {
                    rpcService<IAgreementTextAdminService>().getOverview().kinds.first { it.kind == AgreementTextKind.FINDER }
                }
            },
            render = { host, overview ->
                placeholderActive = overview.placeholderActive
                if (overview.placeholderActive) {
                    val alert = host.div(className = "alert alert-warning mb-0")
                    alert.setAttribute("role", "status")
                    alert.p(
                        tr("Platzhaltertext aktiv: Ersetzen Sie den eingebauten Einwilligungstext unter Vertragstexte."),
                        className = "mb-1",
                    )
                    alert.link(tr("Zu den Vertragstexten"), url = "#${Routes.AGREEMENT_TEXTS}")
                }
            },
        ).reload()

    val acknowledged = dto.memberFinderAcknowledgedNoticeVersion
    if (dto.memberFinderEnabled && acknowledged != MEMBER_FINDER_NOTICE_VERSION) {
        val stale = box.div(className = "text-warning small")
        stale.setAttribute("role", "status")
        stale.content =
            gettext(
                "Bestätigt für Hinweis Version %1, aktuell Version %2. Bitte erneut bestätigen.",
                acknowledged ?: "-",
                MEMBER_FINDER_NOTICE_VERSION,
            )
        val again = newActionButton(ActionIcon.APPROVE, tr("Erneut bestätigen"), ButtonStyle.OUTLINEPRIMARY)
        box.add(again)
        again.onClick { showMemberFinderNoticeDialog(placeholderActive) { switchMemberFinderOn(onChanged) } }
    }
    switch.onClick {
        if (dto.memberFinderEnabled) {
            confirmMemberFinderOff(onChanged)
        } else {
            showMemberFinderNoticeDialog(placeholderActive) { switchMemberFinderOn(onChanged) }
        }
    }
}

private fun switchMemberFinderOn(onChanged: () -> Unit) {
    AppScope.launch {
        val saved =
            guarded {
                try {
                    rpcService<IOrganizationSettingsService>().updateMemberFinderEnabled(true, MEMBER_FINDER_NOTICE_VERSION)
                } catch (e: MemberFinderNoticeOutdatedException) {
                    notifyError(tr("Der Hinweis wurde aktualisiert, bitte erneut lesen."))
                    null
                }
            }
        if (saved != null) {
            notifySuccess(tr("Der Mitglieder-Finder ist eingeschaltet."))
            MemberFinderNav.refresh()
        }
        onChanged()
    }
}

/** Switching OFF is irreversible for the data (requests, blocks and bands go, every consent ends), so it asks first, with the focus on "Abbrechen". */
private fun confirmMemberFinderOff(onChanged: () -> Unit) {
    confirmDialog(
        title = tr("Mitglieder-Finder abschalten?"),
        message =
            tr(
                "Wenn Sie den Mitglieder-Finder abschalten, werden alle Anfragen und Blockierungen gelöscht und alle Einwilligungen beendet.",
            ),
        confirmLabel = tr("Abschalten"),
        focusCancel = true,
    ) {
        AppScope.launch {
            val saved = guarded { rpcService<IOrganizationSettingsService>().updateMemberFinderEnabled(false, null) }
            if (saved != null) {
                notifySuccess(tr("Der Mitglieder-Finder ist ausgeschaltet."))
                MemberFinderNav.refresh()
            }
            onChanged()
        }
    }
}

/**
 * The notice dialog. "Einschalten" calls [onConfirm] only after the box is ticked; the focus starts on "Abbrechen", Escape and a click outside cancel,
 * the dialog is `role="dialog"` with `aria-modal` (Bootstrap's modal), and the foot names the version of the notice. The fourth paragraph is the label
 * of the box. [placeholderActive] `true` adds the warning that the consent text is still the placeholder.
 */
internal fun showMemberFinderNoticeDialog(
    placeholderActive: Boolean?,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = tr("Mitglieder-Finder erlauben?"))
    MEMBER_FINDER_NOTICE_PARAGRAPHS.take(3).forEach { paragraph -> modal.p(paragraph) }
    if (placeholderActive == true) {
        modal.div(
            tr("Platzhaltertext aktiv: Ersetzen Sie den eingebauten Einwilligungstext unter Vertragstexte."),
            className = "alert alert-warning",
        )
    }
    val box = CheckBox(value = false, label = MEMBER_FINDER_NOTICE_PARAGRAPHS[3])
    modal.add(box)
    modal.div(
        tr(
            "Gut zu wissen: Wenn Sie den Mitglieder-Finder später abschalten, werden alle Anfragen und Blockierungen gelöscht und alle Einwilligungen beendet.",
        ),
        className = "border-top pt-2 mt-2 small",
    )
    modal.div(gettext("Hinweis Version %1", MEMBER_FINDER_NOTICE_VERSION), className = "text-muted small")
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
