package network.lapis.cloud.server.rpc

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberHonorTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberHonorCategory
import network.lapis.cloud.shared.domain.MemberHonorInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

/**
 * Welle V1.4.4.3 "Mitgliederlebenszyklus: Ehrungsverwaltung" -- exercises [MemberHonorService]
 * directly through throwaway routes, same house style [MemberAnniversaryServiceTest]/
 * [BoardMembershipServiceTest] establish (own fixtures, direct table inserts, `X-Member-Id`
 * trusted-header auth, a fixed `today` injected via the service's own `clock` constructor
 * parameter).
 */
class MemberHonorServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val today = LocalDate(2026, 6, 15)

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterTest {
            transaction { MemberHonorTable.deleteAll() }
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createMember(
            email: String,
            role: AccountRole,
            anonymizedAt: LocalDateTime? = null,
            displayName: String = "Fixture Mitglied ${Uuid.random().toString().take(6)}",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[MemberTable.email] = email
                    it[MemberTable.status] = MemberStatus.ACTIVE
                    it[MemberTable.joinedAt] = LocalDate(2020, 1, 1)
                    it[MemberTable.anonymizedAt] = anonymizedAt
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

        fun captureRootLogEvents(block: () -> Unit): List<ILoggingEvent> {
            val root = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            root.addAppender(appender)
            return try {
                block()
                appender.list.toList()
            } finally {
                root.detachAppender(appender)
            }
        }

        fun Route.registerHonorTestRoutes() {
            fun service(callArg: io.ktor.server.application.ApplicationCall) = MemberHonorService(call = callArg, clock = { today })

            get("/test/honor/list") {
                val q = call.request.queryParameters
                val category = q["category"]?.let { MemberHonorCategory.valueOf(it) }
                val page =
                    service(call).listHonors(
                        memberId = q["memberId"],
                        category = category,
                        limit = q["limit"]?.toInt() ?: 50,
                        offset = q["offset"]?.toInt() ?: 0,
                    )
                call.respondText("${page.totalCount}|${page.limit}|${page.entries.joinToString(",") { it.id }}")
            }
            post("/test/honor/create") {
                val q = call.request.queryParameters
                val dto =
                    service(call).createHonor(
                        MemberHonorInput(
                            memberId = q["memberId"]!!,
                            category = q["category"]?.let { MemberHonorCategory.valueOf(it) } ?: MemberHonorCategory.SERVICE_AWARD,
                            title = q["title"] ?: "Testtitel",
                            awardedAt = q["awardedAt"]?.let { LocalDate.parse(it) } ?: today,
                            awardedBy = q["awardedBy"],
                            note = q["note"],
                        ),
                    )
                call.respondText(dto.id)
            }
            post("/test/honor/update/{id}") {
                val q = call.request.queryParameters
                val dto =
                    service(call).updateHonor(
                        id = call.parameters["id"]!!,
                        input =
                            MemberHonorInput(
                                memberId = q["memberId"]!!,
                                category = q["category"]?.let { MemberHonorCategory.valueOf(it) } ?: MemberHonorCategory.SERVICE_AWARD,
                                title = q["title"] ?: "Testtitel",
                                awardedAt = q["awardedAt"]?.let { LocalDate.parse(it) } ?: today,
                                awardedBy = q["awardedBy"],
                                note = q["note"],
                            ),
                    )
                call.respondText(dto.id)
            }
            delete("/test/honor/delete/{id}") {
                service(call).deleteHonor(call.parameters["id"]!!)
                call.respondText("ok")
            }
        }

        // ── Rollen-Matrix ────────────────────────────────────────────────

        listOf(AccountRole.MEMBER, AccountRole.TREASURER).forEach { deniedRole ->
            test("Rolle $deniedRole wird bei listHonors/createHonor abgelehnt (ForbiddenException)") {
                testApplication {
                    application {
                        install(StatusPages) { installHonorExceptionHandlers() }
                        routing { registerHonorTestRoutes() }
                    }
                    val caller = createMember(email = "honor-role-$deniedRole-${Uuid.random()}@example.org", role = deniedRole)
                    val target = createMember(email = "honor-target-$deniedRole-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                    client.get("/test/honor/list") { header("X-Member-Id", caller.toString()) }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .post("/test/honor/create") {
                            header("X-Member-Id", caller.toString())
                            parameter("memberId", target.toString())
                        }.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        listOf(AccountRole.BOARD, AccountRole.ADMIN).forEach { allowedRole ->
            test("Rolle $allowedRole darf listHonors/createHonor/updateHonor") {
                testApplication {
                    application {
                        install(StatusPages) { installHonorExceptionHandlers() }
                        routing { registerHonorTestRoutes() }
                    }
                    val caller = createMember(email = "honor-allowed-$allowedRole-${Uuid.random()}@example.org", role = allowedRole)
                    val target = createMember(email = "honor-target2-$allowedRole-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                    client.get("/test/honor/list") { header("X-Member-Id", caller.toString()) }.status shouldBe HttpStatusCode.OK
                    val createResponse =
                        client.post("/test/honor/create") {
                            header("X-Member-Id", caller.toString())
                            parameter("memberId", target.toString())
                        }
                    createResponse.status shouldBe HttpStatusCode.OK
                    val honorId = createResponse.bodyAsText()
                    client
                        .post("/test/honor/update/$honorId") {
                            header("X-Member-Id", caller.toString())
                            parameter("memberId", target.toString())
                            parameter("title", "Geändert")
                        }.status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("BOARD wird bei deleteHonor abgelehnt (ForbiddenException) -- ADMIN-only") {
            testApplication {
                application {
                    install(StatusPages) { installHonorExceptionHandlers() }
                    routing { registerHonorTestRoutes() }
                }
                val board = createMember(email = "honor-del-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val admin = createMember(email = "honor-del-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val target = createMember(email = "honor-del-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val created =
                    client
                        .post("/test/honor/create") {
                            header("X-Member-Id", admin.toString())
                            parameter("memberId", target.toString())
                        }.bodyAsText()
                client.delete("/test/honor/delete/$created") { header("X-Member-Id", board.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.delete("/test/honor/delete/$created") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.OK
            }
        }

        // ── Validierung ──────────────────────────────────────────────────

        test("awardedAt in der Zukunft wird abgelehnt (BadRequestException)") {
            testApplication {
                application {
                    install(StatusPages) { installHonorExceptionHandlers() }
                    routing { registerHonorTestRoutes() }
                }
                val board = createMember(email = "honor-future-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val target = createMember(email = "honor-future-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                client
                    .post("/test/honor/create") {
                        header("X-Member-Id", board.toString())
                        parameter("memberId", target.toString())
                        parameter("awardedAt", "2026-06-16")
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("leerer Titel wird abgelehnt (BadRequestException)") {
            testApplication {
                application {
                    install(StatusPages) { installHonorExceptionHandlers() }
                    routing { registerHonorTestRoutes() }
                }
                val board = createMember(email = "honor-blanktitle-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val target = createMember(email = "honor-blanktitle-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                client
                    .post("/test/honor/create") {
                        header("X-Member-Id", board.toString())
                        parameter("memberId", target.toString())
                        parameter("title", "   ")
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("überlanger Titel wird abgelehnt (BadRequestException)") {
            testApplication {
                application {
                    install(StatusPages) { installHonorExceptionHandlers() }
                    routing { registerHonorTestRoutes() }
                }
                val board = createMember(email = "honor-longtitle-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val target = createMember(email = "honor-longtitle-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                client
                    .post("/test/honor/create") {
                        header("X-Member-Id", board.toString())
                        parameter("memberId", target.toString())
                        parameter("title", "x".repeat(201))
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("memberId zeigt auf anonymisiertes Mitglied -- wird abgelehnt (BadRequestException)") {
            testApplication {
                application {
                    install(StatusPages) { installHonorExceptionHandlers() }
                    routing { registerHonorTestRoutes() }
                }
                val board = createMember(email = "honor-anon-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val anonymized =
                    createMember(
                        email = "honor-anon-target-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        anonymizedAt = LocalDateTime(2026, 1, 1, 0, 0),
                    )
                client
                    .post("/test/honor/create") {
                        header("X-Member-Id", board.toString())
                        parameter("memberId", anonymized.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("memberId ohne existierendes Mitglied wird abgelehnt (BadRequestException)") {
            testApplication {
                application {
                    install(StatusPages) { installHonorExceptionHandlers() }
                    routing { registerHonorTestRoutes() }
                }
                val board = createMember(email = "honor-missing-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                client
                    .post("/test/honor/create") {
                        header("X-Member-Id", board.toString())
                        parameter("memberId", Uuid.random().toString())
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("limit > 200 wird serverseitig auf 200 gedeckelt (keine Ablehnung)") {
            testApplication {
                application {
                    install(StatusPages) { installHonorExceptionHandlers() }
                    routing { registerHonorTestRoutes() }
                }
                val board = createMember(email = "honor-limit-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val response =
                    client.get("/test/honor/list") {
                        header("X-Member-Id", board.toString())
                        parameter("limit", "9999")
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().split("|")[1] shouldBe "200"
            }
        }

        // ── Filter ───────────────────────────────────────────────────────

        test("listHonors(memberId=X) filtert korrekt auf ein einzelnes Mitglied") {
            testApplication {
                application {
                    install(StatusPages) { installHonorExceptionHandlers() }
                    routing { registerHonorTestRoutes() }
                }
                val board = createMember(email = "honor-filter-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val targetA = createMember(email = "honor-filter-a-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val targetB = createMember(email = "honor-filter-b-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                client.post("/test/honor/create") {
                    header("X-Member-Id", board.toString())
                    parameter("memberId", targetA.toString())
                }
                client.post("/test/honor/create") {
                    header("X-Member-Id", board.toString())
                    parameter("memberId", targetB.toString())
                }

                val response =
                    client.get("/test/honor/list") {
                        header("X-Member-Id", board.toString())
                        parameter("memberId", targetA.toString())
                    }
                val parts = response.bodyAsText().split("|")
                parts[0] shouldBe "1"
            }
        }

        test("listHonors(category=Y) filtert korrekt auf eine Kategorie") {
            testApplication {
                application {
                    install(StatusPages) { installHonorExceptionHandlers() }
                    routing { registerHonorTestRoutes() }
                }
                val board = createMember(email = "honor-catfilter-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val target = createMember(email = "honor-catfilter-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                client.post("/test/honor/create") {
                    header("X-Member-Id", board.toString())
                    parameter("memberId", target.toString())
                    parameter("category", MemberHonorCategory.HONORARY_MEMBERSHIP.name)
                }
                client.post("/test/honor/create") {
                    header("X-Member-Id", board.toString())
                    parameter("memberId", target.toString())
                    parameter("category", MemberHonorCategory.OTHER.name)
                }

                val response =
                    client.get("/test/honor/list") {
                        header("X-Member-Id", board.toString())
                        parameter("memberId", target.toString())
                        parameter("category", MemberHonorCategory.HONORARY_MEMBERSHIP.name)
                    }
                response.bodyAsText().split("|")[0] shouldBe "1"
            }
        }

        // ── Sortierung ───────────────────────────────────────────────────

        test("Sortierreihenfolge ist awarded_at DESC, title ASC") {
            testApplication {
                application {
                    install(StatusPages) { installHonorExceptionHandlers() }
                    routing { registerHonorTestRoutes() }
                }
                val board = createMember(email = "honor-sort-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val target = createMember(email = "honor-sort-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val earlyA =
                    client
                        .post("/test/honor/create") {
                            header("X-Member-Id", board.toString())
                            parameter("memberId", target.toString())
                            parameter("awardedAt", "2020-01-01")
                            parameter("title", "AAA")
                        }.bodyAsText()
                val lateB =
                    client
                        .post("/test/honor/create") {
                            header("X-Member-Id", board.toString())
                            parameter("memberId", target.toString())
                            parameter("awardedAt", "2025-01-01")
                            parameter("title", "BBB")
                        }.bodyAsText()

                val response =
                    client.get("/test/honor/list") {
                        header("X-Member-Id", board.toString())
                        parameter("memberId", target.toString())
                    }
                val ids = response.bodyAsText().split("|")[2].split(",")
                ids shouldBe listOf(lateB, earlyA)
            }
        }

        // ── Log-Redaction ────────────────────────────────────────────────

        test("Log-Zeile enthält weder Titel noch Notiz noch awardedBy") {
            testApplication {
                application {
                    install(StatusPages) { installHonorExceptionHandlers() }
                    routing { registerHonorTestRoutes() }
                }
                val board = createMember(email = "honor-log-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val target = createMember(email = "honor-log-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val secretTitle = "SehrEinzigartigerEhrungsTitel"
                val secretNote = "SehrEinzigartigeVorstandsNotiz"

                val logEvents =
                    captureRootLogEvents {
                        runBlocking {
                            val response =
                                client.post("/test/honor/create") {
                                    header("X-Member-Id", board.toString())
                                    parameter("memberId", target.toString())
                                    parameter("title", secretTitle)
                                    parameter("note", secretNote)
                                }
                            response.status shouldBe HttpStatusCode.OK
                        }
                    }

                val relevant = logEvents.filter { it.formattedMessage.contains("member honor created") }
                relevant.isEmpty() shouldBe false
                relevant.forEach { event ->
                    event.formattedMessage.contains(secretTitle) shouldBe false
                    event.formattedMessage.contains(secretNote) shouldBe false
                }
            }
        }

        // ── NotFoundException ────────────────────────────────────────────

        test("updateHonor mit unbekannter Id wirft NotFoundException") {
            testApplication {
                application {
                    install(StatusPages) { installHonorExceptionHandlers() }
                    routing { registerHonorTestRoutes() }
                }
                val board = createMember(email = "honor-notfound-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val target = createMember(email = "honor-notfound-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                client
                    .post("/test/honor/update/${Uuid.random()}") {
                        header("X-Member-Id", board.toString())
                        parameter("memberId", target.toString())
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }
    })

private fun StatusPagesConfig.installHonorExceptionHandlers() {
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
}
