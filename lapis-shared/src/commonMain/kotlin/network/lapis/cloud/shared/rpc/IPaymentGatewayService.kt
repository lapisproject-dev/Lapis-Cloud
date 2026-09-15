package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.CheckoutSessionDto
import network.lapis.cloud.shared.domain.ContributionCheckoutInput
import network.lapis.cloud.shared.domain.DonationCheckoutInput
import network.lapis.cloud.shared.domain.PaymentGatewayAvailabilityDto
import network.lapis.cloud.shared.domain.PaymentGatewayComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.PaymentGatewayComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.PaymentGatewaySettingsDto
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionPageDto
import network.lapis.cloud.shared.domain.PaymentTransactionQuery
import network.lapis.cloud.shared.domain.PspConfigStatusDto

/**
 * Zahlungsdienstleister-Anbindung -- Welle V1.2.1 "Zahlungs-Fundament" shipped ONLY the
 * disclaimer-acknowledgment opt-in gate below (`OrganizationSettings.paymentGatewayEnabled`), with
 * no checkout/webhook functionality behind it. Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue
 * #6) added that functionality, additively, on THIS SAME interface -- scoped to **Stripe only** at
 * the time (Stripe's webhook signature is a self-contained HMAC verifiable with zero outbound calls
 * and zero SDK; PayPal's Orders v2 flow needs OAuth2 token exchange and a live outbound
 * verification call).
 *
 * Welle V1.2.8b "PayPal-Anbindung" (GitHub Issue #6) adds PayPal as a second, equally first-class
 * [PaymentProvider], on this same interface, additively. The two open design questions the V1.2.8
 * KDoc deferred are resolved as follows -- see
 * `network.lapis.cloud.server.payment.psp.PaypalOrdersClient`/`PaypalWebhookVerification` KDoc for
 * the full reasoning:
 * 1. **Webhook signature verification** uses PayPal's own `POST /v1/notifications/
 *    verify-webhook-signature` API (option "verify API"), NOT local certificate-chain validation.
 *    Local validation would still need an outbound HTTPS fetch of the certificate named in the
 *    (attacker-controlled, unauthenticated) `PAYPAL-CERT-URL` header on a cold cache -- an SSRF
 *    sink for no real benefit over the verify API, which fails BENIGN (PayPal retries a non-2xx
 *    delivery for up to 3 days) and scopes verification to `LAPIS_PAYPAL_WEBHOOK_ID` for free.
 * 2. **Capture happens inside the PayPal webhook handler**, on `CHECKOUT.ORDER.APPROVED`, never in
 *    the return-flow -- robust against a payer who approves and then closes the tab, and needs
 *    zero new RPC method and zero client change (`PaymentReturnScreen`'s webhook-authoritative
 *    polling already works unchanged for either provider).
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
 *
 * ## The three-part usability gate (Welle V1.2.8)
 *
 * Every checkout-creation method below (and the webhook route itself, at delivery time) is gated by
 * a private `requirePaymentGatewayUsable()` -- ALL of the following must hold, or the gateway is
 * unusable end to end, even if some subset looks configured:
 * 1. `organization_settings.payment_gateway_enabled` is `true` (this gate, above).
 * 2. The CURRENT disclaimer version was acknowledged (same "stale acknowledgment blocks writes"
 *    posture `SepaService.requireSepaUsable` already establishes for SEPA).
 * 3. The deployment's secrets for the SELECTED provider are present and valid
 *    (`PspConfigState.Configured` for `STRIPE`, `PaypalConfigState.Configured` for `PAYPAL`), AND
 *    `organization_settings.payment_gateway_provider` matches a provider whose transport is
 *    actually configured. `enablePaymentGateway` accepts both `STRIPE` and `PAYPAL` (only `MANUAL`
 *    is rejected) -- enabling a provider whose env transport is not configured leaves the gateway
 *    merely UNUSABLE end-to-end (this three-part gate), it does not throw at enable-time.
 *
 * ## Never a secret-writing RPC
 *
 * **There is deliberately no `updatePspSettings`.** PSP secrets are env-only
 * (`LAPIS_STRIPE_SECRET_KEY`/`LAPIS_STRIPE_WEBHOOK_SIGNING_SECRET`/`LAPIS_PAYPAL_CLIENT_ID`/
 * `LAPIS_PAYPAL_CLIENT_SECRET`/`LAPIS_PAYPAL_WEBHOOK_ID`, never persisted, never `SecretBox`-sealed
 * -- see `PspConfig`/`PaypalConfig` KDoc) and can never be written through an RPC. The
 * Treasurer/ADMIN screen configures: the compliance gate ([enablePaymentGateway]/
 * [disablePaymentGateway]) and the four ledger-account mappings (through the existing
 * `IOrganizationSettingsService.updateOrganizationSettings`), and *reads* [getPspConfigStatus] to
 * see whether the deployment's env vars are in place. A later wave must NOT "complete" this API by
 * adding a secret-writing method -- that would defeat the entire "env-only, never persisted"
 * design.
 */
@RpcService
interface IPaymentGatewayService {
    /** Role: ADMIN. Not gated by `paymentGatewayEnabled` (must be readable BEFORE the feature can be switched on). */
    suspend fun getPaymentGatewayComplianceDisclaimer(): PaymentGatewayComplianceDisclaimerDto

    /**
     * Role: ADMIN. `provider` must be `PAYPAL` or `STRIPE` (never `MANUAL`, rejected with
     * [BadRequestException]). See class KDoc "The disclaimer-acknowledgment mechanism" and "The
     * three-part usability gate" -- enabling a provider whose deployment secrets are not configured
     * succeeds here but leaves the gateway unusable, it does not throw.
     */
    suspend fun enablePaymentGateway(
        provider: PaymentProvider,
        acknowledgment: PaymentGatewayComplianceAcknowledgmentInput,
    ): PaymentGatewaySettingsDto

    /** Role: ADMIN. No acknowledgment required to turn the feature off. */
    suspend fun disablePaymentGateway(): PaymentGatewaySettingsDto

    /** Role: ADMIN. */
    suspend fun getPaymentGatewaySettings(): PaymentGatewaySettingsDto

    /**
     * Welle V1.2.8. Role: ANY authenticated member. Never throws for a disabled/unconfigured
     * gateway -- returns `enabled = false` (and every `*Available` flag `false`) instead. See
     * [PaymentGatewayAvailabilityDto].
     */
    suspend fun getPaymentGatewayAvailability(): PaymentGatewayAvailabilityDto

    /**
     * Welle V1.2.8. Role: the contribution's OWN member, or TREASURER/BOARD/ADMIN acting for a
     * member (MINOR fix, code review: this KDoc previously omitted BOARD from the actual role set).
     * Gated by the three-part usability gate (class KDoc). The amount is read from
     * `contribution.amount_due` server-side (MINOR fix, code review: this KDoc previously named the
     * non-existent `contribution.amount` column) and is NEVER taken from the caller. Rejects
     * ([ConflictException]) a contribution already in `ContributionStatusSets.SETTLED` OR currently
     * `ContributionStatusSets.DEBIT_IN_FLIGHT` (a SEPA batch may already be collecting it -- MAJOR
     * fix, code review, Welle V1.2.8: this double-collection guard was missing entirely before), and
     * reuses an existing non-expired `CREATED` session for the same contribution instead of minting
     * a second Stripe session.
     */
    suspend fun createContributionCheckout(input: ContributionCheckoutInput): CheckoutSessionDto

    /**
     * Welle V1.2.8. Role: ANY authenticated member. Same three-part usability gate (class KDoc).
     * When `organization_settings.is_political_party` is `true`,
     * [DonationCheckoutInput.donorCategory] is MANDATORY and the checkout is refused up front
     * ([ConflictException]) if the §25 PartG compliance check would PROHIBIT the requested amount
     * for that category -- a donor must never be charged for a donation this organization is
     * legally forbidden to accept.
     */
    suspend fun createDonationCheckout(input: DonationCheckoutInput): CheckoutSessionDto

    /**
     * Welle V1.2.8. Role: the session's own initiating member, or TREASURER/BOARD/ADMIN. The poll
     * target for the post-redirect return screen -- the webhook is authoritative for whether the
     * payment actually completed, this method only reports the current, already-persisted state.
     */
    suspend fun getCheckoutSession(checkoutSessionId: String): CheckoutSessionDto

    /** Welle V1.2.8. Role: TREASURER/BOARD/ADMIN. `limit` is clamped to 1..200 server-side (same idiom as `SepaService.listMandates`). */
    suspend fun listPaymentTransactions(query: PaymentTransactionQuery): PaymentTransactionPageDto

    /** Welle V1.2.8. Role: ADMIN. See [PspConfigStatusDto] -- reports presence, never a value. */
    suspend fun getPspConfigStatus(): PspConfigStatusDto
}
