package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.p
import io.kvision.i18n.gettext
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
 * Amount field (validated client-side: > 0, ≤ 2 decimals (rounded before the cap check, see the
 * submit handler), ≤ the configured maximum reported by `getPaymentGatewayAvailability`'s
 * `maxCheckoutAmountEur` -- same "cheap client-side check, server is the real authority" posture
 * every other form in this client follows: the server independently re-validates all three bounds
 * and remains the actual authority), optional purpose, and -- when
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
        // Welle V1.2.9 fix: a probe that genuinely failed (dropped connection, expired session)
        // must not be reported to the donor as "the organization disabled this" -- see
        // PspProbeResult's own KDoc.
        when (val result = pspProbe { rpcService<IPaymentGatewayService>().getPaymentGatewayAvailability() }) {
            is PspProbeResult.TransportError -> {
                statusHost.p(tr("Status konnte nicht geladen werden. Bitte laden Sie die Seite neu."))
                return@launch
            }
            is PspProbeResult.Ok -> {
                val availability = result.value
                if (!availability.donationCheckoutAvailable) {
                    statusHost.p(tr("Online-Spenden sind für diese Organisation aktuell nicht möglich."))
                    return@launch
                }
                renderDonationForm(
                    formHost,
                    donorCategoryRequired = availability.donorCategoryRequired,
                    maxCheckoutAmountEur = availability.maxCheckoutAmountEur,
                )
            }
        }
    }
}

private fun renderDonationForm(
    formHost: SimplePanel,
    donorCategoryRequired: Boolean,
    maxCheckoutAmountEur: Decimal?,
) {
    val amountInput = formHost.text(label = tr("Betrag (EUR)"))
    // Welle V1.2.9: shows the real, server-configured ceiling BEFORE the donor types an amount --
    // see PaymentGatewayAvailabilityDto.maxCheckoutAmountEur's own KDoc for why this is a UX
    // convenience only, never the enforcement point.
    if (maxCheckoutAmountEur != null) {
        formHost.p(gettext("Höchstbetrag pro Online-Spende: %1", formatMoney(maxCheckoutAmountEur))) {
            addCssClasses("text-muted small")
        }
    }
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

        // Client-side rounding only -- the server independently validates scale<=2 and the
        // configured maximum; see this file's own KDoc "cheap client-side check" note. Rounded
        // BEFORE the cap check below (fix, code review Welle V1.2.9 round 2): the cap comparison
        // must use the same value that is actually sent, or a harmless extra-decimal typo like
        // "10000.004" (which `isPositiveDecimal` deliberately lets through and this rounding step
        // is meant to absorb) gets rejected client-side for a value the server would have
        // accepted once rounded. `amountText.toDouble()` is safe here (unguarded): the branch
        // above already proved `amountText` is a positive decimal.
        val roundedAmount =
            if (Validation.isPositiveDecimal(amountText)) {
                Validation.roundToTwoDecimalPlaces(amountText.toDouble())
            } else {
                null
            }

        val validationError =
            when {
                !Validation.isPositiveDecimal(amountText) -> tr("Bitte einen gültigen, positiven Betrag angeben.")
                // Welle V1.2.9: catches the doomed-RPC case client-side with the REAL configured
                // number -- see PaymentGatewayAvailabilityDto.maxCheckoutAmountEur's own KDoc.
                roundedAmount != null && Validation.exceedsMaxCheckoutAmountEur(roundedAmount, maxCheckoutAmountEur?.toDouble()) ->
                    gettext("Der Höchstbetrag pro Online-Spende beträgt %1.", formatMoney(requireNotNull(maxCheckoutAmountEur)))
                donorCategoryRequired && donorCategory == null -> tr("Bitte eine Spenderkategorie auswählen.")
                else -> null
            }
        if (validationError != null) {
            errorBox.content = validationError
            errorBox.show()
            return@onClick
        }
        val amount = requireNotNull(roundedAmount) { "validationError above already rejects a non-positive-decimal amountText" }.toDecimal()

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
