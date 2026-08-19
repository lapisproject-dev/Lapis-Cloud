package network.lapis.cloud.shared.domain

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
// V1.2.1 (SEPA mandates/PSP webhooks are V1.2.2/V1.2.4) -- the gate exists now so those later
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
