package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
enum class MailingMessageStatus { DRAFT, QUEUED, SENT, FAILED }

@Serializable
enum class DeliveryStatus { SENT, BOUNCED, SKIPPED_UNSUBSCRIBED }

@Serializable
data class MailingListDto(
    val id: String,
    val name: String,
    val description: String?,
    val createdBy: String,
    val subscriberCount: Int,
    val isSubscribedByCurrentMember: Boolean,
)

@Serializable
data class MailingListSubscriptionDto(
    val id: String,
    val mailingListId: String,
    val memberId: String,
    val memberDisplayName: String,
    val subscribedAt: LocalDateTime,
    val unsubscribedAt: LocalDateTime?,
)

@Serializable
data class MailingMessageDto(
    val id: String,
    val mailingListId: String,
    val subject: String,
    val bodyText: String,
    val sentBy: String,
    val sentAt: LocalDateTime?,
    val status: MailingMessageStatus,
)

@Serializable
data class MailingDeliveryLogDto(
    val id: String,
    val mailingMessageId: String,
    val memberId: String,
    val deliveredAt: LocalDateTime,
    val deliveryStatus: DeliveryStatus,
)

@Serializable
data class DirectMessageDto(
    val id: String,
    val senderId: String,
    val senderDisplayName: String,
    val recipientId: String,
    val recipientDisplayName: String,
    val body: String,
    val sentAt: LocalDateTime,
    val readAt: LocalDateTime?,
)
