package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.html.ButtonStyle
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
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseSepaBatchDto
import network.lapis.cloud.shared.domain.TravelExpenseSepaBlocker
import network.lapis.cloud.shared.domain.TravelExpenseSepaContextDto
import network.lapis.cloud.shared.domain.TravelExpenseSepaExportRequest
import network.lapis.cloud.shared.domain.TravelExpenseSepaReexportRequest
import network.lapis.cloud.shared.rpc.ITravelExpenseService

/**
 * Welle V1.9.123 "SEPA-Ueberweisungsdatei fuer genehmigte Reisekosten" -- the "Zur Auszahlung bereit" section of the approvals screen
 * (BOARD/ADMIN): booked reports as cards with a checkbox, a sticky summary bar ("N ausgewählt · Summe X"), a confirm dialog that names
 * the sum, the debit account and the execution date, and the list of earlier files.
 *
 * **Design rules this implements (design team, 2026-10):**
 * - A report that cannot be selected keeps a DISABLED checkbox and shows the reason as VISIBLE text next to it, tied to the checkbox by
 *   `aria-describedby` -- never a bare grey box (Raskin) and never colour alone (Norman).
 * - The IBAN is shown masked ("IBAN •••• 3000", accessible name "IBAN endet auf 3000"); the full number never reaches the browser.
 * - An exported report shows the verb "Exportiert" with its date and a re-export action, never a check mark: "exported" is not "paid".
 * - The confirm button is the primary colour, not a warning colour: generating the file is the intended action, and the dialog names what
 *   happens ("Datei erzeugen und als exportiert markieren"). The sum is the largest thing in the dialog.
 * - There is NO download of an old file. The earlier-files list is metadata only.
 *
 * The amounts shown are for orientation; the server computes the binding amount from the database and checks it against the booking.
 */
fun renderTravelExpenseSepaSection(root: SimplePanel) {
    val service = { rpcService<ITravelExpenseService>() }
    root.h2(tr("Zur Auszahlung bereit")) { addCssClass("h6") }
    root.p(
        tr("Wählen Sie gebuchte Abrechnungen aus und erzeugen Sie daraus eine SEPA-Überweisungsdatei für das Online-Banking."),
    ) { addCssClasses("text-muted small") }
    val listPanel = root.simplePanel { addCssClass("lapis-card-list") }

    val bar =
        root.hPanel(spacing = 8) {
            addCssClasses("lapis-sepa-bar align-items-center flex-wrap border-top pt-2 mt-2 position-sticky bottom-0 bg-body")
        }
    val summary =
        bar.div {
            addCssClasses("flex-grow-1 fw-bold")
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
        }
    val generateButton = bar.actionButton(ActionIcon.EXPORT, tr("SEPA-Datei erzeugen"), style = ButtonStyle.PRIMARY)

    root.h2(tr("Bisherige SEPA-Dateien")) { addCssClasses("h6 mt-3") }
    val historyPanel = root.simplePanel { addCssClass("lapis-card-list") }

    val selected = LinkedHashMap<String, Long>()
    var context: TravelExpenseSepaContextDto? = null

    fun refreshBar() {
        val count = selected.size
        summary.content =
            gettext("%1 ausgewählt · Summe %2", count, formatMoney(centsToDecimal(travelExpenseSepaSelectionSumCents(selected))))
        generateButton.disabled = count == 0 || count > (context?.maxTransactions ?: 0)
    }

    lateinit var reload: () -> Unit

    fun openExportDialog(
        reportCount: Int,
        sumCents: Long,
        reexport: Pair<String, String>?,
    ) {
        val ctx = context ?: return
        val modal = Modal(caption = if (reexport == null) tr("SEPA-Datei erzeugen") else tr("SEPA-Datei erneut erzeugen"))
        modal.div(formatMoney(centsToDecimal(sumCents))) { addCssClasses("fs-3 fw-bold") }
        val form = modal.lapisForm()
        val defaultAccount = ctx.bankAccounts.firstOrNull { it.isDefault } ?: ctx.bankAccounts.firstOrNull()
        val accountField =
            if (ctx.bankAccounts.size > 1) {
                form.selectField(
                    label = tr("Belastungskonto"),
                    options =
                        ctx.bankAccounts.map {
                            it.id to
                                sanitizeUntrustedI18nText(gettext("%1 (IBAN •••• %2)", it.label, it.ibanLast4))
                        },
                    value = defaultAccount?.id,
                    required = true,
                )
            } else {
                null
            }
        if (accountField == null && defaultAccount != null) {
            modal.untrustedDiv(
                gettext(
                    "%1 Überweisungen von %2 (IBAN •••• %3)",
                    reportCount,
                    sanitizeUntrustedI18nText(defaultAccount.label),
                    defaultAccount.ibanLast4,
                ),
            )
        } else {
            modal.div(gettext("%1 Überweisungen", reportCount))
        }
        val dateField =
            form.textField(
                label = tr("Ausführungsdatum (JJJJ-MM-TT)"),
                value = ctx.defaultExecutionDate?.toString(),
                required = true,
                hint = tr("Ein Bankarbeitstag (TARGET2), höchstens 60 Tage in der Zukunft."),
                rule = { FormRules.isoDate(it) },
            )
        val setupProblems = ctx.missingSetup
        if (setupProblems.isNotEmpty()) {
            setupProblems.forEach { problem ->
                modal.div(
                    when (problem) {
                        "BANK_ACCOUNT" -> travelExpenseSepaErrorMessage("debtor_account_missing")
                        "ORGANIZATION_NAME" -> travelExpenseSepaErrorMessage("organization_name_missing")
                        else -> travelExpenseSepaErrorMessage("encryption_key_missing")
                    },
                ) { addCssClasses("alert alert-warning") }
            }
        }
        form.finish()

        val cancelButton = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
        val confirmButton =
            newActionButton(ActionIcon.EXPORT, tr("Datei erzeugen und als exportiert markieren"), ButtonStyle.PRIMARY).apply {
                disabled = setupProblems.isNotEmpty()
            }
        confirmButton.onClick {
            if (!form.validateAndReport()) return@onClick
            val date = LocalDate.parse(dateField.value.trim())
            val accountId = accountField?.value ?: defaultAccount?.id
            form.runBusy(confirmButton, restoreDisabled = { setupProblems.isNotEmpty() }) {
                val outcome =
                    if (reexport == null) {
                        TravelExpenseHttp.exportSepaBatch(
                            TravelExpenseSepaExportRequest(
                                reportIds = selected.keys.toList(),
                                debtorBankAccountId = accountId,
                                executionDate = date,
                            ),
                        )
                    } else {
                        TravelExpenseHttp.reexportSepa(
                            reportId = reexport.first,
                            request =
                                TravelExpenseSepaReexportRequest(
                                    reason = reexport.second,
                                    debtorBankAccountId = accountId,
                                    executionDate = date,
                                ),
                        )
                    }
                when (outcome) {
                    is SepaExportOutcome.Delivered -> {
                        modal.hide()
                        notifySuccess(tr("Datei heruntergeladen. Laden Sie die Datei jetzt im Online-Banking hoch."))
                        reload()
                    }
                    is SepaExportOutcome.Refused ->
                        if (outcome.blockers.isNotEmpty()) {
                            // The cards show the reasons per report -- reload so the list reflects the server's view.
                            modal.hide()
                            notifyError(
                                gettext(
                                    "%1 Abrechnung(en) konnten nicht aufgenommen werden. Die Gründe stehen an den Abrechnungen.",
                                    outcome.blockers.size,
                                ),
                            )
                            reload()
                        } else {
                            form.showFormError(travelExpenseSepaErrorMessage(outcome.code))
                        }
                }
            }
        }
        modal.addButton(cancelButton)
        modal.addButton(confirmButton)
        modal.show()
    }

    fun renderCandidate(dto: TravelExpenseReportDto) {
        val blocker = dto.sepaBlocker
        val exported = dto.sepaExportedAt != null
        val selectable = blocker == null
        val card = listPanel.vPanel(spacing = 4) { addCssClass("lapis-data-card") }
        val head = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        val reference = dto.sepaReference.orEmpty()
        // The label is assigned after the call (a labelled `checkBox(label = ...)` outside the form grammar would raise the R24B ratchet;
        // this one is a SELECTION LIST entry -- one checkbox per report, never submitted as a form field).
        val check = head.checkBox(value = false)
        check.label = tr("Auswählen")
        check.disabled = !selectable
        check.input.setAttribute("aria-label", gettext("Abrechnung %1 auswählen", reference))
        head.untrustedCardTitle(dto.subjectDisplayName)
        head.typeBadge(reference, "secondary")
        head.div(formatMoney(centsToDecimal(dto.payoutCents))) { addCssClasses("fw-bold") }
        if (dto.refundIbanLast4 != null) {
            card.div(gettext("IBAN •••• %1", dto.refundIbanLast4)) {
                addCssClass("small")
                setAttribute("aria-label", gettext("IBAN endet auf %1", dto.refundIbanLast4))
            }
        }
        dto.refundAccountHolder?.let { card.untrustedDiv(gettext("Kontoinhaber: %1", sanitizeUntrustedI18nText(it)), className = "small") }
        val flags = card.hPanel(spacing = 6) { addCssClasses("flex-wrap") }
        if (travelExpenseSepaHolderDiffers(dto.refundAccountHolder, dto.subjectDisplayName)) {
            flags.typeBadge(tr("Kontoinhaber weicht vom Mitglied ab"), "warning")
        }
        if (dto.refundAccountChangedAfterSubmit) flags.typeBadge(tr("Konto nach Einreichung geändert"), "warning")
        if (blocker != null && blocker != TravelExpenseSepaBlocker.ALREADY_EXPORTED) {
            val reasonId = "sepa-reason-${dto.id}"
            card.div(travelExpenseSepaBlockerLabel(blocker)) {
                id = reasonId
                addCssClasses("small text-muted fst-italic")
            }
            check.input.setAttribute("aria-describedby", reasonId)
        }
        if (exported) {
            val exportedRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
            exportedRow.div(
                gettext(
                    "Exportiert am %1 von %2",
                    formatSystemDateTime(requireNotNull(dto.sepaExportedAt)),
                    sanitizeUntrustedI18nText(dto.sepaExportedByDisplayName.orEmpty()),
                ),
            ) { addCssClasses("small") }
            val reexportButton = exportedRow.actionButton(ActionIcon.EXPORT, tr("Erneut exportieren …"), small = true)
            reexportButton.onClick {
                confirmWithReasonDialog(
                    title = tr("Erneut exportieren"),
                    message =
                        gettext(
                            "Die Abrechnung %1 wurde bereits am %2 exportiert (Datei %3). Ein erneuter Export erzeugt eine neue Datei und kann zu einer doppelten Zahlung führen, wenn die erste Datei bei der Bank eingereicht wurde.",
                            reference,
                            formatSystemDateTime(requireNotNull(dto.sepaExportedAt)),
                            dto.sepaExportBatchId.orEmpty().take(8),
                        ),
                    reasonLabel = tr("Begründung"),
                    reasonRequired = true,
                    reasonMinLength = 10,
                    reasonMaxLength = 500,
                    confirmLabel = tr("Weiter"),
                ) { reason ->
                    if (reason != null) openExportDialog(reportCount = 1, sumCents = dto.payoutCents, reexport = dto.id to reason)
                }
            }
        }
        check.subscribe { checked ->
            if (checked) selected[dto.id] = dto.payoutCents else selected.remove(dto.id)
            refreshBar()
        }
    }

    fun renderBatch(batch: TravelExpenseSepaBatchDto) {
        val card = historyPanel.vPanel(spacing = 2) { addCssClass("lapis-data-card") }
        val head = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        head.div(formatSystemDateTime(batch.createdAt)) { addCssClasses("fw-bold") }
        if (batch.isReexport) head.typeBadge(tr("Erneuter Export"), "warning")
        head.div(formatMoney(centsToDecimal(batch.controlSumCents)))
        card.untrustedDiv(
            gettext(
                "%1 Überweisungen von %2 (IBAN •••• %3), Ausführung %4",
                batch.transactionCount,
                sanitizeUntrustedI18nText(batch.debtorAccountLabel),
                batch.debtorIbanLast4,
                formatDate(batch.requestedExecutionDate),
            ),
            className = "small",
        )
        card.untrustedDiv(gettext("Erzeugt von %1", sanitizeUntrustedI18nText(batch.createdByDisplayName)), className = "text-muted small")
    }

    reload = {
        selected.clear()
        AppScope.launch {
            val svc = service()
            val candidates = guarded { svc.listSepaExportCandidates() }
            val ctx = guarded { svc.getSepaExportContext() }
            val batches = guarded { svc.listSepaBatches() }
            listPanel.removeAll()
            historyPanel.removeAll()
            if (candidates == null || ctx == null) {
                listPanel.dataErrorState(onRetry = { reload() })
                generateButton.disabled = true
                return@launch
            }
            context = ctx
            if (candidates.isEmpty()) listPanel.p(tr("Keine gebuchten Abrechnungen zur Auszahlung."))
            candidates.forEach { renderCandidate(it) }
            if (batches.isNullOrEmpty()) {
                historyPanel.p(tr("Noch keine SEPA-Datei erzeugt.")) { addCssClasses("text-muted small") }
            } else {
                batches.forEach { renderBatch(it) }
            }
            refreshBar()
        }
    }
    generateButton.onClick {
        openExportDialog(reportCount = selected.size, sumCents = travelExpenseSepaSelectionSumCents(selected), reexport = null)
    }
    refreshBar()
    reload()
}
