package network.lapis.cloud.server.member

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.PrivilegedActionRequestTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PrivilegedActionKind
import network.lapis.cloud.shared.domain.PrivilegedActionStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- persistence of `privileged_action_request`. Every function must run INSIDE the caller's
 * `transaction {}`. **Lock order is global and fixed: target `member` row, then the id-ordered account union
 * (`PeerGuard.lockFactsAfterMemberLock`), then the request row** -- the creating path inserts a row only after the member lock,
 * so a path that locked the request row first would be a lock-order inversion against it (the written plan said request first;
 * member first is the order every other writer in this schema already uses, see `EmailChangeStore`). `AuditLogRecorder.record`
 * is always the last lock-taking call.
 *
 * **At most one open request per (target, action)** is enforced twice: by the member lock plus the lazy-expiry check of the
 * service, and by the database (`uq_privileged_action_request_open`, the H2-capable substitute for a partial unique index, V71).
 * The veto token is stored as a SHA-256 hash only; [resolveLocked] nulls it, so a used or finished request's link is dead.
 */
internal object PrivilegedActionStore {
    /** How long a request waits for an approver. */
    val APPROVAL_TTL: Duration = 72.hours

    /** Temporary password: the target's objection period between the approval and the earliest generation. */
    val OBJECTION_DELAY: Duration = 24.hours

    /** Temporary password: how long the requester may generate the password after [OBJECTION_DELAY]. */
    val EXECUTE_WINDOW: Duration = 72.hours

    /** Resolved requests are erased after this period. */
    val RESOLVED_RETENTION: Duration = 180.days

    private val OPEN = listOf(PrivilegedActionStatus.PENDING.name, PrivilegedActionStatus.APPROVED_WAITING.name)

    fun plus(
        at: LocalDateTime,
        duration: Duration,
    ): LocalDateTime = (at.toInstant(TimeZone.UTC) + duration).toLocalDateTime(TimeZone.UTC)

    /** Unlocked read, for the target-id lookup that precedes the member lock. */
    fun byId(id: Uuid): ResultRow? = PrivilegedActionRequestTable.selectAll().where { PrivilegedActionRequestTable.id eq id }.singleOrNull()

    /** The request by id, locked. Call it only AFTER the member lock and the account union (see class KDoc). */
    fun byIdLocked(id: Uuid): ResultRow? =
        PrivilegedActionRequestTable
            .selectAll()
            .where { PrivilegedActionRequestTable.id eq id }
            .forUpdate()
            .singleOrNull()

    /** The open request of ([targetId], [action]), locked, or null. Does NOT check the expiry -- callers decide. */
    fun openLocked(
        targetId: Uuid,
        action: PrivilegedActionKind,
    ): ResultRow? =
        PrivilegedActionRequestTable
            .selectAll()
            .where {
                (PrivilegedActionRequestTable.openTargetMemberId eq targetId) and
                    (PrivilegedActionRequestTable.action eq action.name)
            }.forUpdate()
            .singleOrNull()

    /** Unlocked lookup of an open request by the hash of its objection token. */
    fun findOpenByVetoHash(hash: String): ResultRow? =
        PrivilegedActionRequestTable
            .selectAll()
            .where {
                (PrivilegedActionRequestTable.vetoTokenHash eq hash) and (PrivilegedActionRequestTable.status inList OPEN)
            }.singleOrNull()

    /** Ids of requests that are due for expiry as of [now]: PENDING past `expires_at`, APPROVED_WAITING past `execute_until`. */
    fun dueIds(now: LocalDateTime): List<Uuid> {
        val pending =
            PrivilegedActionRequestTable
                .selectAll()
                .where {
                    (PrivilegedActionRequestTable.status eq PrivilegedActionStatus.PENDING.name) and
                        (PrivilegedActionRequestTable.expiresAt lessEq now)
                }.map { it[PrivilegedActionRequestTable.id] }
        val waiting =
            PrivilegedActionRequestTable
                .selectAll()
                .where {
                    (PrivilegedActionRequestTable.status eq PrivilegedActionStatus.APPROVED_WAITING.name) and
                        (PrivilegedActionRequestTable.executeUntil lessEq now)
                }.map { it[PrivilegedActionRequestTable.id] }
        return pending + waiting
    }

    /** Open requests (PENDING / APPROVED_WAITING), newest first, unlocked -- for the overview card. */
    fun openRows(limit: Int): List<ResultRow> =
        PrivilegedActionRequestTable
            .selectAll()
            .where { PrivilegedActionRequestTable.status inList OPEN }
            .orderBy(PrivilegedActionRequestTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .toList()

    /** Requests [actorId] made that are open or were resolved at or after [since], newest first, unlocked. */
    fun requestedBy(
        actorId: Uuid,
        since: LocalDateTime,
        limit: Int,
    ): List<ResultRow> =
        PrivilegedActionRequestTable
            .selectAll()
            .where {
                (PrivilegedActionRequestTable.actorMemberId eq actorId) and
                    ((PrivilegedActionRequestTable.status inList OPEN) or (PrivilegedActionRequestTable.resolvedAt greaterEq since))
            }.orderBy(PrivilegedActionRequestTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .toList()

    @Suppress("LongParameterList")
    fun insertPending(
        action: PrivilegedActionKind,
        actorId: Uuid,
        targetId: Uuid,
        targetRole: AccountRole,
        requestedRole: AccountRole?,
        requestedStatus: MemberStatus?,
        reason: String,
        vetoTokenHash: String?,
        now: LocalDateTime,
    ): Uuid {
        val id = Uuid.random()
        PrivilegedActionRequestTable.insert {
            it[PrivilegedActionRequestTable.id] = id
            it[PrivilegedActionRequestTable.action] = action.name
            it[actorMemberId] = actorId
            it[targetMemberId] = targetId
            it[openTargetMemberId] = targetId
            it[targetRoleAtRequest] = targetRole.name
            it[PrivilegedActionRequestTable.requestedRole] = requestedRole?.name
            it[PrivilegedActionRequestTable.requestedStatus] = requestedStatus?.name
            it[PrivilegedActionRequestTable.reason] = reason
            it[status] = PrivilegedActionStatus.PENDING.name
            it[approverMemberId] = null
            it[PrivilegedActionRequestTable.vetoTokenHash] = vetoTokenHash
            it[createdAt] = now
            it[expiresAt] = plus(at = now, duration = APPROVAL_TTL)
            it[notBefore] = null
            it[executeUntil] = null
            it[decidedAt] = null
            it[resolvedAt] = null
        }
        return id
    }

    /** Moves a PENDING request to APPROVED_WAITING (temporary password only): approver, decision time, objection period, execute window. */
    fun markApprovedWaitingLocked(
        id: Uuid,
        approverId: Uuid,
        now: LocalDateTime,
    ) {
        val notBeforeAt = plus(at = now, duration = OBJECTION_DELAY)
        PrivilegedActionRequestTable.update({ PrivilegedActionRequestTable.id eq id }) {
            it[status] = PrivilegedActionStatus.APPROVED_WAITING.name
            it[approverMemberId] = approverId
            it[decidedAt] = now
            it[notBefore] = notBeforeAt
            it[executeUntil] = plus(at = notBeforeAt, duration = EXECUTE_WINDOW)
        }
    }

    /** Finishes a request: status, `open_target_member_id = NULL`, `resolved_at`, the veto token hash dropped. */
    fun resolveLocked(
        id: Uuid,
        status: PrivilegedActionStatus,
        now: LocalDateTime,
        approverId: Uuid? = null,
        decided: Boolean = false,
    ) {
        require(status.isOpen.not()) { "resolveLocked finishes a request; ${status.name} is an open status" }
        PrivilegedActionRequestTable.update({ PrivilegedActionRequestTable.id eq id }) {
            it[PrivilegedActionRequestTable.status] = status.name
            it[openTargetMemberId] = null
            it[resolvedAt] = now
            it[vetoTokenHash] = null
            if (approverId != null) it[approverMemberId] = approverId
            if (decided) it[decidedAt] = now
        }
    }

    /** Deletes resolved requests older than [RESOLVED_RETENTION]. */
    fun purgeResolved(now: LocalDateTime): Int {
        val cutoff = plus(at = now, duration = -RESOLVED_RETENTION)
        return PrivilegedActionRequestTable.deleteWhere {
            (PrivilegedActionRequestTable.status neq PrivilegedActionStatus.PENDING.name) and
                (PrivilegedActionRequestTable.status neq PrivilegedActionStatus.APPROVED_WAITING.name) and
                (PrivilegedActionRequestTable.resolvedAt less cutoff)
        }
    }
}
