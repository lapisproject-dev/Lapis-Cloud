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
import network.lapis.cloud.server.conference.MAX_SEND_DATA_DESTINATIONS
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EncounterSpaceRoleTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.ENCOUNTER_MAP_TOPIC
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.encounterMapPayload
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

// Privacy rule of this file (Art. 9 GDPR): the logs name COUNTS only -- never a member id, identity, room, name, initials or coordinate.
private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.166 -- delivers the map packets and keeps the map state honest.
 *
 * - **Every [tickMs]**: [EncounterMapState.drain], then one LiveKit `sendDataTo` per chunk of recipients (never `sendData` to everybody, never to
 *   a guest: the recipients are the registered viewers, and only an ACTIVE member who is present is ever registered). Runs OUTSIDE every
 *   transaction and every monitor, best effort: a failed delivery is only counted, the clients repair a missed packet through the sequence number.
 * - **Every [reconcileEvery] ticks** ([reconcileFromDb]): re-derives, per tracked session, who is still allowed to stand on the map from the
 *   database (session open? map switched on? present? status ACTIVE? no office?). This is the safety net for everything the in-memory hooks cannot
 *   see, above all a status change DURING a session (ACTIVE becomes FRIEND): the person leaves the map within one reconcile interval, i.e. within
 *   five seconds at most -- that window is documented, not hidden.
 *
 * Never throws. If LiveKit is unreachable only a warning with a COUNT is logged.
 */
class EncounterMapBroadcaster(
    private val liveKit: LiveKitAdminClient,
    private val state: EncounterMapState,
    private val liveKitEnabled: Boolean,
    private val tickMs: Long = 200,
    private val reconcileEvery: Int = 25,
    private val reconcile: suspend () -> Unit = { reconcileFromDbInto(state) },
) {
    private var job: Job? = null
    private var ticks = 0

    fun start() {
        if (!liveKitEnabled || job?.isActive == true) return
        job =
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                while (isActive) {
                    tick()
                    delay(tickMs.milliseconds)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** One run: deliver what changed, and every [reconcileEvery]-th run reconcile with the database. Never throws. */
    suspend fun tick() {
        if (!liveKitEnabled) return
        try {
            var failed = 0
            for (pending in state.drain()) {
                val bytes = encounterMapPayload(pending.packet).toByteArray(Charsets.UTF_8)
                for (chunk in pending.recipients.chunked(MAX_SEND_DATA_DESTINATIONS)) {
                    try {
                        liveKit.sendDataTo(
                            room = pending.livekitRoom,
                            topic = ENCOUNTER_MAP_TOPIC,
                            payload = bytes,
                            destinationIdentities = chunk,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                    }
                }
            }
            if (failed > 0) logger.warn { "encounter map: $failed deliveries failed" }
            ticks++
            if (ticks % reconcileEvery == 0) reconcile()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "encounter map: a run failed (${e::class.simpleName})" }
        }
    }

    /** One reconcile run against the database (also callable by tests). */
    internal fun reconcileFromDb() = reconcileFromDbInto(state)

    companion object {
        /**
         * For every tracked session: closed room / switched-off map / ended session clears the session (viewers get a reset); otherwise
         * [EncounterMapState.retain] with walkers = present ACTIVE members without an office and viewers = present ACTIVE members.
         */
        internal fun reconcileFromDbInto(state: EncounterMapState) {
            for (sessionRoomId in state.sessionsForReconcile()) {
                val verdict = transaction { verdictOf(sessionRoomId) }
                if (verdict == null) {
                    state.clear(sessionRoomId)
                } else {
                    state.retain(sessionRoomId = sessionRoomId, walkers = verdict.walkers, viewers = verdict.viewers)
                }
            }
        }

        private class Verdict(
            val walkers: Set<Uuid>,
            val viewers: Set<Uuid>,
        )

        private fun verdictOf(sessionRoomId: Uuid): Verdict? {
            val room = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq sessionRoomId }.singleOrNull() ?: return null
            if (room[ConferenceRoomTable.endedAt] != null) return null
            val spaceId = room[ConferenceRoomTable.encounterSpaceId] ?: return null
            val space = EncounterSpaceTable.selectAll().where { EncounterSpaceTable.id eq spaceId }.singleOrNull() ?: return null
            if (space[EncounterSpaceTable.archivedAt] != null || !space[EncounterSpaceTable.mapEnabled]) return null
            val present =
                (ConferenceParticipationTable innerJoin MemberTable)
                    .selectAll()
                    .where { ConferenceParticipationTable.roomId eq sessionRoomId }
                    .filter { it[MemberTable.status] == MemberStatus.ACTIVE }
                    .map { it[ConferenceParticipationTable.memberId] }
                    .toSet()
            val officers =
                (EncounterSpaceRoleTable innerJoin MemberTable)
                    .selectAll()
                    .where { (EncounterSpaceRoleTable.spaceId eq spaceId) and (MemberTable.status eq MemberStatus.ACTIVE) }
                    .map { it[EncounterSpaceRoleTable.memberId] }
                    .toSet()
            return Verdict(walkers = present - officers, viewers = present)
        }
    }
}
