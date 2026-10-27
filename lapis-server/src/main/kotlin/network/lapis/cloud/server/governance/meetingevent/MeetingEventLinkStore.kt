package network.lapis.cloud.server.governance.meetingevent

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.canRecordForMeeting
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.LinkedEventRef
import network.lapis.cloud.shared.domain.LinkedMeetingRef
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingEventDifference
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** The neutral title a caller sees for a not-yet-published event they did not create the link for. */
internal const val MEETING_EVENT_DRAFT_PLACEHOLDER_TITLE = "Veranstaltung (Entwurf)"

private val EVENT_MANAGE_ROLES = setOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * V1.9.227 -- data access of the Sitzung <-> Veranstaltung link and THE ONLY WRITER of `event.meeting_id` (a tripwire test pins that; neither
 * `EventStore.insertEvent` nor `EventStore.updateEvent` ever touches the column, so editing an event can never move or erase a link).
 *
 * Like every `*Store` it never opens a transaction of its own (the callers do) and uses typed query builders only. Lock order for every caller:
 * the Sitzung row first, the event row second, the audit entry last.
 */
internal object MeetingEventLinkStore {
    /** The id of the event announcing [meetingId], or `null`. */
    fun eventIdForMeeting(meetingId: Uuid): Uuid? =
        EventTable
            .selectAll()
            .where { EventTable.meetingId eq meetingId }
            .singleOrNull()
            ?.get(EventTable.id)

    fun setLink(
        eventId: Uuid,
        meetingId: Uuid,
    ) {
        EventTable.update({ EventTable.id eq eventId }) { it[EventTable.meetingId] = meetingId }
    }

    /** Dissolves the link of [eventId]; returns the meeting it pointed to, or `null` if there was none. */
    fun clearLinkOfEvent(eventId: Uuid): Uuid? {
        val previous =
            EventTable
                .selectAll()
                .where { EventTable.id eq eventId }
                .singleOrNull()
                ?.get(EventTable.meetingId) ?: return null
        EventTable.update({ EventTable.id eq eventId }) { it[meetingId] = null }
        return previous
    }

    /** Dissolves the link of [meetingId]; returns the event it pointed to, or `null` if there was none. */
    fun clearLinkOfMeeting(meetingId: Uuid): Uuid? {
        val eventId = eventIdForMeeting(meetingId) ?: return null
        EventTable.update({ EventTable.id eq eventId }) { it[EventTable.meetingId] = null }
        return eventId
    }

    /** Batch variant for lists (no N+1): the link of every meeting in [meetings] that has one, keyed by meeting id. */
    fun linkedEventRefs(
        meetings: List<MeetingDto>,
        caller: CurrentMember,
        wallNow: LocalDateTime,
    ): Map<String, LinkedEventRef> {
        if (meetings.isEmpty()) return emptyMap()
        val byId = meetings.associateBy { Uuid.parse(it.id) }
        val events = EventTable.selectAll().where { EventTable.meetingId inList byId.keys.toList() }.toList()
        val canRecordCache = HashMap<String, Boolean>()
        return events.associate { row ->
            val meeting = byId.getValue(checkNotNull(row[EventTable.meetingId]))
            val canRecord = canRecordCache.getOrPut(meeting.committeeId) { caller.canRecordForMeeting(Uuid.parse(meeting.committeeId)) }
            meeting.id to refFor(row = row, meeting = meeting, caller = caller, canRecord = canRecord, wallNow = wallNow)
        }
    }

    fun linkedEventRef(
        eventRow: ResultRow,
        meeting: MeetingDto,
        caller: CurrentMember,
        wallNow: LocalDateTime,
    ): LinkedEventRef =
        refFor(
            row = eventRow,
            meeting = meeting,
            caller = caller,
            canRecord = caller.canRecordForMeeting(Uuid.parse(meeting.committeeId)),
            wallNow = wallNow,
        )

    private fun refFor(
        row: ResultRow,
        meeting: MeetingDto,
        caller: CurrentMember,
        canRecord: Boolean,
        wallNow: LocalDateTime,
    ): LinkedEventRef {
        val status = row[EventTable.status]
        val isManager = caller.role in EVENT_MANAGE_ROLES
        // V1.9.229: an officer of this Sitzung's committee opens (and manages) its event, DRAFT or not.
        val canOpen = isManager || status == EventStatus.PUBLISHED || canRecord
        // Full detail for whoever may open the event or may manage the link (the officers who created it); everybody else gets the neutral shape,
        // so an unpublished event's title and data stay hidden until it is published.
        if (!(canOpen || canRecord)) {
            return LinkedEventRef(
                eventId = row[EventTable.id].toString(),
                title = MEETING_EVENT_DRAFT_PLACEHOLDER_TITLE,
                status = status,
                startsAt = meeting.scheduledAt,
                endsAt = meeting.scheduledAt,
                locationText = null,
                onlineKind = network.lapis.cloud.shared.domain.EventOnlineKind.NONE,
                canOpen = false,
                difference = null,
                canPublish = false,
            )
        }
        return LinkedEventRef(
            eventId = row[EventTable.id].toString(),
            title = row[EventTable.title],
            status = status,
            startsAt = row[EventTable.startsAt],
            endsAt = row[EventTable.endsAt],
            locationText = row[EventTable.locationText],
            onlineKind = row[EventTable.onlineKind],
            canOpen = canOpen,
            canPublish =
                status == EventStatus.DRAFT && row[EventTable.visibility] == EventVisibility.MEMBERS_ONLY && (isManager || canRecord),
            difference =
                MeetingEventDifferenceCalc
                    .compute(
                        meetingScheduledAt = meeting.scheduledAt,
                        meetingLocation = meeting.location,
                        meetingFormat = meeting.format,
                        eventStartsAt = row[EventTable.startsAt],
                        eventLocation = row[EventTable.locationText],
                        eventOnlineKind = row[EventTable.onlineKind],
                        eventStatus = status,
                        wallNow = wallNow,
                    ).restrictAlign(mayAlign = mayAlignEvent(caller = caller, eventRow = row, canRecord = canRecord)),
        )
    }

    /**
     * The Sitzung linked to [eventRow] as the event page shows it, or `null` if there is none or the caller may neither manage events nor the
     * Sitzung's committee (the member view of events shows nothing of the link).
     */
    fun linkedMeetingRef(
        eventRow: ResultRow,
        caller: CurrentMember,
        wallNow: LocalDateTime,
    ): LinkedMeetingRef? {
        val meetingId = eventRow[EventTable.meetingId] ?: return null
        val meeting =
            (MeetingTable innerJoin CommitteeTable)
                .selectAll()
                .where { (MeetingTable.id eq meetingId) }
                .singleOrNull() ?: return null
        val committeeId = meeting[MeetingTable.committeeId]
        val canManage = caller.canRecordForMeeting(committeeId)
        if (!canManage && caller.role !in EVENT_MANAGE_ROLES) return null
        return LinkedMeetingRef(
            meetingId = meetingId.toString(),
            committeeName = meeting[CommitteeTable.name],
            scheduledAt = meeting[MeetingTable.scheduledAt],
            status = meeting[MeetingTable.status],
            canManageLink = canManage,
            difference =
                MeetingEventDifferenceCalc
                    .compute(
                        meetingScheduledAt = meeting[MeetingTable.scheduledAt],
                        meetingLocation = meeting[MeetingTable.location],
                        meetingFormat = meeting[MeetingTable.format],
                        eventStartsAt = eventRow[EventTable.startsAt],
                        eventLocation = eventRow[EventTable.locationText],
                        eventOnlineKind = eventRow[EventTable.onlineKind],
                        eventStatus = eventRow[EventTable.status],
                        wallNow = wallNow,
                    ).restrictAlign(mayAlign = mayAlignEvent(caller = caller, eventRow = eventRow, canRecord = canManage)),
        )
    }

    /**
     * Mirrors the bridge rule of `MeetingEventLinkOperations.align`: BOARD/ADMIN may re-date any event, an officer of the Sitzung's committee
     * any event that is not public. [MeetingEventDifference.alignable] must not promise an action the server would refuse with a 403.
     */
    private fun mayAlignEvent(
        caller: CurrentMember,
        eventRow: ResultRow,
        canRecord: Boolean,
    ): Boolean = caller.role in EVENT_MANAGE_ROLES || (canRecord && eventRow[EventTable.visibility] != EventVisibility.PUBLIC)

    private fun MeetingEventDifference.restrictAlign(mayAlign: Boolean): MeetingEventDifference {
        val permitted = alignable && mayAlign
        return copy(alignable = permitted)
    }
}
