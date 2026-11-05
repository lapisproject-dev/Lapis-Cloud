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
import network.lapis.cloud.server.db.generated.EventConferenceTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// Privacy rule of this file: the logs name counts and exception classes only -- never an identity, a display name or a room id.
private val logger = KotlinLogging.logger {}

/**
 * V1.9.282 -- what makes "an event guest may not share the screen unless a moderator allowed exactly this session" real, whatever the token
 * of the guest says. Runs every [intervalSeconds], never throws, and only looks at ACTIVE event rooms (bound through `event_conference`, no
 * encounter session). Per room, under [ConferenceAudioGrantState.withRoomLock] (the lock every whole replacement of a guest permission uses):
 *
 *  1. lists the participants and drops a grant whose participant sid is gone (the guest left or reconnected);
 *  2. for every `evg-` participant (never the egress) WITHOUT a valid grant (registry entry with exactly this sid):
 *     - replaces the permission if it lists a screen share source or is empty (= every source, which is what a guest token minted before
 *       V1.9.282 carries while the microphone is allowed) by [guestConferencePermission] without the screen share -- this also covers guest
 *       sessions that are still running with such an old token at the deploy, and an empty registry after a restart (fail closed);
 *     - mutes every screen share track that is switched on. A mute is no hard boundary (LiveKit has no server-side unpublish; a modified
 *       client can switch the track on again), so a track that is switched on AGAIN after our mute gets its guest removed from the room.
 *
 * The microphone is never changed here (it keeps whatever the guest has right now).
 */
class EventGuestScreenShareReconciler(
    private val liveKitAdminClient: LiveKitAdminClient,
    private val grants: ConferenceAudioGrantState,
    private val screenShareGrants: EventGuestScreenShareGrants,
    private val liveKitEnabled: Boolean,
    private val intervalSeconds: Long = 5,
) {
    private var job: Job? = null

    /** Track sids of screen shares this reconciler muted; bounded by pruning to the sids seen in the latest pass. */
    private val mutedByUs = HashSet<String>()

    /** Participant sids whose permission was corrected although LiveKit did not report one (so it is done once, not every tick). */
    private val correctedUnknown = HashSet<String>()

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

    private data class EventRoom(
        val id: Uuid,
        val livekitRoomName: String,
    )

    private class Counts {
        var corrected = 0
        var muted = 0
        var removed = 0
        var failed = 0
    }

    /** One full run. Never throws (except for coroutine cancellation). */
    suspend fun tick() {
        if (!liveKitEnabled) return
        try {
            val (activeIds, rooms) =
                transaction {
                    val active =
                        ConferenceRoomTable
                            .selectAll()
                            .where { ConferenceRoomTable.endedAt.isNull() }
                            .map { it[ConferenceRoomTable.id] }
                            .toSet()
                    val bound =
                        EventConferenceTable
                            .selectAll()
                            .mapNotNull { it[EventConferenceTable.conferenceRoomId] }
                            .filter { it in active }
                    val eventRooms =
                        if (bound.isEmpty()) {
                            emptyList()
                        } else {
                            ConferenceRoomTable
                                .selectAll()
                                .where { (ConferenceRoomTable.id inList bound) and ConferenceRoomTable.encounterSpaceId.isNull() }
                                .map {
                                    EventRoom(
                                        id = it[ConferenceRoomTable.id],
                                        livekitRoomName = it[ConferenceRoomTable.livekitRoomName],
                                    )
                                }
                        }
                    active to eventRooms
                }
            screenShareGrants.pruneInactiveRooms(activeIds)
            val counts = Counts()
            val seenTracks = HashSet<String>()
            val seenParticipants = HashSet<String>()
            var failedRooms = 0
            for (room in rooms) {
                try {
                    reconcile(room = room, counts = counts, seenTracks = seenTracks, seenParticipants = seenParticipants)
                } catch (e: LiveKitAdminException) {
                    failedRooms++
                }
            }
            if (failedRooms == 0 && counts.failed == 0) {
                mutedByUs.retainAll(seenTracks)
                correctedUnknown.retainAll(seenParticipants)
            }
            if (counts.corrected > 0 || counts.muted > 0 || counts.removed > 0) {
                logger.info {
                    "event guest screen share: ${counts.corrected} permission(s) corrected, ${counts.muted} track(s) muted, ${counts.removed} guest(s) removed"
                }
            }
            if (counts.failed > 0) logger.warn { "event guest screen share: LiveKit failed for ${counts.failed} guest(s)" }
            if (failedRooms > 0) logger.warn { "event guest screen share: LiveKit failed for $failedRooms room(s)" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error { "event guest screen share: the reconciliation failed (${e::class.simpleName})" }
        }
    }

    private suspend fun reconcile(
        room: EventRoom,
        counts: Counts,
        seenTracks: MutableSet<String>,
        seenParticipants: MutableSet<String>,
    ) {
        grants.withRoomLock(roomId = room.id) {
            val participants = liveKitAdminClient.listParticipants(room.livekitRoomName)
            screenShareGrants.dropIfAbsent(roomId = room.id, presentSids = participants.map { it.sid }.toSet())
            for (participant in participants) {
                if (!isEventGuestIdentity(participant.identity) || participant.kind == LIVEKIT_KIND_EGRESS) continue
                seenParticipants += participant.sid
                participant.tracks.forEach { seenTracks += it.sid }
                if (screenShareGrants.isGranted(
                        roomId = room.id,
                        identity = participant.identity,
                        participantSid = participant.sid,
                    )
                ) {
                    continue
                }
                try {
                    reconcileGuest(room = room, guest = participant, counts = counts)
                } catch (e: LiveKitAdminException) {
                    // one guest must not keep the others of the room from being corrected; the next tick retries
                    counts.failed++
                }
            }
        }
    }

    private suspend fun reconcileGuest(
        room: EventRoom,
        guest: LiveKitParticipantInfo,
        counts: Counts,
    ) {
        val permission = guest.permission
        val needsCorrection =
            if (permission == null) {
                guest.sid !in correctedUnknown
            } else {
                permission.canPublishSources.isEmpty() ||
                    permission.canPublishSources.any { it.uppercase() in LIVEKIT_SCREEN_SHARE_SOURCES }
            }
        if (needsCorrection) {
            liveKitAdminClient.updateParticipantPermission(
                room = room.livekitRoomName,
                identity = guest.identity,
                permission = guestConferencePermission(microphoneAllowed = guest.mayPublishMicrophoneNow(), screenShareGranted = false),
            )
            if (permission == null) correctedUnknown += guest.sid
            counts.corrected++
        }
        val open = guest.openScreenShareTracks()
        if (open.any { it.sid in mutedByUs }) {
            liveKitAdminClient.removeParticipant(room = room.livekitRoomName, identity = guest.identity)
            open.forEach { mutedByUs -= it.sid }
            counts.removed++
            return
        }
        for (track in open) {
            liveKitAdminClient.muteScreenShareTrack(room = room.livekitRoomName, identity = guest.identity, trackSid = track.sid)
            mutedByUs += track.sid
            counts.muted++
        }
    }
}
