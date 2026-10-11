package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** Welle V1.9.102 -- what triggered an automatic end of a committee seat. Only one trigger exists today. */
internal enum class CommitteeSeatEndTrigger { MEMBER_STATUS_CHANGE, }

/**
 * Welle V1.9.102 -- before/after snapshot of one automatically ended committee seat for the GoBD audit log. Ids, enums and dates only:
 * no display name, no mail address, no date of death (data minimisation -- the audit log is append-only and cannot be erased).
 */
@Serializable
internal data class CommitteeMembershipAuditSnapshot(
    val memberId: String,
    val committeeId: String,
    val committeeType: CommitteeType,
    val role: CommitteeRole,
    val since: LocalDate,
    val until: LocalDate?,
    val autoEndReason: MemberStatus?,
    val trigger: String? = null,
    val newMemberStatus: MemberStatus? = null,
)

/**
 * An audit-log write that has been prepared but not executed yet. [AuditLogRecorder.record] takes a global `SELECT ... FOR UPDATE` on the
 * hash-chain state row and, by its own contract, must be the LAST row lock of the transaction; the seat hook therefore collects its audits
 * and lets the caller execute them at the very end (see [CommitteeSeatEndResult.recordAudits]).
 */
internal class PendingAudit(
    val write: () -> Unit,
)

/**
 * Result of [CommitteeSeatLifecycle.endSeatsForStatusChange] (phase 1). Carries the prepared audits of phase 2.
 */
internal class CommitteeSeatEndResult(
    val endedMembershipIds: List<Uuid>,
    /** Executive boards that had an eligible CHAIR from the following day on before the hook and have none after it. */
    val vacatedChairBoardIds: List<Uuid>,
    private val pendingAudits: List<PendingAudit>,
) {
    val endedCount: Int get() = endedMembershipIds.size

    /**
     * Phase 2 -- writes the audit entries. MUST run as (part of) the last audit writes of the caller's transaction, after every other
     * row-locking write (SEPA mandates, photo/bio revokes, regional-chapter officer grants ...) -- see [AuditLogRecorder] deadlock contract.
     */
    fun recordAudits() {
        pendingAudits.forEach { it.write() }
    }

    companion object {
        val EMPTY =
            CommitteeSeatEndResult(endedMembershipIds = emptyList(), vacatedChairBoardIds = emptyList(), pendingAudits = emptyList())
    }
}

/**
 * Welle V1.9.102 "Gremien, Welle B" -- the ONE place that ends committee seats automatically when a member's status changes to one of
 * [MemberStatusSets.COMMITTEE_SEAT_ENDING] (WITHDRAWN, REJECTED, DECEASED, DONOR). Called from exactly three places (a tripwire test pins
 * that): `MemberRoleStatusMutations.applyStatusChangeLocked` (the central status path of the admin UI, the four-eyes privileged action and
 * the operator console), `RegistrationService.leaveMembership` and `RegistrationService.rejectApplication`.
 *
 * **Semantics.** Every seat of the member with `until IS NULL OR until > stichtag` gets `until = stichtag` and `auto_end_reason = reason`.
 * `until` is INCLUSIVE everywhere in the eligibility code (`CommitteeEligibility`): the person still counts on the day of the change and no
 * longer from the following day (known limitation, documented). Seats that already ended on or before the stichtag are never touched, so a
 * second call is a no-op (idempotent). A seat that has not started yet (`since > stichtag`) also gets `until = stichtag`; the resulting
 * empty interval makes the person eligible on no day at all, and the row is kept for the record, never deleted. Past votes, closed
 * elections, frozen election snapshots and earlier resolutions are not touched: only seat rows of this member change.
 *
 * **Two phases (audit lock order).** Phase 1 ([endSeatsForStatusChange]) writes seat rows (and the EXECUTIVE_BOARD cascade onto
 * `BoardMembership`) but NO audit; the caller then does its remaining row-locking writes and finally calls
 * [CommitteeSeatEndResult.recordAudits] (phase 2), directly before its own `MEMBER` audit entry.
 *
 * **Failure policy.** The hook runs inside the status change's own transaction: a database error rolls the whole status change back (a half
 * state such as "withdrawn but still chair" is exactly what this wave removes), and a retry is safe because the hook is idempotent. A
 * business consequence -- an executive board left without a CHAIR -- never throws; it is reported in [CommitteeSeatEndResult.vacatedChairBoardIds],
 * logged, and shown to the board by the notice band.
 */
internal object CommitteeSeatLifecycle {
    private val logger = KotlinLogging.logger {}

    /**
     * Phase 1. Transaction-free by contract: must run inside the caller's already-open `transaction {}`.
     *
     * @param newStatus the member's target status (recorded in the audit snapshot)
     * @param reason what is stored in `auto_end_reason`; must be in [MemberStatusSets.COMMITTEE_SEAT_ENDING]. Separate from [newStatus]
     *   because `rejectApplication` falls back to FRIEND for a former friend yet still ends the seats with reason REJECTED.
     * @param stichtag the organisation-time-zone date of the status change
     * @param actorMemberId the human who triggered the status change (deciding BOARD/ADMIN, or the leaving member); null only for the
     *   operator console. Recorded on every seat-end and EXECUTIVE_BOARD cascade audit (GoBD attribution, section 20 GwG board change).
     */
    fun endSeatsForStatusChange(
        memberId: Uuid,
        newStatus: MemberStatus,
        reason: MemberStatus,
        stichtag: LocalDate,
        now: LocalDateTime,
        actorMemberId: Uuid? = null,
        actorRole: AccountRole? = null,
    ): CommitteeSeatEndResult {
        check(TransactionManager.currentOrNull() != null) {
            "CommitteeSeatLifecycle.endSeatsForStatusChange must run inside an already-open transaction {} block"
        }
        require(reason in MemberStatusSets.COMMITTEE_SEAT_ENDING) { "reason $reason does not end committee seats" }

        // Deterministic lock order (by id) so two concurrent status changes of different members cannot deadlock on shared committee rows.
        val rows =
            (CommitteeMembershipTable innerJoin CommitteeTable)
                .selectAll()
                .where {
                    (CommitteeMembershipTable.memberId eq memberId) and
                        (CommitteeMembershipTable.until.isNull() or (CommitteeMembershipTable.until greater stichtag))
                }.orderBy(CommitteeMembershipTable.id)
                .toList()
        if (rows.isEmpty()) return CommitteeSeatEndResult.EMPTY

        // "Has a chair": evaluated for the day AFTER the stichtag, because `until` is inclusive and a chair ended today still counts today.
        val firstDayWithoutSeat = stichtag.plus(1, DateTimeUnit.DAY)
        val chairedBefore = chairedExecutiveBoardIds(firstDayWithoutSeat)

        val endedIds = mutableListOf<Uuid>()
        val audits = mutableListOf<PendingAudit>()
        for (row in rows) {
            val seatId = row[CommitteeMembershipTable.id]
            val updated =
                CommitteeMembershipTable.update({
                    (CommitteeMembershipTable.id eq seatId) and
                        (CommitteeMembershipTable.until.isNull() or (CommitteeMembershipTable.until greater stichtag))
                }) {
                    it[until] = stichtag
                    it[autoEndReason] = reason
                }
            if (updated == 0) continue // a parallel run already ended it
            endedIds += seatId
            cascadeExecutiveBoardEnd(
                committeeId = row[CommitteeMembershipTable.committeeId],
                memberId = memberId,
                until = stichtag,
                now = now,
                actorMemberId = actorMemberId,
                actorRole = actorRole,
            )?.let { audits += it }
            audits +=
                seatAudit(
                    row = row,
                    stichtag = stichtag,
                    reason = reason,
                    newStatus = newStatus,
                    now = now,
                    actorMemberId = actorMemberId,
                    actorRole = actorRole,
                )
        }

        val chairedAfter = chairedExecutiveBoardIds(firstDayWithoutSeat)
        val vacated = (chairedBefore - chairedAfter).toList().sortedBy { it.toString() }
        logger.info {
            "Committee seats ended automatically: member=$memberId reason=$reason seats=${endedIds.size} " +
                "boardsWithoutChair=${vacated.joinToString(",")}"
        }
        return CommitteeSeatEndResult(endedMembershipIds = endedIds, vacatedChairBoardIds = vacated, pendingAudits = audits)
    }

    /**
     * Ids of the active EXECUTIVE_BOARD committees that have at least one CHAIR seat eligible on [date]
     * (`since <= date`, `until IS NULL OR until >= date`, same predicate as `CommitteeEligibility`).
     */
    fun chairedExecutiveBoardIds(date: LocalDate): Set<Uuid> =
        (CommitteeMembershipTable innerJoin CommitteeTable)
            .selectAll()
            .where {
                (CommitteeTable.type eq CommitteeType.EXECUTIVE_BOARD) and
                    (CommitteeTable.active eq true) and
                    (CommitteeMembershipTable.role eq CommitteeRole.CHAIR) and
                    (CommitteeMembershipTable.since lessEq date) and
                    (CommitteeMembershipTable.until.isNull() or (CommitteeMembershipTable.until greaterEq date))
            }.map { it[CommitteeMembershipTable.committeeId] }
            .toSet()

    private fun seatAudit(
        row: ResultRow,
        stichtag: LocalDate,
        reason: MemberStatus,
        newStatus: MemberStatus,
        now: LocalDateTime,
        actorMemberId: Uuid?,
        actorRole: AccountRole?,
    ): PendingAudit {
        val seatId = row[CommitteeMembershipTable.id]
        val before =
            CommitteeMembershipAuditSnapshot(
                memberId = row[CommitteeMembershipTable.memberId].toString(),
                committeeId = row[CommitteeMembershipTable.committeeId].toString(),
                committeeType = row[CommitteeTable.type],
                role = row[CommitteeMembershipTable.role],
                since = row[CommitteeMembershipTable.since],
                until = row[CommitteeMembershipTable.until],
                autoEndReason = row[CommitteeMembershipTable.autoEndReason],
            )
        val after =
            before.copy(
                until = stichtag,
                autoEndReason = reason,
                trigger = CommitteeSeatEndTrigger.MEMBER_STATUS_CHANGE.name,
                newMemberStatus = newStatus,
            )
        return PendingAudit {
            AuditLogRecorder.record(
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                entityType = AuditEntityType.COMMITTEE_MEMBERSHIP,
                entityId = seatId,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(CommitteeMembershipAuditSnapshot.serializer(), before),
                after = Json.encodeToString(CommitteeMembershipAuditSnapshot.serializer(), after),
                occurredAt = now,
            )
        }
    }
}
