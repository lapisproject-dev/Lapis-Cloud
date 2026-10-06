package network.lapis.cloud.server.security

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.forMemberUpdate
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.PeerAction
import network.lapis.cloud.shared.domain.PeerActionAuditFacts
import network.lapis.cloud.shared.domain.PeerAuditEvent
import network.lapis.cloud.shared.domain.PeerDenyReason
import network.lapis.cloud.shared.rpc.NoSecondAdminException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PeerApprovalRequiredException
import network.lapis.cloud.shared.rpc.PeerProtectionDeniedException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- the outcome of the peer-protection matrix for one action of one caller against one
 * target.
 *
 * - [Allow]: the action may run directly; [Allow.notifyTarget] says the target is informed by mail, [Allow.notifyOtherAdmins]
 *   that every OTHER administrator is (a new administrator appeared).
 * - [Mask]: a read returns a marked, value-free answer instead of an error.
 * - [RequiresApproval]: the action is never executed directly -- a second administrator must approve a request;
 *   [RequiresApproval.eligibleApprovers] (always greater than 0) is how many could.
 * - [Deny]: refused.
 */
sealed interface PeerDecision {
    data class Allow(
        val notifyTarget: Boolean = false,
        val notifyOtherAdmins: Boolean = false,
    ) : PeerDecision

    data object Mask : PeerDecision

    data class RequiresApproval(
        val eligibleApprovers: Int,
    ) : PeerDecision

    data class Deny(
        val reason: PeerDenyReason,
    ) : PeerDecision
}

/**
 * Welle V1.9.57 -- THE peer-protection matrix as a pure function (no database, fully parameterizable in tests). Whoever
 * adds an action against another account asks this function; the source-scan tripwire `PrivilegedPeerActionTripwireTest`
 * guarantees that every protected write/read goes through [PeerGuard]. The full table lives in
 * `docs/architecture/admin-peer-protection.adoc`.
 *
 * [targetRole] is `null` for a member without a login account (treated like a plain MEMBER). [eligibleApprovers] is the
 * number of administrators who could approve a request right now (see [PeerGuard.eligibleApproverCount]).
 */
object PeerPolicy {
    fun decide(
        actorRole: AccountRole,
        actorId: Uuid,
        targetRole: AccountRole?,
        targetId: Uuid,
        action: PeerAction,
        eligibleApprovers: Int,
        mailConfigured: Boolean,
    ): PeerDecision {
        val self = actorId == targetId
        val targetIsAdmin = targetRole == AccountRole.ADMIN
        val targetEscalated = targetRole != null && targetRole in ESCALATED_ROLES
        val actorIsAdmin = actorRole == AccountRole.ADMIN
        val actorIsBoard = actorRole == AccountRole.BOARD
        val notPermitted = PeerDecision.Deny(PeerDenyReason.NOT_PERMITTED)

        return when (action) {
            PeerAction.READ_PROTECTED_DATA ->
                when {
                    self -> PeerDecision.Allow()
                    actorIsAdmin -> PeerDecision.Allow()
                    actorIsBoard -> if (targetIsAdmin) PeerDecision.Mask else PeerDecision.Allow()
                    else -> notPermitted
                }
            PeerAction.WRITE_PROTECTED_DATA ->
                when {
                    self -> PeerDecision.Allow()
                    actorIsAdmin -> PeerDecision.Allow(notifyTarget = targetIsAdmin)
                    actorIsBoard -> if (targetIsAdmin) PeerDecision.Deny(PeerDenyReason.PROTECTED_TARGET) else PeerDecision.Allow()
                    else -> notPermitted
                }
            PeerAction.ERASE ->
                when {
                    targetIsAdmin -> PeerDecision.Deny(PeerDenyReason.TARGET_IS_ADMIN)
                    self || actorIsAdmin -> PeerDecision.Allow()
                    else -> notPermitted
                }
            PeerAction.LINK_IDENTITY ->
                when {
                    !actorIsAdmin -> notPermitted
                    self -> PeerDecision.Allow()
                    targetIsAdmin -> PeerDecision.Deny(PeerDenyReason.TARGET_IS_ADMIN)
                    else -> PeerDecision.Allow()
                }
            PeerAction.EMAIL_OVERRIDE ->
                when {
                    !actorIsAdmin -> notPermitted
                    self -> PeerDecision.Deny(PeerDenyReason.SELF_TARGET)
                    targetIsAdmin -> PeerDecision.Deny(PeerDenyReason.TARGET_IS_ADMIN)
                    else -> PeerDecision.Allow()
                }
            PeerAction.TEMP_PASSWORD ->
                when {
                    !actorIsAdmin -> notPermitted
                    self -> PeerDecision.Deny(PeerDenyReason.SELF_TARGET)
                    !targetIsAdmin -> PeerDecision.Allow(notifyTarget = true)
                    !mailConfigured -> PeerDecision.Deny(PeerDenyReason.MAIL_UNAVAILABLE)
                    else -> approvalOrNoSecondAdmin(eligibleApprovers)
                }
            PeerAction.RESET_MAIL ->
                when {
                    !actorIsAdmin -> notPermitted
                    self -> PeerDecision.Deny(PeerDenyReason.SELF_TARGET)
                    else -> PeerDecision.Allow(notifyTarget = targetIsAdmin)
                }
            PeerAction.DEMOTE ->
                when {
                    !actorIsAdmin -> notPermitted
                    self -> PeerDecision.Deny(PeerDenyReason.SELF_TARGET)
                    targetIsAdmin -> approvalOrNoSecondAdmin(eligibleApprovers)
                    else -> PeerDecision.Allow()
                }
            PeerAction.PROMOTE_TO_ADMIN ->
                when {
                    !actorIsAdmin -> notPermitted
                    self -> PeerDecision.Deny(PeerDenyReason.SELF_TARGET)
                    else -> PeerDecision.Allow(notifyOtherAdmins = true)
                }
            PeerAction.SUSPEND ->
                when {
                    self -> PeerDecision.Deny(PeerDenyReason.SELF_TARGET)
                    actorIsAdmin -> if (targetIsAdmin) approvalOrNoSecondAdmin(eligibleApprovers) else PeerDecision.Allow()
                    actorIsBoard -> if (targetEscalated) notPermitted else PeerDecision.Allow()
                    else -> notPermitted
                }
            PeerAction.NON_BLOCKING_STATUS ->
                when {
                    self -> PeerDecision.Deny(PeerDenyReason.SELF_TARGET)
                    actorIsAdmin -> PeerDecision.Allow(notifyTarget = targetIsAdmin)
                    actorIsBoard -> if (targetEscalated) notPermitted else PeerDecision.Allow()
                    else -> notPermitted
                }
        }
    }

    private fun approvalOrNoSecondAdmin(eligibleApprovers: Int): PeerDecision =
        if (eligibleApprovers > 0) {
            PeerDecision.RequiresApproval(eligibleApprovers = eligibleApprovers)
        } else {
            PeerDecision.Deny(PeerDenyReason.NO_SECOND_ADMIN)
        }
}

/** Facts about the target, read UNDER the peer-protection lock (see [PeerGuard.lockFactsAfterMemberLock]). */
internal data class LockedPeerFacts(
    val targetRole: AccountRole?,
    val targetStatus: MemberStatus,
    /** Every ADMIN account row plus the target's own account row, locked in id order. */
    val lockedAccountRows: List<ResultRow>,
    val eligibleApprovers: Int,
)

/**
 * Server-internal signal that [PeerGuard.require] refused an action. It is converted into the typed RPC exception by
 * [peerGuarded] -- AFTER the surrounding transaction rolled back and AFTER the refusal was audited in its own short
 * transaction. Never leaves the server.
 */
internal class PeerDeniedSignal(
    val decision: PeerDecision,
    val action: PeerAction,
    val targetId: Uuid,
    val targetRole: AccountRole?,
) : RuntimeException("peer protection refused $action", null, false, false)

/**
 * Welle V1.9.57 -- database side of the peer protection: the lock order, the approver eligibility and the refusal audit.
 *
 * **Lock order** (identical to `MemberService.updateMemberRole` / `updateMemberStatus`, so there is no lock-order
 * inversion with them): (1) the target's `member` row `FOR UPDATE`, (2) ONE id-ordered query locking the union
 * {target account} U {every ADMIN account}, (3) anything else, with `AuditLogRecorder.record` always last. Every writer of
 * an account's role or of a member's login-blocking status takes that union lock, so the facts read here cannot change
 * until the caller's transaction ends.
 */
internal object PeerGuard {
    /** How long an ADMIN must hold the role before they may approve a request (strawman protection). */
    val ADMIN_TENURE: Duration = 7.days

    /** The `member` row of [targetId] locked `FOR UPDATE`, or null. Call this FIRST. */
    fun lockMember(targetId: Uuid): ResultRow? =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq targetId }
            .forMemberUpdate()
            .singleOrNull()

    /**
     * Step 2 of the lock order: the member row [memberRow] is already locked by the caller. Locks {target account} U {all
     * ADMIN accounts} in ONE id-ordered query and derives the facts. [requesterId] (the actor) and [targetId] are never
     * eligible approvers; [approverCutoffBase] is the request's creation time (the tenure is measured against it).
     */
    fun lockFactsAfterMemberLock(
        targetId: Uuid,
        memberRow: ResultRow,
        requesterId: Uuid?,
        approverCutoffBase: LocalDateTime = DbClock.nowLocalDateTime(TimeZone.UTC),
    ): LockedPeerFacts {
        val locked =
            AccountTable
                .selectAll()
                .where { (AccountTable.memberId eq targetId) or (AccountTable.role eq AccountRole.ADMIN) }
                .orderBy(AccountTable.id)
                .forUpdate()
                .toList()
        val targetRole = locked.singleOrNull { it[AccountTable.memberId] == targetId }?.get(AccountTable.role)
        val excluded = setOfNotNull(requesterId, targetId)
        return LockedPeerFacts(
            targetRole = targetRole,
            targetStatus = memberRow[MemberTable.status],
            lockedAccountRows = locked,
            // Only a request against an ADMIN needs approvers; for any other target the matrix never reads the number -- no query.
            eligibleApprovers =
                if (targetRole == AccountRole.ADMIN) {
                    eligibleApproverCount(
                        lockedAdminRows = locked,
                        excluding = excluded,
                        requestCreatedAt = approverCutoffBase,
                    )
                } else {
                    0
                },
        )
    }

    /**
     * Ids of the administrators who could approve a request created at [requestCreatedAt]: ADMIN right now (the rows are
     * locked), not [excluding]d (actor, target), member status not login-blocked, not anonymized, and holding the role
     * for at least [ADMIN_TENURE] at the time of the request (`role_changed_at` null = pre-existing account = tenured).
     */
    fun eligibleApproverIds(
        lockedAdminRows: List<ResultRow>,
        excluding: Set<Uuid>,
        requestCreatedAt: LocalDateTime,
    ): List<Uuid> {
        val candidates =
            lockedAdminRows.filter { it[AccountTable.role] == AccountRole.ADMIN && it[AccountTable.memberId] !in excluding }
        if (candidates.isEmpty()) return emptyList()
        val tenureCutoff = (requestCreatedAt.toInstant(TimeZone.UTC) - ADMIN_TENURE).toLocalDateTime(TimeZone.UTC)
        val tenured =
            candidates.filter {
                val changedAt = it[AccountTable.roleChangedAt]
                changedAt == null || changedAt <= tenureCutoff
            }
        if (tenured.isEmpty()) return emptyList()
        val ids = tenured.map { it[AccountTable.memberId] }
        return MemberTable
            .selectAll()
            .where {
                (MemberTable.id inList ids) and
                    (MemberTable.status notInList MemberStatusSets.LOGIN_BLOCKED) and
                    MemberTable.anonymizedAt.isNull()
            }.map { it[MemberTable.id] }
    }

    /** Number of administrators who could approve, see [eligibleApproverIds]. */
    fun eligibleApproverCount(
        lockedAdminRows: List<ResultRow>,
        excluding: Set<Uuid>,
        requestCreatedAt: LocalDateTime,
    ): Int = eligibleApproverIds(lockedAdminRows = lockedAdminRows, excluding = excluding, requestCreatedAt = requestCreatedAt).size

    /** Is [approverId] one of the [eligibleApproverCount] administrators? Same rules, for ONE person. */
    fun isEligibleApprover(
        lockedAdminRows: List<ResultRow>,
        approverId: Uuid,
        excluding: Set<Uuid>,
        requestCreatedAt: LocalDateTime,
    ): Boolean {
        if (approverId in excluding) return false
        val row = lockedAdminRows.singleOrNull { it[AccountTable.memberId] == approverId } ?: return false
        return eligibleApproverCount(
            lockedAdminRows = listOf(row),
            excluding = excluding,
            requestCreatedAt = requestCreatedAt,
        ) == 1
    }

    /**
     * Locks the target (member row, then the account union) and decides [action] for [actor]. Returns on [PeerDecision.Allow]
     * / [PeerDecision.Mask]; on [PeerDecision.Deny] and [PeerDecision.RequiresApproval] throws [PeerDeniedSignal] (the
     * caller must run inside [peerGuarded]). Must be called inside an open `transaction {}`.
     *
     * [memberRow] is the already-locked member row when the caller took the lock itself, otherwise it is locked here.
     */
    fun require(
        actor: CurrentMember,
        targetId: Uuid,
        action: PeerAction,
        mailConfigured: Boolean,
        memberRow: ResultRow? = null,
    ): Pair<PeerDecision, LockedPeerFacts> {
        val row = memberRow ?: lockMember(targetId) ?: throw NotFoundException("Member not found")
        val facts = lockFactsAfterMemberLock(targetId = targetId, memberRow = row, requesterId = actor.memberId)
        return decideLocked(actor = actor, targetId = targetId, action = action, mailConfigured = mailConfigured, facts = facts) to facts
    }

    /**
     * Decides [action] on facts the caller already holds under lock (so no second, differently ordered lock is taken).
     * Same contract as [require]: returns on Allow/Mask, throws [PeerDeniedSignal] on Deny/RequiresApproval.
     */
    fun decideLocked(
        actor: CurrentMember,
        targetId: Uuid,
        action: PeerAction,
        mailConfigured: Boolean,
        facts: LockedPeerFacts,
    ): PeerDecision {
        val decision =
            PeerPolicy.decide(
                actorRole = actor.role,
                actorId = actor.memberId,
                targetRole = facts.targetRole,
                targetId = targetId,
                action = action,
                eligibleApprovers = facts.eligibleApprovers,
                mailConfigured = mailConfigured,
            )
        if (decision is PeerDecision.Deny || decision is PeerDecision.RequiresApproval) {
            throw PeerDeniedSignal(decision = decision, action = action, targetId = targetId, targetRole = facts.targetRole)
        }
        return decision
    }
}

/**
 * Welle V1.9.57 -- runs [block] (which calls [PeerGuard.require] inside its transaction) and converts a refusal into the
 * typed RPC exception AFTER the transaction rolled back, auditing it in its own short transaction first.
 */
internal inline fun <T> peerGuarded(
    actor: CurrentMember,
    block: () -> T,
): T =
    try {
        block()
    } catch (signal: PeerDeniedSignal) {
        PeerDenyAudit.record(actor = actor, signal = signal)
        throw signal.toRpcException()
    }

internal fun PeerDeniedSignal.toRpcException(): Exception =
    when (val d = decision) {
        is PeerDecision.RequiresApproval -> PeerApprovalRequiredException()
        is PeerDecision.Deny ->
            when (d.reason) {
                PeerDenyReason.NO_SECOND_ADMIN -> NoSecondAdminException()
                else -> PeerProtectionDeniedException()
            }
        else -> IllegalStateException("not a refusal")
    }

/**
 * Welle V1.9.57 -- audit of a REFUSED action against an ADMIN account. The main transaction was rolled back, so the entry
 * is written in a fresh, short transaction; it is throttled per caller (a refusal is cheap to provoke, the chain must not
 * become a DoS target) and never fails the caller (a broken audit write must not turn a refusal into a 500). Only
 * refusals against ADMIN targets for the actions that matter are recorded.
 */
internal object PeerDenyAudit {
    private val limiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.hours)

    private val AUDITED_ACTIONS =
        setOf(
            PeerAction.TEMP_PASSWORD,
            PeerAction.DEMOTE,
            PeerAction.SUSPEND,
            PeerAction.ERASE,
            PeerAction.LINK_IDENTITY,
            PeerAction.EMAIL_OVERRIDE,
            PeerAction.WRITE_PROTECTED_DATA,
        )

    fun record(
        actor: CurrentMember,
        signal: PeerDeniedSignal,
    ) {
        if (signal.targetRole != AccountRole.ADMIN || signal.action !in AUDITED_ACTIONS) return
        if (!limiter.checkAndRecord("actor:${actor.memberId}")) return
        val denyReason =
            when (val d = signal.decision) {
                is PeerDecision.Deny -> d.reason
                else -> null
            }
        runCatching {
            transaction {
                val status =
                    MemberTable
                        .selectAll()
                        .where { MemberTable.id eq signal.targetId }
                        .singleOrNull()
                        ?.get(MemberTable.status)
                        ?: return@transaction
                val before =
                    MemberChangeSnapshot(displayNameChanged = false, emailChanged = false, status = status, role = signal.targetRole)
                val after =
                    before.copy(
                        peerAction =
                            PeerActionAuditFacts(
                                event = PeerAuditEvent.DENIED,
                                action = signal.action,
                                targetRole = signal.targetRole,
                                denyReason = denyReason,
                            ),
                    )
                AuditLogRecorder.record(
                    actorMemberId = actor.memberId,
                    actorRole = actor.role,
                    entityType = AuditEntityType.MEMBER,
                    entityId = signal.targetId,
                    action = AuditAction.UPDATE,
                    before = Json.encodeToString(MemberChangeSnapshot.serializer(), before),
                    after = Json.encodeToString(MemberChangeSnapshot.serializer(), after),
                )
            }
        }.onFailure { e -> logger.error { "peer denial audit failed: ${e::class.simpleName}" } }
    }
}

/** Thin forwarding to [PeerGuard.require] with the caller's own identity (see the V1.9.57 plan, section 3). */
internal fun CurrentMember.peerDecision(
    targetRole: AccountRole?,
    targetId: Uuid,
    action: PeerAction,
    eligibleApprovers: Int,
    mailConfigured: Boolean,
): PeerDecision =
    PeerPolicy.decide(
        actorRole = role,
        actorId = memberId,
        targetRole = targetRole,
        targetId = targetId,
        action = action,
        eligibleApprovers = eligibleApprovers,
        mailConfigured = mailConfigured,
    )
