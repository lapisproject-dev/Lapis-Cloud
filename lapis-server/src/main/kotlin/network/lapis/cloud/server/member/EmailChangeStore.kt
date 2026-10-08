package network.lapis.cloud.server.member

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.forMemberUpdate
import network.lapis.cloud.server.db.generated.MemberEmailChangeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.shared.domain.EmailChangeKind
import network.lapis.cloud.shared.domain.EmailChangeStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** Outcome of [EmailChangeStore.applyLocked]. */
internal sealed interface ApplyOutcome {
    data object Applied : ApplyOutcome

    /** The address is (or became, by a race) the address of another member -- the change is now CONFLICT. */
    data object Duplicate : ApplyOutcome
}

/**
 * Welle V1.9.56 "E-Mail-Änderung absichern" -- the persistence layer of the address-change lifecycle and **the only
 * place that writes `member.email` of an existing member** ([applyLocked]; the source-scan tripwire
 * `MemberEmailWriteTripwireTest` enforces this). Every function must run INSIDE the caller's `transaction {}` with the
 * member row already locked `FOR UPDATE` ([lockMember]) -- lock order is always `member -> member_email_change`, and
 * the audit write is the LAST lock-taking operation (see `AuditLogRecorder`).
 *
 * **At most one open change per member** is enforced twice: by the member lock plus [supersedeOpenLocked], and by the
 * database (`uq_member_email_change_open`, the H2-capable substitute for a partial unique index, see V70).
 *
 * Tokens are stored as SHA-256 hashes only; [resolveLocked] nulls both hashes of a finished change, so a used or
 * superseded link is dead by construction (the lookups below additionally require `status = PENDING`).
 */
internal object EmailChangeStore {
    /** How long a proposal (path B) stays open. */
    val PROPOSAL_TTL: Duration = 7.days

    /** Warning period between the request and the earliest effective time of paths B0 and C. */
    val OVERRIDE_DELAY: Duration = 72.hours

    /** How long a path B0/C change stays open (proof of ownership of the new address must happen within this window). */
    val OVERRIDE_TTL: Duration = 7.days

    /** Resolved changes (and the third-party addresses in them) are erased after this period. */
    val RESOLVED_RETENTION: Duration = 180.days

    private const val PENDING = "PENDING"

    fun plus(
        at: LocalDateTime,
        duration: Duration,
    ): LocalDateTime = (at.toInstant(TimeZone.UTC) + duration).toLocalDateTime(TimeZone.UTC)

    /** Locks and returns the member row, or null if it does not exist. */
    fun lockMember(memberId: Uuid): ResultRow? =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .forMemberUpdate()
            .singleOrNull()

    /** The open (PENDING) change of [memberId], locked, or null. Does NOT check the expiry -- callers decide. */
    fun openChangeLocked(memberId: Uuid): ResultRow? =
        MemberEmailChangeTable
            .selectAll()
            .where { MemberEmailChangeTable.openMemberId eq memberId }
            .forUpdate()
            .singleOrNull()

    /** A change by id, locked, or null. */
    fun changeByIdLocked(changeId: Uuid): ResultRow? =
        MemberEmailChangeTable
            .selectAll()
            .where { MemberEmailChangeTable.id eq changeId }
            .forUpdate()
            .singleOrNull()

    /** Unlocked read, for the member-id lookup that precedes the member lock. */
    fun changeById(changeId: Uuid): ResultRow? =
        MemberEmailChangeTable
            .selectAll()
            .where { MemberEmailChangeTable.id eq changeId }
            .singleOrNull()

    /** The still-open (PENDING, unexpired) change of [memberId] without locking -- for reads. */
    fun openChangeUnexpired(
        memberId: Uuid,
        now: LocalDateTime,
    ): ResultRow? =
        MemberEmailChangeTable
            .selectAll()
            .where {
                (MemberEmailChangeTable.openMemberId eq memberId) and
                    (MemberEmailChangeTable.status eq PENDING) and
                    (MemberEmailChangeTable.expiresAt greater now)
            }.singleOrNull()

    /** Unlocked lookup of a PENDING, unexpired change by the hash of the link token sent to the NEW address. */
    fun findByConfirmHash(
        hash: String,
        now: LocalDateTime,
    ): ResultRow? =
        MemberEmailChangeTable
            .selectAll()
            .where {
                (MemberEmailChangeTable.confirmTokenHash eq hash) and
                    (MemberEmailChangeTable.status eq PENDING) and
                    (MemberEmailChangeTable.expiresAt greater now)
            }.singleOrNull()

    /** Unlocked lookup of a PENDING, unexpired change by the hash of the link token sent to the OLD address. */
    fun findByRevokeHash(
        hash: String,
        now: LocalDateTime,
    ): ResultRow? =
        MemberEmailChangeTable
            .selectAll()
            .where {
                (MemberEmailChangeTable.revokeTokenHash eq hash) and
                    (MemberEmailChangeTable.status eq PENDING) and
                    (MemberEmailChangeTable.expiresAt greater now)
            }.singleOrNull()

    /** Ids of PENDING changes that are expired as of [now]. */
    fun expiredPendingIds(now: LocalDateTime): List<Uuid> =
        MemberEmailChangeTable
            .selectAll()
            .where { (MemberEmailChangeTable.status eq PENDING) and (MemberEmailChangeTable.expiresAt lessEq now) }
            .map { it[MemberEmailChangeTable.id] }

    /** Ids of PENDING path B0/C changes whose new address is confirmed and whose warning period has elapsed. */
    fun applicableIds(now: LocalDateTime): List<Uuid> =
        MemberEmailChangeTable
            .selectAll()
            .where {
                (MemberEmailChangeTable.status eq PENDING) and
                    (MemberEmailChangeTable.kind neq EmailChangeKind.PROPOSAL.name) and
                    MemberEmailChangeTable.newEmailConfirmedAt.isNotNull() and
                    MemberEmailChangeTable.effectiveAt.isNotNull() and
                    (MemberEmailChangeTable.effectiveAt lessEq now) and
                    (MemberEmailChangeTable.expiresAt greater now)
            }.map { it[MemberEmailChangeTable.id] }

    /** Ends the open change of [memberId], if any, as SUPERSEDED. Returns the superseded row (for the audit entry) or null. */
    fun supersedeOpenLocked(
        memberId: Uuid,
        now: LocalDateTime,
    ): ResultRow? {
        val open = openChangeLocked(memberId) ?: return null
        resolveLocked(changeId = open[MemberEmailChangeTable.id], status = EmailChangeStatus.SUPERSEDED, now = now)
        return open
    }

    /** Inserts a PENDING change (member lock held, no open change left). Returns the new id. */
    fun insertPending(
        memberId: Uuid,
        pendingEmail: String,
        kind: EmailChangeKind,
        requestedBy: Uuid?,
        reason: String?,
        confirmHash: String,
        revokeHash: String,
        now: LocalDateTime,
        expiresAt: LocalDateTime,
        effectiveAt: LocalDateTime?,
    ): Uuid {
        val id = Uuid.random()
        MemberEmailChangeTable.insert {
            it[MemberEmailChangeTable.id] = id
            it[MemberEmailChangeTable.memberId] = memberId
            it[openMemberId] = memberId
            it[MemberEmailChangeTable.pendingEmail] = pendingEmail
            it[MemberEmailChangeTable.kind] = kind.name
            it[MemberEmailChangeTable.requestedBy] = requestedBy
            it[MemberEmailChangeTable.reason] = reason
            it[confirmTokenHash] = confirmHash
            it[revokeTokenHash] = revokeHash
            it[status] = PENDING
            it[createdAt] = now
            it[MemberEmailChangeTable.expiresAt] = expiresAt
            it[MemberEmailChangeTable.effectiveAt] = effectiveAt
            it[newEmailConfirmedAt] = null
            it[resolvedAt] = null
        }
        return id
    }

    /** Records an immediate own change (path A) as an already APPLIED row, so audit and export have a change id. */
    fun insertAppliedSelf(
        memberId: Uuid,
        pendingEmail: String,
        now: LocalDateTime,
    ): Uuid {
        val id = Uuid.random()
        MemberEmailChangeTable.insert {
            it[MemberEmailChangeTable.id] = id
            it[MemberEmailChangeTable.memberId] = memberId
            it[openMemberId] = null
            it[MemberEmailChangeTable.pendingEmail] = pendingEmail
            it[kind] = EmailChangeKind.SELF.name
            it[requestedBy] = memberId
            it[reason] = null
            it[confirmTokenHash] = null
            it[revokeTokenHash] = null
            it[status] = EmailChangeStatus.APPLIED.name
            it[createdAt] = now
            it[expiresAt] = now
            it[effectiveAt] = now
            it[newEmailConfirmedAt] = null
            it[resolvedAt] = now
        }
        return id
    }

    /**
     * Welle V1.9.73 -- records an address taken over from the identity provider at login as an already APPLIED row
     * (`kind = IDP_SYNC`, no requester, no tokens), so audit and export have a change id. The caller then runs [applyLocked],
     * which may still turn the row into CONFLICT. Member lock held.
     */
    fun insertAppliedIdpSync(
        memberId: Uuid,
        pendingEmail: String,
        now: LocalDateTime,
    ): Uuid {
        val id = Uuid.random()
        MemberEmailChangeTable.insert {
            it[MemberEmailChangeTable.id] = id
            it[MemberEmailChangeTable.memberId] = memberId
            it[openMemberId] = null
            it[MemberEmailChangeTable.pendingEmail] = pendingEmail
            it[kind] = EmailChangeKind.IDP_SYNC.name
            it[requestedBy] = null
            it[reason] = null
            it[confirmTokenHash] = null
            it[revokeTokenHash] = null
            it[status] = EmailChangeStatus.APPLIED.name
            it[createdAt] = now
            it[expiresAt] = now
            it[effectiveAt] = now
            it[newEmailConfirmedAt] = now
            it[resolvedAt] = now
        }
        return id
    }

    /** Stamps the proof of ownership of the new address (path B0/C). */
    fun markNewAddressConfirmedLocked(
        changeId: Uuid,
        now: LocalDateTime,
    ) {
        MemberEmailChangeTable.update({ MemberEmailChangeTable.id eq changeId }) {
            it[newEmailConfirmedAt] = now
        }
    }

    /** Finishes a change: status, `open_member_id = NULL`, `resolved_at`, both token hashes dropped. */
    fun resolveLocked(
        changeId: Uuid,
        status: EmailChangeStatus,
        now: LocalDateTime,
    ) {
        require(status != EmailChangeStatus.PENDING) { "resolveLocked finishes a change; PENDING is not a final status" }
        MemberEmailChangeTable.update({ MemberEmailChangeTable.id eq changeId }) {
            it[MemberEmailChangeTable.status] = status.name
            it[openMemberId] = null
            it[resolvedAt] = now
            it[confirmTokenHash] = null
            it[revokeTokenHash] = null
        }
    }

    /**
     * **The one and only write of `member.email` for an existing member.** Sets the address to [newEmail]
     * (already lowercased) and `emailVerifiedAt` to [now] when [verified] (ownership of the new address was proven),
     * otherwise to null. Finishes [changeId] as APPLIED -- or as CONFLICT when the address belongs to another member
     * (pre-check on `lower(email)`, plus the unique-constraint backstop for a concurrent claim, run under a savepoint so
     * the surrounding PostgreSQL transaction stays usable, SQLSTATE 25P02). Any other SQL failure is rethrown.
     */
    fun applyLocked(
        memberId: Uuid,
        changeId: Uuid,
        newEmail: String,
        verified: Boolean,
        now: LocalDateTime,
    ): ApplyOutcome {
        val usedByAnother =
            MemberTable
                .selectAll()
                .where { (MemberTable.email.lowerCase() eq newEmail) and (MemberTable.id neq memberId) }
                .count() > 0
        if (usedByAnother) {
            resolveLocked(changeId = changeId, status = EmailChangeStatus.CONFLICT, now = now)
            return ApplyOutcome.Duplicate
        }
        try {
            withSavepoint(name = "email_change_apply") {
                MemberTable.update({ MemberTable.id eq memberId }) {
                    it[email] = newEmail
                    it[emailVerifiedAt] = if (verified) now else null
                }
            }
        } catch (e: ExposedSQLException) {
            if (!e.isUniqueViolation()) throw e
            resolveLocked(changeId = changeId, status = EmailChangeStatus.CONFLICT, now = now)
            return ApplyOutcome.Duplicate
        }
        resolveLocked(changeId = changeId, status = EmailChangeStatus.APPLIED, now = now)
        return ApplyOutcome.Applied
    }

    /** Deletes resolved changes older than [RESOLVED_RETENTION] -- third-party addresses must not be kept indefinitely. */
    fun purgeResolved(now: LocalDateTime): Int {
        val cutoff = plus(at = now, duration = -RESOLVED_RETENTION)
        return MemberEmailChangeTable.deleteWhere {
            (MemberEmailChangeTable.status neq PENDING) and (MemberEmailChangeTable.resolvedAt less cutoff)
        }
    }
}
