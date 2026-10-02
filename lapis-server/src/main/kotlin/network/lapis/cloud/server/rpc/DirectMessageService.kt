package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.DirectMessageCursorDto
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.DirectMessagePageDto
import network.lapis.cloud.shared.domain.DirectMessagePartnerDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.IDirectMessageService
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** Welle V1.9.34 -- upper bound of [DirectMessageService.listConversation]: the newest 200 messages of one conversation. */
internal const val MAX_CONVERSATION_MESSAGES = 200

/** Welle V1.9.36 -- upper bound of [DirectMessageService.listConversationPartners]. */
internal const val MAX_CONVERSATION_PARTNERS = 100

/** Welle V1.9.36 -- upper bound of one [DirectMessageService.listConversationPage] page. */
internal const val MAX_CONVERSATION_PAGE = 100

/**
 * Content is only ever visible to sender and recipient — see [IDirectMessageService] KDoc.
 * Every query here filters by (senderId = current OR recipientId = current); there is no
 * separate "board can read all DMs" code path.
 *
 * **V0.11.0 security fix**: every method here previously had NO membership-status gate at all --
 * any authenticated caller (including [network.lapis.cloud.shared.domain.MemberStatus.APPLICATION]
 * and, once FRIEND self-registration shipped, an unverified [network.lapis.cloud.shared.domain
 * .MemberStatus.FRIEND]) could DM any member id, unthrottled. Every method now calls
 * [requireActiveMembership] first -- a FRIEND has no legitimate DM need under this wave's scope
 * (conference access only). This was the highest-priority item on the FRIEND-wave risk list found
 * while planning it, and predates FRIEND entirely (it applied to APPLICATION/GUEST before too).
 *
 * **V1.9.36**: [listConversationPartners], [listConversationPage] and [markConversationRead] only ever see the caller's own
 * conversations (every query is anchored on the caller's id), carry no message text in the partner list, and give no existence
 * oracle: an unknown or foreign member id yields an empty page / 0 rather than an error; a malformed id yields a uniform
 * [BadRequestException] that does not echo the input.
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
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            insertDirectMessage(senderId = current.memberId, recipientId = recipient, body = body)
        }
    }

    override suspend fun listInbox(): List<DirectMessageDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
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
            requireActiveMembership(memberId = current.memberId)
            baseQuery()
                .where {
                    ((DirectMessageTable.senderId eq current.memberId) and (DirectMessageTable.recipientId eq other)) or
                        ((DirectMessageTable.senderId eq other) and (DirectMessageTable.recipientId eq current.memberId))
                }.orderBy(DirectMessageTable.sentAt, SortOrder.DESC)
                .limit(MAX_CONVERSATION_MESSAGES)
                .map { it.toDirectMessageDto() }
        }
    }

    override suspend fun markRead(messageId: String) {
        val current = resolveCurrentMember(call)
        val id = Uuid.parse(messageId)
        val now = DbClock.nowLocalDateTime()
        transaction {
            requireActiveMembership(memberId = current.memberId)
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
            requireActiveMembership(memberId = current.memberId)
            DirectMessageTable
                .selectAll()
                .where { (DirectMessageTable.recipientId eq current.memberId) and (DirectMessageTable.readAt.isNull()) }
                .count()
                .toInt()
        }
    }

    override suspend fun listConversationPartners(limit: Int): List<DirectMessagePartnerDto> {
        val current = resolveCurrentMember(call)
        val me = current.memberId
        val n = limit.coerceIn(1, MAX_CONVERSATION_PARTNERS)
        return transaction {
            requireActiveMembership(memberId = me)
            val lastActivity = HashMap<Uuid, LocalDateTime>()
            val unread = HashMap<Uuid, Int>()
            val lastSent = DirectMessageTable.sentAt.max()
            DirectMessageTable
                .select(DirectMessageTable.recipientId, lastSent)
                .where { DirectMessageTable.senderId eq me }
                .groupBy(DirectMessageTable.recipientId)
                .forEach { row -> row[lastSent]?.let { lastActivity.merge(row[DirectMessageTable.recipientId], it, ::laterOf) } }
            val lastReceived = DirectMessageTable.sentAt.max()
            DirectMessageTable
                .select(DirectMessageTable.senderId, lastReceived)
                .where { DirectMessageTable.recipientId eq me }
                .groupBy(DirectMessageTable.senderId)
                .forEach { row -> row[lastReceived]?.let { lastActivity.merge(row[DirectMessageTable.senderId], it, ::laterOf) } }
            val unreadCount = DirectMessageTable.id.count()
            DirectMessageTable
                .select(DirectMessageTable.senderId, unreadCount)
                .where { (DirectMessageTable.recipientId eq me) and DirectMessageTable.readAt.isNull() }
                .groupBy(DirectMessageTable.senderId)
                .forEach { row -> unread[row[DirectMessageTable.senderId]] = row[unreadCount].toInt() }
            // Messages to oneself (possible before V1.9.12) are not a conversation.
            val top =
                lastActivity.entries
                    .filter { it.key != me }
                    .sortedWith(compareByDescending<Map.Entry<Uuid, LocalDateTime>> { it.value }.thenBy { it.key.toString() })
                    .take(n)
            if (top.isEmpty()) return@transaction emptyList()
            val names =
                MemberTable
                    .select(MemberTable.id, MemberTable.displayName)
                    .where { MemberTable.id inList top.map { it.key } }
                    .associate { it[MemberTable.id] to it[MemberTable.displayName] }
            top.mapNotNull { (partnerId, at) ->
                names[partnerId]?.let { name ->
                    DirectMessagePartnerDto(
                        partnerId = partnerId.toString(),
                        partnerDisplayName = name,
                        lastActivityAt = at,
                        unreadCount = unread[partnerId] ?: 0,
                    )
                }
            }
        }
    }

    override suspend fun listConversationPage(
        otherMemberId: String,
        beforeSentAt: LocalDateTime?,
        beforeId: String?,
        limit: Int,
    ): DirectMessagePageDto {
        val current = resolveCurrentMember(call)
        if ((beforeSentAt == null) != (beforeId == null)) throw BadRequestException("Invalid cursor")
        val cursorId = beforeId?.let { parseUuidOrBadRequest(raw = it, message = "Invalid cursor") }
        val other = parseUuidOrBadRequest(raw = otherMemberId, message = "Invalid member id")
        val n = limit.coerceIn(1, MAX_CONVERSATION_PAGE)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            if (other ==
                current.memberId
            ) {
                return@transaction DirectMessagePageDto(messages = emptyList(), hasMore = false, nextCursor = null)
            }
            val rows =
                baseQuery()
                    .where {
                        val pair =
                            ((DirectMessageTable.senderId eq current.memberId) and (DirectMessageTable.recipientId eq other)) or
                                ((DirectMessageTable.senderId eq other) and (DirectMessageTable.recipientId eq current.memberId))
                        if (beforeSentAt != null && cursorId != null) {
                            pair and
                                (
                                    (DirectMessageTable.sentAt less beforeSentAt) or
                                        ((DirectMessageTable.sentAt eq beforeSentAt) and (DirectMessageTable.id less cursorId))
                                )
                        } else {
                            pair
                        }
                    }.orderBy(DirectMessageTable.sentAt to SortOrder.DESC, DirectMessageTable.id to SortOrder.DESC)
                    .limit(n + 1)
                    .map { it.toDirectMessageDto() }
            val page = rows.take(n)
            DirectMessagePageDto(
                messages = page,
                hasMore = rows.size > n,
                nextCursor = page.lastOrNull()?.let { DirectMessageCursorDto(sentAt = it.sentAt, id = it.id) },
            )
        }
    }

    override suspend fun markConversationRead(otherMemberId: String): Int {
        val current = resolveCurrentMember(call)
        val other = parseUuidOrBadRequest(raw = otherMemberId, message = "Invalid member id")
        val now = DbClock.nowLocalDateTime()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            DirectMessageTable.update(
                {
                    (DirectMessageTable.recipientId eq current.memberId) and
                        (DirectMessageTable.senderId eq other) and
                        DirectMessageTable.readAt.isNull()
                },
            ) {
                it[readAt] = now
            }
        }
    }

    private fun laterOf(
        a: LocalDateTime,
        b: LocalDateTime,
    ): LocalDateTime = if (a >= b) a else b

    /** The message never echoes the input. */
    private fun parseUuidOrBadRequest(
        raw: String,
        message: String,
    ): Uuid = runCatching { Uuid.parse(raw) }.getOrElse { throw BadRequestException(message) }

    private fun baseQuery() =
        DirectMessageTable
            .join(senderMember, JoinType.INNER, DirectMessageTable.senderId, senderMember[MemberTable.id])
            .join(recipientMember, JoinType.INNER, DirectMessageTable.recipientId, recipientMember[MemberTable.id])
            .selectAll()

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
