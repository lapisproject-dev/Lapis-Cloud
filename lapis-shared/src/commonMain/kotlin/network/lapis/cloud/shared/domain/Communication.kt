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
    /** Welle V1.9.15 -- the CURRENT member's own open-tracking consent timestamp (self-service context only), `null` = no consent. */
    val currentMemberOpenTrackingConsentedAt: LocalDateTime? = null,
    /** Welle V1.9.15 -- the CURRENT member's own click-tracking consent timestamp (self-service context only), `null` = no consent. */
    val currentMemberClickTrackingConsentedAt: LocalDateTime? = null,
)

@Serializable
data class MailingListSubscriptionDto(
    val id: String,
    val mailingListId: String,
    val memberId: String,
    val memberDisplayName: String,
    val subscribedAt: LocalDateTime,
    val unsubscribedAt: LocalDateTime?,
    /** Welle V1.9.15 -- only filled in the self-service context (`setTrackingConsent`); `listSubscribers` leaves it `null` (data minimisation). */
    val openTrackingConsentedAt: LocalDateTime? = null,
    /** See [openTrackingConsentedAt]. */
    val clickTrackingConsentedAt: LocalDateTime? = null,
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

/** V1.9.36 -- one conversation partner of the caller: name, last activity and unread count; never any message text. */
@Serializable
data class DirectMessagePartnerDto(
    val partnerId: String,
    val partnerDisplayName: String,
    val lastActivityAt: LocalDateTime,
    val unreadCount: Int,
)

/** V1.9.36 -- keyset cursor of a conversation page: the oldest message of the previous page. */
@Serializable
data class DirectMessageCursorDto(
    val sentAt: LocalDateTime,
    val id: String,
)

/** V1.9.36 -- one page of a conversation, newest first. [nextCursor] is the last (oldest) message of this page, `null` if empty. */
@Serializable
data class DirectMessagePageDto(
    val messages: List<DirectMessageDto>,
    val hasMore: Boolean,
    val nextCursor: DirectMessageCursorDto?,
)

/**
 * Welle V1.9.15 -- per-link aggregate of [MailingMessageStatsDto]. [uniqueRecipients]/[totalClicks]
 * are `null` when the click cohort is below
 * [MailingHtmlPolicy.MIN_CONSENTS_FOR_STATS] (k-anonymity floor).
 */
@Serializable
data class MailingLinkStatsDto(
    val linkIndex: Int,
    val targetUrl: String,
    val uniqueRecipients: Int?,
    val totalClicks: Int?,
)

/**
 * Welle V1.9.15 -- aggregate-only statistics for one sent mailing message. **Never** carries a
 * member id, name or personal timestamp -- only counts, and only when the consenting cohort is at
 * least [MailingHtmlPolicy.MIN_CONSENTS_FOR_STATS].
 */
@Serializable
data class MailingMessageStatsDto(
    val messageId: String,
    /** Number of deliveries with status SENT. */
    val delivered: Int,
    /** SENT deliveries whose recipient had opted into open tracking at send time. */
    val openCohort: Int,
    /** Of [openCohort], those that loaded the pixel at least once; `null` when suppressed. */
    val openedAtLeastOnce: Int?,
    /** SENT deliveries whose recipient had opted into click tracking at send time. */
    val clickCohort: Int,
    val links: List<MailingLinkStatsDto>,
    val openSuppressed: Boolean,
    val clickSuppressed: Boolean,
    /** `openSuppressed || clickSuppressed`. */
    val suppressed: Boolean,
    /** `true` once the retention period ([MailingHtmlPolicy.RETENTION_DAYS]) has passed and the raw events were erased. */
    val retentionExpired: Boolean,
)
