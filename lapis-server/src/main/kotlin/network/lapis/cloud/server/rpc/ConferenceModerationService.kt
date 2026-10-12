package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceAudioPolicy
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceModerationPackets
import network.lapis.cloud.server.conference.LIVEKIT_MICROPHONE_SOURCE
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import network.lapis.cloud.server.conference.MAX_SEND_DATA_DESTINATIONS
import network.lapis.cloud.server.conference.conferencePermission
import network.lapis.cloud.server.conference.isEventGuestIdentity
import network.lapis.cloud.server.conference.liveKitIdentityAsMemberId
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.encounter.ConferenceModeratorAuthority
import network.lapis.cloud.server.encounter.EncounterRoomGuard
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_TOPIC
import network.lapis.cloud.shared.domain.ConferenceBroadcastView
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IConferenceModerationService
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

// Privacy rule of this file: the logs name counts and exception classes only -- never a member id, identity, display name or room id.
private val logger = KotlinLogging.logger {}

/**
 * The server-side-only reason of EVERY refused moderation call (Kilua transmits only the exception TYPE, so a client cannot tell "no
 * authority" from "unknown room" from "peer rule" -- there is no existence or role oracle).
 */
internal const val MODERATION_DENIED = "Moderation action not possible"

/** Audit `moderation` discriminators -- the closed set of values the audit entry may carry. */
internal const val AUDIT_MUTE_ONE = "mute_one"
internal const val AUDIT_MUTE_ALL = "mute_all"
internal const val AUDIT_UNMUTE_REQUEST = "unmute_request"
internal const val AUDIT_AUDIO_POLICY = "audio_policy"
internal const val AUDIT_BROADCAST_VIEW = "BROADCAST_VIEW"
internal const val AUDIT_JOIN_LEAVE_SOUNDS = "JOIN_LEAVE_SOUNDS"

/**
 * V1.9.108 -- see [IConferenceModerationService]. Every call follows the same three phases (pitfall S6: LiveKit is NEVER called inside a
 * `transaction {}`, because Exposed repeats the whole block on a `SQLException`):
 *
 *  1. a `transaction {}` that authorizes (the shared [ConferenceModeratorAuthority], the room is active, no encounter session, the target is
 *     in the room, not the caller, not the creator, and the PEER RULE: somebody without BOARD/ADMIN cannot moderate somebody who has it);
 *  2. the LiveKit calls;
 *  3. a `transaction {}` that writes ONE audit entry as its last operation. The entry names actor, action, room and a count -- never the
 *     people affected (privacy).
 *
 * **Why the peer rule does not use `PeerGuard`**: that four-eyes mechanism protects against account takeover by a peer. A mute is a
 * reversible order measure, not an account change.
 *
 * **No forced unmute, ever.** The only way a microphone gets switched on is the person doing it themselves; see
 * `ConferenceNoForcedUnmuteTripwireTest`.
 *
 * The rate limiters and [audioGrantState] are process-wide singletons wired in `Application.module` -- NO defaults on purpose (Kilua
 * builds this service per RPC call, a default would silently give every call a fresh, empty limiter).
 */
class ConferenceModerationService(
    private val call: ApplicationCall,
    private val liveKitAdminClient: LiveKitAdminClient,
    private val config: ConferenceConfig,
    private val audioGrantState: ConferenceAudioGrantState,
    private val muteAllRateLimiter: FederationInboxRateLimiter,
    private val muteOneRateLimiter: FederationInboxRateLimiter,
    private val requestUnmuteRateLimiter: FederationInboxRateLimiter,
    private val policyRateLimiter: FederationInboxRateLimiter,
    private val previewRateLimiter: FederationInboxRateLimiter,
    private val broadcastViewRateLimiter: FederationInboxRateLimiter,
    private val joinLeaveSoundsRateLimiter: FederationInboxRateLimiter,
) : IConferenceModerationService {
    private data class RoomContext(
        val roomId: Uuid,
        val livekitRoomName: String,
        val creatorId: Uuid,
    )

    /** Who `muteAll` would mute, and which identities are moderators (they receive the `muted_peer` packets). */
    private data class MutePlan(
        val candidates: List<LiveKitParticipantInfo>,
        val moderatorIdentities: List<String>,
    )

    override suspend fun muteParticipant(
        roomId: String,
        memberId: String,
    ) {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = muteOneRateLimiter, key = "member:${current.memberId}")
        val targetId = memberId.asUuidOrDenied()
        val room =
            transaction {
                authorizeRoom(roomId = roomId, current = current).also { requireTarget(room = it, current = current, targetId = targetId) }
            }
        val participants = liveKitCall { liveKitAdminClient.listParticipants(room.livekitRoomName) }
        val target = participants.firstOrNull { it.identity == targetId.toString() } ?: return
        val openMicrophones = target.openMicrophones()
        if (openMicrophones.isEmpty()) return
        val moderators = moderatorIdentities(room = room, participants = participants)
        openMicrophones.forEach {
            liveKitCall { liveKitAdminClient.muteMicrophone(room = room.livekitRoomName, identity = target.identity, trackSid = it.sid) }
        }
        notifyMuted(room = room, mutedIdentities = listOf(target.identity), moderatorIdentities = moderators)
        recordAudit(current = current, room = room, after = """{"moderation":"$AUDIT_MUTE_ONE","count":1}""")
    }

    override suspend fun muteAll(roomId: String): Int {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        val room = transaction { authorizeRoom(roomId = roomId, current = current) }
        requireWithinRate(limiter = muteAllRateLimiter, key = "room:${room.roomId}")
        val plan = mutePlan(room = room, current = current)
        if (plan.candidates.isEmpty()) return 0
        val muted = mutableListOf<String>()
        var failed = 0
        for (candidate in plan.candidates) {
            try {
                candidate.openMicrophones().forEach {
                    liveKitAdminClient.muteMicrophone(room = room.livekitRoomName, identity = candidate.identity, trackSid = it.sid)
                }
                muted += candidate.identity
            } catch (e: LiveKitAdminException) {
                failed++
            }
        }
        if (failed > 0) logger.warn { "conference moderation: muteAll failed for $failed of ${plan.candidates.size} participant(s)" }
        if (muted.isEmpty()) throw ConflictException("LiveKit request failed")
        notifyMuted(room = room, mutedIdentities = muted, moderatorIdentities = plan.moderatorIdentities)
        recordAudit(current = current, room = room, after = """{"moderation":"$AUDIT_MUTE_ALL","count":${muted.size}}""")
        return muted.size
    }

    override suspend fun estimateMuteAll(roomId: String): Int {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = previewRateLimiter, key = "member:${current.memberId}")
        val room = transaction { authorizeRoom(roomId = roomId, current = current) }
        return mutePlan(room = room, current = current).candidates.size
    }

    override suspend fun requestUnmute(
        roomId: String,
        memberId: String,
    ) {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        val targetId = memberId.asUuidOrDenied()
        val (room, locked) =
            transaction {
                val room = authorizeRoom(roomId = roomId, current = current)
                requireTarget(room = room, current = current, targetId = targetId)
                room to isLocked(room.roomId)
            }
        requireWithinRate(limiter = requestUnmuteRateLimiter, key = "room:${room.roomId}:target:$targetId")
        val participants = liveKitCall { liveKitAdminClient.listParticipants(room.livekitRoomName) }
        val target = participants.firstOrNull { it.identity == targetId.toString() } ?: return
        if (target.openMicrophones().isNotEmpty()) return
        if (locked) {
            // The person cannot switch on in a locked room, so a request is pointless without a release of exactly this person. Record the
            // release BEFORE the permission update: the reconciler must not undo it in between.
            if (!audioGrantState.grant(roomId = room.roomId, memberId = targetId)) throw ConflictException("Too many individual releases")
            try {
                liveKitCall {
                    liveKitAdminClient.updateParticipantPermission(
                        room = room.livekitRoomName,
                        identity = target.identity,
                        permission = conferencePermission(microphoneAllowed = true),
                    )
                }
            } catch (e: ConflictException) {
                audioGrantState.revoke(roomId = room.roomId, memberId = targetId)
                throw e
            }
        }
        liveKitCall {
            liveKitAdminClient.sendDataTo(
                room = room.livekitRoomName,
                topic = CONFERENCE_MODERATION_TOPIC,
                payload = ConferenceModerationPackets.unmuteRequest(),
                destinationIdentities = listOf(target.identity),
            )
        }
        recordAudit(current = current, room = room, after = """{"moderation":"$AUDIT_UNMUTE_REQUEST","count":1}""")
    }

    override suspend fun setRoomAudioPolicy(
        roomId: String,
        muteOnJoin: Boolean,
        participantsMayUnmute: Boolean,
    ): ConferenceRoomDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        val room = transaction { authorizeRoom(roomId = roomId, current = current) }
        requireWithinRate(limiter = policyRateLimiter, key = "room:${room.roomId}")
        // One policy change per room at a time, and never concurrently with the reconciler's pass over this room.
        return audioGrantState.withRoomLock(roomId = room.roomId) {
            // The row is the truth: joins mint their token from it from this commit on. LiveKit is enforced afterwards.
            val wasLocked =
                transaction {
                    val row =
                        ConferenceRoomTable
                            .selectAll()
                            .where { ConferenceRoomTable.id eq room.roomId }
                            .forUpdate()
                            .singleOrNull()
                            ?.takeIf { it[ConferenceRoomTable.endedAt] == null }
                            ?: throw ForbiddenException(MODERATION_DENIED)
                    ConferenceRoomTable.update({ ConferenceRoomTable.id eq room.roomId }) {
                        it[ConferenceRoomTable.muteOnJoin] = muteOnJoin
                        it[ConferenceRoomTable.participantsMayUnmute] = participantsMayUnmute
                    }
                    !row[ConferenceRoomTable.participantsMayUnmute]
                }
            val nowLocked = !participantsMayUnmute
            var liveCount = 0
            var liveKitFailed = false
            // An unlock that did not reach everybody earlier is re-applied by the next request for "unlocked" (the stored state is equal,
            // but LiveKit is not) -- otherwise the moderator could never repair it from the UI.
            val retryUnlock = !nowLocked && audioGrantState.isUnlockPending(room.roomId)
            if (wasLocked != nowLocked || retryUnlock) {
                if (wasLocked != nowLocked) {
                    // Releases only make sense inside one lock period.
                    audioGrantState.clear(room.roomId)
                }
                try {
                    val outcome = applyLock(room = room, locked = nowLocked)
                    liveCount = outcome.liveCount
                    if (!nowLocked && outcome.failed > 0) {
                        audioGrantState.markUnlockPending(room.roomId)
                    } else {
                        audioGrantState.clearUnlockPending(room.roomId)
                    }
                } catch (e: LiveKitAdminException) {
                    liveKitFailed = true
                    // A failed LOCK is enforced by the reconciler (it watches locked rooms); a failed UNLOCK is handed back by it via the mark.
                    if (nowLocked) audioGrantState.clearUnlockPending(room.roomId) else audioGrantState.markUnlockPending(room.roomId)
                    logger.warn {
                        "conference moderation: applying the room ${if (nowLocked) "lock" else "unlock"} failed (LiveKit); the reconciler will retry"
                    }
                }
            }
            val dto =
                transaction {
                    val fresh = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room.roomId }.single()
                    val result = ConferenceRoomDtoMapper.toDto(row = fresh, callerId = current.memberId, liveParticipantCount = liveCount)
                    // AuditLogRecorder.record must be the LAST lock-taking operation in this transaction.
                    AuditLogRecorder.record(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        entityType = AuditEntityType.CONFERENCE_ROOM,
                        entityId = room.roomId,
                        action = AuditAction.UPDATE,
                        after =
                            """{"moderation":"$AUDIT_AUDIO_POLICY","muteOnJoin":$muteOnJoin,"participantsMayUnmute":$participantsMayUnmute}""",
                    )
                    result
                }
            if (liveKitFailed) throw ConflictException("LiveKit request failed")
            dto
        }
    }

    override suspend fun setRoomBroadcastView(
        roomId: String,
        view: ConferenceBroadcastView,
    ): ConferenceRoomDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        // Authorization first and from the database; every refusal (no authority, unknown/ended room, encounter session) is the same exception.
        val room = transaction { authorizeRoom(roomId = roomId, current = current) }
        requireWithinRate(limiter = broadcastViewRateLimiter, key = "room:${room.roomId}")
        // No LiveKit call: the view only matters from the next recording / stream start.
        return transaction {
            ConferenceRoomTable
                .selectAll()
                .where { ConferenceRoomTable.id eq room.roomId }
                .forUpdate()
                .singleOrNull()
                ?.takeIf { it[ConferenceRoomTable.endedAt] == null }
                ?: throw ForbiddenException(MODERATION_DENIED)
            ConferenceRoomTable.update({ ConferenceRoomTable.id eq room.roomId }) {
                it[ConferenceRoomTable.broadcastView] = view
            }
            val fresh = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room.roomId }.single()
            val result = ConferenceRoomDtoMapper.toDto(row = fresh, callerId = current.memberId, liveParticipantCount = 0)
            // AuditLogRecorder.record must be the LAST lock-taking operation in this transaction. Metadata only -- no names.
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.CONFERENCE_ROOM,
                entityId = room.roomId,
                action = AuditAction.UPDATE,
                after = """{"moderation":"$AUDIT_BROADCAST_VIEW","view":"${view.name}"}""",
            )
            result
        }
    }

    override suspend fun setRoomJoinLeaveSounds(
        roomId: String,
        enabled: Boolean,
    ): ConferenceRoomDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        // Authorization first and from the database; every refusal (no authority, unknown/ended room, encounter session) is the same exception.
        val room = transaction { authorizeRoom(roomId = roomId, current = current) }
        requireWithinRate(limiter = joinLeaveSoundsRateLimiter, key = "room:${room.roomId}")
        // No LiveKit call: the tones are a purely client-side matter, running clients learn the option at their next join or reload.
        return transaction {
            ConferenceRoomTable
                .selectAll()
                .where { ConferenceRoomTable.id eq room.roomId }
                .forUpdate()
                .singleOrNull()
                ?.takeIf { it[ConferenceRoomTable.endedAt] == null }
                ?: throw ForbiddenException(MODERATION_DENIED)
            ConferenceRoomTable.update({ ConferenceRoomTable.id eq room.roomId }) {
                it[ConferenceRoomTable.joinLeaveSounds] = enabled
            }
            val fresh = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room.roomId }.single()
            val result = ConferenceRoomDtoMapper.toDto(row = fresh, callerId = current.memberId, liveParticipantCount = 0)
            // AuditLogRecorder.record must be the LAST lock-taking operation in this transaction. Metadata only -- no names.
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.CONFERENCE_ROOM,
                entityId = room.roomId,
                action = AuditAction.UPDATE,
                after = """{"moderation":"$AUDIT_JOIN_LEAVE_SOUNDS","enabled":$enabled}""",
            )
            result
        }
    }

    // -- LiveKit-side application of a lock change ------------------------------------------------------------------------------------

    /**
     * Switches every non-moderator in the room to the new permission; on locking also mutes their open microphones. Per-participant
     * failures are counted and logged, only an unreachable LiveKit (the listing) propagates. Returns the live participant count and the
     * number of people the change did not reach.
     */
    private suspend fun applyLock(
        room: RoomContext,
        locked: Boolean,
    ): LockOutcome {
        // Direct call, NOT liveKitCall: the caller turns a LiveKitAdminException into "policy stored + audited, then report the failure".
        val participants = liveKitAdminClient.listParticipants(room.livekitRoomName)
        val moderators = moderatorIdentities(room = room, participants = participants).toSet()
        // Account-less event guests (`evg-...`) are ordinary non-moderators: the room-wide lock applies to them as well.
        val others =
            participants.filter {
                (liveKitIdentityAsMemberId(it.identity) != null || isEventGuestIdentity(it.identity)) && it.identity !in moderators
            }
        var failed = 0
        val muted = mutableListOf<String>()
        for (participant in others) {
            try {
                liveKitAdminClient.updateParticipantPermission(
                    room = room.livekitRoomName,
                    identity = participant.identity,
                    permission = conferencePermission(microphoneAllowed = !locked),
                )
                if (locked) {
                    val open = participant.openMicrophones()
                    open.forEach {
                        liveKitAdminClient.muteMicrophone(room = room.livekitRoomName, identity = participant.identity, trackSid = it.sid)
                    }
                    if (open.isNotEmpty()) muted += participant.identity
                }
            } catch (e: LiveKitAdminException) {
                failed++
            }
        }
        if (failed > 0) logger.warn { "conference moderation: the lock change failed for $failed of ${others.size} participant(s)" }
        notifyMuted(room = room, mutedIdentities = muted, moderatorIdentities = moderators.toList())
        try {
            liveKitAdminClient.sendData(
                room = room.livekitRoomName,
                topic = CONFERENCE_MODERATION_TOPIC,
                payload = if (locked) ConferenceModerationPackets.lock() else ConferenceModerationPackets.unlock(),
            )
        } catch (e: LiveKitAdminException) {
            logger.warn { "conference moderation: the lock notice could not be sent" }
        }
        return LockOutcome(liveCount = participants.size, failed = failed)
    }

    /** Result of [applyLock]: the live participant count and how many people the permission change did NOT reach. */
    private data class LockOutcome(
        val liveCount: Int,
        val failed: Int,
    )

    // -- Helpers ------------------------------------------------------------------------------------------------------------------------

    private suspend fun mutePlan(
        room: RoomContext,
        current: CurrentMember,
    ): MutePlan {
        val participants = liveKitCall { liveKitAdminClient.listParticipants(room.livekitRoomName) }
        val privileged = privilegedAmong(participants)
        val candidates =
            participants
                .filter { participant ->
                    val memberId = liveKitIdentityAsMemberId(participant.identity)
                    val eligible =
                        if (memberId != null) {
                            memberId != current.memberId && !isModerator(memberId = memberId, room = room, privileged = privileged)
                        } else {
                            // an account-less event guest is never a moderator
                            isEventGuestIdentity(participant.identity)
                        }
                    eligible && participant.openMicrophones().isNotEmpty()
                }.take(config.maxParticipants)
        return MutePlan(
            candidates = candidates,
            moderatorIdentities = moderatorIdentities(room = room, participants = participants, privileged = privileged),
        )
    }

    private suspend fun moderatorIdentities(
        room: RoomContext,
        participants: List<LiveKitParticipantInfo>,
        privileged: Set<Uuid> = emptySet(),
    ): List<String> {
        val known = privileged.ifEmpty { privilegedAmong(participants) }
        return participants
            .filter { p ->
                liveKitIdentityAsMemberId(p.identity)?.let { isModerator(memberId = it, room = room, privileged = known) } ==
                    true
            }.map { it.identity }
    }

    /** BOARD/ADMIN among the member UUIDs of [participants] (a short read-only transaction -- never around a LiveKit call). */
    private fun privilegedAmong(participants: List<LiveKitParticipantInfo>): Set<Uuid> {
        val memberIds = participants.mapNotNull { liveKitIdentityAsMemberId(it.identity) }
        return transaction { ConferenceAudioPolicy.privilegedAmong(memberIds) }
    }

    private fun isModerator(
        memberId: Uuid,
        room: RoomContext,
        privileged: Set<Uuid>,
    ): Boolean = ConferenceAudioPolicy.isModeratorIdentity(identity = memberId, creatorId = room.creatorId, privileged = privileged)

    /**
     * Tells the muted people ("muted") and the moderators present ("muted_peer", one per muted person) what happened. Best effort: the
     * microphone is already muted, a lost notice must not turn that into an error.
     */
    private suspend fun notifyMuted(
        room: RoomContext,
        mutedIdentities: List<String>,
        moderatorIdentities: List<String>,
    ) {
        if (mutedIdentities.isEmpty()) return
        sendBestEffort(room = room, payload = ConferenceModerationPackets.muted(), destinations = mutedIdentities)
        if (moderatorIdentities.isEmpty()) return
        mutedIdentities.forEach { identity ->
            val memberId = liveKitIdentityAsMemberId(identity) ?: return@forEach
            sendBestEffort(room = room, payload = ConferenceModerationPackets.mutedPeer(memberId), destinations = moderatorIdentities)
        }
    }

    private suspend fun sendBestEffort(
        room: RoomContext,
        payload: ByteArray,
        destinations: List<String>,
    ) {
        destinations.chunked(MAX_SEND_DATA_DESTINATIONS).forEach { chunk ->
            try {
                liveKitAdminClient.sendDataTo(
                    room = room.livekitRoomName,
                    topic = CONFERENCE_MODERATION_TOPIC,
                    payload = payload,
                    destinationIdentities = chunk,
                )
            } catch (e: LiveKitAdminException) {
                logger.warn { "conference moderation: a notice to ${chunk.size} participant(s) could not be sent" }
            }
        }
    }

    private fun recordAudit(
        current: CurrentMember,
        room: RoomContext,
        after: String,
    ) {
        transaction {
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.CONFERENCE_ROOM,
                entityId = room.roomId,
                action = AuditAction.UPDATE,
                after = after,
            )
        }
    }

    /** The room is active, an ordinary conference (no encounter session), and the caller moderates it -- else the ONE uniform refusal. */
    private fun authorizeRoom(
        roomId: String,
        current: CurrentMember,
    ): RoomContext {
        val id = roomId.asUuidOrDenied()
        val row =
            ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.singleOrNull()
                ?: throw ForbiddenException(MODERATION_DENIED)
        if (EncounterRoomGuard.isEncounterRoom(row) || row[ConferenceRoomTable.endedAt] != null) throw ForbiddenException(MODERATION_DENIED)
        if (!ConferenceModeratorAuthority.isModerator(row = row, current = current)) throw ForbiddenException(MODERATION_DENIED)
        return RoomContext(
            roomId = id,
            livekitRoomName = row[ConferenceRoomTable.livekitRoomName],
            creatorId = row[ConferenceRoomTable.createdByMemberId],
        )
    }

    /** The target takes part in the room, is neither the caller nor the creator, and the PEER RULE holds. */
    private fun requireTarget(
        room: RoomContext,
        current: CurrentMember,
        targetId: Uuid,
    ) {
        if (targetId == current.memberId || targetId == room.creatorId) throw ForbiddenException(MODERATION_DENIED)
        val inRoom =
            ConferenceParticipationTable
                .selectAll()
                .where {
                    (ConferenceParticipationTable.roomId eq room.roomId) and
                        (ConferenceParticipationTable.memberId eq targetId) and
                        ConferenceParticipationTable.leftAt.isNull()
                }.limit(1)
                .any()
        if (!inRoom) throw ForbiddenException(MODERATION_DENIED)
        // Peer rule: somebody who is not BOARD/ADMIN (the room creator as an ordinary member) cannot moderate somebody who is.
        if (!current.isPrivileged && ConferenceAudioPolicy.privilegedAmong(listOf(targetId)).isNotEmpty()) {
            throw ForbiddenException(MODERATION_DENIED)
        }
    }

    private fun isLocked(roomId: Uuid): Boolean =
        !ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.single()[ConferenceRoomTable.participantsMayUnmute]

    private fun LiveKitParticipantInfo.openMicrophones() =
        tracks.filter {
            it.source.equals(LIVEKIT_MICROPHONE_SOURCE, ignoreCase = true) &&
                !it.muted
        }

    private fun String.asUuidOrDenied(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw ForbiddenException(MODERATION_DENIED) }

    private fun requireConferenceEnabled() {
        if (!config.enabled) {
            throw ConflictException("Videokonferenzen is not configured on this server (LAPIS_LIVEKIT_URL/_API_KEY/_API_SECRET unset)")
        }
    }

    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        key: String,
    ) {
        if (!limiter.checkAndRecord(key)) throw ConflictException("Too many requests -- try again later")
    }

    private suspend fun <T> liveKitCall(action: suspend () -> T): T =
        try {
            action()
        } catch (e: LiveKitAdminException) {
            throw ConflictException("LiveKit request failed: ${e.message}")
        }
}
