package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.CreateAuctionListingInput
import network.lapis.cloud.shared.domain.DurationUnit
import network.lapis.cloud.shared.domain.durationToHours
import network.lapis.cloud.shared.domain.maxAmount
import network.lapis.cloud.shared.rpc.IAuctionService

/**
 * V1.9.49 (R36B): the listing form of the auction screen, built into the host of a collapsible create form ([collapsibleCreateForm])
 * behind the page header button "Neues Angebot". It only writes ([IAuctionService.createListing]); every read stays in `AuctionScreen.kt`.
 *
 * D3 (unchanged): the member's own LTR balance comes first, before any input, and the flat listing fee is stated right under it -- both
 * now live inside the opened form, not on the closed page. The Tier 1 [confirmDialog] before the fee is booked is unchanged. The
 * fingerprint covers the inputs (loose widgets, no `LapisForm`) and the number of chosen pictures.
 *
 * V1.9.222: the duration is a number plus a unit (hours, days, weeks; default 7 days) converted ONLY by `durationToHours` of
 * lapis-shared,
 * whose
 * bound (2160 hours = 90 days) is the server's own -- a value above it (13 weeks and more) is refused with a hint, never shortened. The
 * form has a
 * picture gallery ([ImageGalleryEditor], buffer mode). After `createListing` the form STAYS OPEN until every picture is uploaded: the
 * listing
 * already exists then, so a failed picture is retried without losing it; only a clean finish (or "Fertig") folds the form back.
 */
internal fun SimplePanel.renderCreateListingForm(
    collapse: (Boolean) -> Unit,
    onCompleted: () -> Unit,
): FormSnapshot {
    val panel = vPanel(spacing = 6)
    panel.renderMyLtrBalanceInline()
    panel.div(gettext("Beim Einstellen wird eine feste Gebühr von %1 fällig.", formatLtr(0.01.toDecimal()))) {
        addCssClasses("text-muted small")
    }
    val titleInput = panel.text(label = tr("Titel"))
    val descriptionInput = panel.textArea(label = tr("Beschreibung"), rows = 3)
    val startingBidInput = panel.text(label = tr("Startpreis (LTR)"))
    val buyNowInput = panel.text(label = tr("Sofortkaufpreis (LTR, optional -- muss über dem Startpreis liegen)"))
    // V1.9.222: number + unit. The bound is the server's (AuctionDurationRules), converted by durationToHours -- one source for both
    // sides.
    val durationRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-end flex-wrap") }
    val durationInput = durationRow.text(label = tr("Laufzeit"), type = InputType.NUMBER) { value = "7" }
    val unitSelect =
        durationRow.select(
            options =
                listOf(
                    DurationUnit.HOURS.name to tr("Stunden"),
                    DurationUnit.DAYS.name to tr("Tage"),
                    DurationUnit.WEEKS.name to tr("Wochen"),
                ),
            value = DurationUnit.DAYS.name,
            label = tr("Einheit"),
        )
    val durationHint = panel.div(className = "text-muted small") { setAttribute("id", "lapis-auction-duration-hint") }
    durationInput.input.setAttribute("aria-describedby", "lapis-auction-duration-hint")
    durationInput.input.setAttribute("min", "1")

    fun selectedUnit(): DurationUnit = DurationUnit.entries.firstOrNull { it.name == unitSelect.value } ?: DurationUnit.DAYS

    fun refreshDurationHint(invalid: Boolean) {
        val unit = selectedUnit()
        durationInput.input.setAttribute("max", maxAmount(unit).toString())
        durationHint.content =
            when (unit) {
                DurationUnit.HOURS -> gettext("1–%1 Stunden (maximal 90 Tage)", maxAmount(unit))
                DurationUnit.DAYS -> gettext("1–%1 Tage", maxAmount(unit))
                DurationUnit.WEEKS -> gettext("1–%1 Wochen (maximal 90 Tage)", maxAmount(unit))
            }
        if (invalid) {
            durationHint.removeCssClass("text-muted")
            durationHint.addCssClass("text-danger")
        } else {
            durationHint.removeCssClass("text-danger")
            durationHint.addCssClass("text-muted")
        }
    }
    refreshDurationHint(invalid = false)
    unitSelect.subscribe { refreshDurationHint(invalid = false) }

    // The listing is created before its pictures are uploaded; the form folds back only when nothing is left to upload or retry.
    var createdId: String? = null
    var finished = false
    lateinit var galleryEditor: ImageGalleryEditor

    fun finish() {
        if (finished) return
        finished = true
        collapse(true)
        onCompleted()
    }
    galleryEditor =
        panel.imageGalleryEditor(
            AttachedImageOwnerType.AUCTION_LISTING,
            ownerTitle = { titleInput.value.orEmpty().trim() },
            onChange = { if (createdId != null && !galleryEditor.hasFailures() && galleryEditor.pendingCount() == 0) finish() },
        )
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttons = panel.hPanel(spacing = 8)
    val submitButton = newActionButton(ActionIcon.ADD, tr("Angebot erstellen"), ButtonStyle.PRIMARY)
    val doneButton = newActionButton(ActionIcon.APPROVE, tr("Fertig"), ButtonStyle.PRIMARY)
    buttons.add(collapseCancelButton(collapse))
    buttons.add(submitButton)
    doneButton.onClick { finish() }

    submitButton.onClick {
        errorBox.hide()
        val title = titleInput.value.orEmpty().trim()
        val description = descriptionInput.value.orEmpty().trim()
        val startingBidText = startingBidInput.value.orEmpty().trim()
        val buyNowText = buyNowInput.value.orEmpty().trim()
        val durationAmount =
            durationInput.value
                .orEmpty()
                .trim()
                .toIntOrNull()
        val unit = selectedUnit()
        val durationHours = durationAmount?.let { durationToHours(it, unit) }

        val startingBidParsed = parseLtrAmountInput(startingBidText)
        if (!Validation.isNonBlank(title) || !Validation.isNonBlank(description) || startingBidParsed !is AmountInput.Valid) {
            errorBox.content =
                amountErrorText(startingBidParsed, tr("Bitte Titel, Beschreibung und einen positiven Startpreis (LTR) angeben."))
            errorBox.show()
            return@onClick
        }
        if (durationAmount == null || durationHours == null) {
            refreshDurationHint(invalid = true)
            errorBox.content = tr("Bitte eine Laufzeit im angegebenen Bereich wählen.")
            errorBox.show()
            return@onClick
        }
        refreshDurationHint(invalid = false)
        val startingBid = startingBidParsed.value
        var buyNowPrice: Decimal? = null
        if (buyNowText.isNotBlank()) {
            val buyNowParsed = parseLtrAmountInput(buyNowText)
            if (buyNowParsed !is AmountInput.Valid) {
                errorBox.content =
                    amountErrorText(buyNowParsed, tr("Der Sofortkaufpreis muss, falls angegeben, ein positiver LTR-Betrag sein."))
                errorBox.show()
                return@onClick
            }
            val parsed = buyNowParsed.value
            if (parsed.toDouble() <= startingBid.toDouble()) {
                errorBox.content = tr("Der Sofortkaufpreis muss über dem Startpreis liegen.")
                errorBox.show()
                return@onClick
            }
            buyNowPrice = parsed
        }

        // Tier 1 "Kostenpflichtig" (D4): the plain, neutral-framed confirmDialog -- states the
        // flat listing fee plus the chosen parameters plainly before the caller commits.
        val buyNowSummary = buyNowPrice?.let { gettext(", Sofortkaufpreis %1", formatLtr(it)) } ?: ""
        confirmDialog(
            title = tr("Angebot erstellen"),
            message =
                gettext(
                    "Es wird ein Angebot \"%1\" mit Startpreis %2%3 und %4 Laufzeit erstellt. Dabei wird eine feste " +
                        "Gebühr von %5 aus Ihrem freien LTR-Guthaben gebucht.",
                    title,
                    formatLtr(startingBid),
                    buyNowSummary,
                    durationLabel(durationAmount, unit),
                    formatLtr(0.01.toDecimal()),
                ),
            confirmLabel = tr("Erstellen"),
        ) {
            submitButton.disabled = true
            AppScope.launch {
                val result =
                    guarded {
                        rpcService<IAuctionService>().createListing(
                            CreateAuctionListingInput(
                                title = title,
                                description = description,
                                startingBidLtr = startingBid,
                                buyNowPriceLtr = buyNowPrice,
                                durationHours = durationHours,
                            ),
                        )
                    }
                if (result == null) {
                    submitButton.disabled = false
                    return@launch
                }
                notifySuccess(gettext("Angebot \"%1\" erstellt.", result.title))
                // The listing exists now: it must not be created twice, so the button stays off. The pictures follow; the form stays
                // open
                // for
                // a retry of a failed one.
                createdId = result.id
                if (galleryEditor.pendingCount() == 0 || galleryEditor.uploadPending(result.id)) {
                    finish()
                } else {
                    errorBox.content = tr("Angebot wurde erstellt; einzelne Bilder fehlen noch.")
                    errorBox.show()
                    buttons.removeAll()
                    buttons.add(doneButton)
                }
            }
        }
    }
    return FormSnapshot {
        listOf(titleInput, descriptionInput, startingBidInput, buyNowInput, durationInput).map { it.value.orEmpty() } +
            listOf(unitSelect.value.orEmpty(), galleryEditor.pendingCount().toString())
    }
}

/** "2 Wochen", "1 Tag", "36 Stunden" -- the duration the person chose, in the plural form of the language. */
internal fun durationLabel(
    amount: Int,
    unit: DurationUnit,
): String =
    when (unit) {
        DurationUnit.HOURS -> ngettext("%1 Stunde", "%1 Stunden", amount, amount)
        DurationUnit.DAYS -> ngettext("%1 Tag", "%1 Tage", amount, amount)
        DurationUnit.WEEKS -> ngettext("%1 Woche", "%1 Wochen", amount, amount)
    }
