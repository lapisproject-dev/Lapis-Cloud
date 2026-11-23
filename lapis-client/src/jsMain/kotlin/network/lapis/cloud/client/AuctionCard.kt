package network.lapis.cloud.client

import dev.kilua.rpc.types.toDouble
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.image
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.AuctionBidDto
import network.lapis.cloud.shared.domain.AuctionCurrencies
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.rpc.IAuctionService

// V1.9.49: the auction card, the bid/Sofortkauf controls, their confirm dialogs and "Meine Gebote" moved out of `AuctionScreen.kt`
// unchanged (it grew past 900 lines). They only write (placeBid, buyNow, settleAuction) or render what the screen hands them.

// ================================================================================================
// Auction card (shared by the browse list and "Meine Auktionen")
// ================================================================================================

internal fun renderAuctionCard(
    panel: SimplePanel,
    auction: AuctionDto,
    currentMemberId: String?,
    onChanged: () -> Unit,
) {
    val card = panel.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    val headerRow = card.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    // Security audit W6b follow-up round 3 (major finding A): auction title/description are seller-controlled
    // free text rendered as raw widget content -- sanitize before KVision can resolve a forged marker on render.
    headerRow.div(sanitizeUntrustedI18nText(auction.title)) { addCssClasses("flex-grow-1 fw-bold") }
    // V1.9.320: the currency is always named (a second, non-colour channel); a Fiat auction also says how it is paid.
    val isFiat = auction.currency != AuctionCurrencies.LTR
    headerRow.statusBadge(auction.currency, if (isFiat) "dark" else "primary")
    if (isFiat) headerRow.statusBadge(tr("Direktzahlung"), "secondary")
    headerRow.statusBadge(auctionStatusLabel(auction.status), auctionStatusColor(auction.status))
    if (auction.status != auction.effectiveStatus) {
        headerRow.statusBadge(
            gettext("Effektiv: %1", auctionStatusLabel(auction.effectiveStatus)),
            auctionStatusColor(auction.effectiveStatus),
        )
    }

    // V1.9.222: the cover image (gallery position 0); the whole gallery sits behind the "Bilder" disclosure below and is loaded on
    // first
    // open.
    auction.coverImageUrl?.let { url ->
        card.image(url, gettext("Titelbild von %1", sanitizeUntrustedI18nText(auction.title)), className = "lapis-gallery-card-cover") {
            setAttribute("loading", "lazy")
            setAttribute("decoding", "async")
        }
    }
    card.div(sanitizeUntrustedI18nText(auction.description)) { addCssClasses("small") }
    card.div(
        gettext(
            "Verkäufer: %1 · Endet: %2 · Gebote: %3",
            auction.sellerDisplayName,
            formatSystemDateTime(auction.endsAt),
            auction.bidCount,
        ),
    ) {
        addCssClasses("text-muted small")
    }

    val priceRow = card.hPanel(spacing = 16) { addCssClasses("align-items-center flex-wrap") }
    val startCell = priceRow.vPanel(spacing = 2)
    startCell.div(tr("Startpreis")) { addCssClasses("text-muted small") }
    startCell.auctionAmountSpan(auction.currency, auction.startingBidLtr, auction.startingPriceMinor)
    val currentPriceForDisplay = if (isFiat) auction.currentPriceMinor else auction.currentPriceLtr
    if (currentPriceForDisplay != null) {
        val currentCell = priceRow.vPanel(spacing = 2)
        currentCell.div(if (auction.leaderIsMe) tr("Aktueller Preis (Sie führen)") else tr("Aktueller Preis")) {
            addCssClasses("text-muted small")
        }
        currentCell.auctionAmountSpan(auction.currency, auction.currentPriceLtr, auction.currentPriceMinor)
        auction.currentLeaderDisplayName?.let { leader ->
            currentCell.div(if (auction.leaderIsMe) tr("Führend: Sie") else gettext("Führend: %1", leader)) {
                addCssClasses("text-muted small")
            }
        }
    }
    val buyNowPriceForDisplay = if (isFiat) auction.buyNowPriceMinor else auction.buyNowPriceLtr
    if (buyNowPriceForDisplay != null) {
        val buyNowCell = priceRow.vPanel(spacing = 2)
        buyNowCell.div(tr("Sofortkaufpreis")) { addCssClasses("text-muted small") }
        buyNowCell.auctionAmountSpan(auction.currency, auction.buyNowPriceLtr, auction.buyNowPriceMinor)
    }

    if (auction.effectiveStatus == AuctionStatus.SETTLED) {
        card.div(
            gettext(
                "Verkauft an %1 für %2.",
                auction.winnerDisplayName ?: "--",
                formatAuctionAmount(auction.currency, auction.finalPriceLtr, auction.finalPriceMinor),
            ),
        ) { addCssClasses("small") }
    }

    if (isFiat && auction.status in FIAT_AWARDED_STATUSES) {
        card.div(
            gettext(
                "Zuschlag an %1 für %2. Die Bezahlung erfolgt direkt zwischen Verkäufer und Käufer.",
                auction.winnerDisplayName ?: "--",
                formatAuctionAmount(auction.currency, null, auction.finalPriceMinor),
            ),
        ) { addCssClasses("small") }
        if (auction.callerIsSeller || auction.callerIsWinner) renderFiatSettlementPanel(card, auction, onChanged)
    }

    val isSeller = currentMemberId != null && auction.sellerMemberId == currentMemberId
    // The creator can add pictures while the auction is open and has no bid; everybody else sees the gallery only if there is one.
    val galleryEditableByMe = isSeller && auction.effectiveStatus == AuctionStatus.OPEN && auction.bidCount == 0
    if (auction.coverImageUrl != null || galleryEditableByMe) {
        var galleryLoaded = false
        var galleryHost: SimplePanel? = null
        val toggle =
            card.disclosure(tr("Bilder"), bodyId = "lapis-auction-gallery-${auction.id}") { galleryHost = this }
        toggle.onClick {
            if (!galleryLoaded) {
                galleryLoaded = true
                galleryHost?.loadGalleryInto(AttachedImageOwnerType.AUCTION_LISTING, auction.id, auction.title)
            }
        }
    }
    if (!isSeller && auction.effectiveStatus == AuctionStatus.OPEN) {
        renderBidAndBuyNowControls(card, auction, onChanged)
    }

    // Any authenticated member (NOT seller-restricted server-side) may settle -- only
    // rendered/enabled once the auction has ended but the persisted status has not yet lazily
    // flipped (see file KDoc "Confirm-dialog tier" -- no confirm dialog here, deterministic).
    if (auction.status == AuctionStatus.OPEN && auction.effectiveStatus != AuctionStatus.OPEN) {
        val settleRow = card.hPanel(spacing = 8) { addCssClasses("border-top pt-2 mt-1") }
        settleRow.div(tr("Diese Auktion ist beendet, aber noch nicht abgewickelt.")) { addCssClasses("text-muted small flex-grow-1") }
        val settleButton = settleRow.button(tr("Abwickeln"), style = ButtonStyle.OUTLINESECONDARY)
        settleButton.onClick {
            settleButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IAuctionService>().settleAuction(auction.id) }
                settleButton.disabled = false
                if (result != null) {
                    notifySuccess(gettext("Auktion \"%1\" abgewickelt.", result.title))
                    onChanged()
                }
            }
        }
    }
}

/**
 * D5: [placeBid]/[buyNow] each get a small "Wird ausgeführt …" busy-affordance ([busyLabel]) next
 * to their button, in addition to `disabled = true` -- a bare disabled button gives no feedback
 * that real LTR is being committed. D6(c): [placeBid]'s confirm dialog restates the price *as last
 * fetched* and states plainly that the actual evaluation happens against the live price at
 * confirmation time.
 */
private fun renderBidAndBuyNowControls(
    card: SimplePanel,
    auction: AuctionDto,
    onChanged: () -> Unit,
) {
    val controlsPanel = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-1") }
    if (auction.currency != AuctionCurrencies.LTR) {
        renderFiatBidAndBuyNowControls(controlsPanel, auction, onChanged)
        return
    }
    val bidRow = controlsPanel.lapisToolbar()
    val bidInput = bidRow.text(label = tr("Ihr Höchstgebot (LTR)")) { placeholder = "LTR" }
    bidInput.input.setAttribute("inputmode", "decimal")
    val bidButton = bidRow.button(tr("Bieten"), style = ButtonStyle.OUTLINEDANGER)
    val bidBusyLabel = bidRow.div(tr("Wird ausgeführt …")) { addCssClasses("text-muted small") }
    bidBusyLabel.hide()
    val errorBox =
        controlsPanel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    bidButton.onClick {
        errorBox.hide()
        val bidText = bidInput.value.orEmpty().trim()
        val bidParsed = parseLtrAmountInput(bidText)
        if (bidParsed !is AmountInput.Valid) {
            errorBox.content = amountErrorText(bidParsed, tr("Bitte ein positives Höchstgebot (LTR) angeben."))
            errorBox.show()
            return@onClick
        }
        val bidAmount = bidParsed.value
        val startingBid = auction.startingBidLtr ?: return@onClick
        if (bidAmount.toDouble() < startingBid.toDouble()) {
            errorBox.content =
                gettext("Ihr Höchstgebot muss mindestens dem Startpreis (%1) entsprechen.", formatLtr(startingBid))
            errorBox.show()
            return@onClick
        }
        val lastFetchedPriceText =
            auction.currentPriceLtr?.let { gettext("zuletzt abgerufener Preis: %1", formatLtr(it)) }
                ?: gettext("noch keine Gebote, Startpreis: %1", formatLtr(startingBid))
        placeBidConfirmDialog(auction.title, formatLtr(bidAmount), lastFetchedPriceText, reservesLtr = true) {
            bidButton.disabled = true
            bidBusyLabel.show()
            AppScope.launch {
                val result = guarded { rpcService<IAuctionService>().placeBid(auction.id, bidAmount) }
                bidButton.disabled = false
                bidBusyLabel.hide()
                if (result != null) {
                    val leadCopy = if (result.youAreLeader) gettext("Sie führen jetzt.") else gettext("Ein anderes Gebot führt weiterhin.")
                    notifySuccess(
                        gettext(
                            "Gebot angenommen. Aktueller Preis: %1. %2",
                            formatAuctionAmount(AuctionCurrencies.LTR, result.currentPriceLtr, null),
                            leadCopy,
                        ),
                    )
                    bidInput.value = null
                    onChanged()
                }
            }
        }
    }

    val buyNowPrice = auction.buyNowPriceLtr
    val currentPrice = auction.currentPriceLtr
    if (buyNowPrice != null && (currentPrice == null || currentPrice.toDouble() < buyNowPrice.toDouble())) {
        val buyNowRow = controlsPanel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
        val buyNowButton = buyNowRow.button(gettext("Sofort kaufen für %1", formatLtr(buyNowPrice)), style = ButtonStyle.DANGER)
        val buyNowBusyLabel = buyNowRow.div(tr("Wird ausgeführt …")) { addCssClasses("text-muted small") }
        buyNowBusyLabel.hide()
        buyNowButton.onClick {
            buyNowConfirmDialog(auction.title, formatLtr(buyNowPrice), fiat = false) {
                buyNowButton.disabled = true
                buyNowBusyLabel.show()
                AppScope.launch {
                    val result = guarded { rpcService<IAuctionService>().buyNow(auction.id) }
                    buyNowButton.disabled = false
                    buyNowBusyLabel.hide()
                    if (result != null) {
                        val finalPrice = result.finalPriceLtr ?: buyNowPrice
                        notifySuccess(gettext("Sofortkauf abgeschlossen: \"%1\" für %2.", result.title, formatLtr(finalPrice)))
                        onChanged()
                    }
                }
            }
        }
    }
}

/** Tier 2 "Endgültig" (D4): bespoke modal, matches `LtrLedgerScreen.peerTransferConfirmDialog`'s
 * irreversibility-bar styling. D6(c): restates the price as last fetched and states plainly that
 * the bid is evaluated against the live price at confirmation time, not the one shown here. */
private fun placeBidConfirmDialog(
    auctionTitle: String,
    maxBidText: String,
    lastFetchedPriceText: String,
    reservesLtr: Boolean,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = tr("Gebot bestätigen"))
    if (reservesLtr) {
        modal.div(tr("Ihr Höchstgebot ist verbindlich und reserviert LTR aus Ihrem freien Guthaben.")) {
            addCssClasses("fw-bold text-danger")
        }
    } else {
        // V1.9.320: a Fiat bid reserves nothing; the contract and the payment run directly between the two people.
        modal.div(tr("Ihr Höchstgebot ist verbindlich. Bei Zuschlag bezahlen Sie den Betrag direkt an den Verkäufer.")) {
            addCssClasses("fw-bold text-danger")
        }
        modal.div(tr("Lapis nimmt kein Geld an, hält keines und vermittelt bei Streit nicht. Es gibt keinen Käuferschutz."))
    }
    modal.div(
        gettext(
            "Sie bieten %1 auf \"%2\" (%3). Ihr Gebot wird gegen den " +
                "aktuellen Preis zum Zeitpunkt der Bestätigung ausgewertet, nicht den hier angezeigten -- der Preis kann " +
                "sich seit dem letzten Abruf geändert haben.",
            maxBidText,
            auctionTitle,
            lastFetchedPriceText,
        ),
    )
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Gebot abgeben"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

/** Tier 2 "Endgültig" (D4): bespoke modal, same shape as [placeBidConfirmDialog]. */
private fun buyNowConfirmDialog(
    auctionTitle: String,
    buyNowPriceText: String,
    fiat: Boolean,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = tr("Sofortkauf bestätigen"))
    modal.div(tr("Sofortkauf ist verbindlich -- kann nicht rückgängig gemacht werden.")) { addCssClasses("fw-bold text-danger") }
    modal.div(gettext("Sie kaufen \"%1\" sofort für %2.", auctionTitle, buyNowPriceText))
    if (fiat) {
        modal.div(tr("Sie bezahlen den Betrag danach direkt an den Verkäufer. Lapis nimmt kein Geld an und vermittelt bei Streit nicht."))
    }
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Sofort kaufen"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

// ================================================================================================
// Meine Gebote
// ================================================================================================

internal fun renderMyBidsTable(
    panel: SimplePanel,
    bids: List<AuctionBidDto>,
    viewport: NarrowViewportSource = BrowserNarrowViewport,
) {
    // W5: was a hand-built pseudo-table (header row + one `hPanel` row per bid with fixed column widths); now a real table
    // (`dataTable`, card list on a narrow screen). Same rows in the same order, same cell texts (PseudoTableGoldenDomTest).
    panel.plainDataTable(
        columns =
            listOf(
                textColumn<AuctionBidDto>(title = tr("Auktion"), primary = true) { it.auctionTitle },
                DataColumn(
                    title = tr("Ihr Höchstgebot"),
                    numeric = true,
                    cell = { cell, bid -> cell.auctionAmountSpan(bid.currency, bid.maxBidLtr, bid.maxBidMinor) },
                ),
                // Security audit W6b, round 7 (major finding 2): trusted(...) keeps both branches live-translatable
                // instead of losing their marker to textColumn's unconditional untrusted-text sanitizer.
                textColumn<AuctionBidDto>(title = tr("Führend")) {
                    if (it.isCurrentLeader) trusted(tr("Ja")) else trusted(tr("Nein"))
                },
                DataColumn(
                    title = tr("Status"),
                    cell = { cell, bid -> cell.statusBadge(auctionStatusLabel(bid.auctionStatus), auctionStatusColor(bid.auctionStatus)) },
                ),
                systemDateTimeColumn<AuctionBidDto>(
                    title = tr("Abgegeben"),
                    numeric = false,
                    cssClasses = "text-muted small",
                ) { it.createdAt },
            ),
        rows = bids,
        viewport = viewport,
        label = gettext("Meine Gebote"),
    )
}

/** The statuses of an awarded Fiat auction (the settlement panel is shown to the seller and the winner). */
internal val FIAT_AWARDED_STATUSES = setOf(AuctionStatus.AWAITING_PAYMENT, AuctionStatus.PAID, AuctionStatus.COMPLETED)

/**
 * V1.9.320: bid and Sofortkauf controls of a FIAT (EUR) auction. Same shape as the LTR controls above, but the amounts are minor units
 * ([parseMinorAmountInput]) and nothing is reserved: the dialogs say that the money is paid directly to the seller after the award.
 */
private fun renderFiatBidAndBuyNowControls(
    controlsPanel: SimplePanel,
    auction: AuctionDto,
    onChanged: () -> Unit,
) {
    val bidRow = controlsPanel.lapisToolbar()
    val bidInput = bidRow.text(label = gettext("Ihr Höchstgebot (%1)", auction.currency)) { placeholder = auction.currency }
    bidInput.input.setAttribute("inputmode", "decimal")
    val bidButton = bidRow.button(tr("Bieten"), style = ButtonStyle.OUTLINEDANGER)
    val bidBusyLabel = bidRow.div(tr("Wird ausgeführt …")) { addCssClasses("text-muted small") }
    bidBusyLabel.hide()
    val errorBox =
        controlsPanel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val startingMinor = auction.startingPriceMinor ?: return

    bidButton.onClick {
        errorBox.hide()
        val bidMinor = parseMinorAmountInput(bidInput.value.orEmpty())
        if (bidMinor == null || bidMinor < AuctionCurrencies.MIN_MINOR || bidMinor > AuctionCurrencies.MAX_MINOR) {
            errorBox.content = tr("Bitte einen gültigen Betrag mit höchstens zwei Nachkommastellen angeben.")
            errorBox.show()
            return@onClick
        }
        if (bidMinor < startingMinor) {
            errorBox.content =
                gettext(
                    "Ihr Höchstgebot muss mindestens dem Startpreis (%1) entsprechen.",
                    formatAuctionAmount(auction.currency, null, startingMinor),
                )
            errorBox.show()
            return@onClick
        }
        val lastFetchedPriceText =
            auction.currentPriceMinor?.let { gettext("zuletzt abgerufener Preis: %1", formatAuctionAmount(auction.currency, null, it)) }
                ?: gettext("noch keine Gebote, Startpreis: %1", formatAuctionAmount(auction.currency, null, startingMinor))
        placeBidConfirmDialog(
            auction.title,
            formatAuctionAmount(auction.currency, null, bidMinor),
            lastFetchedPriceText,
            reservesLtr = false,
        ) {
            bidButton.disabled = true
            bidBusyLabel.show()
            AppScope.launch {
                val result = guarded { rpcService<IAuctionService>().placeFiatBid(auction.id, bidMinor) }
                bidButton.disabled = false
                bidBusyLabel.hide()
                if (result != null) {
                    val leadCopy = if (result.youAreLeader) gettext("Sie führen jetzt.") else gettext("Ein anderes Gebot führt weiterhin.")
                    notifySuccess(
                        gettext(
                            "Gebot angenommen. Aktueller Preis: %1. %2",
                            formatAuctionAmount(result.currency, null, result.currentPriceMinor),
                            leadCopy,
                        ),
                    )
                    bidInput.value = null
                    onChanged()
                }
            }
        }
    }

    val buyNowMinor = auction.buyNowPriceMinor
    val currentMinor = auction.currentPriceMinor
    if (buyNowMinor != null && (currentMinor == null || currentMinor < buyNowMinor)) {
        val buyNowRow = controlsPanel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
        val buyNowText = formatAuctionAmount(auction.currency, null, buyNowMinor)
        val buyNowButton = buyNowRow.button(gettext("Sofort kaufen für %1", buyNowText), style = ButtonStyle.DANGER)
        val buyNowBusyLabel = buyNowRow.div(tr("Wird ausgeführt …")) { addCssClasses("text-muted small") }
        buyNowBusyLabel.hide()
        buyNowButton.onClick {
            buyNowConfirmDialog(auction.title, buyNowText, fiat = true) {
                buyNowButton.disabled = true
                buyNowBusyLabel.show()
                AppScope.launch {
                    val result = guarded { rpcService<IAuctionService>().buyNow(auction.id) }
                    buyNowButton.disabled = false
                    buyNowBusyLabel.hide()
                    if (result != null) {
                        notifySuccess(
                            gettext(
                                "Sofortkauf abgeschlossen: \"%1\" für %2.",
                                result.title,
                                formatAuctionAmount(result.currency, null, result.finalPriceMinor ?: buyNowMinor),
                            ),
                        )
                        onChanged()
                    }
                }
            }
        }
    }
}
