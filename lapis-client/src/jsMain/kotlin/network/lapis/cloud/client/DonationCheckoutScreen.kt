package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.p
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.DonationCheckoutInput
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.rpc.IPaymentGatewayService

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- `/donate`, any authenticated member.
 * Amount field (validated client-side: > 0, ≤ 2 decimals, ≤ the configured maximum reported by
 * `getPaymentGatewayAvailability`'s sibling gate -- the actual ceiling itself is only known
 * server-side, so this screen validates shape only and lets a too-large amount surface as the
 * server's own `BadRequestException`, same "cheap client-side check, server is the real authority"
 * posture every other form in this client follows), optional purpose, and -- when
 * `PaymentGatewayAvailabilityDto.donorCategoryRequired` -- a mandatory [DonorCategory] select with
 * the §25 PartG explanation.
 */
fun renderDonationCheckoutScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 560.px
            marginTop = 24.px
        }
    root.h1(tr("Spenden"))

    val statusHost = root.vPanel(spacing = 8)
    val formHost = root.vPanel(spacing = 8)

    AppScope.launch {
        val availability = pspProbe { rpcService<IPaymentGatewayService>().getPaymentGatewayAvailability() }
        if (availability == null || !availability.donationCheckoutAvailable) {
            statusHost.p(tr("Online-Spenden sind für diese Organisation aktuell nicht möglich."))
            return@launch
        }
        renderDonationForm(formHost, donorCategoryRequired = availability.donorCategoryRequired)
    }
}

private fun renderDonationForm(
    formHost: SimplePanel,
    donorCategoryRequired: Boolean,
) {
    val amountInput = formHost.text(label = tr("Betrag (EUR)"))
    val purposeInput =
        formHost.text(label = tr("Verwendungszweck (optional)")) {
            // Mirrors the server-side MAX_DONATION_PURPOSE_LENGTH check in
            // PaymentGatewayService.createDonationCheckout (payment_checkout_session.purpose
            // VARCHAR(200)) -- immediate feedback instead of a failed RPC call after 200 chars.
            maxlength = MAX_DONATION_PURPOSE_LENGTH
        }
    val donorCategorySelect =
        if (donorCategoryRequired) {
            formHost.p(
                tr(
                    "Diese Organisation ist eine politische Partei -- §25 Parteiengesetz verlangt eine " +
                        "Einordnung jeder Spende. Bitte die zutreffende Kategorie wählen.",
                ),
            ) { addCssClasses("text-muted small") }
            // MAJOR (code review round 2, Welle V1.2.8): this select previously pre-selected the
            // FIRST enum entry (GERMAN_NATURAL_PERSON). Two defects in one line: (a) the §25 PartG
            // donor classification is a legal self-declaration BY THE DONOR -- pre-filling it means
            // the system silently declares on the donor's behalf, and the most common category is
            // exactly the one a foreign/corporate donor must NOT be nudged into; (b) it made the
            // `donorCategoryRequired && donorCategory == null` branch below dead code, because a
            // value was always present. An explicit empty placeholder restores the forcing function
            // -- same `value = ""` + blank-keyed leading option pattern `AuditLogScreen`'s own
            // entityTypeOptions establishes.
            formHost.select(
                options = donorCategoryOptions(),
                value = "",
                label = tr("Spenderkategorie (§25 PartG)"),
            )
        } else {
            null
        }
    val errorBox =
        formHost.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val submitButton = formHost.button(tr("Spende starten"), style = ButtonStyle.PRIMARY)

    submitButton.onClick {
        errorBox.hide()
        val amountText = amountInput.value.orEmpty().trim()
        val donorCategory = donorCategorySelect?.value?.let { runCatching { DonorCategory.valueOf(it) }.getOrNull() }

        val validationError =
            when {
                !Validation.isPositiveDecimal(amountText) -> tr("Bitte einen gültigen, positiven Betrag angeben.")
                donorCategoryRequired && donorCategory == null -> tr("Bitte eine Spenderkategorie auswählen.")
                else -> null
            }
        if (validationError != null) {
            errorBox.content = validationError
            errorBox.show()
            return@onClick
        }
        // Client-side rounding only -- the server independently validates scale<=2 and the
        // configured maximum; see this file's own KDoc "cheap client-side check" note.
        val amount = Validation.roundToTwoDecimalPlaces(amountText.toDouble()).toDecimal()

        submitButton.disabled = true
        AppScope.launch {
            try {
                val session =
                    pspGuarded(tr(PSP_CHECKOUT_CONFLICT_MESSAGE)) {
                        rpcService<IPaymentGatewayService>().createDonationCheckout(
                            DonationCheckoutInput(
                                amount = amount,
                                donorCategory = donorCategory,
                                purpose = purposeInput.value?.trim()?.takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                val redirectUrl = session?.redirectUrl
                if (redirectUrl != null) {
                    window.location.href = redirectUrl
                } else if (session != null) {
                    notifyError(tr("Der Zahlungsdienstleister hat keine Weiterleitungsadresse geliefert."))
                }
            } finally {
                submitButton.disabled = false
            }
        }
    }
}

/**
 * Reuses `AccountingLabels.kt`'s own [donorCategoryLabel] -- same §25 PartG category vocabulary,
 * never a second, drifting German label set. The leading blank-keyed entry is the deliberate
 * "no category chosen yet" placeholder -- see the call site's own MAJOR-fix comment for why this
 * select must never arrive pre-answered.
 */
internal fun donorCategoryOptions(): List<Pair<String, String>> =
    listOf("" to tr("Bitte wählen ...")) + DonorCategory.entries.map { it.name to donorCategoryLabel(it) }

/** Mirrors `payment_checkout_session.purpose VARCHAR(200)` -- see `PaymentGatewayService.createDonationCheckout`'s server-side check. */
private const val MAX_DONATION_PURPOSE_LENGTH = 200
