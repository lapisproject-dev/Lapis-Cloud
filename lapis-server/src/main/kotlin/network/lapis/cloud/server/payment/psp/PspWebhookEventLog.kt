package network.lapis.cloud.server.payment.psp

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.PspWebhookEventTable
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Server-internal-only outcome of one `psp_webhook_event` delivery -- never sent over RPC, so not
 * a shared `@Serializable` domain enum (see `33-payments.kuml.kts`'s own file-header note on why
 * `outcome` is modelled as a plain `String` column there, mirroring
 * `federation_inbox_delivery_log.reject_reason`/`.activity_type`).
 */
enum class PspWebhookOutcome { REJECTED, IGNORED, DUPLICATE, PROCESSED, UNPOSTED }

/**
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- writes exactly one `psp_webhook_event`
 * row per webhook delivery attempt, ALWAYS in its OWN `transaction {}` (unlike every other file in
 * `payment/psp/`, which is transaction-free by contract) -- so a rolled-back ingestion still leaves
 * the forensic trace behind. Direct analogue of `FederationInboxDeliveryLogTable`'s own logging
 * call site in `FederationRoutes.kt`.
 */
object PspWebhookEventLog {
    fun record(
        provider: PaymentProvider,
        providerEventId: String?,
        eventType: String?,
        signatureVerified: Boolean,
        rejectReason: String?,
        outcome: PspWebhookOutcome,
        paymentTransactionId: Uuid?,
        bodySha256: String,
        bodyByteSize: Int,
        receivedAt: LocalDateTime = DbClock.nowLocalDateTime(),
    ) {
        transaction {
            PspWebhookEventTable.insert {
                it[id] = Uuid.random()
                it[PspWebhookEventTable.provider] = provider
                it[PspWebhookEventTable.providerEventId] = providerEventId
                it[PspWebhookEventTable.eventType] = eventType
                it[PspWebhookEventTable.receivedAt] = receivedAt
                it[PspWebhookEventTable.signatureVerified] = signatureVerified
                it[PspWebhookEventTable.rejectReason] = rejectReason
                it[PspWebhookEventTable.outcome] = outcome.name
                it[PspWebhookEventTable.paymentTransactionId] = paymentTransactionId
                it[PspWebhookEventTable.bodySha256] = bodySha256
                it[PspWebhookEventTable.bodyByteSize] = bodyByteSize
            }
        }
    }
}
