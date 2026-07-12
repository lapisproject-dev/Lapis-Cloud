package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.MailingListSubscriptionDto
import network.lapis.cloud.shared.domain.MailingMessageDto

@RpcService
interface IMailingService {
    suspend fun listMailingLists(): List<MailingListDto>

    /** Role: Board/Admin. */
    suspend fun createMailingList(
        name: String,
        description: String? = null,
    ): MailingListDto

    /** Always self-service, immediately effective, no confirmation dark pattern. */
    suspend fun subscribe(mailingListId: String)

    /** Always self-service, immediately effective. */
    suspend fun unsubscribe(mailingListId: String)

    /** Role: Board/Admin. */
    suspend fun adminSubscribeMember(
        mailingListId: String,
        memberId: String,
    )

    /** Role: Board/Admin. */
    suspend fun listSubscribers(mailingListId: String): List<MailingListSubscriptionDto>

    /** Role: Board/Admin. */
    suspend fun createDraftMessage(
        mailingListId: String,
        subject: String,
        bodyText: String,
    ): MailingMessageDto

    /** Role: Board/Admin. */
    suspend fun listMailingMessages(mailingListId: String): List<MailingMessageDto>

    /**
     * Synchronous send loop over active (non-unsubscribed) subscribers, writing one
     * [network.lapis.cloud.shared.domain.MailingDeliveryLogDto] row per recipient. No
     * queue/campaign engine and no retries beyond a single per-recipient try/catch — that is
     * explicitly out of scope for this wave. Role: Board/Admin.
     */
    suspend fun sendMailingMessage(messageId: String): MailingMessageDto
}
