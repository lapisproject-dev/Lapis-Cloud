package network.lapis.cloud.server.member

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PrivilegedActionRequestTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.PeerExecutedEvent
import network.lapis.cloud.server.mail.PeerNotificationMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.LockedPeerFacts
import network.lapis.cloud.server.security.PasswordResetTokenStore
import network.lapis.cloud.server.security.PeerDecision
import network.lapis.cloud.server.security.PeerDeniedSignal
import network.lapis.cloud.server.security.PeerGuard
import network.lapis.cloud.server.security.PeerPolicy
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.TemporaryPasswordGenerator
import network.lapis.cloud.server.security.peerGuarded
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MailDeliveryState
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.MemberStatusTransitions
import network.lapis.cloud.shared.domain.PeerAction
import network.lapis.cloud.shared.domain.PeerActionAuditFacts
import network.lapis.cloud.shared.domain.PeerActionDecisionDto
import network.lapis.cloud.shared.domain.PeerActionDecisionsDto
import network.lapis.cloud.shared.domain.PeerAuditEvent
import network.lapis.cloud.shared.domain.PeerDecisionKind
import network.lapis.cloud.shared.domain.PrivilegedActionKind
import network.lapis.cloud.shared.domain.PrivilegedActionOverviewDto
import network.lapis.cloud.shared.domain.PrivilegedActionRequestDto
import network.lapis.cloud.shared.domain.PrivilegedActionStatus
import network.lapis.cloud.shared.domain.PrivilegedPasswordResultDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PeerProtectionDeniedException
import network.lapis.cloud.shared.rpc.PrivilegedActionStateException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Outcome of the unauthenticated objection link, see [PrivilegedActionService.vetoByLink]. */
internal enum class VetoResult { VETOED, INVALID }

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- the four-eyes lifecycle of the actions one ADMIN may take against another ADMIN only with
 * the approval of a second one (temporary password, demotion, suspension). Shared by the RPC facade, the unauthenticated
 * objection route and the poller ([PrivilegedActionPoller]); no RPC dependency of its own. See
 * `docs/architecture/admin-peer-protection.adoc` for the matrix, the lifecycle and the threat model.
 *
 * **Layering of every write**: validate -> authorize -> `transaction { lock target member; lock the account union; (lock the
 * request row); re-decide on the locked facts; write; audit }` -> AFTER the commit: mails, session and token invalidation (no
 * external effect inside a transaction). Raw tokens and the generated password are local variables between creation and
 * hand-off and never reach a log, an audit snapshot or a return value other than the one-time answer.
 *
 * **Approver eligibility** (`PeerGuard.eligibleApproverIds`): ADMIN at the time of the approval (read under lock), not the
 * requester, not the target, member status not login-blocked, and ADMIN for at least 7 days at the time of the REQUEST -- the
 * tenure rule keeps one person from creating a strawman administrator who approves their request.
 *
 * **Lock order**: target member -> account union -> request row; `AuditLogRecorder.record` is the last lock-taking call.
 */
internal class PrivilegedActionService(
    private val smtpConfigState: SmtpConfigState,
    private val mailer: PeerNotificationMailer,
    /** Per-requester cap on new requests (singleton; the RPC facade is rebuilt per call). */
    private val actorRateLimiter: FederationInboxRateLimiter,
    /** Per-target cap on new requests (singleton). */
    private val targetRateLimiter: FederationInboxRateLimiter,
) {
    private val mailConfigured: Boolean get() = smtpConfigState is SmtpConfigState.Configured
    private val notifier = PeerNotifier(mailer = mailer, smtpConfigState = smtpConfigState)

    // ------------------------------------------------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------------------------------------------------

    /** UI-only: the decision of every action against [targetIdRaw]. The server re-decides on every call. */
    fun decisions(
        actor: CurrentMember,
        targetIdRaw: String,
    ): PeerActionDecisionsDto {
        if (actor.role != AccountRole.ADMIN &&
            actor.role != AccountRole.BOARD &&
            actor.role != AccountRole.TREASURER
        ) {
            throw ForbiddenException()
        }
        val targetId = parseMemberId(targetIdRaw)
        val now = nowUtc()
        return transaction {
            if (MemberTable.selectAll().where { MemberTable.id eq targetId }.count() == 0L) throw NotFoundException("Member not found")
            val adminRows = AccountTable.selectAll().where { AccountTable.role eq AccountRole.ADMIN }.toList()
            val targetRole =
                AccountTable
                    .selectAll()
                    .where { AccountTable.memberId eq targetId }
                    .singleOrNull()
                    ?.get(AccountTable.role)
            val eligible =
                PeerGuard.eligibleApproverCount(
                    lockedAdminRows = adminRows,
                    excluding = setOf(actor.memberId, targetId),
                    requestCreatedAt = now,
                )
            PeerActionDecisionsDto(
                memberId = targetId.toString(),
                decisions =
                    PeerAction.entries.map { action ->
                        PeerPolicy
                            .decide(
                                actorRole = actor.role,
                                actorId = actor.memberId,
                                targetRole = targetRole,
                                targetId = targetId,
                                action = action,
                                eligibleApprovers = eligible,
                                mailConfigured = mailConfigured,
                            ).toDto(action = action, eligible = eligible)
                    },
            )
        }
    }

    /** The "Ausstehende Freigaben" card. */
    fun overview(actor: CurrentMember): PrivilegedActionOverviewDto {
        actor.requireRole(AccountRole.ADMIN)
        val now = nowUtc()
        return transaction {
            val open = PrivilegedActionStore.openRows(limit = OVERVIEW_LIMIT)
            val mine =
                PrivilegedActionStore.requestedBy(
                    actorId = actor.memberId,
                    since = PrivilegedActionStore.plus(at = now, duration = (-RECENT_DAYS).days),
                    limit = OVERVIEW_LIMIT,
                )
            val adminRows = AccountTable.selectAll().where { AccountTable.role eq AccountRole.ADMIN }.toList()
            val adminIds = adminRows.map { it[AccountTable.memberId] }.toSet()
            val awaiting =
                open.filter { row ->
                    // A request whose requester or target is no longer an ADMIN is stale (approving it would only end it as INVALIDATED).
                    row[PrivilegedActionRequestTable.actorMemberId] in adminIds &&
                        row[PrivilegedActionRequestTable.targetMemberId] in adminIds &&
                        row[PrivilegedActionRequestTable.status] == PrivilegedActionStatus.PENDING.name &&
                        row[PrivilegedActionRequestTable.expiresAt] > now &&
                        actor.memberId in
                        PeerGuard.eligibleApproverIds(
                            lockedAdminRows = adminRows,
                            excluding =
                                setOf(
                                    row[PrivilegedActionRequestTable.actorMemberId],
                                    row[PrivilegedActionRequestTable.targetMemberId],
                                ),
                            requestCreatedAt = row[PrivilegedActionRequestTable.createdAt],
                        )
                }
            val names =
                displayNames(
                    (open + mine).flatMap {
                        listOf(it[PrivilegedActionRequestTable.actorMemberId], it[PrivilegedActionRequestTable.targetMemberId])
                    },
                )
            PrivilegedActionOverviewDto(
                awaitingMyApproval = awaiting.map { it.toDto(names) },
                requestedByMe = mine.map { it.toDto(names) },
            )
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Creating a request
    // ------------------------------------------------------------------------------------------------------------

    fun requestTemporaryPassword(
        actor: CurrentMember,
        targetIdRaw: String,
        reasonRaw: String,
    ): PrivilegedActionRequestDto =
        create(
            actor = actor,
            targetIdRaw = targetIdRaw,
            kind = PrivilegedActionKind.TEMP_PASSWORD,
            requestedRole = null,
            requestedStatus = null,
            reasonRaw = reasonRaw,
        )

    fun requestDemotion(
        actor: CurrentMember,
        targetIdRaw: String,
        newRole: AccountRole,
        reasonRaw: String,
    ): PrivilegedActionRequestDto {
        if (newRole == AccountRole.ADMIN) throw BadRequestException("The new role must not be ADMIN")
        return create(
            actor = actor,
            targetIdRaw = targetIdRaw,
            kind = PrivilegedActionKind.DEMOTE,
            requestedRole = newRole,
            requestedStatus = null,
            reasonRaw = reasonRaw,
        )
    }

    fun requestSuspension(
        actor: CurrentMember,
        targetIdRaw: String,
        newStatus: MemberStatus,
        reasonRaw: String,
    ): PrivilegedActionRequestDto {
        // DECEASED needs a date of death and its plausibility check -- an administrator who died is handled by demoting first.
        if (newStatus !in MemberStatusSets.LOGIN_BLOCKED || newStatus == MemberStatus.DECEASED) {
            throw BadRequestException("The new status must be a login-blocking status other than DECEASED")
        }
        return create(
            actor = actor,
            targetIdRaw = targetIdRaw,
            kind = PrivilegedActionKind.SUSPEND,
            requestedRole = null,
            requestedStatus = newStatus,
            reasonRaw = reasonRaw,
        )
    }

    private class Created(
        val id: Uuid,
        val dto: PrivilegedActionRequestDto,
        val targetEmail: String,
        val approverEmails: List<String>,
    )

    @Suppress("LongMethod")
    private fun create(
        actor: CurrentMember,
        targetIdRaw: String,
        kind: PrivilegedActionKind,
        requestedRole: AccountRole?,
        requestedStatus: MemberStatus?,
        reasonRaw: String,
    ): PrivilegedActionRequestDto {
        actor.requireRole(AccountRole.ADMIN)
        val targetId = parseMemberId(targetIdRaw)
        val reason = reasonRaw.trim()
        if (reason.length < REASON_MIN_LENGTH || reason.length > REASON_MAX_LENGTH) {
            throw BadRequestException("A reason is required ($REASON_MIN_LENGTH-$REASON_MAX_LENGTH characters)")
        }
        val peerAction = kind.toPeerAction()
        val vetoRaw = if (kind == PrivilegedActionKind.TEMP_PASSWORD) EmailChangeTokens.newRawToken() else null
        val now = nowUtc()
        val created =
            peerGuarded(actor = actor) {
                transaction {
                    val memberRow = PeerGuard.lockMember(targetId) ?: throw NotFoundException("Member not found")
                    val facts =
                        PeerGuard.lockFactsAfterMemberLock(
                            targetId = targetId,
                            memberRow = memberRow,
                            requesterId = actor.memberId,
                            approverCutoffBase = now,
                        )
                    val decision =
                        PeerPolicy.decide(
                            actorRole = actor.role,
                            actorId = actor.memberId,
                            targetRole = facts.targetRole,
                            targetId = targetId,
                            action = peerAction,
                            eligibleApprovers = facts.eligibleApprovers,
                            mailConfigured = mailConfigured,
                        )
                    when (decision) {
                        is PeerDecision.RequiresApproval -> Unit
                        is PeerDecision.Deny ->
                            throw PeerDeniedSignal(
                                decision = decision,
                                action = peerAction,
                                targetId = targetId,
                                targetRole = facts.targetRole,
                            )
                        // Allow/Mask: the target is not an ADMIN, the direct RPC applies -- a request makes no sense.
                        else -> throw PrivilegedActionStateException()
                    }
                    if (memberRow[MemberTable.anonymizedAt] !=
                        null
                    ) {
                        throw ConflictException("Member has been anonymized and can no longer be edited")
                    }
                    if (memberRow[MemberTable.status] == MemberStatus.DECEASED) throw ConflictException("The member is deceased")

                    // Budgets only after authorization passed: a caller who may not act on this target cannot burn its budget. Both
                    // counters run unconditionally (no short-circuit); throwing rolls the transaction back (nothing written yet).
                    val actorAllowed = actorRateLimiter.checkAndRecord("actor:${actor.memberId}")
                    val targetAllowed = targetRateLimiter.checkAndRecord("target:$targetId")
                    if (!actorAllowed || !targetAllowed) throw ConflictException("Too many requests -- try again later")

                    // An open request of the same kind: a lapsed one (the poller has not run yet) is finished lazily, a live one blocks.
                    PrivilegedActionStore.openLocked(targetId = targetId, action = kind)?.let { open ->
                        if (isLapsed(row = open, now = now)) {
                            PrivilegedActionStore.resolveLocked(
                                id = open[PrivilegedActionRequestTable.id],
                                status = PrivilegedActionStatus.EXPIRED,
                                now = now,
                            )
                            audit(
                                actor = null,
                                targetId = targetId,
                                memberRow = memberRow,
                                targetRole = facts.targetRole,
                                facts =
                                    PeerActionAuditFacts(
                                        event = PeerAuditEvent.EXPIRED,
                                        action = peerAction,
                                        requestId = open[PrivilegedActionRequestTable.id].toString(),
                                        targetRole = facts.targetRole,
                                    ),
                                now = now,
                            )
                        } else {
                            throw PrivilegedActionStateException()
                        }
                    }
                    val id =
                        try {
                            withSavepoint(name = "privileged_action_insert") {
                                PrivilegedActionStore.insertPending(
                                    action = kind,
                                    actorId = actor.memberId,
                                    targetId = targetId,
                                    targetRole = AccountRole.ADMIN,
                                    requestedRole = requestedRole,
                                    requestedStatus = requestedStatus,
                                    reason = reason,
                                    vetoTokenHash = vetoRaw?.let { EmailChangeTokens.hash(it) },
                                    now = now,
                                )
                            }
                        } catch (e: ExposedSQLException) {
                            // Only the unique constraint on (target, kind) means "an equal request is already open" -- anything else is a bug.
                            if (!e.isUniqueViolation()) throw e
                            throw PrivilegedActionStateException()
                        }
                    val approverIds =
                        PeerGuard.eligibleApproverIds(
                            lockedAdminRows = facts.lockedAccountRows,
                            excluding = setOf(actor.memberId, targetId),
                            requestCreatedAt = now,
                        )
                    val approverEmails = MemberTable.selectAll().where { MemberTable.id inList approverIds }.map { it[MemberTable.email] }
                    val row = PrivilegedActionStore.byId(id) ?: error("inserted request vanished")
                    val dto = row.toDto(displayNames(listOf(actor.memberId, targetId)))
                    // LAST lock-taking operation.
                    audit(
                        actor = actor,
                        targetId = targetId,
                        memberRow = memberRow,
                        targetRole = facts.targetRole,
                        facts =
                            PeerActionAuditFacts(
                                event = PeerAuditEvent.REQUESTED,
                                action = peerAction,
                                requestId = id.toString(),
                                targetRole = facts.targetRole,
                            ),
                        now = now,
                    )
                    Created(id = id, dto = dto, targetEmail = memberRow[MemberTable.email], approverEmails = approverEmails)
                }
            }

        // AFTER the commit. The target of a temporary-password request is warned FIRST and the mail must really be accepted: it is the
        // only safeguard the target has (objection link). If it was not, the request is withdrawn and the link never leaves the server.
        if (vetoRaw != null) {
            val status =
                runCatching {
                    mailer.sendRequestForTarget(
                        email = created.targetEmail,
                        rawVetoToken = vetoRaw,
                        actorName = created.dto.actorDisplayName,
                        notBefore = PrivilegedActionStore.plus(at = now, duration = PrivilegedActionStore.OBJECTION_DELAY),
                    )
                }.onFailure { e -> logger.error { "peer request mail to the target threw: ${e::class.simpleName}" } }
                    .getOrNull()
            if (status != DeliveryStatus.SENT) {
                withdrawUnwarned(actor = actor, requestId = created.id, targetId = targetId)
                throw PeerProtectionDeniedException()
            }
        }
        created.approverEmails.forEach { email ->
            runCatching {
                mailer.sendApprovalNeeded(
                    email = email,
                    actorName = created.dto.actorDisplayName,
                    targetName = created.dto.targetDisplayName,
                    action = kind,
                    expiresAt = created.dto.expiresAt,
                )
            }.onFailure { e -> logger.error { "peer approval mail threw: ${e::class.simpleName}" } }
        }
        return created.dto
    }

    /** Ends a freshly created request whose warning to the target could not be handed off. */
    private fun withdrawUnwarned(
        actor: CurrentMember,
        requestId: Uuid,
        targetId: Uuid,
    ) {
        val now = nowUtc()
        runCatching {
            transaction {
                val memberRow = PeerGuard.lockMember(targetId) ?: return@transaction
                val facts = PeerGuard.lockFactsAfterMemberLock(targetId = targetId, memberRow = memberRow, requesterId = actor.memberId)
                val row = PrivilegedActionStore.byIdLocked(requestId) ?: return@transaction
                if (!row.isOpen()) return@transaction
                PrivilegedActionStore.resolveLocked(id = requestId, status = PrivilegedActionStatus.WITHDRAWN, now = now)
                audit(
                    actor = actor,
                    targetId = targetId,
                    memberRow = memberRow,
                    targetRole = facts.targetRole,
                    facts =
                        PeerActionAuditFacts(
                            event = PeerAuditEvent.WITHDRAWN,
                            action = PrivilegedActionKind.TEMP_PASSWORD.toPeerAction(),
                            requestId = requestId.toString(),
                            targetRole = facts.targetRole,
                        ),
                    now = now,
                )
            }
        }.onFailure { e -> logger.error { "withdrawing an unwarned privileged-action request failed: ${e::class.simpleName}" } }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Deciding
    // ------------------------------------------------------------------------------------------------------------

    /** What a locked decision transaction returns to its caller for the AFTER-commit work. */
    private class Decided(
        val dto: PrivilegedActionRequestDto,
        val targetId: Uuid,
        val revokeSessions: Boolean = false,
        val notifyEvent: PeerExecutedEvent? = null,
        val requesterId: Uuid? = null,
    )

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun approve(
        actor: CurrentMember,
        requestIdRaw: String,
    ): PrivilegedActionRequestDto {
        actor.requireRole(AccountRole.ADMIN)
        val requestId = parseRequestId(requestIdRaw)
        val targetId =
            transaction { PrivilegedActionStore.byId(requestId) }?.get(PrivilegedActionRequestTable.targetMemberId)
                ?: throw PrivilegedActionStateException()
        val now = nowUtc()
        val decided =
            transaction {
                val memberRow = PeerGuard.lockMember(targetId) ?: throw PrivilegedActionStateException()
                val facts =
                    PeerGuard.lockFactsAfterMemberLock(
                        targetId = targetId,
                        memberRow = memberRow,
                        requesterId = null,
                        approverCutoffBase = now,
                    )
                val row = PrivilegedActionStore.byIdLocked(requestId) ?: throw PrivilegedActionStateException()
                if (row[PrivilegedActionRequestTable.status] != PrivilegedActionStatus.PENDING.name ||
                    row[PrivilegedActionRequestTable.expiresAt] <= now
                ) {
                    throw PrivilegedActionStateException()
                }
                val kind = PrivilegedActionKind.valueOf(row[PrivilegedActionRequestTable.action])
                val peerAction = kind.toPeerAction()
                val requesterId = row[PrivilegedActionRequestTable.actorMemberId]
                val createdAt = row[PrivilegedActionRequestTable.createdAt]
                // Who may approve: re-read under lock, never trusted from the client.
                if (!PeerGuard.isEligibleApprover(
                        lockedAdminRows = facts.lockedAccountRows,
                        approverId = actor.memberId,
                        excluding = setOf(requesterId, targetId),
                        requestCreatedAt = createdAt,
                    )
                ) {
                    throw ForbiddenException()
                }
                val requester = requesterAsCurrentMember(requesterId = requesterId, facts = facts)
                val invalid = invalidReason(row = row, memberRow = memberRow, facts = facts, requester = requester)
                if (invalid) {
                    PrivilegedActionStore.resolveLocked(id = requestId, status = PrivilegedActionStatus.INVALIDATED, now = now)
                    audit(
                        actor = null,
                        targetId = targetId,
                        memberRow = memberRow,
                        targetRole = facts.targetRole,
                        facts =
                            PeerActionAuditFacts(
                                event = PeerAuditEvent.INVALIDATED,
                                action = peerAction,
                                requestId = requestId.toString(),
                                targetRole = facts.targetRole,
                            ),
                        now = now,
                    )
                    return@transaction Decided(dto = loadDto(requestId), targetId = targetId)
                }
                requester!!
                val peerFacts =
                    PeerActionAuditFacts(
                        event = PeerAuditEvent.EXECUTED,
                        action = peerAction,
                        requestId = requestId.toString(),
                        targetRole = facts.targetRole,
                        approverId = actor.memberId.toString(),
                    )
                when (kind) {
                    PrivilegedActionKind.TEMP_PASSWORD -> {
                        PrivilegedActionStore.markApprovedWaitingLocked(id = requestId, approverId = actor.memberId, now = now)
                        audit(
                            actor = actor,
                            targetId = targetId,
                            memberRow = memberRow,
                            targetRole = facts.targetRole,
                            facts = peerFacts.copy(event = PeerAuditEvent.APPROVED),
                            now = now,
                        )
                        Decided(dto = loadDto(requestId), targetId = targetId)
                    }
                    PrivilegedActionKind.DEMOTE -> {
                        val newRole = AccountRole.valueOf(row[PrivilegedActionRequestTable.requestedRole] ?: error("DEMOTE without a role"))
                        PrivilegedActionStore.resolveLocked(
                            id = requestId,
                            status = PrivilegedActionStatus.EXECUTED,
                            now = now,
                            approverId = actor.memberId,
                            decided = true,
                        )
                        // The audit entry is written by the mutation (last lock-taking call): actor = the REQUESTER, approver in the facts.
                        MemberRoleStatusMutations.applyRoleChangeLocked(
                            actor = requester,
                            targetId = targetId,
                            newRole = newRole,
                            currentRole = AccountRole.ADMIN,
                            memberRow = memberRow,
                            lockedAccountRows = facts.lockedAccountRows,
                            now = now,
                            peerFacts = peerFacts,
                        )
                        Decided(
                            dto = loadDto(requestId),
                            targetId = targetId,
                            notifyEvent = PeerExecutedEvent.ROLE_CHANGED,
                            requesterId = requesterId,
                        )
                    }
                    PrivilegedActionKind.SUSPEND -> {
                        val newStatus =
                            MemberStatus.valueOf(
                                row[PrivilegedActionRequestTable.requestedStatus] ?: error("SUSPEND without a status"),
                            )
                        PrivilegedActionStore.resolveLocked(
                            id = requestId,
                            status = PrivilegedActionStatus.EXECUTED,
                            now = now,
                            approverId = actor.memberId,
                            decided = true,
                        )
                        val outcome =
                            MemberRoleStatusMutations.applyStatusChangeLocked(
                                actor = requester,
                                targetId = targetId,
                                newStatus = newStatus,
                                trimmedReason = null,
                                dateOfDeath = null,
                                row = memberRow,
                                existingRole = AccountRole.ADMIN,
                                lockedAccountRows = facts.lockedAccountRows,
                                now = now,
                                regionalChapterEnforced = false,
                                peerFacts = peerFacts,
                            )
                        Decided(
                            dto = loadDto(requestId),
                            targetId = targetId,
                            revokeSessions = outcome.revokeSessions,
                            notifyEvent = PeerExecutedEvent.ACCESS_SUSPENDED,
                            requesterId = requesterId,
                        )
                    }
                }
            }
        // AFTER the commit.
        if (decided.revokeSessions) SessionStore.revokeAllForMember(memberId = decided.targetId)
        val event = decided.notifyEvent
        val requesterId = decided.requesterId
        if (event != null && requesterId != null) {
            notifier.targetExecuted(targetId = decided.targetId, actorId = requesterId, event = event, occurredAt = now)
        }
        return decided.dto
    }

    fun reject(
        actor: CurrentMember,
        requestIdRaw: String,
    ): PrivilegedActionRequestDto {
        actor.requireRole(AccountRole.ADMIN)
        val requestId = parseRequestId(requestIdRaw)
        val targetId =
            transaction { PrivilegedActionStore.byId(requestId) }?.get(PrivilegedActionRequestTable.targetMemberId)
                ?: throw PrivilegedActionStateException()
        val now = nowUtc()
        return transaction {
            val memberRow = PeerGuard.lockMember(targetId) ?: throw PrivilegedActionStateException()
            val facts =
                PeerGuard.lockFactsAfterMemberLock(
                    targetId = targetId,
                    memberRow = memberRow,
                    requesterId = null,
                    approverCutoffBase = now,
                )
            val row = PrivilegedActionStore.byIdLocked(requestId) ?: throw PrivilegedActionStateException()
            if (row[PrivilegedActionRequestTable.status] != PrivilegedActionStatus.PENDING.name ||
                row[PrivilegedActionRequestTable.expiresAt] <= now
            ) {
                throw PrivilegedActionStateException()
            }
            if (!PeerGuard.isEligibleApprover(
                    lockedAdminRows = facts.lockedAccountRows,
                    approverId = actor.memberId,
                    excluding = setOf(row[PrivilegedActionRequestTable.actorMemberId], targetId),
                    requestCreatedAt = row[PrivilegedActionRequestTable.createdAt],
                )
            ) {
                throw ForbiddenException()
            }
            PrivilegedActionStore.resolveLocked(
                id = requestId,
                status = PrivilegedActionStatus.REJECTED,
                now = now,
                approverId = actor.memberId,
                decided = true,
            )
            val kind = PrivilegedActionKind.valueOf(row[PrivilegedActionRequestTable.action])
            audit(
                actor = actor,
                targetId = targetId,
                memberRow = memberRow,
                targetRole = facts.targetRole,
                facts =
                    PeerActionAuditFacts(
                        event = PeerAuditEvent.REJECTED,
                        action = kind.toPeerAction(),
                        requestId = requestId.toString(),
                        targetRole = facts.targetRole,
                        approverId = actor.memberId.toString(),
                    ),
                now = now,
            )
            loadDto(requestId)
        }
    }

    fun withdraw(
        actor: CurrentMember,
        requestIdRaw: String,
    ): PrivilegedActionRequestDto {
        actor.requireRole(AccountRole.ADMIN)
        val requestId = parseRequestId(requestIdRaw)
        val targetId =
            transaction { PrivilegedActionStore.byId(requestId) }?.get(PrivilegedActionRequestTable.targetMemberId)
                ?: throw PrivilegedActionStateException()
        val now = nowUtc()
        return transaction {
            val memberRow = PeerGuard.lockMember(targetId) ?: throw PrivilegedActionStateException()
            val facts =
                PeerGuard.lockFactsAfterMemberLock(
                    targetId = targetId,
                    memberRow = memberRow,
                    requesterId = null,
                    approverCutoffBase = now,
                )
            val row = PrivilegedActionStore.byIdLocked(requestId) ?: throw PrivilegedActionStateException()
            // "missing", "foreign" and "finished" are one answer.
            if (row[PrivilegedActionRequestTable.actorMemberId] != actor.memberId || !row.isOpen()) throw PrivilegedActionStateException()
            PrivilegedActionStore.resolveLocked(id = requestId, status = PrivilegedActionStatus.WITHDRAWN, now = now)
            val kind = PrivilegedActionKind.valueOf(row[PrivilegedActionRequestTable.action])
            audit(
                actor = actor,
                targetId = targetId,
                memberRow = memberRow,
                targetRole = facts.targetRole,
                facts =
                    PeerActionAuditFacts(
                        event = PeerAuditEvent.WITHDRAWN,
                        action = kind.toPeerAction(),
                        requestId = requestId.toString(),
                        targetRole = facts.targetRole,
                    ),
                now = now,
            )
            loadDto(requestId)
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Executing a temporary password
    // ------------------------------------------------------------------------------------------------------------

    private class Executed(
        val password: String,
        val targetId: Uuid,
        val requesterId: Uuid,
    )

    @Suppress("LongMethod")
    fun executeTemporaryPassword(
        actor: CurrentMember,
        requestIdRaw: String,
    ): PrivilegedPasswordResultDto {
        actor.requireRole(AccountRole.ADMIN)
        val requestId = parseRequestId(requestIdRaw)
        val targetId =
            transaction { PrivilegedActionStore.byId(requestId) }?.get(PrivilegedActionRequestTable.targetMemberId)
                ?: throw PrivilegedActionStateException()
        val now = nowUtc()
        val executed =
            transaction {
                val memberRow = PeerGuard.lockMember(targetId) ?: throw PrivilegedActionStateException()
                val facts =
                    PeerGuard.lockFactsAfterMemberLock(
                        targetId = targetId,
                        memberRow = memberRow,
                        requesterId = null,
                        approverCutoffBase = now,
                    )
                val row = PrivilegedActionStore.byIdLocked(requestId) ?: throw PrivilegedActionStateException()
                // Only the requester, only an approved temporary-password request, only inside the window: everything else is one answer.
                if (row[PrivilegedActionRequestTable.actorMemberId] != actor.memberId ||
                    row[PrivilegedActionRequestTable.action] != PrivilegedActionKind.TEMP_PASSWORD.name ||
                    row[PrivilegedActionRequestTable.status] != PrivilegedActionStatus.APPROVED_WAITING.name
                ) {
                    throw PrivilegedActionStateException()
                }
                val notBefore = row[PrivilegedActionRequestTable.notBefore] ?: throw PrivilegedActionStateException()
                val executeUntil = row[PrivilegedActionRequestTable.executeUntil] ?: throw PrivilegedActionStateException()
                if (now < notBefore || now >= executeUntil) throw PrivilegedActionStateException()
                val requester = requesterAsCurrentMember(requesterId = actor.memberId, facts = facts)
                if (invalidReason(row = row, memberRow = memberRow, facts = facts, requester = requester) || requester == null) {
                    PrivilegedActionStore.resolveLocked(id = requestId, status = PrivilegedActionStatus.INVALIDATED, now = now)
                    audit(
                        actor = null,
                        targetId = targetId,
                        memberRow = memberRow,
                        targetRole = facts.targetRole,
                        facts =
                            PeerActionAuditFacts(
                                event = PeerAuditEvent.INVALIDATED,
                                action = PeerAction.TEMP_PASSWORD,
                                requestId = requestId.toString(),
                                targetRole = facts.targetRole,
                            ),
                        now = now,
                    )
                    return@transaction null
                }
                PrivilegedActionStore.resolveLocked(id = requestId, status = PrivilegedActionStatus.EXECUTED, now = now)
                // Server-generated ONLY: a caller-chosen password cannot be requested for an ADMIN target. Never stored, never logged.
                val password = TemporaryPasswordGenerator.generate()
                TemporaryPasswordMutation.applyLocked(
                    actor = requester,
                    targetId = targetId,
                    memberRow = memberRow,
                    accountRole = AccountRole.ADMIN,
                    effectivePassword = password,
                    auditReason = null,
                    now = now,
                    peerFacts =
                        PeerActionAuditFacts(
                            event = PeerAuditEvent.EXECUTED,
                            action = PeerAction.TEMP_PASSWORD,
                            requestId = requestId.toString(),
                            targetRole = facts.targetRole,
                            approverId = row[PrivilegedActionRequestTable.approverMemberId]?.toString(),
                        ),
                )
                Executed(password = password, targetId = targetId, requesterId = actor.memberId)
            } ?: throw PrivilegedActionStateException()

        // AFTER the commit -- the same invalidations as the direct path: every session and every outstanding reset token of the target.
        val revoked = SessionStore.revokeAllForMember(memberId = executed.targetId)
        PasswordResetTokenStore.invalidateAllForMember(memberId = executed.targetId)
        notifier.targetExecuted(
            targetId = executed.targetId,
            actorId = executed.requesterId,
            event = PeerExecutedEvent.TEMPORARY_PASSWORD_SET,
            occurredAt = now,
        )
        return PrivilegedPasswordResultDto(
            generatedPassword = executed.password,
            revokedSessionCount = revoked,
            memberNotified = if (mailConfigured) MailDeliveryState.HANDED_TO_SMTP else MailDeliveryState.NOT_CONFIGURED,
        )
    }

    // ------------------------------------------------------------------------------------------------------------
    // The target's objection (unauthenticated link) and the poller
    // ------------------------------------------------------------------------------------------------------------

    /**
     * The target's objection through the one-time link. One answer for every unusable token (wrong, used, expired, foreign): the
     * caller never learns which. A valid token on an open request ends it as VETOED; the token is consumed.
     */
    fun vetoByLink(rawToken: String): VetoResult {
        val hash = EmailChangeTokens.hash(rawToken)
        val now = nowUtc()
        val found = transaction { PrivilegedActionStore.findOpenByVetoHash(hash) } ?: return VetoResult.INVALID
        val requestId = found[PrivilegedActionRequestTable.id]
        val targetId = found[PrivilegedActionRequestTable.targetMemberId]
        return transaction {
            val memberRow = PeerGuard.lockMember(targetId) ?: return@transaction VetoResult.INVALID
            val facts =
                PeerGuard.lockFactsAfterMemberLock(
                    targetId = targetId,
                    memberRow = memberRow,
                    requesterId = null,
                    approverCutoffBase = now,
                )
            val row = PrivilegedActionStore.byIdLocked(requestId) ?: return@transaction VetoResult.INVALID
            if (!row.isOpen() ||
                !EmailChangeTokens.matches(storedHash = row[PrivilegedActionRequestTable.vetoTokenHash], raw = rawToken)
            ) {
                return@transaction VetoResult.INVALID
            }
            PrivilegedActionStore.resolveLocked(id = requestId, status = PrivilegedActionStatus.VETOED, now = now)
            audit(
                actor = null,
                targetId = targetId,
                memberRow = memberRow,
                targetRole = facts.targetRole,
                facts =
                    PeerActionAuditFacts(
                        event = PeerAuditEvent.VETOED,
                        action = PeerAction.TEMP_PASSWORD,
                        requestId = requestId.toString(),
                        targetRole = facts.targetRole,
                    ),
                now = now,
            )
            VetoResult.VETOED
        }
    }

    /** Poller entry: expires overdue requests (each in its own transaction under the locks) and purges old resolved rows. */
    fun runDue(now: LocalDateTime): Int {
        var touched = 0
        transaction { PrivilegedActionStore.dueIds(now) }.forEach { requestId ->
            val done =
                runCatching {
                    transaction {
                        val targetId =
                            PrivilegedActionStore.byId(requestId)?.get(PrivilegedActionRequestTable.targetMemberId)
                                ?: return@transaction false
                        val memberRow = PeerGuard.lockMember(targetId) ?: return@transaction false
                        val facts =
                            PeerGuard.lockFactsAfterMemberLock(
                                targetId = targetId,
                                memberRow = memberRow,
                                requesterId = null,
                                approverCutoffBase = now,
                            )
                        val row = PrivilegedActionStore.byIdLocked(requestId) ?: return@transaction false
                        if (!row.isOpen() || !isLapsed(row = row, now = now)) return@transaction false
                        PrivilegedActionStore.resolveLocked(id = requestId, status = PrivilegedActionStatus.EXPIRED, now = now)
                        val kind = PrivilegedActionKind.valueOf(row[PrivilegedActionRequestTable.action])
                        audit(
                            actor = null,
                            targetId = targetId,
                            memberRow = memberRow,
                            targetRole = facts.targetRole,
                            facts =
                                PeerActionAuditFacts(
                                    event = PeerAuditEvent.EXPIRED,
                                    action = kind.toPeerAction(),
                                    requestId = requestId.toString(),
                                    targetRole = facts.targetRole,
                                ),
                            now = now,
                        )
                        true
                    }
                }.onFailure { e -> logger.error { "privileged-action expiry failed (requestId=$requestId): ${e::class.simpleName}" } }
                    .getOrDefault(false)
            if (done) touched++
        }
        val purged = runCatching { transaction { PrivilegedActionStore.purgeResolved(now) } }.getOrDefault(0)
        return touched + purged
    }

    // ------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------

    /** The requester, as the [CurrentMember] the executed change is attributed to -- or null when they are no longer an ADMIN. */
    private fun requesterAsCurrentMember(
        requesterId: Uuid,
        facts: LockedPeerFacts,
    ): CurrentMember? {
        val account = facts.lockedAccountRows.singleOrNull { it[AccountTable.memberId] == requesterId } ?: return null
        if (account[AccountTable.role] != AccountRole.ADMIN) return null
        val status =
            MemberTable
                .selectAll()
                .where { MemberTable.id eq requesterId }
                .singleOrNull()
                ?.get(MemberTable.status) ?: return null
        if (status in MemberStatusSets.LOGIN_BLOCKED) return null
        return CurrentMember(memberId = requesterId, role = AccountRole.ADMIN, status = status)
    }

    /**
     * `true` when the facts the request was based on no longer hold: the target is no longer an ADMIN (or was anonymized /
     * died), the requester is no longer an ADMIN, or the requested transition is no longer possible.
     */
    private fun invalidReason(
        row: ResultRow,
        memberRow: ResultRow,
        facts: LockedPeerFacts,
        requester: CurrentMember?,
    ): Boolean {
        if (requester == null) return true
        if (facts.targetRole != AccountRole.valueOf(row[PrivilegedActionRequestTable.targetRoleAtRequest])) return true
        if (memberRow[MemberTable.anonymizedAt] != null || memberRow[MemberTable.status] == MemberStatus.DECEASED) return true
        val kind = PrivilegedActionKind.valueOf(row[PrivilegedActionRequestTable.action])
        if (kind == PrivilegedActionKind.SUSPEND) {
            val newStatus = MemberStatus.valueOf(row[PrivilegedActionRequestTable.requestedStatus] ?: return true)
            val from = memberRow[MemberTable.status]
            if (newStatus == from || newStatus !in MemberStatusTransitions.allowedTargets(from)) return true
        }
        if (kind == PrivilegedActionKind.DEMOTE) {
            val newRole = AccountRole.valueOf(row[PrivilegedActionRequestTable.requestedRole] ?: return true)
            if (newRole == AccountRole.ADMIN) return true
        }
        return false
    }

    private fun ResultRow.isOpen(): Boolean = PrivilegedActionStatus.valueOf(this[PrivilegedActionRequestTable.status]).isOpen

    private fun isLapsed(
        row: ResultRow,
        now: LocalDateTime,
    ): Boolean =
        when (PrivilegedActionStatus.valueOf(row[PrivilegedActionRequestTable.status])) {
            PrivilegedActionStatus.PENDING -> row[PrivilegedActionRequestTable.expiresAt] <= now
            PrivilegedActionStatus.APPROVED_WAITING ->
                (
                    row[PrivilegedActionRequestTable.executeUntil]
                        ?: row[PrivilegedActionRequestTable.expiresAt]
                ) <=
                    now
            else -> false
        }

    private fun loadDto(requestId: Uuid): PrivilegedActionRequestDto {
        val row = PrivilegedActionStore.byId(requestId) ?: error("request vanished")
        return row.toDto(
            displayNames(listOf(row[PrivilegedActionRequestTable.actorMemberId], row[PrivilegedActionRequestTable.targetMemberId])),
        )
    }

    private fun displayNames(ids: List<Uuid>): Map<Uuid, String> {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return MemberTable
            .selectAll()
            .where { MemberTable.id inList distinct }
            .associate { it[MemberTable.id] to it[MemberTable.displayName] }
    }

    private fun ResultRow.toDto(names: Map<Uuid, String>): PrivilegedActionRequestDto =
        PrivilegedActionRequestDto(
            id = this[PrivilegedActionRequestTable.id].toString(),
            action = PrivilegedActionKind.valueOf(this[PrivilegedActionRequestTable.action]),
            actorMemberId = this[PrivilegedActionRequestTable.actorMemberId].toString(),
            actorDisplayName = names[this[PrivilegedActionRequestTable.actorMemberId]] ?: "?",
            targetMemberId = this[PrivilegedActionRequestTable.targetMemberId].toString(),
            targetDisplayName = names[this[PrivilegedActionRequestTable.targetMemberId]] ?: "?",
            requestedRole = this[PrivilegedActionRequestTable.requestedRole]?.let { AccountRole.valueOf(it) },
            requestedStatus = this[PrivilegedActionRequestTable.requestedStatus]?.let { MemberStatus.valueOf(it) },
            reason = this[PrivilegedActionRequestTable.reason],
            status = PrivilegedActionStatus.valueOf(this[PrivilegedActionRequestTable.status]),
            createdAt = this[PrivilegedActionRequestTable.createdAt],
            expiresAt = this[PrivilegedActionRequestTable.expiresAt],
            notBefore = this[PrivilegedActionRequestTable.notBefore],
            executeUntil = this[PrivilegedActionRequestTable.executeUntil],
        )

    /** One MEMBER/UPDATE audit entry. Carries NEVER the reason, a token or an address -- see [PeerActionAuditFacts]. Call it LAST in the transaction. */
    private fun audit(
        actor: CurrentMember?,
        targetId: Uuid,
        memberRow: ResultRow,
        targetRole: AccountRole?,
        facts: PeerActionAuditFacts,
        now: LocalDateTime,
    ) {
        val before =
            MemberChangeSnapshot(
                displayNameChanged = false,
                emailChanged = false,
                status = memberRow[MemberTable.status],
                role = targetRole,
            )
        val after = before.copy(peerAction = facts)
        AuditLogRecorder.record(
            actorMemberId = actor?.memberId,
            actorRole = actor?.role,
            entityType = AuditEntityType.MEMBER,
            entityId = targetId,
            action = AuditAction.UPDATE,
            before = Json.encodeToString(MemberChangeSnapshot.serializer(), before),
            after = Json.encodeToString(MemberChangeSnapshot.serializer(), after),
            occurredAt = now,
        )
    }

    private fun PrivilegedActionKind.toPeerAction(): PeerAction =
        when (this) {
            PrivilegedActionKind.TEMP_PASSWORD -> PeerAction.TEMP_PASSWORD
            PrivilegedActionKind.DEMOTE -> PeerAction.DEMOTE
            PrivilegedActionKind.SUSPEND -> PeerAction.SUSPEND
        }

    private fun PeerDecision.toDto(
        action: PeerAction,
        eligible: Int,
    ): PeerActionDecisionDto =
        when (this) {
            is PeerDecision.Allow ->
                PeerActionDecisionDto(
                    action = action,
                    kind = PeerDecisionKind.ALLOW,
                    notifiesTarget = notifyTarget,
                    eligibleApprovers = eligible,
                )
            PeerDecision.Mask -> PeerActionDecisionDto(action = action, kind = PeerDecisionKind.MASK, eligibleApprovers = eligible)
            is PeerDecision.RequiresApproval ->
                PeerActionDecisionDto(action = action, kind = PeerDecisionKind.REQUIRES_APPROVAL, eligibleApprovers = eligibleApprovers)
            is PeerDecision.Deny ->
                PeerActionDecisionDto(
                    action = action,
                    kind = PeerDecisionKind.DENY,
                    denyReason = reason,
                    eligibleApprovers = eligible,
                )
        }

    private fun parseMemberId(raw: String): Uuid = runCatching { Uuid.parse(raw) }.getOrElse { throw NotFoundException("Member not found") }

    private fun parseRequestId(raw: String): Uuid = runCatching { Uuid.parse(raw) }.getOrElse { throw PrivilegedActionStateException() }

    private fun nowUtc(): LocalDateTime = DbClock.nowLocalDateTime(TimeZone.UTC)

    private companion object {
        const val REASON_MIN_LENGTH = 10
        const val REASON_MAX_LENGTH = 500
        const val OVERVIEW_LIMIT = 50
        const val RECENT_DAYS = 7
    }
}
