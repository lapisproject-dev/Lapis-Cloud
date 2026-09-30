package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingLinkClickTable
import network.lapis.cloud.server.db.generated.MailingListSubscriptionTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageLinkTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.newsletter.MailingDeliveryWorker
import network.lapis.cloud.server.mail.newsletter.MailingHtmlSanitizer
import network.lapis.cloud.server.mail.newsletter.MailingMailRenderer
import network.lapis.cloud.server.mail.newsletter.MailingTrackingData
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import network.lapis.cloud.shared.domain.MailingLinkStatsDto
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.MailingListSubscriptionDto
import network.lapis.cloud.shared.domain.MailingMessageDto
import network.lapis.cloud.shared.domain.MailingMessageStatsDto
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MailingPreviewDto
import network.lapis.cloud.shared.domain.MailingTrackingConsentSnapshot
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IMailingService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val BOARD_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * **V0.11.0 security fix**: [listMailingLists]/[subscribe]/[unsubscribe] previously had NO
 * membership-status gate at all -- any authenticated caller (including a self-registered FRIEND)
 * could enumerate and subscribe to org mailing lists. All three now call [requireActiveMembership]
 * -- communication/mailing lists are ACTIVE-only per spec, same as [DirectMessageService]. Board-
 * only endpoints ([createMailingList]/[adminSubscribeMember]/[listSubscribers]/
 * [createDraftMessage]/[listMailingMessages]/[sendMailingMessage]) were never reachable by a
 * non-privileged caller to begin with ([requireRole]).
 *
 * **Welle V1.9.7 "SuperMailer"** replaces [sendMailingMessage]'s previous synchronous
 * `runCatching { DeliveryStatus.SENT }` stub (which never called any real transport and could
 * double-send on a repeat click) with a genuine async handoff to [MailingDeliveryWorker]: a
 * bounded `DRAFT -> QUEUED` transition (a second call on an already-`QUEUED` message throws
 * [ConflictException] rather than re-queuing), a D3 recipient-eligibility filter (active
 * subscription, [MemberStatus.ACTIVE], not anonymized, not deceased), and a
 * [MailingHtmlPolicy.MAX_RECIPIENTS] cap. Also adds [createDraftMessageHtml]/
 * [previewMailingMessage]/[previewMailingHtml]/[getMailingDeliveryMode] (Teil A -- HTML-authored
 * drafts, sanitized via [MailingHtmlSanitizer]).
 */
class MailingService(
    private val call: ApplicationCall,
    private val deliveryWorker: MailingDeliveryWorker,
    private val deliveryMode: MailingDeliveryMode,
    private val branding: MailBranding,
) : IMailingService {
    override suspend fun listMailingLists(): List<MailingListDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            MailingListTable.selectAll().map { row ->
                val listId = row[MailingListTable.id]
                val subscriberCount =
                    MailingListSubscriptionTable
                        .selectAll()
                        .where {
                            (MailingListSubscriptionTable.mailingListId eq listId) and
                                (MailingListSubscriptionTable.unsubscribedAt.isNull())
                        }.count()
                val ownSubscription =
                    MailingListSubscriptionTable
                        .selectAll()
                        .where {
                            (MailingListSubscriptionTable.mailingListId eq listId) and
                                (MailingListSubscriptionTable.memberId eq current.memberId) and
                                (MailingListSubscriptionTable.unsubscribedAt.isNull())
                        }.singleOrNull()
                row.toMailingListDto(
                    subscriberCount = subscriberCount.toInt(),
                    isSubscribed = ownSubscription != null,
                    openTrackingConsentedAt = ownSubscription?.get(MailingListSubscriptionTable.openTrackingConsentedAt),
                    clickTrackingConsentedAt = ownSubscription?.get(MailingListSubscriptionTable.clickTrackingConsentedAt),
                )
            }
        }
    }

    override suspend fun createMailingList(
        name: String,
        description: String?,
    ): MailingListDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        return transaction {
            val id = Uuid.random()
            MailingListTable.insert {
                it[MailingListTable.id] = id
                it[MailingListTable.name] = name
                it[MailingListTable.description] = description
                it[createdBy] = current.memberId
            }
            MailingListTable
                .selectAll()
                .where { MailingListTable.id eq id }
                .single()
                .toMailingListDto(subscriberCount = 0, isSubscribed = false)
        }
    }

    override suspend fun subscribe(mailingListId: String) {
        val current = resolveCurrentMember(call)
        val listId = Uuid.parse(mailingListId)
        val now = DbClock.nowLocalDateTime()
        transaction {
            requireActiveMembership(memberId = current.memberId)
            val existing =
                MailingListSubscriptionTable
                    .selectAll()
                    .where {
                        (MailingListSubscriptionTable.mailingListId eq listId) and
                            (MailingListSubscriptionTable.memberId eq current.memberId)
                    }.singleOrNull()
            if (existing == null) {
                MailingListSubscriptionTable.insert {
                    it[id] = Uuid.random()
                    it[MailingListSubscriptionTable.mailingListId] = listId
                    it[memberId] = current.memberId
                    it[subscribedAt] = now
                }
            } else if (existing[MailingListSubscriptionTable.unsubscribedAt] != null) {
                MailingListSubscriptionTable.update({ MailingListSubscriptionTable.id eq existing[MailingListSubscriptionTable.id] }) {
                    it[unsubscribedAt] = null
                    it[subscribedAt] = now
                    // Welle V1.9.7 -- re-subscribing resets any prior tracking consent (D9/plan);
                    // the columns are still unused this wave (no UI/RPC sets them yet) but reset
                    // them defensively regardless so a future consent never silently survives an
                    // unsubscribe/resubscribe cycle.
                    it[openTrackingConsentedAt] = null
                    it[clickTrackingConsentedAt] = null
                }
            }
        }
    }

    override suspend fun unsubscribe(mailingListId: String) {
        val current = resolveCurrentMember(call)
        val listId = Uuid.parse(mailingListId)
        val now = DbClock.nowLocalDateTime()
        transaction {
            requireActiveMembership(memberId = current.memberId)
            val existing =
                MailingListSubscriptionTable
                    .selectAll()
                    .where {
                        (MailingListSubscriptionTable.mailingListId eq listId) and
                            (MailingListSubscriptionTable.memberId eq current.memberId)
                    }.singleOrNull()
            val hadOpen = existing?.get(MailingListSubscriptionTable.openTrackingConsentedAt) != null
            val hadClick = existing?.get(MailingListSubscriptionTable.clickTrackingConsentedAt) != null
            MailingListSubscriptionTable.update(
                {
                    (MailingListSubscriptionTable.mailingListId eq listId) and
                        (MailingListSubscriptionTable.memberId eq current.memberId)
                },
            ) {
                it[unsubscribedAt] = now
                // Welle V1.9.7, D9 -- unsubscribing resets any tracking consent too.
                it[openTrackingConsentedAt] = null
                it[clickTrackingConsentedAt] = null
            }
            if (hadOpen || hadClick) {
                // Welle V1.9.15 -- unsubscribing is a withdrawal: the already-collected counting data
                // goes too, and the change is audited (AuditEntityType.MEMBER, no new migration).
                MailingTrackingData.eraseForSubscription(
                    listId = listId,
                    memberId = current.memberId,
                    eraseOpen = hadOpen,
                    eraseClick = hadClick,
                )
                recordConsentAudit(
                    current = current,
                    listId = listId,
                    beforeOpen = hadOpen,
                    beforeClick = hadClick,
                    afterOpen = false,
                    afterClick = false,
                    now = now,
                )
            }
        }
    }

    override suspend fun setTrackingConsent(
        mailingListId: String,
        openTracking: Boolean,
        clickTracking: Boolean,
    ): MailingListSubscriptionDto {
        val current = resolveCurrentMember(call)
        val listId = Uuid.parse(mailingListId)
        val now = DbClock.nowLocalDateTime()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val existing =
                MailingListSubscriptionTable
                    .selectAll()
                    .where {
                        (MailingListSubscriptionTable.mailingListId eq listId) and
                            (MailingListSubscriptionTable.memberId eq current.memberId) and
                            (MailingListSubscriptionTable.unsubscribedAt.isNull())
                    }.singleOrNull()
                    ?: throw ConflictException("Einwilligung ist nur bei aktivem Abonnement möglich.")
            val subscriptionId = existing[MailingListSubscriptionTable.id]
            val beforeOpen = existing[MailingListSubscriptionTable.openTrackingConsentedAt]
            val beforeClick = existing[MailingListSubscriptionTable.clickTrackingConsentedAt]
            // true keeps an existing timestamp (the moment consent was GIVEN must not drift), false clears it.
            val newOpen = if (openTracking) beforeOpen ?: now else null
            val newClick = if (clickTracking) beforeClick ?: now else null
            val changed = (beforeOpen != null) != (newOpen != null) || (beforeClick != null) != (newClick != null)
            if (changed) {
                MailingListSubscriptionTable.update({ MailingListSubscriptionTable.id eq subscriptionId }) {
                    it[openTrackingConsentedAt] = newOpen
                    it[clickTrackingConsentedAt] = newClick
                }
                // Withdrawal erases the already-collected data of exactly the withdrawn kind.
                MailingTrackingData.eraseForSubscription(
                    listId = listId,
                    memberId = current.memberId,
                    eraseOpen = beforeOpen != null && newOpen == null,
                    eraseClick = beforeClick != null && newClick == null,
                )
                // Deadlock contract: the audit chain row lock is the LAST lock taken in this transaction.
                recordConsentAudit(
                    current = current,
                    listId = listId,
                    beforeOpen = beforeOpen != null,
                    beforeClick = beforeClick != null,
                    afterOpen = newOpen != null,
                    afterClick = newClick != null,
                    now = now,
                )
            }
            MailingListSubscriptionDto(
                id = subscriptionId.toString(),
                mailingListId = listId.toString(),
                memberId = current.memberId.toString(),
                memberDisplayName = "",
                subscribedAt = existing[MailingListSubscriptionTable.subscribedAt],
                unsubscribedAt = null,
                openTrackingConsentedAt = newOpen,
                clickTrackingConsentedAt = newClick,
            )
        }
    }

    private fun recordConsentAudit(
        current: CurrentMember,
        listId: Uuid,
        beforeOpen: Boolean,
        beforeClick: Boolean,
        afterOpen: Boolean,
        afterClick: Boolean,
        now: LocalDateTime,
    ) {
        fun snapshot(
            open: Boolean,
            click: Boolean,
        ) = Json.encodeToString(
            MemberChangeSnapshot.serializer(),
            MemberChangeSnapshot(
                displayNameChanged = false,
                emailChanged = false,
                status = MemberStatus.ACTIVE,
                role = current.role,
                mailingTrackingConsent =
                    MailingTrackingConsentSnapshot(mailingListId = listId.toString(), openTracking = open, clickTracking = click),
            ),
        )
        AuditLogRecorder.record(
            actorMemberId = current.memberId,
            actorRole = current.role,
            entityType = AuditEntityType.MEMBER,
            entityId = current.memberId,
            action = AuditAction.UPDATE,
            before = snapshot(beforeOpen, beforeClick),
            after = snapshot(afterOpen, afterClick),
            occurredAt = now,
        )
    }

    override suspend fun adminSubscribeMember(
        mailingListId: String,
        memberId: String,
    ) {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val listId = Uuid.parse(mailingListId)
        val targetMemberId = Uuid.parse(memberId)
        val now = DbClock.nowLocalDateTime()
        transaction {
            val existing =
                MailingListSubscriptionTable
                    .selectAll()
                    .where {
                        (MailingListSubscriptionTable.mailingListId eq listId) and
                            (MailingListSubscriptionTable.memberId eq targetMemberId)
                    }.singleOrNull()
            if (existing == null) {
                MailingListSubscriptionTable.insert {
                    it[id] = Uuid.random()
                    it[MailingListSubscriptionTable.mailingListId] = listId
                    it[MailingListSubscriptionTable.memberId] = targetMemberId
                    it[subscribedAt] = now
                }
            }
        }
    }

    override suspend fun listSubscribers(mailingListId: String): List<MailingListSubscriptionDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val listId = Uuid.parse(mailingListId)
        return transaction {
            (MailingListSubscriptionTable innerJoin MemberTable)
                .selectAll()
                .where { MailingListSubscriptionTable.mailingListId eq listId }
                .map { it.toMailingListSubscriptionDto() }
        }
    }

    override suspend fun createDraftMessage(
        mailingListId: String,
        subject: String,
        bodyText: String,
    ): MailingMessageDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val validSubject = validateSubject(subject)
        val trimmedBody = bodyText.trim()
        if (trimmedBody.isEmpty()) throw BadRequestException("Text darf nicht leer sein.")
        if (trimmedBody.length > MailingHtmlPolicy.MAX_TEXT_CHARS) {
            throw BadRequestException("Nachricht zu lang (max. ${MailingHtmlPolicy.MAX_TEXT_CHARS} Zeichen Text).")
        }
        return transaction {
            requireMailingListExists(mailingListId)
            val id = Uuid.random()
            MailingMessageTable.insert {
                it[MailingMessageTable.id] = id
                it[MailingMessageTable.mailingListId] = Uuid.parse(mailingListId)
                it[MailingMessageTable.subject] = validSubject
                it[MailingMessageTable.bodyText] = trimmedBody
                it[bodyHtml] = null
                it[sentBy] = current.memberId
                it[status] = MailingMessageStatus.DRAFT
            }
            MailingMessageTable
                .selectAll()
                .where { MailingMessageTable.id eq id }
                .single()
                .toMailingMessageDto()
        }
    }

    override suspend fun createDraftMessageHtml(
        mailingListId: String,
        subject: String,
        bodyHtml: String,
    ): MailingMessageDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val validSubject = validateSubject(subject)
        // Sanitize BEFORE opening the transaction -- MailingHtmlSanitizer.sanitize/MailingPlainText
        // .derive are pure/CPU-only, no reason to hold a DB transaction open across them.
        val sanitized = MailingHtmlSanitizer.sanitize(bodyHtml)
        return transaction {
            requireMailingListExists(mailingListId)
            val id = Uuid.random()
            MailingMessageTable.insert {
                it[MailingMessageTable.id] = id
                it[MailingMessageTable.mailingListId] = Uuid.parse(mailingListId)
                it[MailingMessageTable.subject] = validSubject
                it[MailingMessageTable.bodyText] = sanitized.plainText
                it[MailingMessageTable.bodyHtml] = sanitized.html
                it[sentBy] = current.memberId
                it[status] = MailingMessageStatus.DRAFT
            }
            MailingMessageTable
                .selectAll()
                .where { MailingMessageTable.id eq id }
                .single()
                .toMailingMessageDto()
        }
    }

    override suspend fun previewMailingMessage(messageId: String): MailingPreviewDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val id = Uuid.parse(messageId)
        val message =
            transaction {
                MailingMessageTable.selectAll().where { MailingMessageTable.id eq id }.singleOrNull()
            } ?: throw NotFoundException("MailingMessage $messageId not found")
        val sanitized = message[MailingMessageTable.bodyHtml]?.let { MailingHtmlSanitizer.sanitize(it) }
        val rendered =
            MailingMailRenderer.render(
                subject = message[MailingMessageTable.subject],
                content = sanitized,
                legacyBodyText = message[MailingMessageTable.bodyText],
                branding = branding,
            )
        return MailingPreviewDto(subject = rendered.subject, plainText = rendered.plainText, html = rendered.html)
    }

    override suspend fun previewMailingHtml(
        subject: String,
        bodyHtml: String,
    ): MailingPreviewDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val validSubject = validateSubject(subject)
        val sanitized = MailingHtmlSanitizer.sanitize(bodyHtml)
        val rendered =
            MailingMailRenderer.render(
                subject = validSubject,
                content = sanitized,
                legacyBodyText = sanitized.plainText,
                branding = branding,
            )
        return MailingPreviewDto(subject = rendered.subject, plainText = rendered.plainText, html = rendered.html)
    }

    override suspend fun getMailingDeliveryMode(): MailingDeliveryMode {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        return deliveryMode
    }

    override suspend fun listMailingMessages(mailingListId: String): List<MailingMessageDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val listId = Uuid.parse(mailingListId)
        return transaction {
            MailingMessageTable.selectAll().where { MailingMessageTable.mailingListId eq listId }.map { it.toMailingMessageDto() }
        }
    }

    override suspend fun mailingMessageStats(messageId: String): MailingMessageStatsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val id = Uuid.parse(messageId)
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val message =
                MailingMessageTable.selectAll().where { MailingMessageTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("MailingMessage $messageId not found")
            val sentAt = message[MailingMessageTable.sentAt]
            val retentionExpired =
                sentAt != null &&
                    sentAt.date.plus(DatePeriod(days = MailingHtmlPolicy.RETENTION_DAYS)) <= now.date
            val sent = (MailingDeliveryLogTable.mailingMessageId eq id) and (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.SENT)
            val delivered =
                MailingDeliveryLogTable
                    .selectAll()
                    .where { sent }
                    .count()
                    .toInt()
            val openCohort =
                MailingDeliveryLogTable
                    .selectAll()
                    .where { sent and (MailingDeliveryLogTable.openTracked eq true) }
                    .count()
                    .toInt()
            val clickCohort =
                MailingDeliveryLogTable
                    .selectAll()
                    .where { sent and (MailingDeliveryLogTable.clickTracked eq true) }
                    .count()
                    .toInt()
            val openSuppressed = openCohort < MailingHtmlPolicy.MIN_CONSENTS_FOR_STATS
            val clickSuppressed = clickCohort < MailingHtmlPolicy.MIN_CONSENTS_FOR_STATS
            val openedAtLeastOnce =
                if (openSuppressed) {
                    null
                } else {
                    MailingDeliveryLogTable
                        .selectAll()
                        .where { sent and (MailingDeliveryLogTable.openTracked eq true) and (MailingDeliveryLogTable.openCount greater 0) }
                        .count()
                        .toInt()
                }
            // One aggregate query for all links: GROUP BY link_index over clicks of click-tracked SENT deliveries.
            val clickAggregates =
                if (clickSuppressed) {
                    emptyMap()
                } else {
                    val recipients = MailingLinkClickTable.id.count()
                    val totalClicks = MailingLinkClickTable.clickCount.sum()
                    (MailingLinkClickTable innerJoin MailingDeliveryLogTable)
                        .select(MailingLinkClickTable.linkIndex, recipients, totalClicks)
                        .where { sent and (MailingDeliveryLogTable.clickTracked eq true) }
                        .groupBy(MailingLinkClickTable.linkIndex)
                        .associate { row ->
                            row[MailingLinkClickTable.linkIndex] to Pair(row[recipients].toInt(), row[totalClicks] ?: 0)
                        }
                }
            val links =
                MailingMessageLinkTable
                    .selectAll()
                    .where { MailingMessageLinkTable.mailingMessageId eq id }
                    .orderBy(MailingMessageLinkTable.linkIndex)
                    .map { row ->
                        val index = row[MailingMessageLinkTable.linkIndex]
                        val aggregate = clickAggregates[index]
                        MailingLinkStatsDto(
                            linkIndex = index,
                            targetUrl = row[MailingMessageLinkTable.targetUrl],
                            uniqueRecipients = if (clickSuppressed) null else (aggregate?.first ?: 0),
                            totalClicks = if (clickSuppressed) null else (aggregate?.second ?: 0),
                        )
                    }
            MailingMessageStatsDto(
                messageId = id.toString(),
                delivered = delivered,
                openCohort = openCohort,
                openedAtLeastOnce = openedAtLeastOnce,
                clickCohort = clickCohort,
                links = links,
                openSuppressed = openSuppressed,
                clickSuppressed = clickSuppressed,
                suppressed = openSuppressed || clickSuppressed,
                retentionExpired = retentionExpired,
            )
        }
    }

    override suspend fun sendMailingMessage(messageId: String): MailingMessageDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val id = Uuid.parse(messageId)
        val now = DbClock.nowLocalDateTime()
        // Snapshot taken INSIDE the queuing transaction, right after the DRAFT->QUEUED update --
        // returned as-is, never re-read afterwards. MailingDeliveryWorker runs on its own
        // coroutine/scope and could in principle finish the whole send before this method's own
        // stack unwinds; re-reading the row after calling deliveryWorker.enqueue would make the
        // returned status a race (QUEUED or already SENT/FAILED depending on timing) instead of
        // the deterministic "QUEUED, always" IMailingService.sendMailingMessage's KDoc promises.
        val queuedSnapshot =
            transaction {
                // Bounded, idempotent conditional transition -- see class KDoc. A second call on an
                // already-QUEUED/SENT/FAILED message updates 0 rows and throws below, rather than
                // silently re-queuing (the pre-V1.9.7 bug this fixes: a repeat click used to write a
                // second full round of delivery-log rows).
                val updated =
                    MailingMessageTable.update(
                        { (MailingMessageTable.id eq id) and (MailingMessageTable.status eq MailingMessageStatus.DRAFT) },
                    ) {
                        it[status] = MailingMessageStatus.QUEUED
                    }
                if (updated == 0) {
                    val exists = MailingMessageTable.selectAll().where { MailingMessageTable.id eq id }.count() > 0
                    if (!exists) throw NotFoundException("MailingMessage $messageId not found")
                    throw ConflictException("MailingMessage $messageId was already sent or queued")
                }
                val queuedRow = MailingMessageTable.selectAll().where { MailingMessageTable.id eq id }.single()
                val listId = queuedRow[MailingMessageTable.mailingListId]
                // Welle V1.9.15 -- freeze the message's trackable links NOW (DRAFT -> QUEUED): the
                // worker later rewrites hrefs against exactly these rows, and the click route resolves
                // its redirect target from exactly these rows (never from anything in the URL).
                queuedRow[MailingMessageTable.bodyHtml]?.let { storedHtml ->
                    MailingTrackingData.captureLinks(messageId = id, sanitized = MailingHtmlSanitizer.sanitize(storedHtml))
                }

                // D3 (plan): only ACTIVE, non-anonymized, non-deceased members with a still-active
                // subscription. Members who fall out of this filter get no delivery-log row at all --
                // see IMailingService.sendMailingMessage KDoc.
                val eligibleRecipients =
                    (MailingListSubscriptionTable innerJoin MemberTable)
                        .selectAll()
                        .where {
                            (MailingListSubscriptionTable.mailingListId eq listId) and
                                (MailingListSubscriptionTable.unsubscribedAt.isNull()) and
                                (MemberTable.status eq MemberStatus.ACTIVE) and
                                (MemberTable.anonymizedAt.isNull()) and
                                (MemberTable.dateOfDeath.isNull())
                        }.map { it[MailingListSubscriptionTable.memberId] }

                if (eligibleRecipients.size > MailingHtmlPolicy.MAX_RECIPIENTS) {
                    // Throwing here rolls back the whole transaction, including the DRAFT->QUEUED
                    // transition above -- the message is left exactly as it was (still DRAFT).
                    throw BadRequestException(
                        "Zu viele Empfänger (${eligibleRecipients.size}, max. ${MailingHtmlPolicy.MAX_RECIPIENTS}).",
                    )
                }

                eligibleRecipients.forEach { subscriberId ->
                    MailingDeliveryLogTable.insert {
                        it[MailingDeliveryLogTable.id] = Uuid.random()
                        it[MailingDeliveryLogTable.mailingMessageId] = id
                        it[MailingDeliveryLogTable.memberId] = subscriberId
                        it[MailingDeliveryLogTable.deliveredAt] = now
                        it[MailingDeliveryLogTable.deliveryStatus] = DeliveryStatus.PENDING
                    }
                }

                MailingMessageTable
                    .selectAll()
                    .where { MailingMessageTable.id eq id }
                    .single()
                    .toMailingMessageDto()
            }
        // Handed off AFTER the queuing transaction committed. Review fix (finding #2, W-SuperMailer
        // round 1): MailingDeliveryWorker.enqueue can throw ConflictException if its own bounded
        // queue is saturated (or the worker is shutting down) -- by this point the DRAFT->QUEUED
        // transition and the PENDING rows are ALREADY committed, so simply letting that exception
        // propagate would leave the message stuck QUEUED forever: sendMailingMessage only ever
        // transitions FROM DRAFT, so a retry on a QUEUED message throws its own ConflictException
        // ("already sent or queued"), and recoverInterrupted() would eventually mark it FAILED
        // without ever actually attempting delivery. Roll the queuing back instead -- reset the
        // message to DRAFT and delete the PENDING rows this call just inserted -- so a caller who
        // sees this exception can genuinely retry via a normal sendMailingMessage call, exactly what
        // the KDoc/error message already promise.
        try {
            deliveryWorker.enqueue(id)
        } catch (e: ConflictException) {
            transaction {
                MailingDeliveryLogTable.deleteWhere {
                    (MailingDeliveryLogTable.mailingMessageId eq id) and (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.PENDING)
                }
                // The captured links go too -- otherwise the retry's captureLinks would still work
                // (delete + insert), but a DRAFT must not carry frozen-link rows.
                MailingMessageLinkTable.deleteWhere { MailingMessageLinkTable.mailingMessageId eq id }
                MailingMessageTable.update(
                    { (MailingMessageTable.id eq id) and (MailingMessageTable.status eq MailingMessageStatus.QUEUED) },
                ) {
                    it[status] = MailingMessageStatus.DRAFT
                }
            }
            throw e
        }
        return queuedSnapshot
    }
}

private fun requireMailingListExists(mailingListId: String) {
    val listId = Uuid.parse(mailingListId)
    val exists = MailingListTable.selectAll().where { MailingListTable.id eq listId }.count() > 0
    if (!exists) throw NotFoundException("MailingList $mailingListId not found")
}

/** Trims, rejects blank/too-long/control-character subjects -- see plan §5.7. */
private fun validateSubject(subject: String): String {
    val trimmed = subject.trim()
    if (trimmed.isEmpty()) throw BadRequestException("Betreff darf nicht leer sein.")
    if (trimmed.length > MailingHtmlPolicy.MAX_SUBJECT_CHARS) {
        throw BadRequestException("Betreff zu lang (max. ${MailingHtmlPolicy.MAX_SUBJECT_CHARS} Zeichen).")
    }
    if (trimmed.any { it.isISOControl() }) {
        throw BadRequestException("Betreff enthält ungültige Zeichen.")
    }
    return trimmed
}

private fun ResultRow.toMailingListDto(
    subscriberCount: Int,
    isSubscribed: Boolean,
    openTrackingConsentedAt: LocalDateTime? = null,
    clickTrackingConsentedAt: LocalDateTime? = null,
): MailingListDto =
    MailingListDto(
        id = this[MailingListTable.id].toString(),
        name = this[MailingListTable.name],
        description = this[MailingListTable.description],
        createdBy = this[MailingListTable.createdBy].toString(),
        subscriberCount = subscriberCount,
        isSubscribedByCurrentMember = isSubscribed,
        currentMemberOpenTrackingConsentedAt = openTrackingConsentedAt,
        currentMemberClickTrackingConsentedAt = clickTrackingConsentedAt,
    )

private fun ResultRow.toMailingListSubscriptionDto(): MailingListSubscriptionDto =
    MailingListSubscriptionDto(
        id = this[MailingListSubscriptionTable.id].toString(),
        mailingListId = this[MailingListSubscriptionTable.mailingListId].toString(),
        memberId = this[MailingListSubscriptionTable.memberId].toString(),
        memberDisplayName = this[MemberTable.displayName],
        subscribedAt = this[MailingListSubscriptionTable.subscribedAt],
        unsubscribedAt = this[MailingListSubscriptionTable.unsubscribedAt],
    )

private fun ResultRow.toMailingMessageDto(): MailingMessageDto =
    MailingMessageDto(
        id = this[MailingMessageTable.id].toString(),
        mailingListId = this[MailingMessageTable.mailingListId].toString(),
        subject = this[MailingMessageTable.subject],
        bodyText = this[MailingMessageTable.bodyText],
        bodyHtml = this[MailingMessageTable.bodyHtml],
        sentBy = this[MailingMessageTable.sentBy].toString(),
        sentAt = this[MailingMessageTable.sentAt],
        status = this[MailingMessageTable.status],
    )
