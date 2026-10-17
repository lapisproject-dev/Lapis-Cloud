package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventConferenceStatus
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.113 -- how an event's online conference follows the event's own lifecycle. Every function runs INSIDE the caller's
 * transaction (it only touches `event_conference` rows -- no LiveKit call -- so it works with conferencing unconfigured, too).
 */
internal object EventOnlineLifecycle {
    /**
     * Call after an event row was written by `updateEvent`/a series edit, with the values just stored. A conference that is running
     * ([EventConferenceStatus.ACTIVE]) vetoes a change of the online kind ([ConflictException]: the participants are in the room) and any
     * change that TIGHTENS who may take part (visibility away from PUBLIC, a new or changed registration deadline) -- the people already
     * connected (account-less guests, GUEST/FRIEND accounts) keep valid tokens and open LiveKit connections, so the veto is the only
     * safe answer; loosening changes stay allowed. A later
     * end of the event moves the window end later; a changed window of a not-running conference (PENDING/FAILED/ENDED) deletes its row so
     * the next first join provisions it afresh (the old `conference_room` stays behind as history).
     */
    fun onEventSaved(
        eventId: Uuid,
        kind: EventOnlineKind,
        startsAt: LocalDateTime,
        endsAt: LocalDateTime,
        previousVisibility: EventVisibility,
        newVisibility: EventVisibility,
        previousRegistrationClosesAt: LocalDateTime?,
        newRegistrationClosesAt: LocalDateTime?,
    ) {
        val row = EventConferenceStore.get(eventId)
        // V1.9.156: the release of recording / streaming belongs to a Lapis conference only. An event that is no conference (any more) must not keep a
        // dormant release that would silently apply again if the kind is switched back. (A running conference vetoes the kind change below.)
        if (kind != EventOnlineKind.LAPIS_CONFERENCE && row?.get(EventConferenceTable.status) != EventConferenceStatus.ACTIVE) {
            EventTable.update({ EventTable.id eq eventId }) { it[captureAllowed] = false }
        }
        if (row == null) return
        if (row[EventConferenceTable.status] == EventConferenceStatus.ACTIVE &&
            accessTightened(
                previousVisibility = previousVisibility,
                newVisibility = newVisibility,
                previousRegistrationClosesAt = previousRegistrationClosesAt,
                newRegistrationClosesAt = newRegistrationClosesAt,
            )
        ) {
            throw ConflictException(
                "Die Videokonferenz dieser Veranstaltung läuft bereits -- Sichtbarkeit und Anmeldeschluss können erst nach ihrem Ende eingeschränkt werden.",
            )
        }
        if (kind != EventOnlineKind.LAPIS_CONFERENCE) {
            if (row[EventConferenceTable.status] == EventConferenceStatus.ACTIVE) {
                throw ConflictException(
                    "Die Videokonferenz dieser Veranstaltung läuft bereits -- die Art der Online-Teilnahme kann erst nach ihrem Ende geändert werden.",
                )
            }
            EventConferenceStore.onEventSaved(eventId = eventId, newWindow = null)
            return
        }
        EventConferenceStore.onEventSaved(
            eventId = eventId,
            newWindow = EventOnlineWindow.of(startsAt = startsAt, endsAt = endsAt, orgZone = OrganizationTimeZone.current()),
        )
    }

    private fun accessTightened(
        previousVisibility: EventVisibility,
        newVisibility: EventVisibility,
        previousRegistrationClosesAt: LocalDateTime?,
        newRegistrationClosesAt: LocalDateTime?,
    ): Boolean =
        (previousVisibility == EventVisibility.PUBLIC && newVisibility != EventVisibility.PUBLIC) ||
            (newRegistrationClosesAt != null && newRegistrationClosesAt != previousRegistrationClosesAt)

    /** Hard deleting an event (series edit): refused while its conference runs; otherwise the bookkeeping row goes first (foreign key). */
    fun beforeEventDeleted(eventId: Uuid) {
        val row = EventConferenceStore.get(eventId) ?: return
        if (row[EventConferenceTable.status] == EventConferenceStatus.ACTIVE) {
            throw ConflictException(
                "Die Videokonferenz dieser Veranstaltung läuft bereits -- sie kann erst nach ihrem Ende gelöscht werden.",
            )
        }
        EventConferenceStore.deleteFor(eventId)
    }
}
