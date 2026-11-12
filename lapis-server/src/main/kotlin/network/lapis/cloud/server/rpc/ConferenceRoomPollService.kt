package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.roompoll.RoomPollAccess
import network.lapis.cloud.server.conference.roompoll.RoomPollAudience
import network.lapis.cloud.server.conference.roompoll.RoomPollLifecycle
import network.lapis.cloud.server.conference.roompoll.RoomPollNudge
import network.lapis.cloud.server.conference.roompoll.RoomPollResultJson
import network.lapis.cloud.server.conference.roompoll.RoomPollVersionToken
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceRoomPollAnswerTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollOptionTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollVotedTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.RoomPollAuditSnapshot
import network.lapis.cloud.shared.domain.RoomPollCloseReason
import network.lapis.cloud.shared.domain.RoomPollDto
import network.lapis.cloud.shared.domain.RoomPollOptionDto
import network.lapis.cloud.shared.domain.RoomPollRules
import network.lapis.cloud.shared.domain.RoomPollStartInput
import network.lapis.cloud.shared.domain.RoomPollStateDto
import network.lapis.cloud.shared.domain.RoomPollStatus
import network.lapis.cloud.shared.domain.RoomPollVersionDto
import network.lapis.cloud.shared.domain.RoomPollVoterGroup
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IConferenceRoomPollService
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * V1.9.304 -- see [IConferenceRoomPollService], `docs/features/room-polls.adoc` and `docs/architecture/room-polls.adoc`.
 *
 * This class and [RoomPollLifecycle] are the ONLY writers of the four `conference_room_poll*` tables. LiveKit is never called inside a
 * `transaction {}` (Exposed repeats the whole block on a `SQLException`, which would repeat the packet): every method decides inside one
 * transaction, commits and only then sends the nudge. A lazily closed poll is committed even when the call itself is refused afterwards,
 * so refusals after a close are returned as an outcome and thrown outside the transaction.
 *
 * Order of every method: member, feature switch, rate limit per account, id parsing (a malformed id is the same refusal as everything
 * else), the transaction. NO defaults on the limiters on purpose (Kilua builds this service per RPC call; they are process-wide singletons
 * wired in `Application.module`). Lock order: room row, then poll row, then the audit chain (last). The member row is never locked.
 *
 * Privacy rule of this file: no log line at all (nothing about a room poll may reach a log; `RoomPollLogPrivacyTripwireTest`).
 */
class ConferenceRoomPollService(
    private val call: ApplicationCall,
    private val liveKitAdminClient: LiveKitAdminClient,
    private val config: ConferenceConfig,
    private val ltrBalanceProvider: LtrBalanceProvider,
    private val writeLimiter: FederationInboxRateLimiter,
    private val answerLimiter: FederationInboxRateLimiter,
    private val stateLimiter: FederationInboxRateLimiter,
    private val versionLimiter: FederationInboxRateLimiter,
) : IConferenceRoomPollService {
    private sealed interface Outcome {
        data class Done(
            val state: RoomPollStateDto,
            val nudgeRoom: String?,
        ) : Outcome

        /** The caller is refused after a lazy close was committed. */
        data object AlreadyAnswered : Outcome

        data object NotOpen : Outcome

        data object Conflict : Outcome
    }

    override suspend fun startRoomPoll(input: RoomPollStartInput): RoomPollStateDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = writeLimiter, current = current)
        val roomId = parseId(input.roomId)
        val valid =
            try {
                RoomPollRules.validate(input)
            } catch (e: IllegalArgumentException) {
                throw BadRequestException("Invalid room poll")
            }
        val outcome =
            try {
                transaction {
                    val now = DbClock.nowLocalDateTime()
                    val roomRow = RoomPollLifecycle.lockRoom(roomId)
                    val audience = RoomPollAccess.audience(roomRow = roomRow, current = current)
                    if (roomRow == null ||
                        !RoomPollAccess.isLeadership(roomRow = roomRow, current = current) ||
                        audience != RoomPollAudience.PARTICIPANT
                    ) {
                        throw RoomPollAccess.denied()
                    }
                    RoomPollLifecycle.materializeIfDue(roomId = roomId, now = now)
                    if (RoomPollLifecycle.openPoll(roomId) != null) return@transaction Outcome.Conflict
                    val pollId = Uuid.random()
                    ConferenceRoomPollTable.insert {
                        it[id] = pollId
                        it[ConferenceRoomPollTable.roomId] = roomId
                        it[openRoomId] = roomId
                        it[question] = valid.question
                        it[countingMode] = valid.countingMode
                        it[status] = RoomPollStatus.OPEN
                        it[startedAt] = now
                        it[stateVersion] = 1L
                        it[snapshotAnswerCount] = 0
                    }
                    valid.options.forEachIndexed { index, text ->
                        ConferenceRoomPollOptionTable.insert {
                            it[id] = Uuid.random()
                            it[ConferenceRoomPollOptionTable.pollId] = pollId
                            it[position] = index
                            it[ConferenceRoomPollOptionTable.text] = text
                        }
                    }
                    AuditLogRecorder.record(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        entityType = AuditEntityType.CONFERENCE_ROOM_POLL,
                        entityId = pollId,
                        action = AuditAction.CREATE,
                        after =
                            Json.encodeToString(
                                RoomPollAuditSnapshot.serializer(),
                                RoomPollAuditSnapshot(
                                    roomId = roomId.toString(),
                                    pollId = pollId.toString(),
                                    countingMode = valid.countingMode.name,
                                    optionCount = valid.options.size,
                                ),
                            ),
                        occurredAt = now,
                    )
                    Outcome.Done(
                        state = buildState(roomRow = roomRow, current = current, now = now),
                        nudgeRoom = roomRow[ConferenceRoomTable.livekitRoomName],
                    )
                }
            } catch (e: ExposedSQLException) {
                if (e.isUniqueViolation()) Outcome.Conflict else throw e
            }
        return finish(outcome = outcome, conflictMessage = "A poll is already running")
    }

    override suspend fun closeRoomPoll(
        roomId: String,
        pollId: String,
    ): RoomPollStateDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = writeLimiter, current = current)
        val room = parseId(roomId)
        val poll = parseId(pollId)
        val outcome =
            transaction {
                val now = DbClock.nowLocalDateTime()
                val roomRow = RoomPollLifecycle.lockRoom(room)
                RoomPollAccess.audience(roomRow = roomRow, current = current, allowEnded = true, requirePresence = false)
                if (roomRow == null || !RoomPollAccess.isLeadership(roomRow = roomRow, current = current)) throw RoomPollAccess.denied()
                val pollRow =
                    ConferenceRoomPollTable
                        .selectAll()
                        .where { (ConferenceRoomPollTable.id eq poll) and (ConferenceRoomPollTable.roomId eq room) }
                        .forUpdate()
                        .singleOrNull() ?: throw RoomPollAccess.denied()
                var nudge: String? = null
                if (pollRow[ConferenceRoomPollTable.status] == RoomPollStatus.OPEN) {
                    val reason =
                        RoomPollLifecycle.dueReason(
                            startedAt = pollRow[ConferenceRoomPollTable.startedAt],
                            roomEndedAt = roomRow[ConferenceRoomTable.endedAt],
                            now = now,
                        )
                    RoomPollLifecycle.close(poll = pollRow, reason = reason ?: RoomPollCloseReason.MANUAL, now = now, actor = current)
                    if (reason == null) nudge = roomRow[ConferenceRoomTable.livekitRoomName]
                }
                Outcome.Done(state = buildState(roomRow = roomRow, current = current, now = now), nudgeRoom = nudge)
            }
        return finish(outcome = outcome, conflictMessage = "Not possible")
    }

    override suspend fun answerRoomPoll(
        roomId: String,
        pollId: String,
        optionId: String,
    ): RoomPollStateDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = answerLimiter, current = current)
        val room = parseId(roomId)
        val poll = parseId(pollId)
        val option = parseId(optionId)
        val outcome =
            try {
                transaction {
                    val now = DbClock.nowLocalDateTime()
                    val roomRow = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room }.singleOrNull()
                    // The live status comes first (inside the entry check), before any poll query.
                    val audience = RoomPollAccess.audience(roomRow = roomRow, current = current)
                    val liveStatus = liveStatus(current.memberId)
                    RoomPollAccess.requireResponderStatus(liveStatus)
                    if (roomRow == null || audience == RoomPollAudience.ENDED_READER) throw RoomPollAccess.denied()
                    RoomPollLifecycle.materializeIfDue(roomId = room, now = now)
                    val pollRow =
                        ConferenceRoomPollTable
                            .selectAll()
                            .where { (ConferenceRoomPollTable.id eq poll) and (ConferenceRoomPollTable.roomId eq room) }
                            .forUpdate()
                            .singleOrNull() ?: throw RoomPollAccess.denied()
                    if (pollRow[ConferenceRoomPollTable.status] != RoomPollStatus.OPEN) return@transaction Outcome.NotOpen
                    val optionRow =
                        ConferenceRoomPollOptionTable
                            .selectAll()
                            .where { (ConferenceRoomPollOptionTable.id eq option) and (ConferenceRoomPollOptionTable.pollId eq poll) }
                            .singleOrNull() ?: throw BadRequestException("Unknown option")
                    val alreadyVoted =
                        ConferenceRoomPollVotedTable
                            .selectAll()
                            .where {
                                (ConferenceRoomPollVotedTable.pollId eq poll) and
                                    (ConferenceRoomPollVotedTable.memberId eq current.memberId)
                            }.limit(1)
                            .any()
                    if (alreadyVoted) return@transaction Outcome.AlreadyAnswered
                    val group =
                        if (liveStatus in
                            MemberStatusSets.ROOM_POLL_MEMBERS
                        ) {
                            RoomPollVoterGroup.MEMBERS
                        } else {
                            RoomPollVoterGroup.GUESTS_AND_FRIENDS
                        }
                    val weight =
                        if (group == RoomPollVoterGroup.MEMBERS) {
                            ltrBalanceProvider
                                .balanceAsOf(memberId = current.memberId, asOf = pollRow[ConferenceRoomPollTable.startedAt])
                                .max(BigDecimal.ZERO)
                                .setScale(2, RoundingMode.DOWN)
                        } else {
                            BigDecimal.ZERO.setScale(2)
                        }
                    ConferenceRoomPollVotedTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceRoomPollVotedTable.pollId] = poll
                        it[memberId] = current.memberId
                    }
                    ConferenceRoomPollAnswerTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceRoomPollAnswerTable.pollId] = poll
                        it[ConferenceRoomPollAnswerTable.optionId] = optionRow[ConferenceRoomPollOptionTable.id]
                        it[voterGroup] = group
                        it[weightLtr] = weight
                    }
                    // No interim result is taken any more: a snapshot next to the final result lets a moderator who ends the poll right
                    // after the next answer reconstruct that one person's vote (see docs/features/room-polls.adoc).
                    Outcome.Done(state = buildState(roomRow = roomRow, current = current, now = now, voted = true), nudgeRoom = null)
                }
            } catch (e: ExposedSQLException) {
                if (e.isUniqueViolation()) Outcome.AlreadyAnswered else throw e
            }
        return finish(outcome = outcome, conflictMessage = "Already answered")
    }

    override suspend fun getRoomPollState(roomId: String): RoomPollStateDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = stateLimiter, current = current)
        val room = parseId(roomId)
        val outcome =
            transaction {
                val now = DbClock.nowLocalDateTime()
                val roomRow = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room }.singleOrNull()
                RoomPollAccess.audience(roomRow = roomRow, current = current, allowEnded = true)
                RoomPollLifecycle.materializeIfDue(roomId = room, now = now)
                Outcome.Done(state = buildState(roomRow = roomRow!!, current = current, now = now), nudgeRoom = null)
            }
        return finish(outcome = outcome, conflictMessage = "Not possible")
    }

    override suspend fun getRoomPollVersion(
        roomId: String,
        knownVersion: String?,
    ): RoomPollVersionDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = versionLimiter, current = current)
        val room = parseId(roomId)
        val token =
            transaction {
                val now = DbClock.nowLocalDateTime()
                val roomRow = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room }.singleOrNull()
                RoomPollAccess.audience(roomRow = roomRow, current = current, allowEnded = true)
                RoomPollLifecycle.materializeIfDue(roomId = room, now = now)
                val leadership = RoomPollAccess.isLeadership(roomRow = roomRow!!, current = current)
                val poll = visiblePoll(roomId = room, now = now)
                RoomPollVersionToken.token(
                    pollId = poll?.get(ConferenceRoomPollTable.id),
                    status = poll?.get(ConferenceRoomPollTable.status),
                    stateVersion = poll?.get(ConferenceRoomPollTable.stateVersion) ?: 0L,
                    leadership = leadership,
                )
            }
        return if (token == knownVersion) RoomPollVersionDto(changed = false) else RoomPollVersionDto(changed = true, version = token)
    }

    // -- Helpers ------------------------------------------------------------------------------------------------------------------------

    private suspend fun finish(
        outcome: Outcome,
        conflictMessage: String,
    ): RoomPollStateDto =
        when (outcome) {
            is Outcome.Done -> {
                outcome.nudgeRoom?.let { RoomPollNudge.send(liveKit = liveKitAdminClient, livekitRoomName = it) }
                outcome.state
            }
            Outcome.AlreadyAnswered -> throw ConflictException("Already answered")
            Outcome.NotOpen -> throw ConflictException("The poll is not open")
            Outcome.Conflict -> throw ConflictException(conflictMessage)
        }

    /** The poll the room shows now: the open one, else the last one that ended within [RoomPollRules.RESULT_VISIBLE_HOURS]. */
    private fun visiblePoll(
        roomId: Uuid,
        now: LocalDateTime,
    ): ResultRow? {
        RoomPollLifecycle.openPoll(roomId)?.let { return it }
        val closed = RoomPollLifecycle.latestClosedPoll(roomId) ?: return null
        val closedAt = closed[ConferenceRoomPollTable.closedAt] ?: return null
        val limit = ServerClock.wallToInstant(wall = closedAt, orgZone = ServerClock.zone) + RoomPollRules.RESULT_VISIBLE_HOURS.hours
        return if (limit > ServerClock.wallToInstant(wall = now, orgZone = ServerClock.zone)) closed else null
    }

    private fun buildState(
        roomRow: ResultRow,
        current: CurrentMember,
        now: LocalDateTime,
        voted: Boolean? = null,
    ): RoomPollStateDto {
        val roomId = roomRow[ConferenceRoomTable.id]
        val leadership = RoomPollAccess.isLeadership(roomRow = roomRow, current = current)
        val pollRow = visiblePoll(roomId = roomId, now = now)
        val token =
            RoomPollVersionToken.token(
                pollId = pollRow?.get(ConferenceRoomPollTable.id),
                status = pollRow?.get(ConferenceRoomPollTable.status),
                stateVersion = pollRow?.get(ConferenceRoomPollTable.stateVersion) ?: 0L,
                leadership = leadership,
            )
        if (pollRow == null) return RoomPollStateDto(roomId = roomId.toString(), isLeadership = leadership, version = token)
        val pollId = pollRow[ConferenceRoomPollTable.id]
        val open = pollRow[ConferenceRoomPollTable.status] == RoomPollStatus.OPEN
        val options =
            ConferenceRoomPollOptionTable
                .selectAll()
                .where { ConferenceRoomPollOptionTable.pollId eq pollId }
                .orderBy(ConferenceRoomPollOptionTable.position, SortOrder.ASC)
                .map {
                    RoomPollOptionDto(
                        id = it[ConferenceRoomPollOptionTable.id].toString(),
                        position = it[ConferenceRoomPollOptionTable.position],
                        text = it[ConferenceRoomPollOptionTable.text],
                    )
                }
        val hasVoted =
            voted ?: (
                open &&
                    ConferenceRoomPollVotedTable
                        .selectAll()
                        .where {
                            (ConferenceRoomPollVotedTable.pollId eq pollId) and
                                (ConferenceRoomPollVotedTable.memberId eq current.memberId)
                        }.limit(1)
                        .any()
            )
        val ended = roomRow[ConferenceRoomTable.endedAt] != null
        return RoomPollStateDto(
            roomId = roomId.toString(),
            poll =
                RoomPollDto(
                    id = pollId.toString(),
                    question = pollRow[ConferenceRoomPollTable.question],
                    options = options,
                    countingMode = pollRow[ConferenceRoomPollTable.countingMode],
                    status = pollRow[ConferenceRoomPollTable.status],
                    closeReason = pollRow[ConferenceRoomPollTable.closeReason],
                ),
            voted = hasVoted,
            canAnswer = open && !hasVoted && !ended,
            isLeadership = leadership,
            answerCount =
                if (open && leadership) {
                    ConferenceRoomPollAnswerTable
                        .selectAll()
                        .where { ConferenceRoomPollAnswerTable.pollId eq pollId }
                        .count()
                        .toInt()
                } else {
                    null
                },
            result = if (open) null else RoomPollResultJson.decode(pollRow[ConferenceRoomPollTable.resultJson]),
            version = token,
        )
    }

    /**
     * The live status of the member. An ERASED (anonymized) member is refused like a stranger: erasure keeps the status, and the erasure
     * deletes the "has answered" markers of running polls, so without this check such a person could answer a running poll a second time.
     */
    private fun liveStatus(memberId: Uuid): MemberStatus {
        val row = MemberTable.selectAll().where { MemberTable.id eq memberId }.single()
        if (row[MemberTable.anonymizedAt] != null) throw RoomPollAccess.denied()
        return row[MemberTable.status]
    }

    private fun parseId(raw: String): Uuid = runCatching { Uuid.parse(raw) }.getOrElse { throw RoomPollAccess.denied() }

    private fun requireConferenceEnabled() {
        if (!config.enabled) {
            throw ConflictException("Videokonferenzen is not configured on this server (LAPIS_LIVEKIT_URL/_API_KEY/_API_SECRET unset)")
        }
    }

    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        current: CurrentMember,
    ) {
        if (!limiter.checkAndRecord("member:${current.memberId}")) throw ConflictException("Too many requests -- try again later")
    }
}
