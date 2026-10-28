package network.lapis.cloud.server.governance.session

import network.lapis.cloud.server.conference.ConferenceConnectedMembers
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.computeQuorum
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.canRecordForMeeting
import network.lapis.cloud.shared.domain.AttendanceRuleKind
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.ConferenceMeetingStateDto
import network.lapis.cloud.shared.domain.EstablishedQuorumDto
import network.lapis.cloud.shared.domain.MeetingSessionAuditKind
import network.lapis.cloud.shared.domain.MeetingSessionAuditSnapshot
import network.lapis.cloud.shared.domain.MeetingSessionNextStep
import network.lapis.cloud.shared.domain.MeetingSessionPhase
import network.lapis.cloud.shared.domain.MeetingSessionRole
import network.lapis.cloud.shared.domain.SessionAgendaItemDto
import network.lapis.cloud.shared.domain.SessionAttendanceInput
import network.lapis.cloud.shared.domain.SessionRosterEntryDto
import network.lapis.cloud.shared.rpc.AgendaMovedMeanwhileException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** V1.9.236 -- process-wide limiters (top level: `GovernanceService` is constructed anew for every RPC call). Checked AFTER authentication, per member. */
internal val MEETING_SESSION_READ_RATE_LIMITER = FederationInboxRateLimiter(maxRequests = 40, window = 1.minutes)
internal val MEETING_SESSION_MUTATE_RATE_LIMITER = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes)

/**
 * V1.9.236 "Sitzungsmodus im Konferenzraum" -- the six operations behind `IGovernanceService.getConferenceMeetingState` .. `moveConferenceAgenda`.
 * `GovernanceService` only delegates here.
 *
 * **Access.** Every operation resolves `(roomId, meetingId)` through [MeetingSessionAccess]: whatever the caller must not see is one and the same
 * not-found. The role ([MeetingSessionRole]) then gates the operation: a PARTICIPANT never mutates (Forbidden), the MINUTES role keeps the
 * attendance only. Reads send a PARTICIPANT no name, no roster and no live quorum.
 *
 * **State machine** (derived, see [meetingSessionPhase]): NOT_OPENED -> OPEN (`openConferenceMeeting`) -> CLOSED (status HELD/CANCELLED). Every
 * mutation but `open` needs OPEN; CLOSED is read-only; an ended room is read-only too.
 *
 * **Locks and effects.** Mutations lock the Sitzung row `FOR UPDATE` first, the audit entry is the last step. LiveKit is asked OUTSIDE any
 * transaction (before the locking one), and only for LEAD/MINUTES; a participant never triggers a LiveKit call.
 *
 * **Privacy.** No log line and no exception message carries a name, a roster or an identity list; single attendance changes are not audited.
 */
internal class MeetingSessionOperations(
    private val conferencesEnabled: () -> Boolean,
    private val connectedMembers: ConferenceConnectedMembers,
    private val readLimiter: FederationInboxRateLimiter = MEETING_SESSION_READ_RATE_LIMITER,
    private val mutateLimiter: FederationInboxRateLimiter = MEETING_SESSION_MUTATE_RATE_LIMITER,
) {
    // ---- read ----

    suspend fun getState(
        current: CurrentMember,
        roomId: Uuid?,
    ): ConferenceMeetingStateDto? {
        if (!conferencesEnabled() || roomId == null) return null
        requireWithinRate(limiter = readLimiter, current = current)
        val probe =
            transaction {
                MeetingSessionAccess.resolve(current = current, roomId = roomId, expectedMeetingId = null, lock = false)?.let(::probeOf)
            } ?: return null
        val connected = connectedFor(probe)
        return transaction {
            MeetingSessionAccess.resolve(current = current, roomId = roomId, expectedMeetingId = null, lock = false)?.let {
                buildState(current = current, ctx = it, connected = connected)
            }
        }
    }

    // ---- mutations ----

    suspend fun open(
        current: CurrentMember,
        roomId: Uuid,
        meetingId: Uuid,
    ): ConferenceMeetingStateDto {
        beginMutation(current)
        transaction {
            val ctx = MeetingSessionAccess.require(current = current, roomId = roomId, expectedMeetingId = meetingId, lock = true)
            requireRole(ctx = ctx, MeetingSessionRole.LEAD)
            requireRoomLive(ctx)
            when (phaseOf(ctx)) {
                MeetingSessionPhase.NOT_OPENED -> Unit
                MeetingSessionPhase.OPEN -> throw ConflictException("already opened")
                MeetingSessionPhase.CLOSED -> throw ConflictException("meeting is closed")
            }
            val firstItem =
                AgendaItemTable
                    .selectAll()
                    .where { AgendaItemTable.meetingId eq ctx.meetingId }
                    .orderBy(AgendaItemTable.position to SortOrder.ASC, AgendaItemTable.id to SortOrder.ASC)
                    .limit(1)
                    .singleOrNull()
                    ?.get(AgendaItemTable.id)
            MeetingTable.update({ MeetingTable.id eq ctx.meetingId }) {
                it[openedAt] = DbClock.nowLocalDateTime()
                it[currentAgendaItemId] = firstItem
            }
            MeetingSessionAudit.record(
                actor = current,
                snapshot =
                    MeetingSessionAuditSnapshot(
                        kind = MeetingSessionAuditKind.OPENED,
                        meetingId = ctx.meetingId.toString(),
                        toAgendaItemId = firstItem?.toString(),
                    ),
            )
        }
        return requireState(current = current, roomId = roomId)
    }

    suspend fun setAttendance(
        current: CurrentMember,
        roomId: Uuid,
        meetingId: Uuid,
        input: SessionAttendanceInput,
    ): ConferenceMeetingStateDto {
        beginMutation(current)
        val memberId = parseId(input.memberId)
        val representedBy = input.representedByMemberId?.let(::parseId)
        transaction {
            val ctx = MeetingSessionAccess.require(current = current, roomId = roomId, expectedMeetingId = meetingId, lock = true)
            requireRole(ctx = ctx, MeetingSessionRole.LEAD, MeetingSessionRole.MINUTES)
            requireRoomLive(ctx)
            requireOpen(ctx = ctx)
            val rule = CommitteeAttendanceRules.forType(ctx.committeeRow[CommitteeTable.type])
            val rows = AttendanceTable.selectAll().where { AttendanceTable.meetingId eq ctx.meetingId }.toList()
            val statuses = rows.associate { it[AttendanceTable.memberId] to it[AttendanceTable.status] }
            val representatives = rows.mapNotNull { it[AttendanceTable.representedByMemberId] }.toSet()
            val roster =
                rule
                    .roster(
                        meetingId = ctx.meetingId,
                        committeeRow = ctx.committeeRow,
                        scheduledDate = ctx.meetingRow[MeetingTable.scheduledAt].date,
                    ).map { it.memberId }
                    .toSet()
            rule.validate(
                input = ValidatedAttendance(memberId = memberId, status = input.status, representedBy = representedBy),
                roster = roster,
                current = statuses,
                representatives = representatives,
            )
            val status = input.status
            if (status == null) {
                AttendanceTable.deleteWhere { (AttendanceTable.meetingId eq ctx.meetingId) and (AttendanceTable.memberId eq memberId) }
            } else {
                val note = rows.firstOrNull { it[AttendanceTable.memberId] == memberId }?.get(AttendanceTable.note)
                AttendanceWriter.upsert(
                    meetingId = ctx.meetingId,
                    memberId = memberId,
                    status = status,
                    representedBy = representedBy,
                    note = note,
                    now = DbClock.nowLocalDateTime(),
                )
            }
        }
        return requireState(current = current, roomId = roomId)
    }

    suspend fun adoptConnected(
        current: CurrentMember,
        roomId: Uuid,
        meetingId: Uuid,
    ): Int {
        beginMutation(current)
        // Phase 1 (no lock): who may do this, and which LiveKit room to ask -- the LiveKit call itself stays outside any transaction.
        val probe =
            transaction {
                val ctx = MeetingSessionAccess.require(current = current, roomId = roomId, expectedMeetingId = meetingId, lock = false)
                requireRole(ctx = ctx, MeetingSessionRole.LEAD, MeetingSessionRole.MINUTES)
                probeOf(ctx)
            }
        val connected = connectedFor(probe)
        return transaction {
            val ctx = MeetingSessionAccess.require(current = current, roomId = roomId, expectedMeetingId = meetingId, lock = true)
            requireRole(ctx = ctx, MeetingSessionRole.LEAD, MeetingSessionRole.MINUTES)
            requireRoomLive(ctx)
            requireOpen(ctx = ctx)
            val rule = CommitteeAttendanceRules.forType(ctx.committeeRow[CommitteeTable.type])
            if (rule.kind == AttendanceRuleKind.UNSUPPORTED) throw ConflictException("attendance not supported for this committee kind")
            val roster =
                rule
                    .roster(
                        meetingId = ctx.meetingId,
                        committeeRow = ctx.committeeRow,
                        scheduledDate = ctx.meetingRow[MeetingTable.scheduledAt].date,
                    ).map {
                        it.memberId
                    }
            val connectedIds = connected.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }.toSet()
            val existing =
                AttendanceTable
                    .selectAll()
                    .where { AttendanceTable.meetingId eq ctx.meetingId }
                    .map { it[AttendanceTable.memberId] }
                    .toSet()
            val now = DbClock.nowLocalDateTime()
            var added = 0
            roster.filter { it in connectedIds && it !in existing }.forEach { memberId ->
                // A concurrent writer of the old recordAttendance RPC does not take the Sitzung lock: a unique violation is skipped, not thrown.
                val inserted =
                    try {
                        withSavepoint(name = "adopt_attendance") {
                            AttendanceTable.insert {
                                it[AttendanceTable.id] = Uuid.random()
                                it[AttendanceTable.meetingId] = ctx.meetingId
                                it[AttendanceTable.memberId] = memberId
                                it[status] = AttendanceStatus.PRESENT
                                it[representedByMemberId] = null
                                it[note] = null
                                it[recordedAt] = now
                            }
                        }
                        true
                    } catch (e: ExposedSQLException) {
                        if (e.isUniqueViolation()) false else throw e
                    }
                if (inserted) added++
            }
            MeetingSessionAudit.record(
                actor = current,
                snapshot =
                    MeetingSessionAuditSnapshot(
                        kind = MeetingSessionAuditKind.ATTENDANCE_ADOPTED,
                        meetingId = ctx.meetingId.toString(),
                        adoptedCount = added,
                    ),
            )
            added
        }
    }

    suspend fun establishQuorum(
        current: CurrentMember,
        roomId: Uuid,
        meetingId: Uuid,
        acknowledgeNotMet: Boolean,
    ): ConferenceMeetingStateDto {
        beginMutation(current)
        transaction {
            val ctx = MeetingSessionAccess.require(current = current, roomId = roomId, expectedMeetingId = meetingId, lock = true)
            requireRole(ctx = ctx, MeetingSessionRole.LEAD)
            requireRoomLive(ctx)
            requireOpen(ctx = ctx, notOpenedMessage = "open the meeting first")
            val quorum =
                computeQuorum(
                    meetingId = ctx.meetingId,
                    committeeId = ctx.committeeId,
                    scheduledDate = ctx.meetingRow[MeetingTable.scheduledAt].date,
                )
            if (!quorum.met && !acknowledgeNotMet) {
                throw ConflictException("quorum not met: ${quorum.presentCount}/${quorum.requiredCount}/${quorum.eligibleMemberCount}")
            }
            MeetingTable.update({ MeetingTable.id eq ctx.meetingId }) {
                it[quorumEstablishedAt] = DbClock.nowLocalDateTime()
                it[quorumPresentCount] = quorum.presentCount
                it[quorumRequiredCount] = quorum.requiredCount
                it[quorumEligibleCount] = quorum.eligibleMemberCount
                it[quorumOverridden] = !quorum.met
            }
            MeetingSessionAudit.record(
                actor = current,
                snapshot =
                    MeetingSessionAuditSnapshot(
                        kind = MeetingSessionAuditKind.QUORUM_ESTABLISHED,
                        meetingId = ctx.meetingId.toString(),
                        present = quorum.presentCount,
                        required = quorum.requiredCount,
                        eligible = quorum.eligibleMemberCount,
                        overridden = !quorum.met,
                    ),
            )
        }
        return requireState(current = current, roomId = roomId)
    }

    suspend fun moveAgenda(
        current: CurrentMember,
        roomId: Uuid,
        meetingId: Uuid,
        fromAgendaItemId: String?,
        toAgendaItemId: String,
    ): ConferenceMeetingStateDto {
        beginMutation(current)
        val from = fromAgendaItemId?.let(::parseId)
        val to = parseId(toAgendaItemId)
        transaction {
            val ctx = MeetingSessionAccess.require(current = current, roomId = roomId, expectedMeetingId = meetingId, lock = true)
            requireRole(ctx = ctx, MeetingSessionRole.LEAD)
            requireRoomLive(ctx)
            requireOpen(ctx = ctx)
            // The target must be an item of THIS Sitzung (an item of another Sitzung is as unknown as a random id).
            AgendaItemTable
                .selectAll()
                .where { (AgendaItemTable.id eq to) and (AgendaItemTable.meetingId eq ctx.meetingId) }
                .singleOrNull() ?: throw NotFoundException(MeetingSessionAccess.NOT_FOUND_MESSAGE)
            if (effectiveCurrentItem(ctx) != from) throw AgendaMovedMeanwhileException()
            // V1.9.238: no hand-over while a vote is running (a closed one waiting for its tally does not hold the agenda).
            MeetingSessionMotionReads.requireNoOpenDecisionInMeeting(meetingId = ctx.meetingId, openOnly = true)
            MeetingTable.update({ MeetingTable.id eq ctx.meetingId }) { it[currentAgendaItemId] = to }
            MeetingSessionAudit.record(
                actor = current,
                snapshot =
                    MeetingSessionAuditSnapshot(
                        kind = MeetingSessionAuditKind.AGENDA_MOVED,
                        meetingId = ctx.meetingId.toString(),
                        fromAgendaItemId = from?.toString(),
                        toAgendaItemId = to.toString(),
                    ),
            )
        }
        return requireState(current = current, roomId = roomId)
    }

    // ---- helpers ----

    /** The current agenda item as the reader shows it: a marker that is not an item of this Sitzung (no foreign key, see V127) counts as "none". */
    internal fun effectiveCurrentItem(ctx: SessionContext): Uuid? =
        ctx.meetingRow[MeetingTable.currentAgendaItemId]?.takeIf {
            AgendaItemTable
                .selectAll()
                .where { (AgendaItemTable.id eq it) and (AgendaItemTable.meetingId eq ctx.meetingId) }
                .count() > 0
        }

    internal fun beginMutation(current: CurrentMember) {
        if (!conferencesEnabled()) throw NotFoundException(MeetingSessionAccess.NOT_FOUND_MESSAGE)
        requireWithinRate(limiter = mutateLimiter, current = current)
    }

    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        current: CurrentMember,
    ) {
        if (!limiter.checkAndRecord(
                "member:${current.memberId}",
            )
        ) {
            throw ConflictException("Zu viele Anfragen -- bitte später erneut versuchen.")
        }
    }

    internal fun parseId(raw: String): Uuid =
        runCatching {
            Uuid.parse(raw)
        }.getOrElse { throw NotFoundException(MeetingSessionAccess.NOT_FOUND_MESSAGE) }

    internal fun requireRole(
        ctx: SessionContext,
        vararg allowed: MeetingSessionRole,
    ) {
        if (ctx.role !in allowed) throw ForbiddenException()
    }

    internal fun requireRoomLive(ctx: SessionContext) {
        if (ctx.roomRow[ConferenceRoomTable.endedAt] != null) throw ConflictException("the conference room has ended")
    }

    internal fun phaseOf(ctx: SessionContext): MeetingSessionPhase =
        meetingSessionPhase(status = ctx.meetingRow[MeetingTable.status], openedAt = ctx.meetingRow[MeetingTable.openedAt])

    internal fun requireOpen(
        ctx: SessionContext,
        notOpenedMessage: String = "meeting is not open",
    ) {
        when (phaseOf(ctx)) {
            MeetingSessionPhase.OPEN -> Unit
            MeetingSessionPhase.NOT_OPENED -> throw ConflictException(notOpenedMessage)
            MeetingSessionPhase.CLOSED -> throw ConflictException("meeting is closed")
        }
    }

    private class Probe(
        val role: MeetingSessionRole,
        val livekitRoomName: String,
    )

    private fun probeOf(ctx: SessionContext) = Probe(role = ctx.role, livekitRoomName = ctx.roomRow[ConferenceRoomTable.livekitRoomName])

    /** LiveKit is asked only for the roles that see the roster; a participant never triggers a call. Outside any transaction. */
    private suspend fun connectedFor(probe: Probe): Set<String> =
        if (probe.role == MeetingSessionRole.PARTICIPANT) emptySet() else connectedMembers.connectedIdentities(probe.livekitRoomName)

    /** The fresh state after a mutation: the caller just passed the very same access check, so a null here means the room vanished meanwhile. */
    internal suspend fun requireState(
        current: CurrentMember,
        roomId: Uuid,
    ): ConferenceMeetingStateDto {
        val probe =
            transaction {
                MeetingSessionAccess.resolve(current = current, roomId = roomId, expectedMeetingId = null, lock = false)?.let(::probeOf)
            }
                ?: throw NotFoundException(MeetingSessionAccess.NOT_FOUND_MESSAGE)
        val connected = connectedFor(probe)
        return transaction {
            MeetingSessionAccess.resolve(current = current, roomId = roomId, expectedMeetingId = null, lock = false)?.let {
                buildState(current = current, ctx = it, connected = connected)
            }
        }
            ?: throw NotFoundException(MeetingSessionAccess.NOT_FOUND_MESSAGE)
    }

    /** Builds the DTO from rows read in the current transaction. [connected] is the raw LiveKit identity set (empty for a participant). */
    private fun buildState(
        current: CurrentMember,
        ctx: SessionContext,
        connected: Set<String>,
    ): ConferenceMeetingStateDto {
        val meeting = ctx.meetingRow
        val phase = phaseOf(ctx)
        val committeeType = ctx.committeeRow[CommitteeTable.type]
        val rule = CommitteeAttendanceRules.forType(committeeType)
        val agenda =
            AgendaItemTable
                .selectAll()
                .where { AgendaItemTable.meetingId eq ctx.meetingId }
                .orderBy(AgendaItemTable.position to SortOrder.ASC, AgendaItemTable.id to SortOrder.ASC)
                .map {
                    SessionAgendaItemDto(
                        id = it[AgendaItemTable.id].toString(),
                        position = it[AgendaItemTable.position],
                        title = it[AgendaItemTable.title],
                    )
                }
        val currentItem = meeting[MeetingTable.currentAgendaItemId]?.toString()?.takeIf { marker -> agenda.any { it.id == marker } }
        val established =
            meeting[MeetingTable.quorumEstablishedAt]?.let { at ->
                EstablishedQuorumDto(
                    at = at,
                    present = meeting[MeetingTable.quorumPresentCount] ?: 0,
                    required = meeting[MeetingTable.quorumRequiredCount] ?: 0,
                    eligible = meeting[MeetingTable.quorumEligibleCount] ?: 0,
                    overridden = meeting[MeetingTable.quorumOverridden],
                )
            }
        val manage = ctx.role == MeetingSessionRole.LEAD && current.canRecordForMeeting(ctx.committeeId)
        val base =
            ConferenceMeetingStateDto(
                meetingId = ctx.meetingId.toString(),
                meetingTitle = meeting[MeetingTable.title],
                committeeId = ctx.committeeId.toString(),
                phase = phase,
                openedAt = meeting[MeetingTable.openedAt],
                callerRole = ctx.role,
                ruleKind = rule.kind,
                establishedQuorum = established,
                agenda = agenda,
                currentAgendaItemId = currentItem,
                agendaMotions = MeetingSessionMotionReads.agendaMotions(ctx = ctx, agenda = agenda),
                runningMotionVote = MeetingSessionMotionReads.runningVote(ctx = ctx, current = current),
                canManageMotions = manage,
                attachableMotions = if (manage && currentItem != null) MeetingSessionMotionReads.attachable(ctx) else emptyList(),
            )
        if (ctx.role == MeetingSessionRole.PARTICIPANT) return base

        val liveQuorum =
            computeQuorum(meetingId = ctx.meetingId, committeeId = ctx.committeeId, scheduledDate = meeting[MeetingTable.scheduledAt].date)
        val roster =
            rule.roster(
                meetingId = ctx.meetingId,
                committeeRow = ctx.committeeRow,
                scheduledDate = meeting[MeetingTable.scheduledAt].date,
            )
        val rosterIds = roster.map { it.memberId }.toSet()
        val attendance =
            AttendanceTable
                .selectAll()
                .where {
                    AttendanceTable.meetingId eq ctx.meetingId
                }.associateBy { it[AttendanceTable.memberId] }
        val connectedIds = connected.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }.toSet()
        val guestIds = attendance.filter { (id, row) -> row[AttendanceTable.status] == AttendanceStatus.GUEST && id !in rosterIds }.keys
        val namesNeeded = guestIds + attendance.values.mapNotNull { it[AttendanceTable.representedByMemberId] }
        val names = displayNames(namesNeeded)

        fun entry(
            memberId: Uuid,
            displayName: String,
        ): SessionRosterEntryDto {
            val row = attendance[memberId]
            val standIn = row?.get(AttendanceTable.representedByMemberId)
            return SessionRosterEntryDto(
                memberId = memberId.toString(),
                displayName = displayName,
                status = row?.get(AttendanceTable.status),
                representedByMemberId = standIn?.toString(),
                representedByDisplayName = standIn?.let { id -> roster.firstOrNull { it.memberId == id }?.displayName ?: names[id] },
                connected = memberId in connectedIds,
            )
        }

        val rosterEntries = roster.map { entry(it.memberId, it.displayName) }
        val guests = guestIds.map { entry(it, names[it].orEmpty()) }.sortedWith(compareBy({ it.displayName.lowercase() }, { it.memberId }))
        val adoptable = roster.count { it.memberId in connectedIds && it.memberId !in attendance }
        val extra = connectedIds.count { it !in rosterIds && it !in guestIds }
        val hint =
            if (ctx.role != MeetingSessionRole.LEAD) {
                null
            } else {
                nextStep(
                    phase = phase,
                    adoptable = adoptable,
                    established = established,
                    agenda = agenda,
                    currentItem = currentItem,
                    ruleKind = rule.kind,
                )
            }
        return base.copy(
            liveQuorum = liveQuorum,
            nextStepHint = hint,
            roster = rosterEntries,
            guests = guests,
            adoptableCount = adoptable,
            extraConnectedCount = extra,
        )
    }

    private fun nextStep(
        phase: MeetingSessionPhase,
        adoptable: Int,
        established: EstablishedQuorumDto?,
        agenda: List<SessionAgendaItemDto>,
        currentItem: String?,
        ruleKind: AttendanceRuleKind,
    ): MeetingSessionNextStep =
        when (phase) {
            MeetingSessionPhase.NOT_OPENED -> MeetingSessionNextStep.OPEN_MEETING
            MeetingSessionPhase.CLOSED -> MeetingSessionNextStep.NONE
            MeetingSessionPhase.OPEN ->
                when {
                    established == null && adoptable > 0 && ruleKind == AttendanceRuleKind.NAME_LIST ->
                        MeetingSessionNextStep.ADOPT_CONNECTED
                    established == null -> MeetingSessionNextStep.ESTABLISH_QUORUM
                    agenda.isNotEmpty() &&
                        agenda.indexOfFirst {
                            it.id == currentItem
                        } < agenda.lastIndex -> MeetingSessionNextStep.NEXT_AGENDA_ITEM
                    else -> MeetingSessionNextStep.NONE
                }
        }

    private fun displayNames(ids: Set<Uuid>): Map<Uuid, String> {
        if (ids.isEmpty()) return emptyMap()
        return ids
            .chunked(500)
            .flatMap { chunk ->
                MemberTable.selectAll().where { MemberTable.id inList chunk }.map {
                    it[MemberTable.id] to
                        it[MemberTable.displayName]
                }
            }.toMap()
    }
}
