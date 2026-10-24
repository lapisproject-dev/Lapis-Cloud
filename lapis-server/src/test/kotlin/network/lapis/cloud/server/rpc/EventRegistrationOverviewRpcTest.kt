package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.events.EventOnlineFixtures
import network.lapis.cloud.server.events.EventRegistrationMessageNotifier
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.routes.registerEventRegistrationCsvRoutes
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventRegistrationOverviewDto
import network.lapis.cloud.shared.domain.EventRegistrationPaymentState
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.204 -- `getRegistrationOverview`, `registerSelfWithMessage` and the CSV route through the real service / route code:
 * who may call what (anonymous, MEMBER, FRIEND, GUEST, TREASURER, the creator without a board role, BOARD, ADMIN), the exact counters,
 * the effective status of a lapsed hold, that the message leaves the server ONLY through the overview and the export, and the audit entry.
 */
class EventRegistrationOverviewRpcTest :
    FunSpec({
        val fx = EventOnlineFixtures()
        val json = Json { ignoreUnknownKeys = true }

        beforeSpec { DatabaseConfig.connect() }

        // The audit entries of the export pin their actor member (FK), so the fixture members stay behind (unique emails; the H2 database is per JVM).
        afterSpec {
            transaction {
                EventRegistrationTable.deleteWhere { eventId inList fx.eventIds }
                EventTable.deleteWhere { id inList fx.eventIds }
            }
        }

        val dispatcher = MailDispatcher(transport = NoOpMailTransport(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

        fun generous() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        fun Route.registerRoutes(exportLimiter: FederationInboxRateLimiter = generous()) {
            fun serviceFor(call: ApplicationCall) =
                EventService(
                    call = call,
                    checkoutGateways = emptyMap(),
                    baseUrl = "https://example.org",
                    mailDispatcher = dispatcher,
                    writeRateLimiter = generous(),
                    checkInRateLimiter = generous(),
                    seriesPreviewRateLimiter = generous(),
                    messageNotifier = EventRegistrationMessageNotifier.DISABLED,
                )
            post("/t/{id}/overview") {
                val dto = serviceFor(call).getRegistrationOverview(call.parameters["id"]!!)
                call.respondText(Json.encodeToString(EventRegistrationOverviewDto.serializer(), dto))
            }
            post("/t/{id}/register") {
                val message = call.request.queryParameters["m"]
                val result = serviceFor(call).registerSelfWithMessage(eventId = call.parameters["id"]!!, message = message)
                call.respondText("${result.registration.status}|${result.registration.message}")
            }
            post("/t/{id}/list") {
                val rows = serviceFor(call).listRegistrations(call.parameters["id"]!!)
                call.respondText(rows.joinToString(",") { "${it.message}" })
            }
            post("/t/{id}/cancel-own") {
                call.respondText("${serviceFor(call).cancelOwnRegistration(call.parameters["id"]!!).message}")
            }
            registerEventRegistrationCsvRoutes(exportRateLimiter = exportLimiter, maxRows = 6)
        }

        suspend fun withApp(
            exportLimiter: FederationInboxRateLimiter = generous(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
            application {
                install(StatusPages) {
                    exception<UnauthenticatedException> {
                        call,
                        cause,
                        ->
                        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                    }
                    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
                }
                routing { registerRoutes(exportLimiter) }
            }
            block()
        }

        suspend fun ApplicationTestBuilder.overview(
            event: Uuid,
            member: Uuid?,
        ): HttpResponse = client.post("/t/$event/overview") { if (member != null) header("X-Member-Id", member.toString()) }

        fun setMessage(
            registration: Uuid,
            text: String?,
        ) {
            transaction { EventRegistrationTable.update({ EventRegistrationTable.id eq registration }) { it[message] = text } }
        }

        test("only BOARD and ADMIN get the overview; everybody else and the anonymous caller are refused") {
            val creatorMember = fx.member(role = AccountRole.MEMBER)
            val event = fx.event(createdBy = creatorMember, kind = EventOnlineKind.NONE)
            withApp {
                overview(event, null).status shouldBe HttpStatusCode.Unauthorized
                overview(event, fx.member(role = AccountRole.MEMBER)).status shouldBe HttpStatusCode.Forbidden
                overview(event, fx.member(role = AccountRole.MEMBER, status = MemberStatus.FRIEND)).status shouldBe HttpStatusCode.Forbidden
                overview(event, fx.member(role = AccountRole.MEMBER, status = MemberStatus.GUEST)).status shouldBe HttpStatusCode.Forbidden
                overview(event, fx.member(role = AccountRole.TREASURER)).status shouldBe HttpStatusCode.Forbidden
                // the creator whose role is only MEMBER (the role was withdrawn later) does not see it either
                overview(event, creatorMember).status shouldBe HttpStatusCode.Forbidden
                overview(event, fx.member(role = AccountRole.BOARD)).status shouldBe HttpStatusCode.OK
                overview(event, fx.member(role = AccountRole.ADMIN)).status shouldBe HttpStatusCode.OK
            }
        }

        test("an unknown or malformed id is refused for a board member without echoing the input") {
            val board = fx.member(role = AccountRole.BOARD)
            withApp {
                val unknown = client.post("/t/${Uuid.random()}/overview") { header("X-Member-Id", board.toString()) }
                (unknown.status == HttpStatusCode.NotFound || unknown.status == HttpStatusCode.InternalServerError) shouldBe true
            }
        }

        test("the counters are exact: a lapsed hold counts as cancelled/expired and activeCount equals the occupied seats") {
            val board = fx.member(role = AccountRole.BOARD)
            val event = fx.event(createdBy = board, kind = EventOnlineKind.NONE, capacity = 5)
            val confirmed = fx.registration(eventId = event, memberId = fx.member(), status = EventRegistrationStatus.CONFIRMED)
            val pending = fx.registration(eventId = event, memberId = fx.member(), status = EventRegistrationStatus.PENDING_PAYMENT)
            val lapsed = fx.registration(eventId = event, memberId = fx.member(), status = EventRegistrationStatus.PENDING_PAYMENT)
            transaction {
                EventRegistrationTable.update({ EventRegistrationTable.id eq lapsed }) {
                    it[holdExpiresAt] = DbClock.nowLocalDateTime().let { n -> LocalDateTime(n.year, n.month, n.day, 0, 0) }
                }
            }
            fx.registration(eventId = event, memberId = fx.member(), status = EventRegistrationStatus.WAITLISTED)
            fx.registration(eventId = event, memberId = fx.member(), status = EventRegistrationStatus.CANCELLED)
            fx.registration(eventId = event, memberId = fx.member(), status = EventRegistrationStatus.EXPIRED)
            setMessage(confirmed, "Hallo")
            withApp {
                val body = overview(event, board).bodyAsText()
                val dto = json.decodeFromString(EventRegistrationOverviewDto.serializer(), body)
                dto.capacity shouldBe 5
                dto.activeCount shouldBe 2
                dto.waitlistedCount shouldBe 1
                dto.inactiveCount shouldBe 3
                dto.freeSeats shouldBe 3
                dto.withMessageCount shouldBe 1
                dto.truncated shouldBe false
                dto.rows.size shouldBe 6
                dto.rows.first { it.id == lapsed.toString() }.status shouldBe EventRegistrationStatus.EXPIRED
                dto.rows.first { it.id == pending.toString() }.status shouldBe EventRegistrationStatus.PENDING_PAYMENT
                dto.rows.first { it.id == pending.toString() }.paymentState shouldBe EventRegistrationPaymentState.FREE
                // confirmed and pending first, then the waiting list, then the inactive ones
                dto.rows
                    .map { it.status }
                    .take(2)
                    .toSet() shouldBe
                    setOf(EventRegistrationStatus.CONFIRMED, EventRegistrationStatus.PENDING_PAYMENT)
                dto.rows[2].status shouldBe EventRegistrationStatus.WAITLISTED
                dto.rows.first { it.id == confirmed.toString() }.message shouldBe "Hallo"
            }
        }

        test("without a capacity there is no free-seat figure") {
            val board = fx.member(role = AccountRole.BOARD)
            val event = fx.event(createdBy = board, kind = EventOnlineKind.NONE, capacity = null)
            fx.registration(eventId = event, memberId = fx.member())
            withApp {
                val dto = json.decodeFromString(EventRegistrationOverviewDto.serializer(), overview(event, board).bodyAsText())
                dto.freeSeats shouldBe null
                dto.capacity shouldBe null
                dto.activeCount shouldBe 1
            }
        }

        test("registerSelfWithMessage stores the message, a too-long one is a BadRequest without a row, blank is null") {
            val board = fx.member(role = AccountRole.BOARD)
            val event = fx.event(createdBy = board, kind = EventOnlineKind.NONE)
            val a = fx.member()
            val b = fx.member()
            val c = fx.member()
            withApp {
                val ok = client.post("/t/$event/register?m=Bitte%20Rampe") { header("X-Member-Id", a.toString()) }
                ok.status shouldBe HttpStatusCode.OK
                // the registration DTO a member gets back NEVER carries the message
                ok.bodyAsText() shouldBe "CONFIRMED|null"
                val tooLong = client.post("/t/$event/register?m=${"a".repeat(501)}") { header("X-Member-Id", b.toString()) }
                tooLong.status shouldBe HttpStatusCode.BadRequest
                tooLong.bodyAsText() shouldBe "Die Nachricht darf höchstens 500 Zeichen lang sein."
                transaction { EventRegistrationTable.selectAll().where { EventRegistrationTable.memberId eq b }.count() } shouldBe 0
                val blank = client.post("/t/$event/register?m=%20%20") { header("X-Member-Id", c.toString()) }
                blank.status shouldBe HttpStatusCode.OK
                transaction {
                    EventRegistrationTable
                        .selectAll()
                        .where { EventRegistrationTable.memberId eq c }
                        .single()[EventRegistrationTable.message]
                } shouldBe null
                val stored =
                    transaction {
                        EventRegistrationTable
                            .selectAll()
                            .where { EventRegistrationTable.memberId eq a }
                            .single()[EventRegistrationTable.message]
                    }
                stored shouldBe "Bitte Rampe"
            }
        }

        test(
            "the message appears in no other registration DTO: listRegistrations, cancelOwnRegistration and the registration result carry null",
        ) {
            val board = fx.member(role = AccountRole.BOARD)
            val event = fx.event(createdBy = board, kind = EventOnlineKind.NONE)
            val member = fx.member()
            withApp {
                client.post("/t/$event/register?m=Geheim") { header("X-Member-Id", member.toString()) }.status shouldBe HttpStatusCode.OK
                val list = client.post("/t/$event/list") { header("X-Member-Id", board.toString()) }.bodyAsText()
                list shouldBe "null"
                list shouldNotContain "Geheim"
                client.post("/t/$event/cancel-own") { header("X-Member-Id", member.toString()) }.bodyAsText() shouldBe "null"
            }
        }

        test("CSV export: role and unknown-id order, headers, content, and exactly one audit entry without any personal data") {
            val board = fx.member(role = AccountRole.BOARD, name = "Berta Board")
            val event = fx.event(createdBy = board, kind = EventOnlineKind.NONE)
            val r1 =
                fx.registration(
                    eventId = event,
                    memberId = fx.member(name = "=Evil Member"),
                    status = EventRegistrationStatus.CONFIRMED,
                )
            val r2 = fx.registration(eventId = event, memberId = null, status = EventRegistrationStatus.WAITLISTED)
            setMessage(r1, "Zeile 1\nZeile 2")
            setMessage(r2, "@gast")
            withApp {
                // a plain member learns nothing about the id: 403 for an unknown id as for a known one
                val member = fx.member()
                client.get("/api/events/${Uuid.random()}/registrations.csv") { header("X-Member-Id", member.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.get("/api/events/$event/registrations.csv") { header("X-Member-Id", member.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.get("/api/events/$event/registrations.csv").status shouldBe HttpStatusCode.Unauthorized
                client
                    .get("/api/events/${Uuid.random()}/registrations.csv") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
                client.get("/api/events/not-a-uuid/registrations.csv") { header("X-Member-Id", board.toString()) }.also {
                    it.status shouldBe HttpStatusCode.NotFound
                    it.bodyAsText() shouldNotContain "not-a-uuid"
                }
                val before = transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq event }.count() }
                before shouldBe 0
                val response = client.get("/api/events/$event/registrations.csv") { header("X-Member-Id", board.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldContain "text/csv"
                response.headers[HttpHeaders.ContentType]!!.lowercase() shouldContain "charset=utf-8"
                response.headers[HttpHeaders.ContentDisposition] shouldContain "attachment; filename=\"anmeldungen-$event-"
                response.headers[HttpHeaders.ContentDisposition] shouldContain "filename*=UTF-8''anmeldungen-$event-"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                val bytes = response.bodyAsBytes()
                bytes.take(3) shouldBe listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                val text = bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
                text shouldStartWith "Name;Art;Status;"
                text shouldContain "\"'=Evil Member\";Mitglied;Angemeldet;"
                text shouldContain "\"Zeile 1\nZeile 2\""
                text shouldContain "\"'@gast\""
                text shouldContain "Gast;Gast;Warteliste;1;"
                val entries =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityId eq event }
                            .map {
                                Triple(
                                    it[AuditLogEntryTable.entityType],
                                    it[AuditLogEntryTable.actorMemberId],
                                    it[AuditLogEntryTable.afterSnapshot],
                                )
                            }
                    }
                entries.size shouldBe 1
                entries.single().first shouldBe AuditEntityType.EVENT_REGISTRATION_EXPORT
                entries.single().second shouldBe board
                entries.single().third shouldBe "{\"rowCount\":2,\"format\":\"csv\"}"
                entries.single().third!! shouldNotContain "Evil"
                entries.single().third!! shouldNotContain "gast"
            }
        }

        test("CSV export refuses more rows than the ceiling with 409 and is rate limited") {
            val board = fx.member(role = AccountRole.BOARD)
            val big = fx.event(createdBy = board, kind = EventOnlineKind.NONE)
            repeat(7) { fx.registration(eventId = big, memberId = fx.member()) }
            withApp(exportLimiter = FederationInboxRateLimiter(maxRequests = 3, window = 1.minutes)) {
                client.get("/api/events/$big/registrations.csv") { header("X-Member-Id", board.toString()) }.also {
                    it.status shouldBe HttpStatusCode.Conflict
                    it.bodyAsText() shouldBe "Zu viele Anmeldungen für einen Export."
                }
                val small = fx.event(createdBy = board, kind = EventOnlineKind.NONE)
                val seen = mutableListOf<HttpStatusCode>()
                repeat(3) {
                    seen += client.get("/api/events/$small/registrations.csv") { header("X-Member-Id", board.toString()) }.status
                }
                seen shouldContainExactly listOf(HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.TooManyRequests)
            }
        }
    })
