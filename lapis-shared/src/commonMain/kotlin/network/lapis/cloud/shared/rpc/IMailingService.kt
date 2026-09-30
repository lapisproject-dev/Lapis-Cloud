package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.MailingListSubscriptionDto
import network.lapis.cloud.shared.domain.MailingMessageDto
import network.lapis.cloud.shared.domain.MailingMessageStatsDto
import network.lapis.cloud.shared.domain.MailingPreviewDto

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
     * eventual outcome. Recipients are filtered ONCE, at queuing time, to active, non-anonymized,
     * non-deceased subscribers (everyone who fails that filter gets no delivery-log row at all) and
     * capped at [network.lapis.cloud.shared.domain.MailingHtmlPolicy.MAX_RECIPIENTS]. A syntactically
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
}
