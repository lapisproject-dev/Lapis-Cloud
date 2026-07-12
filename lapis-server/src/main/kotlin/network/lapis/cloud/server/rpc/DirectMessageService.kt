package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.tables.DirectMessageTable
import network.lapis.cloud.server.db.tables.MemberTable
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.rpc.IDirectMessageService
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Content is only ever visible to sender and recipient — see [IDirectMessageService] KDoc.
 * Every query here filters by (senderId = current OR recipientId = current); there is no
 * separate "board can read all DMs" code path.
 */
class DirectMessageService(
    private val call: ApplicationCall,
) : IDirectMessageService {
    private val senderMember = MemberTable.alias("sender_member")
    private val recipientMember = MemberTable.alias("recipient_member")

    override suspend fun sendDirectMessage(
        recipientId: String,
        body: String,
    ): DirectMessageDto {
        val current = resolveCurrentMember(call)
        val recipient = Uuid.parse(recipientId)
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return transaction {
            val id = Uuid.random()
            DirectMessageTable.insert {
                it[DirectMessageTable.id] = id
                it[senderId] = current.memberId
                it[DirectMessageTable.recipientId] = recipient
                it[DirectMessageTable.body] = body
                it[sentAt] = now
            }
            loadMessage(id)
        }
    }

    override suspend fun listInbox(): List<DirectMessageDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            baseQuery()
                .where { DirectMessageTable.recipientId eq current.memberId }
                .orderBy(DirectMessageTable.sentAt, SortOrder.DESC)
                .map { it.toDirectMessageDto() }
        }
    }

    override suspend fun listConversation(otherMemberId: String): List<DirectMessageDto> {
        val current = resolveCurrentMember(call)
        val other = Uuid.parse(otherMemberId)
        return transaction {
            baseQuery()
                .where {
                    ((DirectMessageTable.senderId eq current.memberId) and (DirectMessageTable.recipientId eq other)) or
                        ((DirectMessageTable.senderId eq other) and (DirectMessageTable.recipientId eq current.memberId))
                }.orderBy(DirectMessageTable.sentAt, SortOrder.DESC)
                .map { it.toDirectMessageDto() }
        }
    }

    override suspend fun markRead(messageId: String) {
        val current = resolveCurrentMember(call)
        val id = Uuid.parse(messageId)
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        transaction {
            DirectMessageTable.update(
                { (DirectMessageTable.id eq id) and (DirectMessageTable.recipientId eq current.memberId) },
            ) {
                it[readAt] = now
            }
        }
    }

    override suspend fun unreadCount(): Int {
        val current = resolveCurrentMember(call)
        return transaction {
            DirectMessageTable
                .selectAll()
                .where { (DirectMessageTable.recipientId eq current.memberId) and (DirectMessageTable.readAt.isNull()) }
                .count()
                .toInt()
        }
    }

    private fun baseQuery() =
        DirectMessageTable
            .join(senderMember, JoinType.INNER, DirectMessageTable.senderId, senderMember[MemberTable.id])
            .join(recipientMember, JoinType.INNER, DirectMessageTable.recipientId, recipientMember[MemberTable.id])
            .selectAll()

    private fun loadMessage(id: Uuid) = baseQuery().where { DirectMessageTable.id eq id }.single().toDirectMessageDto()

    private fun ResultRow.toDirectMessageDto(): DirectMessageDto =
        DirectMessageDto(
            id = this[DirectMessageTable.id].toString(),
            senderId = this[DirectMessageTable.senderId].toString(),
            senderDisplayName = this[senderMember[MemberTable.displayName]],
            recipientId = this[DirectMessageTable.recipientId].toString(),
            recipientDisplayName = this[recipientMember[MemberTable.displayName]],
            body = this[DirectMessageTable.body],
            sentAt = this[DirectMessageTable.sentAt],
            readAt = this[DirectMessageTable.readAt],
        )
}
