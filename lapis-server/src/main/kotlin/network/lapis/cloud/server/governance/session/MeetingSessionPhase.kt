package network.lapis.cloud.server.governance.session

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.MeetingSessionPhase
import network.lapis.cloud.shared.domain.MeetingStatus

/**
 * V1.9.236 -- THE derivation of a Sitzung's phase in its conference room. Nothing is stored: `HELD` and `CANCELLED` are [MeetingSessionPhase.CLOSED];
 * a `PLANNED` Sitzung is [MeetingSessionPhase.NOT_OPENED] until `meeting.opened_at` is set, and [MeetingSessionPhase.OPEN] from then on.
 */
internal fun meetingSessionPhase(
    status: MeetingStatus,
    openedAt: LocalDateTime?,
): MeetingSessionPhase =
    when (status) {
        MeetingStatus.HELD, MeetingStatus.CANCELLED -> MeetingSessionPhase.CLOSED
        MeetingStatus.PLANNED -> if (openedAt == null) MeetingSessionPhase.NOT_OPENED else MeetingSessionPhase.OPEN
    }
