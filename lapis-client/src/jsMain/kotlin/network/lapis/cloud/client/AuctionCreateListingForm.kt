package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.form.check.checkBox
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.AuctionComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.AuctionCurrencies
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionFiatAvailabilityDto
import network.lapis.cloud.shared.domain.AuctionPaymentDetailsInput
import network.lapis.cloud.shared.domain.CreateAuctionListingInput
import network.lapis.cloud.shared.domain.CreateFiatAuctionListingInput
import network.lapis.cloud.shared.domain.DurationUnit
import network.lapis.cloud.shared.domain.durationToHours
import network.lapis.cloud.shared.domain.maxAmount
import network.lapis.cloud.shared.rpc.AuctionPaymentDetailsField
import network.lapis.cloud.shared.rpc.AuctionPaymentDetailsInvalidException
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
 *
 * V1.9.320: when the instance has switched Fiat auctions on ([IAuctionService.getAuctionFiatAvailability]) a "Bezahlung" choice (LTR or
 * Euro) appears. Euro replaces the fee line with the three payment-detail inputs (holder, IBAN, link; at least one of IBAN/link), a
 * reminder that the money moves directly between the two people, and -- until the member has acknowledged the CURRENT seller notice -- the
 * notice itself with a mandatory checkbox. The default stays LTR; without the switch the form is exactly the LTR form.
 */
internal fun SimplePanel.renderCreateListingForm(
    collapse: (Boolean) -> Unit,
    onCompleted: () -> Unit,
): FormSnapshot {
    val panel = vPanel(spacing = 6)
    panel.renderMyLtrBalanceInline()
    val feeLine =
        panel.div(gettext("Beim Einstellen wird eine feste Gebühr von %1 fällig.", formatLtr(0.01.toDecimal()))) {
            addCssClasses("text-muted small")
        }
    val titleInput = panel.text(label = tr("Titel"))
    val descriptionInput = panel.textArea(label = tr("Beschreibung"), rows = 3)
    // V1.9.320: only offered when the instance switched Fiat auctions on; stays hidden (and out of the DOM) otherwise.
    val currencySelect =
        panel.select(
            options =
                listOf(
                    AuctionCurrencies.LTR to tr("LTR (Libertaler)"),
                    AuctionCurrencies.EUR to tr("Euro (Direktzahlung)"),
                ),
            value = AuctionCurrencies.LTR,
            label = tr("Bezahlung"),
        )
    currencySelect.hide()
    val startingBidInput = panel.text(label = tr("Startpreis (LTR)"))
    val buyNowInput = panel.text(label = tr("Sofortkaufpreis (LTR, optional -- muss über dem Startpreis liegen)"))
    val fiatSection = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-2") }
    val holderInput = fiatSection.text(label = tr("Kontoinhaber"))
    val ibanInput = fiatSection.text(label = tr("IBAN (deutsches Konto)"))
    val linkInput = fiatSection.text(label = tr("Zahlungslink (https, optional, wenn eine IBAN angegeben ist)"))
    fiatSection.div(
        tr(
            "Mindestens IBAN oder Zahlungslink ist nötig. Die Angaben werden verschlüsselt gespeichert und nur dem " +
                "Höchstbietenden nach dem Zuschlag gezeigt. Danach können Sie sie nicht mehr ändern.",
        ),
    ) { addCssClasses("text-muted small") }
    fiatSection.div(
        tr("Die Zahlung erfolgt direkt zwischen Ihnen und dem Höchstbietenden. Lapis nimmt kein Geld an und vermittelt bei Streit nicht."),
    ) { addCssClasses("small fw-bold") }
    // V1.9.321: price offers are fixed at creation and need a Sofortkaufpreis as the upper bound.
    val offersBox = fiatSection.checkBox(value = false, label = tr("Preisvorschläge erlauben"))
    fiatSection.div(
        tr(
            "Käufer können einen Preis unter dem Sofortkaufpreis vorschlagen. Das ist nur mit Sofortkaufpreis möglich " +
                "und kann nach dem Einstellen nicht mehr geändert werden.",
        ),
    ) { addCssClasses("text-muted small") }
    val noticeHost = fiatSection.vPanel(spacing = 4)
    fiatSection.hide()
    var availability: AuctionFiatAvailabilityDto? = null
    var acknowledgeBox: io.kvision.form.check.CheckBox? = null
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

    fun isEuro(): Boolean = currencySelect.value == AuctionCurrencies.EUR

    // V1.9.320: the labels and the visible blocks follow the chosen currency; LTR (the default) is the form as it always was.
    fun applyCurrency() {
        val euro = isEuro()
        startingBidInput.label = if (euro) tr("Startpreis (EUR)") else tr("Startpreis (LTR)")
        buyNowInput.label =
            if (euro) {
                tr("Sofortkaufpreis (EUR, optional -- muss über dem Startpreis liegen)")
            } else {
                tr("Sofortkaufpreis (LTR, optional -- muss über dem Startpreis liegen)")
            }
        if (euro) {
            feeLine.hide()
            fiatSection.show()
        } else {
            feeLine.show()
            fiatSection.hide()
        }
    }
    currencySelect.subscribe { applyCurrency() }

    AppScope.launch {
        val loaded =
            try {
                rpcService<IAuctionService>().getAuctionFiatAvailability()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Quiet by design: without the Euro offer the form is the plain LTR form; nothing the person did failed.
                null
            }
        if (loaded != null && loaded.fiatEnabled) {
            availability = loaded
            currencySelect.show()
            val acknowledgedAt = loaded.sellerAcknowledgedAt
            if (loaded.sellerAcknowledgedCurrentVersion && acknowledgedAt != null) {
                noticeHost.div(gettext("Hinweis für Verkäufer bestätigt am %1", formatSystemDateTime(acknowledgedAt))) {
                    addCssClasses("text-muted small")
                }
            } else {
                noticeHost.disclosure(tr("Hinweis für Verkäufer lesen"), bodyId = "lapis-auction-fiat-notice") {
                    div {
                        addCssClasses("border rounded p-2 small lapis-pre-line")
                        content = sanitizeUntrustedI18nText(loaded.sellerDisclaimerText)
                    }
                }
                val acknowledgeLabel = tr("Ich habe den Hinweis für Verkäufer gelesen und verstanden.")
                acknowledgeBox = noticeHost.checkBox(value = false, label = acknowledgeLabel)
            }
        }
    }

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

    // The listing exists now: it must not be created twice, so the button stays off. The pictures follow; the form stays open for a retry
    // of a failed one. Shared by the LTR and the Euro submit.
    suspend fun onListingCreated(result: AuctionDto) {
        notifySuccess(gettext("Angebot \"%1\" erstellt.", result.title))
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

    // V1.9.320: the Euro (direct payment) listing. No fee, no LTR booking; the notice is acknowledged first when the member has not yet.
    fun submitEuro(
        title: String,
        description: String,
        startingText: String,
        buyNowText: String,
        durationAmount: Int?,
        durationHours: Int?,
        unit: DurationUnit,
    ) {
        fun fail(message: String) {
            errorBox.content = message
            errorBox.show()
        }
        val startingMinor = parseMinorAmountInput(startingText)
        if (!Validation.isNonBlank(title) || !Validation.isNonBlank(description) || startingMinor == null) {
            fail(tr("Bitte Titel, Beschreibung und einen positiven Startpreis (EUR) mit höchstens zwei Nachkommastellen angeben."))
            return
        }
        val range = AuctionCurrencies.MIN_MINOR..AuctionCurrencies.MAX_MINOR
        if (startingMinor !in range) {
            fail(
                gettext(
                    "Der Startpreis muss zwischen %1 und %2 liegen.",
                    formatAuctionAmount(AuctionCurrencies.EUR, null, AuctionCurrencies.MIN_MINOR),
                    formatAuctionAmount(AuctionCurrencies.EUR, null, AuctionCurrencies.MAX_MINOR),
                ),
            )
            return
        }
        if (durationAmount == null || durationHours == null) {
            refreshDurationHint(invalid = true)
            fail(tr("Bitte eine Laufzeit im angegebenen Bereich wählen."))
            return
        }
        refreshDurationHint(invalid = false)
        var buyNowMinor: Long? = null
        if (buyNowText.isNotBlank()) {
            val parsed = parseMinorAmountInput(buyNowText)
            if (parsed == null || parsed !in range) {
                fail(tr("Der Sofortkaufpreis muss, falls angegeben, ein gültiger EUR-Betrag sein."))
                return
            }
            if (parsed <= startingMinor) {
                fail(tr("Der Sofortkaufpreis muss über dem Startpreis liegen."))
                return
            }
            buyNowMinor = parsed
        }
        val offersEnabled = offersBox.value
        if (offersEnabled && buyNowMinor == null) {
            fail(tr("Preisvorschläge sind nur mit einem Sofortkaufpreis möglich."))
            return
        }
        val holder = holderInput.value.orEmpty().trim()
        val iban = ibanInput.value.orEmpty().trim()
        val link = linkInput.value.orEmpty().trim()
        if (holder.length < 2) {
            fail(paymentDetailsErrorText(AuctionPaymentDetailsField.ACCOUNT_HOLDER))
            return
        }
        if (iban.isEmpty() && link.isEmpty()) {
            fail(paymentDetailsErrorText(AuctionPaymentDetailsField.MISSING))
            return
        }
        val current = availability
        if (current == null) {
            fail(tr("Auktionen in Euro sind auf dieser Plattform derzeit nicht verfügbar."))
            return
        }
        val needsAcknowledgment = !current.sellerAcknowledgedCurrentVersion
        if (needsAcknowledgment && acknowledgeBox?.value != true) {
            fail(tr("Bitte bestätigen Sie, dass Sie den Hinweis für Verkäufer gelesen haben."))
            return
        }
        val buyNowSummary = buyNowMinor?.let { gettext(", Sofortkaufpreis %1", formatAuctionAmount(AuctionCurrencies.EUR, null, it)) } ?: ""
        confirmDialog(
            title = tr("Angebot in Euro erstellen"),
            message =
                gettext(
                    "Es wird ein Angebot \"%1\" mit Startpreis %2%3 und %4 Laufzeit erstellt. Es fällt keine Gebühr an. " +
                        "Die Zahlung erfolgt direkt zwischen Ihnen und dem Höchstbietenden; Lapis nimmt kein Geld an.",
                    title,
                    formatAuctionAmount(AuctionCurrencies.EUR, null, startingMinor),
                    buyNowSummary,
                    durationLabel(durationAmount, unit),
                ),
            confirmLabel = tr("Erstellen"),
            dangerNote = tr("Die Zahlungsangaben können nach dem Zuschlag nicht mehr geändert werden."),
        ) {
            submitButton.disabled = true
            AppScope.launch {
                if (needsAcknowledgment) {
                    val acknowledged =
                        guarded {
                            rpcService<IAuctionService>().acknowledgeFiatSellerDisclaimer(
                                AuctionComplianceAcknowledgmentInput(
                                    disclaimerVersion = current.sellerDisclaimerVersion,
                                    disclaimerSha256 = current.sellerDisclaimerSha256,
                                ),
                            )
                        }
                    if (acknowledged == null) {
                        submitButton.disabled = false
                        return@launch
                    }
                    availability = acknowledged
                }
                var invalid: AuctionPaymentDetailsInvalidException? = null
                val result =
                    guarded {
                        try {
                            rpcService<IAuctionService>().createFiatListing(
                                CreateFiatAuctionListingInput(
                                    title = title,
                                    description = description,
                                    currency = AuctionCurrencies.EUR,
                                    startingPriceMinor = startingMinor,
                                    buyNowPriceMinor = buyNowMinor,
                                    durationHours = durationHours,
                                    paymentDetails =
                                        AuctionPaymentDetailsInput(
                                            accountHolder = holder,
                                            iban = iban.ifEmpty { null },
                                            paymentLink = link.ifEmpty { null },
                                        ),
                                    sellerDisclaimerVersion = current.sellerDisclaimerVersion,
                                    sellerDisclaimerSha256 = current.sellerDisclaimerSha256,
                                    offersEnabled = offersEnabled,
                                ),
                            )
                        } catch (e: AuctionPaymentDetailsInvalidException) {
                            invalid = e
                            null
                        }
                    }
                if (result == null) {
                    submitButton.disabled = false
                    invalid?.let { fail(paymentDetailsErrorText(it.field)) }
                    return@launch
                }
                onListingCreated(result)
            }
        }
    }

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

        if (isEuro()) {
            submitEuro(title, description, startingBidText, buyNowText, durationAmount, durationHours, unit)
            return@onClick
        }
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
                onListingCreated(result)
            }
        }
    }
    return FormSnapshot {
        listOf(titleInput, descriptionInput, startingBidInput, buyNowInput, durationInput, holderInput, ibanInput, linkInput)
            .map { it.value.orEmpty() } +
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

/** The fixed text of a refused payment-detail input (Kilua RPC transmits only the exception type and its [AuctionPaymentDetailsField]). */
internal fun paymentDetailsErrorText(field: AuctionPaymentDetailsField): String =
    when (field) {
        AuctionPaymentDetailsField.ACCOUNT_HOLDER -> tr("Bitte einen Kontoinhaber mit 2 bis 70 Zeichen angeben.")
        AuctionPaymentDetailsField.IBAN -> tr("Die IBAN ist ungültig. Bitte eine deutsche IBAN angeben.")
        AuctionPaymentDetailsField.PAYMENT_LINK ->
            tr("Der Zahlungslink muss mit https:// beginnen, darf höchstens 500 Zeichen lang sein und keine Zugangsdaten enthalten.")
        AuctionPaymentDetailsField.MISSING -> tr("Bitte eine IBAN oder einen Zahlungslink angeben.")
    }
