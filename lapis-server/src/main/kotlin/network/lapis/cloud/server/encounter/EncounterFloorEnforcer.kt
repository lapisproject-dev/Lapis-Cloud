package network.lapis.cloud.server.encounter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import network.lapis.cloud.server.conference.encounterFloorPermission
import network.lapis.cloud.shared.domain.ENCOUNTER_FLOOR_TOPIC
import network.lapis.cloud.shared.domain.EncounterFloorSignal
import network.lapis.cloud.shared.domain.encounterFloorPayload
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// Privacy rule of this file (Art. 9 GDPR): the logs say WHAT failed, never for whom or in which room.
private val logger = KotlinLogging.logger {}

/** Why a floor ended -- only steers the handling, never stored or logged. */
enum class RevokeReason { EXPIRED, ENDED, LEFT, MODERATED, VIOLATION }

/**
 * V1.9.138 -- the clock and the safety net of the temporary floor. The grant itself is made by `EncounterSpaceService.grantFloor`; this
 * class makes sure it **ends**: every [tickMillis] it lets expired floors lapse, retries a revoke that failed, and every
 * [verifyEverySeconds] checks against LiveKit that the holder is still there, still limited to the microphone and still allowed to publish
 * (a LiveKit reconnect with the old token resets the permission to "listen only"; the grant is then re-applied while it is valid).
 *
 * **Revoke ladder** ([revoke]): (1) the grant stops being valid at once, "off" goes to everybody; (2) up to [retryDelaysMillis].size
 * attempts of `can_publish = false` (sources stay `MICROPHONE`) plus muting every unmuted microphone track; (3) if all fail, the person is
 * disconnected; (4) if even that fails the grant stays in the state as "revoking" and [tick] tries again every [pendingRetryMillis]. There is
 * no path on which a grant disappears from the state without a successful demotion or disconnect (a restart or the end of the session
 * excepted -- then the poller disconnects every publisher that is not an office holder).
 *
 * Every LiveKit call is made OUTSIDE a transaction; this class has no database access at all.
 */
class EncounterFloorEnforcer(
    private val liveKit: LiveKitAdminClient,
    private val floorState: EncounterFloorState,
    private val liveKitEnabled: Boolean,
    private val tickMillis: Long = 1_000,
    private val verifyEverySeconds: Int = 15,
    private val retryDelaysMillis: List<Long> = listOf(0, 1_000, 2_000),
    private val pendingRetryMillis: Long = 5_000,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private var job: Job? = null

    /** Idempotent. */
    fun start() {
        if (job?.isActive == true) return
        job =
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                while (isActive) {
                    tick()
                    delay(tickMillis.milliseconds)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** One run. Never throws (except for cancellation). */
    suspend fun tick() {
        if (!liveKitEnabled) return
        for ((roomId, grant) in floorState.sessionsWithGrant()) {
            try {
                when {
                    grant.revoking -> retryPendingRevoke(roomId = roomId, grant = grant)
                    floorState.remainingSeconds(
                        grant,
                    ) <= 0 -> revoke(roomId = roomId, memberId = grant.memberId, reason = RevokeReason.EXPIRED)
                    grant.lastVerifiedAt.elapsedNow() >= verifyEverySeconds.seconds -> verify(roomId = roomId, grant = grant)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn { "encounter floor: a run failed (${e::class.simpleName})" }
            }
        }
    }

    /** Raises the ONE live connection of [memberId] to microphone-only publishing. Throws [LiveKitAdminException]. */
    suspend fun applyGrant(
        livekitRoomName: String,
        memberId: Uuid,
    ) {
        liveKit.updateParticipantPermission(
            room = livekitRoomName,
            identity = memberId.toString(),
            permission = encounterFloorPermission(granted = true, canPublishData = true),
        )
    }

    /** Sends the floor state: `grant == null` = "off" to everybody; otherwise "on" to everybody and "you" to the holder. Best effort. */
    suspend fun broadcast(
        livekitRoomName: String,
        grant: EncounterFloorState.Grant?,
    ) {
        if (grant == null) {
            send(livekitRoomName = livekitRoomName, signal = EncounterFloorSignal.Off, to = null)
            return
        }
        val remaining = floorState.remainingSeconds(grant)
        // "you" first: the holder's client keeps "I have the floor" when the broadcast "on" follows.
        send(livekitRoomName = livekitRoomName, signal = EncounterFloorSignal.You(remaining), to = grant.memberId)
        send(livekitRoomName = livekitRoomName, signal = EncounterFloorSignal.On(remaining), to = null)
    }

    /** Ends the floor of [memberId] iff they hold it now; otherwise nothing happens. Never call it while holding the session lock. */
    suspend fun endForMember(
        roomId: Uuid,
        memberId: Uuid,
        reason: RevokeReason,
    ) {
        if (floorState.current(roomId)?.memberId == memberId) revoke(roomId = roomId, memberId = memberId, reason = reason)
    }

    /** Takes the floor away from [memberId] (see the class KDoc for the ladder). Idempotent; never call it while holding the session lock. */
    suspend fun revoke(
        roomId: Uuid,
        memberId: Uuid,
        reason: RevokeReason,
    ) {
        val grant =
            floorState.withSessionLock(roomId = roomId) {
                val g = floorState.current(roomId)
                if (g == null || g.memberId != memberId) null else floorState.markRevoking(roomId)
            } ?: return
        // From here on the grant is no longer valid for the poller; the people in the room learn it at once.
        send(livekitRoomName = grant.livekitRoomName, signal = EncounterFloorSignal.Off, to = null)
        runRevokeLadder(roomId = roomId, grant = grant, reason = reason)
    }

    private suspend fun retryPendingRevoke(
        roomId: Uuid,
        grant: EncounterFloorState.Grant,
    ) {
        val last = grant.lastRevokeAttemptAt
        if (last != null && last.elapsedNow() < pendingRetryMillis.milliseconds) return
        runRevokeLadder(roomId = roomId, grant = grant, reason = RevokeReason.VIOLATION)
    }

    private suspend fun runRevokeLadder(
        roomId: Uuid,
        grant: EncounterFloorState.Grant,
        reason: RevokeReason,
    ) {
        for (delayMillis in retryDelaysMillis) {
            if (delayMillis > 0) sleeper(delayMillis)
            if (demote(grant)) {
                finish(roomId = roomId, grant = grant)
                return
            }
        }
        // The demotion keeps failing: disconnect the person (their client reconnects with a listen-only token).
        try {
            liveKit.removeParticipant(room = grant.livekitRoomName, identity = grant.memberId.toString())
            finish(roomId = roomId, grant = grant)
        } catch (e: LiveKitAdminException) {
            floorState.noteRevokeAttempt(roomId = roomId, serial = grant.serial)
            logger.warn { "encounter floor: a floor could not be taken away (${reason.name}) -- retrying" }
        }
    }

    /** One demotion attempt: `can_publish = false` and every unmuted microphone track muted. `true` if the person is demoted or not there any more. */
    private suspend fun demote(grant: EncounterFloorState.Grant): Boolean =
        try {
            val identity = grant.memberId.toString()
            val present = liveKit.listParticipants(grant.livekitRoomName).firstOrNull { it.identity == identity }
            if (present == null) {
                true
            } else {
                liveKit.updateParticipantPermission(
                    room = grant.livekitRoomName,
                    identity = identity,
                    permission = encounterFloorPermission(granted = false, canPublishData = true),
                )
                present.tracks.filter { it.source == "MICROPHONE" && !it.muted }.forEach {
                    liveKit.muteMicrophone(room = grant.livekitRoomName, identity = identity, trackSid = it.sid)
                }
                true
            }
        } catch (e: LiveKitAdminException) {
            false
        }

    private suspend fun finish(
        roomId: Uuid,
        grant: EncounterFloorState.Grant,
    ) {
        floorState.withSessionLock(roomId = roomId) { floorState.remove(roomId = roomId, serial = grant.serial) }
        // A second "off" after the removal: a client that missed the first one still learns that the floor is over.
        send(livekitRoomName = grant.livekitRoomName, signal = EncounterFloorSignal.Off, to = null)
    }

    /** The periodic check of a valid grant against what LiveKit really reports. */
    private suspend fun verify(
        roomId: Uuid,
        grant: EncounterFloorState.Grant,
    ) {
        val participants =
            try {
                liveKit.listParticipants(grant.livekitRoomName)
            } catch (e: LiveKitAdminException) {
                logger.warn { "encounter floor: LiveKit is not reachable -- skipping the check" }
                return
            }
        val holder = participants.firstOrNull { it.identity == grant.memberId.toString() }
        if (holder == null) {
            val absent = floorState.recordPresence(roomId = roomId, serial = grant.serial, present = false)
            if (absent >= ABSENT_CHECKS_TO_END) revoke(roomId = roomId, memberId = grant.memberId, reason = RevokeReason.LEFT)
            return
        }
        floorState.recordPresence(roomId = roomId, serial = grant.serial, present = true)
        if (violates(holder)) {
            revoke(roomId = roomId, memberId = grant.memberId, reason = RevokeReason.VIOLATION)
            try {
                liveKit.removeParticipant(room = grant.livekitRoomName, identity = grant.memberId.toString())
            } catch (e: LiveKitAdminException) {
                logger.warn { "encounter floor: a violating participant could not be disconnected" }
            }
            return
        }
        if (holder.permission?.canPublish != true) {
            // The snapshot may be stale: a revoke can have run since. Re-check under the session lock (revoke() marks "revoking" under the
            // same lock), so a demotion is never undone by a re-apply.
            try {
                floorState.withSessionLock(roomId = roomId) {
                    val now = floorState.current(roomId)
                    if (now != null && now.serial == grant.serial && !now.revoking && floorState.remainingSeconds(now) > 0) {
                        applyGrant(livekitRoomName = grant.livekitRoomName, memberId = grant.memberId)
                    }
                }
            } catch (e: LiveKitAdminException) {
                logger.warn { "encounter floor: the permission could not be re-applied" }
            }
        }
        floorState.current(roomId)?.takeIf { it.serial == grant.serial && !it.revoking }?.let {
            broadcast(livekitRoomName = it.livekitRoomName, grant = it)
        }
    }

    /** More than one track, or anything but a microphone, while the floor is granted. */
    private fun violates(p: LiveKitParticipantInfo): Boolean =
        p.tracks.size > 1 || p.tracks.any { it.type != "AUDIO" || it.source != "MICROPHONE" }

    private suspend fun send(
        livekitRoomName: String,
        signal: EncounterFloorSignal,
        to: Uuid?,
    ) {
        val payload = encounterFloorPayload(signal).encodeToByteArray()
        try {
            if (to == null) {
                liveKit.sendData(room = livekitRoomName, topic = ENCOUNTER_FLOOR_TOPIC, payload = payload)
            } else {
                liveKit.sendDataTo(
                    room = livekitRoomName,
                    topic = ENCOUNTER_FLOOR_TOPIC,
                    payload = payload,
                    destinationIdentities = listOf(to.toString()),
                )
            }
        } catch (e: LiveKitAdminException) {
            logger.debug { "encounter floor: a packet could not be sent" }
        }
    }

    private companion object {
        /** Two checks in a row without the holder end the floor (one missed check may be a short reconnect). */
        const val ABSENT_CHECKS_TO_END = 2
    }
}
