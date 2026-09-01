package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionCheckoutInput
import network.lapis.cloud.shared.domain.ContributionDto
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.IPaymentGatewayService

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- member-facing section hooked into
 * `ContributionsScreen.kt` with **one line** (`renderPspCheckoutSection(root)`, between the page's
 * own `h1` and `renderSepaMandateSection`), same "one line, no other change to that file" posture
 * [SepaMandateSection] already establishes.
 *
 * S-16 trap (same as [SepaMandateSection]): owns its OWN [io.kvision.panel.VPanel], only ever
 * clears that panel, never `root` -- `ContributionsScreen.renderOwnSummary`/
 * `renderOrgWideContributions` keep rendering below it, asynchronously.
 *
 * Renders **nothing at all** for a plain MEMBER when the gateway is off; one muted line for
 * privileged roles (an ADMIN additionally gets a link to `/payment-gateway-settings`, mirroring
 * [SepaMandateSection]'s own K1 treatment). Otherwise: one "Online bezahlen" button per outstanding
 * own contribution -> `createContributionCheckout` -> `window.location.href = redirectUrl`.
 */
fun renderPspCheckoutSection(root: SimplePanel) {
    val panel = root.vPanel(spacing = 8)

    fun refresh() {
        panel.removeAll()
        AppScope.launch {
            // Welle V1.2.9 fix: a probe that genuinely failed (dropped connection, expired
            // session) must not be reported as "the organization disabled this" -- see
            // PspProbeResult's own KDoc. Symmetric with DonationCheckoutScreen's own handling.
            val availability =
                when (val result = pspProbe { rpcService<IPaymentGatewayService>().getPaymentGatewayAvailability() }) {
                    is PspProbeResult.TransportError -> {
                        panel.div(tr("Status konnte nicht geladen werden. Bitte laden Sie die Seite neu.")) {
                            addCssClasses("text-muted small")
                        }
                        return@launch
                    }
                    is PspProbeResult.Ok -> result.value
                }
            if (!availability.contributionCheckoutAvailable) {
                if (AppState.hasRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)) {
                    val notice = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
                    notice.div(tr("Online-Zahlung ist für diese Organisation nicht aktiviert.")) {
                        addCssClasses("text-muted small")
                    }
                    if (AppState.hasRole(AccountRole.ADMIN)) {
                        val settingsLink = notice.button(tr("Zahlungs-Konfiguration öffnen"), style = ButtonStyle.LINK)
                        settingsLink.onClick { navigateTo(Routes.PAYMENT_GATEWAY_SETTINGS) }
                    }
                }
                // MEMBER (or no privileged role at all): render nothing.
                return@launch
            }

            val session = AppState.session ?: return@launch
            val myContributions =
                when (val result = pspProbe { rpcService<IContributionService>().getMemberContributionSummary(session.memberId) }) {
                    is PspProbeResult.TransportError -> {
                        panel.div(tr("Status konnte nicht geladen werden. Bitte laden Sie die Seite neu.")) {
                            addCssClasses("text-muted small")
                        }
                        return@launch
                    }
                    is PspProbeResult.Ok -> result.value
                }
            val outstanding = myContributions.contributions.filter { it.status in ContributionStatusSets.OUTSTANDING }
            outstanding.forEach { contribution ->
                renderCheckoutButton(panel, contribution)
            }
        }
    }
    refresh()
}

private fun renderCheckoutButton(
    panel: SimplePanel,
    contribution: ContributionDto,
) {
    val row = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    row.div(formatMoney(contribution.amountDue)) { addCssClasses("small") }
    // Welle V1.2.9 fix, reverted (code review, Welle V1.2.9 round 2): `maxCheckoutAmountEur`
    // (`PspConfig.maxCheckoutAmountEur`) is explicitly documented as an "Abuse/DoS cap on
    // `createDonationCheckout`" -- `createContributionCheckout` (PaymentGatewayService.kt) never
    // reads it and never rejects on it. A contribution amount is server-derived (never
    // donor-typed) and can legitimately exceed the donation-abuse cap (e.g. an annual
    // corporate/Förder-Beitrag) -- gating the button here on that donation-only cap blocked a
    // payment the server would happily accept, contradicting this function's own removed comment
    // that claimed the RPC "could only ever fail server-side" for amounts above the cap. If a
    // real ceiling for contributions is ever wanted, it has to be enforced in
    // `createContributionCheckout` itself first; only then does a matching client-side pre-check
    // belong here again.
    val payButton = row.button(tr("Online bezahlen"), style = ButtonStyle.PRIMARY)
    payButton.onClick {
        payButton.disabled = true
        AppScope.launch {
            try {
                val session =
                    pspGuarded(tr(PSP_CHECKOUT_CONFLICT_MESSAGE)) {
                        rpcService<IPaymentGatewayService>().createContributionCheckout(
                            ContributionCheckoutInput(contributionId = contribution.id),
                        )
                    }
                val redirectUrl = session?.redirectUrl
                if (redirectUrl != null) {
                    window.location.href = redirectUrl
                } else if (session != null) {
                    notifyError(tr("Der Zahlungsdienstleister hat keine Weiterleitungsadresse geliefert."))
                }
            } finally {
                payButton.disabled = false
            }
        }
    }
}

/** Shared `conflictMessage` for `createContributionCheckout`/`createDonationCheckout` -- see `SepaMandateSection.kt`'s own `SEPA_WRITE_CONFLICT_MESSAGE` KDoc for why a call-site-specific text beats the generic "im Konflikt" toast. */
internal const val PSP_CHECKOUT_CONFLICT_MESSAGE =
    "Der Online-Zahlungsvorgang konnte nicht gestartet werden -- mögliche Gründe: die Online-Zahlung ist nicht " +
        "aktiviert, der aktuelle Rechtshinweis wurde nicht erneut bestätigt, der Zahlungsdienstleister ist nicht " +
        "vollständig konfiguriert, oder der Beitrag ist bereits ausgeglichen."
