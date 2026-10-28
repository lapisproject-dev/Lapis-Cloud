package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.messaging.DirectMessageAttachmentStore
import network.lapis.cloud.server.messaging.DirectMessageBlockStore
import network.lapis.cloud.server.messaging.DirectMessageGroups
import network.lapis.cloud.server.messaging.DirectMessageRateLimiter
import network.lapis.cloud.server.messaging.DirectMessageRecipientSearch
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AttachmentRules
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DirectMessageAttachmentDto
import network.lapis.cloud.shared.domain.DirectMessageBlockDto
import network.lapis.cloud.shared.domain.DirectMessageCursorDto
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.DirectMessageGroupDto
import network.lapis.cloud.shared.domain.DirectMessageGroupSendResultDto
import network.lapis.cloud.shared.domain.DirectMessageGroupSendSnapshot
import network.lapis.cloud.shared.domain.DirectMessagePageDto
import network.lapis.cloud.shared.domain.DirectMessagePartnerDto
import network.lapis.cloud.shared.domain.DirectMessageRecipientDto
import network.lapis.cloud.shared.domain.DirectMessageRules
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.DirectMessageAttachmentsUnavailableException
import network.lapis.cloud.shared.rpc.DirectMessageGroupTooLargeException
import network.lapis.cloud.shared.rpc.DirectMessageGroupUnavailableException
import network.lapis.cloud.shared.rpc.DirectMessageRateLimitedException
import network.lapis.cloud.shared.rpc.IDirectMessageService
import network.lapis.cloud.shared.rpc.NotFoundException
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
 *
 * **V1.9.237 "Neue Nachricht"**: a recipient that cannot be written to (malformed id, unknown, not an organization member, anonymized, blocked
 * in either direction) is ALWAYS one [DirectMessageRecipientUnavailableException] -- no existence or block oracle. Rate limits
 * ([DirectMessageRateLimiter]) are checked OUTSIDE every `transaction {}` (Exposed retries would count twice).
 */
class DirectMessageService(
    private val call: ApplicationCall,
    private val rateLimiter: DirectMessageRateLimiter,
) : IDirectMessageService {
    /** A service with its own, fresh limiter (every limit starts at zero) -- for callers that do not share the application's instance. */
    constructor(call: ApplicationCall) : this(call = call, rateLimiter = DirectMessageRateLimiter())

    private val senderMember = MemberTable.alias("sender_member")
    private val recipientMember = MemberTable.alias("recipient_member")

    override suspend fun sendDirectMessage(
        recipientId: String,
        body: String,
    ): DirectMessageDto {
        val current = resolveCurrentMember(call)
        transaction { requireActiveMembership(memberId = current.memberId) }
        requireSendAllowed(memberId = current.memberId, recipients = 1)
        val recipient = parseRecipientIdOrUnavailable(recipientId)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            insertDirectMessage(senderId = current.memberId, recipientId = recipient, body = body)
        }
    }

    override suspend fun sendDirectMessageWithAttachments(
        recipientId: String,
        body: String,
        attachmentIds: List<String>,
    ): DirectMessageDto {
        val current = resolveCurrentMember(call)
        // Every problem with the attachment list answers with the SAME exception: no hint which id, no existence oracle.
        val unavailable = DirectMessageAttachmentsUnavailableException()
        val ids =
            attachmentIds.map { raw -> runCatching { Uuid.parse(raw) }.getOrElse { throw unavailable } }.also { parsed ->
                if (parsed.size > AttachmentRules.MAX_FILES || parsed.size != parsed.toSet().size) throw unavailable
            }
        // No separate gate transaction here: the send is ONE transaction whose first step is the member-row lock (a tripwire pins that). The
        // limiter is charged before it; a non-member only burns his own budget and is refused inside the transaction.
        requireSendAllowed(memberId = current.memberId, recipients = 1)
        val recipient = parseRecipientIdOrUnavailable(recipientId)
        return transaction {
            // The member-row lock serializes this send against the same member's uploads (they take the same lock first).
            requireActiveMembership(memberId = current.memberId, forUpdate = true)
            val dto = insertDirectMessage(senderId = current.memberId, recipientId = recipient, body = body)
            if (ids.isEmpty()) return@transaction dto
            // A null result rolls the whole transaction back: the message is not created either.
            val attached =
                DirectMessageAttachmentStore.attachPendingInTx(
                    ids = ids,
                    uploader = current.memberId,
                    recipient = recipient,
                    messageId = Uuid.parse(dto.id),
                ) ?: throw unavailable
            dto.copy(
                attachments =
                    attached.map {
                        DirectMessageAttachmentDto(
                            id = it.id.toString(),
                            fileName = it.fileName,
                            mimeType = it.mimeType,
                            sizeBytes = it.sizeBytes,
                        )
                    },
            )
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
                .withAttachments()
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
                .withAttachments()
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
                    .withAttachments()
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

    override suspend fun searchMessageRecipients(query: String): List<DirectMessageRecipientDto> {
        val current = resolveCurrentMember(call)
        transaction { requireActiveMembership(memberId = current.memberId) }
        if (!rateLimiter.checkAndRecordSearch(current.memberId)) throw DirectMessageRateLimitedException()
        return transaction { DirectMessageRecipientSearch.search(callerId = current.memberId, rawQuery = query) }
    }

    override suspend fun listMessageGroups(): List<DirectMessageGroupDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val today = OrganizationTimeZone.today()
            DirectMessageGroups.addressable(current = current, today = today).mapNotNull { row ->
                val recipients = recipientsOrNull(row = row, senderId = current.memberId, today = today) ?: return@mapNotNull null
                DirectMessageGroupDto(
                    committeeId = row[CommitteeTable.id].toString(),
                    name = row[CommitteeTable.name],
                    memberCount = recipients.size,
                )
            }
        }
    }

    override suspend fun sendGroupMessage(
        committeeId: String,
        body: String,
    ): DirectMessageGroupSendResultDto {
        val current = resolveCurrentMember(call)
        // Read phase: nothing is written and the limiter is not charged for a send that cannot happen anyway.
        val (recipientCount, trimmed) =
            transaction {
                requireActiveMembership(memberId = current.memberId)
                val today = OrganizationTimeZone.today()
                val row = DirectMessageGroups.resolveAddressable(current = current, rawId = committeeId, today = today)
                val recipients =
                    recipientsOrNull(row = row, senderId = current.memberId, today = today)
                        ?: throw DirectMessageGroupUnavailableException()
                if (recipients.size > DirectMessageRules.MAX_RECIPIENTS_PER_SEND) throw DirectMessageGroupTooLargeException()
                recipients.size to validateDirectMessageBody(body)
            }
        requireSendAllowed(memberId = current.memberId, recipients = recipientCount)
        return transaction {
            // The member-row lock serializes two parallel group sends of the same sender; the group rule and the recipients are re-read under it.
            requireActiveMembership(memberId = current.memberId, forUpdate = true)
            val today = OrganizationTimeZone.today()
            val row = DirectMessageGroups.resolveAddressable(current = current, rawId = committeeId, today = today)
            val recipients =
                recipientsOrNull(row = row, senderId = current.memberId, today = today) ?: throw DirectMessageGroupUnavailableException()
            if (recipients.size > DirectMessageRules.MAX_RECIPIENTS_PER_SEND) throw DirectMessageGroupTooLargeException()
            val sentAt = DbClock.nowLocalDateTime()
            var delivered = 0
            // Ascending by id: a fixed order, so two overlapping fan-outs cannot deadlock on each other's rows.
            for (recipient in recipients) {
                // A recipient who blocked the sender is skipped silently -- the sender must not learn who blocked him.
                if (directMessageRecipientRefusal(senderId = current.memberId, recipientId = recipient) != null) continue
                insertDirectMessageRow(senderId = current.memberId, recipientId = recipient, trimmedBody = trimmed, sentAt = sentAt)
                delivered++
            }
            // LAST lock-taking step (AuditLogRecorder locks the chain-state row). Count only: never a text, a name or a recipient.
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.DIRECT_MESSAGE_GROUP_SEND,
                entityId = row[CommitteeTable.id],
                action = AuditAction.CREATE,
                after = Json.encodeToString(DirectMessageGroupSendSnapshot(deliveredCount = delivered)),
                occurredAt = sentAt,
            )
            DirectMessageGroupSendResultDto(committeeName = row[CommitteeTable.name], sentAt = sentAt)
        }
    }

    override suspend fun blockMessageSender(memberId: String) {
        val current = resolveCurrentMember(call)
        transaction { requireActiveMembership(memberId = current.memberId) }
        if (!rateLimiter.checkAndRecordBlock(current.memberId)) throw DirectMessageRateLimitedException()
        // A malformed or unknown id is a silent no-op: the answer is always Unit, so the call is no existence probe.
        val target = runCatching { Uuid.parse(memberId) }.getOrNull() ?: return
        val now = DbClock.nowLocalDateTime()
        transaction {
            requireActiveMembership(memberId = current.memberId)
            if (MemberTable.selectAll().where { MemberTable.id eq target }.any()) {
                DirectMessageBlockStore.block(blocker = current.memberId, blocked = target, now = now)
            }
        }
    }

    override suspend fun getRecipientSearchVisibility(): Boolean {
        val current = resolveCurrentMember(call)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            MemberTable
                .select(MemberTable.dmDiscoverable)
                .where { MemberTable.id eq current.memberId }
                .single()[MemberTable.dmDiscoverable]
        }
    }

    override suspend fun setRecipientSearchVisibility(visible: Boolean) {
        val current = resolveCurrentMember(call)
        transaction {
            requireActiveMembership(memberId = current.memberId, forUpdate = true)
            MemberTable.update({ MemberTable.id eq current.memberId }) { it[dmDiscoverable] = visible }
        }
    }

    override suspend fun listMessageBlocks(): List<DirectMessageBlockDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val blocks = DirectMessageBlockStore.ownBlocks(blocker = current.memberId, limit = DirectMessageRules.MAX_LISTED_BLOCKS)
            if (blocks.isEmpty()) return@transaction emptyList()
            val blockedIds = blocks.map { it.blockedMemberId }
            // A name is revealed only where the caller already has a conversation with that member: blocks that came from finder requests
            // (an Art. 9 context) must not reveal any identity.
            val known = HashSet<Uuid>()
            DirectMessageTable
                .select(DirectMessageTable.recipientId)
                .where { (DirectMessageTable.senderId eq current.memberId) and (DirectMessageTable.recipientId inList blockedIds) }
                .forEach { known += it[DirectMessageTable.recipientId] }
            DirectMessageTable
                .select(DirectMessageTable.senderId)
                .where { (DirectMessageTable.recipientId eq current.memberId) and (DirectMessageTable.senderId inList blockedIds) }
                .forEach { known += it[DirectMessageTable.senderId] }
            val names =
                if (known.isEmpty()) {
                    emptyMap()
                } else {
                    MemberTable
                        .select(MemberTable.id, MemberTable.displayName)
                        .where { MemberTable.id inList known }
                        .associate { it[MemberTable.id] to it[MemberTable.displayName] }
                }
            blocks.map {
                DirectMessageBlockDto(
                    blockId = it.id.toString(),
                    displayName = names[it.blockedMemberId].orEmpty(),
                    createdAt = it.createdAt,
                )
            }
        }
    }

    override suspend fun unblockMessageSender(blockId: String) {
        val current = resolveCurrentMember(call)
        val id = runCatching { Uuid.parse(blockId) }.getOrElse { throw NotFoundException("Not found") }
        transaction {
            requireActiveMembership(memberId = current.memberId)
            if (!DirectMessageBlockStore.unblock(id = id, blocker = current.memberId)) throw NotFoundException("Not found")
        }
    }

    /** Charges the send limiter; OUTSIDE every transaction. */
    private fun requireSendAllowed(
        memberId: Uuid,
        recipients: Int,
    ) {
        if (!rateLimiter.checkAndRecordSend(memberId = memberId, recipients = recipients)) throw DirectMessageRateLimitedException()
    }

    /** `null` for a committee whose stored rule no longer parses (it is then neither listed nor addressable). */
    private fun recipientsOrNull(
        row: ResultRow,
        senderId: Uuid,
        today: LocalDate,
    ): List<Uuid>? =
        try {
            DirectMessageGroups.recipientsOf(row = row, senderId = senderId, today = today)
        } catch (e: ConflictException) {
            null
        }

    /** V1.9.233 -- fills [DirectMessageDto.attachments] for a whole page with ONE query (no N+1). Inside the caller's transaction. */
    private fun List<DirectMessageDto>.withAttachments(): List<DirectMessageDto> {
        if (isEmpty()) return this
        val byMessage = DirectMessageAttachmentStore.listForMessages(map { Uuid.parse(it.id) })
        if (byMessage.isEmpty()) return this
        return map { message -> byMessage[Uuid.parse(message.id)]?.let { message.copy(attachments = it) } ?: message }
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
