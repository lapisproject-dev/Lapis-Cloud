package network.lapis.cloud.server.tender

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.TenderPayoutFailedNotification
import network.lapis.cloud.server.mail.TenderPayoutNotificationMailer
import network.lapis.cloud.server.mail.TenderPayoutPaidNotification
import network.lapis.cloud.server.security.AccountRights
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private const val TENDER_PAYOUT_FAILED_MAX_RECIPIENTS = 20

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

    /**
     * V1.9.331 -- the board notice after a freshly committed PAYOUT_FAILED. Recipients: ACTIVE BOARD/ADMIN/TREASURER members who bid on nothing
     * here and are not the paying member. At most one mail round per call; never throws.
     */
    fun failed(
        tenderId: Uuid,
        payerId: Uuid,
    ) {
        try {
            val notification = transaction { loadFailed(tenderId = tenderId, payerId = payerId) } ?: return
            mailer.sendFailedForBoard(notification)
        } catch (e: Exception) {
            logger.warn { "tender $tenderId payout failure notice failed: ${e::class.simpleName}" }
        }
    }

    private fun loadFailed(
        tenderId: Uuid,
        payerId: Uuid,
    ): TenderPayoutFailedNotification? {
        val data = TenderStore.payoutFailureData(tenderId) ?: return null
        val excluded = (data.bidderIds + payerId).toList()
        val recipients =
            (AccountTable innerJoin MemberTable)
                .select(MemberTable.id, MemberTable.email)
                .where {
                    AccountRights.effectiveRoleIn(TENDER_READ_ROLES) and
                        (MemberTable.status eq MemberStatus.ACTIVE) and
                        (MemberTable.id notInList excluded)
                }.map { it[MemberTable.id] to it[MemberTable.email].trim() }
                .sortedBy { it.first }
                .map { it.second }
                .filter { it.isNotEmpty() && '@' in it }
                .distinctBy { it.lowercase() }
                .take(TENDER_PAYOUT_FAILED_MAX_RECIPIENTS)
        if (recipients.isEmpty()) {
            logger.info { "tender $tenderId payout failure notice: no recipients" }
            return null
        }
        return TenderPayoutFailedNotification(tenderId = tenderId, tenderTitle = data.title, recipientEmails = recipients)
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
