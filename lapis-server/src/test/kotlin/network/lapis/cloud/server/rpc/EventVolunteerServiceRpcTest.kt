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
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.EventVolunteerShiftTable
import network.lapis.cloud.server.db.generated.EventVolunteerSignupTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.EventVolunteerShiftInput
import network.lapis.cloud.shared.domain.EventVolunteerShiftStatus
import network.lapis.cloud.shared.domain.EventVolunteerSignupStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.7 "Helfer-/Schichtplanung für Veranstaltungen" -- RPC-surface coverage of
 * [EventVolunteerService] via throwaway test routes + `X-Member-Id` header, same house style
 * [EventRoomServiceRpcTest]/[EventServiceRpcTest] already establish. Covers the permission matrix
 * (MEMBER/TREASURER -> 403 for shift management, MEMBER -> success for self-service), the
 * capacity/re-signup happy path, and the "cannot cancel someone else's signup" guard.
 */
class EventVolunteerServiceRpcTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()
        val createdShiftIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdShiftIds.isNotEmpty()) {
                    EventVolunteerSignupTable.deleteWhere { shiftId inList createdShiftIds }
                    EventVolunteerShiftTable.deleteWhere { id inList createdShiftIds }
                }
                if (createdEventIds.isNotEmpty()) EventTable.deleteWhere { id inList createdEventIds }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createMember(role: AccountRole): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventVolunteerServiceRpcTest Mitglied"
                    it[email] = "eventvolunteerrpc-${Uuid.random()}@example.org"
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

        fun createEvent(
            createdBy: Uuid,
            status: EventStatus = EventStatus.PUBLISHED,
        ): Uuid {
            val id = Uuid.random()
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "volunteer-rpc-test-$id"
                    it[title] = "Volunteer-RPC-Test-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = now
                    it[endsAt] = now
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[EventTable.status] = status
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[EventTable.createdAt] = now
                    it[EventTable.createdBy] = createdBy
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id
        }

        fun Route.registerEventVolunteerTestRoutes() {
            val rateLimiter = FederationInboxRateLimiter()

            fun serviceFor(call: io.ktor.server.application.ApplicationCall) =
                EventVolunteerService(call = call, writeRateLimiter = rateLimiter)
            post("/test/event-volunteer/list") {
                val eventId = call.request.queryParameters["eventId"]!!
                val shifts = serviceFor(call).listShifts(eventId)
                call.respondText(shifts.size.toString())
            }
            post("/test/event-volunteer/create") {
                val eventId = call.request.queryParameters["eventId"]!!
                val neededCount = call.request.queryParameters["neededCount"]?.toInt() ?: 1
                val shift =
                    serviceFor(call).createShift(
                        EventVolunteerShiftInput(
                            eventId = eventId,
                            description = "Aufbau",
                            startsAt = LocalDateTime(2026, 6, 1, 18, 0),
                            endsAt = LocalDateTime(2026, 6, 1, 20, 0),
                            neededCount = neededCount,
                        ),
                    )
                createdShiftIds += Uuid.parse(shift.id)
                call.respondText(shift.id)
            }
            post("/test/event-volunteer/{id}/cancel") {
                val shift = serviceFor(call).cancelShift(id = call.parameters["id"]!!)
                call.respondText(shift.status.name)
            }
            post("/test/event-volunteer/{id}/roster") {
                val roster = serviceFor(call).getShiftRoster(id = call.parameters["id"]!!)
                call.respondText(roster.signups.size.toString())
            }
            post("/test/event-volunteer/{id}/signup") {
                val signup = serviceFor(call).signUpSelf(shiftId = call.parameters["id"]!!)
                call.respondText(signup.status.name)
            }
            post("/test/event-volunteer/{id}/cancel-own-signup") {
                val signup = serviceFor(call).cancelOwnSignup(shiftId = call.parameters["id"]!!)
                call.respondText(signup.status.name)
            }
        }

        fun StatusPagesConfig.installEventVolunteerExceptionHandlers() {
            exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
            exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
            exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
            exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
            exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
        }

        test("MEMBER is forbidden from creating a shift") {
            testApplication {
                application {
                    install(StatusPages) { installEventVolunteerExceptionHandlers() }
                    routing { registerEventVolunteerTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val eventId = createEvent(board)
                val member = createMember(AccountRole.MEMBER)
                val response =
                    client.post("/test/event-volunteer/create?eventId=$eventId") { header("X-Member-Id", member.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("TREASURER is forbidden from cancelling a shift") {
            testApplication {
                application {
                    install(StatusPages) { installEventVolunteerExceptionHandlers() }
                    routing { registerEventVolunteerTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val eventId = createEvent(board)
                val createResponse =
                    client.post("/test/event-volunteer/create?eventId=$eventId") { header("X-Member-Id", board.toString()) }
                val shiftId = createResponse.bodyAsText()

                val treasurer = createMember(AccountRole.TREASURER)
                val response =
                    client.post("/test/event-volunteer/$shiftId/cancel") { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("MEMBER is forbidden from reading the shift roster") {
            testApplication {
                application {
                    install(StatusPages) { installEventVolunteerExceptionHandlers() }
                    routing { registerEventVolunteerTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val eventId = createEvent(board)
                val createResponse =
                    client.post("/test/event-volunteer/create?eventId=$eventId") { header("X-Member-Id", board.toString()) }
                val shiftId = createResponse.bodyAsText()

                val member = createMember(AccountRole.MEMBER)
                val response = client.post("/test/event-volunteer/$shiftId/roster") { header("X-Member-Id", member.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("MEMBER can list shifts and sign themselves up") {
            testApplication {
                application {
                    install(StatusPages) { installEventVolunteerExceptionHandlers() }
                    routing { registerEventVolunteerTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val eventId = createEvent(board)
                val createResponse =
                    client.post("/test/event-volunteer/create?eventId=$eventId&neededCount=2") {
                        header("X-Member-Id", board.toString())
                    }
                val shiftId = createResponse.bodyAsText()

                val member = createMember(AccountRole.MEMBER)
                val listResponse =
                    client.post("/test/event-volunteer/list?eventId=$eventId") { header("X-Member-Id", member.toString()) }
                listResponse.status shouldBe HttpStatusCode.OK
                listResponse.bodyAsText() shouldBe "1"

                val signupResponse = client.post("/test/event-volunteer/$shiftId/signup") { header("X-Member-Id", member.toString()) }
                signupResponse.status shouldBe HttpStatusCode.OK
                signupResponse.bodyAsText() shouldBe EventVolunteerSignupStatus.CONFIRMED.name
            }
        }

        test("a full shift rejects a third signup with Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installEventVolunteerExceptionHandlers() }
                    routing { registerEventVolunteerTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val eventId = createEvent(board)
                val createResponse =
                    client.post("/test/event-volunteer/create?eventId=$eventId&neededCount=1") {
                        header("X-Member-Id", board.toString())
                    }
                val shiftId = createResponse.bodyAsText()

                val member1 = createMember(AccountRole.MEMBER)
                val member2 = createMember(AccountRole.MEMBER)
                client
                    .post("/test/event-volunteer/$shiftId/signup") { header("X-Member-Id", member1.toString()) }
                    .status shouldBe HttpStatusCode.OK
                val second =
                    client.post("/test/event-volunteer/$shiftId/signup") { header("X-Member-Id", member2.toString()) }
                second.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("cancel own signup, then re-sign-up reactivates the SAME row (no duplicate)") {
            testApplication {
                application {
                    install(StatusPages) { installEventVolunteerExceptionHandlers() }
                    routing { registerEventVolunteerTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val eventId = createEvent(board)
                val createResponse =
                    client.post("/test/event-volunteer/create?eventId=$eventId&neededCount=1") {
                        header("X-Member-Id", board.toString())
                    }
                val shiftId = createResponse.bodyAsText()

                val member = createMember(AccountRole.MEMBER)
                client
                    .post("/test/event-volunteer/$shiftId/signup") { header("X-Member-Id", member.toString()) }
                    .status shouldBe HttpStatusCode.OK

                val cancelResponse =
                    client.post("/test/event-volunteer/$shiftId/cancel-own-signup") { header("X-Member-Id", member.toString()) }
                cancelResponse.status shouldBe HttpStatusCode.OK
                cancelResponse.bodyAsText() shouldBe EventVolunteerSignupStatus.CANCELLED.name

                val resignupResponse =
                    client.post("/test/event-volunteer/$shiftId/signup") { header("X-Member-Id", member.toString()) }
                resignupResponse.status shouldBe HttpStatusCode.OK
                resignupResponse.bodyAsText() shouldBe EventVolunteerSignupStatus.CONFIRMED.name

                // DB-level confirmation: still exactly ONE signup row for this shift/member pair.
                val shiftUuid = Uuid.parse(shiftId)
                val rowCount =
                    transaction {
                        EventVolunteerSignupTable
                            .selectAll()
                            .where { (EventVolunteerSignupTable.shiftId eq shiftUuid) and (EventVolunteerSignupTable.memberId eq member) }
                            .count()
                    }
                rowCount shouldBe 1L
            }
        }

        test("a member cannot cancel someone else's signup -- sees NotFound for their own (non-existent) one") {
            testApplication {
                application {
                    install(StatusPages) { installEventVolunteerExceptionHandlers() }
                    routing { registerEventVolunteerTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val eventId = createEvent(board)
                val createResponse =
                    client.post("/test/event-volunteer/create?eventId=$eventId&neededCount=1") {
                        header("X-Member-Id", board.toString())
                    }
                val shiftId = createResponse.bodyAsText()

                val member1 = createMember(AccountRole.MEMBER)
                val member2 = createMember(AccountRole.MEMBER)
                client
                    .post("/test/event-volunteer/$shiftId/signup") { header("X-Member-Id", member1.toString()) }
                    .status shouldBe HttpStatusCode.OK

                // member2 never signed up -- cancelOwnSignup must NOT find/cancel member1's row.
                val response =
                    client.post("/test/event-volunteer/$shiftId/cancel-own-signup") { header("X-Member-Id", member2.toString()) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("BOARD can create a shift, read its roster, and cancel it") {
            testApplication {
                application {
                    install(StatusPages) { installEventVolunteerExceptionHandlers() }
                    routing { registerEventVolunteerTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val eventId = createEvent(board)
                val createResponse =
                    client.post("/test/event-volunteer/create?eventId=$eventId&neededCount=1") {
                        header("X-Member-Id", board.toString())
                    }
                val shiftId = createResponse.bodyAsText()

                val member = createMember(AccountRole.MEMBER)
                client
                    .post("/test/event-volunteer/$shiftId/signup") { header("X-Member-Id", member.toString()) }
                    .status shouldBe HttpStatusCode.OK

                val rosterResponse = client.post("/test/event-volunteer/$shiftId/roster") { header("X-Member-Id", board.toString()) }
                rosterResponse.status shouldBe HttpStatusCode.OK
                rosterResponse.bodyAsText() shouldBe "1"

                val cancelResponse = client.post("/test/event-volunteer/$shiftId/cancel") { header("X-Member-Id", board.toString()) }
                cancelResponse.status shouldBe HttpStatusCode.OK

                // A CANCELLED shift rejects further signups.
                val otherMember = createMember(AccountRole.MEMBER)
                val signupResponse =
                    client.post("/test/event-volunteer/$shiftId/signup") { header("X-Member-Id", otherMember.toString()) }
                signupResponse.status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── Security-Audit MAJOR-1 fix: DRAFT-event visibility -- listShifts/signUpSelf must not
        // leak shift details/accept signups for an event a MEMBER is not yet allowed to see.

        test("MEMBER cannot list shifts of a DRAFT event -- NotFound") {
            testApplication {
                application {
                    install(StatusPages) { installEventVolunteerExceptionHandlers() }
                    routing { registerEventVolunteerTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val eventId = createEvent(board, status = EventStatus.DRAFT)
                val createResponse =
                    client.post("/test/event-volunteer/create?eventId=$eventId") { header("X-Member-Id", board.toString()) }
                createResponse.status shouldBe HttpStatusCode.OK

                val member = createMember(AccountRole.MEMBER)
                val response = client.post("/test/event-volunteer/list?eventId=$eventId") { header("X-Member-Id", member.toString()) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("BOARD/ADMIN can list shifts of a DRAFT event") {
            testApplication {
                application {
                    install(StatusPages) { installEventVolunteerExceptionHandlers() }
                    routing { registerEventVolunteerTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val eventId = createEvent(board, status = EventStatus.DRAFT)
                val createResponse =
                    client.post("/test/event-volunteer/create?eventId=$eventId") { header("X-Member-Id", board.toString()) }
                createResponse.status shouldBe HttpStatusCode.OK

                val response = client.post("/test/event-volunteer/list?eventId=$eventId") { header("X-Member-Id", board.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "1"
            }
        }

        test("MEMBER cannot sign up for a shift of a DRAFT event -- NotFound") {
            testApplication {
                application {
                    install(StatusPages) { installEventVolunteerExceptionHandlers() }
                    routing { registerEventVolunteerTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val eventId = createEvent(board, status = EventStatus.DRAFT)
                val createResponse =
                    client.post("/test/event-volunteer/create?eventId=$eventId") { header("X-Member-Id", board.toString()) }
                val shiftId = createResponse.bodyAsText()

                val member = createMember(AccountRole.MEMBER)
                val response = client.post("/test/event-volunteer/$shiftId/signup") { header("X-Member-Id", member.toString()) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── Security-Audit MAJOR-2 fix: cancelShift idempotency -- a second cancel must not silently
        // succeed / re-run the write.

        test("cancelling an already-CANCELLED shift returns Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installEventVolunteerExceptionHandlers() }
                    routing { registerEventVolunteerTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val eventId = createEvent(board)
                val createResponse =
                    client.post("/test/event-volunteer/create?eventId=$eventId") { header("X-Member-Id", board.toString()) }
                val shiftId = createResponse.bodyAsText()

                client.post("/test/event-volunteer/$shiftId/cancel") { header("X-Member-Id", board.toString()) }.status shouldBe
                    HttpStatusCode.OK
                val secondCancel = client.post("/test/event-volunteer/$shiftId/cancel") { header("X-Member-Id", board.toString()) }
                secondCancel.status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── Security-Audit MAJOR-2 fix: real-thread race between cancelShift and signUpSelf on the
        // SAME shift -- mirrors EventVolunteerCapacityGuardTest's own "single most important test"
        // real-thread idiom. Proves the FOR UPDATE row lock cancelShift now takes as its FIRST
        // operation actually serializes against a concurrent signUpSelf: either the signup wins the
        // race (lands CONFIRMED before the cancel commits, and the cancel still succeeds afterwards),
        // or the cancel wins first (the signup then sees the shift already CANCELLED and gets a
        // ConflictException) -- but NEVER a CONFIRMED signup silently coexisting with no consistent
        // DB state, and never an uncaught error on either side.
        test("cancelShift vs signUpSelf race -- serializes cleanly, never an inconsistent state") {
            testApplication {
                application {
                    install(StatusPages) { installEventVolunteerExceptionHandlers() }
                    routing { registerEventVolunteerTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val eventId = createEvent(board)
                val createResponse =
                    client.post("/test/event-volunteer/create?eventId=$eventId&neededCount=1") {
                        header("X-Member-Id", board.toString())
                    }
                val shiftId = createResponse.bodyAsText()
                val member = createMember(AccountRole.MEMBER)

                val startLatch = CountDownLatch(2)
                val doneLatch = CountDownLatch(2)
                val cancelStatus = AtomicReference<HttpStatusCode>()
                val signupStatus = AtomicReference<HttpStatusCode>()

                val cancelThread =
                    Thread {
                        try {
                            startLatch.countDown()
                            startLatch.await(20, TimeUnit.SECONDS)
                            cancelStatus.set(
                                runBlocking {
                                    client
                                        .post("/test/event-volunteer/$shiftId/cancel") { header("X-Member-Id", board.toString()) }
                                        .status
                                },
                            )
                        } finally {
                            doneLatch.countDown()
                        }
                    }
                val signupThread =
                    Thread {
                        try {
                            startLatch.countDown()
                            startLatch.await(20, TimeUnit.SECONDS)
                            signupStatus.set(
                                runBlocking {
                                    client
                                        .post("/test/event-volunteer/$shiftId/signup") { header("X-Member-Id", member.toString()) }
                                        .status
                                },
                            )
                        } finally {
                            doneLatch.countDown()
                        }
                    }
                cancelThread.start()
                signupThread.start()
                check(doneLatch.await(20, TimeUnit.SECONDS)) { "concurrent cancel/signup did not complete in time" }

                // The cancel is the only cancel call in this test -- it never conflicts with itself,
                // so it must always succeed regardless of race outcome.
                cancelStatus.get() shouldBe HttpStatusCode.OK
                // The signup either wins the race (CONFIRMED) or loses it and observes the
                // now-CANCELLED shift (Conflict) -- never anything else.
                (signupStatus.get() == HttpStatusCode.OK || signupStatus.get() == HttpStatusCode.Conflict) shouldBe true

                // DB-level confirmation: the shift ends up CANCELLED either way (it's the only cancel
                // call), and the confirmed-signup count matches exactly what the HTTP response
                // claimed -- no silent mismatch between what the API returned and what committed.
                val shiftUuid = Uuid.parse(shiftId)
                val shiftStatus =
                    transaction {
                        EventVolunteerShiftTable
                            .selectAll()
                            .where { EventVolunteerShiftTable.id eq shiftUuid }
                            .single()[EventVolunteerShiftTable.status]
                    }
                shiftStatus shouldBe EventVolunteerShiftStatus.CANCELLED
                val confirmedCount =
                    transaction {
                        EventVolunteerSignupTable
                            .selectAll()
                            .where {
                                (EventVolunteerSignupTable.shiftId eq shiftUuid) and
                                    (EventVolunteerSignupTable.status eq EventVolunteerSignupStatus.CONFIRMED)
                            }.count()
                    }
                if (signupStatus.get() == HttpStatusCode.OK) {
                    confirmedCount shouldBe 1L
                } else {
                    confirmedCount shouldBe 0L
                }
            }
        }
    })
