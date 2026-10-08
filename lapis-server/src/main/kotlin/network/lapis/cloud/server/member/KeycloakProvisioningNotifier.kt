package network.lapis.cloud.server.member

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.KeycloakProvisioningMailer
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
 * Welle V1.9.73 -- the AFTER-COMMIT notices of the Keycloak provisioning / profile sync (pattern of [PeerNotifier]): look up the
 * recipients, hand the receipts to the [KeycloakProvisioningMailer]. Never inside the deciding transaction, never
 * result-relevant (a missing SMTP configuration or a failing send never blocks a login), no address, name or token in any log
 * line -- a failure is logged by exception class name only.
 */
internal class KeycloakProvisioningNotifier(
    private val mailer: KeycloakProvisioningMailer,
    private val smtpConfigState: SmtpConfigState,
) {
    private val mailConfigured: Boolean get() = smtpConfigState is SmtpConfigState.Configured

    /** Tells every ADMIN (not BOARD; not blocked, not anonymized) that [newMemberId] was created on the first Keycloak login. */
    fun memberProvisioned(
        newMemberId: Uuid,
        occurredAt: LocalDateTime,
    ) {
        if (!mailConfigured) return
        runCatching {
            val (name, recipients) =
                transaction {
                    val newName =
                        MemberTable
                            .selectAll()
                            .where { MemberTable.id eq newMemberId }
                            .singleOrNull()
                            ?.get(MemberTable.displayName) ?: "?"
                    val addresses =
                        (AccountTable innerJoin MemberTable)
                            .selectAll()
                            .where {
                                (AccountTable.role eq AccountRole.ADMIN) and
                                    (MemberTable.status notInList MemberStatusSets.LOGIN_BLOCKED) and
                                    MemberTable.anonymizedAt.isNull()
                            }.map { it[MemberTable.email] }
                    newName to addresses
                }
            recipients.forEach { email ->
                runCatching { mailer.sendMemberProvisioned(email = email, newMemberName = name, occurredAt = occurredAt) }
                    .onFailure { e -> logger.error { "keycloak provisioning notice to one recipient failed: ${e::class.simpleName}" } }
            }
        }.onFailure { e -> logger.error { "keycloak provisioning notice failed: ${e::class.simpleName}" } }
    }

    /** Warns the OLD address of a member whose address was taken over from the identity provider; [maskedNewEmail] only. */
    fun emailSynced(
        oldEmail: String,
        maskedNewEmail: String,
        occurredAt: LocalDateTime,
    ) {
        if (!mailConfigured) return
        runCatching { mailer.sendEmailSyncedToOldAddress(email = oldEmail, maskedNewEmail = maskedNewEmail, occurredAt = occurredAt) }
            .onFailure { e -> logger.error { "keycloak email-sync notice failed: ${e::class.simpleName}" } }
    }
}
