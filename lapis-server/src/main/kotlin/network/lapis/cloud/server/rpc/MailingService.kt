package network.lapis.cloud.server.rpc

import dev.kilua.rpc.AbstractServiceException
import dev.kilua.rpc.annotations.RpcServiceException
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.tables.MailingDeliveryLogTable
import network.lapis.cloud.server.db.tables.MailingListSubscriptionTable
import network.lapis.cloud.server.db.tables.MailingListTable
import network.lapis.cloud.server.db.tables.MailingMessageTable
import network.lapis.cloud.server.db.tables.MemberTable
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.MailingListSubscriptionDto
import network.lapis.cloud.shared.domain.MailingMessageDto
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.rpc.IMailingService
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val BOARD_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

@RpcServiceException
class ConflictException(
    override val message: String,
) : AbstractServiceException()

class MailingService(
    private val call: ApplicationCall,
) : IMailingService {
    override suspend fun listMailingLists(): List<MailingListDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            MailingListTable.selectAll().map { row ->
                val listId = row[MailingListTable.id]
                val subscriberCount =
                    MailingListSubscriptionTable
                        .selectAll()
                        .where {
                            (MailingListSubscriptionTable.mailingListId eq listId) and
                                (MailingListSubscriptionTable.unsubscribedAt.isNull())
                        }.count()
                val isSubscribed =
                    MailingListSubscriptionTable
                        .selectAll()
                        .where {
                            (MailingListSubscriptionTable.mailingListId eq listId) and
                                (MailingListSubscriptionTable.memberId eq current.memberId) and
                                (MailingListSubscriptionTable.unsubscribedAt.isNull())
                        }.count() > 0
                row.toMailingListDto(subscriberCount.toInt(), isSubscribed)
            }
        }
    }

    override suspend fun createMailingList(
        name: String,
        description: String?,
    ): MailingListDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        return transaction {
            val id = Uuid.random()
            MailingListTable.insert {
                it[MailingListTable.id] = id
                it[MailingListTable.name] = name
                it[MailingListTable.description] = description
                it[createdBy] = current.memberId
            }
            MailingListTable
                .selectAll()
                .where { MailingListTable.id eq id }
                .single()
                .toMailingListDto(0, false)
        }
    }

    override suspend fun subscribe(mailingListId: String) {
        val current = resolveCurrentMember(call)
        val listId = Uuid.parse(mailingListId)
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        transaction {
            val existing =
                MailingListSubscriptionTable
                    .selectAll()
                    .where {
                        (MailingListSubscriptionTable.mailingListId eq listId) and
                            (MailingListSubscriptionTable.memberId eq current.memberId)
                    }.singleOrNull()
            if (existing == null) {
                MailingListSubscriptionTable.insert {
                    it[id] = Uuid.random()
                    it[MailingListSubscriptionTable.mailingListId] = listId
                    it[memberId] = current.memberId
                    it[subscribedAt] = now
                }
            } else if (existing[MailingListSubscriptionTable.unsubscribedAt] != null) {
                MailingListSubscriptionTable.update({ MailingListSubscriptionTable.id eq existing[MailingListSubscriptionTable.id] }) {
                    it[unsubscribedAt] = null
                    it[subscribedAt] = now
                }
            }
        }
    }

    override suspend fun unsubscribe(mailingListId: String) {
        val current = resolveCurrentMember(call)
        val listId = Uuid.parse(mailingListId)
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        transaction {
            MailingListSubscriptionTable.update(
                {
                    (MailingListSubscriptionTable.mailingListId eq listId) and
                        (MailingListSubscriptionTable.memberId eq current.memberId)
                },
            ) {
                it[unsubscribedAt] = now
            }
        }
    }

    override suspend fun adminSubscribeMember(
        mailingListId: String,
        memberId: String,
    ) {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val listId = Uuid.parse(mailingListId)
        val targetMemberId = Uuid.parse(memberId)
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        transaction {
            val existing =
                MailingListSubscriptionTable
                    .selectAll()
                    .where {
                        (MailingListSubscriptionTable.mailingListId eq listId) and
                            (MailingListSubscriptionTable.memberId eq targetMemberId)
                    }.singleOrNull()
            if (existing == null) {
                MailingListSubscriptionTable.insert {
                    it[id] = Uuid.random()
                    it[MailingListSubscriptionTable.mailingListId] = listId
                    it[MailingListSubscriptionTable.memberId] = targetMemberId
                    it[subscribedAt] = now
                }
            }
        }
    }

    override suspend fun listSubscribers(mailingListId: String): List<MailingListSubscriptionDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val listId = Uuid.parse(mailingListId)
        return transaction {
            (MailingListSubscriptionTable innerJoin MemberTable)
                .selectAll()
                .where { MailingListSubscriptionTable.mailingListId eq listId }
                .map { it.toMailingListSubscriptionDto() }
        }
    }

    override suspend fun createDraftMessage(
        mailingListId: String,
        subject: String,
        bodyText: String,
    ): MailingMessageDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        return transaction {
            val id = Uuid.random()
            MailingMessageTable.insert {
                it[MailingMessageTable.id] = id
                it[MailingMessageTable.mailingListId] = Uuid.parse(mailingListId)
                it[MailingMessageTable.subject] = subject
                it[MailingMessageTable.bodyText] = bodyText
                it[sentBy] = current.memberId
                it[status] = MailingMessageStatus.DRAFT
            }
            MailingMessageTable
                .selectAll()
                .where { MailingMessageTable.id eq id }
                .single()
                .toMailingMessageDto()
        }
    }

    override suspend fun listMailingMessages(mailingListId: String): List<MailingMessageDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val listId = Uuid.parse(mailingListId)
        return transaction {
            MailingMessageTable.selectAll().where { MailingMessageTable.mailingListId eq listId }.map { it.toMailingMessageDto() }
        }
    }

    override suspend fun sendMailingMessage(messageId: String): MailingMessageDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val id = Uuid.parse(messageId)
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return transaction {
            val message =
                MailingMessageTable.selectAll().where { MailingMessageTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("MailingMessage $messageId not found")
            val currentStatus = message[MailingMessageTable.status]
            if (currentStatus != MailingMessageStatus.DRAFT && currentStatus != MailingMessageStatus.QUEUED) {
                throw ConflictException("MailingMessage $messageId was already sent (status=$currentStatus)")
            }
            val listId = message[MailingMessageTable.mailingListId]

            val activeSubscribers =
                MailingListSubscriptionTable
                    .selectAll()
                    .where {
                        (MailingListSubscriptionTable.mailingListId eq listId) and
                            (MailingListSubscriptionTable.unsubscribedAt.isNull())
                    }.map { it[MailingListSubscriptionTable.memberId] }

            activeSubscribers.forEach { subscriberId ->
                val deliveryStatus =
                    runCatching {
                        // Basis: kein echter Versand-Anbieter angebunden — die eigentliche
                        // Zustellung ist ausserhalb des Scopes dieser Welle. Simuliert hier nur
                        // den Erfolgsfall pro Empfänger mit eigenem try/catch (kein
                        // Massen-Retry).
                        DeliveryStatus.SENT
                    }.getOrElse { DeliveryStatus.BOUNCED }
                MailingDeliveryLogTable.insert {
                    it[MailingDeliveryLogTable.id] = Uuid.random()
                    it[MailingDeliveryLogTable.mailingMessageId] = id
                    it[MailingDeliveryLogTable.memberId] = subscriberId
                    it[MailingDeliveryLogTable.deliveredAt] = now
                    it[MailingDeliveryLogTable.deliveryStatus] = deliveryStatus
                }
            }

            MailingMessageTable.update({ MailingMessageTable.id eq id }) {
                it[status] = MailingMessageStatus.SENT
                it[sentAt] = now
            }
            MailingMessageTable
                .selectAll()
                .where { MailingMessageTable.id eq id }
                .single()
                .toMailingMessageDto()
        }
    }
}

private fun ResultRow.toMailingListDto(
    subscriberCount: Int,
    isSubscribed: Boolean,
): MailingListDto =
    MailingListDto(
        id = this[MailingListTable.id].toString(),
        name = this[MailingListTable.name],
        description = this[MailingListTable.description],
        createdBy = this[MailingListTable.createdBy].toString(),
        subscriberCount = subscriberCount,
        isSubscribedByCurrentMember = isSubscribed,
    )

private fun ResultRow.toMailingListSubscriptionDto(): MailingListSubscriptionDto =
    MailingListSubscriptionDto(
        id = this[MailingListSubscriptionTable.id].toString(),
        mailingListId = this[MailingListSubscriptionTable.mailingListId].toString(),
        memberId = this[MailingListSubscriptionTable.memberId].toString(),
        memberDisplayName = this[MemberTable.displayName],
        subscribedAt = this[MailingListSubscriptionTable.subscribedAt],
        unsubscribedAt = this[MailingListSubscriptionTable.unsubscribedAt],
    )

private fun ResultRow.toMailingMessageDto(): MailingMessageDto =
    MailingMessageDto(
        id = this[MailingMessageTable.id].toString(),
        mailingListId = this[MailingMessageTable.mailingListId].toString(),
        subject = this[MailingMessageTable.subject],
        bodyText = this[MailingMessageTable.bodyText],
        sentBy = this[MailingMessageTable.sentBy].toString(),
        sentAt = this[MailingMessageTable.sentAt],
        status = this[MailingMessageTable.status],
    )
