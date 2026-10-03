package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.CrowdfundingProjectInput
import network.lapis.cloud.shared.rpc.ICrowdfundingService

/**
 * V1.9.48 (R36B): the two create forms of the crowdfunding screen, built into the host of a collapsible create form
 * ([collapsibleCreateForm]) -- "Projekt einreichen" (page header) and "Verteilung berechnen" (title row "Treuhänder-Werkzeuge").
 * Behaviour, validation and RPCs are unchanged from before the move; only the collapse callback and the [FormSnapshot] are new.
 */

internal fun renderSubmitProjectForm(
    root: SimplePanel,
    collapse: ((Boolean) -> Unit)? = null,
    onCompleted: () -> Unit,
): FormSnapshot {
    val panel = root.vPanel(spacing = 6)
    // D3: the free LTR balance is the first element, before any input field.
    panel.renderMyLtrBalanceInline()
    val titleInput = panel.text(label = tr("Titel"))
    val descriptionInput = panel.textArea(label = tr("Beschreibung"), rows = 3)
    val weightInput = panel.text(label = tr("Sichtbarkeits-Gewicht (LTR)"))
    // D7 (must-fix, resolved by reading CrowdfundingService.kt in full): the stake is NEVER
    // refunded -- not on rejection, not on approval, there is no release path in this codebase at
    // all (LtrLedgerEntryType.PROJECT_STAKE_RELEASE is reserved-and-unused). Stated plainly, not
    // left to member inference.
    panel.div(
        tr(
            "Ihr Einsatz wird NICHT zurückerstattet -- unabhängig davon, ob der Vorstand das Projekt später " +
                "genehmigt oder ablehnt. Es gibt in diesem System keinen Rückerstattungspfad für diesen Einsatz.",
        ),
    ) { addCssClasses("text-muted small") }
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttonRow = panel.hPanel(spacing = 8)
    val submitButton = buttonRow.actionButton(ActionIcon.SEND, tr("Projekt einreichen"), style = ButtonStyle.PRIMARY)
    if (collapse != null) buttonRow.add(collapseCancelButton(collapse))

    submitButton.onClick {
        errorBox.hide()
        val title = titleInput.value.orEmpty().trim()
        val description = descriptionInput.value.orEmpty().trim()
        val weightText = weightInput.value.orEmpty().trim()

        if (!Validation.isNonBlank(title) || !Validation.isNonBlank(description) || !Validation.isPositiveDecimal(weightText)) {
            errorBox.content = tr("Bitte Titel, Beschreibung und ein positives Sichtbarkeits-Gewicht (LTR) angeben.")
            errorBox.show()
            return@onClick
        }
        val weight = weightText.toDouble().toDecimal()

        // Tier 1 "Kostenpflichtig" (D4): the plain, neutral-framed confirmDialog -- material to the
        // submitter's own free balance, not a treasury cost.
        confirmDialog(
            title = tr("Projekt einreichen"),
            message =
                gettext(
                    "Es werden %1 als Sichtbarkeits-Gewicht aus Ihrem freien LTR-Guthaben gebunden. " +
                        "Dieser Einsatz wird NICHT zurückerstattet, unabhängig von der späteren Vorstandsentscheidung.",
                    formatLtr(weight),
                ),
            confirmLabel = tr("Einreichen"),
        ) {
            submitButton.disabled = true
            AppScope.launch {
                val result =
                    guarded {
                        rpcService<ICrowdfundingService>().submitProject(
                            CrowdfundingProjectInput(title = title, description = description, initialWeightLtr = weight),
                        )
                    }
                submitButton.disabled = false
                if (result != null) {
                    notifySuccess(gettext("Projekt \"%1\" eingereicht.", result.title))
                    collapse?.invoke(true)
                    onCompleted()
                }
            }
        }
    }
    return FormSnapshot { listOf(titleInput.value.orEmpty(), descriptionInput.value.orEmpty(), weightInput.value.orEmpty()) }
}
// ================================================================================================
// Distribution compute form
// ================================================================================================

/**
 * No confirm-dialog: idempotent per period (unique constraint project+period, `insertIgnore`) and
 * produces only an audit/decision record, never a bank transfer or `JournalEntry` -- a considered
 * and rejected decision, same posture `AuctionService`'s own KDoc documents for its analogous
 * `settleAuction` call. Both [periodStart]/[periodEnd] are required here (unlike
 * `AccountingFilters.dateRangeFilter`'s usual optional "Von" -- overridden via custom labels).
 */
internal fun renderDistributionComputeForm(
    root: SimplePanel,
    collapse: ((Boolean) -> Unit)? = null,
    onCompleted: () -> Unit,
): FormSnapshot {
    root.div(
        tr(
            "Berechnet den EUR-Spendenpool für den gewählten Zeitraum (bezahlte Beiträge abzüglich einer festen " +
                "Mindestbeteiligung je Zahler) und verteilt ihn proportional nach Verteilungs-Korb auf alle genehmigten " +
                "Projekte. Erzeugt nur einen Prüf-/Entscheidungsdatensatz, keine Journalbuchung/Überweisung -- erneutes " +
                "Ausführen für denselben Zeitraum erzeugt keine Duplikate.",
        ),
    ) { addCssClasses("text-muted small mb-2") }
    val range =
        root.dateRangeFilter(fromLabel = tr("Von (JJJJ-MM-TT, Pflichtfeld)"), toLabel = tr("Bis (JJJJ-MM-TT, Pflichtfeld)"))
    val errorBox =
        root.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttonRow = root.hPanel(spacing = 8)
    val computeButton = buttonRow.button(tr("Verteilung berechnen"), style = ButtonStyle.PRIMARY)
    if (collapse != null) buttonRow.add(collapseCancelButton(collapse))

    computeButton.onClick {
        errorBox.hide()
        val periodStart = range.parseFrom()
        val periodEnd = range.parseTo()
        if (periodStart == null || periodEnd == null) {
            errorBox.content = tr("Bitte Start- und Enddatum im Format JJJJ-MM-TT angeben -- beide Felder sind hier Pflicht.")
            errorBox.show()
            return@onClick
        }
        computeButton.disabled = true
        AppScope.launch {
            val distributions = guarded { rpcService<ICrowdfundingService>().computeMonthlyDistribution(periodStart, periodEnd) }
            computeButton.disabled = false
            if (distributions != null) {
                notifySuccess(
                    gettext(
                        "Verteilung für %1 bis %2 berechnet (%3 Projekt(e)).",
                        formatDate(periodStart),
                        formatDate(periodEnd),
                        distributions.size,
                    ),
                )
                collapse?.invoke(true)
                onCompleted()
            }
        }
    }
    return FormSnapshot { listOf(range.fromInput.value.orEmpty(), range.toInput.value.orEmpty()) }
}
