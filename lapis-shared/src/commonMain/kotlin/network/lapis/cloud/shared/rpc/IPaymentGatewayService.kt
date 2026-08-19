package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.PaymentGatewayComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.PaymentGatewayComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.PaymentGatewaySettingsDto
import network.lapis.cloud.shared.domain.PaymentProvider

/**
 * Zahlungsdienstleister-Anbindung (PayPal/Stripe) -- Welle V1.2.1 "Zahlungs-Fundament" ships ONLY
 * the disclaimer-acknowledgment opt-in gate below (`OrganizationSettings.paymentGatewayEnabled`),
 * NO checkout/webhook functionality yet (PSP HTTP client, checkout sessions, webhook route,
 * reconciliation queue are V1.2.4 -- `createContributionCheckout`/`listPaymentTransactions`/etc.
 * are added to THIS SAME interface then, not a new one). See
 * `network.lapis.cloud.server.rpc.PaymentGatewayComplianceDisclaimer` KDoc for the full mechanism
 * and `11-organization-settings.kuml.kts` file header "Welle V1.2.1" for why the gate exists now,
 * ahead of any real functionality behind it.
 *
 * ## The `paymentGatewayEnabled` gate
 *
 * Exact mirror of `IAuctionService`'s "The `auctionEnabled` gate" -- `paymentGatewayEnabled` (and
 * `paymentGatewayProvider`, set together with it) are deliberately NOT part of
 * `IOrganizationSettingsService.updateOrganizationSettings`'s writable field set; settable only via
 * [enablePaymentGateway] (requires the disclaimer acknowledgment below) or off via [disablePaymentGateway].
 *
 * ## The disclaimer-acknowledgment mechanism (auditable, not a bare boolean flip)
 *
 * Exact mirror of `IAuctionService`'s own mechanism -- [enablePaymentGateway] requires the calling
 * ADMIN to first [getPaymentGatewayComplianceDisclaimer] (the current, versioned+hashed legal-risk
 * text) and echo BOTH its `version` and `sha256` back unmodified, alongside which [PaymentProvider]
 * is being enabled. On success the acknowledgment is persisted as its own append-only row (who/
 * when/which version+hash+provider). [disablePaymentGateway] requires no such acknowledgment and
 * does not erase the acknowledgment history.
 */
@RpcService
interface IPaymentGatewayService {
    /** Role: ADMIN. Not gated by `paymentGatewayEnabled` (must be readable BEFORE the feature can be switched on). */
    suspend fun getPaymentGatewayComplianceDisclaimer(): PaymentGatewayComplianceDisclaimerDto

    /** Role: ADMIN. `provider` must be `PAYPAL` or `STRIPE` (never `MANUAL`). See class KDoc "The disclaimer-acknowledgment mechanism". */
    suspend fun enablePaymentGateway(
        provider: PaymentProvider,
        acknowledgment: PaymentGatewayComplianceAcknowledgmentInput,
    ): PaymentGatewaySettingsDto

    /** Role: ADMIN. No acknowledgment required to turn the feature off. */
    suspend fun disablePaymentGateway(): PaymentGatewaySettingsDto

    /** Role: ADMIN. */
    suspend fun getPaymentGatewaySettings(): PaymentGatewaySettingsDto
}
