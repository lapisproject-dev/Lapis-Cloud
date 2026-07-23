package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.TransparenzregisterReminderTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.BoardChangeType
import network.lapis.cloud.shared.domain.BoardMembershipSnapshot
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * [CommitteeRole]s that can only be held by one member at a time -- CHAIR/DEPUTY_CHAIR/SECRETARY
 * are named seats; MEMBER/ASSESSOR are ordinary board seats several people hold concurrently.
 * [recordBoardJoin] uses this to detect a "displaced incumbent" (a contested election unseating a
 * sitting CHAIR, e.g.) -- see that function's KDoc.
 */
private val SINGLE_HOLDER_COMMITTEE_ROLES = setOf(CommitteeRole.CHAIR, CommitteeRole.DEPUTY_CHAIR, CommitteeRole.SECRETARY)

/**
 * V0.5.2 §20 GwG Transparenzregister beneficial-owner event recording -- the single place
 * [network.lapis.cloud.server.rpc.ElectionService.tally]'s `EXECUTIVE_BOARD` winner-seating branch,
 * [network.lapis.cloud.server.rpc.GovernanceService.addCommitteeMember]/[network.lapis.cloud.server
 * .rpc.GovernanceService.endCommitteeMembership] (when the target Committee is `EXECUTIVE_BOARD`
 * -- co-option/removal outside an election), and [BoardMembershipService]'s own administrative
 * appoint/end actions all hook into, so a "Vorstandsaenderung" always produces at least one
 * [BoardMembershipTable] mutation plus one [TransparenzregisterReminderTable] row, regardless of
 * which of these paths triggered it. [recordBoardJoin] can additionally produce a second,
 * *displaced-incumbent* mutation+reminder pair -- see that function's KDoc.
 *
 * Transaction-free by contract, same as [ResolutionBook] -- every function here must run inside
 * the caller's already-open `transaction {}` (this is what lets `ElectionService.tally` call
 * [recordBoardJoin] without nesting a second transaction).
 */
object BoardMembershipEvents {
    /**
     * Closes any currently-open (`endedAt == null`) [BoardMembershipTable] row for [memberId] as of
     * [startedAt] -- WITHOUT emitting a `LEFT` reminder for it, so a re-election of an incumbent
     * (same or new [role]) produces exactly one `JOINED` reminder for the new term, not a
     * LEFT+JOINED pair. Flagged for legal review: a lawyer should confirm this single-reminder
     * choice is enough to prompt the manual register update on a role change.
     *
     * Displaced-incumbent handling: [role] may be a [SINGLE_HOLDER_COMMITTEE_ROLES] seat
     * (CHAIR/DEPUTY_CHAIR/SECRETARY), which by definition can only be held by one member at a
     * time. If some OTHER member currently holds an open [BoardMembershipTable] row for that same
     * [role] (a contested election unseating a sitting incumbent, e.g. `targetRole = CHAIR` and
     * `winner != incumbent`), that row is closed too and a `LEFT` reminder is emitted for it --
     * this genuinely is the other half of the Vorstandsaenderung, and without it the departed
     * beneficial owner would never be flagged for removal from the real Transparenzregister while
     * [BoardMembershipTable] would incorrectly show two simultaneous holders of the same seat.
     * MEMBER/ASSESSOR are ordinary (non-single-holder) seats and are exempt from this check.
     *
     * Then inserts a fresh [BoardMembershipTable] row and a `JOINED`
     * [TransparenzregisterReminderTable] row. Returns the new [BoardMembershipTable] row's id.
     */
    fun recordBoardJoin(
        memberId: Uuid,
        role: CommitteeRole,
        startedAt: LocalDate,
        now: LocalDateTime,
    ): Uuid {
        BoardMembershipTable.update({
            (BoardMembershipTable.memberId eq memberId) and (BoardMembershipTable.endedAt.isNull())
        }) {
            it[endedAt] = startedAt
        }
        if (role in SINGLE_HOLDER_COMMITTEE_ROLES) {
            val displacedRows =
                BoardMembershipTable
                    .selectAll()
                    .where {
                        (BoardMembershipTable.committeeRole eq role) and
                            (BoardMembershipTable.endedAt.isNull()) and
                            (BoardMembershipTable.memberId neq memberId)
                    }.toList()
            displacedRows.forEach { row ->
                val displacedId = row[BoardMembershipTable.id]
                BoardMembershipTable.update({ BoardMembershipTable.id eq displacedId }) {
                    it[endedAt] = startedAt
                }
                TransparenzregisterReminderTable.insert {
                    it[TransparenzregisterReminderTable.id] = Uuid.random()
                    it[triggeredAt] = now
                    it[TransparenzregisterReminderTable.memberId] = row[BoardMembershipTable.memberId]
                    it[committeeRole] = role
                    it[changeType] = BoardChangeType.LEFT
                    it[resolved] = false
                    it[resolvedAt] = null
                    it[resolvedBy] = null
                }
            }
        }
        val id = Uuid.random()
        BoardMembershipTable.insert {
            it[BoardMembershipTable.id] = id
            it[BoardMembershipTable.memberId] = memberId
            it[committeeRole] = role
            it[BoardMembershipTable.startedAt] = startedAt
            it[endedAt] = null
        }
        TransparenzregisterReminderTable.insert {
            it[TransparenzregisterReminderTable.id] = Uuid.random()
            it[triggeredAt] = now
            it[TransparenzregisterReminderTable.memberId] = memberId
            it[committeeRole] = role
            it[changeType] = BoardChangeType.JOINED
            it[resolved] = false
            it[resolvedAt] = null
            it[resolvedBy] = null
        }
        return id
    }

    /**
     * Ends the [BoardMembershipTable] row identified by [boardMembershipId] as of [endedAt] and
     * inserts a `LEFT` [TransparenzregisterReminderTable] row. Throws [NotFoundException] if that
     * row does not exist, [ConflictException] if it is already ended.
     */
    fun recordBoardLeave(
        boardMembershipId: Uuid,
        endedAt: LocalDate,
        now: LocalDateTime,
    ) {
        val row =
            BoardMembershipTable
                .selectAll()
                .where { BoardMembershipTable.id eq boardMembershipId }
                .singleOrNull()
                ?: throw NotFoundException("BoardMembership $boardMembershipId not found")
        if (row[BoardMembershipTable.endedAt] != null) {
            throw ConflictException("BoardMembership $boardMembershipId already ended")
        }
        BoardMembershipTable.update({ BoardMembershipTable.id eq boardMembershipId }) {
            it[BoardMembershipTable.endedAt] = endedAt
        }
        TransparenzregisterReminderTable.insert {
            it[TransparenzregisterReminderTable.id] = Uuid.random()
            it[triggeredAt] = now
            it[memberId] = row[BoardMembershipTable.memberId]
            it[committeeRole] = row[BoardMembershipTable.committeeRole]
            it[changeType] = BoardChangeType.LEFT
            it[resolved] = false
            it[resolvedAt] = null
            it[resolvedBy] = null
        }
    }
}

/**
 * V0.5.3 GoBD audit-log helper for a [BoardMembershipEvents.recordBoardJoin]-created
 * [BoardMembershipTable] row -- CREATE, matching [BoardMembershipService.appointBoardMember]'s
 * own audit shape. Widens Resolution-book-style audit coverage from just the administrative
 * [BoardMembershipService.appointBoardMember] path to every [BoardMembershipEvents.recordBoardJoin]
 * call site (`ElectionService.tally`'s `EXECUTIVE_BOARD` winner-seating and
 * [GovernanceService.addCommitteeMember]'s co-option path too) -- fixes a V0.5.3-review finding
 * that only the manually-typed administrative path was ever audited, while the vote-/election-
 * decided Vorstandsaenderungen this codebase's own conventions flag as the higher-scrutiny case
 * left no tamper-evident record at all. See `14-audit-log.kuml.kts` file header for the resulting
 * widened scope statement.
 *
 * Deliberately NOT called from inside [BoardMembershipEvents.recordBoardJoin] itself, for the same
 * deadlock-avoidance reason [auditResolutionCreate] documents: `ElectionService.tally` calls
 * [BoardMembershipEvents.recordBoardJoin] from inside a winner-seating loop that is followed by
 * further row-locking writes (`MotionTable.update`/`ElectionTable.update`) later in the same
 * transaction. Every call site therefore invokes this helper itself, explicitly, as (part of) the
 * true last locking operation of its own transaction -- see each call site for the exact placement.
 *
 * Only the freshly created row is audited here, matching the granularity
 * [BoardMembershipService.appointBoardMember] already established -- a displaced single-holder
 * incumbent's row closing inside [BoardMembershipEvents.recordBoardJoin] itself (CHAIR/
 * DEPUTY_CHAIR/SECRETARY unseating) is a pre-existing gap in that established shape, not something
 * this fix introduces or widens; broadening audit granularity beyond what
 * [BoardMembershipService.appointBoardMember] already does is out of this fix's scope.
 */
internal fun auditBoardMembershipCreate(
    boardMembershipId: Uuid,
    memberId: Uuid,
    committeeRole: CommitteeRole,
    startedAt: LocalDate,
    current: CurrentMember,
) {
    AuditLogRecorder.record(
        actorMemberId = current.memberId,
        actorRole = current.role,
        entityType = AuditEntityType.BOARD_MEMBERSHIP,
        entityId = boardMembershipId,
        action = AuditAction.CREATE,
        before = null,
        after =
            Json.encodeToString(
                BoardMembershipSnapshot.serializer(),
                BoardMembershipSnapshot(
                    memberId = memberId.toString(),
                    committeeRole = committeeRole,
                    startedAt = startedAt,
                    endedAt = null,
                ),
            ),
    )
}

/**
 * V0.5.3 GoBD audit-log helper for a [BoardMembershipEvents.recordBoardLeave]-ended
 * [BoardMembershipTable] row -- UPDATE (`endedAt` null -> set), matching
 * [BoardMembershipService.endBoardMembership]'s own audit shape. Widens coverage to
 * [GovernanceService.endCommitteeMembership]'s `EXECUTIVE_BOARD` removal path, mirroring
 * [auditBoardMembershipCreate]'s widened scope for the join side. Same deliberate
 * not-called-from-inside-[BoardMembershipEvents.recordBoardLeave] reasoning as
 * [auditBoardMembershipCreate] -- every call site invokes this itself as the true last locking
 * operation of its own transaction.
 */
internal fun auditBoardMembershipEnd(
    boardMembershipId: Uuid,
    memberId: Uuid,
    committeeRole: CommitteeRole,
    startedAt: LocalDate,
    endedAt: LocalDate,
    current: CurrentMember,
) {
    AuditLogRecorder.record(
        actorMemberId = current.memberId,
        actorRole = current.role,
        entityType = AuditEntityType.BOARD_MEMBERSHIP,
        entityId = boardMembershipId,
        action = AuditAction.UPDATE,
        before =
            Json.encodeToString(
                BoardMembershipSnapshot.serializer(),
                BoardMembershipSnapshot(
                    memberId = memberId.toString(),
                    committeeRole = committeeRole,
                    startedAt = startedAt,
                    endedAt = null,
                ),
            ),
        after =
            Json.encodeToString(
                BoardMembershipSnapshot.serializer(),
                BoardMembershipSnapshot(
                    memberId = memberId.toString(),
                    committeeRole = committeeRole,
                    startedAt = startedAt,
                    endedAt = endedAt,
                ),
            ),
    )
}
