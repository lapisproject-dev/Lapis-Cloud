package network.lapis.cloud.server.governance.meetingevent

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.events.EventOnlineLifecycle
import network.lapis.cloud.server.events.EventPolicy
import network.lapis.cloud.server.events.EventRoomCollisionGuard
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.toMeetingDto
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.activeRecordingRole
import network.lapis.cloud.server.security.canRecordForMeeting
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.LinkedEventRef
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingEventField
import network.lapis.cloud.shared.domain.MeetingEventLinkAuditKind
import network.lapis.cloud.shared.domain.MeetingEventUnlinkReason
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.EventAlreadyLinkedException
import network.lapis.cloud.shared.rpc.EventNotLinkableException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.MeetingAlreadyLinkedException
import network.lapis.cloud.shared.rpc.MeetingEventLinkStaleException
import network.lapis.cloud.shared.rpc.MeetingLocationMissingException
import network.lapis.cloud.shared.rpc.MeetingNotAnnounceableException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.time.Duration
import kotlin.uuid.Uuid

/** The fixed description of an announced Sitzung. NEVER the agenda, a title or a note of the Sitzung. `{committee}` is the committee's name. */
internal const val MEETING_EVENT_DESCRIPTION_TEMPLATE: String =
    "Sitzung des Gremiums {committee}. Die Einladung mit Tagesordnung erhalten die Mitglieder des Gremiums gesondert."

/** A Sitzung has no end of its own; its announcement runs this long. */
internal val MEETING_EVENT_DEFAULT_DURATION: Duration = Duration.ofHours(2)

private const val MAX_LINKABLE_MEETINGS = 200
private const val MAX_LINKABLE_CANDIDATES = 1000
private val EVENT_MANAGE_ROLES = setOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * V1.9.227 "Sitzung in der Videokonferenz" -- the five operations of the Sitzung <-> Veranstaltung link, called by `GovernanceService`.
 *
 * **Bridge rule (V1.9.229).** A committee officer (Vorsitz, Schriftfuehrung, ...) manages exactly the event announcing a Sitzung of THEIR committee:
 * they announce one (a MEMBERS_ONLY DRAFT; no parameter can change that -- the signatures carry ids only), re-date it, and unlink it. Editing,
 * publishing (to members) and cancelling (when free) go through `EventService`'s officer path ([MeetingEventOfficerAccess]). LINKING an existing event
 * is BOARD/ADMIN only, because a link would otherwise hand an officer the right to edit any event they can see; aligning or touching a PUBLIC
 * event stays with the board too.
 *
 * **Lock order** (every operation): the Sitzung row `FOR UPDATE`, then the event row `FOR UPDATE`, the audit entry last. `cancelEvent` locks the
 * event row only, so it cannot deadlock against this order. No operation touches a conference room or `conference_room.meeting_id`: an
 * event's room is created on its first join and is deliberately never a Sitzung room (`EventConferenceGuard`, V1.9.113).
 *
 * **DSGVO.** `event.meeting_id` is a reference between two records, not a personal datum; it needs no `PersonalData` contribution.
 */
internal class MeetingEventLinkOperations(
    private val conferencesEnabled: () -> Boolean,
    private val rateLimiter: FederationInboxRateLimiter,
) {
    fun announce(
        current: CurrentMember,
        meetingId: Uuid,
    ): LinkedEventRef {
        requireWithinRate(current)
        val now = DbClock.nowLocalDateTime()
        val wallNow = OrganizationTimeZone.wallNowOf(now)
        return transaction {
            val meeting = lockMeetingAndAuthorize(current = current, meetingId = meetingId)
            if (meeting[MeetingTable.status] != MeetingStatus.PLANNED || meeting[MeetingTable.scheduledAt] < wallNow) {
                throw MeetingNotAnnounceableException()
            }
            if (MeetingEventLinkStore.eventIdForMeeting(meetingId) != null) throw MeetingAlreadyLinkedException()
            val committeeName =
                CommitteeTable
                    .selectAll()
                    .where { CommitteeTable.id eq meeting[MeetingTable.committeeId] }
                    .single()[CommitteeTable.name]
            val input = announcementInput(meeting = meeting, committeeName = committeeName)
            EventPolicy.validate(input = input, wallNow = wallNow, existingStartsAt = null)
            val eventId = Uuid.random()
            val slug = EventPolicy.slugFor(title = input.title) { candidate -> EventStore.slugTaken(slug = candidate, excludingId = null) }
            try {
                EventStore.insertEvent(
                    id = eventId,
                    slug = slug,
                    title = input.title,
                    description = input.description,
                    locationText = input.locationText,
                    onlineUrl = null,
                    startsAt = input.startsAt,
                    endsAt = input.endsAt,
                    capacity = null,
                    feeAmount = BigDecimal.ZERO,
                    feeCurrency = "EUR",
                    visibility = EventVisibility.MEMBERS_ONLY,
                    registrationClosesAt = null,
                    createdAt = now,
                    createdBy = current.memberId,
                    status = EventStatus.DRAFT,
                    onlineKind = input.onlineKind,
                )
                MeetingEventLinkStore.setLink(eventId = eventId, meetingId = meetingId)
            } catch (e: org.jetbrains.exposed.v1.exceptions.ExposedSQLException) {
                if (e.isUniqueViolation()) throw MeetingAlreadyLinkedException()
                throw e
            }
            MeetingEventLinkAudit.record(
                actor = current,
                kind = MeetingEventLinkAuditKind.ANNOUNCED,
                meetingId = meetingId,
                eventId = eventId,
            )
            refOf(current = current, meetingId = meetingId, eventRow = EventStore.getEventOrThrow(eventId), wallNow = wallNow)
        }
    }

    fun link(
        current: CurrentMember,
        meetingId: Uuid,
        eventId: Uuid,
    ): LinkedEventRef {
        requireWithinRate(current)
        val wallNow = OrganizationTimeZone.wallNowOf(DbClock.nowLocalDateTime())
        return transaction {
            val meeting = lockMeetingAndAuthorize(current = current, meetingId = meetingId)
            // V1.9.229: linking an EXISTING event is BOARD/ADMIN only -- it would otherwise be a rights key (link a foreign public event, then edit it).
            if (current.role !in EVENT_MANAGE_ROLES) throw ForbiddenException()
            if (meeting[MeetingTable.status] == MeetingStatus.CANCELLED) throw MeetingNotAnnounceableException()
            val event = EventStore.lockEventForUpdate(eventId) ?: throw NotFoundException("Event $eventId not found")
            if (event[EventTable.status] == EventStatus.CANCELLED ||
                (event[EventTable.seriesId] != null && !event[EventTable.seriesDetached])
            ) {
                throw EventNotLinkableException()
            }
            if (event[EventTable.meetingId] != null) throw EventAlreadyLinkedException()
            if (MeetingEventLinkStore.eventIdForMeeting(meetingId) != null) throw MeetingAlreadyLinkedException()
            try {
                MeetingEventLinkStore.setLink(eventId = eventId, meetingId = meetingId)
            } catch (e: org.jetbrains.exposed.v1.exceptions.ExposedSQLException) {
                if (e.isUniqueViolation()) throw MeetingAlreadyLinkedException()
                throw e
            }
            MeetingEventLinkAudit.record(actor = current, kind = MeetingEventLinkAuditKind.LINKED, meetingId = meetingId, eventId = eventId)
            refOf(current = current, meetingId = meetingId, eventRow = EventStore.getEventOrThrow(eventId), wallNow = wallNow)
        }
    }

    fun unlink(
        current: CurrentMember,
        meetingId: Uuid,
        eventId: Uuid,
    ) {
        requireWithinRate(current)
        transaction {
            lockMeetingAndAuthorize(current = current, meetingId = meetingId)
            val event = EventStore.lockEventForUpdate(eventId) ?: throw MeetingEventLinkStaleException()
            if (event[EventTable.meetingId] != meetingId) throw MeetingEventLinkStaleException()
            MeetingEventLinkStore.clearLinkOfEvent(eventId)
            MeetingEventLinkAudit.record(
                actor = current,
                kind = MeetingEventLinkAuditKind.UNLINKED,
                meetingId = meetingId,
                eventId = eventId,
                reason = MeetingEventUnlinkReason.MANUAL,
            )
        }
    }

    fun align(
        current: CurrentMember,
        meetingId: Uuid,
        eventId: Uuid,
    ): LinkedEventRef {
        requireWithinRate(current)
        val wallNow = OrganizationTimeZone.wallNowOf(DbClock.nowLocalDateTime())
        return transaction {
            val meeting = lockMeetingAndAuthorize(current = current, meetingId = meetingId)
            val event = EventStore.lockEventForUpdate(eventId) ?: throw MeetingEventLinkStaleException()
            if (event[EventTable.meetingId] != meetingId) throw MeetingEventLinkStaleException()
            // V1.9.229: an officer of the Sitzung's committee (lockMeetingAndAuthorize + event.meeting_id == meetingId) re-dates the event of
            // THIS Sitzung -- but never a public one (the board's territory).
            val officerRole =
                if (current.role in EVENT_MANAGE_ROLES) {
                    null
                } else {
                    MeetingEventOfficerAccess.requireMembersOnly(event)
                    current.activeRecordingRole(meeting[MeetingTable.committeeId]) ?: throw ForbiddenException()
                }
            val difference =
                MeetingEventDifferenceCalc.compute(
                    meetingScheduledAt = meeting[MeetingTable.scheduledAt],
                    meetingLocation = meeting[MeetingTable.location],
                    meetingFormat = meeting[MeetingTable.format],
                    eventStartsAt = event[EventTable.startsAt],
                    eventLocation = event[EventTable.locationText],
                    eventOnlineKind = event[EventTable.onlineKind],
                    eventStatus = event[EventTable.status],
                    wallNow = wallNow,
                )
            if (!difference.alignable) throw MeetingNotAnnounceableException()

            val oldStart = event[EventTable.startsAt]
            val newStart = meeting[MeetingTable.scheduledAt]
            val changedFields = mutableListOf<MeetingEventField>()
            var newEnd = event[EventTable.endsAt]
            if (difference.startsAtDiffers) {
                val duration = Duration.between(oldStart.toJavaLocalDateTime(), newEnd.toJavaLocalDateTime())
                newEnd = newStart.toJavaLocalDateTime().plus(duration).toKotlinLocalDateTime()
                changedFields += MeetingEventField.STARTS_AT
                if (newEnd != event[EventTable.endsAt]) changedFields += MeetingEventField.ENDS_AT
            }
            val meetingLocation = meeting[MeetingTable.location]?.trim()?.takeIf { it.isNotBlank() }
            var newLocation = event[EventTable.locationText]
            if (difference.locationDiffers && meetingLocation != null) {
                newLocation = meetingLocation
                changedFields += MeetingEventField.LOCATION
            }
            val newStartValue = if (difference.startsAtDiffers) newStart else oldStart
            // The same rules as updateEvent: the start must not be in the past, the registration deadline not after the start, ...
            EventPolicy.validate(
                input = inputOf(event = event, startsAt = newStartValue, endsAt = newEnd, locationText = newLocation),
                wallNow = wallNow,
                existingStartsAt = oldStart,
            )
            val roomId = event[EventTable.roomId]
            if (roomId != null) {
                EventRoomCollisionGuard.assertNoOverlap(
                    roomId = roomId,
                    startsAt = newStartValue,
                    endsAt = newEnd,
                    excludingEventId = eventId,
                )
            }
            EventStore.updateEvent(
                id = eventId,
                title = event[EventTable.title],
                description = event[EventTable.description],
                locationText = newLocation,
                onlineUrl = event[EventTable.onlineUrl],
                startsAt = newStartValue,
                endsAt = newEnd,
                capacity = event[EventTable.capacity],
                feeAmount = null,
                feeCurrency = null,
                visibility = event[EventTable.visibility],
                registrationClosesAt = event[EventTable.registrationClosesAt],
                roomId = roomId,
                summary = event[EventTable.summary],
                coverImageAlt = event[EventTable.coverImageAlt],
                onlineUrlPublic = event[EventTable.onlineUrlPublic],
                onlineKind = event[EventTable.onlineKind],
                encounterSpaceId = event[EventTable.encounterSpaceId],
            )
            // Keep the event's conference window in step with the new date (a running conference vetoes, like in updateEvent).
            EventOnlineLifecycle.onEventSaved(
                eventId = eventId,
                kind = event[EventTable.onlineKind],
                startsAt = newStartValue,
                endsAt = newEnd,
                previousVisibility = event[EventTable.visibility],
                newVisibility = event[EventTable.visibility],
                previousRegistrationClosesAt = event[EventTable.registrationClosesAt],
                newRegistrationClosesAt = event[EventTable.registrationClosesAt],
            )
            MeetingEventLinkAudit.record(
                actor = current,
                kind = MeetingEventLinkAuditKind.ALIGNED,
                meetingId = meetingId,
                eventId = eventId,
                changedFields = changedFields,
                officerRole = officerRole,
            )
            refOf(current = current, meetingId = meetingId, eventRow = EventStore.getEventOrThrow(eventId), wallNow = wallNow)
        }
    }

    fun listLinkable(
        current: CurrentMember,
        eventId: Uuid,
    ): List<MeetingDto> {
        requireWithinRate(current)
        // V1.9.229: BOARD/ADMIN only (see link).
        if (current.role !in EVENT_MANAGE_ROLES) throw ForbiddenException()
        return transaction {
            val event = EventStore.getEventOrNull(eventId) ?: throw NotFoundException("Event $eventId not found")
            if (event[EventTable.status] == EventStatus.CANCELLED || event[EventTable.meetingId] != null) return@transaction emptyList()
            if (event[EventTable.seriesId] != null && !event[EventTable.seriesDetached]) return@transaction emptyList()
            val linked =
                EventTable
                    .selectAll()
                    .where { EventTable.meetingId.isNotNull() }
                    .mapNotNull { it[EventTable.meetingId] }
                    .toSet()
            val canRecordCache = HashMap<Uuid, Boolean>()
            (MeetingTable innerJoin CommitteeTable)
                .selectAll()
                .where { MeetingTable.status neq MeetingStatus.CANCELLED }
                .orderBy(MeetingTable.scheduledAt to SortOrder.DESC, MeetingTable.id to SortOrder.ASC)
                .limit(MAX_LINKABLE_CANDIDATES)
                .filter { row -> row[MeetingTable.id] !in linked }
                .filter { row ->
                    canRecordCache.getOrPut(row[MeetingTable.committeeId]) { current.canRecordForMeeting(row[MeetingTable.committeeId]) }
                }.take(MAX_LINKABLE_MEETINGS)
                .map { it.toMeetingDto(resolveMemberNames = false) }
        }
    }

    private fun requireWithinRate(current: CurrentMember) {
        if (!rateLimiter.checkAndRecord(
                "actor:${current.memberId}",
            )
        ) {
            throw ConflictException("Zu viele Anfragen -- bitte später erneut versuchen.")
        }
    }

    /** Locks the Sitzung row FIRST (see the class KDoc) and checks the caller's right on the committee read from the LOCKED row. */
    private fun lockMeetingAndAuthorize(
        current: CurrentMember,
        meetingId: Uuid,
    ): ResultRow {
        val meeting =
            MeetingTable
                .selectAll()
                .where { MeetingTable.id eq meetingId }
                .forUpdate()
                .singleOrNull() ?: throw NotFoundException("Meeting $meetingId not found")
        if (!current.canRecordForMeeting(meeting[MeetingTable.committeeId])) throw ForbiddenException()
        return meeting
    }

    private fun announcementInput(
        meeting: ResultRow,
        committeeName: String,
    ): EventInput {
        val location = meeting[MeetingTable.location]?.trim()?.takeIf { it.isNotBlank() }
        val kind =
            when (meeting[MeetingTable.format]) {
                MeetingFormat.IN_PERSON -> {
                    if (location == null) throw MeetingLocationMissingException()
                    EventOnlineKind.NONE
                }
                MeetingFormat.ONLINE, MeetingFormat.HYBRID ->
                    if (conferencesEnabled()) {
                        EventOnlineKind.LAPIS_CONFERENCE
                    } else {
                        if (location == null) throw MeetingLocationMissingException()
                        EventOnlineKind.NONE
                    }
            }
        val startsAt = meeting[MeetingTable.scheduledAt]
        val date = "%02d.%02d.%04d".format(startsAt.day, startsAt.month.ordinal + 1, startsAt.year)
        return EventInput(
            title = "$committeeName: Sitzung am $date".take(EventPolicy.MAX_TITLE_LENGTH),
            description = MEETING_EVENT_DESCRIPTION_TEMPLATE.replace("{committee}", committeeName),
            locationText = location,
            startsAt = startsAt,
            endsAt = startsAt.toJavaLocalDateTime().plus(MEETING_EVENT_DEFAULT_DURATION).toKotlinLocalDateTime(),
            capacity = null,
            feeAmount = BigDecimal.ZERO,
            feeCurrency = "EUR",
            visibility = EventVisibility.MEMBERS_ONLY,
            registrationClosesAt = null,
            onlineKind = kind,
        )
    }

    private fun inputOf(
        event: ResultRow,
        startsAt: LocalDateTime,
        endsAt: LocalDateTime,
        locationText: String?,
    ): EventInput =
        EventInput(
            title = event[EventTable.title],
            description = event[EventTable.description],
            locationText = locationText,
            onlineUrl = event[EventTable.onlineUrl],
            startsAt = startsAt,
            endsAt = endsAt,
            capacity = event[EventTable.capacity],
            feeAmount = event[EventTable.feeAmount],
            feeCurrency = event[EventTable.feeCurrency],
            visibility = event[EventTable.visibility],
            registrationClosesAt = event[EventTable.registrationClosesAt],
            roomId = event[EventTable.roomId]?.toString(),
            summary = event[EventTable.summary],
            coverImageAlt = event[EventTable.coverImageAlt],
            onlineUrlPublic = event[EventTable.onlineUrlPublic],
            onlineKind = event[EventTable.onlineKind],
            encounterSpaceId = event[EventTable.encounterSpaceId]?.toString(),
        )

    private fun refOf(
        current: CurrentMember,
        meetingId: Uuid,
        eventRow: ResultRow,
        wallNow: LocalDateTime,
    ): LinkedEventRef {
        val meeting =
            (MeetingTable innerJoin CommitteeTable)
                .selectAll()
                .where { MeetingTable.id eq meetingId }
                .single()
                .toMeetingDto(resolveMemberNames = false)
        return MeetingEventLinkStore.linkedEventRef(eventRow = eventRow, meeting = meeting, caller = current, wallNow = wallNow)
    }

    /** Called from the same transaction as the status change to CANCELLED; the Sitzung row is already locked by that UPDATE. */
    object Lifecycle {
        fun onMeetingCancelled(
            actor: CurrentMember,
            meetingId: Uuid,
        ) {
            val eventId = MeetingEventLinkStore.clearLinkOfMeeting(meetingId) ?: return
            MeetingEventLinkAudit.record(
                actor = actor,
                kind = MeetingEventLinkAuditKind.UNLINKED,
                meetingId = meetingId,
                eventId = eventId,
                reason = MeetingEventUnlinkReason.MEETING_CANCELLED,
            )
        }

        /** Called from the same transaction as the cancellation, with the event row already locked. */
        fun onEventCancelled(
            actor: CurrentMember,
            eventId: Uuid,
        ) {
            val meetingId = MeetingEventLinkStore.clearLinkOfEvent(eventId) ?: return
            MeetingEventLinkAudit.record(
                actor = actor,
                kind = MeetingEventLinkAuditKind.UNLINKED,
                meetingId = meetingId,
                eventId = eventId,
                reason = MeetingEventUnlinkReason.EVENT_CANCELLED,
            )
        }
    }
}
