package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.DirectMessagePageDto
import network.lapis.cloud.shared.domain.DirectMessagePartnerDto

/**
 * Flat 1:1 messages, no threads/attachments in this wave. "Conversation" is derived
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
}
