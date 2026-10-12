package network.lapis.cloud.server.events

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EventConferenceStatus
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.rpc.ConferenceRoomCloser
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EVENT_CONFERENCE_DEFAULT_CAPACITY
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Privacy rule of this file: logs name counts and exception classes only -- never an event id, a room name, a title or a person.
private val logger = KotlinLogging.logger {}

private const val MAX_CONFERENCE_TITLE_LENGTH = 200

/** Smallest `empty_timeout` handed to LiveKit (the default of an ordinary room). */
private const val MIN_EMPTY_TIMEOUT_SECONDS = 300L

/** Largest `empty_timeout` handed to LiveKit -- an upper bound so a misconfigured window cannot pin a room forever. */
private val MAX_EMPTY_TIMEOUT: Duration = 24.hours

/** Thrown inside the activation transaction when the claimer was superseded; rolls the transaction back (the inserted room row is discarded). */
private class ClaimSupersededException : RuntimeException("event conference claim was superseded")

/**
 * Welle V1.9.113 -- the ONLY writer of `event_conference` rows and of the conference rooms that belong to events. Provisions the Lapis
 * video conference of an event on its FIRST online join, exactly once, also under concurrent first joins.
 *
 * ## The protocol (LiveKit is never called inside a `transaction {}`)
 *
 * 1. **Claim** (transaction): no row -> insert `PENDING` with a fresh `claim_token` (inside a savepoint; a unique violation means another
 *    request won -- read its row). A `FAILED` row, or a `PENDING` row older than [pendingTakeoverAfter] (a crashed claimer), is taken over
 *    with a conditional `UPDATE ... WHERE status = <seen> AND claim_token = <seen>`: only the request that updated exactly one row owns
 *    the claim. `ACTIVE` -> the room exists, return it. `ENDED` -> over.
 * 2. **Winner, after the claim committed**: create the LiveKit room, then (transaction) insert the `conference_room` row and flip the claim
 *    to `ACTIVE` with the **fencing** condition `claim_token = <mine>`. If that touches no row the claimer was superseded: the
 *    transaction is rolled back and the room it created at LiveKit is deleted best effort. If LiveKit failed the claim becomes `FAILED`
 *    (again fenced), so the next join retries.
 * 3. **Loser that sees a fresh `PENDING`**: waits OUTSIDE any transaction ([maxWaits] x [retryDelay]) and re-reads, then reports
 *    [Ready.Preparing] -- the client simply asks again.
 * 4. **`ACTIVE`**: a room that LiveKit forgot because it stayed empty (`empty_timeout`) is recreated idempotently under the same name, so
 *    an empty room never ends in the middle of the window. A room a moderator ended through `endRoom` ends the event conference too.
 */
internal class EventConferenceProvisioner(
    private val config: ConferenceConfig,
    private val liveKit: LiveKitAdminClient,
    private val closer: ConferenceRoomCloser,
    private val retryDelay: Duration = 200.milliseconds,
    private val maxWaits: Int = 3,
    private val pendingTakeoverAfter: Duration = 30.seconds,
) {
    sealed interface Ready {
        /** The room exists and is open. */
        data class Room(
            val roomId: Uuid,
            val livekitRoomName: String,
        ) : Ready

        /** Another request is creating the room right now; ask again in a moment. */
        data object Preparing : Ready

        /** LiveKit could not create the room (the claim is `FAILED`, the next join retries). */
        data object Failed : Ready

        /** The conference is over. */
        data object Ended : Ready
    }

    private sealed interface Plan {
        data class Winner(
            val claimToken: Uuid,
            val livekitRoomName: String,
            val maxParticipants: Int,
            val title: String,
            val createdBy: Uuid,
            val publicEvent: Boolean,
            val window: EventOnlineWindow.Window,
        ) : Plan

        data class Active(
            val roomId: Uuid,
            val livekitRoomName: String,
            val maxParticipants: Int,
            val window: EventOnlineWindow.Window,
        ) : Plan

        data object Pending : Plan

        data object Ended : Plan

        data object Gone : Plan
    }

    /** The number of seats of the event conference: the event's capacity (default 25), capped by the server-wide event ceiling. */
    fun derivedMaxParticipants(eventRow: ResultRow): Int =
        minOf(eventRow[EventTable.capacity] ?: EVENT_CONFERENCE_DEFAULT_CAPACITY, config.eventMaxParticipants).coerceAtLeast(2)

    suspend fun ensureRoom(
        eventId: Uuid,
        now: Instant,
    ): Ready {
        var waits = 0
        while (true) {
            when (val plan = transaction { claim(eventId = eventId, now = now) }) {
                Plan.Gone -> return Ready.Ended
                Plan.Ended -> return Ready.Ended
                is Plan.Active -> return ensureLiveKitRoom(plan = plan, now = now)
                is Plan.Winner -> return provision(eventId = eventId, plan = plan, now = now)
                Plan.Pending -> {
                    if (waits >= maxWaits) return Ready.Preparing
                    waits++
                    delay(retryDelay)
                }
            }
        }
    }

    private fun claim(
        eventId: Uuid,
        now: Instant,
    ): Plan {
        val eventRow = EventStore.getEventOrNull(eventId) ?: return Plan.Gone
        if (eventRow[EventTable.onlineKind] != EventOnlineKind.LAPIS_CONFERENCE) return Plan.Gone
        val window =
            EventOnlineWindow.of(
                startsAt = eventRow[EventTable.startsAt],
                endsAt = eventRow[EventTable.endsAt],
                orgZone = OrganizationTimeZone.current(),
            )
        val nowUtc = EventOnlineWindow.toUtc(now)
        var row = EventConferenceStore.get(eventId)
        if (row == null) {
            val token = Uuid.random()
            val name = newRoomName()
            val inserted =
                try {
                    withSavepoint(name = "event_conference_claim") {
                        EventConferenceStore.insertPending(
                            eventId = eventId,
                            claimToken = token,
                            livekitRoomName = name,
                            now = nowUtc,
                            window = window,
                        )
                    }
                    true
                } catch (e: Exception) {
                    if (!e.isUniqueViolation()) throw e
                    false
                }
            if (inserted) return winner(eventRow = eventRow, claimToken = token, name = name, window = window)
            row = EventConferenceStore.get(eventId) ?: return Plan.Pending
        }
        val status = row[EventConferenceTable.status]
        return when (status) {
            EventConferenceStatus.ENDED -> Plan.Ended
            EventConferenceStatus.ACTIVE -> activePlan(eventRow = eventRow, row = row, now = nowUtc, window = window)
            else -> {
                val abandoned =
                    status == EventConferenceStatus.PENDING &&
                        EventOnlineWindow.fromUtc(row[EventConferenceTable.claimedAt]) + pendingTakeoverAfter <= now
                if (status == EventConferenceStatus.FAILED || abandoned) {
                    val token = Uuid.random()
                    val name = newRoomName()
                    val won =
                        EventConferenceStore.takeOver(
                            eventId = eventId,
                            seenStatus = status,
                            seenClaimToken = row[EventConferenceTable.claimToken],
                            newClaimToken = token,
                            newLivekitRoomName = name,
                            now = nowUtc,
                            window = window,
                        )
                    if (won) winner(eventRow = eventRow, claimToken = token, name = name, window = window) else Plan.Pending
                } else {
                    Plan.Pending
                }
            }
        }
    }

    private fun winner(
        eventRow: ResultRow,
        claimToken: Uuid,
        name: String,
        window: EventOnlineWindow.Window,
    ): Plan =
        Plan.Winner(
            claimToken = claimToken,
            livekitRoomName = name,
            maxParticipants = derivedMaxParticipants(eventRow),
            title = eventRow[EventTable.title].take(MAX_CONFERENCE_TITLE_LENGTH),
            createdBy = eventRow[EventTable.createdBy],
            publicEvent = eventRow[EventTable.visibility] == EventVisibility.PUBLIC,
            window = window,
        )

    private fun activePlan(
        eventRow: ResultRow,
        row: ResultRow,
        now: LocalDateTime,
        window: EventOnlineWindow.Window,
    ): Plan {
        val roomId = row[EventConferenceTable.conferenceRoomId] ?: return Plan.Pending
        val roomRow = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.singleOrNull()
        if (roomRow == null || roomRow[ConferenceRoomTable.endedAt] != null) {
            // A moderator ended the room through endRoom (or it vanished): the event conference is over.
            EventConferenceStore.markEnded(eventId = row[EventConferenceTable.eventId], now = now)
            return Plan.Ended
        }
        return Plan.Active(
            roomId = roomId,
            livekitRoomName = roomRow[ConferenceRoomTable.livekitRoomName],
            maxParticipants = derivedMaxParticipants(eventRow),
            window = window,
        )
    }

    private suspend fun provision(
        eventId: Uuid,
        plan: Plan.Winner,
        now: Instant,
    ): Ready {
        try {
            liveKit.createRoom(
                name = plan.livekitRoomName,
                maxParticipants = plan.maxParticipants,
                emptyTimeoutSeconds = emptyTimeoutSeconds(window = plan.window, now = now),
            )
        } catch (e: LiveKitAdminException) {
            logger.warn { "event conference: LiveKit refused to create the room (${e::class.simpleName})" }
            transaction { EventConferenceStore.markFailed(eventId = eventId, claimToken = plan.claimToken) }
            return Ready.Failed
        }
        val roomId = Uuid.random()
        val activated =
            try {
                transaction {
                    ConferenceRoomTable.insert {
                        it[id] = roomId
                        it[title] = plan.title
                        it[description] = ""
                        it[livekitRoomName] = plan.livekitRoomName
                        it[createdByMemberId] = plan.createdBy
                        it[createdAt] = DbClock.nowLocalDateTime()
                        it[endedAt] = null
                        it[maxParticipants] = plan.maxParticipants
                        it[allowFederationGuests] = plan.publicEvent
                        it[muteOnJoin] = false
                        it[participantsMayUnmute] = true
                    }
                    if (!EventConferenceStore.activate(eventId = eventId, claimToken = plan.claimToken, conferenceRoomId = roomId)) {
                        throw ClaimSupersededException()
                    }
                    true
                }
            } catch (_: ClaimSupersededException) {
                false
            }
        if (!activated) {
            // Another claimer took over while this one was creating the room: discard the room created here (best effort) and report
            // that the conference is being prepared -- the winner's room is what everybody joins.
            runCatching { liveKit.deleteRoom(plan.livekitRoomName) }
            return Ready.Preparing
        }
        return Ready.Room(roomId = roomId, livekitRoomName = plan.livekitRoomName)
    }

    /** An empty LiveKit room is deleted after its `empty_timeout`; re-create it idempotently so a quiet room never ends inside the window. */
    private suspend fun ensureLiveKitRoom(
        plan: Plan.Active,
        now: Instant,
    ): Ready {
        val known = liveKit.listRooms().any { it.name == plan.livekitRoomName }
        if (!known) {
            liveKit.createRoom(
                name = plan.livekitRoomName,
                maxParticipants = plan.maxParticipants,
                emptyTimeoutSeconds = emptyTimeoutSeconds(window = plan.window, now = now),
            )
        }
        return Ready.Room(roomId = plan.roomId, livekitRoomName = plan.livekitRoomName)
    }

    /**
     * Ends every conference whose window has closed (and every one whose room a moderator already ended). One LiveKit failure leaves that
     * conference ACTIVE for the next sweep. Returns the number of conferences ended.
     */
    suspend fun endExpired(now: Instant): Int {
        val nowUtc = EventOnlineWindow.toUtc(now)
        var ended = 0
        val due = transaction { EventConferenceStore.listActiveClosedBefore(cutoff = nowUtc, limit = SWEEP_LIMIT) }
        for (row in due) ended += endActive(row = row, now = nowUtc)
        val active = transaction { EventConferenceStore.listActive(limit = SWEEP_LIMIT) }
        for (row in active) {
            if (due.any { it[EventConferenceTable.eventId] == row[EventConferenceTable.eventId] }) continue
            val roomId = row[EventConferenceTable.conferenceRoomId] ?: continue
            val roomEnded =
                transaction {
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.singleOrNull()?.get(
                        ConferenceRoomTable.endedAt,
                    ) != null
                }
            if (roomEnded) {
                transaction { EventConferenceStore.markEnded(eventId = row[EventConferenceTable.eventId], now = nowUtc) }
                ended++
            }
        }
        transaction { EventConferenceStore.endOrphansClosedBefore(cutoff = nowUtc, now = nowUtc) }
        return ended
    }

    /** Ends the conference of [eventId] right now (the event was cancelled). A no-op without a conference. */
    suspend fun endNow(
        eventId: Uuid,
        now: Instant,
    ) {
        val nowUtc = EventOnlineWindow.toUtc(now)
        val row = transaction { EventConferenceStore.get(eventId) } ?: return
        when (row[EventConferenceTable.status]) {
            EventConferenceStatus.ACTIVE -> endActive(row = row, now = nowUtc)
            EventConferenceStatus.ENDED -> Unit
            else -> transaction { EventConferenceStore.markEnded(eventId = eventId, now = nowUtc) }
        }
    }

    private suspend fun endActive(
        row: ResultRow,
        now: LocalDateTime,
    ): Int {
        val eventId = row[EventConferenceTable.eventId]
        val roomId = row[EventConferenceTable.conferenceRoomId]
        if (roomId == null) {
            transaction { EventConferenceStore.markEnded(eventId = eventId, now = now) }
            return 1
        }
        val roomRow = transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.singleOrNull() }
        try {
            if (roomRow != null && roomRow[ConferenceRoomTable.endedAt] == null) {
                closer.close(
                    roomId = roomId,
                    livekitRoomName = roomRow[ConferenceRoomTable.livekitRoomName],
                    now = now,
                    actorMemberId = roomRow[ConferenceRoomTable.createdByMemberId],
                    actorRole = AccountRole.MEMBER,
                )
            }
        } catch (e: LiveKitAdminException) {
            logger.warn { "event conference: LiveKit refused to close a room (${e::class.simpleName}) -- will retry" }
            return 0
        }
        transaction { EventConferenceStore.markEnded(eventId = eventId, now = now) }
        return 1
    }

    private fun newRoomName(): String = "lc-${Uuid.random()}"

    private fun emptyTimeoutSeconds(
        window: EventOnlineWindow.Window,
        now: Instant,
    ): Int {
        val remaining = window.closesAt - now
        return remaining
            .coerceIn(MIN_EMPTY_TIMEOUT_SECONDS.seconds, MAX_EMPTY_TIMEOUT)
            .inWholeSeconds
            .toInt()
    }

    companion object {
        private const val SWEEP_LIMIT = 100
    }
}
