package network.lapis.cloud.server.tender

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.TenderPayoutNotificationMailer
import network.lapis.cloud.server.mail.TenderPayoutPaidNotification
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V1.9.330 -- the AFTER-COMMIT receipt to the winner of a paid tender. Reads the COMMITTED `tender_payout` row in its own read transaction
 * (never inside the payout transaction: no external effect in a `transaction {}`), never result-relevant, never throws. No amount, address,
 * title or name in any log line. Touches no ledger table (only `TenderPayout` does).
 */
internal class TenderPayoutNotifier(
    private val mailer: TenderPayoutNotificationMailer,
) {
    fun paid(tenderId: Uuid) {
        try {
            val notification = transaction { load(tenderId) } ?: return
            mailer.sendPaidForWinner(notification)
        } catch (e: Exception) {
            logger.warn { "tender $tenderId payout notice failed: ${e::class.simpleName}" }
        }
    }

    private fun load(tenderId: Uuid): TenderPayoutPaidNotification? {
        val data = TenderStore.payoutReceiptData(tenderId) ?: return null
        val member = MemberTable.selectAll().where { MemberTable.id eq data.winnerId }.singleOrNull() ?: return null
        val email = member[MemberTable.email].trim()
        if (email.isEmpty() || '@' !in email || member[MemberTable.status] !in MemberStatusSets.LTR_ELIGIBLE) return null
        return TenderPayoutPaidNotification(
            tenderId = tenderId,
            winnerEmail = email,
            tenderTitle = data.title,
            amount = data.amount,
            executedAt = data.executedAt,
        )
    }
}
