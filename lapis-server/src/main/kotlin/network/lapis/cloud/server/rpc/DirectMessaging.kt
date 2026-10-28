package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.memberfinder.MemberFinderBlockStore
import network.lapis.cloud.server.messaging.DirectMessageBlockStore
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.DirectMessageRecipientUnavailableException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

internal const val DIRECT_MESSAGE_MAX_BODY_LENGTH = 10_000

/**
 * Why [directMessageRecipientRefusal] refused a recipient. Since V1.9.237 an unknown, unreceivable, anonymized and blocked recipient are ONE
 * case ([UNAVAILABLE]) -- the sender must not be able to tell them apart (no existence / block oracle). The upload route maps both to its
 * own uniform answer.
 */
internal enum class DirectMessageRecipientRefusal { SELF, UNAVAILABLE }

/**
 * V1.9.233 / V1.9.237 -- the recipient rule of [insertDirectMessage], shared with the attachment upload route and the group send: `null` means
 * the sender may write to the recipient; otherwise the reason. The recipient must exist, be an organization member
 * ([MemberStatusSets.ORGANIZATION_MEMBER]), not be anonymized and must not have blocked the sender (direct-message block, or finder block in
 * EITHER direction); writing to oneself is refused. Inside a transaction.
 *
 * The member lookup and BOTH block lookups always run, even when the answer is already settled: equal work for every refusal reason, so a
 * response time does not tell "unknown" from "blocked".
 */
internal fun directMessageRecipientRefusal(
    senderId: Uuid,
    recipientId: Uuid,
): DirectMessageRecipientRefusal? {
    if (senderId == recipientId) return DirectMessageRecipientRefusal.SELF
    val recipientRow =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq recipientId }
            .singleOrNull()
    val blocked = DirectMessageBlockStore.isBlockedEitherWay(a = senderId, b = recipientId)
    val finderBlocked = recipientId in MemberFinderBlockStore.blockedEitherWay(senderId)
    val receivable =
        recipientRow != null &&
            recipientRow[MemberTable.anonymizedAt] == null &&
            recipientRow[MemberTable.status] in MemberStatusSets.ORGANIZATION_MEMBER
    return if (receivable && !blocked && !finderBlocked) null else DirectMessageRecipientRefusal.UNAVAILABLE
}

/** V1.9.237 -- a malformed recipient id is answered exactly like an unknown one. The message never echoes the input. */
internal fun parseRecipientIdOrUnavailable(raw: String): Uuid =
    runCatching { Uuid.parse(raw) }.getOrElse { throw DirectMessageRecipientUnavailableException() }

/** V1.9.237 -- the body rule, once: 1..[DIRECT_MESSAGE_MAX_BODY_LENGTH] characters after trimming. Returns the trimmed text. */
internal fun validateDirectMessageBody(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty() || trimmed.length > DIRECT_MESSAGE_MAX_BODY_LENGTH) {
        throw ConflictException("Message body must be 1-$DIRECT_MESSAGE_MAX_BODY_LENGTH characters")
    }
    return trimmed
}

/** V1.9.237 -- writes the row only; the caller has validated body and recipient. Inside a transaction. */
internal fun insertDirectMessageRow(
    senderId: Uuid,
    recipientId: Uuid,
    trimmedBody: String,
    sentAt: LocalDateTime,
): Uuid {
    val id = Uuid.random()
    DirectMessageTable.insert {
        it[DirectMessageTable.id] = id
        it[DirectMessageTable.senderId] = senderId
        it[DirectMessageTable.recipientId] = recipientId
        it[DirectMessageTable.body] = trimmedBody
        it[DirectMessageTable.sentAt] = sentAt
    }
    return id
}

/**
 * Common insert path of [DirectMessageService.sendDirectMessage], [DirectMessageService.sendDirectMessageWithAttachments],
 * [CarpoolService.contactAuthor], [network.lapis.cloud.server.rpc.MemberFinderService.contactConnection] and the event registration
 * notifier. Must run inside the caller's open `transaction {}`.
 *
 * Checks: body 1..[DIRECT_MESSAGE_MAX_BODY_LENGTH] characters ([ConflictException]); no message to oneself ([ConflictException]); the recipient
 * rule of [directMessageRecipientRefusal] -- unknown, unreceivable, anonymized and BLOCKED all give the same
 * [DirectMessageRecipientUnavailableException] (V1.9.237; before, "unknown" echoed the id in a [network.lapis.cloud.shared.rpc.NotFoundException]).
 *
 * The block applies to ALL five entry points. The event registration notifier stays silent on refusal (it swallows every exception and logs only
 * the class name): a block therefore also suppresses the registration message to the event creator -- the email notice still goes out.
 */
internal fun insertDirectMessage(
    senderId: Uuid,
    recipientId: Uuid,
    body: String,
    sentAt: LocalDateTime = DbClock.nowLocalDateTime(),
): DirectMessageDto {
    val trimmed = validateDirectMessageBody(body)
    when (directMessageRecipientRefusal(senderId = senderId, recipientId = recipientId)) {
        DirectMessageRecipientRefusal.SELF -> throw ConflictException("Cannot send a direct message to yourself")
        DirectMessageRecipientRefusal.UNAVAILABLE -> throw DirectMessageRecipientUnavailableException()
        null -> Unit
    }
    val id = insertDirectMessageRow(senderId = senderId, recipientId = recipientId, trimmedBody = trimmed, sentAt = sentAt)
    val senderMember = MemberTable.alias("sender_member_dm")
    val recipientMember = MemberTable.alias("recipient_member_dm")
    val row =
        DirectMessageTable
            .join(senderMember, JoinType.INNER, DirectMessageTable.senderId, senderMember[MemberTable.id])
            .join(recipientMember, JoinType.INNER, DirectMessageTable.recipientId, recipientMember[MemberTable.id])
            .selectAll()
            .where { DirectMessageTable.id eq id }
            .single()
    return DirectMessageDto(
        id = row[DirectMessageTable.id].toString(),
        senderId = row[DirectMessageTable.senderId].toString(),
        senderDisplayName = row[senderMember[MemberTable.displayName]],
        recipientId = row[DirectMessageTable.recipientId].toString(),
        recipientDisplayName = row[recipientMember[MemberTable.displayName]],
        body = row[DirectMessageTable.body],
        sentAt = row[DirectMessageTable.sentAt],
        readAt = row[DirectMessageTable.readAt],
    )
}
