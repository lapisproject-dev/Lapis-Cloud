package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.LiveKitAccessToken
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.TurnCredentialMinter
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceBreakoutAssignmentTable
import network.lapis.cloud.server.db.generated.ConferenceBreakoutRoomTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.ConferenceBreakoutAssignmentDto
import network.lapis.cloud.shared.domain.ConferenceBreakoutAssignmentInput
import network.lapis.cloud.shared.domain.ConferenceBreakoutPlanInput
import network.lapis.cloud.shared.domain.ConferenceBreakoutRoomDto
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceTurnServer
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IConferenceBreakoutService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Kleinsitzung-scale cap, mirrors [ConferenceConfig.maxParticipants]'s own order of magnitude -- see [IConferenceBreakoutService.createBreakoutRooms] KDoc. */
private const val MAX_BREAKOUT_ROOMS = 20

/** Same limit [ConferenceRoomTable.title] applies, reused for a breakout room's own [ConferenceBreakoutRoomTable.label]. */
private const val MAX_LABEL_LENGTH = 120

/**
 * Security-audit fix (resource-exhaustion finding) -- mirrors [MAX_BREAKOUT_ROOMS]'s
 * "Kleinsitzung-scale cap" reasoning, bounding [ConferenceBreakoutService.createBreakoutRooms]'s
 * `plan.manualAssignments` array BEFORE either validation `forEach` below walks it, so an
 * arbitrarily large client-supplied array cannot burn CPU/parsing time proportional to its length
 * before the first entry is even validated. Deliberately independent of [ConferenceConfig
 * .maxParticipants] (an operator-configurable value) -- this is a request-SHAPE bound, not a
 * room-size bound, and must not silently rise just because an operator raises the room-size config.
 */
private const val MAX_MANUAL_ASSIGNMENTS = 100

/**
 * Security-audit fix (resource-exhaustion finding) -- mirrors [MAX_BREAKOUT_ROOMS]/
 * [MAX_MANUAL_ASSIGNMENTS]'s reasoning, bounding [ConferenceBreakoutService.assignParticipants]'s
 * `assignments` array. Unlike `createBreakoutRooms`, this call's per-entry cost is NOT collapsed
 * into a `Map` before touching the DB -- each entry performs its own SELECT+UPDATE+INSERT inside
 * one transaction plus, outside it, one outbound `RemoveParticipant` Twirp call -- so this bound is
 * load-bearing on its own, not merely a CPU-time nicety like [MAX_MANUAL_ASSIGNMENTS]. See
 * [ConferenceBreakoutService.assignParticipants] for the accompanying de-duplication-by-memberId
 * fix that closes the same finding from the other direction (duplicate entries, not just length).
 */
private const val MAX_ASSIGNMENTS_PER_CALL = 100

/** Wave-1 "Kleinsitzung" default, reused verbatim -- see [ConferenceService]'s own `ROOM_EMPTY_TIMEOUT_SECONDS` KDoc. */
private const val ROOM_EMPTY_TIMEOUT_SECONDS = 300

private val DEFAULT_ACTION_RATE_WINDOW = 1.minutes

/** Moderator action, fans out to up to [MAX_BREAKOUT_ROOMS] outbound `CreateRoom` calls -- see [ConferenceService.DEFAULT_GUEST_ACCESS_RATE_MAX] KDoc for the analogous "budget matches per-call fan-out cost" reasoning. */
private const val DEFAULT_CREATE_RATE_MAX = 10
private const val DEFAULT_ASSIGN_RATE_MAX = 20

/** Fans out to up to [MAX_BREAKOUT_ROOMS] outbound `DeleteRoom` calls. */
private const val DEFAULT_RECALL_RATE_MAX = 10

/** Shared budget: [ConferenceBreakoutService.requestBreakoutJoinToken]/[ConferenceBreakoutService.rejoinMainRoomToken]/[ConferenceBreakoutService.getMyBreakoutAssignment]/[ConferenceBreakoutService.returnToMainRoom] -- ordinary participant actions, none of them fanning out to more than one outbound LiveKit call, mirrors [ConferenceService]'s own `joinRoomRateLimiter`/`leaveRoomRateLimiter` reuse reasoning. */
private const val DEFAULT_TOKEN_RATE_MAX = 30

/** Result of [ConferenceBreakoutService.createBreakoutRooms]'s Phase-1 `transaction {}`, consumed outside any transaction -- see [ConferenceService]'s own `JoinPrep`/`RevokePlan` for the same "collect inside the transaction, act on the network outside it" shape. */
private data class ParentRoomPrep(
    val livekitRoomName: String,
    val moderatorId: Uuid,
)

/** One member's relocation for [ConferenceBreakoutService.assignParticipants]'s force-disconnect phase, outside any transaction. */
private data class Relocation(
    val memberId: Uuid,
    val fromLivekitRoomName: String,
    val toLivekitRoomName: String,
)

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 6 "Breakout-Räume" -- see
 * [IConferenceBreakoutService] KDoc for the full authorization matrix and design decisions (separate
 * service, no independent availability gate, moderator never auto-assigned, no breakout-room
 * moderator, `conference_participation` stays open across a breakout excursion, guests allowed on
 * identical terms, no new `AuditEntityType`) and `31-conference-breakout.kuml.kts` file header for
 * the persistence model.
 *
 * ## Authorization re-derivation, never a cached role
 *
 * [requireModeratorOrPrivileged] is deliberately DUPLICATED here (not extracted into a shared
 * helper) rather than widening [ConferenceService]'s already-live-verified Wave 1 constructor for a
 * two-line check -- same reasoning [ConferenceRecordingService] KDoc "Authorization re-derivation"
 * already gives for its own identical duplication.
 *
 * ## Why not a LiveKit data-channel push for the assignment signal
 *
 * Considered and rejected in favor of the disconnect-then-resolve mechanism the client implements
 * (`network.lapis.cloud.client.ConferenceScreen`'s `resolvePostDisconnectDestination`), for three
 * reasons: (1) [network.lapis.cloud.server.conference.LiveKitRoomInfo]'s data-channel wrapper has no
 * per-participant targeting today, so a broadcast-to-everyone "you've been assigned" message would
 * need every OTHER client to filter it out by identity, and a malicious participant could forge one
 * (low real risk since [requestBreakoutJoinToken]'s own authorization query re-verifies regardless,
 * but an unnecessary new trust surface for zero gain). (2) The chosen mechanism (server
 * force-disconnects via [LiveKitAdminClient.removeParticipant]/[LiveKitAdminClient.deleteRoom],
 * client resolves via RPC) is REAL-TIME already -- no polling latency -- because LiveKit's own
 * `RoomEvent.Disconnected` fires the instant the server acts, so there is no actual responsiveness
 * advantage to a data-channel push. (3) It reuses 100% pre-existing infrastructure rather than
 * adding a second control-plane topic alongside `lapis-chat`, keeping this wave's client diff
 * smaller and avoiding a second bespoke wire format to secure and test.
 *
 * **Consequence this creates, that this class implements**: [createBreakoutRooms]/
 * [assignParticipants], after committing their DB rows, actively force-disconnect every
 * newly-/re-assigned member from the room they were PREVIOUSLY in via
 * [LiveKitAdminClient.removeParticipant] -- this is what makes the "no push" design actually deliver
 * near-real-time relocation instead of relying on the participant eventually reconnecting on their
 * own. **Never [ConferenceService.removeParticipant]** for this -- that method ALSO closes the
 * target's `conference_participation` row (moderator-kicking semantics), which is wrong here (see
 * [IConferenceBreakoutService] KDoc "`conference_participation` stays open across a breakout
 * excursion"). Calls [LiveKitAdminClient.removeParticipant] directly, outside any transaction,
 * best-effort (`runCatching`, log-and-continue per member -- one participant's LiveKit hiccup must
 * never abort assigning the others).
 */
class ConferenceBreakoutService(
    private val call: ApplicationCall,
    private val liveKitAdminClient: LiveKitAdminClient,
    private val config: ConferenceConfig = ConferenceConfig.load(),
    private val createRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_CREATE_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    private val assignRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_ASSIGN_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    private val recallRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_RECALL_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    private val tokenRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_TOKEN_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
) : IConferenceBreakoutService {
    override suspend fun createBreakoutRooms(
        roomId: String,
        plan: ConferenceBreakoutPlanInput,
    ): List<ConferenceBreakoutRoomDto> {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = createRateLimiter, memberId = current.memberId)

        if (plan.roomCount !in 1..MAX_BREAKOUT_ROOMS) {
            throw BadRequestException("roomCount must be between 1 and $MAX_BREAKOUT_ROOMS")
        }
        if (plan.roomLabels.isNotEmpty() && plan.roomLabels.size != plan.roomCount) {
            throw BadRequestException("roomLabels must be empty or have exactly roomCount (${plan.roomCount}) entries")
        }
        val normalizedLabels =
            if (plan.roomLabels.isEmpty()) {
                (1..plan.roomCount).map { "Breakout-Raum $it" }
            } else {
                plan.roomLabels.map { label ->
                    val trimmed = label.trim()
                    if (trimmed.isBlank()) throw BadRequestException("roomLabels entries must not be blank")
                    if (trimmed.length > MAX_LABEL_LENGTH) {
                        throw BadRequestException("roomLabels entries must be at most $MAX_LABEL_LENGTH characters")
                    }
                    trimmed
                }
            }
        if (plan.manualAssignments.size > MAX_MANUAL_ASSIGNMENTS) {
            throw BadRequestException("manualAssignments must have at most $MAX_MANUAL_ASSIGNMENTS entries")
        }
        plan.manualAssignments.forEach { assignment ->
            if (assignment.breakoutIndex !in 0 until plan.roomCount) {
                throw BadRequestException(
                    "manualAssignments breakoutIndex ${assignment.breakoutIndex} out of range 0..<${plan.roomCount}",
                )
            }
        }

        val roomUuid = roomId.toBreakoutUuid()
        val parentRoom =
            transaction {
                val row =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomUuid }.singleOrNull()
                        ?: throw NotFoundException("Conference room $roomUuid not found")
                requireModeratorOrPrivileged(row = row, current = current)
                if (row[ConferenceRoomTable.endedAt] != null) {
                    throw ConflictException("Conference room $roomUuid has already ended")
                }
                val hasOpenBatch =
                    ConferenceBreakoutRoomTable
                        .selectAll()
                        .where { (ConferenceBreakoutRoomTable.parentRoomId eq roomUuid) and ConferenceBreakoutRoomTable.closedAt.isNull() }
                        .limit(1)
                        .any()
                if (hasOpenBatch) {
                    throw ConflictException("Room $roomUuid already has an open breakout batch -- recall it first")
                }
                ParentRoomPrep(
                    livekitRoomName = row[ConferenceRoomTable.livekitRoomName],
                    moderatorId = row[ConferenceRoomTable.createdByMemberId],
                )
            }

        // Outside any transaction (class KDoc, mirrors ConferenceService KDoc "Transaction
        // boundaries around the LiveKit network call") -- the live roster of the PARENT room, not
        // the potentially-stale conference_participation log.
        val liveParticipants = liveKitCall { liveKitAdminClient.listParticipants(parentRoom.livekitRoomName) }
        val liveMemberIds = liveParticipants.mapNotNull { it.identity.toUuidOrNull() }.toSet()

        plan.manualAssignments.forEach { assignment ->
            val memberId = assignment.memberId.toBreakoutUuid()
            if (memberId !in liveMemberIds) {
                throw BadRequestException("manualAssignments member ${assignment.memberId} is not currently live in room $roomUuid")
            }
        }

        // Compute the final assignment plan in memory -- manual pins first, then auto-distribute
        // the rest (minus the moderator, minus anyone already manually pinned) round-robin, sorted
        // by DISPLAY NAME (not raw member-id/identity) for a moderator-legible distribution --
        // design review fix: sorting by an invisible UUID would produce an outcome a moderator has
        // no way to make sense of.
        val manualByMember = plan.manualAssignments.associate { it.memberId.toBreakoutUuid() to it.breakoutIndex }
        val autoPoolIds = liveMemberIds - parentRoom.moderatorId - manualByMember.keys
        // memberDisplayName runs a query -- must happen INSIDE a transaction (class KDoc "Transaction
        // boundaries"), so the sort key is fetched in one batched read here rather than calling
        // memberDisplayName(it) from inside sortedBy{} itself (which runs outside any transaction and
        // would throw "No transaction in context").
        val displayNameByMember =
            transaction {
                MemberTable
                    .selectAll()
                    .where { MemberTable.id inList autoPoolIds.toList() }
                    .associate { it[MemberTable.id] to it[MemberTable.displayName] }
            }
        val autoPoolSorted = autoPoolIds.sortedBy { displayNameByMember[it] ?: "" }
        val autoByMember = autoPoolSorted.mapIndexed { index, memberId -> memberId to (index % plan.roomCount) }.toMap()
        val finalAssignments: Map<Uuid, Int> = manualByMember + autoByMember

        // Outside any transaction -- create the real LiveKit rooms. Best-effort cleanup of
        // already-created rooms on partial failure; never writes any DB row if any createRoom call
        // failed.
        val createdRoomNames = mutableListOf<String>()
        try {
            repeat(plan.roomCount) {
                val name = "lc-bo-${Uuid.random()}"
                liveKitCall {
                    liveKitAdminClient.createRoom(
                        name = name,
                        maxParticipants = config.maxParticipants,
                        emptyTimeoutSeconds = ROOM_EMPTY_TIMEOUT_SECONDS,
                    )
                }
                createdRoomNames += name
            }
        } catch (e: ConflictException) {
            createdRoomNames.forEach { name ->
                runCatching { liveKitCall { liveKitAdminClient.deleteRoom(name) } }
                    .onFailure {
                        logger.warn {
                            "createBreakoutRooms: failed to clean up partially-created breakout LiveKit room " +
                                "$name for parent $roomUuid"
                        }
                    }
            }
            throw e
        }

        val now = nowLocalDateTime()
        val dtos =
            transaction {
                val breakoutRoomIds = mutableListOf<Uuid>()
                createdRoomNames.forEachIndexed { index, name ->
                    val breakoutRoomId = Uuid.random()
                    breakoutRoomIds += breakoutRoomId
                    ConferenceBreakoutRoomTable.insert {
                        it[id] = breakoutRoomId
                        it[parentRoomId] = roomUuid
                        it[label] = normalizedLabels[index]
                        it[livekitRoomName] = name
                        it[createdByMemberId] = current.memberId
                        // Strictly increasing per room, NOT the same `now` for every room in the
                        // batch -- assignParticipants/getMyBreakoutAssignment/recallAll all order
                        // a batch's rooms by createdAt to give a `breakoutIndex` a stable meaning
                        // across separate RPC calls (see IConferenceBreakoutService KDoc). A shared
                        // timestamp for every room would make that ordering depend on the
                        // secondary, semantically meaningless `id` (random UUID) tiebreak instead.
                        it[createdAt] =
                            now
                                .toInstant(TimeZone.currentSystemDefault())
                                .plus(index.milliseconds)
                                .toLocalDateTime(TimeZone.currentSystemDefault())
                        it[closedAt] = null
                    }
                }
                finalAssignments.forEach { (memberId, index) ->
                    ConferenceBreakoutAssignmentTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceBreakoutAssignmentTable.breakoutRoomId] = breakoutRoomIds[index]
                        it[ConferenceBreakoutAssignmentTable.memberId] = memberId
                        it[assignedAt] = now
                        it[recalledAt] = null
                    }
                }
                breakoutRoomIds.map { id ->
                    rowToBreakoutDto(ConferenceBreakoutRoomTable.selectAll().where { ConferenceBreakoutRoomTable.id eq id }.single())
                }
            }

        // See class KDoc "Consequence this creates, that this class implements".
        finalAssignments.keys.forEach { memberId ->
            runCatching {
                liveKitCall {
                    liveKitAdminClient.removeParticipant(
                        room = parentRoom.livekitRoomName,
                        identity = memberId.toString(),
                    )
                }
            }.onFailure {
                logger.warn { "createBreakoutRooms: failed to disconnect member $memberId from parent room $roomUuid" }
            }
        }

        return dtos
    }

    override suspend fun assignParticipants(
        roomId: String,
        assignments: List<ConferenceBreakoutAssignmentInput>,
    ): List<ConferenceBreakoutRoomDto> {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = assignRateLimiter, memberId = current.memberId)
        val roomUuid = roomId.toBreakoutUuid()

        if (assignments.size > MAX_ASSIGNMENTS_PER_CALL) {
            throw BadRequestException("assignments must have at most $MAX_ASSIGNMENTS_PER_CALL entries")
        }
        // Security-audit fix (resource-exhaustion finding) -- de-duplicate by memberId, LAST entry
        // wins, mirroring createBreakoutRooms' own manualByMember `Map` collapsing duplicate
        // memberIds before any DB write. Without this, a client-supplied array with the same
        // memberId repeated many times would still perform one SELECT+UPDATE+INSERT triplet AND
        // one outbound `RemoveParticipant` Twirp call per (non-deduplicated) entry below, even
        // though only the final entry for a given member has any lasting effect -- unbounded
        // append-only row growth plus an unbounded outbound-call burst, all inside a single held DB
        // transaction, from a request the length cap above alone would not fully close (the cap
        // bounds the array's length, not how many times the SAME memberId may repeat within it).
        val dedupedAssignments = assignments.associateBy { it.memberId }.values.toList()

        val relocations =
            transaction {
                val parentRow =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomUuid }.singleOrNull()
                        ?: throw NotFoundException("Conference room $roomUuid not found")
                requireModeratorOrPrivileged(row = parentRow, current = current)

                val openRooms =
                    ConferenceBreakoutRoomTable
                        .selectAll()
                        .where { (ConferenceBreakoutRoomTable.parentRoomId eq roomUuid) and ConferenceBreakoutRoomTable.closedAt.isNull() }
                        .orderBy(ConferenceBreakoutRoomTable.createdAt to SortOrder.ASC, ConferenceBreakoutRoomTable.id to SortOrder.ASC)
                        .toList()
                if (openRooms.isEmpty()) {
                    throw ConflictException("Room $roomUuid has no open breakout batch")
                }

                val now = nowLocalDateTime()
                val result = mutableListOf<Relocation>()
                dedupedAssignments.forEach { input ->
                    if (input.breakoutIndex !in openRooms.indices) {
                        throw BadRequestException(
                            "breakoutIndex ${input.breakoutIndex} out of range for the open batch (${openRooms.size} rooms)",
                        )
                    }
                    val memberId = input.memberId.toBreakoutUuid()
                    val targetRoomRow = openRooms[input.breakoutIndex]
                    val targetRoomId = targetRoomRow[ConferenceBreakoutRoomTable.id]

                    val hasOpenParticipation =
                        ConferenceParticipationTable
                            .selectAll()
                            .where {
                                (ConferenceParticipationTable.roomId eq roomUuid) and
                                    (ConferenceParticipationTable.memberId eq memberId) and
                                    ConferenceParticipationTable.leftAt.isNull()
                            }.limit(1)
                            .any()
                    if (!hasOpenParticipation) {
                        throw BadRequestException(
                            "member ${input.memberId} does not currently hold an open participation in room $roomUuid",
                        )
                    }

                    // Where this member is coming FROM, for the force-disconnect phase below -- an
                    // existing open assignment (a breakout room) or, if none, the main room.
                    val existingAssignment =
                        (ConferenceBreakoutAssignmentTable innerJoin ConferenceBreakoutRoomTable)
                            .selectAll()
                            .where {
                                (ConferenceBreakoutRoomTable.parentRoomId eq roomUuid) and
                                    (ConferenceBreakoutAssignmentTable.memberId eq memberId) and
                                    ConferenceBreakoutAssignmentTable.recalledAt.isNull()
                            }.singleOrNull()
                    val fromLivekitRoomName =
                        existingAssignment?.get(ConferenceBreakoutRoomTable.livekitRoomName)
                            ?: parentRow[ConferenceRoomTable.livekitRoomName]

                    if (existingAssignment != null) {
                        ConferenceBreakoutAssignmentTable.update({
                            ConferenceBreakoutAssignmentTable.id eq existingAssignment[ConferenceBreakoutAssignmentTable.id]
                        }) {
                            it[recalledAt] = now
                        }
                    }
                    // Append-only per file header -- a fresh row even for a no-op self-reassignment
                    // (same target room), mirroring joinRoom/leaveRoom's own "re-join writes a
                    // second row" idiom.
                    ConferenceBreakoutAssignmentTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceBreakoutAssignmentTable.breakoutRoomId] = targetRoomId
                        it[ConferenceBreakoutAssignmentTable.memberId] = memberId
                        it[assignedAt] = now
                        it[recalledAt] = null
                    }
                    result +=
                        Relocation(
                            memberId = memberId,
                            fromLivekitRoomName = fromLivekitRoomName,
                            toLivekitRoomName = targetRoomRow[ConferenceBreakoutRoomTable.livekitRoomName],
                        )
                }
                result
            }

        // Outside any transaction -- see class KDoc "Consequence this creates". Skips a genuine
        // no-op (member reassigned to the SAME room they were already in).
        relocations.filter { it.fromLivekitRoomName != it.toLivekitRoomName }.forEach { relocation ->
            runCatching {
                liveKitCall {
                    liveKitAdminClient.removeParticipant(
                        room = relocation.fromLivekitRoomName,
                        identity = relocation.memberId.toString(),
                    )
                }
            }.onFailure {
                logger.warn {
                    "assignParticipants: failed to disconnect member ${relocation.memberId} from ${relocation.fromLivekitRoomName}"
                }
            }
        }

        return transaction {
            ConferenceBreakoutRoomTable
                .selectAll()
                .where { (ConferenceBreakoutRoomTable.parentRoomId eq roomUuid) and ConferenceBreakoutRoomTable.closedAt.isNull() }
                .orderBy(ConferenceBreakoutRoomTable.createdAt to SortOrder.ASC, ConferenceBreakoutRoomTable.id to SortOrder.ASC)
                .map { rowToBreakoutDto(it) }
        }
    }

    override suspend fun recallAll(roomId: String): Int {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = recallRateLimiter, memberId = current.memberId)
        val roomUuid = roomId.toBreakoutUuid()

        val openRooms =
            transaction {
                val parentRow =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomUuid }.singleOrNull()
                        ?: throw NotFoundException("Conference room $roomUuid not found")
                requireModeratorOrPrivileged(row = parentRow, current = current)
                ConferenceBreakoutRoomTable
                    .selectAll()
                    .where { (ConferenceBreakoutRoomTable.parentRoomId eq roomUuid) and ConferenceBreakoutRoomTable.closedAt.isNull() }
                    .map { it[ConferenceBreakoutRoomTable.id] to it[ConferenceBreakoutRoomTable.livekitRoomName] }
            }
        if (openRooms.isEmpty()) return 0

        // Outside any transaction -- delete every open breakout LiveKit room. Design review fix:
        // treats EVERY deleteRoom failure here as "already gone" (log-and-continue, never fail the
        // whole call) rather than createBreakoutRooms'/assignParticipants' own fail-loud posture --
        // a breakout room whose occupants already all voluntarily returned via returnToMainRoom
        // routinely self-empties past its own empty_timeout well within a session's length, so a
        // moderator clicking "alle zurückholen" afterwards must not see a raw failure toast for a
        // completely normal situation. The DB bookkeeping below always proceeds regardless -- same
        // best-effort posture ConferenceBreakoutCoordinator.closeAllBreakoutRoomsForRoom's own
        // endRoom-cascade caller applies.
        openRooms.forEach { (_, name) ->
            runCatching { liveKitCall { liveKitAdminClient.deleteRoom(name) } }
                .onFailure {
                    logger.warn { "recallAll: deleteRoom for breakout room $name (parent $roomUuid) failed -- treating as already-gone" }
                }
        }

        val now = nowLocalDateTime()
        return transaction {
            val ids = openRooms.map { it.first }
            ConferenceBreakoutRoomTable.update({ ConferenceBreakoutRoomTable.id inList ids }) { it[closedAt] = now }
            ConferenceBreakoutAssignmentTable.update({
                (ConferenceBreakoutAssignmentTable.breakoutRoomId inList ids) and ConferenceBreakoutAssignmentTable.recalledAt.isNull()
            }) { it[recalledAt] = now }
            ids.size
        }
    }

    // Returns a single-or-empty list, never a plain nullable DTO -- see
    // IConferenceBreakoutService.getMyBreakoutAssignment KDoc for why (kilua-rpc JS codegen).
    override suspend fun getMyBreakoutAssignment(roomId: String): List<ConferenceBreakoutAssignmentDto> {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = tokenRateLimiter, memberId = current.memberId)
        val roomUuid = roomId.toBreakoutUuid()
        return transaction {
            ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomUuid }.singleOrNull()
                ?: throw NotFoundException("Conference room $roomUuid not found")
            val hasParticipation =
                ConferenceParticipationTable
                    .selectAll()
                    .where {
                        (ConferenceParticipationTable.roomId eq roomUuid) and
                            (ConferenceParticipationTable.memberId eq current.memberId)
                    }.limit(1)
                    .any()
            if (!hasParticipation) throw ForbiddenException("Caller has never participated in room $roomUuid")

            (ConferenceBreakoutAssignmentTable innerJoin ConferenceBreakoutRoomTable)
                .selectAll()
                .where {
                    (ConferenceBreakoutRoomTable.parentRoomId eq roomUuid) and
                        (ConferenceBreakoutAssignmentTable.memberId eq current.memberId) and
                        ConferenceBreakoutAssignmentTable.recalledAt.isNull()
                }.singleOrNull()
                ?.let { row ->
                    listOf(
                        ConferenceBreakoutAssignmentDto(
                            breakoutRoomId = row[ConferenceBreakoutRoomTable.id].toString(),
                            breakoutRoomLabel = row[ConferenceBreakoutRoomTable.label],
                            assignedAt = row[ConferenceBreakoutAssignmentTable.assignedAt],
                        ),
                    )
                }.orEmpty()
        }
    }

    override suspend fun requestBreakoutJoinToken(breakoutRoomId: String): ConferenceJoinTokenDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = tokenRateLimiter, memberId = current.memberId)
        val breakoutUuid = breakoutRoomId.toBreakoutUuid()

        // The ONE query that must never be short-circuited or weakened -- see
        // IConferenceBreakoutService.requestBreakoutJoinToken KDoc. This is the entire enforcement
        // of "only a specifically-assigned participant may obtain a join token for that specific
        // breakout room".
        val prep =
            transaction {
                val assignmentRow =
                    (ConferenceBreakoutAssignmentTable innerJoin ConferenceBreakoutRoomTable)
                        .selectAll()
                        .where {
                            (ConferenceBreakoutAssignmentTable.breakoutRoomId eq breakoutUuid) and
                                (ConferenceBreakoutAssignmentTable.memberId eq current.memberId) and
                                ConferenceBreakoutAssignmentTable.recalledAt.isNull()
                        }.singleOrNull()
                        ?: throw ForbiddenException("Caller does not hold an open assignment to breakout room $breakoutUuid")
                TokenPrep(
                    livekitRoomName = assignmentRow[ConferenceBreakoutRoomTable.livekitRoomName],
                    isNonMember = currentMemberIsNonMember(current.memberId),
                    displayName = memberDisplayName(current.memberId),
                )
            }

        // Everyone gets PARTICIPANT here -- there is no breakout-room-scoped moderator concept, see
        // IConferenceBreakoutService KDoc "No breakout-room moderator".
        return mintJoinToken(
            roomIdForDto = breakoutUuid,
            livekitRoomName = prep.livekitRoomName,
            memberId = current.memberId,
            displayName = prep.displayName,
            role = ConferenceRole.PARTICIPANT,
            isNonMember = prep.isNonMember,
        )
    }

    override suspend fun returnToMainRoom(breakoutRoomId: String) {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = tokenRateLimiter, memberId = current.memberId)
        val breakoutUuid = breakoutRoomId.toBreakoutUuid()
        val now = nowLocalDateTime()
        transaction {
            ConferenceBreakoutAssignmentTable.update({
                (ConferenceBreakoutAssignmentTable.breakoutRoomId eq breakoutUuid) and
                    (ConferenceBreakoutAssignmentTable.memberId eq current.memberId) and
                    ConferenceBreakoutAssignmentTable.recalledAt.isNull()
            }) {
                it[recalledAt] = now
            }
        }
    }

    override suspend fun rejoinMainRoomToken(roomId: String): ConferenceJoinTokenDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = tokenRateLimiter, memberId = current.memberId)
        val roomUuid = roomId.toBreakoutUuid()

        val prep =
            transaction {
                val row =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomUuid }.singleOrNull()
                        ?: throw NotFoundException("Conference room $roomUuid not found")
                if (row[ConferenceRoomTable.endedAt] != null) {
                    throw ConflictException("Conference room $roomUuid has already ended")
                }
                val hasOpenParticipation =
                    ConferenceParticipationTable
                        .selectAll()
                        .where {
                            (ConferenceParticipationTable.roomId eq roomUuid) and
                                (ConferenceParticipationTable.memberId eq current.memberId) and
                                ConferenceParticipationTable.leftAt.isNull()
                        }.limit(1)
                        .any()
                if (!hasOpenParticipation) {
                    throw ForbiddenException("Caller does not currently hold an open participation in room $roomUuid")
                }
                // Same role derivation as ConferenceService.joinRoom -- a moderator returning to the
                // main room regains MODERATOR, their room-level authority never changed.
                val role =
                    if (row[ConferenceRoomTable.createdByMemberId] ==
                        current.memberId
                    ) {
                        ConferenceRole.MODERATOR
                    } else {
                        ConferenceRole.PARTICIPANT
                    }
                RejoinPrep(
                    livekitRoomName = row[ConferenceRoomTable.livekitRoomName],
                    isNonMember = currentMemberIsNonMember(current.memberId),
                    displayName = memberDisplayName(current.memberId),
                    role = role,
                )
            }

        return mintJoinToken(
            roomIdForDto = roomUuid,
            livekitRoomName = prep.livekitRoomName,
            memberId = current.memberId,
            displayName = prep.displayName,
            role = prep.role,
            isNonMember = prep.isNonMember,
        )
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private data class TokenPrep(
        val livekitRoomName: String,
        val isNonMember: Boolean,
        val displayName: String,
    )

    private data class RejoinPrep(
        val livekitRoomName: String,
        val isNonMember: Boolean,
        val displayName: String,
        val role: ConferenceRole,
    )

    /** See [IConferenceBreakoutService] KDoc "deliberately NOT duplicated" -- reuses the exact same conference-enabled gate [ConferenceService] applies, no independent availability toggle. */
    private fun requireConferenceEnabled() {
        if (!config.enabled) {
            throw ConflictException(
                "Videokonferenzen is not configured on this server (LAPIS_LIVEKIT_URL/_API_KEY/_API_SECRET " +
                    "unset) -- see ConferenceConfig KDoc",
            )
        }
    }

    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        memberId: Uuid,
    ) {
        if (!limiter.checkAndRecord("member:$memberId")) {
            throw ConflictException("Too many requests -- try again later")
        }
    }

    /** See [ConferenceService] KDoc "Transaction boundaries around the LiveKit network call" -- same choke point, same mapping. */
    private suspend fun <T> liveKitCall(action: suspend () -> T): T =
        try {
            action()
        } catch (e: LiveKitAdminException) {
            throw ConflictException("LiveKit request failed: ${e.message}")
        }

    /** See class KDoc "Authorization re-derivation, never a cached role" -- deliberately duplicated, not shared with [ConferenceService]. */
    private fun requireModeratorOrPrivileged(
        row: ResultRow,
        current: CurrentMember,
    ) {
        val isCreator = row[ConferenceRoomTable.createdByMemberId] == current.memberId
        if (!isCreator && !current.isPrivileged) throw ForbiddenException()
    }

    /**
     * V0.11.0: widened from `== MemberStatus.GUEST` to `in MemberStatusSets.NON_MEMBER` so a
     * FRIEND gets the same SHORT `guestTokenTtlMinutes` LiveKit token a GUEST always got -- this
     * feeds a token-TTL decision, not an access decision (that gate already ran in
     * [ConferenceService.joinRoom]/[requireRoomEntryAuthorization] before a breakout assignment
     * could even exist for this member).
     */
    private fun currentMemberIsNonMember(memberId: Uuid): Boolean =
        MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.status] in
            MemberStatusSets.NON_MEMBER

    private fun memberDisplayName(memberId: Uuid): String =
        MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.displayName]

    private fun rowToBreakoutDto(row: ResultRow): ConferenceBreakoutRoomDto {
        val id = row[ConferenceBreakoutRoomTable.id]
        val assignments =
            (ConferenceBreakoutAssignmentTable innerJoin MemberTable)
                .selectAll()
                .where {
                    (ConferenceBreakoutAssignmentTable.breakoutRoomId eq id) and ConferenceBreakoutAssignmentTable.recalledAt.isNull()
                }.orderBy(ConferenceBreakoutAssignmentTable.assignedAt to SortOrder.ASC)
                .map { it[ConferenceBreakoutAssignmentTable.memberId] to it[MemberTable.displayName] }
        return ConferenceBreakoutRoomDto(
            id = id.toString(),
            parentRoomId = row[ConferenceBreakoutRoomTable.parentRoomId].toString(),
            label = row[ConferenceBreakoutRoomTable.label],
            createdAt = row[ConferenceBreakoutRoomTable.createdAt],
            closedAt = row[ConferenceBreakoutRoomTable.closedAt],
            assignedMemberIds = assignments.map { it.first.toString() },
            assignedDisplayNames = assignments.map { it.second },
        )
    }

    /** Shared by [requestBreakoutJoinToken]/[rejoinMainRoomToken] -- same [LiveKitAccessToken.mintParticipantToken]/[TurnCredentialMinter.mint] shape [ConferenceService.joinRoom] establishes, including the non-member-vs-ACTIVE TTL split. */
    private fun mintJoinToken(
        roomIdForDto: Uuid,
        livekitRoomName: String,
        memberId: Uuid,
        displayName: String,
        role: ConferenceRole,
        isNonMember: Boolean,
    ): ConferenceJoinTokenDto {
        val effectiveTtl = if (isNonMember) config.guestTokenTtlMinutes else config.tokenTtlMinutes
        val minted =
            LiveKitAccessToken.mintParticipantToken(
                apiKey = config.apiKey,
                apiSecret = config.apiSecret,
                roomName = livekitRoomName,
                identity = memberId.toString(),
                displayName = displayName,
                ttl = effectiveTtl.minutes,
            )
        val turnServers =
            if (config.turnEnabled) {
                val turnCredential =
                    TurnCredentialMinter.mint(
                        sharedSecret = config.turnSharedSecret,
                        urls = config.turnUrls,
                        label = memberId.toString(),
                        ttl = effectiveTtl.minutes,
                    )
                listOf(
                    ConferenceTurnServer(
                        urls = turnCredential.urls,
                        username = turnCredential.username,
                        credential = turnCredential.credential,
                    ),
                )
            } else {
                emptyList()
            }
        return ConferenceJoinTokenDto(
            roomId = roomIdForDto.toString(),
            livekitRoomName = livekitRoomName,
            serverUrl = config.livekitUrl,
            token = minted.jwt,
            identity = memberId.toString(),
            displayName = displayName,
            role = role,
            expiresAt = minted.expiresAt.toLocalDateTime(TimeZone.currentSystemDefault()),
            turnServers = turnServers,
        )
    }

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()

    private fun String.toBreakoutUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toUuidOrNull(): Uuid? = runCatching { Uuid.parse(this) }.getOrNull()
}

/**
 * V1.0 Wave 6 "Breakout-Räume" -- the ONE non-RPC, server-internal bridge from Wave 1's
 * [ConferenceService.endRoom] into this wave's breakout lifecycle. A plain object (not an RPC
 * service, not a new [IConferenceBreakoutService] method), mirroring
 * [ConferenceRecordingCoordinator]'s own "server-internal bridge, called as the LAST statement of
 * the caller's transaction" shape -- but DB-only in a slightly different sense than that class:
 * [closeAllBreakoutRoomsForRoom] itself never calls LiveKit (that already happened, best-effort, in
 * the caller's own OUTSIDE-transaction phase, see [ConferenceService.endRoom]'s Wave 6 addition) --
 * it mirrors `endRoom`'s own DIRECT, synchronous `deleteRoom` shape rather than
 * [ConferenceRecordingCoordinator]'s poller-mediated pattern, because breakout-room `deleteRoom` has
 * no ffmpeg/Egress-style async lag that would need a poller to bridge.
 *
 * **Belt-and-braces, not the only safety net.** Even if the caller's own best-effort `deleteRoom`
 * loop failed for some breakout rooms (LiveKit hiccup), those LiveKit rooms self-heal via their own
 * `empty_timeout` (300s) once the parent meeting has ended and nobody can obtain a fresh join token
 * for them anymore ([requestBreakoutJoinToken]'s own authorization query fails the instant this
 * function stamps their assignment rows [recalledAt]/`closedAt`) -- same self-healing property
 * [IConferenceService.listActiveRooms]' own lazy reconciliation already relies on elsewhere.
 */
object ConferenceBreakoutCoordinator {
    /**
     * DB-only read, no transaction ownership assumed beyond the caller's own -- returns
     * `livekit_room_name` for every currently-open breakout room of [roomId], for the caller
     * ([ConferenceService.endRoom]) to delete via LiveKit OUTSIDE any transaction, mirroring
     * `endRoom`'s own main-room `deleteRoom` call.
     */
    fun openLiveKitRoomNames(roomId: Uuid): List<String> =
        ConferenceBreakoutRoomTable
            .selectAll()
            .where { (ConferenceBreakoutRoomTable.parentRoomId eq roomId) and ConferenceBreakoutRoomTable.closedAt.isNull() }
            .map { it[ConferenceBreakoutRoomTable.livekitRoomName] }

    /**
     * DB-only, must be called as the LAST lock-taking statement of the caller's already-open
     * transaction -- same deadlock-avoidance contract [ConferenceRecordingCoordinator
     * .stopActiveRecordingsForRoom] documents. Stamps `closed_at` on every open breakout room of
     * [roomId] and `recalled_at` on every one of their still-open assignment rows -- pure
     * bookkeeping, the actual LiveKit `deleteRoom` calls already happened (best-effort) in the
     * caller's own OUTSIDE-transaction phase, see [ConferenceService.endRoom]'s Wave 6 addition.
     * No-op if [roomId] has no open breakout room.
     */
    fun closeAllBreakoutRoomsForRoom(
        roomId: Uuid,
        now: LocalDateTime,
    ) {
        val openIds =
            ConferenceBreakoutRoomTable
                .selectAll()
                .where { (ConferenceBreakoutRoomTable.parentRoomId eq roomId) and ConferenceBreakoutRoomTable.closedAt.isNull() }
                .map { it[ConferenceBreakoutRoomTable.id] }
        if (openIds.isEmpty()) return
        ConferenceBreakoutRoomTable.update({ ConferenceBreakoutRoomTable.id inList openIds }) { it[closedAt] = now }
        ConferenceBreakoutAssignmentTable.update({
            (ConferenceBreakoutAssignmentTable.breakoutRoomId inList openIds) and ConferenceBreakoutAssignmentTable.recalledAt.isNull()
        }) {
            it[recalledAt] = now
        }
    }
}
