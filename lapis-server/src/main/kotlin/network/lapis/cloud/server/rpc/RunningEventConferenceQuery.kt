package network.lapis.cloud.server.rpc

import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EventConferenceStatus
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.events.EventMemberVisibility
import network.lapis.cloud.server.events.EventOnlineCaller
import network.lapis.cloud.server.events.EventOnlineWindow
import network.lapis.cloud.server.events.decideEventOnlineAccess
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.EventConferenceParticipation
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.domain.EventOnlinePhase
import network.lapis.cloud.shared.domain.RunningEventConferenceDto
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant

/** V1.9.295 -- hard cap on the rows [RunningEventConferenceQuery.list] reads (also the SQL `limit`). */
internal const val MAX_RUNNING_EVENT_CONFERENCES = 50

/**
 * V1.9.295 -- read-only query behind `IConferenceService.listRunningEventConferences`.
 *
 * Selects ONLY the event columns [decideEventOnlineAccess] and the DTO need; `livekit_room_name`, `claim_token` and the conference room id
 * are never read. `FORBIDDEN` is mapped to "registration required" -- that holds only because the SQL pre-filters on PUBLISHED,
 * LAPIS_CONFERENCE and the caller's ACTIVE membership (checked by the service), so a registration is the one remaining cause.
 * The time window is checked FIRST because [decideEventOnlineAccess] tests the registration before the window: an unregistered member
 * would otherwise get FORBIDDEN instead of ENDED for an event that is over. The SQL additionally keeps only rows whose stored window has
 * not closed yet, so finished events awaiting the poller do not use up the limit. Rows are dropped after the `limit`, so fewer than
 * [MAX_RUNNING_EVENT_CONFERENCES] rows can come back. Must run inside a transaction.
 */
internal object RunningEventConferenceQuery {
    fun list(
        current: CurrentMember,
        now: Instant,
        orgZone: TimeZone,
    ): List<RunningEventConferenceDto> =
        EventConferenceTable
            .innerJoin(EventTable)
            .innerJoin(ConferenceRoomTable)
            .select(
                EventTable.id,
                EventTable.title,
                EventTable.status,
                EventTable.onlineKind,
                EventTable.visibility,
                EventTable.registrationClosesAt,
                EventTable.createdBy,
                EventTable.startsAt,
                EventTable.endsAt,
            ).where {
                (EventConferenceTable.status eq EventConferenceStatus.ACTIVE) and
                    ConferenceRoomTable.endedAt.isNull() and
                    EventMemberVisibility.visibleWithoutManageScopePredicate() and
                    (EventTable.onlineKind eq EventOnlineKind.LAPIS_CONFERENCE) and
                    // an event that is over but whose row the poller has not ended yet must not eat the 50 slots (stored window = UTC copy)
                    (EventConferenceTable.windowClosesAt greaterEq EventOnlineWindow.toUtc(now))
            }.orderBy(EventTable.startsAt to SortOrder.ASC, EventTable.title to SortOrder.ASC, EventTable.id to SortOrder.ASC)
            .limit(MAX_RUNNING_EVENT_CONFERENCES)
            .mapNotNull { row ->
                val startsAt = row[EventTable.startsAt]
                val endsAt = row[EventTable.endsAt]
                val window = EventOnlineWindow.of(startsAt = startsAt, endsAt = endsAt, orgZone = orgZone)
                if (EventOnlineWindow.phase(window = window, now = now) == EventOnlinePhase.ENDED) return@mapNotNull null
                val participation =
                    when (
                        decideEventOnlineAccess(
                            eventRow = row,
                            caller = EventOnlineCaller.Session(current),
                            now = now,
                            orgZone = orgZone,
                        ).outcome
                    ) {
                        EventOnlineOutcome.JOINED -> EventConferenceParticipation.OPEN_TO_YOU
                        EventOnlineOutcome.NOT_YET -> EventConferenceParticipation.NOT_YET_OPEN
                        EventOnlineOutcome.FORBIDDEN -> EventConferenceParticipation.REGISTRATION_REQUIRED
                        EventOnlineOutcome.ENDED,
                        EventOnlineOutcome.FULL,
                        EventOnlineOutcome.PREPARING,
                        EventOnlineOutcome.UNAVAILABLE,
                        -> null
                    } ?: return@mapNotNull null
                RunningEventConferenceDto(
                    eventId = row[EventTable.id].toString(),
                    eventTitle = row[EventTable.title],
                    startsAt = startsAt,
                    endsAt = endsAt,
                    participation = participation,
                )
            }
}
