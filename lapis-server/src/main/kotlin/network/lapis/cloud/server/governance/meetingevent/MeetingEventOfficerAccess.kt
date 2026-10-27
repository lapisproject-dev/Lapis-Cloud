package network.lapis.cloud.server.governance.meetingevent

import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.events.EventPolicy
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.MeetingEventGrant
import network.lapis.cloud.server.security.meetingEventGrant
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MeetingEventField
import network.lapis.cloud.shared.rpc.MeetingEventCancelReservedException
import network.lapis.cloud.shared.rpc.MeetingEventFieldReservedException
import network.lapis.cloud.shared.rpc.MeetingEventVisibilityReservedException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** The rows an officer's write path works on, both locked, plus the grant derived from them. */
internal data class OfficerLockedEvent(
    val grant: MeetingEventGrant.Officer,
    val meeting: ResultRow,
    val event: ResultRow,
)

/**
 * V1.9.229 -- the officer path of the event RPCs: an officer (Vorsitz, Schriftfuehrung, ...) of a committee manages THE event announcing one of
 * that committee's Sitzungen, within an allow-list, and nothing else. The grant is always derived from `event.meeting_id` of the LOCKED event row
 * and the committee of the LOCKED Sitzung row -- never from anything the client sent.
 *
 * Every refusal of the lookup (unknown id, not linked, link changed meanwhile, foreign committee, office ended) is the SAME [NotFoundException]
 * an unknown id gets, byte for byte, so the response reveals neither that an event exists nor why access fails.
 *
 * Lock order: Sitzung row, then event row (the order of every Sitzung <-> event operation); the audit entry stays the last locking step.
 */
internal object MeetingEventOfficerAccess {
    /** Read path (`getEvent`, DTO flags): unlocked; event row -> Sitzung row -> grant. */
    fun grantFor(
        current: CurrentMember,
        eventRow: ResultRow,
    ): MeetingEventGrant {
        val meetingId = eventRow[EventTable.meetingId]
        val committeeId =
            meetingId?.let { id ->
                MeetingTable
                    .selectAll()
                    .where { MeetingTable.id eq id }
                    .singleOrNull()
                    ?.get(MeetingTable.committeeId)
            }
        return current.meetingEventGrant(eventMeetingId = meetingId, meetingCommitteeId = committeeId)
    }

    /** Write path; see the class KDoc. Must run inside the caller's transaction. */
    fun lockForOfficer(
        current: CurrentMember,
        eventId: Uuid,
    ): OfficerLockedEvent {
        val notFound = { NotFoundException("Event $eventId not found") }
        val meetingId = EventStore.getEventOrNull(eventId)?.get(EventTable.meetingId) ?: throw notFound()
        val meeting =
            MeetingTable
                .selectAll()
                .where { MeetingTable.id eq meetingId }
                .forUpdate()
                .singleOrNull() ?: throw notFound()
        val event = EventStore.lockEventForUpdate(eventId) ?: throw notFound()
        if (event[EventTable.meetingId] != meetingId) throw notFound()
        val grant =
            current.meetingEventGrant(eventMeetingId = meetingId, meetingCommitteeId = meeting[MeetingTable.committeeId])
                as? MeetingEventGrant.Officer ?: throw notFound()
        return OfficerLockedEvent(grant = grant, meeting = meeting, event = event)
    }

    /**
     * The `updateEvent` allow-list. [normalized] is the already normalized input ([EventPolicy.normalizedOnline]). Returns the changed fields
     * (names only, for the audit entry); throws the typed refusals.
     */
    fun checkOfficerUpdate(
        existing: ResultRow,
        normalized: EventInput,
    ): List<MeetingEventField> {
        requireMembersOnly(existing)
        if (normalized.visibility != EventVisibility.MEMBERS_ONLY) throw MeetingEventVisibilityReservedException()

        fun reserved(): Nothing = throw MeetingEventFieldReservedException()
        if (normalized.capacity != existing[EventTable.capacity]) reserved()
        if (normalized.feeAmount.compareTo(existing[EventTable.feeAmount]) != 0) reserved()
        if (normalized.feeCurrency != existing[EventTable.feeCurrency]) reserved()
        if (normalized.registrationClosesAt != existing[EventTable.registrationClosesAt]) reserved()
        if (normalized.roomId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { parseUuidOrNull(it) } != existing[EventTable.roomId]
        ) {
            reserved()
        }
        if (EventPolicy.normalizedSummary(normalized.summary) != EventPolicy.normalizedSummary(existing[EventTable.summary])) reserved()
        if (EventPolicy.normalizedCoverImageAlt(normalized.coverImageAlt) !=
            EventPolicy.normalizedCoverImageAlt(existing[EventTable.coverImageAlt])
        ) {
            reserved()
        }
        if (normalized.onlineUrlPublic != existing[EventTable.onlineUrlPublic]) reserved()
        val oldKind = existing[EventTable.onlineKind]
        if (normalized.onlineKind == EventOnlineKind.LAPIS_ENCOUNTER && oldKind != EventOnlineKind.LAPIS_ENCOUNTER) reserved()
        if (normalized.onlineKind == EventOnlineKind.LAPIS_ENCOUNTER &&
            normalized.encounterSpaceId?.let { parseUuidOrNull(it) } != existing[EventTable.encounterSpaceId]
        ) {
            reserved()
        }
        val oldUrl = existing[EventTable.onlineUrl]
        val newUrl = normalized.onlineUrl?.trim()?.takeIf { it.isNotBlank() }
        // A URL the board published must not be swapped for another one by an officer.
        if (existing[EventTable.onlineUrlPublic] && newUrl != oldUrl) reserved()

        val changed = mutableListOf<MeetingEventField>()
        if (normalized.title.trim() != existing[EventTable.title]) changed += MeetingEventField.TITLE
        if (normalized.description.trim() != existing[EventTable.description]) changed += MeetingEventField.DESCRIPTION
        if (normalized.startsAt != existing[EventTable.startsAt]) changed += MeetingEventField.STARTS_AT
        if (normalized.endsAt != existing[EventTable.endsAt]) changed += MeetingEventField.ENDS_AT
        if (normalized.locationText?.trim()?.takeIf { it.isNotBlank() } !=
            existing[EventTable.locationText]
        ) {
            changed += MeetingEventField.LOCATION
        }
        if (normalized.onlineKind != oldKind) changed += MeetingEventField.ONLINE_KIND
        if (newUrl != oldUrl) changed += MeetingEventField.ONLINE_URL
        return changed
    }

    /** An officer never touches a public event (and never makes one public). */
    fun requireMembersOnly(existing: ResultRow) {
        if (existing[EventTable.visibility] == EventVisibility.PUBLIC) throw MeetingEventVisibilityReservedException()
    }

    fun requireFreeForCancel(existing: ResultRow) {
        if (existing[EventTable.feeAmount].compareTo(BigDecimal.ZERO) != 0) throw MeetingEventCancelReservedException()
    }

    private fun parseUuidOrNull(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()
}
