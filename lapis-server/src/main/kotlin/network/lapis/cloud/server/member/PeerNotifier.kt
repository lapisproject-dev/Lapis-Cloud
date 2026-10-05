package network.lapis.cloud.server.member

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.PeerExecutedEvent
import network.lapis.cloud.server.mail.PeerNotificationMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.57 -- the AFTER-COMMIT notices of the admin peer protection: look up the recipients, hand the receipts to the
 * [PeerNotificationMailer]. **Never inside the transaction that decided** (no external effect in a `transaction {}`),
 * **never result-relevant** (the change already committed; a failed notice is logged by class name only and never turns a
 * successful action into a failed call), no address or token in any log line. A no-op when outbound mail is not configured.
 */
internal class PeerNotifier(
    private val mailer: PeerNotificationMailer,
    private val smtpConfigState: SmtpConfigState,
) {
    private val mailConfigured: Boolean get() = smtpConfigState is SmtpConfigState.Configured

    private data class Person(
        val email: String,
        val displayName: String,
    )

    private fun person(memberId: Uuid): Person? =
        transaction {
            MemberTable
                .selectAll()
                .where { MemberTable.id eq memberId }
                .singleOrNull()
                ?.let { Person(email = it[MemberTable.email], displayName = it[MemberTable.displayName]) }
        }

    /** Display names are looked up so that no caller has to carry them around. */
    fun displayName(memberId: Uuid): String = person(memberId)?.displayName ?: "?"

    fun targetExecuted(
        targetId: Uuid,
        actorId: Uuid,
        event: PeerExecutedEvent,
        occurredAt: LocalDateTime,
    ) = safely(what = "executed") {
        val target = person(targetId) ?: return@safely
        mailer.sendExecutedForTarget(
            email = target.email,
            event = event,
            actorName = person(actorId)?.displayName ?: "?",
            occurredAt = occurredAt,
        )
    }

    fun resetMailTriggered(
        targetId: Uuid,
        actorId: Uuid,
        occurredAt: LocalDateTime,
    ) = safely(what = "reset-mail") {
        val target = person(targetId) ?: return@safely
        mailer.sendResetMailTriggered(email = target.email, actorName = person(actorId)?.displayName ?: "?", occurredAt = occurredAt)
    }

    fun protectedDataChanged(
        targetId: Uuid,
        actorId: Uuid,
        occurredAt: LocalDateTime,
    ) = safely(what = "protected-data") {
        val target = person(targetId) ?: return@safely
        mailer.sendProtectedDataChanged(email = target.email, actorName = person(actorId)?.displayName ?: "?", occurredAt = occurredAt)
    }

    /** Tells every administrator except [newAdminId] and [actorId] that a new administrator appeared. */
    fun newAdministrator(
        newAdminId: Uuid,
        actorId: Uuid,
        occurredAt: LocalDateTime,
    ) = safely(what = "new-admin") {
        val newAdminName = person(newAdminId)?.displayName ?: "?"
        val actorName = person(actorId)?.displayName ?: "?"
        val recipients =
            transaction {
                (AccountTable innerJoin MemberTable)
                    .selectAll()
                    .where {
                        (AccountTable.role eq AccountRole.ADMIN) and
                            (MemberTable.status notInList MemberStatusSets.LOGIN_BLOCKED) and
                            MemberTable.anonymizedAt.isNull()
                    }.filter { it[MemberTable.id] != newAdminId && it[MemberTable.id] != actorId }
                    .map { it[MemberTable.email] }
            }
        recipients.forEach { email ->
            runCatching {
                mailer.sendNewAdministrator(email = email, newAdminName = newAdminName, actorName = actorName, occurredAt = occurredAt)
            }.onFailure { e -> logger.error { "peer notice (new-admin) to one recipient failed: ${e::class.simpleName}" } }
        }
    }

    private inline fun safely(
        what: String,
        block: () -> Unit,
    ) {
        if (!mailConfigured) return
        runCatching { block() }.onFailure { e -> logger.error { "peer notice ($what) failed: ${e::class.simpleName}" } }
    }
}
