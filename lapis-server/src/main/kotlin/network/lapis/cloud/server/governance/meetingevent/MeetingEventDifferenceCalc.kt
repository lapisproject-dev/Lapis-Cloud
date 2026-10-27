package network.lapis.cloud.server.governance.meetingevent

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.MeetingEventDifference
import network.lapis.cloud.shared.domain.MeetingFormat
import java.time.temporal.ChronoUnit

/**
 * V1.9.227 -- pure comparison of a Sitzung and the Veranstaltung announcing it. No DB access.
 *
 * Start: compared to the minute. Location: `trim().lowercase()`, `null` and blank are the same. Online: a Sitzung is "online" for the formats
 * ONLINE and HYBRID, an event for any [EventOnlineKind] other than NONE.
 *
 * [MeetingEventDifference.alignable] is the precondition of `alignEventToMeeting`: something the alignment can change differs (the start, or a
 * location when the Sitzung HAS one -- a blank Sitzung location never overwrites the event's), the Sitzung is not in the past (the event
 * rules reject a start in the past) and the event is not cancelled.
 */
internal object MeetingEventDifferenceCalc {
    fun compute(
        meetingScheduledAt: LocalDateTime,
        meetingLocation: String?,
        meetingFormat: MeetingFormat,
        eventStartsAt: LocalDateTime,
        eventLocation: String?,
        eventOnlineKind: EventOnlineKind,
        eventStatus: EventStatus,
        wallNow: LocalDateTime,
    ): MeetingEventDifference {
        val startsAtDiffers = minute(meetingScheduledAt) != minute(eventStartsAt)
        val meetingLoc = normalizedLocation(meetingLocation)
        val locationDiffers = meetingLoc != normalizedLocation(eventLocation)
        val meetingOnline = meetingFormat == MeetingFormat.ONLINE || meetingFormat == MeetingFormat.HYBRID
        val onlineDiffers = meetingOnline != (eventOnlineKind != EventOnlineKind.NONE)
        val alignable =
            (startsAtDiffers || (locationDiffers && meetingLoc.isNotEmpty())) &&
                meetingScheduledAt >= wallNow &&
                eventStatus != EventStatus.CANCELLED
        return MeetingEventDifference(
            startsAtDiffers = startsAtDiffers,
            locationDiffers = locationDiffers,
            onlineDiffers = onlineDiffers,
            alignable = alignable,
        )
    }

    private fun minute(value: LocalDateTime): java.time.LocalDateTime = value.toJavaLocalDateTime().truncatedTo(ChronoUnit.MINUTES)

    private fun normalizedLocation(raw: String?): String = raw?.trim()?.lowercase().orEmpty()
}
