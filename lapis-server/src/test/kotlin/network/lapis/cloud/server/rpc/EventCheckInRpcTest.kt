package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
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
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventCheckInOutcome
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- [EventService]'s four new RPCs
 * (`openCheckIn`/`checkInByCode`/`checkInRegistration`/`reissueTicket`). Same "throwaway test
 * routes + `X-Member-Id` header" house style [EventServiceRpcTest] already establishes.
 */
class EventCheckInRpcTest :
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
                    it[displayName] = "EventCheckInRpcTest Mitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
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

        val farFutureStartsAt = LocalDateTime(2030, 1, 1, 18, 0)
        val farFutureEndsAt = LocalDateTime(2030, 1, 1, 22, 0)

        fun createEvent(createdBy: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "checkin-rpc-test-$id"
                    it[title] = "CheckIn-RPC-Test-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = farFutureStartsAt
                    it[endsAt] = farFutureEndsAt
                    it[capacity] = null
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

        /** Direct row insert -- a V1.4.3.1-era CONFIRMED registration with NO ticket yet, exactly the state `openCheckIn`'s own nachausstellung sweep exists for. */
        fun insertConfirmedRegistrationWithoutTicket(
            eventId: Uuid,
            guestEmail: String,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                EventStore.insertRegistration(
                    id = id,
                    eventId = eventId,
                    memberId = null,
                    guestName = "RPC-Test-Gast",
                    guestEmail = guestEmail,
                    activeParticipantKey = "g:$guestEmail",
                    status = EventRegistrationStatus.CONFIRMED,
                    feeAmount = BigDecimal.ZERO,
                    holdExpiresAt = null,
                    waitlistPosition = null,
                    cancelTokenSha256 = "cancel-$id",
                    registeredAt = now,
                    confirmedAt = now,
                )
            }
            return id
        }

        /** Direct row insert -- a CONFIRMED, already-ticketed registration bound to a real MEMBER (not a guest), so `EventStore.memberInfoByIds`'s bulk resolution has an actual member row to resolve against. */
        fun insertConfirmedMemberRegistration(
            eventId: Uuid,
            memberId: Uuid,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                EventStore.insertRegistration(
                    id = id,
                    eventId = eventId,
                    memberId = memberId,
                    guestName = null,
                    guestEmail = null,
                    activeParticipantKey = "m:$memberId",
                    status = EventRegistrationStatus.CONFIRMED,
                    feeAmount = BigDecimal.ZERO,
                    holdExpiresAt = null,
                    waitlistPosition = null,
                    cancelTokenSha256 = "cancel-$id",
                    registeredAt = now,
                    confirmedAt = now,
                    ticketCodeSha256 = "ticket-hash-$id",
                    ticketIssuedAt = now,
                )
            }
            return id
        }

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

        fun Route.registerCheckInTestRoutes(
            mailDispatcher: MailDispatcher,
            writeRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
            checkInRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 240, window = 1.minutes),
        ) {
            fun serviceFor(call: ApplicationCall) =
                EventService(
                    call = call,
                    pspConfigState = PspConfigState.NotConfigured,
                    checkoutClient = null,
                    baseUrl = "https://example.org",
                    mailDispatcher = mailDispatcher,
                    writeRateLimiter = writeRateLimiter,
                    checkInRateLimiter = checkInRateLimiter,
                )
            post("/test/event/{id}/open-checkin") {
                val roster = serviceFor(call).openCheckIn(eventId = call.parameters["id"]!!)
                call.respondText("${roster.confirmedCount}:${roster.rows.size}")
            }
            // Exposes each row's resolved displayName/email/checkedInByDisplayName -- the fields
            // `EventStore.memberInfoByIds`'s bulk lookup (Security-Review MAJOR/N+1 fix) now
            // populates instead of the old per-row `memberDisplayNameOrNull`/`memberEmailOrNull`
            // calls. Sorted by registrationId so the test can assert on a stable order.
            post("/test/event/{id}/open-checkin-detail") {
                val roster = serviceFor(call).openCheckIn(eventId = call.parameters["id"]!!)
                val encoded =
                    roster.rows
                        .sortedBy { it.registrationId }
                        .joinToString(";") { "${it.displayName}|${it.email}|${it.checkedInByDisplayName}" }
                call.respondText(encoded)
            }
            post("/test/event/{id}/checkin-by-code") {
                val code = call.request.queryParameters["code"]!!
                val result = serviceFor(call).checkInByCode(eventId = call.parameters["id"]!!, code = code)
                call.respondText(result.outcome.name)
            }
            post("/test/registration/{id}/checkin") {
                val eventId = call.request.queryParameters["eventId"]!!
                val result = serviceFor(call).checkInRegistration(eventId = eventId, registrationId = call.parameters["id"]!!)
                call.respondText(result.outcome.name)
            }
            post("/test/registration/{id}/reissue") {
                serviceFor(call).reissueTicket(registrationId = call.parameters["id"]!!)
                call.respondText("ok")
            }
        }

        fun StatusPagesConfig.installEventCheckInExceptionHandlers() {
            exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
            exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
        }

        // ── Role matrix -- MEMBER/TREASURER forbidden, BOARD/ADMIN allowed ────────────────────────

        test("openCheckIn/checkInByCode/checkInRegistration/reissueTicket are all forbidden for MEMBER and TREASURER") {
            testApplication {
                application {
                    install(StatusPages) { installEventCheckInExceptionHandlers() }
                    routing {
                        registerCheckInTestRoutes(
                            MailDispatcher(transport = RecordingMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                        )
                    }
                }
                val organizer = createMember(email = "role-organizer-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val eventId = createEvent(createdBy = organizer)
                val registrationId =
                    insertConfirmedRegistrationWithoutTicket(eventId = eventId, guestEmail = "role-guest-${Uuid.random()}@example.org")

                for (role in listOf(AccountRole.MEMBER, AccountRole.TREASURER)) {
                    val caller = createMember(email = "role-caller-$role-${Uuid.random()}@example.org", role = role)
                    client.post("/test/event/$eventId/open-checkin") { header("X-Member-Id", caller.toString()) }.status shouldBe
                        HttpStatusCode.Forbidden
                    client
                        .post("/test/event/$eventId/checkin-by-code?code=ABCD1234EFGH5678") {
                            header("X-Member-Id", caller.toString())
                        }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .post("/test/registration/$registrationId/checkin?eventId=$eventId") {
                            header("X-Member-Id", caller.toString())
                        }.status shouldBe HttpStatusCode.Forbidden
                    client.post("/test/registration/$registrationId/reissue") { header("X-Member-Id", caller.toString()) }.status shouldBe
                        HttpStatusCode.Forbidden
                }
            }
        }

        test("openCheckIn/checkInRegistration succeed for BOARD and ADMIN") {
            testApplication {
                application {
                    routing {
                        registerCheckInTestRoutes(
                            MailDispatcher(transport = RecordingMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                        )
                    }
                }
                for (role in listOf(AccountRole.BOARD, AccountRole.ADMIN)) {
                    val organizer = createMember(email = "role-ok-organizer-$role-${Uuid.random()}@example.org", role = role)
                    val eventId = createEvent(createdBy = organizer)
                    val registrationId =
                        insertConfirmedRegistrationWithoutTicket(
                            eventId = eventId,
                            guestEmail = "role-ok-guest-$role-${Uuid.random()}@example.org",
                        )
                    client.post("/test/event/$eventId/open-checkin") { header("X-Member-Id", organizer.toString()) }.status shouldBe
                        HttpStatusCode.OK
                    client
                        .post(
                            "/test/registration/$registrationId/checkin?eventId=$eventId",
                        ) { header("X-Member-Id", organizer.toString()) }
                        .status shouldBe
                        HttpStatusCode.OK
                }
            }
        }

        test("openCheckIn is rate-limited by checkInRateLimiter, not writeRateLimiter") {
            // Regression test for a review finding: `openCheckIn` used to call `requireWithinRate`
            // (`writeRateLimiter`, tuned for ordinary event-management writes) instead of
            // `requireWithinCheckInRate` (`checkInRateLimiter`, the 240/min door-scanning budget) --
            // yet the client re-calls `openCheckIn` after EVERY successful code/list check-in to
            // refresh the roster. A busy door desk exhausted the 60/min write budget on roster
            // refreshes alone within a minute, even though every check-in itself still succeeded.
            // Here `writeRateLimiter` is pinned to 1 request/minute -- if `openCheckIn` still drew
            // from it, the second call below would throw ConflictException (mapped to 409).
            testApplication {
                application {
                    install(StatusPages) { installEventCheckInExceptionHandlers() }
                    routing {
                        registerCheckInTestRoutes(
                            MailDispatcher(transport = RecordingMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                            writeRateLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes),
                            checkInRateLimiter = FederationInboxRateLimiter(maxRequests = 240, window = 1.minutes),
                        )
                    }
                }
                val organizer = createMember(email = "ratelimit-organizer-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val eventId = createEvent(createdBy = organizer)
                repeat(5) {
                    client.post("/test/event/$eventId/open-checkin") { header("X-Member-Id", organizer.toString()) }.status shouldBe
                        HttpStatusCode.OK
                }
            }
        }

        // ── openCheckIn nachausstellung ────────────────────────────────────────────────────────────

        test("openCheckIn issues a ticket for a CONFIRMED registration without one, idempotently") {
            testApplication {
                application {
                    routing {
                        registerCheckInTestRoutes(
                            MailDispatcher(transport = RecordingMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                        )
                    }
                }
                val organizer = createMember(email = "nachaus-organizer-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val eventId = createEvent(createdBy = organizer)
                val registrationId =
                    insertConfirmedRegistrationWithoutTicket(eventId = eventId, guestEmail = "nachaus-guest-${Uuid.random()}@example.org")

                val response1 = client.post("/test/event/$eventId/open-checkin") { header("X-Member-Id", organizer.toString()) }
                response1.status shouldBe HttpStatusCode.OK
                val hashAfterFirst =
                    transaction { EventStore.getRegistrationOrThrow(registrationId)[EventRegistrationTable.ticketCodeSha256] }
                (hashAfterFirst != null) shouldBe true

                // Second call is a no-op -- same hash, not a fresh one.
                client.post("/test/event/$eventId/open-checkin") { header("X-Member-Id", organizer.toString()) }
                val hashAfterSecond =
                    transaction { EventStore.getRegistrationOrThrow(registrationId)[EventRegistrationTable.ticketCodeSha256] }
                hashAfterSecond shouldBe hashAfterFirst
            }
        }

        test(
            "openCheckIn resolves member/guest display names and emails via the bulk lookup, " +
                "including the checked-in-by actor's own name (Security-Review MAJOR/N+1 fix)",
        ) {
            // Regression test for the N+1 fix: `EventStore.memberInfoByIds` replaced up to three
            // per-row single-id lookups with one bulk query keyed by every distinct `memberId`/
            // `checkedInBy` id in the roster. Exercise BOTH a real MEMBER registrant and a GUEST
            // registrant in the SAME roster, plus a check-in actor whose own display name must
            // resolve through the identical map (an id appearing in `checkedInBy`, not `memberId`).
            testApplication {
                application {
                    routing {
                        registerCheckInTestRoutes(
                            MailDispatcher(transport = RecordingMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                        )
                    }
                }
                val organizerEmail = "detail-organizer-${Uuid.random()}@example.org"
                val organizer = createMember(email = organizerEmail, role = AccountRole.BOARD)
                val participantEmail = "detail-participant-${Uuid.random()}@example.org"
                val participant = createMember(email = participantEmail, role = AccountRole.MEMBER)
                val eventId = createEvent(createdBy = organizer)
                val memberRegistrationId = insertConfirmedMemberRegistration(eventId = eventId, memberId = participant)
                val guestEmail = "detail-guest-${Uuid.random()}@example.org"
                insertConfirmedRegistrationWithoutTicket(eventId = eventId, guestEmail = guestEmail)

                // Check the MEMBER registration in -- checkedInBy becomes the organizer, whose
                // display name must ALSO resolve via the bulk map (a second, distinct id).
                client
                    .post("/test/registration/$memberRegistrationId/checkin?eventId=$eventId") {
                        header("X-Member-Id", organizer.toString())
                    }.status shouldBe HttpStatusCode.OK

                val detail =
                    client
                        .post("/test/event/$eventId/open-checkin-detail") { header("X-Member-Id", organizer.toString()) }
                        .bodyAsText()
                val rows = detail.split(";").sorted()

                // `createMember` always sets displayName "EventCheckInRpcTest Mitglied" -- both
                // rows share it, only email/checkedInByDisplayName distinguish them.
                rows shouldBe
                    listOf(
                        "EventCheckInRpcTest Mitglied|$participantEmail|EventCheckInRpcTest Mitglied",
                        "RPC-Test-Gast|$guestEmail|null",
                    ).sorted()
            }
        }

        // ── reissueTicket ──────────────────────────────────────────────────────────────────────────

        test("reissueTicket sends exactly one mail and invalidates the previous code") {
            testApplication {
                val transport = RecordingMailTransport()
                val mailDispatcher = MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
                application { routing { registerCheckInTestRoutes(mailDispatcher) } }

                val organizer = createMember(email = "reissue-organizer-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val eventId = createEvent(createdBy = organizer)
                val guestEmail = "reissue-guest-${Uuid.random()}@example.org"
                val registrationId = insertConfirmedRegistrationWithoutTicket(eventId = eventId, guestEmail = guestEmail)

                client.post("/test/event/$eventId/open-checkin") { header("X-Member-Id", organizer.toString()) }
                val originalHash =
                    transaction { EventStore.getRegistrationOrThrow(registrationId)[EventRegistrationTable.ticketCodeSha256] }

                val response = client.post("/test/registration/$registrationId/reissue") { header("X-Member-Id", organizer.toString()) }
                response.status shouldBe HttpStatusCode.OK

                val recipient = runBlocking { withTimeout(5.seconds) { transport.firstRecipient.await() } }
                recipient shouldBe guestEmail

                val rotatedHash = transaction { EventStore.getRegistrationOrThrow(registrationId)[EventRegistrationTable.ticketCodeSha256] }
                (rotatedHash != originalHash) shouldBe true
                transaction { EventStore.findByTicketCodeHash(originalHash!!) } shouldBe null
            }
        }

        test(
            "checkInRegistration is bound to the caller's own eventId -- a registration id from a " +
                "DIFFERENT event returns WRONG_EVENT, never checks it in",
        ) {
            // Regression test for a review finding (MINOR): `checkInRegistration` used to derive
            // `eventId` from the looked-up registration row itself, making
            // `EventCheckIn.classifyAndCheckIn`'s WRONG_EVENT guard a tautology on this path -- a
            // BOARD/ADMIN client (or a hand-built RPC call) sending a registrationId from a
            // DIFFERENT event than the check-in screen it has open would still check it in.
            testApplication {
                application {
                    routing {
                        registerCheckInTestRoutes(
                            MailDispatcher(transport = RecordingMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                        )
                    }
                }
                val organizer = createMember(email = "wrongevent-organizer-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val ownEventId = createEvent(createdBy = organizer)
                val otherEventId = createEvent(createdBy = organizer)
                val otherEventRegistrationId =
                    insertConfirmedRegistrationWithoutTicket(
                        eventId = otherEventId,
                        guestEmail = "wrongevent-guest-${Uuid.random()}@example.org",
                    )

                // The check-in screen open is for ownEventId, but the registrationId belongs to
                // otherEventId -- must be rejected as WRONG_EVENT, not silently checked in.
                val response =
                    client.post("/test/registration/$otherEventRegistrationId/checkin?eventId=$ownEventId") {
                        header("X-Member-Id", organizer.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe EventCheckInOutcome.WRONG_EVENT.name

                val checkedInAt =
                    transaction {
                        EventRegistrationTable
                            .selectAll()
                            .where { EventRegistrationTable.id eq otherEventRegistrationId }
                            .single()[EventRegistrationTable.checkedInAt]
                    }
                checkedInAt shouldBe null
            }
        }

        // ── checkInByCode / checkInRegistration outcome plumbing ──────────────────────────────────

        test("checkInByCode with an unknown code returns UNKNOWN_CODE, not an error") {
            testApplication {
                application {
                    routing {
                        registerCheckInTestRoutes(
                            MailDispatcher(transport = RecordingMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                        )
                    }
                }
                val organizer = createMember(email = "unknown-organizer-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val eventId = createEvent(createdBy = organizer)
                val response =
                    client.post("/test/event/$eventId/checkin-by-code?code=ZZZZ9999ZZZZ9999") {
                        header("X-Member-Id", organizer.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe EventCheckInOutcome.UNKNOWN_CODE.name
            }
        }
    })
