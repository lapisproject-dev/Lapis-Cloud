package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.form.check.checkBox
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.ConversionRateBasis
import network.lapis.cloud.shared.domain.DonationConversionInput
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.PriceOracleConfigDto
import network.lapis.cloud.shared.domain.ReferenceDateFallback
import network.lapis.cloud.shared.domain.ReferenceRateBasis
import network.lapis.cloud.shared.domain.ReferenceRateConfirmationInput
import network.lapis.cloud.shared.domain.ReferenceRateOutcome
import network.lapis.cloud.shared.domain.ReferenceRateResolutionDto
import network.lapis.cloud.shared.domain.ReferenceRateRules
import network.lapis.cloud.shared.rpc.IPriceOracleService

/**
 * V1.9.324 -- "Goldpreis zum Stichtag": the donation conversion form of the reference-date mode. The treasurer picks the member, the amount and
 * the reference date, checks the rate the server found, and only then may convert. Everything shown comes from the server's answer
 * ([ReferenceRateResolutionDto]); the client never derives a rate, a trading day or a fallback itself (the LTR figure in the preview is an
 * estimate, labelled as such, like in the ordinary form). The conversion is bound to the previewed rate: the price and the snapshot or rate id of
 * the preview travel with the request, and any change of member, amount or date discards the preview.
 */
internal fun renderReferenceDateConvertForm(
    root: SimplePanel,
    members: List<MemberSummaryDto>,
    config: PriceOracleConfigDto,
) {
    if (members.isEmpty()) {
        root.div(tr("Keine Mitglieder vorhanden, denen eine Spende gutgeschrieben werden könnte.")) { addCssClasses("text-muted small") }
        return
    }
    val panel = root.vPanel(spacing = 6)
    panel.div(
        tr(
            "Bucht eine bereits eingegangene Spende als LTR-Mint auf das gewählte Mitglied, zum Goldpreis des Stichtags. " +
                "Zuerst wird der Kurs des Stichtags geprüft; gebucht wird genau dieser Kurs.",
        ),
    ) { addCssClasses("text-muted small") }

    val memberSelect = panel.searchableSelect(options = untrustedOptions(members.map { it.id to it.displayName }), label = tr("Mitglied"))
    val amountInput = panel.text(label = tr("Spendenbetrag"))
    val dateInput = panel.localDateInput(label = tr("Stichtag"))
    panel.div(tr("Der Tag, dessen Schlusskurs gelten soll. Frühestens gestern: der Kurs eines Tages steht erst nach Mitternacht fest.")) {
        addCssClasses("text-muted small")
    }
    val noteInput = panel.text(label = tr("Notiz (optional)"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val checkRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val checkButton = checkRow.button(tr("Kurs zum Stichtag prüfen"), style = ButtonStyle.OUTLINESECONDARY)
    val busyLabel = checkRow.div(tr("Wird ausgeführt …")) { addCssClasses("text-muted small") }
    busyLabel.hide()
    val previewPanel = panel.vPanel(spacing = 4)
    val outcomePanel = panel.vPanel(spacing = 4)

    val convertRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val convertButton = convertRow.button(tr("Zu LTR konvertieren"), style = ButtonStyle.OUTLINEDANGER)
    convertButton.disabled = true

    var previewed: PreviewedRate? = null

    fun invalidate() {
        previewed = null
        convertButton.disabled = true
        previewPanel.removeAll()
    }
    memberSelect.subscribe { invalidate() }
    amountInput.subscribe { invalidate() }
    dateInput.subscribe { invalidate() }

    fun readInputs(): Triple<MemberSummaryDto, Decimal, LocalDate>? {
        val member = members.find { it.id == memberSelect.value }
        val amountText = amountInput.value.orEmpty().trim()
        val date = dateInput.dateOrNull()
        if (member == null || !Validation.isPositiveDecimal(amountText) || date == null) {
            errorBox.content = tr("Bitte ein Mitglied, einen positiven Spendenbetrag und einen gültigen Stichtag angeben.")
            errorBox.show()
            return null
        }
        return Triple(member, amountText.toDouble().toDecimal(), date)
    }

    fun runCheck() {
        errorBox.hide()
        outcomePanel.removeAll()
        invalidate()
        val (member, amount, date) = readInputs() ?: return
        checkButton.disabled = true
        busyLabel.show()
        AppScope.launch {
            val resolution = guarded { rpcService<IPriceOracleService>().previewReferenceRate(date, member.id) }
            checkButton.disabled = false
            busyLabel.hide()
            if (resolution == null) return@launch
            renderResolution(previewPanel, resolution, config, amount) { runCheck() }
            if (resolution.outcome == ReferenceRateOutcome.RESOLVED && !resolution.selfConfirmationBlocked) {
                previewed = PreviewedRate(member.id, amountInput.value.orEmpty().trim(), date, resolution)
                convertButton.disabled = false
            }
        }
    }
    checkButton.onClick { runCheck() }

    convertButton.onClick {
        errorBox.hide()
        val preview = previewed
        val inputs = readInputs()
        if (preview == null || inputs == null) {
            invalidate()
            return@onClick
        }
        val (member, amount, date) = inputs
        val resolution = preview.resolution
        val price = resolution.price
        // The preview belongs to exactly one member, amount and date; anything else needs a new check.
        if (member.id != preview.memberId ||
            amountInput.value.orEmpty().trim() != preview.amountText ||
            date != preview.date ||
            price == null
        ) {
            invalidate()
            errorBox.content = tr("Die Eingaben wurden geändert. Bitte den Kurs erneut prüfen.")
            errorBox.show()
            return@onClick
        }
        val note = noteInput.value?.trim()?.takeIf { it.isNotBlank() }
        confirmDialog(
            title = tr("Spenden-Konvertierung bestätigen"),
            message =
                gettext(
                    "Sie buchen eine Spende von %1 für %2 zum Kurs vom %3 (%4 je %5).",
                    formatDonationAmount(amount, config.donationCurrency),
                    member.displayName,
                    formatDate(resolution.effectiveDate),
                    formatDonationAmount(price, config.donationCurrency),
                    anchorAssetLabel(config.anchorAsset),
                ),
            confirmLabel = tr("Konvertieren"),
            dangerNote = tr("Diese Aktion mintet echtes LTR und kann nicht rückgängig gemacht werden."),
            focusCancel = true,
        ) {
            convertButton.disabled = true
            AppScope.launch {
                val result =
                    guarded {
                        rpcService<IPriceOracleService>().convertDonationToLtr(
                            DonationConversionInput(
                                memberId = member.id,
                                donationAmount = amount,
                                note = note,
                                referenceDate = date,
                                expectedPrice = price,
                                expectedRateRefId = resolution.snapshotId ?: resolution.referenceRateId,
                            ),
                        )
                    }
                if (result != null) {
                    notifySuccess(gettext("%1 für %2 gemintet.", formatLtr(result.ltrMinted), member.displayName))
                    renderConversionResult(outcomePanel, result)
                    amountInput.value = null
                    noteInput.value = null
                    invalidate()
                } else {
                    // The rate may have changed meanwhile: the preview is no longer trustworthy.
                    invalidate()
                }
            }
        }
    }
}

private class PreviewedRate(
    val memberId: String,
    val amountText: String,
    val date: LocalDate,
    val resolution: ReferenceRateResolutionDto,
)

private fun renderResolution(
    panel: SimplePanel,
    resolution: ReferenceRateResolutionDto,
    config: PriceOracleConfigDto,
    amount: Decimal,
    onConfirmed: () -> Unit,
) {
    panel.removeAll()
    val box = panel.vPanel(spacing = 2) { addCssClasses("border rounded p-2 bg-body-tertiary small") }
    box.div(gettext("Stichtag: %1", formatDate(resolution.referenceDate))) { addCssClass("fw-bold") }
    if (resolution.fallbackReason != ReferenceDateFallback.NONE) {
        box.div(
            gettext(
                "Für diesen Tag gibt es keinen Handelskurs (%1). Verwendet wird der Kurs vom %2.",
                fallbackLabel(resolution.fallbackReason),
                formatDate(resolution.effectiveDate),
            ),
        ) { addCssClass("text-muted") }
    }
    val price = resolution.price
    if (resolution.outcome != ReferenceRateOutcome.RESOLVED || price == null) {
        val missing = box.vPanel(spacing = 4) { addCssClasses("alert alert-warning mb-0") }
        missing.div(outcomeLabel(resolution.outcome))
        resolution.legacyMedianHint?.let {
            missing.div(
                gettext(
                    "Gespeicherter Median dieses Tages (nur zur Orientierung, nicht verwendbar): %1",
                    formatDonationAmount(it, config.donationCurrency),
                ),
            )
        }
        val manualButton = missing.button(tr("Kurs manuell bestätigen"), style = ButtonStyle.OUTLINEPRIMARY)
        manualButton.onClick { manualRateDialog(resolution, config, onConfirmed) }
        return
    }
    box.div(
        if (resolution.basis == ReferenceRateBasis.MANUAL) {
            gettext(
                "Grundlage: manuell bestätigter Kurs (%1, %2)",
                resolution.confirmedByDisplayName.orEmpty(),
                resolution.confirmedAt?.let { formatSystemDateTime(it) }.orEmpty(),
            )
        } else {
            tr("Grundlage: gespeicherter Kurs aus dem Kursverlauf")
        },
    ) { addCssClass("text-muted") }
    resolution.evidenceText?.let { box.div(gettext("Beleg: %1", it)) { addCssClass("text-muted") } }
    if (resolution.sources.isNotEmpty()) {
        box.div(gettext("Quellen (mindestens %1 nötig):", resolution.quorumRequired)) { addCssClass("text-muted") }
        resolution.sources.forEach { source ->
            box.div(
                gettext(
                    "%1: %2 (%3, %4)",
                    source.sourceId,
                    formatDonationAmount(source.rawPrice, config.donationCurrency),
                    formatSystemDateTime(source.observedAt),
                    if (source.included) gettext("verwendet") else gettext("ausgeschlossen"),
                ),
            ) { addCssClass("text-muted") }
        }
    }
    box.div(gettext("Kurs: %1 je %2", formatDonationAmount(price, config.donationCurrency), anchorAssetLabel(config.anchorAsset))) {
        addCssClass("fw-bold")
    }
    estimateLtrMinted(donationAmount = amount, anchorUnitsPerLtr = config.anchorUnitsPerLtr, anchorPrice = price)?.let {
        box.div(gettext("Geschätzt: ca. %1 (der genaue Betrag steht nach der Buchung fest).", formatLtr(it))) { addCssClass("text-muted") }
    }
    if (resolution.selfConfirmationBlocked) {
        box.div(
            tr(
                "Sie haben diesen Kurs selbst bestätigt und dürfen damit keine Spende für sich selbst umrechnen. Bitte eine andere Person buchen lassen.",
            ),
        ) { addCssClasses("alert alert-warning mb-0") }
    }
}

/** The dialog that records a manual rate: price, evidence text (counter), and the acknowledgement of a large deviation from the stored price. */
private fun manualRateDialog(
    resolution: ReferenceRateResolutionDto,
    config: PriceOracleConfigDto,
    onConfirmed: () -> Unit,
) {
    val modal = Modal(caption = tr("Kurs manuell bestätigen"))
    modal.div(
        gettext(
            "Kurs für den %1 (Kurstag %2) in %3 je %4. Der Kurs kann danach nicht mehr geändert werden.",
            formatDate(resolution.referenceDate),
            formatDate(resolution.effectiveDate),
            config.donationCurrency,
            anchorAssetLabel(config.anchorAsset),
        ),
    )
    val priceInput = modal.text(label = tr("Kurs"))
    val evidenceInput = modal.textArea(label = tr("Beleg (Quelle und Begründung)")) { rows = 4 }
    val counter = modal.div(gettext("%1 / %2 Zeichen", 0, ReferenceRateRules.EVIDENCE_MAX)) { addCssClasses("text-muted small") }
    evidenceInput.subscribe {
        counter.content =
            gettext("%1 / %2 Zeichen", ReferenceRateRules.normalizeEvidence(it.orEmpty()).length, ReferenceRateRules.EVIDENCE_MAX)
    }
    val comparison = resolution.comparisonMedian
    val acknowledge =
        modal.checkBox(label = tr("Ich bestätige, dass der Kurs deutlich vom gespeicherten Kurs dieses Tages abweicht")).apply {
            if (comparison == null) hide()
        }
    val errorBox =
        modal.div().apply {
            addCssClass("text-danger")
            hide()
        }
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Kurs bestätigen"), style = ButtonStyle.PRIMARY).apply {
            onClick {
                errorBox.hide()
                val priceText = priceInput.value.orEmpty().trim()
                val evidence = ReferenceRateRules.normalizeEvidence(evidenceInput.value.orEmpty())
                if (!Validation.isPositiveDecimal(priceText) ||
                    evidence.length < ReferenceRateRules.EVIDENCE_MIN ||
                    evidence.length > ReferenceRateRules.EVIDENCE_MAX
                ) {
                    errorBox.content =
                        gettext(
                            "Bitte einen positiven Kurs und einen Beleg mit %1 bis %2 Zeichen angeben.",
                            ReferenceRateRules.EVIDENCE_MIN,
                            ReferenceRateRules.EVIDENCE_MAX,
                        )
                    errorBox.show()
                    return@onClick
                }
                val price = priceText.toDouble().toDecimal()
                val deviates =
                    comparison != null &&
                        comparison.toDouble() > 0.0 &&
                        kotlin.math.abs(price.toDouble() - comparison.toDouble()) * BPS_PER_UNIT / comparison.toDouble() >
                        config.maxSpreadBps
                if (deviates && !acknowledge.value) {
                    errorBox.content =
                        tr("Der Kurs weicht stark vom gespeicherten Kurs ab. Bitte die Abweichung bestätigen oder den Kurs prüfen.")
                    errorBox.show()
                    return@onClick
                }
                disabled = true
                AppScope.launch {
                    val result =
                        guarded {
                            rpcService<IPriceOracleService>().confirmReferenceRate(
                                ReferenceRateConfirmationInput(
                                    referenceDate = resolution.referenceDate,
                                    price = price,
                                    evidenceText = evidence,
                                    acknowledgeDeviation = deviates && acknowledge.value,
                                ),
                            )
                        }
                    disabled = false
                    if (result != null) {
                        modal.hide()
                        notifySuccess(tr("Kurs bestätigt."))
                        onConfirmed()
                    }
                }
            }
        },
    )
    modal.show()
}

private const val BPS_PER_UNIT = 10_000

private fun fallbackLabel(reason: ReferenceDateFallback): String =
    when (reason) {
        ReferenceDateFallback.NONE -> ""
        ReferenceDateFallback.WEEKEND -> gettext("Wochenende")
        ReferenceDateFallback.HOLIDAY -> gettext("Feiertag")
    }

private fun outcomeLabel(outcome: ReferenceRateOutcome): String =
    when (outcome) {
        ReferenceRateOutcome.RESOLVED -> ""
        ReferenceRateOutcome.NO_DATA -> gettext("Für diesen Tag liegt kein gespeicherter Kurs vor.")
        ReferenceRateOutcome.QUORUM_MISSING -> gettext("Die gespeicherten Kurse dieses Tages erfüllen das Mindest-Quorum nicht.")
        ReferenceRateOutcome.STATUS_NOT_LIVE ->
            gettext(
                "Das Mindest-Quorum war erfüllt, aber nicht alle Quellen waren verfügbar (Status nicht LIVE). " +
                    "Der Kurs muss manuell bestätigt werden.",
            )
        ReferenceRateOutcome.RAW_VALUES_MISSING ->
            gettext("Für diesen Tag fehlen die Einzelwerte der Quellen (älterer Kursverlauf). Der Kurs muss manuell bestätigt werden.")
    }

/** [typeBadge] grammar: the setting is a fixed choice, covers every literal. */
internal fun conversionRateBasisLabel(basis: ConversionRateBasis): String =
    when (basis) {
        ConversionRateBasis.CURRENT -> gettext("Aktueller Kurs")
        ConversionRateBasis.REFERENCE_DATE -> gettext("Goldpreis zum Stichtag")
    }

/** The stored basis of a finished conversion, for the result card. */
internal fun referenceRateBasisLabel(basis: ReferenceRateBasis): String =
    when (basis) {
        ReferenceRateBasis.CURRENT -> gettext("Aktueller Kurs")
        ReferenceRateBasis.SNAPSHOT -> gettext("Gespeicherter Kurs des Stichtags")
        ReferenceRateBasis.MANUAL -> gettext("Manuell bestätigter Kurs")
    }
