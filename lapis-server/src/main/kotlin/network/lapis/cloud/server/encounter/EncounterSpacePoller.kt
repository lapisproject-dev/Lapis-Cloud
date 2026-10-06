package network.lapis.cloud.server.encounter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// Privacy rule of this file (Art. 9 GDPR): the logs name counts and exception classes only -- never a member id, identity, name or room id.
private val logger = KotlinLogging.logger {}

/** A session may not run longer than this; the poller closes it (a forgotten "open" must not keep a room and its presence alive for days). */
const val ENCOUNTER_MAX_SESSION_HOURS = 12L

/** A presence row that is not live at LiveKit is only deleted once it is older than this (the client needs a moment between token and connect). */
const val ENCOUNTER_PRESENCE_STALE_SECONDS = 120L

/** A session whose LiveKit room is missing is only closed once it is older than this (ListRooms may lag right after CreateRoom). */
const val ENCOUNTER_ROOM_MISSING_GRACE_SECONDS = 120L

/** How far back ended sessions are re-checked for a LiveKit room that survived a failed deletion. */
private const val ENDED_RECHECK_HOURS = 24L

private data class OpenSession(
    val roomId: Uuid,
    val spaceId: Uuid,
    val livekitRoomName: String,
    val createdAt: LocalDateTime,
)

/**
 * Welle V1.9.61 -- the safety net of the encounter spaces' data-protection promise ("no lasting trace of who attended"). Runs once
 * right at start and then every [intervalSeconds]; never relied on for the happy path (leave and close delete their own rows), only
 * for what a crashed client or a failed call leaves behind:
 *
 * - **(a)** deletes participation and legacy consent rows of every ENDED encounter session -- runs ALWAYS, even if LiveKit is down.
 * - **(b)** per open session: deletes the presence rows of people who are no longer connected at LiveKit (older than
 *   [ENCOUNTER_PRESENCE_STALE_SECONDS] AND absent in two consecutive sweeps, so a short reconnect gap does not cost a person their row); disconnects, with a still-valid token, blocked people who reconnected, silenced people who still hold the data grant, and live non-officers that hold publish rights (a withdrawn PULPIT/STEWARD).
 * - **(c)** closes a session that is older than [ENCOUNTER_MAX_SESSION_HOURS] (before any LiveKit call, so an outage cannot keep it
 *   open) or whose LiveKit room is gone -- audit entry
 *   `{"state":"CLOSED","reason":"EMPTY|MAX_DURATION"}` with `actorMemberId = null`.
 * - **(d)** retries the LiveKit room deletion of sessions that ended during the last 24 h and are still listed.
 *
 * If LiveKit is unreachable, (b), the room-gone part of (c) and (d) are skipped and only a warning WITHOUT ids is logged. Every LiveKit call is made OUTSIDE a
 * transaction. Idempotent and safe to run concurrently with the RPC service (the closing transaction re-checks under the locks, in the
 * same lock order as the service: space row first, then room row, audit last).
 */
class EncounterSpacePoller(
    private val liveKitAdminClient: LiveKitAdminClient,
    private val moderationState: EncounterModerationState,
    private val liveKitEnabled: Boolean,
    private val intervalSeconds: Long = 60,
    private val clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
) {
    private var job: Job? = null

    /** (room, member) pairs that were absent at LiveKit in the previous sweep; only touched by the sequential [tick]. */
    @Volatile private var absentInPreviousSweep: Set<Pair<Uuid, Uuid>> = emptySet()

    /** Idempotent. Runs one [tick] immediately, then every [intervalSeconds]. */
    fun start() {
        if (job?.isActive == true) return
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

    /** One full run. Never throws. */
    suspend fun tick() {
        try {
            val purged = transaction { EncounterSessionTeardown.purgeEndedSessions() }
            if (purged > 0) logger.info { "encounter poller: $purged orphaned presence row(s) of ended sessions deleted" }
        } catch (e: Exception) {
            logger.error { "encounter poller: the cleanup of ended sessions failed (${e::class.simpleName})" }
        }
        if (!liveKitEnabled) return
        try {
            closeExpiredSessions()
        } catch (e: Exception) {
            logger.error { "encounter poller: closing the expired sessions failed (${e::class.simpleName})" }
        }
        try {
            sweepLiveKit()
        } catch (e: LiveKitAdminException) {
            logger.warn { "encounter poller: LiveKit is not reachable -- skipping the live checks" }
        } catch (e: Exception) {
            logger.error { "encounter poller: the live checks failed (${e::class.simpleName})" }
        }
    }

    /**
     * (c, age part) Closes sessions older than [ENCOUNTER_MAX_SESSION_HOURS] in the database FIRST -- data protection beats availability,
     * so this must not wait for a reachable LiveKit. Only the room deletion needs LiveKit: it is tried best effort here and otherwise
     * retried by (d), because the ended room stays listed.
     */
    private suspend fun closeExpiredSessions() {
        val now = clock()
        val zone = ServerClock.zone
        transaction { loadOpenSessions() }
            .filter { now.toInstant(zone) - it.createdAt.toInstant(zone) > ENCOUNTER_MAX_SESSION_HOURS.hours }
            .forEach { session ->
                closeSession(session = session, reason = "MAX_DURATION", now = now)
                deleteLiveKitRoomBestEffort(session.livekitRoomName)
            }
    }

    private suspend fun sweepLiveKit() {
        val now = clock()
        val zone = ServerClock.zone
        val sessions = transaction { loadOpenSessions() }
        val endedRecently = transaction { loadRecentlyEndedRoomNames(now = now) }
        if (sessions.isEmpty() && endedRecently.isEmpty()) return
        val liveRooms = liveKitAdminClient.listRooms().map { it.name }.toSet()
        val absentThisSweep = mutableSetOf<Pair<Uuid, Uuid>>()

        sessions.forEach { session ->
            val age = now.toInstant(zone) - session.createdAt.toInstant(zone)
            when {
                // Already handled by closeExpiredSessions() before the LiveKit calls; a session that expired meanwhile waits for the next run.
                age > ENCOUNTER_MAX_SESSION_HOURS.hours -> Unit

                session.livekitRoomName !in liveRooms && age > ENCOUNTER_ROOM_MISSING_GRACE_SECONDS.seconds ->
                    closeSession(session = session, reason = "EMPTY", now = now)

                session.livekitRoomName in liveRooms -> reconcilePresence(session = session, now = now, absentThisSweep = absentThisSweep)
            }
        }
        absentInPreviousSweep = absentThisSweep
        // (d) A room that was ended in the database but is still listed: the earlier deletion failed.
        endedRecently.filter { it in liveRooms }.forEach { deleteLiveKitRoomBestEffort(it) }
    }

    private suspend fun reconcilePresence(
        session: OpenSession,
        now: LocalDateTime,
        absentThisSweep: MutableSet<Pair<Uuid, Uuid>>,
    ) {
        val participants = liveKitAdminClient.listParticipants(session.livekitRoomName)
        val live = participants.map { it.identity }.toSet()
        // A person who was removed but still holds a valid token can reconnect: disconnect again (the sweep period is the worst case).
        val toDisconnect = moderationState.blockedIdentities(session.roomId).filter { it in live }.toMutableSet()
        // A silenced person's old token still carries the data channel; a withdrawn office holder's old token still carries canPublish.
        // Compare what each live participant is actually allowed to do with what the server would grant now.
        val silenced = moderationState.silencedIdentities(session.roomId)
        val publishers = participants.filter { it.permission?.canPublish == true || it.tracks.isNotEmpty() }.map { it.identity }
        val officers =
            if (publishers.isEmpty()) {
                emptySet()
            } else {
                transaction {
                    publishers
                        .filter { identity ->
                            val memberId = runCatching { Uuid.parse(identity) }.getOrNull()
                            memberId != null && EncounterRoles.roleOf(spaceId = session.spaceId, memberId = memberId) != null
                        }.toSet()
                }
            }
        participants.forEach { p ->
            if (p.identity in silenced && p.permission?.canPublishData == true) toDisconnect += p.identity
            if (p.identity in publishers && p.identity !in officers) toDisconnect += p.identity
        }
        toDisconnect.forEach { identity ->
            try {
                liveKitAdminClient.removeParticipant(room = session.livekitRoomName, identity = identity)
            } catch (e: LiveKitAdminException) {
                logger.warn { "encounter poller: a participant could not be disconnected" }
            }
        }
        val cutoff = (now.toInstant(ServerClock.zone) - ENCOUNTER_PRESENCE_STALE_SECONDS.seconds).toLocalDateTime(ServerClock.zone)
        val liveIds = live.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
        // Two consecutive absent sweeps are required: livekit-client reconnects with its still-valid token WITHOUT calling enterSpace again,
        // so a single sweep that falls into a short network gap must not delete the row of a person who is about to be live again.
        val removed =
            transaction {
                val candidates =
                    ConferenceParticipationTable
                        .selectAll()
                        .where {
                            val stale =
                                (ConferenceParticipationTable.roomId eq session.roomId) and
                                    (ConferenceParticipationTable.joinedAt less cutoff)
                            if (liveIds.isEmpty()) stale else stale and (ConferenceParticipationTable.memberId notInList liveIds)
                        }.map { it[ConferenceParticipationTable.memberId] }
                val confirmed = candidates.filter { (session.roomId to it) in absentInPreviousSweep }
                candidates.forEach { absentThisSweep += session.roomId to it }
                if (confirmed.isEmpty()) {
                    0
                } else {
                    ConferenceParticipationTable.deleteWhere {
                        (ConferenceParticipationTable.roomId eq session.roomId) and
                            (ConferenceParticipationTable.joinedAt less cutoff) and
                            (ConferenceParticipationTable.memberId inList confirmed)
                    }
                }
            }
        if (removed > 0) logger.info { "encounter poller: $removed stale presence row(s) deleted" }
    }

    /** Closes [session] under the locks (space first, then room, audit last) iff it is still open; never throws into the sweep. */
    private fun closeSession(
        session: OpenSession,
        reason: String,
        now: LocalDateTime,
    ) {
        transaction {
            EncounterSpaceTable
                .selectAll()
                .where { EncounterSpaceTable.id eq session.spaceId }
                .forUpdate()
                .singleOrNull()
            val room =
                ConferenceRoomTable
                    .selectAll()
                    .where { ConferenceRoomTable.id eq session.roomId }
                    .forUpdate()
                    .singleOrNull()
            if (room != null && room[ConferenceRoomTable.endedAt] == null) {
                EncounterSessionTeardown.endSessionInTx(roomId = session.roomId, now = now)
                AuditLogRecorder.record(
                    actorMemberId = null,
                    actorRole = null,
                    entityType = AuditEntityType.ENCOUNTER_SPACE,
                    entityId = session.spaceId,
                    action = AuditAction.UPDATE,
                    after = """{"state":"CLOSED","reason":"$reason"}""",
                )
            }
        }
        moderationState.clear(session.roomId)
        logger.info { "encounter poller: a session was closed ($reason)" }
    }

    private suspend fun deleteLiveKitRoomBestEffort(livekitRoomName: String) {
        try {
            liveKitAdminClient.deleteRoom(livekitRoomName)
        } catch (e: LiveKitAdminException) {
            logger.warn { "encounter poller: a LiveKit room could not be deleted -- retried on the next run" }
        }
    }

    private fun loadOpenSessions(): List<OpenSession> =
        ConferenceRoomTable
            .selectAll()
            .where { ConferenceRoomTable.encounterSpaceId.isNotNull() and ConferenceRoomTable.endedAt.isNull() }
            .map {
                OpenSession(
                    roomId = it[ConferenceRoomTable.id],
                    spaceId = it[ConferenceRoomTable.encounterSpaceId]!!,
                    livekitRoomName = it[ConferenceRoomTable.livekitRoomName],
                    createdAt = it[ConferenceRoomTable.createdAt],
                )
            }

    private fun loadRecentlyEndedRoomNames(now: LocalDateTime): List<String> {
        val since = (now.toInstant(ServerClock.zone) - ENDED_RECHECK_HOURS.hours).toLocalDateTime(ServerClock.zone)
        return ConferenceRoomTable
            .selectAll()
            .where {
                ConferenceRoomTable.encounterSpaceId.isNotNull() and ConferenceRoomTable.endedAt.isNotNull() and
                    (ConferenceRoomTable.endedAt greater since)
            }.map { it[ConferenceRoomTable.livekitRoomName] }
    }
}
