package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.CreateAuctionListingInput
import network.lapis.cloud.shared.rpc.IAuctionService

/**
 * V1.9.49 (R36B): the listing form of the auction screen, built into the host of a collapsible create form ([collapsibleCreateForm])
 * behind the page header button "Neues Angebot". It only writes ([IAuctionService.createListing]); every read stays in `AuctionScreen.kt`.
 *
 * D3 (unchanged): the member's own LTR balance comes first, before any input, and the flat listing fee is stated right under it -- both
 * now live inside the opened form, not on the closed page. The Tier 1 [confirmDialog] before the fee is booked is unchanged. The
 * fingerprint covers all five inputs (loose widgets, no `LapisForm`).
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
    // durationHours is server-bounded (1..2160h); deliberately not duplicated as a client-facing
    // constant, per CreateAuctionListingInput's own KDoc -- a loose "z. B. 24" placeholder hint
    // only, same posture Money.kt/Validation.kt already take toward every other server-owned bound.
    val durationInput = panel.text(label = tr("Laufzeit in Stunden (z. B. 24)"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val buttons = panel.hPanel(spacing = 8)
    val submitButton = newActionButton(ActionIcon.ADD, tr("Angebot erstellen"), ButtonStyle.PRIMARY)
    buttons.add(collapseCancelButton(collapse))
    buttons.add(submitButton)

    submitButton.onClick {
        errorBox.hide()
        val title = titleInput.value.orEmpty().trim()
        val description = descriptionInput.value.orEmpty().trim()
        val startingBidText = startingBidInput.value.orEmpty().trim()
        val buyNowText = buyNowInput.value.orEmpty().trim()
        val durationText = durationInput.value.orEmpty().trim()
        val durationHours = durationText.toIntOrNull()

        if (!Validation.isNonBlank(title) || !Validation.isNonBlank(description) || !Validation.isPositiveDecimal(startingBidText)) {
            errorBox.content = tr("Bitte Titel, Beschreibung und einen positiven Startpreis (LTR) angeben.")
            errorBox.show()
            return@onClick
        }
        if (durationHours == null || durationHours <= 0) {
            errorBox.content = tr("Bitte eine Laufzeit in ganzen Stunden (größer als 0) angeben.")
            errorBox.show()
            return@onClick
        }
        val startingBid = startingBidText.toDouble().toDecimal()
        var buyNowPrice: Decimal? = null
        if (buyNowText.isNotBlank()) {
            if (!Validation.isPositiveDecimal(buyNowText)) {
                errorBox.content = tr("Der Sofortkaufpreis muss, falls angegeben, ein positiver LTR-Betrag sein.")
                errorBox.show()
                return@onClick
            }
            val parsed = buyNowText.toDouble().toDecimal()
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
                    "Es wird ein Angebot \"%1\" mit Startpreis %2%3 und %4 Stunden Laufzeit erstellt. Dabei wird eine feste " +
                        "Gebühr von %5 aus Ihrem freien LTR-Guthaben gebucht.",
                    title,
                    formatLtr(startingBid),
                    buyNowSummary,
                    durationHours,
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
                submitButton.disabled = false
                if (result != null) {
                    notifySuccess(gettext("Angebot \"%1\" erstellt.", result.title))
                    collapse(true)
                    onCompleted()
                }
            }
        }
    }
    return FormSnapshot {
        listOf(titleInput, descriptionInput, startingBidInput, buyNowInput, durationInput).map { it.value.orEmpty() }
    }
}
