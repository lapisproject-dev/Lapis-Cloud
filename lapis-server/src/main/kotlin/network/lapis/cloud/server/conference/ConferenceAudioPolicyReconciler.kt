package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// Privacy rule of this file: the logs name counts and exception classes only -- never a member id, identity, display name or room id.
private val logger = KotlinLogging.logger {}

/**
 * V1.9.108 -- what makes a room lock real, whatever LiveKit does with a permission that is taken away while a track is already published.
 * Runs every [intervalSeconds] and never throws. It only looks at ACTIVE, ordinary (no encounter session) rooms with
 * `participants_may_unmute = false`; per such room it asks LiveKit for the participants and, for everybody who is not a moderator and has
 * no individual release ([ConferenceAudioGrantState]):
 *
 *  - takes the microphone away from the permission if it is still in it (`can_publish_sources` contains `MICROPHONE`, or is empty which
 *    LiveKit reads as "every source") -- this also catches a reconnect with the ORIGINAL token (pitfall S4: the SDK reuses the token it
 *    joined with, so a person who joined before the lock would otherwise get the microphone back on a reconnect);
 *  - mutes a microphone track that is still open.
 *
 * Additionally heals a room whose UNLOCK did not fully reach LiveKit ([ConferenceAudioGrantState.markUnlockPending]): everybody who is
 * not a moderator and whose permission lacks the microphone gets it back (permission only, never an unmute). Every per-room pass runs under
 * [ConferenceAudioGrantState.withRoomLock] and re-reads the lock state after the participant listing, so it can never undo a concurrent
 * unlock.
 *
 * An identity that is no member UUID (egress/recorder) is skipped (pitfall S9). Unreachable LiveKit is a single warning without ids.
 */
class ConferenceAudioPolicyReconciler(
    private val liveKitAdminClient: LiveKitAdminClient,
    private val grants: ConferenceAudioGrantState,
    private val liveKitEnabled: Boolean,
    private val intervalSeconds: Long = 5,
) {
    private var job: Job? = null

    /** `true` once the one-off heal pass after a (re)start has marked the active unlocked rooms (the unlock-pending mark is in memory only). */
    private var startupHealMarked = false

    fun start() {
        if (!liveKitEnabled || job?.isActive == true) return
        job =
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                while (isActive) {
                    tick()
                    delay(intervalSeconds.seconds)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private data class LockedRoom(
        val id: Uuid,
        val livekitRoomName: String,
        val creatorId: Uuid,
    )

    /** One full run. Never throws (except for coroutine cancellation). */
    suspend fun tick() {
        if (!liveKitEnabled) return
        try {
            // The unlock-pending mark does not survive a restart: once per process every active unlocked room is checked as if pending,
            // so a person a failed unlock missed before the restart gets the microphone back (permission only, never an unmute).
            if (!startupHealMarked) {
                transaction {
                    ConferenceRoomTable
                        .selectAll()
                        .where {
                            ConferenceRoomTable.endedAt.isNull() and
                                ConferenceRoomTable.encounterSpaceId.isNull() and
                                (ConferenceRoomTable.participantsMayUnmute eq true)
                        }.map { it[ConferenceRoomTable.id] }
                }.forEach { grants.markUnlockPending(it) }
                startupHealMarked = true
            }
            // Rooms that ended while marked / moderated would otherwise stay in the maps for the life of the process.
            grants.pruneInactiveRooms(
                transaction {
                    ConferenceRoomTable
                        .selectAll()
                        .where { ConferenceRoomTable.endedAt.isNull() }
                        .map { it[ConferenceRoomTable.id] }
                        .toSet()
                },
            )
            // Locked rooms are enforced; rooms whose UNLOCK did not fully reach LiveKit are healed (the microphone is handed back).
            val pending = grants.pendingUnlockRooms()
            val rooms =
                transaction {
                    ConferenceRoomTable
                        .selectAll()
                        .where {
                            ConferenceRoomTable.endedAt.isNull() and
                                ConferenceRoomTable.encounterSpaceId.isNull() and
                                (
                                    (ConferenceRoomTable.participantsMayUnmute eq false) or
                                        (if (pending.isEmpty()) Op.FALSE else (ConferenceRoomTable.id inList pending.toList()))
                                )
                        }.map {
                            LockedRoom(
                                id = it[ConferenceRoomTable.id],
                                livekitRoomName = it[ConferenceRoomTable.livekitRoomName],
                                creatorId = it[ConferenceRoomTable.createdByMemberId],
                            )
                        }
                }
            var corrected = 0
            var muted = 0
            var failedRooms = 0
            for (room in rooms) {
                try {
                    val (c, m) = reconcile(room)
                    corrected += c
                    muted += m
                } catch (e: LiveKitAdminException) {
                    failedRooms++
                }
            }
            if (corrected > 0 || muted > 0) {
                logger.info { "conference audio policy: $corrected permission(s) corrected, $muted microphone(s) muted" }
            }
            if (failedRooms > 0) logger.warn { "conference audio policy: LiveKit failed for $failedRooms room(s)" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error { "conference audio policy: the reconciliation failed (${e::class.simpleName})" }
        }
    }

    /**
     * One room, under the room lock shared with `setRoomAudioPolicy`. The lock state is re-read from the DB AFTER the participant listing
     * and under that lock, so a moderator's unlock (or a lock-then-unlock) that happened after the room query of this tick is never undone.
     */
    private suspend fun reconcile(room: LockedRoom): Pair<Int, Int> =
        grants.withRoomLock(roomId = room.id) {
            val participants = liveKitAdminClient.listParticipants(room.livekitRoomName)
            val locked =
                transaction {
                    ConferenceRoomTable
                        .selectAll()
                        .where { (ConferenceRoomTable.id eq room.id) and ConferenceRoomTable.endedAt.isNull() }
                        .singleOrNull()
                        ?.let { !it[ConferenceRoomTable.participantsMayUnmute] }
                }
            when {
                locked == null -> {
                    grants.clearUnlockPending(room.id)
                    0 to 0
                }
                locked -> {
                    grants.clearUnlockPending(room.id)
                    enforceLock(room = room, participants = participants)
                }
                grants.isUnlockPending(room.id) -> healUnlock(room = room, participants = participants) to 0
                else -> 0 to 0
            }
        }

    /** Hands the microphone back to every non-moderator whose permission lacks it; clears the mark when nobody is left out. */
    private suspend fun healUnlock(
        room: LockedRoom,
        participants: List<LiveKitParticipantInfo>,
    ): Int {
        val memberIds = participants.mapNotNull { liveKitIdentityAsMemberId(it.identity) }
        val privileged = transaction { ConferenceAudioPolicy.privilegedAmong(memberIds) }
        var corrected = 0
        for (participant in participants) {
            val memberId = liveKitIdentityAsMemberId(participant.identity) ?: continue
            if (ConferenceAudioPolicy.isModeratorIdentity(
                    identity = memberId,
                    creatorId = room.creatorId,
                    privileged = privileged,
                )
            ) {
                continue
            }
            if (mayPublishMicrophone(participant)) continue
            // Only grants the permission, never unmutes: the person decides when to switch on.
            liveKitAdminClient.updateParticipantPermission(
                room = room.livekitRoomName,
                identity = participant.identity,
                permission = conferencePermission(microphoneAllowed = true),
            )
            corrected++
        }
        grants.clearUnlockPending(room.id)
        return corrected
    }

    private fun mayPublishMicrophone(participant: LiveKitParticipantInfo): Boolean {
        val permission = participant.permission
        return permission != null &&
            permission.canPublish &&
            (
                permission.canPublishSources.isEmpty() ||
                    permission.canPublishSources.any { it.equals(LIVEKIT_MICROPHONE_SOURCE, ignoreCase = true) }
            )
    }

    private suspend fun enforceLock(
        room: LockedRoom,
        participants: List<LiveKitParticipantInfo>,
    ): Pair<Int, Int> {
        val memberIds = participants.mapNotNull { liveKitIdentityAsMemberId(it.identity) }
        val privileged = transaction { ConferenceAudioPolicy.privilegedAmong(memberIds) }
        var corrected = 0
        var muted = 0
        for (participant in participants) {
            val memberId = liveKitIdentityAsMemberId(participant.identity) ?: continue
            if (ConferenceAudioPolicy.isModeratorIdentity(
                    identity = memberId,
                    creatorId = room.creatorId,
                    privileged = privileged,
                )
            ) {
                continue
            }
            if (grants.isGranted(roomId = room.id, memberId = memberId)) continue
            if (mayPublishMicrophone(participant)) {
                liveKitAdminClient.updateParticipantPermission(
                    room = room.livekitRoomName,
                    identity = participant.identity,
                    permission = conferencePermission(microphoneAllowed = false),
                )
                corrected++
            }
            participant.tracks
                .filter { it.source.equals(LIVEKIT_MICROPHONE_SOURCE, ignoreCase = true) && !it.muted }
                .forEach {
                    liveKitAdminClient.muteMicrophone(room = room.livekitRoomName, identity = participant.identity, trackSid = it.sid)
                    muted++
                }
        }
        return corrected to muted
    }
}
