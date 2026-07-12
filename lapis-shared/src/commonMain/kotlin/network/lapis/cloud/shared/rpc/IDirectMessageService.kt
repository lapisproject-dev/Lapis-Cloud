package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.DirectMessageDto

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
}
