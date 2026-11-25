package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.ReferenceRateOutcome
import network.lapis.cloud.shared.domain.ReferenceRateRules
import network.lapis.cloud.shared.domain.TravelExpenseLtrBlocker
import network.lapis.cloud.shared.domain.TravelExpenseLtrPreviewDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.rpc.ITravelExpenseService

/**
 * V1.9.325 "Reisekostenerstattung in LTR" -- the "Per LTR-Gutschrift" part of the approvals screen: booked reports whose member chose LTR.
 *
 * Design rules (same as the SEPA section): a report that cannot be credited shows the reason as VISIBLE text next to a disabled button, tied
 * to it by `aria-describedby` (never a bare grey button, never colour alone); the member's own report is blocked with a sentence, not hidden.
 * There is NO checkbox: every credit is its own act with its own rate, so it is one card, one dialog, one confirmation. The primary verb
 * names the amount ("%1 LTR gutschreiben"); the amount and the rate come from the server's preview, never from this page. A credit that was
 * made shows the receipt line (when, by whom, rate and day) and the reversal action; "Erneut gutschreiben" exists only after a reversal.
 */
fun renderTravelExpenseLtrSection(root: SimplePanel) {
    root.h2(tr("Per LTR-Gutschrift")) { addCssClass("h6") }
    root.p(
        tr("Abrechnungen, bei denen die Erstattung in LTR gewählt wurde. Der Kurs ist der Goldpreis am Tag des Reiseendes."),
    ) { addCssClasses("text-muted small") }
    val listPanel = root.simplePanel { addCssClass("lapis-card-list") }

    lateinit var reload: () -> Unit

    fun openPayoutDialog(
        reportId: String,
        initial: TravelExpenseLtrPreviewDto,
    ) {
        val modal = Modal(caption = tr("In LTR gutschreiben"))
        val body = modal.vPanel(spacing = 6)
        var preview = initial
        val form = modal.lapisForm()
        val repeatReason =
            form.textAreaField(
                label = tr("Begründung für die erneute Gutschrift"),
                rows = 3,
                hint = tr("10 bis 500 Zeichen."),
                rule = {
                    if (it.trim().length in
                        REASON_MIN..REASON_MAX
                    ) {
                        FieldCheck.Ok
                    } else {
                        FieldCheck.Invalid(gettext("Bitte 10 bis 500 Zeichen angeben."))
                    }
                },
            )
        form.finish()
        val confirmButton = newActionButton(ActionIcon.EXPORT, "", ButtonStyle.PRIMARY)

        fun render() {
            body.removeAll()
            val p = preview
            val res = p.resolution
            body.div(formatMoney(centsToDecimal(p.eurCents))) { addCssClasses("fs-3 fw-bold") }
            body.div(gettext("Kurstag: %1 (Reiseende %2)", formatDate(res.effectiveDate), formatDate(p.referenceDate))) {
                addCssClass("text-muted")
            }
            val price = res.price
            if (res.outcome == ReferenceRateOutcome.RESOLVED && price != null) {
                body.div(gettext("Kurs: %1 EUR je Unze Gold", price.toString())) { addCssClass("fw-bold") }
                val usedLabel = tr("verwendet")
                val excludedLabel = tr("ausgeschlossen")
                if (!res.rawValuesStored) body.div(rawValuesNotStoredText()) { addCssClasses("text-muted small") }
                res.sources.forEach {
                    body.div(gettext("%1: %2 (%3)", it.sourceId, it.rawPrice.toString(), if (it.included) usedLabel else excludedLabel)) {
                        addCssClasses("text-muted small")
                    }
                }
            } else {
                body.div(tr("Für diesen Kurstag liegt kein Kurs vor. Bitte einen Kurs mit Beleg bestätigen.")) {
                    addCssClasses("alert alert-warning mb-0")
                }
                body.button(tr("Kurs manuell bestätigen"), style = ButtonStyle.OUTLINEPRIMARY) {
                    onClick {
                        openManualRateDialog(reportId = reportId, tradingDay = res.effectiveDate) { updated ->
                            preview = updated
                            render()
                        }
                    }
                }
            }
            p.ltrAmount?.let { body.div(gettext("Gutschrift: %1 LTR (gerundet auf 0,01 LTR)", it.toString())) { addCssClass("fw-bold") } }
            p.blocker?.let { blocker ->
                if (blocker != TravelExpenseLtrBlocker.RATE_MISSING) {
                    body.div(travelExpenseLtrBlockerText(blocker)) { addCssClasses("alert alert-warning mb-0") }
                }
            }
            repeatReason.setVisible(p.requiresRepeatReason)
            confirmButton.text = gettext("%1 LTR gutschreiben", p.ltrAmount?.toString().orEmpty())
            confirmButton.disabled = p.blocker != null || p.fingerprint == null
        }
        render()
        confirmButton.onClick {
            val fingerprint = preview.fingerprint ?: return@onClick
            if (preview.requiresRepeatReason && !form.validateAndReport()) return@onClick
            val reason = if (preview.requiresRepeatReason) repeatReason.value.trim() else null
            form.runBusy(confirmButton, restoreDisabled = { preview.blocker != null || preview.fingerprint == null }) {
                val result = guarded { rpcService<ITravelExpenseService>().executeLtrPayout(reportId, fingerprint, reason) }
                if (result != null) {
                    modal.hide()
                    notifySuccess(tr("LTR gutgeschrieben."))
                    reload()
                }
            }
        }
        modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
        modal.addButton(confirmButton)
        modal.show()
    }

    fun renderCandidate(dto: TravelExpenseReportDto) {
        val blocker = dto.ltrBlocker
        val card = listPanel.vPanel(spacing = 4) { addCssClass("lapis-data-card") }
        val head = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        head.untrustedCardTitle(dto.subjectDisplayName)
        head.typeBadge(tr("LTR"), "info")
        head.div(formatMoney(centsToDecimal(dto.payoutCents))) { addCssClasses("fw-bold") }
        card.div(gettext("Reiseende: %1", formatDate(dto.travelTo))) { addCssClasses("small text-muted") }
        val button = card.actionButton(ActionIcon.EXPORT, tr("Gutschrift vorbereiten …"), small = true)
        if (blocker != null && blocker != TravelExpenseLtrBlocker.RATE_MISSING && blocker != TravelExpenseLtrBlocker.SELF_CONFIRMED_RATE) {
            val reasonId = "ltr-reason-${dto.id}"
            card.div(travelExpenseLtrBlockerText(blocker)) {
                id = reasonId
                addCssClasses("small text-muted fst-italic")
            }
            button.setAttribute("aria-describedby", reasonId)
            button.disabled = true
        }
        button.onClick {
            AppScope.launch {
                val preview = guarded { rpcService<ITravelExpenseService>().previewLtrPayout(dto.id) } ?: return@launch
                openPayoutDialog(dto.id, preview)
            }
        }
    }

    reload = {
        AppScope.launch {
            val candidates = guarded { rpcService<ITravelExpenseService>().getLtrPayoutCandidates() }
            listPanel.removeAll()
            if (candidates == null) {
                listPanel.dataErrorState(onRetry = { reload() })
                return@launch
            }
            if (candidates.isEmpty()) listPanel.p(tr("Keine Abrechnungen zur Gutschrift in LTR."))
            candidates.forEach { renderCandidate(it) }
        }
    }
    reload()
}

private const val REASON_MIN = 10
private const val REASON_MAX = 500

/** Manual rate for the trading day of a trip end; the server decides whether it is allowed (never over an existing rate). */
private fun openManualRateDialog(
    reportId: String,
    tradingDay: LocalDate,
    onConfirmed: (TravelExpenseLtrPreviewDto) -> Unit,
) {
    val modal = Modal(caption = tr("Kurs manuell bestätigen"))
    modal.div(
        gettext("Goldpreis in EUR je Unze für den Kurstag %1. Der Kurs kann danach nicht mehr geändert werden.", formatDate(tradingDay)),
    )
    val form = modal.lapisForm()
    val priceInput =
        form.textField(
            label = tr("Kurs"),
            required = true,
            rule = {
                if (Validation.isPositiveDecimal(
                        it,
                    )
                ) {
                    FieldCheck.Ok
                } else {
                    FieldCheck.Invalid(gettext("Bitte einen positiven Kurs angeben."))
                }
            },
        )
    val evidenceInput =
        form.textAreaField(
            label = tr("Beleg (Quelle und Begründung)"),
            rows = 4,
            required = true,
            rule = {
                val length = ReferenceRateRules.normalizeEvidence(it).length
                if (length in ReferenceRateRules.EVIDENCE_MIN..ReferenceRateRules.EVIDENCE_MAX) {
                    FieldCheck.Ok
                } else {
                    FieldCheck.Invalid(
                        gettext(
                            "Bitte einen Beleg mit %1 bis %2 Zeichen angeben.",
                            ReferenceRateRules.EVIDENCE_MIN,
                            ReferenceRateRules.EVIDENCE_MAX,
                        ),
                    )
                }
            },
        )
    val acknowledge = form.checkField(label = tr("Ich bestätige, dass der Kurs deutlich vom gespeicherten Kurs dieses Tages abweicht"))
    form.finish()
    val confirmButton = newActionButton(ActionIcon.SAVE, tr("Kurs bestätigen"), ButtonStyle.PRIMARY)
    confirmButton.onClick {
        if (!form.validateAndReport()) return@onClick
        form.runBusy(confirmButton) {
            val result =
                guarded {
                    rpcService<ITravelExpenseService>().confirmLtrPayoutRate(
                        reportId = reportId,
                        price =
                            priceInput.value
                                .trim()
                                .toDouble()
                                .toDecimal(),
                        evidenceText = ReferenceRateRules.normalizeEvidence(evidenceInput.value),
                        acknowledgeDeviation = acknowledge.value == "true",
                    )
                }
            if (result != null) {
                modal.hide()
                onConfirmed(result)
            }
        }
    }
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(confirmButton)
    modal.show()
}
