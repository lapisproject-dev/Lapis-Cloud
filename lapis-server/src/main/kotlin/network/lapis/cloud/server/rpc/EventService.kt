package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.events.EventCapacityGuard
import network.lapis.cloud.server.events.EventCheckIn
import network.lapis.cloud.server.events.EventParticipant
import network.lapis.cloud.server.events.EventPolicy
import network.lapis.cloud.server.events.EventRegistrationResult
import network.lapis.cloud.server.events.EventRegistrationSubmission
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.events.EventTicketIssuer
import network.lapis.cloud.server.events.EventTicketPolicy
import network.lapis.cloud.server.events.mailPromotion
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.htmlEscape
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.payment.psp.StripeCheckoutClient
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventCheckInResultDto
import network.lapis.cloud.shared.domain.EventCheckInRosterDto
import network.lapis.cloud.shared.domain.EventCheckInRowDto
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventPageDto
import network.lapis.cloud.shared.domain.EventQuery
import network.lapis.cloud.shared.domain.EventRegistrationDto
import network.lapis.cloud.shared.domain.EventRegistrationResultDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventRegistrationStatusSets
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IEventService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val EVENT_MANAGE_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

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
    private val pspConfigState: PspConfigState,
    private val checkoutClient: StripeCheckoutClient?,
    private val baseUrl: String,
    private val mailDispatcher: MailDispatcher,
    private val writeRateLimiter: FederationInboxRateLimiter,
    // Welle V1.4.3.2 -- deliberately SEPARATE from [writeRateLimiter]: a door check-in can produce
    // hundreds of scans in a few minutes, which writeRateLimiter's 60/min budget (tuned for ordinary
    // BOARD/ADMIN event-management clicks) would throttle mid-event. See `IEventService.checkInByCode`
    // KDoc call sites' own rationale.
    private val checkInRateLimiter: FederationInboxRateLimiter,
) : IEventService {
    private val submission by lazy {
        EventRegistrationSubmission(
            pspConfigState = pspConfigState,
            checkoutClient = checkoutClient,
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
        return transaction {
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
            is EventRegistrationResult.StripeFailed -> throw ConflictException("Zahlungsvorgang konnte nicht gestartet werden.")
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
    )
}

private fun ResultRow.toRegistrationDto(): EventRegistrationDto {
    val id = this[EventRegistrationTable.id]
    val memberId = this[EventRegistrationTable.memberId]
    val (paymentTransactionId, journalEntryId) = EventStore.findPaymentInfo(id)
    val checkedInBy = this[EventRegistrationTable.checkedInBy]
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
    )
}
