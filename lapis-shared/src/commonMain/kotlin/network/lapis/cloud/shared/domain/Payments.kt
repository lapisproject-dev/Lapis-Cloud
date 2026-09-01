package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Zahlungsverkehr domain, Welle V1.2.1 "Zahlungs-Fundament" (vault "Lapis Cloud V1.2 --
 * Zahlungsverkehr" plan). See `33-payments.kuml.kts` file header for the full schema-scope
 * rationale (only `payment_transaction`, no SEPA/Mahnwesen/PSP-webhook tables yet).
 *
 * Literal order load-bearing (`PaymentsSchemaDriftTest` pins it against `33-payments.kuml.kts`'s
 * `paymentProvider` enum). `MANUAL` covers a payment recorded by a treasurer directly (e.g. a
 * future camt-import reconciliation), not routed through any real PSP.
 */
@Serializable
enum class PaymentProvider { PAYPAL, STRIPE, MANUAL }

/** Literal order load-bearing, same reason as [PaymentProvider]. */
@Serializable
enum class PaymentTransactionStatus { PENDING, CAPTURED, FAILED, REFUNDED, DISPUTED }

/** Literal order load-bearing, same reason as [PaymentProvider]. */
@Serializable
enum class PaymentIntent { CONTRIBUTION, DONATION }

// ================================================================================================
// Compliance-gate DTOs -- SEPA and Payment-Gateway each get their own disclaimer/acknowledgment/
// settings triad, mirroring AuctionComplianceDisclaimerDto/AuctionComplianceAcknowledgmentInput/
// AuctionSettingsDto EXACTLY (see network.lapis.cloud.server.rpc.AuctionComplianceDisclaimer KDoc
// for the mechanism these DTOs carry). Neither gate has any real functionality behind it yet in
// V1.2.1 (SEPA mandates/PSP webhooks are V1.2.2/V1.2.8) -- the gate exists now so those later
// sub-waves find it already built and reviewed, see 11-organization-settings.kuml.kts file header.
// ================================================================================================

/**
 * The current, versioned+hashed legal disclaimer text an ADMIN must echo back (unmodified) to
 * [network.lapis.cloud.shared.rpc.ISepaService.enableSepaDebit] -- see that method's KDoc.
 */
@Serializable
data class SepaComplianceDisclaimerDto(
    val version: String,
    val text: String,
    val sha256: String,
)

/** Proof the ADMIN was shown the CURRENT SEPA disclaimer text -- see [SepaComplianceDisclaimerDto]. */
@Serializable
data class SepaComplianceAcknowledgmentInput(
    val disclaimerVersion: String,
    val disclaimerSha256: String,
)

/**
 * Role: ADMIN (every field). [lastAcknowledgedByDisplayName]/[lastAcknowledgedAt]/
 * [lastDisclaimerVersion] are all `null` if [sepaDebitEnabled] has never been switched on --
 * mirrors [AuctionSettingsDto] exactly.
 */
@Serializable
data class SepaSettingsDto(
    val sepaDebitEnabled: Boolean,
    val lastAcknowledgedByDisplayName: String?,
    val lastAcknowledgedAt: LocalDateTime?,
    val lastDisclaimerVersion: String?,
)

/**
 * The current, versioned+hashed legal disclaimer text an ADMIN must echo back (unmodified) to
 * [network.lapis.cloud.shared.rpc.IPaymentGatewayService.enablePaymentGateway] -- see that method's KDoc.
 */
@Serializable
data class PaymentGatewayComplianceDisclaimerDto(
    val version: String,
    val text: String,
    val sha256: String,
)

/** Proof the ADMIN was shown the CURRENT gateway disclaimer text -- see [PaymentGatewayComplianceDisclaimerDto]. */
@Serializable
data class PaymentGatewayComplianceAcknowledgmentInput(
    val disclaimerVersion: String,
    val disclaimerSha256: String,
)

/**
 * Role: ADMIN (every field). [lastAcknowledgedByDisplayName]/[lastAcknowledgedAt]/
 * [lastDisclaimerVersion] are all `null` if [paymentGatewayEnabled] has never been switched on.
 */
@Serializable
data class PaymentGatewaySettingsDto(
    val paymentGatewayEnabled: Boolean,
    val paymentGatewayProvider: PaymentProvider?,
    val lastAcknowledgedByDisplayName: String?,
    val lastAcknowledgedAt: LocalDateTime?,
    val lastDisclaimerVersion: String?,
)

// ================================================================================================
// Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- checkout/webhook DTOs. Previously
// tracked under the placeholder name "V1.2.4" in the KDoc paragraphs above (renumbered away before
// this work started); this is that promised sub-wave.
// ================================================================================================

/** Literal order load-bearing, same reason as [PaymentProvider] (`PaymentsSchemaDriftTest` pins it against `33-payments.kuml.kts`). */
@Serializable
enum class PaymentCheckoutSessionStatus { CREATED, COMPLETED, EXPIRED, FAILED }

/**
 * Welle V1.2.8. Role: any authenticated member paying their OWN contribution (or TREASURER/ADMIN
 * acting for a member). The amount is NEVER client-supplied -- it is read from the contribution row
 * server-side, see [network.lapis.cloud.shared.rpc.IPaymentGatewayService.createContributionCheckout].
 */
@Serializable
data class ContributionCheckoutInput(
    val contributionId: String,
)

/**
 * Welle V1.2.8. Role: any authenticated member. [amount] IS client-supplied (a free-form donation
 * has no server-side anchor) and is therefore validated server-side against scale<=2, > 0, and a
 * configurable maximum. [donorCategory] is REQUIRED when the organization is a political party
 * (`organization_settings.is_political_party`), see
 * [network.lapis.cloud.shared.rpc.IPaymentGatewayService.createDonationCheckout] KDoc.
 */
@Serializable
data class DonationCheckoutInput(
    val amount: Decimal,
    val donorCategory: DonorCategory? = null,
    val purpose: String? = null,
)

/** Welle V1.2.8. [redirectUrl] is the PSP-hosted checkout page; `null` once the session is no longer CREATED. */
@Serializable
data class CheckoutSessionDto(
    val id: String,
    val provider: PaymentProvider,
    val intent: PaymentIntent,
    val status: PaymentCheckoutSessionStatus,
    val amount: Decimal,
    val currency: String,
    val contributionId: String?,
    val redirectUrl: String?,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime,
    val paymentTransactionId: String?,
    val journalEntryId: String?,
)

/** Welle V1.2.8. Role: TREASURER/BOARD/ADMIN. */
@Serializable
data class PaymentTransactionDto(
    val id: String,
    val provider: PaymentProvider,
    val providerPaymentId: String,
    val status: PaymentTransactionStatus,
    val amount: Decimal,
    val currency: String,
    val feeAmount: Decimal?,
    val intent: PaymentIntent,
    val contributionId: String?,
    val memberId: String?,
    val memberDisplayName: String?,
    val donorCategory: DonorCategory?,
    val receivedAt: LocalDateTime,
    val journalEntryId: String?,
    val reconciliationNote: String?,
)

/**
 * Welle V1.2.8. [unreconciledOnly] surfaces exactly the rows a webhook captured but could not post
 * (`journalEntryId == null`) -- the treasurer's work queue.
 */
@Serializable
data class PaymentTransactionQuery(
    val status: PaymentTransactionStatus? = null,
    val intent: PaymentIntent? = null,
    val memberId: String? = null,
    val unreconciledOnly: Boolean = false,
    val limit: Int = 50,
    val offset: Int = 0,
)

@Serializable
data class PaymentTransactionPageDto(
    val rows: List<PaymentTransactionDto>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

/**
 * Welle V1.2.8. Role: ADMIN. Diagnostic ONLY -- reports WHETHER each deployment secret is present,
 * NEVER any part of its value, and never a prefix/suffix/length. Mirrors `SmtpConfig`'s redacting
 * `toString` discipline at the DTO boundary.
 */
@Serializable
data class PspConfigStatusDto(
    val configuredProvider: PaymentProvider?,
    val secretKeyConfigured: Boolean,
    val webhookSecretConfigured: Boolean,
    val webhookUrl: String,
    val publicBaseUrl: String,
    val paymentBankAccountConfigured: Boolean,
    val contributionIncomeAccountConfigured: Boolean,
    val donationIncomeAccountConfigured: Boolean,
    val paymentFeeAccountConfigured: Boolean,
)

/**
 * Welle V1.2.8. Role: ANY authenticated member. The member-readable availability probe --
 * deliberately separate from [PaymentGatewaySettingsDto] (ADMIN-only, carries the acknowledgment
 * history). Mirrors the reasoning behind `SepaMandateSection`'s own `sepaProbe` call site: a plain
 * MEMBER must be able to learn "can I pay online?" without an ADMIN-tier RPC.
 *
 * Welle V1.2.9: [maxCheckoutAmountEur] surfaces the server-side abuse/DoS cap
 * (`PspConfig.maxCheckoutAmountEur`) so a donor learns the real ceiling BEFORE submitting a
 * doomed `createDonationCheckout` call, instead of only from that RPC's own
 * `BadRequestException` after a round-trip. `null` when the gate is not [enabled] (a disabled gate
 * has no meaningful ceiling to show) -- **not** authoritative, purely a UX convenience; the server
 * remains the sole enforcer, see `PaymentGatewayService.createDonationCheckout`'s own check.
 * Declared with a default so this additive field never breaks an older client's deserialization of
 * an already-shipped DTO.
 */
@Serializable
data class PaymentGatewayAvailabilityDto(
    val enabled: Boolean,
    val provider: PaymentProvider?,
    val contributionCheckoutAvailable: Boolean,
    val donationCheckoutAvailable: Boolean,
    val donorCategoryRequired: Boolean,
    val maxCheckoutAmountEur: Decimal? = null,
)
