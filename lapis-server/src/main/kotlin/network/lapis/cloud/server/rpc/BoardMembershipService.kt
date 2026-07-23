package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.TransparenzregisterReminderTable
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BeneficialOwnerDataGapDto
import network.lapis.cloud.shared.domain.BoardMembershipDto
import network.lapis.cloud.shared.domain.BoardMembershipInput
import network.lapis.cloud.shared.domain.TransparenzregisterReminderDto
import network.lapis.cloud.shared.domain.TransparenzregisterReportDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IBoardMembershipService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val BOARD_ADMIN_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * V0.5.2 §20 GwG Transparenzregister beneficial-owner reminder support. Implements
 * [IBoardMembershipService] -- see that interface's KDoc for the full "reminder/acknowledgement
 * only, no automated filing" rationale and the legal-review flags this domain carries.
 *
 * [BoardMembershipTable] is a Transparenzregister-facing beneficial-owner roster, deliberately kept
 * PARALLEL to (not a replacement of) [network.lapis.cloud.server.db.generated
 * .CommitteeMembershipTable], which already seats the real Vorstand at
 * [ElectionService.tally]-time -- see `13-transparenzregister.kuml.kts` file header for why a
 * clean new entity was added instead of extending
 * [network.lapis.cloud.server.db.generated.ElectionBoardMemberTable] (that table is the
 * *Wahlvorstand*, the election-counting board, structurally unrelated to the elected Vorstand).
 * [ElectionService.tally]'s own `EXECUTIVE_BOARD`-targeted winner-seating branch calls
 * [BoardMembershipEvents.recordBoardJoin] directly, in the same `transaction {}`, right after it
 * seats the winner into [network.lapis.cloud.server.db.generated.CommitteeMembershipTable].
 * [GovernanceService.addCommitteeMember]/[GovernanceService.endCommitteeMembership] do the same
 * when their target Committee is `EXECUTIVE_BOARD` (co-option/removal via the ordinary governance
 * flow rather than an election). This class's own [appointBoardMember]/[endBoardMembership] cover
 * the remaining administrative path a board membership can start or end outside both of those
 * (a change that never goes through any Committee-membership row at all).
 */
class BoardMembershipService(
    private val call: ApplicationCall,
) : IBoardMembershipService {
    override suspend fun listCurrentBoard(): List<BoardMembershipDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ADMIN_ROLES)
        return transaction {
            (BoardMembershipTable innerJoin MemberTable)
                .selectAll()
                .where { BoardMembershipTable.endedAt.isNull() }
                .map { it.toBoardMembershipDto() }
        }
    }

    override suspend fun appointBoardMember(input: BoardMembershipInput): BoardMembershipDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ADMIN_ROLES)
        val memberId = input.memberId.toUuidOrNotFound("Member")
        return transaction {
            MemberTable.selectAll().where { MemberTable.id eq memberId }.singleOrNull()
                ?: throw NotFoundException("Member ${input.memberId} not found")
            val id = BoardMembershipEvents.recordBoardJoin(memberId, input.committeeRole, input.startedAt, nowLocalDateTime())
            // V0.5.3 GoBD audit log: called last, after recordBoardJoin's own writes and before the
            // final read-only select below, so this satisfies AuditLogRecorder's deadlock-avoidance
            // contract -- see auditBoardMembershipCreate KDoc for the full call-site rationale.
            auditBoardMembershipCreate(id, memberId, input.committeeRole, input.startedAt, current)
            loadBoardMembership(id)
        }
    }

    override suspend fun endBoardMembership(
        boardMembershipId: String,
        endedAt: LocalDate,
    ): BoardMembershipDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ADMIN_ROLES)
        val id = boardMembershipId.toUuidOrNotFound("BoardMembership")
        return transaction {
            val beforeRow =
                BoardMembershipTable.selectAll().where { BoardMembershipTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("BoardMembership $boardMembershipId not found")
            BoardMembershipEvents.recordBoardLeave(id, endedAt, nowLocalDateTime())
            // V0.5.3 GoBD audit log: called last, after recordBoardLeave's own writes and before the
            // final read-only select below -- see auditBoardMembershipEnd KDoc for the full
            // call-site rationale.
            auditBoardMembershipEnd(
                id,
                beforeRow[BoardMembershipTable.memberId],
                beforeRow[BoardMembershipTable.committeeRole],
                beforeRow[BoardMembershipTable.startedAt],
                endedAt,
                current,
            )
            loadBoardMembership(id)
        }
    }

    override suspend fun getTransparenzregisterReport(): TransparenzregisterReportDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ADMIN_ROLES)
        return transaction {
            val openReminders =
                TransparenzregisterReminderTable
                    .selectAll()
                    .where { TransparenzregisterReminderTable.resolved eq false }
                    .map { it.toReminderDto() }

            val currentBoardRows =
                (BoardMembershipTable innerJoin MemberTable)
                    .selectAll()
                    .where { BoardMembershipTable.endedAt.isNull() }
                    .toList()
            val currentBoard = currentBoardRows.map { it.toBoardMembershipDto() }

            val beneficialOwnerDataGaps =
                currentBoardRows.mapNotNull { row ->
                    val missingDateOfBirth = row[MemberTable.dateOfBirth] == null
                    val missingNationality = row[MemberTable.nationality] == null
                    if (!missingDateOfBirth && !missingNationality) {
                        null
                    } else {
                        BeneficialOwnerDataGapDto(
                            memberId = row[BoardMembershipTable.memberId].toString(),
                            memberDisplayName = row[MemberTable.displayName],
                            committeeRole = row[BoardMembershipTable.committeeRole],
                            missingDateOfBirth = missingDateOfBirth,
                            missingNationality = missingNationality,
                        )
                    }
                }

            TransparenzregisterReportDto(
                openReminders = openReminders,
                currentBoard = currentBoard,
                beneficialOwnerDataGaps = beneficialOwnerDataGaps,
            )
        }
    }

    override suspend fun listTransparenzregisterReminders(includeResolved: Boolean): List<TransparenzregisterReminderDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ADMIN_ROLES)
        return transaction {
            val baseQuery = TransparenzregisterReminderTable.selectAll()
            val query = if (includeResolved) baseQuery else baseQuery.where { TransparenzregisterReminderTable.resolved eq false }
            query.map { it.toReminderDto() }
        }
    }

    override suspend fun resolveTransparenzregisterReminder(reminderId: String): TransparenzregisterReminderDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ADMIN_ROLES)
        val id = reminderId.toUuidOrNotFound("TransparenzregisterReminder")
        return transaction {
            val row =
                TransparenzregisterReminderTable
                    .selectAll()
                    .where { TransparenzregisterReminderTable.id eq id }
                    .singleOrNull()
                    ?: throw NotFoundException("TransparenzregisterReminder $reminderId not found")
            if (row[TransparenzregisterReminderTable.resolved]) {
                throw ConflictException("TransparenzregisterReminder $reminderId already resolved")
            }
            TransparenzregisterReminderTable.update({ TransparenzregisterReminderTable.id eq id }) {
                it[resolved] = true
                it[resolvedAt] = nowLocalDateTime()
                it[resolvedBy] = current.memberId
            }
            TransparenzregisterReminderTable
                .selectAll()
                .where { TransparenzregisterReminderTable.id eq id }
                .single()
                .toReminderDto()
        }
    }

    private fun loadBoardMembership(id: Uuid): BoardMembershipDto =
        (BoardMembershipTable innerJoin MemberTable)
            .selectAll()
            .where { BoardMembershipTable.id eq id }
            .single()
            .toBoardMembershipDto()

    private fun memberDisplayName(memberId: Uuid?): String? =
        memberId?.let { id ->
            MemberTable
                .selectAll()
                .where { MemberTable.id eq id }
                .singleOrNull()
                ?.get(MemberTable.displayName)
        }

    // Deliberately NOT an aliased multi-join against MemberTable -- this entity has TWO member
    // FKs (memberId + resolvedBy), so [memberDisplayName], a small follow-up lookup per id, is used
    // instead, same "no aliased multi-joins" house style GovernanceService/ElectionService already
    // establish for their own multi-member-FK entities (e.g. meeting.called_by/chair_member_id).
    private fun ResultRow.toReminderDto(): TransparenzregisterReminderDto {
        val subjectMemberId = this[TransparenzregisterReminderTable.memberId]
        val resolvedById = this[TransparenzregisterReminderTable.resolvedBy]
        return TransparenzregisterReminderDto(
            id = this[TransparenzregisterReminderTable.id].toString(),
            triggeredAt = this[TransparenzregisterReminderTable.triggeredAt],
            memberId = subjectMemberId.toString(),
            memberDisplayName = memberDisplayName(subjectMemberId).orEmpty(),
            committeeRole = this[TransparenzregisterReminderTable.committeeRole],
            changeType = this[TransparenzregisterReminderTable.changeType],
            resolved = this[TransparenzregisterReminderTable.resolved],
            resolvedAt = this[TransparenzregisterReminderTable.resolvedAt],
            resolvedById = resolvedById?.toString(),
            resolvedByDisplayName = memberDisplayName(resolvedById),
        )
    }

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    private fun String.toUuidOrNotFound(kind: String): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $kind id: $this") }
}

private fun ResultRow.toBoardMembershipDto(): BoardMembershipDto =
    BoardMembershipDto(
        id = this[BoardMembershipTable.id].toString(),
        memberId = this[BoardMembershipTable.memberId].toString(),
        memberDisplayName = this[MemberTable.displayName],
        committeeRole = this[BoardMembershipTable.committeeRole],
        startedAt = this[BoardMembershipTable.startedAt],
        endedAt = this[BoardMembershipTable.endedAt],
    )
