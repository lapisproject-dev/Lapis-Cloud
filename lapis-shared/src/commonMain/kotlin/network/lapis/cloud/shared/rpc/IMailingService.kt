package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.MailingListSubscriptionDto
import network.lapis.cloud.shared.domain.MailingMessageDto
import network.lapis.cloud.shared.domain.MailingMessageStatsDto
import network.lapis.cloud.shared.domain.MailingPreviewDto
import network.lapis.cloud.shared.domain.MailingSendEstimateDto
import network.lapis.cloud.shared.domain.MailingSendProgressDto
import network.lapis.cloud.shared.domain.RecipientPreviewDto

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
     * Welle V1.9.7 "SuperMailer" Teil A -- an HTML-authored counterpart to [createDraftMessage]:
     * [bodyHtml] is sanitized server-side (`MailingHtmlSanitizer`, allowlist
     * `network.lapis.cloud.shared.domain.MailingHtmlPolicy`) and the plain-text body is DERIVED
     * from the sanitized HTML (`MailingPlainText`), not supplied separately. Role: Board/Admin.
     */
    suspend fun createDraftMessageHtml(
        mailingListId: String,
        subject: String,
        bodyHtml: String,
    ): MailingMessageDto

    /**
     * Welle V1.9.7 "SuperMailer" Teil A -- renders an already-created message exactly the way it
     * would be sent, WITHOUT ever writing a tracking token (`MailingMailRenderer` KDoc
     * "tracking == null" is the one code path both this and [previewMailingHtml] go through).
     * Role: Board/Admin.
     */
    suspend fun previewMailingMessage(messageId: String): MailingPreviewDto

    /**
     * Welle V1.9.7 "SuperMailer" Teil A -- stateless preview for content that has not been saved
     * yet (the compose form's "Bearbeiten | Vorschau" toggle). Sanitizes [bodyHtml] the same way
     * [createDraftMessageHtml] would, but writes nothing. Role: Board/Admin.
     */
    suspend fun previewMailingHtml(
        subject: String,
        bodyHtml: String,
    ): MailingPreviewDto

    /**
     * Welle V1.9.7 "SuperMailer" -- the operator-configured `LAPIS_MAILING_DELIVERY` mode
     * (`network.lapis.cloud.server.mail.newsletter.MailingDeliveryConfig`), read-only. Role:
     * Board/Admin (same tier as every other admin-authoring endpoint on this interface).
     */
    suspend fun getMailingDeliveryMode(): MailingDeliveryMode

    /**
     * Welle V1.9.7 "SuperMailer" -- flips a `DRAFT` message to `QUEUED` (a bounded, idempotent
     * conditional `UPDATE ... WHERE status = 'DRAFT'`; a second call on an already-`QUEUED` or
     * already-terminal message throws [network.lapis.cloud.shared.rpc.ConflictException] rather
     * than double-queuing -- it does NOT flip an already-`QUEUED` message back to `QUEUED`) and
     * hands it to `network.lapis.cloud.server.mail.newsletter.MailingDeliveryWorker` for
     * asynchronous delivery -- the actual send (real SMTP or a `LOG`-mode no-op, see
     * `MailingDeliveryConfig`/`MailingDeliveryMode`) happens AFTER this call returns, one recipient
     * at a time, off the RPC handler's own coroutine. The returned [MailingMessageDto] therefore
     * reflects `QUEUED`, never yet `SENT`/`FAILED` — call [listMailingMessages] again to see the
     * eventual outcome. For a MANUAL list the recipients are filtered ONCE, at queuing time, to active, non-anonymized,
     * non-deceased subscribers (everyone who fails that filter gets no delivery-log row at all) and
     * capped at [network.lapis.cloud.shared.domain.MailingHtmlPolicy.MAX_RECIPIENTS]. **For a DYNAMIC list (V1.9.111) nothing is decided here**:
     * no delivery row is written, the size is only checked for fail-fast feedback, and the delivery worker resolves the recipients from the
     * rule when it takes the message (and re-checks each one right before the mail). The size limit is applied again at that point. A syntactically
     * INVALID mailbox address is NOT part of that queuing-time filter -- such a recipient still gets
     * a delivery-log row (`PENDING`, then `DeliveryStatus.SKIPPED_NO_ADDRESS` once the worker gets to
     * it), because the address is only re-validated later, per recipient, by the worker itself.
     * Role: Board/Admin.
     */
    suspend fun sendMailingMessage(messageId: String): MailingMessageDto

    /**
     * Welle V1.9.15 -- self-service, ACTIVE members only, only for an active subscription. Per flag:
     * `true` sets the consent timestamp to now if it was not already set; `false` clears it AND
     * erases the member's already-collected counting data of that kind for this list (withdrawal
     * is immediately effective, Art. 7(3)/17(1)(b) DSGVO). Both default to "no consent".
     */
    suspend fun setTrackingConsent(
        mailingListId: String,
        openTracking: Boolean,
        clickTracking: Boolean,
    ): MailingListSubscriptionDto

    /**
     * Welle V1.9.15 -- aggregate-only statistics (no personal data, minimum cohort
     * [network.lapis.cloud.shared.domain.MailingHtmlPolicy.MIN_CONSENTS_FOR_STATS]). Role: Board/Admin.
     */
    suspend fun mailingMessageStats(messageId: String): MailingMessageStatsDto

    /**
     * Welle V1.9.81 -- how many recipients [sendMailingMessage] would reach, and how long that takes under the hourly send budget. The
     * recipient criteria are exactly those of the send (one shared function on the server), so the confirmation never disagrees with the
     * send. Rate limited (60 per minute and member). Role: Board/Admin.
     */
    suspend fun mailingSendEstimate(messageId: String): MailingSendEstimateDto

    /**
     * Welle V1.9.81 -- progress of a send: sent / failed / unclear (interrupted) / pending counts, a pause with its reason, and an ETA.
     * Rate limited (60 per minute and member). Role: Board/Admin.
     */
    suspend fun mailingSendProgress(messageId: String): MailingSendProgressDto

    /**
     * Welle V1.9.111 -- creates a DYNAMIC list: its recipients are computed from [ruleJson] (a `MemberRule`, strictly parsed, stored in its
     * canonical form) when a message is SENT, not when the list is written. [mandatory] makes it a mandatory list (no opt-out); like the
     * kind it cannot be changed afterwards. Role: Board/Admin. A rule that does not parse, or names an unknown/inactive committee or
     * an unknown tier, is a `BadRequestException` without the input in the message.
     */
    suspend fun createDynamicMailingList(
        name: String,
        description: String?,
        ruleJson: String,
        mandatory: Boolean,
    ): MailingListDto

    /** Welle V1.9.111 -- replaces the rule of a DYNAMIC list (audited, before/after). A MANUAL list is a `ConflictException`. Role: Board/Admin. */
    suspend fun updateMailingListRule(
        mailingListId: String,
        ruleJson: String,
    ): MailingListDto

    /**
     * Welle V1.9.111 -- how many members a rule would reach right now (and how many are left out for a missing address or an opt-out).
     * Numbers only. Pass [mailingListId] to count the opt-outs of that list, [mandatory] to ignore them. Rate limited (60 per minute and
     * member). Role: Board/Admin.
     */
    suspend fun previewDynamicRecipients(
        ruleJson: String,
        mailingListId: String? = null,
        mandatory: Boolean = false,
    ): RecipientPreviewDto

    /**
     * Welle V1.9.111 -- self-service opt-out of a DYNAMIC list, only while the rule includes the caller; anything else (unknown list,
     * MANUAL list, rule does not include the caller) is the same generic `NotFoundException`. A mandatory list is a `BadRequestException`.
     * Idempotent.
     */
    suspend fun optOutOfDynamicList(mailingListId: String)

    /** Welle V1.9.111 -- undoes [optOutOfDynamicList]. Same visibility rules, idempotent. */
    suspend fun revokeOptOut(mailingListId: String)
}
