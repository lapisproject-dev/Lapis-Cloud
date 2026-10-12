package network.lapis.cloud.server.mail.newsletter

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingListOptOutTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MailingListKind
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.111 -- DB fixtures of the dynamic mailing list tests. Every test isolates itself through a fresh COMMITTEE of its own (rules use
 * `COMMITTEE` conditions only), so the seeded members of the shared H2 never leak into a rule's result.
 */
internal class DynamicFixture {
    val memberIds = mutableListOf<Uuid>()
    val committeeIds = mutableListOf<Uuid>()
    val listIds = mutableListOf<Uuid>()
    val messageIds = mutableListOf<Uuid>()

    fun member(
        email: String = "dyn-${Uuid.random()}@example.org",
        role: AccountRole? = AccountRole.MEMBER,
        status: MemberStatus = MemberStatus.ACTIVE,
        anonymised: Boolean = false,
        joinedAt: LocalDate = LocalDate(2020, 1, 1),
        displayName: String = "Dyn-Testmitglied",
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[MemberTable.displayName] = displayName
                it[MemberTable.email] = email
                it[MemberTable.status] = status
                it[MemberTable.joinedAt] = joinedAt
                it[anonymizedAt] = if (anonymised) DbClock.nowLocalDateTime() else null
            }
            if (role != null) {
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
        }
        memberIds += id
        return id
    }

    fun committee(active: Boolean = true): Uuid {
        val committeeId = Uuid.random()
        transaction {
            CommitteeTable.insert {
                it[id] = committeeId
                it[name] = "Dyn-Gremium $committeeId"
                it[type] = CommitteeType.WORKING_GROUP
                it[description] = "Test"
                it[CommitteeTable.active] = active
                it[quorumPercent] = 50
                it[createdAt] = LocalDateTime(2020, 1, 1, 0, 0)
            }
        }
        committeeIds += committeeId
        return committeeId
    }

    fun seat(
        committeeId: Uuid,
        memberId: Uuid,
        since: LocalDate = LocalDate(2020, 1, 1),
        until: LocalDate? = null,
    ) {
        transaction {
            CommitteeMembershipTable.insert {
                it[id] = Uuid.random()
                it[CommitteeMembershipTable.committeeId] = committeeId
                it[CommitteeMembershipTable.memberId] = memberId
                it[role] = CommitteeRole.MEMBER
                it[CommitteeMembershipTable.since] = since
                it[CommitteeMembershipTable.until] = until
            }
        }
    }

    /** A MANUAL list inserted directly. */
    fun list(createdBy: Uuid): Uuid {
        val id = Uuid.random()
        transaction {
            MailingListTable.insert {
                it[MailingListTable.id] = id
                it[name] = "Manuelle Liste $id"
                it[description] = null
                it[MailingListTable.createdBy] = createdBy
            }
        }
        listIds += id
        return id
    }

    /** A DYNAMIC list inserted directly (no audit, no validation) -- for worker tests. */
    fun dynamicList(
        committeeId: Uuid,
        createdBy: Uuid,
        mandatory: Boolean = false,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MailingListTable.insert {
                it[MailingListTable.id] = id
                it[name] = "Dyn-Liste $id"
                it[description] = null
                it[MailingListTable.createdBy] = createdBy
                it[kind] = MailingListKind.DYNAMIC
                it[ruleJson] = committeeRule(committeeId)
                it[MailingListTable.mandatory] = mandatory
                it[ruleUpdatedAt] = DbClock.nowLocalDateTime()
            }
        }
        listIds += id
        return id
    }

    fun message(
        listId: Uuid,
        sentBy: Uuid,
        status: MailingMessageStatus = MailingMessageStatus.QUEUED,
        subject: String = "Dyn-Betreff",
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MailingMessageTable.insert {
                it[MailingMessageTable.id] = id
                it[mailingListId] = listId
                it[MailingMessageTable.subject] = subject
                it[bodyText] = "Hallo Welt."
                it[bodyHtml] = null
                it[MailingMessageTable.sentBy] = sentBy
                it[MailingMessageTable.status] = status
                it[queuedAt] = if (status == MailingMessageStatus.QUEUED) DbClock.nowLocalDateTime() else null
            }
        }
        messageIds += id
        return id
    }

    fun optOut(
        listId: Uuid,
        memberId: Uuid,
    ) {
        transaction {
            MailingListOptOutTable.insert {
                it[id] = Uuid.random()
                it[mailingListId] = listId
                it[MailingListOptOutTable.memberId] = memberId
                it[optedOutAt] = DbClock.nowLocalDateTime()
            }
        }
    }

    fun deliveries(messageId: Uuid): Map<Uuid, network.lapis.cloud.shared.domain.DeliveryStatus> =
        transaction {
            MailingDeliveryLogTable
                .selectAll()
                .where { MailingDeliveryLogTable.mailingMessageId eq messageId }
                .associate { it[MailingDeliveryLogTable.memberId] to it[MailingDeliveryLogTable.deliveryStatus] }
        }

    fun messageRow(messageId: Uuid) = transaction { MailingMessageTable.selectAll().where { MailingMessageTable.id eq messageId }.single() }

    fun cleanup() {
        transaction {
            // lists created through the RPC are not in listIds -- find every list a fixture member created
            val allListIds =
                (
                    listIds +
                        MailingListTable.selectAll().where { MailingListTable.createdBy inList memberIds }.map { it[MailingListTable.id] }
                ).distinct()
            val allMessageIds =
                (
                    messageIds +
                        MailingMessageTable
                            .selectAll()
                            .where {
                                MailingMessageTable.mailingListId inList allListIds
                            }.map { it[MailingMessageTable.id] }
                ).distinct()
            MailingDeliveryLogTable.deleteWhere { mailingMessageId inList allMessageIds }
            MailingMessageTable.deleteWhere { MailingMessageTable.id inList allMessageIds }
            MailingListOptOutTable.deleteWhere { mailingListId inList allListIds }
            MailingListOptOutTable.deleteWhere { memberId inList memberIds }
            MailingListTable.deleteWhere { MailingListTable.id inList allListIds }
            CommitteeMembershipTable.deleteWhere { committeeId inList committeeIds }
            CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
            AuditLogEntryTable.deleteWhere { actorMemberId inList memberIds }
            AccountTable.deleteWhere { memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}

internal fun committeeRule(vararg committeeIds: Uuid): String =
    """{"v":1,"op":"ALL","conditions":[{"type":"COMMITTEE","committeeIds":[${committeeIds.joinToString(",") { "\"$it\"" }}]}]}"""
