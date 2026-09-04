package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Review-round regression coverage for [EventService] -- exercises the RPC surface end to end
 * (same "throwaway test routes + `X-Member-Id` header" house style [ContributionPaymentRpcTest]
 * already establishes), unlike [network.lapis.cloud.server.events.EventCapacityTest] (which calls
 * [network.lapis.cloud.server.events.EventRegistrationSubmission] directly and never exercised
 * [EventService.cancelEvent]/`.cancelOwnRegistration` at all).
 *
 * Targets three findings from the same review round:
 * - CRITICAL: `cancelEvent` used to call `EventStore.memberEmailOrNull`/`.memberDisplayNameOrNull`
 *   AFTER its own transaction had already committed, throwing `IllegalStateException("No
 *   transaction in context.")` for the first MEMBER registrant -- the whole RPC surfaced as an
 *   uncaught 500 despite the cancellation itself already being persisted, and no cancellation mail
 *   ever went out.
 * - MAJOR: `EventCapacityGuard.withEventLock` only swept the waitlist BEFORE `block` ran -- a
 *   self-cancellation freeing a seat inside `block` was never picked up by the SAME lock
 *   acquisition, stranding the waitlist head until an unrelated later sweep.
 * - MINOR: `eventWriteRateLimiter` was documented (`Application.kt`) as gating every authenticated
 *   `IEventService` write, but `publishEvent`/`cancelEvent`/`cancelOwnRegistration`/`sweepEvent`
 *   never actually called `requireWithinRate`.
 */
class EventServiceRpcTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) {
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createMember(
            email: String,
            role: AccountRole,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventServiceRpcTest Mitglied"
                    it[MemberTable.email] = email
                    it[status] = network.lapis.cloud.shared.domain.MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        // Far in the future -- EventPolicy.isRegistrationOpen/validate both require `now < startsAt`.
        val farFutureStartsAt = LocalDateTime(2030, 1, 1, 18, 0)
        val farFutureEndsAt = LocalDateTime(2030, 1, 1, 22, 0)

        fun createEvent(
            createdBy: Uuid,
            capacity: Int?,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "rpc-test-$id"
                    it[title] = "RPC-Test-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = farFutureStartsAt
                    it[endsAt] = farFutureEndsAt
                    it[EventTable.capacity] = capacity
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[EventTable.createdAt] = DbClock.nowLocalDateTime()
                    it[EventTable.createdBy] = createdBy
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id
        }

        /**
         * Direct row insert with an already-past `startsAt`/`endsAt` -- deliberately bypasses
         * `EventPolicy.validate` (which `createEvent`'s own RPC path would reject this through), the
         * same way real production data reaches this state today: an event created while still in
         * the future simply ages past its own `startsAt` with the calendar. Used by the
         * `updateEvent`-on-an-already-started-event regression tests (Review MAJOR fix).
         */
        fun createPastEvent(createdBy: Uuid): Uuid {
            val id = Uuid.random()
            val pastStartsAt = LocalDateTime(2020, 1, 1, 18, 0)
            val pastEndsAt = LocalDateTime(2020, 1, 1, 22, 0)
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "rpc-test-past-$id"
                    it[title] = "RPC-Test-Past-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = pastStartsAt
                    it[endsAt] = pastEndsAt
                    it[EventTable.capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[EventTable.createdAt] = pastStartsAt
                    it[EventTable.createdBy] = createdBy
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id
        }

        /** Direct row insert -- deliberately bypasses `EventRegistrationSubmission`/`EventCapacityGuard` so each fixture's starting state (CONFIRMED/WAITLISTED) is exact and independent of capacity-guard behaviour, which is exactly what these tests are probing. */
        fun insertMemberRegistration(
            eventId: Uuid,
            memberId: Uuid,
            status: EventRegistrationStatus,
            waitlistPosition: Int? = null,
        ) {
            val now = DbClock.nowLocalDateTime()
            transaction {
                EventRegistrationTable.insert {
                    it[id] = Uuid.random()
                    it[EventRegistrationTable.eventId] = eventId
                    it[EventRegistrationTable.memberId] = memberId
                    it[guestName] = null
                    it[guestEmail] = null
                    it[activeParticipantKey] = "m:$memberId"
                    it[EventRegistrationTable.status] = status
                    it[feeAmount] = BigDecimal.ZERO
                    it[holdExpiresAt] = null
                    it[EventRegistrationTable.waitlistPosition] = waitlistPosition
                    it[cancelTokenSha256] = null
                    it[registeredAt] = now
                    it[confirmedAt] = if (status == EventRegistrationStatus.CONFIRMED) now else null
                    it[cancelledAt] = null
                    it[waitlistOfferedAt] = null
                }
            }
        }

        fun registrationStatus(
            eventId: Uuid,
            memberId: Uuid,
        ): EventRegistrationStatus =
            transaction {
                EventRegistrationTable
                    .selectAll()
                    .where { (EventRegistrationTable.eventId eq eventId) and (EventRegistrationTable.memberId eq memberId) }
                    .single()[EventRegistrationTable.status]
            }

        /** A [MailTransport] that completes [firstRecipient] with the first `to` it ever sees -- deterministic via `CompletableDeferred`, same idiom `MailDispatcherTest` already establishes (no `Thread.sleep`). */
        class RecordingMailTransport : MailTransport {
            val firstRecipient = CompletableDeferred<String>()

            override suspend fun send(
                to: String,
                subject: String,
                plainTextBody: String,
                htmlBody: String,
            ): MailSendOutcome {
                firstRecipient.complete(to)
                return MailSendOutcome.Sent
            }
        }

        fun Route.registerEventTestRoutes(
            mailDispatcher: MailDispatcher,
            writeRateLimiter: FederationInboxRateLimiter,
        ) {
            fun serviceFor(call: io.ktor.server.application.ApplicationCall) =
                EventService(
                    call = call,
                    pspConfigState = PspConfigState.NotConfigured,
                    checkoutClient = null,
                    baseUrl = "https://example.org",
                    mailDispatcher = mailDispatcher,
                    writeRateLimiter = writeRateLimiter,
                )
            post("/test/event/{id}/cancel") {
                val reason = call.request.queryParameters["reason"] ?: "Testgrund"
                val dto = serviceFor(call).cancelEvent(id = call.parameters["id"]!!, reason = reason)
                call.respondText(dto.status.name)
            }
            post("/test/event/{id}/cancel-own") {
                val dto = serviceFor(call).cancelOwnRegistration(eventId = call.parameters["id"]!!)
                call.respondText(dto.status.name)
            }
            post("/test/event/{id}/publish") {
                val dto = serviceFor(call).publishEvent(id = call.parameters["id"]!!)
                call.respondText(dto.status.name)
            }
            post("/test/event/{id}/sweep") {
                val dto = serviceFor(call).sweepEvent(id = call.parameters["id"]!!)
                call.respondText(dto.status.name)
            }
            // Test-only route for `updateEvent` -- a THIN pass-through (deliberately no DB read of
            // its own before calling `updateEvent`, unlike the other test routes above) so the
            // "missing event" regression test below exercises `updateEvent`'s OWN lock-first
            // NotFoundException, not a redundant lookup in this test scaffold. `startsAt`/`endsAt`
            // default to `createPastEvent`'s own fixed values; every other field matches that
            // fixture's defaults too (capacity/fee/visibility/registrationClosesAt).
            post("/test/event/{id}/update") {
                val eventId = call.parameters["id"]!!
                val title = call.request.queryParameters["title"] ?: "Updated Title"
                val startsAt =
                    call.request.queryParameters["startsAt"]?.let { LocalDateTime.parse(it) } ?: LocalDateTime(2020, 1, 1, 18, 0)
                val endsAt =
                    call.request.queryParameters["endsAt"]?.let { LocalDateTime.parse(it) } ?: LocalDateTime(2020, 1, 1, 22, 0)
                val input =
                    EventInput(
                        title = title,
                        description = "Aktualisierte Beschreibung",
                        locationText = "Testort",
                        onlineUrl = null,
                        startsAt = startsAt,
                        endsAt = endsAt,
                        capacity = null,
                        feeAmount = BigDecimal.ZERO,
                        feeCurrency = "EUR",
                        visibility = EventVisibility.PUBLIC,
                        registrationClosesAt = null,
                    )
                val dto = serviceFor(call).updateEvent(id = eventId, input = input)
                call.respondText(dto.title)
            }
        }

        // ── CRITICAL regression: cancelEvent must not throw + must mail a MEMBER registrant ──────

        test(
            "cancelEvent with an active MEMBER registration does not throw and mails the cancellation notice (CRITICAL regression)",
        ) {
            testApplication {
                val transport = RecordingMailTransport()
                val mailDispatcher = MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
                val writeRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes)
                application { routing { registerEventTestRoutes(mailDispatcher, writeRateLimiter) } }

                val organizer = createMember(email = "cancel-organizer-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val memberEmail = "cancel-member-${Uuid.random()}@example.org"
                val member = createMember(email = memberEmail, role = AccountRole.MEMBER)
                val eventId = createEvent(createdBy = organizer, capacity = null)
                insertMemberRegistration(eventId = eventId, memberId = member, status = EventRegistrationStatus.CONFIRMED)

                val response =
                    client.post("/test/event/$eventId/cancel?reason=Testgrund") { header("X-Member-Id", organizer.toString()) }
                // Before the fix, memberEmailOrNull/memberDisplayNameOrNull threw
                // IllegalStateException("No transaction in context.") for exactly this MEMBER
                // registrant, which (uncaught) surfaced as a 500 here.
                response.status shouldBe HttpStatusCode.OK

                val recipient = runBlocking { withTimeout(5.seconds) { transport.firstRecipient.await() } }
                recipient shouldBe memberEmail

                registrationStatus(eventId = eventId, memberId = member) shouldBe EventRegistrationStatus.CANCELLED
            }
        }

        // ── MAJOR regression: cancelOwnRegistration must promote the waitlist head IN THE SAME CALL ──

        test(
            "cancelOwnRegistration on a capacity=1 event immediately promotes the waitlist head, in the SAME call (MAJOR regression)",
        ) {
            testApplication {
                val transport = RecordingMailTransport()
                val mailDispatcher = MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
                val writeRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes)
                application { routing { registerEventTestRoutes(mailDispatcher, writeRateLimiter) } }

                val organizer = createMember(email = "waitlist-organizer-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val memberAEmail = "waitlist-a-${Uuid.random()}@example.org"
                val memberA = createMember(email = memberAEmail, role = AccountRole.MEMBER)
                val memberBEmail = "waitlist-b-${Uuid.random()}@example.org"
                val memberB = createMember(email = memberBEmail, role = AccountRole.MEMBER)
                val eventId = createEvent(createdBy = organizer, capacity = 1)
                insertMemberRegistration(eventId = eventId, memberId = memberA, status = EventRegistrationStatus.CONFIRMED)
                insertMemberRegistration(
                    eventId = eventId,
                    memberId = memberB,
                    status = EventRegistrationStatus.WAITLISTED,
                    waitlistPosition = 1,
                )

                val response = client.post("/test/event/$eventId/cancel-own") { header("X-Member-Id", memberA.toString()) }
                response.status shouldBe HttpStatusCode.OK

                // Before the fix, the pre-block sweep (BEFORE memberA's own cancellation ran) still
                // saw occupied == capacity and promoted nobody -- memberB stayed WAITLISTED until an
                // unrelated later lock acquisition happened to sweep the event.
                registrationStatus(eventId = eventId, memberId = memberA) shouldBe EventRegistrationStatus.CANCELLED
                registrationStatus(eventId = eventId, memberId = memberB) shouldBe EventRegistrationStatus.CONFIRMED

                val recipient = runBlocking { withTimeout(5.seconds) { transport.firstRecipient.await() } }
                recipient shouldBe memberBEmail
            }
        }

        // ── MAJOR regression: updateEvent must allow editing an already-started event ─────────────
        // (Review MAJOR fix -- see EventPolicy.validate KDoc "existingStartsAt")

        test(
            "updateEvent on an already-started event succeeds when startsAt is left UNCHANGED (MAJOR regression)",
        ) {
            testApplication {
                val writeRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes)
                application {
                    install(StatusPages) {
                        exception<BadRequestException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.BadRequest)
                        }
                    }
                    routing {
                        registerEventTestRoutes(
                            MailDispatcher(transport = RecordingMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                            writeRateLimiter,
                        )
                    }
                }

                val organizer = createMember(email = "update-past-organizer-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val eventId = createPastEvent(createdBy = organizer)

                // Before the fix, `validate` rejected EVERY updateEvent call on this event with
                // "Beginn darf nicht in der Vergangenheit liegen." -- even though startsAt itself was
                // never being touched, only the title.
                val response =
                    client.post("/test/event/$eventId/update?title=Korrigierter+Titel") {
                        header("X-Member-Id", organizer.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "Korrigierter Titel"

                val storedTitle = transaction { EventTable.selectAll().where { EventTable.id eq eventId }.single()[EventTable.title] }
                storedTitle shouldBe "Korrigierter Titel"
            }
        }

        test(
            "updateEvent on an already-started event still rejects moving startsAt to a DIFFERENT past instant (MAJOR regression)",
        ) {
            testApplication {
                val writeRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes)
                application {
                    install(StatusPages) {
                        exception<BadRequestException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.BadRequest)
                        }
                    }
                    routing {
                        registerEventTestRoutes(
                            MailDispatcher(transport = RecordingMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                            writeRateLimiter,
                        )
                    }
                }

                val organizer = createMember(email = "update-past-move-organizer-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val eventId = createPastEvent(createdBy = organizer)

                val response =
                    client.post("/test/event/$eventId/update?startsAt=2019-06-01T18:00:00") {
                        header("X-Member-Id", organizer.toString())
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        // ── MINOR regression: updateEvent must lock the event row, same as cancelEvent ────────────

        test(
            "updateEvent takes the event row lock as its first operation -- throws NotFoundException for a missing event, same as cancelEvent",
        ) {
            testApplication {
                val writeRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes)
                application {
                    install(StatusPages) {
                        exception<NotFoundException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.NotFound)
                        }
                    }
                    routing {
                        registerEventTestRoutes(
                            MailDispatcher(transport = RecordingMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                            writeRateLimiter,
                        )
                    }
                }
                val organizer = createMember(email = "update-missing-organizer-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val response =
                    client.post("/test/event/${Uuid.random()}/update") { header("X-Member-Id", organizer.toString()) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── MINOR regression: eventWriteRateLimiter must actually gate all four writes ────────────

        test(
            "publishEvent/cancelEvent/cancelOwnRegistration/sweepEvent are each throttled by their own " +
                "eventWriteRateLimiter budget (MINOR regression)",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    // A fresh budget of exactly 1 PER TEST BLOCK below -- the FIRST write for a given
                    // member must succeed, the SECOND (same member, same window) must be rejected
                    // with 409. Before the fix, publishEvent/cancelEvent/cancelOwnRegistration/
                    // sweepEvent never called `requireWithinRate` at all, so every second call below
                    // would ALSO have returned 200 -- unbounded, exactly the DoS gap the review
                    // finding described for `sweepEvent`.
                    routing {
                        registerEventTestRoutes(
                            MailDispatcher(transport = RecordingMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                            FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes),
                        )
                    }
                }

                // publishEvent -- two SEPARATE DRAFT events, so the second call's 409 can only come
                // from the rate limiter, never from publishEvent's own "already published" guard.
                run {
                    val organizer = createMember(email = "rate-publish-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                    val eventId1 = createEvent(createdBy = organizer, capacity = null)
                    val eventId2 = createEvent(createdBy = organizer, capacity = null)
                    transaction {
                        EventTable.update({ EventTable.id inList listOf(eventId1, eventId2) }) { it[status] = EventStatus.DRAFT }
                    }
                    client.post("/test/event/$eventId1/publish") { header("X-Member-Id", organizer.toString()) }.status shouldBe
                        HttpStatusCode.OK
                    client.post("/test/event/$eventId2/publish") { header("X-Member-Id", organizer.toString()) }.status shouldBe
                        HttpStatusCode.Conflict
                }

                // cancelEvent
                run {
                    val organizer = createMember(email = "rate-cancel-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                    val eventId1 = createEvent(createdBy = organizer, capacity = null)
                    val eventId2 = createEvent(createdBy = organizer, capacity = null)
                    client.post("/test/event/$eventId1/cancel") { header("X-Member-Id", organizer.toString()) }.status shouldBe
                        HttpStatusCode.OK
                    // A SECOND event (not the same, already-cancelled one) to isolate the rate-limit
                    // rejection from cancelEvent's own "already cancelled" ConflictException.
                    client.post("/test/event/$eventId2/cancel") { header("X-Member-Id", organizer.toString()) }.status shouldBe
                        HttpStatusCode.Conflict
                }

                // cancelOwnRegistration
                run {
                    val organizer = createMember(email = "rate-cancelown-organizer-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                    val member = createMember(email = "rate-cancelown-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                    val eventId1 = createEvent(createdBy = organizer, capacity = null)
                    val eventId2 = createEvent(createdBy = organizer, capacity = null)
                    insertMemberRegistration(eventId = eventId1, memberId = member, status = EventRegistrationStatus.CONFIRMED)
                    insertMemberRegistration(eventId = eventId2, memberId = member, status = EventRegistrationStatus.CONFIRMED)
                    client.post("/test/event/$eventId1/cancel-own") { header("X-Member-Id", member.toString()) }.status shouldBe
                        HttpStatusCode.OK
                    client.post("/test/event/$eventId2/cancel-own") { header("X-Member-Id", member.toString()) }.status shouldBe
                        HttpStatusCode.Conflict
                }

                // sweepEvent
                run {
                    val organizer = createMember(email = "rate-sweep-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                    val eventId = createEvent(createdBy = organizer, capacity = null)
                    client.post("/test/event/$eventId/sweep") { header("X-Member-Id", organizer.toString()) }.status shouldBe
                        HttpStatusCode.OK
                    client.post("/test/event/$eventId/sweep") { header("X-Member-Id", organizer.toString()) }.status shouldBe
                        HttpStatusCode.Conflict
                }
            }
        }
    })
