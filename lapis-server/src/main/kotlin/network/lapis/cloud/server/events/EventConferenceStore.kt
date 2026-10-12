package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventConferenceStatus
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.113 -- Exposed access to `event_conference`. Like every `*Store` here it never opens a transaction of its own (the caller
 * does) and runs only typed query builders. **`event_conference` is written by [EventConferenceProvisioner] and by the event lifecycle
 * hooks in `EventService` (through [onEventSaved]) -- nowhere else.** `status`/`conference_room_id` change ONLY with the fencing
 * `claim_token` condition ([activate], [markFailed]) so a superseded claimer can never overwrite the winner.
 */
internal object EventConferenceStore {
    fun get(eventId: Uuid): ResultRow? = EventConferenceTable.selectAll().where { EventConferenceTable.eventId eq eventId }.singleOrNull()

    fun insertPending(
        eventId: Uuid,
        claimToken: Uuid,
        livekitRoomName: String,
        now: LocalDateTime,
        window: EventOnlineWindow.Window,
    ) {
        EventConferenceTable.insert {
            it[EventConferenceTable.eventId] = eventId
            it[conferenceRoomId] = null
            it[EventConferenceTable.livekitRoomName] = livekitRoomName
            it[status] = EventConferenceStatus.PENDING
            it[EventConferenceTable.claimToken] = claimToken
            it[claimedAt] = now
            it[windowOpensAt] = EventOnlineWindow.toUtc(window.opensAt)
            it[windowClosesAt] = EventOnlineWindow.toUtc(window.closesAt)
            it[createdAt] = now
            it[endedAt] = null
        }
    }

    /**
     * Conditional take-over of a FAILED / abandoned PENDING row: only the caller whose `UPDATE ... WHERE status = <seen> AND claim_token =
     * <seen>` touches exactly one row owns the claim. Returns `true` for the winner.
     */
    fun takeOver(
        eventId: Uuid,
        seenStatus: String,
        seenClaimToken: Uuid,
        newClaimToken: Uuid,
        newLivekitRoomName: String,
        now: LocalDateTime,
        window: EventOnlineWindow.Window,
    ): Boolean =
        EventConferenceTable.update({
            (EventConferenceTable.eventId eq eventId) and
                (EventConferenceTable.status eq seenStatus) and
                (EventConferenceTable.claimToken eq seenClaimToken)
        }) {
            it[status] = EventConferenceStatus.PENDING
            it[claimToken] = newClaimToken
            it[claimedAt] = now
            it[livekitRoomName] = newLivekitRoomName
            it[conferenceRoomId] = null
            it[windowOpensAt] = EventOnlineWindow.toUtc(window.opensAt)
            it[windowClosesAt] = EventOnlineWindow.toUtc(window.closesAt)
        } == 1

    /** Fenced: `true` iff [claimToken] is still the current claim of a PENDING row (otherwise the claimer was superseded). */
    fun activate(
        eventId: Uuid,
        claimToken: Uuid,
        conferenceRoomId: Uuid,
    ): Boolean =
        EventConferenceTable.update({
            (EventConferenceTable.eventId eq eventId) and
                (EventConferenceTable.claimToken eq claimToken) and
                (EventConferenceTable.status eq EventConferenceStatus.PENDING)
        }) {
            it[status] = EventConferenceStatus.ACTIVE
            it[EventConferenceTable.conferenceRoomId] = conferenceRoomId
        } == 1

    /** Fenced like [activate]. */
    fun markFailed(
        eventId: Uuid,
        claimToken: Uuid,
    ): Boolean =
        EventConferenceTable.update({
            (EventConferenceTable.eventId eq eventId) and
                (EventConferenceTable.claimToken eq claimToken) and
                (EventConferenceTable.status eq EventConferenceStatus.PENDING)
        }) {
            it[status] = EventConferenceStatus.FAILED
        } == 1

    fun markEnded(
        eventId: Uuid,
        now: LocalDateTime,
    ) {
        EventConferenceTable.update({
            (EventConferenceTable.eventId eq eventId) and (EventConferenceTable.status neq EventConferenceStatus.ENDED)
        }) {
            it[status] = EventConferenceStatus.ENDED
            it[endedAt] = now
        }
    }

    /** ACTIVE conferences whose window has closed (the poller ends them), oldest first. */
    fun listActiveClosedBefore(
        cutoff: LocalDateTime,
        limit: Int,
    ): List<ResultRow> =
        EventConferenceTable
            .selectAll()
            .where { (EventConferenceTable.status eq EventConferenceStatus.ACTIVE) and (EventConferenceTable.windowClosesAt less cutoff) }
            .orderBy(EventConferenceTable.windowClosesAt)
            .limit(limit)
            .toList()

    /** Every ACTIVE conference (the poller checks whether a moderator ended its room early). Bounded. */
    fun listActive(limit: Int): List<ResultRow> =
        EventConferenceTable
            .selectAll()
            .where { EventConferenceTable.status eq EventConferenceStatus.ACTIVE }
            .limit(limit)
            .toList()

    /** PENDING/FAILED rows whose window has closed: they never became a room and now never will. Returns the number ended. */
    fun endOrphansClosedBefore(
        cutoff: LocalDateTime,
        now: LocalDateTime,
    ): Int =
        EventConferenceTable.update({
            (EventConferenceTable.status inList listOf(EventConferenceStatus.PENDING, EventConferenceStatus.FAILED)) and
                (EventConferenceTable.windowClosesAt less cutoff)
        }) {
            it[status] = EventConferenceStatus.ENDED
            it[endedAt] = now
        }

    /**
     * Hook of `EventService.updateEvent`/series edits (called INSIDE the caller's transaction, after the event row was updated): keeps
     * the stored window in step with a rescheduled event. An ACTIVE conference only has its end moved LATER (a later end
     * extends the window; an earlier one never cuts a running meeting short). A conference that is not ACTIVE (PENDING / FAILED /
     * ENDED) and whose window differs from the new one is deleted, so the next first join provisions it afresh. The ended
     * `conference_room` row stays behind as history.
     */
    fun onEventSaved(
        eventId: Uuid,
        newWindow: EventOnlineWindow.Window?,
    ) {
        val row = get(eventId) ?: return
        if (newWindow == null) {
            // The event no longer has a Lapis conference (kind changed). An ACTIVE one was already refused by requireKindChangeAllowed.
            deleteUnlessActive(eventId)
            return
        }
        val newOpens = EventOnlineWindow.toUtc(newWindow.opensAt)
        val newCloses = EventOnlineWindow.toUtc(newWindow.closesAt)
        if (row[EventConferenceTable.status] == EventConferenceStatus.ACTIVE) {
            if (newCloses > row[EventConferenceTable.windowClosesAt]) {
                EventConferenceTable.update({ EventConferenceTable.eventId eq eventId }) { it[windowClosesAt] = newCloses }
            }
            return
        }
        if (row[EventConferenceTable.windowOpensAt] != newOpens || row[EventConferenceTable.windowClosesAt] != newCloses) {
            // Conditional delete: a first join may have committed ACTIVE since the unlocked read above; that row must survive.
            EventConferenceTable.deleteWhere {
                (EventConferenceTable.eventId eq eventId) and (EventConferenceTable.status neq EventConferenceStatus.ACTIVE)
            }
        }
    }

    /**
     * Deletes the bookkeeping row unless it is (or concurrently became) ACTIVE -- the status condition is re-evaluated by the database at
     * DELETE time, so a room that went live between the caller's unlocked veto read and this statement is never orphaned. A surviving
     * ACTIVE row is reported as a [ConflictException].
     */
    fun deleteUnlessActive(eventId: Uuid) {
        EventConferenceTable.deleteWhere {
            (EventConferenceTable.eventId eq eventId) and (EventConferenceTable.status neq EventConferenceStatus.ACTIVE)
        }
        if (get(eventId) != null) {
            throw ConflictException(
                "Die Videokonferenz dieser Veranstaltung läuft bereits -- sie kann erst nach ihrem Ende geändert oder gelöscht werden.",
            )
        }
    }

    fun deleteFor(eventId: Uuid) = deleteUnlessActive(eventId)
}
