package network.lapis.cloud.client

import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AuctionCurrencies
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionOfferDto
import network.lapis.cloud.shared.domain.AuctionOfferStatus
import network.lapis.cloud.shared.rpc.IAuctionService

// V1.9.321 (Fiat wave b "Preisvorschlag"): the buyer's offer dialog and status, the seller's list of proposals, "Meine Preisvorschläge".
// The word "Angebot" is not used for a price offer (it is the old word for a listing). Nothing here is a free-text channel: an offer is an amount.
// Confirm tiers: sending a proposal binds nobody (no second confirmation); declining/withdrawing is tier 1; ACCEPTING ends the auction (tier 2).

/** Label of one offer's EFFECTIVE status, in words (never colour alone). */
internal fun auctionOfferStatusLabel(offer: AuctionOfferDto): String =
    when (offer.status) {
        AuctionOfferStatus.OFFERED -> if (offer.fromSeller) tr("Gegenvorschlag, offen") else tr("Offen")
        AuctionOfferStatus.ACCEPTED -> tr("Angenommen")
        AuctionOfferStatus.DECLINED -> tr("Abgelehnt")
        AuctionOfferStatus.COUNTERED -> tr("Gegenvorschlag")
        AuctionOfferStatus.EXPIRED -> if (offer.endedByAuction) tr("Auktion beendet") else tr("Abgelaufen")
        AuctionOfferStatus.WITHDRAWN -> tr("Zurückgezogen")
    }

internal fun auctionOfferStatusColor(status: AuctionOfferStatus): String =
    when (status) {
        AuctionOfferStatus.OFFERED -> "primary"
        AuctionOfferStatus.ACCEPTED -> "success"
        AuctionOfferStatus.DECLINED -> "danger"
        AuctionOfferStatus.COUNTERED -> "info"
        AuctionOfferStatus.EXPIRED, AuctionOfferStatus.WITHDRAWN -> "secondary"
    }

/** The lowest amount a NEW proposal may have, as the screen last saw the auction (the server decides again). */
private fun lowestProposalMinor(auction: AuctionDto): Long? {
    val current = auction.currentPriceMinor
    return if (current != null) current + AuctionCurrencies.MIN_INCREMENT_MINOR else auction.startingPriceMinor
}

/**
 * The amount dialog of a proposal ([counterOf] == `null`) or a counter-offer. No second confirmation: sending binds nobody. The hint says so, and
 * that the proposal is valid at most until the auction ends.
 */
private fun offerAmountDialog(
    auction: AuctionDto,
    counterOf: AuctionOfferDto?,
    onDone: () -> Unit,
) {
    val buyNow = auction.buyNowPriceMinor ?: return
    val lowest =
        if (counterOf != null) {
            maxOf(counterOf.amountMinor + 1, auction.currentPriceMinor ?: 0L)
        } else {
            lowestProposalMinor(auction) ?: return
        }
    val modal = Modal(caption = if (counterOf == null) tr("Preis vorschlagen") else tr("Gegenvorschlag machen"))
    modal.div(sanitizeUntrustedI18nText(auction.title)) { addCssClasses("fw-bold") }
    modal.div(
        tr(
            "Ein Preisvorschlag bindet Sie erst, wenn der Verkäufer ihn annimmt. Er gilt höchstens bis zum Auktionsende. " +
                "Ihre Gebote und der Sofortkauf bleiben davon unberührt.",
        ),
    ) { addCssClasses("small") }
    if (counterOf != null) {
        modal.div(tr("Ein Gegenvorschlag bindet Sie erst, wenn der Käufer ihn annimmt. Es ist nur ein Gegenvorschlag möglich.")) {
            addCssClasses("small")
        }
    }
    val input = modal.text(label = gettext("Ihr Preis (%1)", auction.currency)) { placeholder = auction.currency }
    input.input.setAttribute("inputmode", "decimal")
    modal.div(
        gettext(
            "Erlaubt: ab %1 bis unter %2.",
            formatAuctionAmount(auction.currency, null, lowest),
            formatAuctionAmount(auction.currency, null, buyNow),
        ),
    ) { addCssClasses("text-muted small") }
    val errorBox =
        modal.div().apply {
            addCssClass("text-danger")
            hide()
        }
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    val send = newActionButton(ActionIcon.SEND, tr("Vorschlag senden"), ButtonStyle.PRIMARY)
    send.onClick {
        errorBox.hide()
        val minor = parseMinorAmountInput(input.value.orEmpty())
        if (minor == null || minor < lowest || minor >= buyNow) {
            errorBox.content =
                gettext(
                    "Bitte einen Betrag ab %1 und unter %2 angeben.",
                    formatAuctionAmount(auction.currency, null, lowest),
                    formatAuctionAmount(auction.currency, null, buyNow),
                )
            errorBox.show()
            return@onClick
        }
        send.disabled = true
        AppScope.launch {
            val result =
                guarded {
                    if (counterOf == null) {
                        rpcService<IAuctionService>().makeOffer(auction.id, minor)
                    } else {
                        rpcService<IAuctionService>().counterOffer(counterOf.id, minor)
                    }
                }
            send.disabled = false
            if (result != null) {
                modal.hide()
                notifySuccess(if (counterOf == null) tr("Preisvorschlag gesendet.") else tr("Gegenvorschlag gesendet."))
                onDone()
            }
        }
    }
    modal.addButton(send)
    modal.show()
}

/** Tier 2: accepting ends the auction. The wording differs for the seller (money arrives) and the buyer (money is owed). */
private fun acceptOfferDialog(
    auction: AuctionDto,
    offer: AuctionOfferDto,
    asSeller: Boolean,
    onDone: () -> Unit,
) {
    val amountText = formatAuctionAmount(offer.currency, null, offer.amountMinor)
    confirmDialog(
        title = tr("Preisvorschlag annehmen"),
        message =
            if (asSeller) {
                gettext(
                    "Mit der Annahme endet die Auktion. %1 bezahlt %2 direkt an Sie. Lapis nimmt kein Geld an und vermittelt bei Streit nicht.",
                    sanitizeUntrustedI18nText(offer.buyerDisplayName ?: "--"),
                    amountText,
                )
            } else {
                gettext(
                    "Mit der Annahme endet die Auktion. Sie bezahlen %1 direkt an den Verkäufer. Das ist verbindlich. " +
                        "Es gibt keinen Käuferschutz.",
                    amountText,
                )
            },
        confirmLabel = tr("Annehmen"),
        confirmIcon = ActionIcon.APPROVE,
        confirmStyle = ButtonStyle.DANGER,
        dangerNote = tr("Das kann nicht rückgängig gemacht werden."),
    ) {
        AppScope.launch {
            val result = guarded { rpcService<IAuctionService>().acceptOffer(offer.id) }
            if (result != null) {
                notifySuccess(gettext("Preisvorschlag angenommen: \"%1\" für %2.", auction.title, amountText))
                onDone()
            }
        }
    }
}

private fun declineOfferDialog(
    offer: AuctionOfferDto,
    onDone: () -> Unit,
) {
    confirmDialog(
        title = tr("Preisvorschlag ablehnen"),
        message = tr("Möchten Sie diesen Preisvorschlag ablehnen?"),
        confirmLabel = tr("Ablehnen"),
        confirmIcon = ActionIcon.REJECT,
        confirmStyle = ButtonStyle.PRIMARY,
    ) {
        AppScope.launch {
            if (guarded { rpcService<IAuctionService>().declineOffer(offer.id) } != null) {
                notifySuccess(tr("Preisvorschlag abgelehnt."))
                onDone()
            }
        }
    }
}

private fun withdrawOffer(
    offer: AuctionOfferDto,
    onDone: () -> Unit,
) {
    confirmDialog(
        title = tr("Preisvorschlag zurückziehen"),
        message = tr("Möchten Sie diesen Preisvorschlag zurückziehen?"),
        confirmLabel = tr("Zurückziehen"),
        confirmIcon = ActionIcon.UNDO,
        confirmStyle = ButtonStyle.PRIMARY,
    ) {
        AppScope.launch {
            if (guarded { rpcService<IAuctionService>().withdrawOffer(offer.id) } != null) {
                notifySuccess(tr("Preisvorschlag zurückgezogen."))
                onDone()
            }
        }
    }
}

/** One line of an offer with its buttons (only the ones the server says the caller may use). [asSeller]: the caller is the seller of the auction. */
private fun renderOfferRow(
    parent: SimplePanel,
    auction: AuctionDto,
    offer: AuctionOfferDto,
    asSeller: Boolean,
    onDone: () -> Unit,
) {
    val row = parent.vPanel(spacing = 2) { addCssClasses("border rounded p-2") }
    val head = row.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val who =
        when {
            asSeller && !offer.fromSeller -> sanitizeUntrustedI18nText(offer.buyerDisplayName ?: "--")
            asSeller -> tr("Ihr Gegenvorschlag")
            offer.fromSeller -> tr("Gegenvorschlag des Verkäufers")
            else -> tr("Ihr Preisvorschlag")
        }
    head.div(who) { addCssClasses("fw-bold") }
    head.div(formatAuctionAmount(offer.currency, null, offer.amountMinor)) { addCssClasses("flex-grow-1") }
    head.statusBadge(auctionOfferStatusLabel(offer), auctionOfferStatusColor(offer.status))
    if (offer.status == AuctionOfferStatus.OFFERED) {
        row.div(gettext("Gilt bis %1", formatSystemDateTime(offer.expiresAt))) { addCssClasses("text-muted small") }
    }
    if (offer.belowCurrentPrice && offer.status == AuctionOfferStatus.OFFERED) {
        row.div(tr("Unter dem aktuellen Gebot")) { addCssClasses("small text-danger") }
    }
    val actions = row.hPanel(spacing = 6) { addCssClasses("flex-wrap") }
    if (offer.callerCanAccept) {
        actions.actionButton(ActionIcon.APPROVE, tr("Annehmen"), ButtonStyle.OUTLINEPRIMARY, small = true) {
            onClick { acceptOfferDialog(auction, offer, asSeller, onDone) }
        }
    }
    if (offer.callerCanCounter) {
        actions.actionButton(ActionIcon.SEND, tr("Gegenvorschlag"), ButtonStyle.OUTLINESECONDARY, small = true) {
            onClick { offerAmountDialog(auction, counterOf = offer, onDone = onDone) }
        }
    }
    if (offer.callerCanDecline) {
        actions.actionButton(ActionIcon.REJECT, tr("Ablehnen"), ButtonStyle.OUTLINESECONDARY, small = true) {
            onClick { declineOfferDialog(offer, onDone) }
        }
    }
    if (offer.callerCanWithdraw) {
        actions.actionButton(ActionIcon.UNDO, tr("Zurückziehen"), ButtonStyle.OUTLINESECONDARY, small = true) {
            onClick { withdrawOffer(offer, onDone) }
        }
    }
}

/** The buyer's side of an open Fiat auction with price offers: the button, or the block text, plus his own current negotiation. */
internal fun renderBuyerOfferControls(
    card: SimplePanel,
    auction: AuctionDto,
    onChanged: () -> Unit,
) {
    val host = card.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-1") }
    if (auction.callerOfferBlocked) {
        host.div(tr("Sie können auf diese Auktion keine weiteren Preisvorschläge machen.")) { addCssClasses("text-muted small") }
    } else if (!auction.callerHasOpenOffer && auction.buyNowPriceMinor != null) {
        host.actionButton(ActionIcon.SEND, tr("Preis vorschlagen"), ButtonStyle.OUTLINEPRIMARY) {
            onClick { offerAmountDialog(auction, counterOf = null, onDone = onChanged) }
        }
    }
    if (auction.callerHasOpenOffer) {
        val offersHost = host.vPanel(spacing = 4)
        AppScope.launch {
            val mine = guarded { rpcService<IAuctionService>().listMyOffers() } ?: return@launch
            mine
                .filter { it.auctionId == auction.id && it.status == AuctionOfferStatus.OFFERED }
                .forEach { renderOfferRow(offersHost, auction, it, asSeller = false, onDone = onChanged) }
        }
    }
}

/** The seller's list of proposals of his own auction (loaded when the disclosure is opened the first time). */
internal fun renderSellerOffersPanel(
    card: SimplePanel,
    auction: AuctionDto,
    onChanged: () -> Unit,
) {
    val count = auction.openOfferCountForSeller ?: 0
    val host = card.vPanel(spacing = 4) { addCssClasses("border-top pt-2 mt-1") }
    host.statusBadge(gettext("Offene Preisvorschläge: %1", count), if (count > 0) "warning" else "secondary")
    var loaded = false
    var listHost: SimplePanel? = null
    val toggle = host.disclosure(tr("Preisvorschläge anzeigen"), bodyId = "lapis-auction-offers-${auction.id}") { listHost = this }
    toggle.onClick {
        if (loaded) return@onClick
        loaded = true
        AppScope.launch {
            val offers = guarded { rpcService<IAuctionService>().listOffersForAuction(auction.id) }
            val target = listHost ?: return@launch
            if (offers == null) {
                loaded = false
                return@launch
            }
            target.removeAll()
            if (offers.isEmpty()) {
                target.div(tr("Noch keine Preisvorschläge.")) { addCssClasses("text-muted small") }
            } else {
                offers.forEach { renderOfferRow(target, auction, it, asSeller = true, onDone = onChanged) }
            }
        }
    }
}

/** "Meine Preisvorschläge": the caller's own proposals and the counter-offers addressed to him. */
internal fun renderMyOffersTable(
    panel: SimplePanel,
    offers: List<AuctionOfferDto>,
    viewport: NarrowViewportSource = BrowserNarrowViewport,
) {
    panel.plainDataTable(
        columns =
            listOf(
                textColumn<AuctionOfferDto>(title = tr("Auktion"), primary = true) { it.auctionTitle },
                DataColumn(
                    title = tr("Preis"),
                    numeric = true,
                    cell = { cell, offer -> cell.auctionAmountSpan(offer.currency, null, offer.amountMinor) },
                ),
                textColumn<AuctionOfferDto>(title = tr("Von")) {
                    if (it.fromSeller) trusted(tr("Verkäufer")) else trusted(tr("Sie"))
                },
                DataColumn(
                    title = tr("Status"),
                    cell = { cell, offer -> cell.statusBadge(auctionOfferStatusLabel(offer), auctionOfferStatusColor(offer.status)) },
                ),
                systemDateTimeColumn<AuctionOfferDto>(
                    title = tr("Gilt bis"),
                    numeric = false,
                    cssClasses = "text-muted small",
                ) { it.expiresAt },
            ),
        rows = offers,
        viewport = viewport,
        label = gettext("Meine Preisvorschläge"),
    )
}
