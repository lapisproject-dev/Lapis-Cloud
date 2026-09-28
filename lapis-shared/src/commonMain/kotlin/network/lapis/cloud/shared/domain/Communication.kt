package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
enum class MailingMessageStatus { DRAFT, QUEUED, SENT, FAILED }

/**
 * Welle V1.9.7 "SuperMailer" added [PENDING]/[FAILED]/[SKIPPED_NO_ADDRESS] -- the async-send
 * intermediate/terminal states [MailingDeliveryWorker] (server) writes. See
 * `network.lapis.cloud.server.mail.newsletter.MailingDeliveryWorker` KDoc "D1"/"D2"/"D3" for the
 * full state-machine rationale. [SENT]/[BOUNCED]/[SKIPPED_UNSUBSCRIBED] are pre-existing.
 */
@Serializable
enum class DeliveryStatus { SENT, BOUNCED, SKIPPED_UNSUBSCRIBED, PENDING, FAILED, SKIPPED_NO_ADDRESS }

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
    /**
     * Welle V1.9.7 "SuperMailer" -- sanitized HTML content (see `MailingHtmlSanitizer`, server).
     * NULL for plain-text drafts created via [network.lapis.cloud.shared.rpc.IMailingService
     * .createDraftMessage] and everything created before this wave.
     */
    val bodyHtml: String? = null,
    val sentBy: String,
    val sentAt: LocalDateTime?,
    val status: MailingMessageStatus,
)

/**
 * Welle V1.9.7 "SuperMailer" -- stateless render result for both
 * [network.lapis.cloud.shared.rpc.IMailingService.previewMailingMessage] and
 * `.previewMailingHtml`. Never carries tracking artifacts (see `MailingMailRenderer` KDoc
 * "tracking == null").
 */
@Serializable
data class MailingPreviewDto(
    val subject: String,
    val plainText: String,
    val html: String,
)

/**
 * Welle V1.9.7 "SuperMailer" -- the operator-configured send mode
 * (`LAPIS_MAILING_DELIVERY`, server default [LOG]), exposed read-only so the compose UI can show
 * (or hide) the "this is only a log entry, not a real send" honesty caption. See
 * `network.lapis.cloud.server.mail.newsletter.MailingDeliveryConfig` KDoc.
 */
@Serializable
enum class MailingDeliveryMode { LOG, SMTP }

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
