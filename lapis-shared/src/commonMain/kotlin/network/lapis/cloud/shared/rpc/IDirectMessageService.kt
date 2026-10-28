package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DirectMessageBlockDto
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.DirectMessageGroupDto
import network.lapis.cloud.shared.domain.DirectMessageGroupSendResultDto
import network.lapis.cloud.shared.domain.DirectMessagePageDto
import network.lapis.cloud.shared.domain.DirectMessagePartnerDto
import network.lapis.cloud.shared.domain.DirectMessageRecipientDto

/**
 * Flat 1:1 messages, no threads. Since V1.9.233 a message can carry up to 5 attached files ([sendDirectMessageWithAttachments]). "Conversation" is derived
 * client-side by sorting (sender, recipient) pairs by `sentAt`. Content is only ever visible
 * to sender and recipient — no blanket board access to message bodies (content-privacy on top
 * of the pseudonymity principle from the wider concept, which is primarily about identity).
 */
@RpcService
interface IDirectMessageService {
    suspend fun sendDirectMessage(
        recipientId: String,
        body: String,
    ): DirectMessageDto

    /** Newest first. */
    suspend fun listInbox(): List<DirectMessageDto>

    /** Newest first. */
    suspend fun listConversation(otherMemberId: String): List<DirectMessageDto>

    suspend fun markRead(messageId: String)

    suspend fun unreadCount(): Int

    /** V1.9.36 -- own conversations only, newest activity first (lastActivityAt DESC, partnerId ASC), limit clamped 1..100. No message text. */
    suspend fun listConversationPartners(limit: Int): List<DirectMessagePartnerDto>

    /**
     * V1.9.36 -- keyset page, newest first (sentAt DESC, id DESC). beforeSentAt/beforeId both null (first page) or both set.
     * limit clamped 1..100. The cursor is flat (two nullable primitives) on purpose.
     */
    suspend fun listConversationPage(
        otherMemberId: String,
        beforeSentAt: LocalDateTime?,
        beforeId: String?,
        limit: Int,
    ): DirectMessagePageDto

    /** V1.9.36 -- marks every unread message FROM otherMemberId TO the caller as read in one UPDATE; returns the count. Unknown/foreign id -> 0. */
    suspend fun markConversationRead(otherMemberId: String): Int

    /**
     * V1.9.233 -- like [sendDirectMessage], plus the caller's own PENDING uploads for this recipient (0..5 ids, uploaded over
     * `POST /api/direct-messages/attachments`). The text stays mandatory. Atomic: the message and the assignment of the files happen in
     * one transaction; any foreign/unknown/already-attached/expired/other-recipient id fails the WHOLE send with ONE uniform
     * [network.lapis.cloud.shared.rpc.DirectMessageAttachmentsUnavailableException] (no hint which id, no existence oracle) and no
     * message is created. A bad body or a send to oneself stays a plain [network.lapis.cloud.shared.rpc.ConflictException]; since V1.9.237 a
     * recipient that cannot be written to (unknown, malformed, unreceivable, blocked) is ONE uniform [DirectMessageRecipientUnavailableException]. An empty list behaves exactly like [sendDirectMessage]. Used for new
     * messages and replies alike.
     */
    suspend fun sendDirectMessageWithAttachments(
        recipientId: String,
        body: String,
        attachmentIds: List<String>,
    ): DirectMessageDto

    /**
     * V1.9.237 -- name search for "Neue Nachricht". Matches display names only; fewer than 3 or more than 60 characters answer an empty list.
     * At most 10 hits, ordered by name then id. Hidden (see [setRecipientSearchVisibility]), blocked (either way, DM or finder), anonymized and
     * non-member people never appear, and a hidden person looks exactly like a name that does not exist.
     */
    suspend fun searchMessageRecipients(query: String): List<DirectMessageRecipientDto>

    /** V1.9.237 -- the committees the caller may write to (members of it; BOARD/ADMIN: all active ones except the general assembly). */
    suspend fun listMessageGroups(): List<DirectMessageGroupDto>

    /**
     * V1.9.237 -- one individual message per current member of the committee (sender excluded), text only. Atomic. More than 50 recipients ->
     * [DirectMessageGroupTooLargeException]; every unknown/foreign/inactive group -> [DirectMessageGroupUnavailableException]. Recipients that
     * blocked the sender are skipped silently.
     */
    suspend fun sendGroupMessage(
        committeeId: String,
        body: String,
    ): DirectMessageGroupSendResultDto

    /** V1.9.237 -- blocks [memberId] from writing to the caller (idempotent; an unknown id is a silent no-op). */
    suspend fun blockMessageSender(memberId: String)

    /** V1.9.237 -- whether the caller shows up in the recipient search (default true). */
    suspend fun getRecipientSearchVisibility(): Boolean

    suspend fun setRecipientSearchVisibility(visible: Boolean)

    /** V1.9.237 -- the caller's own blocks, newest first. A name is filled in only where a conversation exists, otherwise it is empty. */
    suspend fun listMessageBlocks(): List<DirectMessageBlockDto>

    /** V1.9.237 -- lifts one of the caller's own blocks; a foreign or unknown id is a uniform [NotFoundException]. */
    suspend fun unblockMessageSender(blockId: String)
}
