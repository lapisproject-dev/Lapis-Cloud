package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.rpc.EventImportService
import network.lapis.cloud.server.rpc.EventService
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventInput
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.9.82 -- the RPC surface of the event import (H2 only: the trusted `X-Member-Id` header that drives these throwaway routes
 * exists exclusively on the in-memory test database, see `AuthTestMode`). The database behaviour of the import itself is covered on
 * both databases by [EventImportScenarios].
 */
class EventImportServiceRpcTest :
    FunSpec({
        val slugPrefix = "imprpc-${Uuid.random().toString().take(8)}-"
        beforeSpec {
            DatabaseConfig.connect()
            // Seeded demo members act here: the audit log is append-only, so a member created by this spec could never be deleted again, and
            // `DevSeedData.seedIfEmpty` (used by other specs) only seeds an EMPTY member table.
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { transaction { EventTable.deleteWhere { slug like "$slugPrefix%" } } }

        fun seeded(role: AccountRole): Uuid = DevSeedData.demoMembers.first { it.role == role }.id

        fun json(n: Int) =
            """[{"slug":"$slugPrefix$n","title":"RPC $n","description":"Text","startsAt":"2025-03-01T19:00","endsAt":"2025-03-01T22:00","locationText":"Ort"}]"""

        fun eventCount() =
            transaction {
                EventTable
                    .selectAll()
                    .where { EventTable.slug like "$slugPrefix%" }
                    .count()
                    .toInt()
            }

        fun Route.importRoutes(limiter: FederationInboxRateLimiter) {
            fun serviceFor(call: ApplicationCall) =
                EventImportService(call = call, previewRateLimiter = limiter, commitRateLimiter = limiter)
            post("/test/import/preview") {
                val result = serviceFor(call).previewEventImport(call.receiveText())
                call.respondText("${result.createCount}/${result.skipCount}/${result.errorCount}")
            }
            post("/test/import/commit") {
                val result =
                    serviceFor(
                        call,
                    ).commitEventImport(json = call.receiveText(), payloadSha256 = call.request.queryParameters["hash"].orEmpty())
                call.respondText("${result.created}/${result.skipped}")
            }
        }

        test("MEMBER and TREASURER are forbidden on preview and commit and nothing is written; BOARD and ADMIN are allowed") {
            val member = seeded(AccountRole.MEMBER)
            val treasurer = seeded(AccountRole.TREASURER)
            val admin = seeded(AccountRole.ADMIN)
            val board = seeded(AccountRole.BOARD)
            val payload = json(1)
            val hash = EventImportPolicy.sha256Hex(payload)
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing { importRoutes(FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)) }
                }
                for (who in listOf(member, treasurer)) {
                    client
                        .post("/test/import/preview") {
                            header("X-Member-Id", who.toString())
                            setBody(payload)
                        }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .post("/test/import/commit?hash=$hash") {
                            header("X-Member-Id", who.toString())
                            setBody(payload)
                        }.status shouldBe HttpStatusCode.Forbidden
                }
                eventCount() shouldBe 0
                client
                    .post("/test/import/preview") {
                        header("X-Member-Id", admin.toString())
                        setBody(payload)
                    }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/import/commit?hash=$hash") {
                        header("X-Member-Id", board.toString())
                        setBody(payload)
                    }.status shouldBe HttpStatusCode.OK
                eventCount() shouldBe 1
            }
        }

        test("the authorization check comes before any parsing: a forbidden caller with garbage gets 403, not 400") {
            val member = seeded(AccountRole.MEMBER)
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                        exception<BadRequestException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.BadRequest)
                        }
                    }
                    routing { importRoutes(FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)) }
                }
                client
                    .post("/test/import/preview") {
                        header("X-Member-Id", member.toString())
                        setBody("{ not json")
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("the preview is rate limited per member") {
            val board = seeded(AccountRole.BOARD)
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing { importRoutes(FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) }
                }
                val payload = json(2)
                client
                    .post("/test/import/preview") {
                        header("X-Member-Id", board.toString())
                        setBody(payload)
                    }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/import/preview") {
                        header("X-Member-Id", board.toString())
                        setBody(payload)
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "an imported event is editable through the normal form (unchanged start), stays imported; a NEW event in the past is still refused",
        ) {
            val board = seeded(AccountRole.BOARD)
            val payload = json(3)
            val imported =
                EventImporter.commit(
                    json = payload,
                    payloadSha256 = EventImportPolicy.sha256Hex(payload),
                    actorMemberId = board,
                    actorRole = AccountRole.BOARD,
                    now = LocalDateTime(2026, 10, 9, 10, 0),
                    wallNow = LocalDateTime(2026, 10, 9, 12, 0),
                )
            imported.created shouldBe 1
            val eventId = transaction { EventTable.selectAll().where { EventTable.slug eq "${slugPrefix}3" }.single()[EventTable.id] }

            fun input(startsAt: LocalDateTime) =
                EventInput(
                    title = "Nachträglich bearbeitet",
                    description = "Neue Beschreibung",
                    locationText = "Ort",
                    startsAt = startsAt,
                    endsAt = LocalDateTime(startsAt.year, startsAt.monthNumber, startsAt.dayOfMonth, 22, 0),
                    feeAmount = BigDecimal.ZERO,
                    visibility = EventVisibility.PUBLIC,
                )
            testApplication {
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
                        fun service(call: ApplicationCall) =
                            EventService(
                                call = call,
                                checkoutGateways = emptyMap(),
                                baseUrl = "https://example.org",
                                mailDispatcher =
                                    MailDispatcher(
                                        transport = NoOpMailTransport(),
                                        scope =
                                            CoroutineScope(
                                                SupervisorJob() + Dispatchers.IO,
                                            ),
                                    ),
                                writeRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                                checkInRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                                seriesPreviewRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                            )
                        post("/test/update") {
                            service(call).updateEvent(id = eventId.toString(), input = input(LocalDateTime(2025, 3, 1, 19, 0)))
                            call.respondText("ok")
                        }
                        post("/test/create-past") {
                            service(call).createEvent(input(LocalDateTime(2020, 1, 1, 19, 0)))
                            call.respondText("ok")
                        }
                    }
                }
                client.post("/test/update") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.OK
                client.post("/test/create-past") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.BadRequest
            }
            val after = transaction { EventTable.selectAll().where { EventTable.id eq eventId }.single() }
            after[EventTable.title] shouldBe "Nachträglich bearbeitet"
            after[EventTable.imported] shouldBe true
        }
    })
