package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventSeriesTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.OpenItemTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.events.EventCapacityGuard
import network.lapis.cloud.server.events.EventCheckIn
import network.lapis.cloud.server.events.EventCoverPolicy
import network.lapis.cloud.server.events.EventParticipant
import network.lapis.cloud.server.events.EventPolicy
import network.lapis.cloud.server.events.EventRegistrationResult
import network.lapis.cloud.server.events.EventRegistrationSubmission
import network.lapis.cloud.server.events.EventRoomCollisionGuard
import network.lapis.cloud.server.events.EventRoomStore
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.events.EventTicketIssuer
import network.lapis.cloud.server.events.EventTicketPolicy
import network.lapis.cloud.server.events.mailPromotion
import network.lapis.cloud.server.events.series.EventSeriesLimits
import network.lapis.cloud.server.events.series.EventSeriesMaterializer
import network.lapis.cloud.server.events.series.EventSeriesScopeEngine
import network.lapis.cloud.server.events.series.RecurrenceExpander
import network.lapis.cloud.server.events.series.RecurrenceRuleBuilder
import network.lapis.cloud.server.events.series.RecurrenceSentence
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.htmlEscape
import network.lapis.cloud.server.payment.psp.PspCheckoutGateway
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CounterpartyKey
import network.lapis.cloud.shared.domain.EventCheckInResultDto
import network.lapis.cloud.shared.domain.EventCheckInRosterDto
import network.lapis.cloud.shared.domain.EventCheckInRowDto
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventInvoiceRequestDto
import network.lapis.cloud.shared.domain.EventPageDto
import network.lapis.cloud.shared.domain.EventQuery
import network.lapis.cloud.shared.domain.EventRegistrationDto
import network.lapis.cloud.shared.domain.EventRegistrationResultDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventRegistrationStatusSets
import network.lapis.cloud.shared.domain.EventSeriesCreateResultDto
import network.lapis.cloud.shared.domain.EventSeriesEditResultDto
import network.lapis.cloud.shared.domain.EventSeriesEditScope
import network.lapis.cloud.shared.domain.EventSeriesImpactDto
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemSnapshot
import network.lapis.cloud.shared.domain.OpenItemStatus
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.RecurrenceRuleInput
import network.lapis.cloud.shared.domain.SeriesPreviewDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IEventService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.time.ZoneId
import kotlin.uuid.Uuid

private val EVENT_MANAGE_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)
private val EVENT_INVOICE_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)

/** Matches `billing_street VARCHAR(200)` in V38__event_invoice.sql. */
private const val MAX_BILLING_STREET_LENGTH = 200

/** Matches `billing_postal_code VARCHAR(20)` in V38__event_invoice.sql. */
private const val MAX_BILLING_POSTAL_CODE_LENGTH = 20

/** Matches `billing_city VARCHAR(200)` in V38__event_invoice.sql. */
private const val MAX_BILLING_CITY_LENGTH = 200

/** Matches `billing_country VARCHAR(100)` in V38__event_invoice.sql. */
private const val MAX_BILLING_COUNTRY_LENGTH = 100

/**
 * Bounded retry count for `EventService.computeSeriesImpact`'s snapshot-plan-lock-recheck loop --
 * see that function's KDoc. A concurrent repoint/detach has to land in the narrow window between
 * the unlocked snapshot and the ascending-UUID lock pass to force a retry at all; a handful of
 * attempts is far more than that race could plausibly need in practice, and a bound (instead of an
 * unconditional loop) turns a pathological hot-contention case into a clear `ConflictException`
 * for the caller to retry, rather than a request that spins forever.
 */
private const val MAX_SERIES_IMPACT_LOCK_ATTEMPTS = 5

private fun requireMaxLength(
    value: String?,
    max: Int,
    fieldName: String,
) {
    if (value != null && value.length > max) {
        throw BadRequestException("$fieldName must be at most $max characters")
    }
}

/**
 * Welle V1.4.3.1 "Veranstaltungen: Kernschleife + Anmeldegebuehren-Zahlung" -- the authenticated RPC
 * surface. See [IEventService] KDoc for the role split, and
 * `network.lapis.cloud.server.events.EventRegistrationSubmission` for the shared registration
 * fachlogik this class delegates the actual member self-registration to.
 *
 * A plain `MEMBER` sees only `PUBLISHED` events via [listEvents]/[getEvent] -- `DRAFT`/`CANCELLED`
 * events are BOARD/ADMIN-only, same information-hiding posture the rest of this codebase applies to
 * not-yet-public content.
 */
class EventService(
    private val call: ApplicationCall,
    private val checkoutGateways: Map<PaymentProvider, PspCheckoutGateway>,
    private val baseUrl: String,
    private val mailDispatcher: MailDispatcher,
    private val writeRateLimiter: FederationInboxRateLimiter,
    // Welle V1.4.3.2 -- deliberately SEPARATE from [writeRateLimiter]: a door check-in can produce
    // hundreds of scans in a few minutes, which writeRateLimiter's 60/min budget (tuned for ordinary
    // BOARD/ADMIN event-management clicks) would throttle mid-event. See `IEventService.checkInByCode`
    // KDoc call sites' own rationale.
    private val checkInRateLimiter: FederationInboxRateLimiter,
    // Dritte Folgewelle "Wiederkehrende Veranstaltungen" -- deliberately SEPARATE from
    // [writeRateLimiter]: `previewSeries` is called on every keystroke/selection change in the
    // admin UI's recurrence editor (client-debounced to 400ms, never trusted server-side to
    // enforce that), a much higher-frequency budget than ordinary event-management writes.
    private val seriesPreviewRateLimiter: FederationInboxRateLimiter,
) : IEventService {
    /** Fixed, per [EventSeriesLimits.SUPPORTED_TIMEZONES] -- see that object's KDoc "Frage F2"/"Zeitzone" for why this codebase does not (yet) offer a per-series timezone picker. */
    private val seriesZone: ZoneId = ZoneId.of(EventSeriesLimits.SUPPORTED_TIMEZONES.first())

    private val submission by lazy {
        EventRegistrationSubmission(
            checkoutGateways = checkoutGateways,
            baseUrl = baseUrl,
            mailDispatcher = mailDispatcher,
        )
    }

    override suspend fun listEvents(query: EventQuery): EventPageDto {
        val current = resolveCurrentMember(call)
        val isManager = current.role in EVENT_MANAGE_ROLES
        val now = DbClock.nowLocalDateTime()
        val effectiveStatus = if (isManager) query.status else EventStatus.PUBLISHED
        return transaction {
            val (rows, total) =
                EventStore.list(
                    status = effectiveStatus,
                    includePast = query.includePast,
                    now = now,
                    limit = query.limit,
                    offset = query.offset,
                )
            val dtos = rows.map { row -> row.toEventDto(now = now, memberId = current.memberId, baseUrl = baseUrl) }
            EventPageDto(rows = dtos, totalCount = total, limit = query.limit.coerceIn(1, 200), offset = query.offset.coerceAtLeast(0))
        }
    }

    override suspend fun getEvent(id: String): EventDto {
        val current = resolveCurrentMember(call)
        val isManager = current.role in EVENT_MANAGE_ROLES
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val row = EventStore.getEventOrThrow(id.toEventUuid())
            if (!isManager && row[EventTable.status] != EventStatus.PUBLISHED) {
                throw NotFoundException("Event $id not found")
            }
            row.toEventDto(now = now, memberId = current.memberId, baseUrl = baseUrl)
        }
    }

    override suspend fun createEvent(input: EventInput): EventDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_MANAGE_ROLES)
        requireWithinRate(current.memberId)
        val now = DbClock.nowLocalDateTime()
        EventPolicy.validate(input = input, now = now)
        val roomId = input.roomId?.toEventUuid()
        return transaction {
            // Room-collision check, AFTER EventPolicy.validate, BEFORE the insert -- see
            // EventRoomCollisionGuard KDoc. No event-row lock exists yet to order against (this is
            // a brand-new event), so only the room row itself is locked here.
            if (roomId != null) {
                EventRoomCollisionGuard.assertNoOverlap(
                    roomId = roomId,
                    startsAt = input.startsAt,
                    endsAt = input.endsAt,
                    excludingEventId = null,
                )
            }
            val id = Uuid.random()
            val slug = EventPolicy.slugFor(title = input.title) { candidate -> EventStore.slugTaken(slug = candidate, excludingId = null) }
            EventStore.insertEvent(
                id = id,
                slug = slug,
                title = input.title.trim(),
                description = input.description.trim(),
                locationText = input.locationText?.trim()?.takeIf { it.isNotBlank() },
                onlineUrl = input.onlineUrl?.trim()?.takeIf { it.isNotBlank() },
                startsAt = input.startsAt,
                endsAt = input.endsAt,
                capacity = input.capacity,
                feeAmount = input.feeAmount,
                feeCurrency = input.feeCurrency,
                visibility = input.visibility,
                registrationClosesAt = input.registrationClosesAt,
                createdAt = now,
                createdBy = current.memberId,
                roomId = roomId,
            )
            EventStore.getEventOrThrow(id).toEventDto(now = now, memberId = current.memberId, baseUrl = baseUrl)
        }
    }

    override suspend fun updateEvent(
        id: String,
        input: EventInput,
    ): EventDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_MANAGE_ROLES)
        requireWithinRate(current.memberId)
        val now = DbClock.nowLocalDateTime()
        val eventId = id.toEventUuid()
        return transaction {
            // Row lock, FIRST operation (Review MINOR fix, same discipline `cancelEvent` already
            // established): without it, a concurrent `EventRegistrationSubmission.submit`/
            // `registerSelf` holding `EventCapacityGuard.withEventLock`'s lock on this SAME row could
            // insert a fresh registration with the OLD fee between this transaction's un-locked
            // `hasNonInactiveRegistration` read below and its own commit -- letting the fee change
            // through even though a registration at the old fee now exists underneath it.
            val existing = EventStore.lockEventForUpdate(eventId) ?: throw NotFoundException("Event $eventId not found")
            // Review MAJOR fix: `validate` used to run BEFORE this lock, with no way to tell "startsAt
            // genuinely moved into the past" apart from "startsAt already was, and still is, in the
            // past" -- see `EventPolicy.validate` KDoc. Passing the currently-stored `startsAt` here
            // (only obtainable once `existing` is loaded) makes that distinction possible.
            EventPolicy.validate(input = input, now = now, existingStartsAt = existing[EventTable.startsAt])
            val hasActiveRegistrations = EventStore.hasNonInactiveRegistration(eventId)
            val feeChanged =
                input.feeAmount.compareTo(existing[EventTable.feeAmount]) != 0 || input.feeCurrency != existing[EventTable.feeCurrency]
            if (hasActiveRegistrations && feeChanged) {
                throw ConflictException(
                    "Die Teilnahmegebühr kann nicht mehr geändert werden -- es bestehen bereits Anmeldungen für diese Veranstaltung.",
                )
            }
            val roomId = input.roomId?.toEventUuid()
            // Room-collision check, AFTER EventPolicy.validate, BEFORE the update -- see
            // EventRoomCollisionGuard KDoc. The `event` row lock above is already held (this
            // function's very first operation) -- locking the room row here, AFTER it, follows the
            // same lock ordering every other multi-lock path in this class establishes (never
            // acquire a narrower-scoped lock before the wider one it nests inside).
            if (roomId != null) {
                EventRoomCollisionGuard.assertNoOverlap(
                    roomId = roomId,
                    startsAt = input.startsAt,
                    endsAt = input.endsAt,
                    excludingEventId = eventId,
                )
            }
            EventStore.updateEvent(
                id = eventId,
                title = input.title.trim(),
                description = input.description.trim(),
                locationText = input.locationText?.trim()?.takeIf { it.isNotBlank() },
                onlineUrl = input.onlineUrl?.trim()?.takeIf { it.isNotBlank() },
                startsAt = input.startsAt,
                endsAt = input.endsAt,
                capacity = input.capacity,
                feeAmount = if (feeChanged) input.feeAmount else null,
                feeCurrency = if (feeChanged) input.feeCurrency else null,
                visibility = input.visibility,
                registrationClosesAt = input.registrationClosesAt,
                roomId = roomId,
            )
            EventStore.getEventOrThrow(eventId).toEventDto(now = now, memberId = current.memberId, baseUrl = baseUrl)
        }
    }

    override suspend fun publishEvent(id: String): EventDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_MANAGE_ROLES)
        requireWithinRate(current.memberId)
        val now = DbClock.nowLocalDateTime()
        val eventId = id.toEventUuid()
        return transaction {
            val existing = EventStore.getEventOrThrow(eventId)
            if (existing[EventTable.status] != EventStatus.DRAFT) {
                throw ConflictException("Nur Veranstaltungen im Status Entwurf können veröffentlicht werden.")
            }
            EventStore.setStatus(id = eventId, status = EventStatus.PUBLISHED, cancelledAt = null)
            EventStore.getEventOrThrow(eventId).toEventDto(now = now, memberId = current.memberId, baseUrl = baseUrl)
        }
    }

    override suspend fun cancelEvent(
        id: String,
        reason: String,
    ): EventDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_MANAGE_ROLES)
        requireWithinRate(current.memberId)
        if (reason.isBlank()) throw BadRequestException("Begründung ist erforderlich.")
        val now = DbClock.nowLocalDateTime()
        val eventId = id.toEventUuid()
        val (dto, notices) =
            transaction {
                // Row lock, FIRST operation (Review MAJOR fix): without it, a concurrent
                // `EventRegistrationSubmission.submit` that already passed its own pre-lock
                // `isRegistrationOpen` check (event still PUBLISHED at that point) could acquire
                // `EventCapacityGuard.withEventLock`'s lock on THIS row right after this transaction
                // commits and insert a fresh registration against an event that is, by then, already
                // CANCELLED -- see `EventRegistrationSubmission.submit`'s own in-lock re-check, which
                // this lock is what makes effective (that re-check would otherwise race this exact
                // `setStatus` call).
                val existing = EventStore.lockEventForUpdate(eventId) ?: throw NotFoundException("Event $eventId not found")
                if (existing[EventTable.status] == EventStatus.CANCELLED) {
                    throw ConflictException("Diese Veranstaltung ist bereits abgesagt.")
                }
                EventStore.setStatus(id = eventId, status = EventStatus.CANCELLED, cancelledAt = now)
                val activeRegistrations =
                    EventStore.listByEvent(eventId).filter { row ->
                        row[EventRegistrationTable.status] !in EventRegistrationStatusSets.INACTIVE
                    }
                activeRegistrations.forEach { row -> EventStore.cancelRegistration(id = row[EventRegistrationTable.id], now = now) }
                // Resolve every recipient's mail address/display name to a plain, transaction-free
                // value HERE, while the transaction is still open (Review CRITICAL fix): the mail
                // loop below runs AFTER this `transaction {}` block returns (by design -- see
                // `EventCapacityGuard` KDoc "MailDispatcher.enqueue must never run inside an open
                // transaction"), but `EventStore.memberEmailOrNull`/`.memberDisplayNameOrNull` run
                // their OWN fresh Exposed queries (unlike a `ResultRow` field access, which reads an
                // already-fetched value) and therefore throw `IllegalStateException("No transaction
                // in context.")` if called outside one. The previous code called them from inside
                // `mailEventCancelled` AFTER the transaction had already committed -- every
                // MEMBER-registrant cancellation notice (and every mail queued after the first such
                // member, member or guest) was silently lost, and the whole RPC call surfaced as an
                // uncaught 500 despite the cancellation itself having already committed.
                val notices =
                    activeRegistrations.mapNotNull { row ->
                        val memberId = row[EventRegistrationTable.memberId]
                        val to = if (memberId != null) EventStore.memberEmailOrNull(memberId) else row[EventRegistrationTable.guestEmail]
                        val name =
                            if (memberId != null) {
                                EventStore.memberDisplayNameOrNull(memberId) ?: ""
                            } else {
                                row[EventRegistrationTable.guestName] ?: ""
                            }
                        to?.let { EventCancellationNotice(to = it, recipientName = name) }
                    }
                val fresh = EventStore.getEventOrThrow(eventId).toEventDto(now = now, memberId = current.memberId, baseUrl = baseUrl)
                fresh to notices
            }
        notices.forEach { notice -> mailEventCancelled(notice = notice, eventTitle = dto.title, reason = reason) }
        return dto
    }

    override suspend fun listRegistrations(eventId: String): List<EventRegistrationDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_MANAGE_ROLES)
        // Security-Review LOW fix (Welle V1.4.3.2 Fix-Runde): this call had no rate limit at all
        // since V1.4.3.1 -- every other BOARD/ADMIN management call in this class goes through
        // requireWithinRate (60/min); this one was simply missed.
        requireWithinRate(current.memberId)
        val id = eventId.toEventUuid()
        return transaction {
            EventStore.getEventOrThrow(id)
            EventStore.listByEvent(id).map { it.toRegistrationDto() }
        }
    }

    override suspend fun registerSelf(eventId: String): EventRegistrationResultDto {
        val current = resolveCurrentMember(call)
        requireWithinRate(current.memberId)
        val id = eventId.toEventUuid()
        val (email, displayName) =
            transaction {
                (EventStore.memberEmailOrNull(current.memberId) ?: "") to (EventStore.memberDisplayNameOrNull(current.memberId) ?: "")
            }
        val result =
            submission.submit(
                eventId = id,
                participant = EventParticipant.Member(memberId = current.memberId, displayName = displayName, email = email),
            )
        return when (result) {
            is EventRegistrationResult.Confirmed ->
                EventRegistrationResultDto(registration = fetchRegistrationDto(result.registrationId), checkoutRedirectUrl = null)
            is EventRegistrationResult.Waitlisted ->
                EventRegistrationResultDto(registration = fetchRegistrationDto(result.registrationId), checkoutRedirectUrl = null)
            is EventRegistrationResult.PaymentRequired ->
                EventRegistrationResultDto(
                    registration = fetchRegistrationDto(result.registrationId),
                    checkoutRedirectUrl = result.redirectUrl,
                )
            EventRegistrationResult.AlreadyRegistered -> throw ConflictException("Sie sind für diese Veranstaltung bereits angemeldet.")
            EventRegistrationResult.EventNotAvailable -> throw ConflictException(
                "Diese Veranstaltung ist derzeit nicht für Anmeldungen geöffnet.",
            )
            EventRegistrationResult.WaitlistFull -> throw ConflictException("Die Warteliste dieser Veranstaltung ist voll.")
            EventRegistrationResult.GatewayUnavailable -> throw ConflictException("Zahlungsabwicklung derzeit nicht verfügbar.")
            is EventRegistrationResult.PaymentFailed -> throw ConflictException("Zahlungsvorgang konnte nicht gestartet werden.")
        }
    }

    override suspend fun cancelOwnRegistration(eventId: String): EventRegistrationDto {
        val current = resolveCurrentMember(call)
        requireWithinRate(current.memberId)
        val id = eventId.toEventUuid()
        val now = DbClock.nowLocalDateTime()
        val (registrationId, promotions) =
            EventCapacityGuard.withEventLock(eventId = id, now = now) { _ ->
                val reg =
                    EventStore.findOwnActiveRegistration(eventId = id, memberId = current.memberId)
                        ?: throw NotFoundException("Keine aktive Anmeldung für diese Veranstaltung gefunden.")
                val regId = reg[EventRegistrationTable.id]
                EventStore.cancelRegistration(id = regId, now = now)
                regId
            }
        promotions.forEach { it.mailPromotion(mailDispatcher) }
        return fetchRegistrationDto(registrationId)
    }

    override suspend fun sweepEvent(id: String): EventDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_MANAGE_ROLES)
        requireWithinRate(current.memberId)
        val eventId = id.toEventUuid()
        val now = DbClock.nowLocalDateTime()
        val (dto, promotions) =
            EventCapacityGuard.withEventLock(eventId = eventId, now = now) { event ->
                event.toEventDto(now = now, memberId = current.memberId, baseUrl = baseUrl)
            }
        promotions.forEach { it.mailPromotion(mailDispatcher) }
        return dto
    }

    override suspend fun openCheckIn(eventId: String): EventCheckInRosterDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_MANAGE_ROLES)
        // NOT requireWithinRate/writeRateLimiter (60/min) -- the client re-calls this after EVERY
        // successful checkInByCode/checkInRegistration to refresh the roster (see
        // EventCheckInScreen.refreshRoster/submitCode), so it needs the same 240/min door-scanning
        // budget as the check-in calls themselves, or a busy door desk starts throwing "too many
        // requests" on the roster refresh alone even though every check-in itself still succeeds
        // (review finding, Welle V1.4.3.2).
        requireWithinCheckInRate(current.memberId)
        val id = eventId.toEventUuid()
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val event = EventStore.getEventOrThrow(id)
            // Idempotent nachausstellung: every CONFIRMED row without a ticket yet gets one -- a
            // no-op on every call after the first (EventTicketIssuer.issueIfMissing's own guard).
            // The minted raw codes themselves are deliberately discarded here -- this sweep exists
            // to satisfy chk_event_registration_checkin_ticket / enable list-based check-in for
            // V1.4.3.1-era registrations, NOT to (re-)deliver a ticket mail; a registrant who wants
            // their ticket link uses `reissueTicket`.
            EventTicketIssuer.listConfirmedWithoutTicket(id).forEach { row ->
                EventTicketIssuer.issueIfMissing(registrationId = row[EventRegistrationTable.id], now = now)
            }
            // Security-Review LOW fix (Welle V1.4.3.2 Fix-Runde): capped roster read, NOT the plain
            // `EventStore.listByEvent` -- see `EventStore.listByEventForCheckIn` KDoc for why an
            // unbounded roster here is a response-size/egress concern on this exact endpoint.
            val rows = EventStore.listByEventForCheckIn(id)
            // Security-Review MAJOR fix (N+1 / DoS): resolve every participant's and every
            // check-in actor's member info in ONE bulk query up front instead of up to three
            // single-row lookups PER row below -- see `EventStore.memberInfoByIds` KDoc.
            val memberIds =
                rows.flatMap { row -> listOfNotNull(row[EventRegistrationTable.memberId], row[EventRegistrationTable.checkedInBy]) }
            val memberInfo = EventStore.memberInfoByIds(memberIds)
            val checkInRows =
                rows.map { row ->
                    val memberId = row[EventRegistrationTable.memberId]
                    val displayName =
                        (memberId?.let { memberInfo[it]?.displayName } ?: row[EventRegistrationTable.guestName])
                            ?: "(ohne Namen)"
                    val checkedInBy = row[EventRegistrationTable.checkedInBy]
                    EventCheckInRowDto(
                        registrationId = row[EventRegistrationTable.id].toString(),
                        displayName = displayName,
                        status = row[EventRegistrationTable.status],
                        email = memberId?.let { memberInfo[it]?.email } ?: row[EventRegistrationTable.guestEmail],
                        checkedInAt = row[EventRegistrationTable.checkedInAt],
                        checkedInByDisplayName = checkedInBy?.let { memberInfo[it]?.displayName },
                        hasTicket = row[EventRegistrationTable.ticketCodeSha256] != null,
                    )
                }
            EventCheckInRosterDto(
                eventId = id.toString(),
                eventTitle = event[EventTable.title],
                startsAt = event[EventTable.startsAt],
                locationText = event[EventTable.locationText],
                rows = checkInRows,
                confirmedCount = checkInRows.count { it.status == EventRegistrationStatus.CONFIRMED },
                checkedInCount = checkInRows.count { it.checkedInAt != null },
            )
        }
    }

    override suspend fun checkInByCode(
        eventId: String,
        code: String,
    ): EventCheckInResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_MANAGE_ROLES)
        requireWithinCheckInRate(current.memberId)
        val id = eventId.toEventUuid()
        val now = DbClock.nowLocalDateTime()
        return transaction {
            EventStore.getEventOrThrow(id)
            EventCheckIn.byCode(eventId = id, rawInput = code, actorMemberId = current.memberId, now = now)
        }
    }

    override suspend fun checkInRegistration(
        eventId: String,
        registrationId: String,
    ): EventCheckInResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_MANAGE_ROLES)
        requireWithinCheckInRate(current.memberId)
        val id = registrationId.toEventUuid()
        val boundEventId = eventId.toEventUuid()
        val now = DbClock.nowLocalDateTime()
        return transaction {
            EventCheckIn.byRegistration(eventId = boundEventId, registrationId = id, actorMemberId = current.memberId, now = now)
        }
    }

    override suspend fun reissueTicket(registrationId: String) {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_MANAGE_ROLES)
        requireWithinRate(current.memberId)
        val id = registrationId.toEventUuid()
        val now = DbClock.nowLocalDateTime()
        // Resolve the recipient/event details INSIDE the transaction (member email/display-name
        // lookups need one, see EventService.cancelEvent's own CRITICAL-fix KDoc), send the mail
        // only AFTER it commits.
        val mail =
            transaction {
                val registration = EventStore.getRegistrationOrThrow(id)
                val ticket =
                    EventTicketIssuer.reissue(registrationId = id, now = now)
                        ?: throw ConflictException("Ticket kann nur für eine bestätigte Anmeldung neu ausgestellt werden.")
                val memberId = registration[EventRegistrationTable.memberId]
                val to = memberId?.let { EventStore.memberEmailOrNull(it) } ?: registration[EventRegistrationTable.guestEmail]
                val name = memberId?.let { EventStore.memberDisplayNameOrNull(it) } ?: registration[EventRegistrationTable.guestName]
                val event = EventStore.getEventOrThrow(registration[EventRegistrationTable.eventId])
                if (to == null) {
                    null
                } else {
                    ReissueMail(
                        to = to,
                        recipientName = name ?: to,
                        eventTitle = event[EventTable.title],
                        slug = event[EventTable.slug],
                        rawCode = ticket.rawCode,
                    )
                }
            }
        if (mail != null) {
            val ticketUrl = EventTicketPolicy.ticketUrl(baseUrl = baseUrl, slug = mail.slug, rawCode = mail.rawCode)
            val subject = "Neues Ticket: ${mail.eventTitle}"
            val body =
                "Für Ihre Anmeldung zu \"${mail.eventTitle}\" wurde ein neues Ticket ausgestellt. " +
                    "Ihr vorheriges Ticket ist ab sofort ungültig."
            // Security-Review MINOR fix: `mail.recipientName`/`mail.eventTitle` can originate from
            // an unauthenticated guest form / a BOARD-supplied event title -- htmlEscape() every
            // such value before it goes into `htmlBody` (see `htmlEscape` KDoc). `plainTextBody`
            // stays unescaped -- HTML entities have no meaning there and would only clutter a plain
            // mail client's rendering.
            val bodyHtml =
                "Für Ihre Anmeldung zu \"${htmlEscape(mail.eventTitle)}\" wurde ein neues Ticket ausgestellt. " +
                    "Ihr vorheriges Ticket ist ab sofort ungültig."
            mailDispatcher.enqueue(
                to = mail.to,
                subject = subject,
                plainTextBody = "Hallo ${mail.recipientName},\n\n$body\n\nIhr Ticket: $ticketUrl\n",
                htmlBody = "<p>Hallo ${htmlEscape(mail.recipientName)},</p><p>$bodyHtml</p><p><a href=\"$ticketUrl\">Ihr Ticket</a></p>",
                purpose = "event-ticket-reissue",
            )
        }
    }

    /** Resolved recipient details for [reissueTicket]'s mail -- gathered inside the transaction, sent after commit. */
    private data class ReissueMail(
        val to: String,
        val recipientName: String,
        val eventTitle: String,
        val slug: String,
        val rawCode: String,
    )

    // ── Dritte Folgewelle "Wiederkehrende Veranstaltungen" -- RPC-Verdrahtung ────────────────────

    override suspend fun previewSeries(
        startsAt: LocalDateTime,
        endsAt: LocalDateTime,
        rule: RecurrenceRuleInput,
    ): SeriesPreviewDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_MANAGE_ROLES)
        requireWithinSeriesPreviewRate(current.memberId)
        return when (val built = RecurrenceRuleBuilder.build(rule = rule, dtstart = startsAt, zone = seriesZone)) {
            is RecurrenceRuleBuilder.Result.Invalid -> SeriesPreviewDto(valid = false, errors = built.messages)
            is RecurrenceRuleBuilder.Result.Ok -> {
                val occurrences =
                    runCatching { RecurrenceExpander.expand(rrule = built.rrule, dtstart = startsAt, zone = seriesZone) }
                        .getOrElse { ex ->
                            return SeriesPreviewDto(
                                valid = false,
                                errors =
                                    listOf(
                                        ex.message ?: "Ungültige Wiederholungsregel.",
                                    ),
                            )
                        }
                val sentence = RecurrenceSentence.build(rule = rule, occurrences = occurrences, templateEndTime = endsAt)
                SeriesPreviewDto(
                    valid = true,
                    count = occurrences.size,
                    first = occurrences.firstOrNull(),
                    last = occurrences.lastOrNull(),
                    sentence = sentence,
                )
            }
        }
    }

    override suspend fun createEventSeries(
        input: EventInput,
        rule: RecurrenceRuleInput,
    ): EventSeriesCreateResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_MANAGE_ROLES)
        requireWithinRate(current.memberId)
        val now = DbClock.nowLocalDateTime()
        EventPolicy.validate(input = input, now = now)
        val durationMinutes = minutesBetween(startsAt = input.startsAt, endsAt = input.endsAt)
        if (durationMinutes <= 0 ||
            durationMinutes > network.lapis.cloud.server.events.series.EventSeriesLimits.MAX_INSTANCE_DURATION_MINUTES
        ) {
            throw BadRequestException(
                "Die Dauer einer Serien-Instanz muss zwischen 1 und " +
                    "${network.lapis.cloud.server.events.series.EventSeriesLimits.MAX_INSTANCE_DURATION_MINUTES} Minuten liegen.",
            )
        }
        // Server re-validates the RRULE regardless of any prior previewSeries call -- never trust
        // a client-supplied raw string, only the typed RecurrenceRuleInput.
        val built = RecurrenceRuleBuilder.build(rule = rule, dtstart = input.startsAt, zone = seriesZone)
        val rrule =
            when (built) {
                is RecurrenceRuleBuilder.Result.Invalid -> throw BadRequestException(built.messages.joinToString("; "))
                is RecurrenceRuleBuilder.Result.Ok -> built.rrule
            }
        return transaction {
            if (EventStore.countActiveSeries(now) >= network.lapis.cloud.server.events.series.EventSeriesLimits.MAX_ACTIVE_SERIES_PER_ORG) {
                throw ConflictException(
                    "Es sind bereits " +
                        "${network.lapis.cloud.server.events.series.EventSeriesLimits.MAX_ACTIVE_SERIES_PER_ORG} aktive Serien vorhanden.",
                )
            }
            val seriesId = Uuid.random()
            EventStore.insertSeries(
                id = seriesId,
                rrule = rrule,
                dtstart = input.startsAt,
                timezone = seriesZone.id,
                durationMinutes = durationMinutes,
                splitFromSeriesId = null,
                createdBy = current.memberId,
                createdAt = now,
            )
            val createdIds =
                EventSeriesMaterializer.materialize(
                    seriesId = seriesId,
                    rrule = rrule,
                    dtstart = input.startsAt,
                    zone = seriesZone,
                    durationMinutes = durationMinutes,
                    template = input,
                    createdBy = current.memberId,
                    now = now,
                )
            val firstEvent =
                EventStore
                    .getEventOrThrow(
                        createdIds.first(),
                    ).toEventDto(now = now, memberId = current.memberId, baseUrl = baseUrl)
            EventSeriesCreateResultDto(
                seriesId = seriesId.toString(),
                createdEventIds = createdIds.map { it.toString() },
                firstEvent = firstEvent,
            )
        }
    }

    override suspend fun impactOfSeriesEdit(
        eventId: String,
        scope: EventSeriesEditScope,
    ): EventSeriesImpactDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_MANAGE_ROLES)
        requireWithinSeriesPreviewRate(current.memberId)
        val id = eventId.toEventUuid()
        return transaction {
            computeSeriesImpact(id = id, scope = scope).impact
        }
    }

    override suspend fun updateSeriesEvent(
        eventId: String,
        input: EventInput,
        scope: EventSeriesEditScope,
    ): EventSeriesEditResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_MANAGE_ROLES)
        requireWithinRate(current.memberId)
        val now = DbClock.nowLocalDateTime()
        EventPolicy.validate(input = input, now = now)
        val id = eventId.toEventUuid()
        val durationMinutes = minutesBetween(startsAt = input.startsAt, endsAt = input.endsAt)
        return transaction {
            val (_, scopePlan, seriesId) = computeSeriesImpact(id = id, scope = scope)
            val result =
                EventSeriesMaterializer.applyEdit(
                    scope = scope,
                    scopePlan = scopePlan,
                    seriesId = seriesId,
                    newTemplate = input,
                    durationMinutes = durationMinutes,
                    createdBy = current.memberId,
                    now = now,
                )
            EventSeriesEditResultDto(
                affectedEventCount = result.affectedEventIds.size,
                affectedRegistrationCount = EventStore.countActiveRegistrationsForEvents(result.affectedEventIds),
            )
        }
    }

    override suspend fun cancelSeriesEvent(
        eventId: String,
        scope: EventSeriesEditScope,
        reason: String,
    ): EventSeriesEditResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_MANAGE_ROLES)
        requireWithinRate(current.memberId)
        if (reason.isBlank()) throw BadRequestException("Begründung ist erforderlich.")
        val now = DbClock.nowLocalDateTime()
        val id = eventId.toEventUuid()
        val (dtoResult, notices, eventTitle) =
            transaction {
                val (_, scopePlan, seriesId, title) = computeSeriesImpact(id = id, scope = scope)
                val result = EventSeriesMaterializer.deleteOrCancel(scope = scope, scopePlan = scopePlan, seriesId = seriesId, now = now)
                Triple(
                    EventSeriesEditResultDto(
                        affectedEventCount = result.affectedEventIds.size,
                        affectedRegistrationCount = result.notices.size,
                    ),
                    result.notices,
                    title,
                )
            }
        notices.forEach { notice ->
            mailEventCancelled(
                notice = EventCancellationNotice(to = notice.to, recipientName = notice.recipientName),
                eventTitle = eventTitle,
                reason = reason,
            )
        }
        return dtoResult
    }

    /**
     * The full result of [computeSeriesImpact]: the impact DTO plus everything a caller needs to
     * carry out the actual edit/cancel -- [seriesId] and [title], both read from the SAME locked row
     * [computeSeriesImpact] already fetched, so a caller never needs (and must never perform) a
     * second, separate, unlocked re-read of the event row to get at them (see [computeSeriesImpact]
     * KDoc for why that second read used to be a real TOCTOU gap).
     */
    private data class SeriesImpactComputation(
        val impact: EventSeriesImpactDto,
        val scopePlan: EventSeriesScopeEngine.ScopePlan,
        val seriesId: Uuid,
        val title: String,
    )

    /**
     * Shared helper: locates [id]'s series + resolves [EventSeriesScopeEngine.plan] for [scope] --
     * used by [impactOfSeriesEdit]/[updateSeriesEvent]/[cancelSeriesEvent] so the impact numbers are
     * ALWAYS server-recomputed, never trusted from a prior client-visible call (TOCTOU safety). Must
     * run inside an open transaction.
     *
     * Review MAJOR fix (Runde 1): this used to read the `event` row via the unlocked
     * [EventStore.getEventOrThrow], and every caller then performed a SECOND, separately unlocked
     * [EventStore.getEventOrThrow] of the very same row to obtain `seriesId` (and, for
     * [cancelSeriesEvent], `title`) for the subsequent [EventSeriesMaterializer.applyEdit]/
     * [EventSeriesMaterializer.deleteOrCancel] call. Between those two reads, nothing prevented a
     * concurrent transaction (e.g. another admin's [EventSeriesEditScope.FOLLOWING] split on an
     * earlier occurrence of the SAME series, which re-points `event.series_id` for every occurrence
     * from [targetOriginalStart] onward via [EventStore.repointSeriesId]) from committing a change to
     * this very row's `series_id` in that window.
     *
     * Review MINOR fix (Runde 2, lock-ordering/deadlock): the Runde-1 fix closed that window by
     * taking [EventStore.lockEventForUpdate] on the CLICKED row alone, ONCE, right here, before the
     * caller ever reaches [EventSeriesMaterializer.applyEdit]/[EventSeriesMaterializer.deleteOrCancel]
     * -- but those two lock every AFFECTED row (which always includes the clicked one, see
     * [EventSeriesScopeEngine.plan]'s KDoc) in a SEPARATE, later pass in ascending-UUID order
     * ("deadlock avoidance for concurrent series edits", see their own KDoc). Locking the clicked row
     * here FIRST, independently of where it falls in that ascending order, broke the series' single
     * global lock order whenever the clicked row was not itself the lowest UUID of the affected set:
     * two admins editing overlapping occurrences of the same series (e.g. admin 1 on occurrence C,
     * admin 2 on occurrence A, with A < C) could each hold their own clicked-row lock while waiting
     * on the other's -- a genuine `deadlock detected` from Postgres, surfaced to one admin as an
     * unexpected failure.
     *
     * Fixed by locking the ENTIRE affected set -- including the clicked row -- in exactly ONE
     * ascending-UUID pass, here, matching [EventSeriesMaterializer.applyEdit]/`.deleteOrCancel`'s own
     * discipline (their subsequent per-row [EventStore.lockEventForUpdate] calls become harmless
     * re-locks of a row this transaction already holds). Since the affected set depends on data
     * ([EventSeriesScopeEngine.plan]'s output) read BEFORE any lock is held, this re-checks the
     * clicked row under lock afterwards and retries the whole computation (bounded) if a concurrent
     * commit changed it in the meantime -- the same TOCTOU guarantee as before, just without
     * re-introducing an out-of-order lock.
     */
    private fun computeSeriesImpact(
        id: Uuid,
        scope: EventSeriesEditScope,
    ): SeriesImpactComputation {
        repeat(MAX_SERIES_IMPACT_LOCK_ATTEMPTS) {
            val snapshot = EventStore.getEventOrThrow(id)
            val seriesId = snapshot[EventTable.seriesId] ?: throw ConflictException("Dieser Termin gehört zu keiner Serie.")
            if (snapshot[EventTable.seriesDetached]) {
                throw ConflictException("Dieser Termin wurde bereits aus der Serie gelöst.")
            }
            val originalStart =
                snapshot[EventTable.seriesOriginalStart] ?: throw ConflictException("Serien-Termin ohne series_original_start.")
            val series = EventStore.getSeriesOrThrow(seriesId)
            val scopePlan =
                EventSeriesScopeEngine.plan(
                    scope = scope,
                    rrule = series[EventSeriesTable.rrule],
                    dtstart = series[EventSeriesTable.dtstart],
                    zone = seriesZone,
                    targetOriginalStart = originalStart,
                )
            val affectedRowsUnlocked =
                EventStore.findSeriesEventsByOriginalStarts(
                    seriesId = seriesId,
                    originalStarts = scopePlan.affectedOriginalStarts,
                )
            // Single ascending-UUID lock pass over the WHOLE affected set (clicked row included) --
            // see KDoc above for why the clicked row must never be locked separately/first.
            val idsToLock = (affectedRowsUnlocked.map { it[EventTable.id] } + id).distinct().sortedBy { it.toString() }
            idsToLock.forEach { EventStore.lockEventForUpdate(it) }

            // Re-check the clicked row UNDER LOCK: a concurrent transaction may have committed a
            // `repointSeriesId`/detach between the unlocked snapshot above and the locks just taken.
            // If so, the scope plan just computed may no longer be valid for the clicked row -- retry
            // from a fresh snapshot instead of handing the caller a plan built against stale data.
            val lockedRow = EventStore.getEventOrThrow(id)
            if (lockedRow[EventTable.seriesId] != seriesId ||
                lockedRow[EventTable.seriesDetached] ||
                lockedRow[EventTable.seriesOriginalStart] != originalStart
            ) {
                return@repeat
            }

            val affectedIds = affectedRowsUnlocked.map { it[EventTable.id] }
            val isFirstOccurrence = originalStart == series[EventSeriesTable.dtstart]
            return SeriesImpactComputation(
                impact =
                    EventSeriesImpactDto(
                        affectedEventCount = affectedIds.size,
                        affectedRegistrationCount = EventStore.countActiveRegistrationsForEvents(affectedIds),
                        isFirstOccurrence = isFirstOccurrence,
                        ruleChanged = false,
                    ),
                scopePlan = scopePlan,
                seriesId = seriesId,
                title = lockedRow[EventTable.title],
            )
        }
        throw ConflictException(
            "Dieser Serien-Termin wurde zwischenzeitlich von einer anderen Bearbeitung verändert -- bitte erneut versuchen.",
        )
    }

    private fun minutesBetween(
        startsAt: LocalDateTime,
        endsAt: LocalDateTime,
    ): Int {
        val start = startsAt.toJavaLocalDateTime()
        val end = endsAt.toJavaLocalDateTime()
        return java.time.Duration
            .between(start, end)
            .toMinutes()
            .toInt()
    }

    private fun requireWithinSeriesPreviewRate(memberId: Uuid) {
        if (!seriesPreviewRateLimiter.checkAndRecord("member:$memberId")) {
            throw ConflictException("Zu viele Anfragen -- bitte spaeter erneut versuchen.")
        }
    }

    private fun requireWithinRate(memberId: Uuid) {
        if (!writeRateLimiter.checkAndRecord("member:$memberId")) {
            throw ConflictException("Zu viele Anfragen -- bitte spaeter erneut versuchen.")
        }
    }

    private fun requireWithinCheckInRate(memberId: Uuid) {
        if (!checkInRateLimiter.checkAndRecord("member:$memberId")) {
            throw ConflictException("Zu viele Anfragen -- bitte spaeter erneut versuchen.")
        }
    }

    private fun fetchRegistrationDto(registrationId: Uuid): EventRegistrationDto =
        transaction { EventStore.getRegistrationOrThrow(registrationId).toRegistrationDto() }

    /**
     * Welle V1.4.3.6 "Externe Rechnungsstellung" -- see [IEventService.issueEventInvoice] KDoc.
     * Deliberately a THIN bridge: this method contains no new booking logic of its own, it only
     * assembles the same inputs [OpenItemService.createOpenItem] would need and calls straight
     * into [OpenItemPostingBridge.postItemCreation] (the "transaction-free by contract" idiom that
     * bridge's own KDoc documents) from inside ITS own `transaction {}` -- no nested-transaction
     * question arises because [OpenItemPostingBridge] is a plain object with no `transaction {}`
     * of its own, unlike [OpenItemService.createOpenItem] (a full RPC method that opens one).
     */
    override suspend fun issueEventInvoice(input: EventInvoiceRequestDto): EventRegistrationDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*EVENT_INVOICE_ROLES)
        requireWithinRate(current.memberId)
        requireMaxLength(value = input.billingStreet, max = MAX_BILLING_STREET_LENGTH, fieldName = "billingStreet")
        requireMaxLength(value = input.billingPostalCode, max = MAX_BILLING_POSTAL_CODE_LENGTH, fieldName = "billingPostalCode")
        requireMaxLength(value = input.billingCity, max = MAX_BILLING_CITY_LENGTH, fieldName = "billingCity")
        requireMaxLength(value = input.billingCountry, max = MAX_BILLING_COUNTRY_LENGTH, fieldName = "billingCountry")
        if (input.dueInDays <= 0) throw BadRequestException("dueInDays must be positive")
        val registrationId = input.registrationId.toEventUuid()
        val now = DbClock.nowLocalDateTime()

        return transaction {
            val row =
                EventRegistrationTable
                    .selectAll()
                    .where { EventRegistrationTable.id eq registrationId }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Event registration $registrationId not found")
            if (row[EventRegistrationTable.status] != EventRegistrationStatus.CONFIRMED) {
                throw ConflictException(
                    "Event registration $registrationId is ${row[EventRegistrationTable.status]}, only CONFIRMED registrations can be invoiced.",
                )
            }
            if (row[EventRegistrationTable.openItemId] != null) {
                throw ConflictException("Event registration $registrationId already has an invoice/open item.")
            }
            val feeAmount = row[EventRegistrationTable.feeAmount]
            if (feeAmount <= BigDecimal.ZERO) {
                throw ConflictException("Event registration $registrationId has feeAmount $feeAmount, nothing to invoice.")
            }

            val settingsRow =
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .singleOrNull()
            val eventIncomeAccountId =
                settingsRow?.get(OrganizationSettingsTable.eventIncomeAccountId)
                    ?: throw ConflictException(
                        "organization_settings.event_income_account_id is not configured -- cannot issue an event invoice.",
                    )
            val sphere = settingsRow[OrganizationSettingsTable.eventIncomeSphere]

            val memberId = row[EventRegistrationTable.memberId]
            val counterpartyName =
                (memberId?.let { EventStore.memberDisplayNameOrNull(it) } ?: row[EventRegistrationTable.guestName])
                    ?: "Unbekannt"

            val itemId = Uuid.random()
            val itemDate = now.date
            val dueDate = itemDate.plus(input.dueInDays, DateTimeUnit.DAY)
            val counterpartyKey = CounterpartyKey.of(counterpartyName)
            // Grep-able payment reference, printed on EventInvoicePdfGenerator's "Verwendungszweck"
            // line -- same idea PaymentReferenceAllocator establishes for Beitragsrechnungen, just
            // deterministic from the registration id rather than allocated (no bank-statement-
            // matcher rule exists yet for event invoices this wave).
            val reference = "EVENT-$registrationId"

            OpenItemTable.insert {
                it[id] = itemId
                it[direction] = OpenItemDirection.RECEIVABLE
                it[OpenItemTable.counterpartyName] = counterpartyName
                it[OpenItemTable.counterpartyKey] = counterpartyKey
                it[crmContactId] = null
                it[OpenItemTable.reference] = reference
                it[OpenItemTable.itemDate] = itemDate
                it[OpenItemTable.dueDate] = dueDate
                it[amount] = feeAmount
                it[contraAccountId] = eventIncomeAccountId
                it[OpenItemTable.sphere] = sphere
                it[status] = OpenItemStatus.OPEN
                it[note] = "Anmeldegebühr · Event-Registrierung $registrationId (extern in Rechnung gestellt)"
                it[createdByMemberId] = current.memberId
                it[createdAt] = now
            }

            val outcome =
                OpenItemPostingBridge.postItemCreation(
                    itemId = itemId,
                    direction = OpenItemDirection.RECEIVABLE,
                    counterpartyName = counterpartyName,
                    reference = reference,
                    amount = feeAmount,
                    contraAccountId = eventIncomeAccountId,
                    sphere = sphere,
                    itemDate = itemDate,
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                )
            // Review MINOR fix (booking-logic duplication): this used to repeat the same
            // `when (outcome) { Posted -> ...; Failed -> ... }` `OpenItemTable.update` block
            // `OpenItemService.createOpenItem` already has -- now both share
            // [applyOpenItemPostingOutcome] (see that function's own KDoc).
            applyOpenItemPostingOutcome(itemId = itemId, outcome = outcome)

            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.OPEN_ITEM,
                entityId = itemId,
                action = AuditAction.CREATE,
                before = null,
                after =
                    Json.encodeToString(
                        OpenItemSnapshot.serializer(),
                        OpenItemSnapshot(
                            openItemId = itemId.toString(),
                            direction = OpenItemDirection.RECEIVABLE,
                            counterpartyKey = counterpartyKey,
                            status = OpenItemStatus.OPEN,
                            amount = feeAmount,
                            creationJournalEntryId = (outcome as? OpenItemPostingOutcome.Posted)?.journalEntryId?.toString(),
                            creationPostingError = (outcome as? OpenItemPostingOutcome.Failed)?.reason,
                        ),
                    ),
            )

            EventRegistrationTable.update({ EventRegistrationTable.id eq registrationId }) {
                it[billingStreet] = input.billingStreet?.trim()?.takeIf { s -> s.isNotBlank() }
                it[billingPostalCode] = input.billingPostalCode?.trim()?.takeIf { s -> s.isNotBlank() }
                it[billingCity] = input.billingCity?.trim()?.takeIf { s -> s.isNotBlank() }
                it[billingCountry] = input.billingCountry?.trim()?.takeIf { s -> s.isNotBlank() }
                it[openItemId] = itemId
                it[invoiceIssuedAt] = now
                it[invoiceIssuedBy] = current.memberId
            }

            EventStore.getRegistrationOrThrow(registrationId).toRegistrationDto()
        }
    }

    /** A cancellation-notice recipient, already resolved to a plain value INSIDE the triggering transaction -- see `cancelEvent`'s own KDoc comment for why this indirection exists at all (CRITICAL review fix). */
    private data class EventCancellationNotice(
        val to: String,
        val recipientName: String,
    )

    private fun mailEventCancelled(
        notice: EventCancellationNotice,
        eventTitle: String,
        reason: String,
    ) {
        val subject = "Abgesagt: $eventTitle"
        val body = "Die Veranstaltung \"$eventTitle\" wurde abgesagt.\n\nBegründung: $reason"
        // Security-Review MINOR fix: `eventTitle`/`reason` are BOARD/ADMIN-supplied free text
        // (see `htmlEscape` KDoc "Fehlerszenario B") -- htmlEscape() both before they reach every
        // registrant's `htmlBody`.
        val bodyHtml = "Die Veranstaltung \"${htmlEscape(eventTitle)}\" wurde abgesagt.\n\nBegründung: ${htmlEscape(reason)}"
        mailDispatcher.enqueue(
            to = notice.to,
            subject = subject,
            plainTextBody = "Hallo ${notice.recipientName},\n\n$body\n",
            htmlBody = "<p>Hallo ${htmlEscape(notice.recipientName)},</p><p>${bodyHtml.replace("\n", "<br>")}</p>",
            purpose = "event-cancelled",
        )
    }
}

private fun String.toEventUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

private fun ResultRow.toEventDto(
    now: LocalDateTime,
    memberId: Uuid,
    baseUrl: String,
): EventDto {
    val id = this[EventTable.id]
    val capacity = this[EventTable.capacity]
    val occupied = EventStore.countOccupied(eventId = id, now = now)
    val waitlistCount = EventStore.countWaitlisted(id)
    val full = capacity != null && occupied >= capacity
    val feeEditable = !EventStore.hasNonInactiveRegistration(id)
    val ownStatus = EventStore.findOwnActiveRegistration(eventId = id, memberId = memberId)?.get(EventRegistrationTable.status)
    val status = this[EventTable.status]
    val visibility = this[EventTable.visibility]
    val slug = this[EventTable.slug]
    val publicUrl =
        if (visibility == EventVisibility.PUBLIC && status == EventStatus.PUBLISHED) {
            "$baseUrl/veranstaltung/$slug"
        } else {
            null
        }
    val roomId = this[EventTable.roomId]
    val seriesId = this[EventTable.seriesId]
    val seriesDetached = this[EventTable.seriesDetached]
    val seriesRuleSummary =
        if (seriesId != null && !seriesDetached) {
            EventStore.getSeriesOrNull(seriesId)?.let { seriesRow ->
                runCatching {
                    val parsed = RecurrenceRuleBuilder.parseWhitelisted(seriesRow[EventSeriesTable.rrule])
                    RecurrenceSentence.frequencyOnly(parsed)
                }.getOrNull()
            }
        } else {
            null
        }
    return EventDto(
        id = id.toString(),
        slug = slug,
        title = this[EventTable.title],
        description = this[EventTable.description],
        locationText = this[EventTable.locationText],
        onlineUrl = this[EventTable.onlineUrl],
        startsAt = this[EventTable.startsAt],
        endsAt = this[EventTable.endsAt],
        capacity = capacity,
        feeAmount = this[EventTable.feeAmount],
        feeCurrency = this[EventTable.feeCurrency],
        status = status,
        visibility = visibility,
        registrationClosesAt = this[EventTable.registrationClosesAt],
        occupiedSeats = occupied,
        waitlistCount = waitlistCount,
        full = full,
        feeEditable = feeEditable,
        ownRegistrationStatus = ownStatus,
        publicUrl = publicUrl,
        roomId = roomId?.toString(),
        roomName = EventRoomStore.roomNameOrNull(roomId),
        coverImageUrl = EventCoverPolicy.coverImageUrl(baseUrl = baseUrl, slug = slug, coverImageId = this[EventTable.coverImageId]),
        seriesId = seriesId?.toString(),
        seriesDetached = seriesDetached,
        seriesRuleSummary = seriesRuleSummary,
    )
}

private fun ResultRow.toRegistrationDto(): EventRegistrationDto {
    val id = this[EventRegistrationTable.id]
    val memberId = this[EventRegistrationTable.memberId]
    val (paymentTransactionId, journalEntryId) = EventStore.findPaymentInfo(id)
    val checkedInBy = this[EventRegistrationTable.checkedInBy]
    val invoiceIssuedBy = this[EventRegistrationTable.invoiceIssuedBy]
    return EventRegistrationDto(
        id = id.toString(),
        eventId = this[EventRegistrationTable.eventId].toString(),
        memberId = memberId?.toString(),
        memberDisplayName = memberId?.let { EventStore.memberDisplayNameOrNull(it) },
        guestName = this[EventRegistrationTable.guestName],
        guestEmail = this[EventRegistrationTable.guestEmail],
        status = this[EventRegistrationTable.status],
        feeAmount = this[EventRegistrationTable.feeAmount],
        waitlistPosition = this[EventRegistrationTable.waitlistPosition],
        registeredAt = this[EventRegistrationTable.registeredAt],
        paymentTransactionId = paymentTransactionId?.toString(),
        journalEntryId = journalEntryId?.toString(),
        ticketIssuedAt = this[EventRegistrationTable.ticketIssuedAt],
        checkedInAt = this[EventRegistrationTable.checkedInAt],
        checkedInByDisplayName = checkedInBy?.let { EventStore.memberDisplayNameOrNull(it) },
        billingStreet = this[EventRegistrationTable.billingStreet],
        billingPostalCode = this[EventRegistrationTable.billingPostalCode],
        billingCity = this[EventRegistrationTable.billingCity],
        billingCountry = this[EventRegistrationTable.billingCountry],
        openItemId = this[EventRegistrationTable.openItemId]?.toString(),
        invoiceIssuedAt = this[EventRegistrationTable.invoiceIssuedAt],
        invoiceIssuedByDisplayName = invoiceIssuedBy?.let { EventStore.memberDisplayNameOrNull(it) },
    )
}
