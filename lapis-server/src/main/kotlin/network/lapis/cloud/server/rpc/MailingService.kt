package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingLinkClickTable
import network.lapis.cloud.server.db.generated.MailingListOptOutTable
import network.lapis.cloud.server.db.generated.MailingListSubscriptionTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageLinkTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.budget.BudgetDecision
import network.lapis.cloud.server.mail.budget.MailBudgetStore
import network.lapis.cloud.server.mail.budget.MailLane
import network.lapis.cloud.server.mail.isValidMailboxAddress
import network.lapis.cloud.server.mail.newsletter.MailingAttachmentStore
import network.lapis.cloud.server.mail.newsletter.MailingDeliveryWorker
import network.lapis.cloud.server.mail.newsletter.MailingHtmlSanitizer
import network.lapis.cloud.server.mail.newsletter.MailingMailRenderer
import network.lapis.cloud.server.mail.newsletter.MailingTrackingData
import network.lapis.cloud.server.mail.newsletter.resolveAmong
import network.lapis.cloud.server.mail.newsletter.resolveDynamicRecipients
import network.lapis.cloud.server.mail.secondsUntil
import network.lapis.cloud.server.rules.LoadedMember
import network.lapis.cloud.server.rules.MemberFactsLoader
import network.lapis.cloud.server.rules.RuleReferences
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttachmentRules
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.DynamicListExplanationDto
import network.lapis.cloud.shared.domain.MailingAttachmentDto
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingEta
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import network.lapis.cloud.shared.domain.MailingLinkStatsDto
import network.lapis.cloud.shared.domain.MailingListDeletionSnapshot
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.MailingListKind
import network.lapis.cloud.shared.domain.MailingListRuleSnapshot
import network.lapis.cloud.shared.domain.MailingListSubscriptionDto
import network.lapis.cloud.shared.domain.MailingMessageDto
import network.lapis.cloud.shared.domain.MailingMessageStatsDto
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MailingNotice
import network.lapis.cloud.shared.domain.MailingPauseReason
import network.lapis.cloud.shared.domain.MailingPreviewDto
import network.lapis.cloud.shared.domain.MailingSendEstimateDto
import network.lapis.cloud.shared.domain.MailingSendProgressDto
import network.lapis.cloud.shared.domain.MailingTrackingConsentSnapshot
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RecipientPreviewDto
import network.lapis.cloud.shared.domain.RuleExplanationItem
import network.lapis.cloud.shared.domain.RuleExplanationKind
import network.lapis.cloud.shared.domain.rules.InvalidMemberRuleException
import network.lapis.cloud.shared.domain.rules.MailingRecipientGuard
import network.lapis.cloud.shared.domain.rules.MemberFacts
import network.lapis.cloud.shared.domain.rules.MemberRule
import network.lapis.cloud.shared.domain.rules.MemberRuleCodec
import network.lapis.cloud.shared.domain.rules.RecipientFacts
import network.lapis.cloud.shared.domain.rules.RuleCondition
import network.lapis.cloud.shared.domain.rules.RuleContext
import network.lapis.cloud.shared.domain.rules.matches
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IMailingService
import network.lapis.cloud.shared.rpc.MailingListNotDeletableException
import network.lapis.cloud.shared.rpc.MailingListSendingInProgressException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private val BOARD_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

private const val MAX_LIST_NAME_CHARS = 200
private const val MAX_LIST_DESCRIPTION_CHARS = 1000

/**
 * V1.9.81 -- ONE per-member limiter for the two send-display RPCs ([MailingService.mailingSendEstimate], [MailingService.mailingSendProgress]),
 * defined at module level: [MailingService] is built per call, so a limiter created per instance would never see a second request.
 * 60 calls per minute and member -- the progress panel polls every 15 s.
 */
private val SEND_DISPLAY_RATE_LIMITER = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes)

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
    /** V1.9.234 -- root of the attachment files (same as registerMailingAttachmentRoutes); only for deleting draft files after commit. */
    private val attachmentStorageRoot: File,
    /** V1.9.81 -- the hourly send budget, for the confirmation estimate and the progress display (no budget = no throttling). */
    private val budget: MailBudgetStore = MailBudgetStore(null),
    /** V1.9.81 -- the worker's minimum gap between two recipients, an input of the estimate. */
    private val sendDelay: Duration = MailingDeliveryWorker.DEFAULT_SEND_DELAY,
    private val sendDisplayRateLimiter: FederationInboxRateLimiter = SEND_DISPLAY_RATE_LIMITER,
) : IMailingService {
    override suspend fun listMailingLists(): List<MailingListDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val isBoard = current.role in BOARD_ROLES
            val today = OrganizationTimeZone.today()
            // V1.9.234: archived (deleted) lists are never listed. Stable order: name, then id.
            val rows =
                MailingListTable
                    .selectAll()
                    .where { MailingListTable.deletedAt.isNull() }
                    .orderBy(MailingListTable.name to SortOrder.ASC, MailingListTable.id to SortOrder.ASC)
                    .toList()
            val hasDynamic = rows.any { it[MailingListTable.kind] == MailingListKind.DYNAMIC }
            // Loaded ONCE for all dynamic lists (no per-list queries): the caller's own facts, for the board every ACTIVE member, the
            // committee/tier names for the explanations, and the opt-outs (all of them for the board, the caller's own otherwise).
            val me = if (hasDynamic) MemberFactsLoader.loadOne(memberId = current.memberId, today = today) else null
            val allMembers =
                if (hasDynamic &&
                    isBoard
                ) {
                    MemberFactsLoader.loadAll(today = today, statuses = setOf(MemberStatus.ACTIVE))
                } else {
                    null
                }
            val names = if (hasDynamic) loadRuleNames() else RuleNames(committees = emptyMap(), tiers = emptyMap())
            val optOuts: Map<Uuid, Map<Uuid, LocalDateTime>> =
                if (!hasDynamic) {
                    emptyMap()
                } else {
                    MailingListOptOutTable
                        .selectAll()
                        .let { q -> if (isBoard) q else q.where { MailingListOptOutTable.memberId eq current.memberId } }
                        .groupBy({ it[MailingListOptOutTable.mailingListId] }, {
                            it[MailingListOptOutTable.memberId] to
                                it[MailingListOptOutTable.optedOutAt]
                        })
                        .mapValues { it.value.toMap() }
                }
            rows.mapNotNull { row ->
                val listId = row[MailingListTable.id]
                if (row[MailingListTable.kind] == MailingListKind.DYNAMIC) {
                    dynamicListDto(
                        row = row,
                        isBoard = isBoard,
                        me = me,
                        allMembers = allMembers,
                        names = names,
                        optedOutOf = optOuts[listId].orEmpty(),
                        currentMemberId = current.memberId,
                        today = today,
                    )
                } else {
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
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.MAILING_LIST,
                entityId = id,
                action = AuditAction.CREATE,
                after = ruleSnapshotJson(kind = MailingListKind.MANUAL, mandatory = false, ruleJson = null),
            )
            // V1.9.234: no deleted_at filter -- the list was created in this very transaction.
            MailingListTable
                .selectAll()
                .where { MailingListTable.id eq id }
                .single()
                .toMailingListDto(subscriberCount = 0, isSubscribed = false)
        }
    }

    override suspend fun createDynamicMailingList(
        name: String,
        description: String?,
        ruleJson: String,
        mandatory: Boolean,
    ): MailingListDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val validName = name.trim()
        if (validName.isEmpty()) throw BadRequestException("Name darf nicht leer sein.")
        if (validName.length > MAX_LIST_NAME_CHARS) throw BadRequestException("Name zu lang (max. $MAX_LIST_NAME_CHARS Zeichen).")
        val validDescription = description?.trim()?.takeIf { it.isNotEmpty() }
        if (validDescription != null && validDescription.length > MAX_LIST_DESCRIPTION_CHARS) {
            throw BadRequestException("Beschreibung zu lang (max. $MAX_LIST_DESCRIPTION_CHARS Zeichen).")
        }
        val rule = parseRuleOrBadRequest(ruleJson)
        val canonical = MemberRuleCodec.encode(rule)
        val now = DbClock.nowLocalDateTime()
        return transaction {
            RuleReferences.lockAndValidate(rule = rule, context = RuleContext.MAILING, selfCommitteeId = null)
            val id = Uuid.random()
            MailingListTable.insert {
                it[MailingListTable.id] = id
                it[MailingListTable.name] = validName
                it[MailingListTable.description] = validDescription
                it[createdBy] = current.memberId
                it[kind] = MailingListKind.DYNAMIC
                it[MailingListTable.ruleJson] = canonical
                it[MailingListTable.mandatory] = mandatory
                it[ruleUpdatedAt] = now
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.MAILING_LIST,
                entityId = id,
                action = AuditAction.CREATE,
                after = ruleSnapshotJson(kind = MailingListKind.DYNAMIC, mandatory = mandatory, ruleJson = canonical),
                occurredAt = now,
            )
            boardDtoOf(id = id, current = current)
        }
    }

    override suspend fun updateMailingListRule(
        mailingListId: String,
        ruleJson: String,
    ): MailingListDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val listId = Uuid.parse(mailingListId)
        val rule = parseRuleOrBadRequest(ruleJson)
        val canonical = MemberRuleCodec.encode(rule)
        val now = DbClock.nowLocalDateTime()
        return transaction {
            RuleReferences.lockAndValidate(rule = rule, context = RuleContext.MAILING, selfCommitteeId = null)
            // Locks the LIST row (never a member row); the audit chain lock is the last lock of this transaction.
            val row =
                MailingListTable
                    .selectAll()
                    .where { (MailingListTable.id eq listId) and MailingListTable.deletedAt.isNull() }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("MailingList $mailingListId not found")
            if (row[MailingListTable.kind] != MailingListKind.DYNAMIC) {
                throw ConflictException("Nur automatische Listen haben eine Regel.")
            }
            val before = row[MailingListTable.ruleJson]
            if (before != canonical) {
                MailingListTable.update({ MailingListTable.id eq listId }) {
                    it[MailingListTable.ruleJson] = canonical
                    it[ruleUpdatedAt] = now
                }
                val mandatory = row[MailingListTable.mandatory]
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.MAILING_LIST,
                    entityId = listId,
                    action = AuditAction.UPDATE,
                    before = ruleSnapshotJson(kind = MailingListKind.DYNAMIC, mandatory = mandatory, ruleJson = before),
                    after = ruleSnapshotJson(kind = MailingListKind.DYNAMIC, mandatory = mandatory, ruleJson = canonical),
                    occurredAt = now,
                )
            }
            boardDtoOf(id = listId, current = current)
        }
    }

    override suspend fun previewDynamicRecipients(
        ruleJson: String,
        mailingListId: String?,
        mandatory: Boolean,
    ): RecipientPreviewDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        requireWithinSendDisplayRate(current.memberId)
        val rule = parseRuleOrBadRequest(ruleJson)
        val listId = mailingListId?.let { Uuid.parse(it) }
        val today = OrganizationTimeZone.today()
        return transaction {
            // For an existing list the stored flag decides, never the caller's parameter.
            val effectiveMandatory =
                if (listId == null) {
                    mandatory
                } else {
                    MailingListTable
                        .selectAll()
                        .where { (MailingListTable.id eq listId) and MailingListTable.deletedAt.isNull() }
                        .singleOrNull()
                        ?.get(MailingListTable.mandatory)
                        ?: throw NotFoundException("MailingList $mailingListId not found")
                }
            val resolution = resolveDynamicRecipients(rule = rule, listId = listId, mandatory = effectiveMandatory, today = today)
            RecipientPreviewDto(
                eligible = resolution.recipientIds.size,
                excludedOptedOut = resolution.excludedOptedOut,
                excludedNoEmail = resolution.excludedNoEmail,
            )
        }
    }

    override suspend fun optOutOfDynamicList(mailingListId: String) {
        val current = resolveCurrentMember(call)
        val listId = parseListIdOrNotFound(mailingListId)
        val now = DbClock.nowLocalDateTime()
        transaction {
            requireActiveMembership(memberId = current.memberId)
            requireIncludedDynamicList(listId = listId, memberId = current.memberId, mailingListId = mailingListId)
            val exists =
                MailingListOptOutTable
                    .selectAll()
                    .where { (MailingListOptOutTable.mailingListId eq listId) and (MailingListOptOutTable.memberId eq current.memberId) }
                    .count() > 0
            if (!exists) {
                try {
                    withSavepoint(name = "mailing_opt_out") {
                        MailingListOptOutTable.insert {
                            it[id] = Uuid.random()
                            it[MailingListOptOutTable.mailingListId] = listId
                            it[memberId] = current.memberId
                            it[optedOutAt] = now
                        }
                    }
                } catch (e: ExposedSQLException) {
                    // A concurrent identical opt-out: the postcondition holds, idempotent. Anything else is a real failure.
                    if (!e.isUniqueViolation()) throw e
                }
            }
        }
    }

    override suspend fun revokeOptOut(mailingListId: String) {
        val current = resolveCurrentMember(call)
        val listId = parseListIdOrNotFound(mailingListId)
        transaction {
            requireActiveMembership(memberId = current.memberId)
            requireIncludedDynamicList(listId = listId, memberId = current.memberId, mailingListId = mailingListId)
            MailingListOptOutTable.deleteWhere {
                (MailingListOptOutTable.mailingListId eq listId) and (MailingListOptOutTable.memberId eq current.memberId)
            }
        }
    }

    override suspend fun deleteMailingList(mailingListId: String) {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val listId = parseListIdOrNotFound(mailingListId)
        val now = DbClock.nowLocalDateTime()
        val removedKeys: List<String> =
            transaction {
                // Lock order everywhere: LIST row -> MESSAGE rows -> audit chain (last). Never a member row.
                val row =
                    MailingListTable
                        .selectAll()
                        .where { (MailingListTable.id eq listId) and MailingListTable.deletedAt.isNull() }
                        .forUpdate()
                        .singleOrNull()
                        ?: throw NotFoundException("MailingList $mailingListId not found")
                if (row[MailingListTable.kind] != MailingListKind.MANUAL) throw MailingListNotDeletableException()
                val queued =
                    MailingMessageTable
                        .selectAll()
                        .where {
                            (MailingMessageTable.mailingListId eq listId) and (MailingMessageTable.status eq MailingMessageStatus.QUEUED)
                        }.count()
                if (queued > 0) throw MailingListSendingInProgressException()
                // Lock the drafts (serializes against the attachment upload route, which locks the MESSAGE row), in a fixed order.
                val draftIds =
                    MailingMessageTable
                        .select(MailingMessageTable.id)
                        .where {
                            (MailingMessageTable.mailingListId eq listId) and (MailingMessageTable.status eq MailingMessageStatus.DRAFT)
                        }.orderBy(MailingMessageTable.id to SortOrder.ASC)
                        .forUpdate()
                        .map { it[MailingMessageTable.id] }
                if (draftIds.isNotEmpty()) {
                    // Defence in depth: delivery rows / frozen links only exist from QUEUED on; refuse instead of a FK violation.
                    val hasEvidence =
                        !MailingDeliveryLogTable.selectAll().where { MailingDeliveryLogTable.mailingMessageId inList draftIds }.empty() ||
                            !MailingMessageLinkTable.selectAll().where { MailingMessageLinkTable.mailingMessageId inList draftIds }.empty()
                    if (hasEvidence) throw ConflictException("Ein Entwurf dieser Liste hat bereits Zustelldaten.")
                }
                val keys = draftIds.flatMap { MailingAttachmentStore.deleteAllForMessageInTx(it) }
                val removedDrafts =
                    if (draftIds.isEmpty()) {
                        0
                    } else {
                        MailingMessageTable.deleteWhere {
                            (MailingMessageTable.id inList draftIds) and (MailingMessageTable.status eq MailingMessageStatus.DRAFT)
                        }
                    }
                val removedSubs = MailingListSubscriptionTable.deleteWhere { MailingListSubscriptionTable.mailingListId eq listId }
                // Opt-outs only exist for DYNAMIC lists in practice; removed anyway because soft delete does not cascade.
                val removedOptOuts = MailingListOptOutTable.deleteWhere { MailingListOptOutTable.mailingListId eq listId }
                val retained =
                    MailingMessageTable
                        .selectAll()
                        .where {
                            (MailingMessageTable.mailingListId eq listId) and
                                (MailingMessageTable.status inList listOf(MailingMessageStatus.SENT, MailingMessageStatus.FAILED))
                        }.count()
                        .toInt()
                MailingListTable.update({ MailingListTable.id eq listId }) {
                    it[deletedAt] = now
                    it[deletedBy] = current.memberId
                }
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.MAILING_LIST,
                    entityId = listId,
                    action = AuditAction.VOID,
                    before =
                        Json.encodeToString(
                            MailingListDeletionSnapshot.serializer(),
                            MailingListDeletionSnapshot(
                                kind = row[MailingListTable.kind],
                                removedSubscriptions = removedSubs,
                                removedOptOuts = removedOptOuts,
                                removedDrafts = removedDrafts,
                                removedAttachments = keys.size,
                                retainedSentMessages = retained,
                            ),
                        ),
                    occurredAt = now,
                )
                keys
            }
        // AFTER commit only -- no file-system effect inside transaction {}.
        MailingAttachmentStore.deleteFilesAfterCommit(root = attachmentStorageRoot, keys = removedKeys)
        logger.info { "Mailing list $listId deleted: ${removedKeys.size} draft attachment file(s) removed" }
    }

    override suspend fun subscribe(mailingListId: String) {
        val current = resolveCurrentMember(call)
        val listId = Uuid.parse(mailingListId)
        val now = DbClock.nowLocalDateTime()
        transaction {
            requireActiveMembership(memberId = current.memberId)
            requireManualListOrNotFound(listId = listId, lock = true)
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
            requireManualListOrNotFound(listId = listId)
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
            requireManualListOrNotFound(listId = listId, lock = true)
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
        val listId = parseListIdOrNotFound(mailingListId)
        val targetMemberId = Uuid.parse(memberId)
        val now = DbClock.nowLocalDateTime()
        transaction {
            // V1.9.234: a deleted list answers NotFound; the row lock serializes against deleteMailingList.
            val kind = requireLiveMailingList(listId = listId, lock = true)[MailingListTable.kind]
            if (kind == MailingListKind.DYNAMIC) throw ConflictException("Automatische Listen haben keine festen Abonnenten.")
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
        val listId = parseListIdOrNotFound(mailingListId)
        return transaction {
            // A DYNAMIC list has no stored subscribers; its recipients are computed at send time and never listed by name.
            val kind = requireLiveMailingList(listId = listId)[MailingListTable.kind]
            if (kind == MailingListKind.DYNAMIC) {
                emptyList()
            } else {
                (MailingListSubscriptionTable innerJoin MemberTable)
                    .selectAll()
                    .where { MailingListSubscriptionTable.mailingListId eq listId }
                    .map { it.toMailingListSubscriptionDto() }
            }
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
            requireLiveMailingList(listId = parseListIdOrNotFound(mailingListId), lock = true)
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
            requireLiveMailingList(listId = parseListIdOrNotFound(mailingListId), lock = true)
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
        val (message, list) =
            transaction {
                val row = MailingMessageTable.selectAll().where { MailingMessageTable.id eq id }.singleOrNull()
                row to
                    row?.let {
                        // V1.9.234: no deleted_at filter -- message-bound lookup: a SENT/FAILED message keeps its archived list.
                        MailingListTable
                            .selectAll()
                            .where { MailingListTable.id eq it[MailingMessageTable.mailingListId] }
                            .singleOrNull()
                    }
            }
        if (message == null) throw NotFoundException("MailingMessage $messageId not found")
        val sanitized = message[MailingMessageTable.bodyHtml]?.let { MailingHtmlSanitizer.sanitize(it) }
        val rendered =
            MailingMailRenderer.render(
                subject = message[MailingMessageTable.subject],
                content = sanitized,
                legacyBodyText = message[MailingMessageTable.bodyText],
                branding = branding,
                notice = if (list?.get(MailingListTable.mandatory) == true) MailingNotice.MANDATORY else MailingNotice.NONE,
                dynamicList = list?.get(MailingListTable.kind) == MailingListKind.DYNAMIC,
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
        val listId = parseListIdOrNotFound(mailingListId)
        return transaction {
            requireLiveMailingList(listId = listId)
            val rows = MailingMessageTable.selectAll().where { MailingMessageTable.mailingListId eq listId }.toList()
            // V1.9.232: the attachments of ALL messages in ONE query (no N+1).
            val attachments = MailingAttachmentStore.listForMessages(rows.map { it[MailingMessageTable.id] })
            rows.map { it.toMailingMessageDto(attachments[it[MailingMessageTable.id]].orEmpty()) }
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
                // V1.9.234: lock the LIST row first (same order as deleteMailingList: list -> message) and refuse a deleted list, so a
                // QUEUED message can never end up on a list that is being / was deleted.
                val listIdOfMessage =
                    MailingMessageTable
                        .select(MailingMessageTable.mailingListId)
                        .where { MailingMessageTable.id eq id }
                        .singleOrNull()
                        ?.get(MailingMessageTable.mailingListId)
                        ?: throw NotFoundException("MailingMessage $messageId not found")
                requireLiveMailingList(listId = listIdOfMessage, lock = true)
                // Bounded, idempotent conditional transition -- see class KDoc. A second call on an
                // already-QUEUED/SENT/FAILED message updates 0 rows and throws below, rather than
                // silently re-queuing (the pre-V1.9.7 bug this fixes: a repeat click used to write a
                // second full round of delivery-log rows).
                val updated =
                    MailingMessageTable.update(
                        { (MailingMessageTable.id eq id) and (MailingMessageTable.status eq MailingMessageStatus.DRAFT) },
                    ) {
                        it[status] = MailingMessageStatus.QUEUED
                        it[queuedAt] = now
                    }
                if (updated == 0) {
                    val exists = MailingMessageTable.selectAll().where { MailingMessageTable.id eq id }.count() > 0
                    if (!exists) throw NotFoundException("MailingMessage $messageId not found")
                    throw ConflictException("MailingMessage $messageId was already sent or queued")
                }
                // V1.9.232: defence in depth -- the upload route enforces these under the same row lock; a violation here rolls back.
                val (attachmentCount, attachmentBytes) = MailingAttachmentStore.countAndTotalBytes(id)
                if (attachmentCount > AttachmentRules.MAX_FILES || attachmentBytes > AttachmentRules.MAX_TOTAL_BYTES) {
                    throw ConflictException("Die Anhänge dieser Nachricht überschreiten die zulässigen Grenzen.")
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
                // see IMailingService.sendMailingMessage KDoc. ONE shared function with the send estimate.
                // V1.9.234: no deleted_at filter -- the list row was locked live a few lines above.
                val listRow = MailingListTable.selectAll().where { MailingListTable.id eq listId }.single()
                val dynamic = listRow[MailingListTable.kind] == MailingListKind.DYNAMIC
                // Defence in depth: a mandatory list can only ever be sent by Board/Admin (BOARD_ROLES already says so; this line keeps
                // it true if the general send right is ever widened).
                if (listRow[MailingListTable.mandatory]) current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)
                // V1.9.111: a DYNAMIC list writes NO delivery rows here -- its recipients are resolved by the worker at SEND time
                // (MailingDeliveryWorker.resolveRecipientsIfNeeded). The size is only checked now for fail-fast feedback.
                val eligibleRecipients =
                    if (dynamic) {
                        val rule = storedRuleOrConflict(listRow)
                        resolveDynamicRecipients(
                            rule = rule,
                            listId = listId,
                            mandatory = listRow[MailingListTable.mandatory],
                            today = OrganizationTimeZone.today(),
                        ).recipientIds
                    } else {
                        eligibleRecipientIds(listId)
                    }

                if (eligibleRecipients.size > MailingHtmlPolicy.MAX_RECIPIENTS) {
                    // Throwing here rolls back the whole transaction, including the DRAFT->QUEUED
                    // transition above -- the message is left exactly as it was (still DRAFT).
                    throw BadRequestException(
                        "Zu viele Empfänger (${eligibleRecipients.size}, max. ${MailingHtmlPolicy.MAX_RECIPIENTS}).",
                    )
                }

                (if (dynamic) emptyList() else eligibleRecipients).forEach { subscriberId ->
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
                    .toMailingMessageDto(MailingAttachmentStore.listForMessage(id))
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

    override suspend fun mailingSendEstimate(messageId: String): MailingSendEstimateDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        requireWithinSendDisplayRate(current.memberId)
        val id = Uuid.parse(messageId)
        val recipients =
            transaction {
                val message =
                    MailingMessageTable.selectAll().where { MailingMessageTable.id eq id }.singleOrNull()
                        ?: throw NotFoundException("MailingMessage $messageId not found")
                // V1.9.234: no deleted_at filter -- message-bound lookup: a SENT/FAILED message keeps its archived list.
                val listRow =
                    MailingListTable
                        .selectAll()
                        .where { MailingListTable.id eq message[MailingMessageTable.mailingListId] }
                        .single()
                val dynamic = listRow[MailingListTable.kind] == MailingListKind.DYNAMIC
                val unresolved = message[MailingMessageTable.recipientsResolvedAt] == null
                val status = message[MailingMessageTable.status]
                if (dynamic && (status == MailingMessageStatus.DRAFT || (status == MailingMessageStatus.QUEUED && unresolved))) {
                    // EXACTLY the recipients the worker would resolve right now (one shared function).
                    resolveDynamicRecipients(
                        rule = storedRuleOrConflict(listRow),
                        listId = listRow[MailingListTable.id],
                        mandatory = listRow[MailingListTable.mandatory],
                        today = OrganizationTimeZone.today(),
                    ).recipientIds.size
                } else if (status == MailingMessageStatus.DRAFT) {
                    // EXACTLY the recipients sendMailingMessage would queue.
                    eligibleRecipientIds(message[MailingMessageTable.mailingListId]).size
                } else {
                    // Already queued / finished: what is still to do.
                    countDeliveries(messageId = id, status = DeliveryStatus.PENDING)
                }
            }
        val bulkPerHour = budget.bulkPerHour
        return MailingSendEstimateDto(
            recipientCount = recipients,
            bulkBudgetPerHour = bulkPerHour,
            estimatedSeconds =
                MailingEta.estimateSeconds(
                    remaining = recipients,
                    minDelayMs = sendDelay.inWholeMilliseconds,
                    bulkPerHour = bulkPerHour,
                ),
            spansMultipleHours = bulkPerHour != null && recipients > bulkPerHour,
        )
    }

    override suspend fun mailingSendProgress(messageId: String): MailingSendProgressDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        requireWithinSendDisplayRate(current.memberId)
        val id = Uuid.parse(messageId)
        val now = DbClock.nowLocalDateTime()
        var awaitingResolution = false
        val counts =
            transaction {
                val message =
                    MailingMessageTable.selectAll().where { MailingMessageTable.id eq id }.singleOrNull()
                        ?: throw NotFoundException("MailingMessage $messageId not found")
                // V1.9.234: no deleted_at filter -- message-bound lookup: a SENT/FAILED message keeps its archived list.
                val dynamic =
                    MailingListTable
                        .selectAll()
                        .where { MailingListTable.id eq message[MailingMessageTable.mailingListId] }
                        .singleOrNull()
                        ?.get(MailingListTable.kind) == MailingListKind.DYNAMIC
                awaitingResolution =
                    dynamic &&
                    message[MailingMessageTable.status] == MailingMessageStatus.QUEUED &&
                    message[MailingMessageTable.recipientsResolvedAt] == null
                DeliveryStatus.entries.associateWith { countDeliveries(messageId = id, status = it) }
            }

        fun count(vararg statuses: DeliveryStatus): Int = statuses.sumOf { counts.getValue(it) }
        val pending = count(DeliveryStatus.PENDING)
        var pausedUntil: kotlinx.datetime.LocalDateTime? = null
        var reason: MailingPauseReason? = null
        if (pending > 0) {
            val providerPause = budget.bulkPausedUntil()?.takeIf { it > now }
            val deferral = earliestDeferral(messageId = id, now = now)
            val budgetDecision = budget.peek(lane = MailLane.BULK, now = now)
            when {
                providerPause != null -> {
                    pausedUntil = providerPause
                    reason = MailingPauseReason.PROVIDER_DEFERRAL
                }
                budgetDecision is BudgetDecision.WaitUntil -> {
                    pausedUntil = budgetDecision.at
                    reason = MailingPauseReason.HOURLY_BUDGET
                }
                deferral != null && !hasDueRow(messageId = id, now = now) -> {
                    pausedUntil = deferral
                    reason = MailingPauseReason.PROVIDER_DEFERRAL
                }
            }
        }
        val estimate =
            MailingEta.estimateSeconds(remaining = pending, minDelayMs = sendDelay.inWholeMilliseconds, bulkPerHour = budget.bulkPerHour)
        val extraWait =
            if (reason == MailingPauseReason.PROVIDER_DEFERRAL &&
                pausedUntil != null
            ) {
                now.secondsUntil(pausedUntil).coerceAtLeast(0)
            } else {
                0L
            }
        return MailingSendProgressDto(
            messageId = messageId,
            total = counts.values.sum(),
            sent = count(DeliveryStatus.SENT),
            failed = count(DeliveryStatus.FAILED, DeliveryStatus.BOUNCED),
            interrupted = count(DeliveryStatus.INTERRUPTED),
            skipped = count(DeliveryStatus.SKIPPED_NO_ADDRESS, DeliveryStatus.SKIPPED_UNSUBSCRIBED),
            pending = pending,
            pausedUntil = pausedUntil,
            pauseReason = reason,
            remainingSeconds = if (pending > 0) estimate + extraWait else null,
            awaitingRecipientResolution = awaitingResolution,
        )
    }

    private fun requireWithinSendDisplayRate(memberId: Uuid) {
        if (!sendDisplayRateLimiter.checkAndRecord("member:$memberId")) {
            throw ConflictException("Zu viele Anfragen -- bitte später erneut versuchen.")
        }
    }

    private fun countDeliveries(
        messageId: Uuid,
        status: DeliveryStatus,
    ): Int =
        MailingDeliveryLogTable
            .selectAll()
            .where { (MailingDeliveryLogTable.mailingMessageId eq messageId) and (MailingDeliveryLogTable.deliveryStatus eq status) }
            .count()
            .toInt()

    private fun earliestDeferral(
        messageId: Uuid,
        now: kotlinx.datetime.LocalDateTime,
    ): kotlinx.datetime.LocalDateTime? =
        transaction {
            MailingDeliveryLogTable
                .selectAll()
                .where {
                    (MailingDeliveryLogTable.mailingMessageId eq messageId) and
                        (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.PENDING) and
                        MailingDeliveryLogTable.nextAttemptAt.isNotNull() and
                        (MailingDeliveryLogTable.nextAttemptAt greater now)
                }.map { it[MailingDeliveryLogTable.nextAttemptAt] }
                .filterNotNull()
                .minOrNull()
        }

    private fun hasDueRow(
        messageId: Uuid,
        now: kotlinx.datetime.LocalDateTime,
    ): Boolean =
        transaction {
            MailingDeliveryLogTable
                .selectAll()
                .where {
                    (MailingDeliveryLogTable.mailingMessageId eq messageId) and
                        (MailingDeliveryLogTable.deliveryStatus eq DeliveryStatus.PENDING) and
                        MailingDeliveryLogTable.claimedAt.isNull() and
                        (MailingDeliveryLogTable.nextAttemptAt.isNull() or (MailingDeliveryLogTable.nextAttemptAt lessEq now))
                }.limit(1)
                .count() > 0
        }
}

/**
 * The recipients of a send to mailing list [listId]: ACTIVE, non-anonymized, non-deceased members with a still-active subscription.
 * The single definition behind BOTH `sendMailingMessage` (which queues exactly these) and `mailingSendEstimate` (which shows how many
 * they are) -- the confirmation dialog can never disagree with the send. Must run inside a transaction.
 */
internal fun eligibleRecipientIds(listId: Uuid): List<Uuid> =
    (MailingListSubscriptionTable innerJoin MemberTable)
        .selectAll()
        .where {
            (MailingListSubscriptionTable.mailingListId eq listId) and
                (MailingListSubscriptionTable.unsubscribedAt.isNull()) and
                (MemberTable.status eq MemberStatus.ACTIVE) and
                (MemberTable.anonymizedAt.isNull()) and
                (MemberTable.dateOfDeath.isNull())
        }.map { it[MailingListSubscriptionTable.memberId] }

/**
 * V1.9.234 -- the live (not deleted) list row, or [NotFoundException]. With [lock] the row is taken `FOR UPDATE`: the lock order of every
 * path touching a list is LIST row -> MESSAGE rows -> audit chain, so a write that depends on the list being alive (draft, send,
 * subscribe) serializes against [MailingService.deleteMailingList].
 */
private fun requireLiveMailingList(
    listId: Uuid,
    lock: Boolean = false,
): ResultRow {
    val query = MailingListTable.selectAll().where { (MailingListTable.id eq listId) and MailingListTable.deletedAt.isNull() }
    return (if (lock) query.forUpdate() else query).singleOrNull() ?: throw NotFoundException("MailingList $listId not found")
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
    ruleJson: String? = null,
    ruleExplanation: DynamicListExplanationDto? = null,
    optedOutAt: LocalDateTime? = null,
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
        kind = this[MailingListTable.kind],
        mandatory = this[MailingListTable.mandatory],
        ruleJson = ruleJson,
        ruleExplanation = ruleExplanation,
        currentMemberOptedOutAt = optedOutAt,
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

private fun ResultRow.toMailingMessageDto(attachments: List<MailingAttachmentDto> = emptyList()): MailingMessageDto =
    MailingMessageDto(
        id = this[MailingMessageTable.id].toString(),
        mailingListId = this[MailingMessageTable.mailingListId].toString(),
        subject = this[MailingMessageTable.subject],
        bodyText = this[MailingMessageTable.bodyText],
        bodyHtml = this[MailingMessageTable.bodyHtml],
        sentBy = this[MailingMessageTable.sentBy].toString(),
        sentAt = this[MailingMessageTable.sentAt],
        status = this[MailingMessageTable.status],
        attachments = attachments,
    )

// ---------------------------------------------------------------------------------------------------------------------------------------
// V1.9.111 "Dynamische Mailinglisten" -- helpers. Everything below that reads the database must run inside a caller's transaction.
// ---------------------------------------------------------------------------------------------------------------------------------------

/** Committee and tier names for the explanation sentences. Free text: the client must sanitise before showing. */
private data class RuleNames(
    val committees: Map<String, String>,
    val tiers: Map<String, String>,
)

private fun loadRuleNames(): RuleNames =
    RuleNames(
        committees = CommitteeTable.selectAll().associate { it[CommitteeTable.id].toString() to it[CommitteeTable.name] },
        tiers = MembershipTierTable.selectAll().associate { it[MembershipTierTable.id].toString() to it[MembershipTierTable.name] },
    )

/** The message never contains the rejected input -- only a fixed text. */
private fun parseRuleOrBadRequest(ruleJson: String): MemberRule =
    try {
        MemberRuleCodec.parse(text = ruleJson, context = RuleContext.MAILING)
    } catch (e: InvalidMemberRuleException) {
        throw BadRequestException("Ungültige Regel.")
    }

private fun storedRuleOrNull(row: ResultRow): MemberRule? =
    row[MailingListTable.ruleJson]?.let {
        try {
            MemberRuleCodec.parse(text = it, context = RuleContext.MAILING)
        } catch (e: InvalidMemberRuleException) {
            null
        }
    }

internal fun storedRuleOrConflict(row: ResultRow): MemberRule =
    storedRuleOrNull(row) ?: throw ConflictException("Die Regel dieser Liste ist ungültig.")

private fun ruleSnapshotJson(
    kind: MailingListKind,
    mandatory: Boolean,
    ruleJson: String?,
): String =
    Json.encodeToString(
        MailingListRuleSnapshot.serializer(),
        MailingListRuleSnapshot(kind = kind, mandatory = mandatory, ruleJson = ruleJson),
    )

private fun parseListIdOrNotFound(mailingListId: String): Uuid =
    runCatching { Uuid.parse(mailingListId) }.getOrNull() ?: throw NotFoundException("MailingList $mailingListId not found")

/** Self-service paths of MANUAL lists: a DYNAMIC list answers exactly like a list that does not exist (no way to probe its rule). */
private fun requireManualListOrNotFound(
    listId: Uuid,
    lock: Boolean = false,
) {
    // V1.9.234: unknown and deleted lists answer exactly like a DYNAMIC one.
    val kind = runCatching { requireLiveMailingList(listId = listId, lock = lock)[MailingListTable.kind] }.getOrNull()
    if (kind != MailingListKind.MANUAL) throw NotFoundException("MailingList $listId not found")
}

/**
 * The opt-out paths: only when the list is DYNAMIC and its rule includes the caller right now; EVERYTHING else (unknown list, MANUAL list,
 * rule does not include the caller) is the same generic [NotFoundException], so the call cannot be used to probe a rule. A mandatory list
 * is refused only after that check ([BadRequestException]).
 */
private fun requireIncludedDynamicList(
    listId: Uuid,
    memberId: Uuid,
    mailingListId: String,
) {
    fun notFound(): Nothing = throw NotFoundException("MailingList $mailingListId not found")
    val row =
        MailingListTable
            .selectAll()
            .where { (MailingListTable.id eq listId) and MailingListTable.deletedAt.isNull() }
            .singleOrNull()
            ?: notFound()
    if (row[MailingListTable.kind] != MailingListKind.DYNAMIC) notFound()
    val rule = storedRuleOrNull(row) ?: notFound()
    val today = OrganizationTimeZone.today()
    val me = MemberFactsLoader.loadOne(memberId = memberId, today = today) ?: notFound()
    val mandatory = row[MailingListTable.mandatory]
    if (!includes(rule = rule, member = me, mandatory = mandatory, today = today)) notFound()
    if (mandatory) throw BadRequestException("Eine Pflichtliste kann nicht abbestellt werden.")
}

/** Whether the rule reaches [member] -- the guard WITHOUT the opt-out (an opted-out member still sees the list and can come back). */
private fun includes(
    rule: MemberRule,
    member: LoadedMember,
    mandatory: Boolean,
    today: LocalDate,
): Boolean =
    rule.matches(facts = member.facts, today = today) &&
        MailingRecipientGuard.eligible(
            f =
                RecipientFacts(
                    member = member.facts,
                    anonymised = member.anonymised,
                    deceased = member.deceased,
                    hasValidEmail = isValidMailboxAddress(member.email),
                    optedOut = false,
                ),
            mandatory = mandatory,
        )

/** One DYNAMIC list as the caller may see it, or `null` if it must stay invisible to them. */
private fun dynamicListDto(
    row: ResultRow,
    isBoard: Boolean,
    me: LoadedMember?,
    allMembers: List<LoadedMember>?,
    names: RuleNames,
    optedOutOf: Map<Uuid, LocalDateTime>,
    currentMemberId: Uuid,
    today: LocalDate,
): MailingListDto? {
    val mandatory = row[MailingListTable.mandatory]
    val rule = storedRuleOrNull(row)
    if (rule == null) {
        // Unparseable stored rule (cannot happen through the API): the board sees the broken list, nobody else.
        return if (isBoard) {
            row.toMailingListDto(
                subscriberCount = 0,
                isSubscribed = false,
                ruleJson = row[MailingListTable.ruleJson],
            )
        } else {
            null
        }
    }
    val included = me != null && includes(rule = rule, member = me, mandatory = mandatory, today = today)
    if (!isBoard && !included) return null
    val ownOptOut = if (mandatory) null else optedOutOf[currentMemberId]
    val count =
        if (isBoard && allMembers != null) {
            resolveAmong(
                members = allMembers,
                rule = rule,
                optedOut = optedOutOf.keys,
                mandatory = mandatory,
                today = today,
            ).recipientIds.size
        } else {
            0
        }
    return row.toMailingListDto(
        subscriberCount = count,
        isSubscribed = included && ownOptOut == null,
        ruleJson = if (isBoard) row[MailingListTable.ruleJson] else null,
        // A member only gets the conditions THEY satisfy (no names of other committees); the board sees the whole rule.
        ruleExplanation = explainRule(rule = rule, facts = if (isBoard) null else me?.facts, today = today, names = names),
        optedOutAt = ownOptOut,
    )
}

/** The board's view of one list right after a write (inside the writing transaction). */
private fun boardDtoOf(
    id: Uuid,
    current: CurrentMember,
): MailingListDto {
    val today = OrganizationTimeZone.today()
    val row = MailingListTable.selectAll().where { (MailingListTable.id eq id) and MailingListTable.deletedAt.isNull() }.single()
    val optedOut =
        MailingListOptOutTable
            .selectAll()
            .where { MailingListOptOutTable.mailingListId eq id }
            .associate { it[MailingListOptOutTable.memberId] to it[MailingListOptOutTable.optedOutAt] }
    return dynamicListDto(
        row = row,
        isBoard = true,
        me = MemberFactsLoader.loadOne(memberId = current.memberId, today = today),
        allMembers = MemberFactsLoader.loadAll(today = today, statuses = setOf(MemberStatus.ACTIVE)),
        names = loadRuleNames(),
        optedOutOf = optedOut,
        currentMemberId = current.memberId,
        today = today,
    ) ?: row.toMailingListDto(subscriberCount = 0, isSubscribed = false)
}

/**
 * Turns a rule into structured, localisable items. With [facts] only the conditions that member satisfies (and, per condition, only the
 * values that satisfy it); with `null` the whole rule.
 */
private fun explainRule(
    rule: MemberRule,
    facts: MemberFacts?,
    today: LocalDate,
    names: RuleNames,
): DynamicListExplanationDto =
    DynamicListExplanationDto(
        op = rule.op,
        items =
            rule.conditions.mapNotNull { condition ->
                if (facts != null && !condition.matches(facts = facts, today = today)) return@mapNotNull null
                when (condition) {
                    is RuleCondition.Role ->
                        RuleExplanationItem(
                            kind = RuleExplanationKind.ROLE,
                            labels =
                                condition.values
                                    .filter { facts == null || it in facts.roles }
                                    .map { it.name }
                                    .sorted(),
                        )
                    is RuleCondition.JoinedWithinDays ->
                        RuleExplanationItem(
                            kind = RuleExplanationKind.JOINED_WITHIN_DAYS,
                            days = condition.days,
                        )
                    is RuleCondition.Committee ->
                        RuleExplanationItem(
                            kind = RuleExplanationKind.COMMITTEE,
                            labels =
                                condition.committeeIds
                                    .filter { facts == null || it in facts.activeCommitteeIds }
                                    .mapNotNull { names.committees[it] }
                                    .sorted(),
                        )
                    is RuleCondition.Tier ->
                        RuleExplanationItem(
                            kind = RuleExplanationKind.TIER,
                            labels =
                                condition.tierIds
                                    .filter { facts == null || it == facts.tierId }
                                    .mapNotNull { names.tiers[it] }
                                    .sorted(),
                        )
                    // Not offered in the MAILING context (the parser rejects it); never explained.
                    is RuleCondition.Status -> null
                }
            },
    )
