package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionPaymentDetailsDto
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.rpc.IAuctionService

/**
 * V1.9.320 -- the settlement panel of an AWARDED Fiat (EUR) auction, shown ONLY to the seller and the winner ([AuctionDto.callerIsSeller]/
 * [AuctionDto.callerIsWinner]). Lapis does not move the money: the panel shows the progress in three steps (award, payment confirmed by the
 * seller, goods confirmed by the winner), lets the winner open the seller's payment details, and lets each party give its own one-shot
 * confirmation.
 *
 * **Payment details are never cached and never logged.** They are fetched on an explicit click ([IAuctionService.getAuctionPaymentDetails]),
 * rendered as untrusted text (they are seller-typed) and live only in this panel's DOM. The payment link is opened ONLY when it still begins
 * with `https://` on this side as well (defence in depth next to the server check), in a new tab with `rel="noopener noreferrer"`, with its
 * host named next to the button. A dispute notice is always visible: Lapis arbitrates nothing and gives no buyer protection.
 */
internal fun renderFiatSettlementPanel(
    card: SimplePanel,
    auction: AuctionDto,
    onChanged: () -> Unit,
) {
    val panel = card.vPanel(spacing = 6) { addCssClasses("border rounded p-2 mt-1") }
    panel.div(tr("Abwicklung der Zahlung")) { addCssClasses("fw-bold") }

    // Progress in three steps; every step says its state in words, not only by a symbol or a colour.
    val steps = panel.vPanel(spacing = 2)
    stepRow(
        steps,
        done = true,
        text = gettext("Zuschlag erteilt am %1", auction.settledAt?.let { formatSystemDateTime(it) } ?: "--"),
    )
    stepRow(
        steps,
        done = auction.paymentConfirmedAt != null,
        text =
            auction.paymentConfirmedAt?.let { gettext("Zahlungseingang vom Verkäufer bestätigt am %1", formatSystemDateTime(it)) }
                ?: tr("Zahlungseingang noch nicht bestätigt"),
    )
    stepRow(
        steps,
        done = auction.deliveryConfirmedAt != null,
        text =
            auction.deliveryConfirmedAt?.let { gettext("Wareneingang vom Käufer bestätigt am %1", formatSystemDateTime(it)) }
                ?: tr("Wareneingang noch nicht bestätigt"),
    )
    if (auction.status == AuctionStatus.COMPLETED) {
        panel.div(tr("Diese Auktion ist vollständig abgewickelt.")) { addCssClasses("small text-success") }
    }

    if (auction.callerIsWinner && auction.status != AuctionStatus.COMPLETED) renderPaymentDetailsBlock(panel, auction)

    val actions = panel.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    if (auction.callerIsSeller && auction.paymentConfirmedAt == null) {
        actions.actionButton(ActionIcon.APPROVE, tr("Zahlungseingang bestätigen"), ButtonStyle.OUTLINEPRIMARY) {
            onClick {
                confirmDialog(
                    title = tr("Zahlungseingang bestätigen"),
                    message = tr("Bestätigen Sie nur, wenn das Geld tatsächlich bei Ihnen eingegangen ist."),
                    confirmLabel = tr("Bestätigen"),
                    confirmIcon = ActionIcon.APPROVE,
                    confirmStyle = ButtonStyle.PRIMARY,
                    dangerNote = tr("Diese Bestätigung ist endgültig."),
                ) {
                    runGuardedAction(this) {
                        val result = guarded { rpcService<IAuctionService>().confirmPaymentReceived(auction.id) }
                        if (result != null) {
                            notifySuccess(tr("Zahlungseingang bestätigt."))
                            onChanged()
                        }
                    }
                }
            }
        }
    }
    if (auction.callerIsWinner && auction.deliveryConfirmedAt == null) {
        actions.actionButton(ActionIcon.APPROVE, tr("Wareneingang bestätigen"), ButtonStyle.OUTLINEPRIMARY) {
            onClick {
                confirmDialog(
                    title = tr("Wareneingang bestätigen"),
                    message = tr("Bestätigen Sie nur, wenn die Ware bei Ihnen angekommen ist."),
                    confirmLabel = tr("Bestätigen"),
                    confirmIcon = ActionIcon.APPROVE,
                    confirmStyle = ButtonStyle.PRIMARY,
                    dangerNote = tr("Diese Bestätigung ist endgültig."),
                ) {
                    runGuardedAction(this) {
                        val result = guarded { rpcService<IAuctionService>().confirmDeliveryReceived(auction.id) }
                        if (result != null) {
                            notifySuccess(tr("Wareneingang bestätigt."))
                            onChanged()
                        }
                    }
                }
            }
        }
    }

    panel.div(
        tr(
            "Lapis nimmt kein Geld an, hält keines und vermittelt bei Streit nicht. Es gibt keinen Käuferschutz. " +
                "Klären Sie Zahlungsprobleme direkt mit der Gegenseite.",
        ),
    ) { addCssClasses("text-muted small") }
}

private fun stepRow(
    parent: SimplePanel,
    done: Boolean,
    text: String,
) {
    val row = parent.hPanel(spacing = 6) { addCssClasses("align-items-baseline") }
    row.div(if (done) "✓" else "○") {
        addCssClasses(if (done) "text-success fw-bold" else "text-muted")
        setAttribute("aria-hidden", "true")
    }
    row.div(text) { addCssClasses("small") }
}

/** The winner's block: a button that fetches the payment details on demand, then the details with copy buttons and the checked link. */
private fun renderPaymentDetailsBlock(
    panel: SimplePanel,
    auction: AuctionDto,
) {
    val host = panel.vPanel(spacing = 4)
    val loadRow = host.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val detailsHost = host.vPanel(spacing = 4)
    loadRow.actionButton(ActionIcon.VIEW, tr("Zahlungsangaben anzeigen"), ButtonStyle.OUTLINEPRIMARY) {
        onClick {
            runGuardedAction(this) {
                val details = guarded { rpcService<IAuctionService>().getAuctionPaymentDetails(auction.id) }
                if (details != null) {
                    detailsHost.removeAll()
                    renderPaymentDetails(detailsHost, details)
                }
            }
        }
    }
}

private fun renderPaymentDetails(
    host: SimplePanel,
    details: AuctionPaymentDetailsDto,
) {
    host.div(
        gettext("Zu zahlender Betrag: %1", formatAuctionAmount(details.currency, null, details.amountMinor)),
    ) { addCssClasses("fw-bold") }
    host.div(tr("Empfänger")) { addCssClasses("text-muted small") }
    host.untrustedDiv(details.accountHolder)
    details.ibanFormatted?.let { iban ->
        host.div(tr("IBAN")) { addCssClasses("text-muted small") }
        val ibanRow = host.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        ibanRow.untrustedDiv(iban, className = "font-monospace")
        ibanRow.actionButton(
            ActionIcon.COPY,
            tr("IBAN kopieren"),
            small = true,
        ) { onClick { copyToClipboard(iban.filterNot { it == ' ' }) } }
    }
    host.div(tr("Verwendungszweck")) { addCssClasses("text-muted small") }
    val referenceRow = host.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    referenceRow.untrustedDiv(details.reference, className = "font-monospace")
    referenceRow.actionButton(
        ActionIcon.COPY,
        tr("Verwendungszweck kopieren"),
        small = true,
    ) { onClick { copyToClipboard(details.reference) } }
    val link = details.paymentLink
    // Defence in depth: the server accepted only https links, and this side opens only an https link as well.
    if (link != null && link.startsWith("https://")) {
        host.div(tr("Zahlungslink")) { addCssClasses("text-muted small") }
        val linkRow = host.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        // actionLink sets target=_blank + rel=noopener; noreferrer is added on top (the seller's page must not learn the referring screen).
        val anchor = linkRow.actionLink(ActionIcon.OPEN_EXTERNAL, tr("Link öffnen"), link, target = "_blank")
        anchor.setAttribute("rel", "noopener noreferrer")
        details.paymentLinkHost?.let { linkHost ->
            linkRow.div(gettext("Führt zu: %1", linkHost)) { addCssClasses("fw-bold small") }
        }
    }
}

private fun copyToClipboard(text: String) {
    val clipboard: dynamic = window.navigator.asDynamic().clipboard
    if (clipboard != null && clipboard != undefined) clipboard.writeText(text)
}
