package network.lapis.cloud.server.conference.roompoll

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.ConferenceRoomPollAnswerTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollOptionTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollVotedTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.rpc.OptionTally
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.RoomPollAuditSnapshot
import network.lapis.cloud.shared.domain.RoomPollCloseReason
import network.lapis.cloud.shared.domain.RoomPollResultDto
import network.lapis.cloud.shared.domain.RoomPollRules
import network.lapis.cloud.shared.domain.RoomPollStatus
import network.lapis.cloud.shared.domain.RoomPollVoterGroup
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** The stored form of a public result: only the already suppressed DTO (whole percent shares, no weight sums, no hidden group sizes). */
internal object RoomPollResultJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(result: RoomPollResultDto): String = json.encodeToString(RoomPollResultDto.serializer(), result)

    fun decode(raw: String?): RoomPollResultDto? = raw?.let { json.decodeFromString(RoomPollResultDto.serializer(), it) }
}

/**
 * Lifecycle of a room poll. The database status is canonical: a poll whose room ended or whose 240 minutes are over is closed lazily the next
 * time anybody touches the room (and eagerly by `ConferenceRoomCloser`), by [materializeIfDue]. Every method here must run INSIDE the caller's
 * open `transaction {}` and follows the lock order room row -> poll row -> audit chain.
 */
internal object RoomPollLifecycle {
    /** `null` = the poll is not due; else why it must end. The room ending wins over the time limit. */
    fun dueReason(
        startedAt: LocalDateTime,
        roomEndedAt: LocalDateTime?,
        now: LocalDateTime,
    ): RoomPollCloseReason? {
        if (roomEndedAt != null) return RoomPollCloseReason.ROOM_ENDED
        val deadline =
            ServerClock
                .wallToInstant(wall = startedAt, orgZone = ServerClock.zone)
                .plus(RoomPollRules.MAX_OPEN_MINUTES.minutes)
        val nowInstant = ServerClock.wallToInstant(wall = now, orgZone = ServerClock.zone)
        return if (deadline <= nowInstant) RoomPollCloseReason.TIMEOUT else null
    }

    fun openPoll(roomId: Uuid): ResultRow? =
        ConferenceRoomPollTable.selectAll().where { ConferenceRoomPollTable.openRoomId eq roomId }.singleOrNull()

    /** The last poll that ended, newest first. */
    fun latestClosedPoll(roomId: Uuid): ResultRow? =
        ConferenceRoomPollTable
            .selectAll()
            .where {
                (ConferenceRoomPollTable.roomId eq roomId) and (ConferenceRoomPollTable.status eq RoomPollStatus.CLOSED)
            }.orderBy(ConferenceRoomPollTable.closedAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()

    fun lockRoom(roomId: Uuid): ResultRow? =
        ConferenceRoomTable
            .selectAll()
            .where { ConferenceRoomTable.id eq roomId }
            .forUpdate()
            .singleOrNull()

    /**
     * Closes the open poll of [roomId] if it is due. Does the cheap lock-free check first; only when something is due it takes the room and
     * the poll lock and re-checks. Returns the closed poll's id when it closed one.
     */
    fun materializeIfDue(
        roomId: Uuid,
        now: LocalDateTime,
    ): Uuid? = materializeIfDueDeferringAudit(roomId = roomId, now = now)?.also { it.recordAudit() }?.pollId

    /** A poll closed without its audit entry yet: [recordAudit] appends it (takes the global audit-chain lock). */
    class ClosedPoll internal constructor(
        val pollId: Uuid,
        private val audit: () -> Unit,
    ) {
        fun recordAudit() = audit()
    }

    /**
     * Like [materializeIfDue], but the audit entry is NOT written: the caller does [ClosedPoll.recordAudit] as the LAST lock-taking step of its
     * transaction (the audit-chain lock must come after every other row lock, see [AuditLogRecorder]). The room row must already be locked or
     * the caller must accept that this call locks it.
     */
    fun materializeIfDueDeferringAudit(
        roomId: Uuid,
        now: LocalDateTime,
    ): ClosedPoll? {
        val peek = openPoll(roomId) ?: return null
        val roomPeek = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.singleOrNull() ?: return null
        if (dueReason(
                startedAt = peek[ConferenceRoomPollTable.startedAt],
                roomEndedAt = roomPeek[ConferenceRoomTable.endedAt],
                now = now,
            ) ==
            null
        ) {
            return null
        }
        val room = lockRoom(roomId) ?: return null
        val poll =
            ConferenceRoomPollTable
                .selectAll()
                .where { ConferenceRoomPollTable.id eq peek[ConferenceRoomPollTable.id] }
                .forUpdate()
                .single()
        if (poll[ConferenceRoomPollTable.status] != RoomPollStatus.OPEN) return null
        val reason =
            dueReason(startedAt = poll[ConferenceRoomPollTable.startedAt], roomEndedAt = room[ConferenceRoomTable.endedAt], now = now)
                ?: return null
        return closeDeferringAudit(poll = poll, reason = reason, now = now, actor = null)
    }

    /** Ends an OPEN [poll] (the row is already locked): final result, markers deleted, audit. [actor] `null` = the system. */
    fun close(
        poll: ResultRow,
        reason: RoomPollCloseReason,
        now: LocalDateTime,
        actor: CurrentMember?,
    ) {
        closeDeferringAudit(poll = poll, reason = reason, now = now, actor = actor).recordAudit()
    }

    private fun closeDeferringAudit(
        poll: ResultRow,
        reason: RoomPollCloseReason,
        now: LocalDateTime,
        actor: CurrentMember?,
    ): ClosedPoll {
        val pollId = poll[ConferenceRoomPollTable.id]
        val result = computeResult(poll)
        ConferenceRoomPollTable.update({ ConferenceRoomPollTable.id eq pollId }) {
            it[status] = RoomPollStatus.CLOSED
            it[openRoomId] = null
            it[closedAt] = now
            it[closeReason] = reason
            it[resultJson] = RoomPollResultJson.encode(result)
            it[stateVersion] = poll[ConferenceRoomPollTable.stateVersion] + 1
        }
        // The markers of who answered have done their job (no double answer is possible any more): data minimisation.
        ConferenceRoomPollVotedTable.deleteWhere { ConferenceRoomPollVotedTable.pollId eq pollId }
        val optionCount =
            ConferenceRoomPollOptionTable
                .selectAll()
                .where { ConferenceRoomPollOptionTable.pollId eq pollId }
                .count()
                .toInt()
        return ClosedPoll(pollId = pollId) {
            recordCloseAudit(poll = poll, pollId = pollId, optionCount = optionCount, reason = reason, now = now, actor = actor)
        }
    }

    private fun recordCloseAudit(
        poll: ResultRow,
        pollId: Uuid,
        optionCount: Int,
        reason: RoomPollCloseReason,
        now: LocalDateTime,
        actor: CurrentMember?,
    ) {
        AuditLogRecorder.record(
            actorMemberId = actor?.memberId,
            actorRole = actor?.role,
            entityType = AuditEntityType.CONFERENCE_ROOM_POLL,
            entityId = pollId,
            action = AuditAction.UPDATE,
            after =
                Json.encodeToString(
                    RoomPollAuditSnapshot.serializer(),
                    RoomPollAuditSnapshot(
                        roomId = poll[ConferenceRoomPollTable.roomId].toString(),
                        pollId = pollId.toString(),
                        countingMode = poll[ConferenceRoomPollTable.countingMode].name,
                        optionCount = optionCount,
                        closeReason = reason.name,
                    ),
                ),
            occurredAt = now,
        )
    }

    /** The suppressed result of a poll as of now (reads the answers table only; never the markers). */
    fun computeResult(poll: ResultRow): RoomPollResultDto {
        val pollId = poll[ConferenceRoomPollTable.id]
        val optionIds =
            ConferenceRoomPollOptionTable
                .selectAll()
                .where { ConferenceRoomPollOptionTable.pollId eq pollId }
                .orderBy(ConferenceRoomPollOptionTable.position, SortOrder.ASC)
                .map { it[ConferenceRoomPollOptionTable.id] }
        val answers =
            ConferenceRoomPollAnswerTable
                .selectAll()
                .where { ConferenceRoomPollAnswerTable.pollId eq pollId }
                .map {
                    Triple(
                        it[ConferenceRoomPollAnswerTable.optionId],
                        it[ConferenceRoomPollAnswerTable.voterGroup],
                        it[ConferenceRoomPollAnswerTable.weightLtr],
                    )
                }
        val perGroup =
            RoomPollVoterGroup.entries.associateWith { group ->
                optionIds.map { optionId ->
                    val mine = answers.filter { it.first == optionId && it.second == group }
                    OptionTally(
                        optionId = optionId,
                        responses = mine.size,
                        weightedResponses = mine.count { it.third.signum() > 0 },
                        weightSum = mine.fold(BigDecimal.ZERO) { acc, a -> acc + a.third },
                    )
                }
            }
        return computeRoomPollResult(perGroup = perGroup, mode = poll[ConferenceRoomPollTable.countingMode])
    }
}
