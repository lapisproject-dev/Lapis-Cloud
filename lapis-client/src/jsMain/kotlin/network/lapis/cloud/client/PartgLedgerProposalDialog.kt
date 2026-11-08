package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PartgLedgerProposalDto
import network.lapis.cloud.shared.domain.PartgLedgerProposalRowDto
import network.lapis.cloud.shared.domain.PartgProposalPickDto
import network.lapis.cloud.shared.rpc.PartyReportProposalStaleException

private val PROPOSAL_HEADERS =
    listOf(
        TableHeader(title = tr("Übernehmen")),
        TableHeader(title = tr("Position")),
        TableHeader(title = tr("Erfasst"), numeric = true),
        TableHeader(title = tr("Aus Buchungen"), numeric = true),
        TableHeader(title = tr("Differenz"), numeric = true),
    )

/** The difference of a proposal row in cents (`null` when nothing is entered yet). */
internal fun partgProposalDifference(row: PartgLedgerProposalRowDto): Long? = row.enteredCents?.let { row.proposedCents - it }

/** The ticks the dialog starts with: the shared rule [network.lapis.cloud.shared.domain.PartgLedgerRules.preselect], already decided by the server. */
internal fun partgProposalInitialPicks(rows: List<PartgLedgerProposalRowDto>): Set<String> =
    rows.filter { it.preselected && it.transferable }.map { it.positionCode }.toSet()

/**
 * V1.9.289 (R4a) -- the dialog "Vorschlag aus Buchungen": every position where the bookings differ from what is entered, with the entered value, the
 * value from the bookings and the difference. A value that was typed in or imported is never pre-ticked; a negative proposal cannot be taken over.
 * The server recomputes every amount at the moment of taking over; if the bookings changed in between it refuses and the person reloads the proposal.
 */
internal fun openPartgLedgerProposalDialog(
    proposal: PartgLedgerProposalDto,
    onApplied: () -> Unit,
    onReload: () -> Unit,
) {
    val modal = Modal(caption = gettext("Vorschlag aus Buchungen: %1", proposal.year))
    if (proposal.unmappedWithActivityCount > 0) {
        modal.div(
            gettext(
                "%1 Konten mit Buchungen sind keiner Position zugeordnet. Ihre Beträge fehlen im Vorschlag.",
                proposal.unmappedWithActivityCount,
            ),
        ) { addCssClasses("text-warning-emphasis small") }
    }
    val ticked = partgProposalInitialPicks(proposal.rows).toMutableSet()
    val applyButton = newActionButton(ActionIcon.SAVE, tr("Werte übernehmen"), ButtonStyle.PRIMARY)

    fun paintApply() {
        applyButton.text = gettext("%1 Werte übernehmen", ticked.size)
        applyButton.disabled = ticked.isEmpty()
    }
    if (proposal.rows.isEmpty()) {
        modal.div(
            tr("Die Buchungen stimmen mit den erfassten Werten überein. Es gibt nichts zu übernehmen."),
        ) { addCssClasses("text-muted") }
    } else {
        val table = modal.standardTable(PROPOSAL_HEADERS)
        proposal.rows.forEach { row ->
            table.row {
                cell {
                    if (row.transferable) {
                        val box = checkBox(value = row.positionCode in ticked)
                        box.setAttribute(
                            "aria-label",
                            resolvedAttributeText(tr("Wert übernehmen")) + " " + partgPositionOptionLabel(row.positionCode),
                        )
                        box.setAttribute("data-partg-pick", row.positionCode)
                        // the widget reports its value (also once when it is created): never a toggle
                        box.subscribe { checked ->
                            if (checked) ticked.add(row.positionCode) else ticked.remove(row.positionCode)
                            paintApply()
                        }
                    } else {
                        div(tr("Negativ, nicht übertragbar. Bitte Zuordnung oder Buchungen prüfen.")) {
                            addCssClasses("small text-danger")
                            setAttribute("data-partg-negative", row.positionCode)
                        }
                    }
                }
                textCell(partgPositionOptionLabel(row.positionCode))
                numCell {
                    if (row.enteredCents == null) div("–") else moneySpan(centsToDecimal(row.enteredCents!!))
                }
                numCell { moneySpan(centsToDecimal(row.proposedCents), warnIfNegative = true) }
                numCell {
                    val diff = partgProposalDifference(row)
                    if (diff == null) div("–") else moneySpan(centsToDecimal(diff), warnIfNegative = false)
                }
            }
        }
    }
    val failure = modal.div(className = "alert alert-warning mt-2") { hide() }
    val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    paintApply()
    applyButton.onClick {
        val picks =
            proposal.rows
                .filter {
                    it.positionCode in ticked && it.transferable
                }.map { PartgProposalPickDto(it.positionCode, it.proposedCents) }
        if (picks.isEmpty()) return@onClick
        applyButton.disabled = true
        AppScope.launch {
            try {
                val result =
                    partyReportRpc().applyLedgerProposal(
                        year = proposal.year,
                        unitKey = proposal.unitKey,
                        expectedRevision = proposal.revision,
                        expectedPriorRevision = proposal.priorRevision,
                        picks = picks,
                    )
                modal.hide()
                notifySuccess(gettext("%1 Werte übernommen.", result.changed))
                onApplied()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: PartyReportProposalStaleException) {
                failure.content = tr("Buchungen haben sich geändert, Vorschlag neu laden")
                failure.show()
                failure.actionButton(ActionIcon.REFRESH, tr("Neu laden")) {
                    onClick {
                        modal.hide()
                        onReload()
                    }
                }
            } catch (e: Throwable) {
                applyButton.disabled = false
                guarded<Unit> { throw e }
            }
        }
    }
    modal.addButton(cancel)
    modal.addButton(applyButton)
    modal.show()
}
