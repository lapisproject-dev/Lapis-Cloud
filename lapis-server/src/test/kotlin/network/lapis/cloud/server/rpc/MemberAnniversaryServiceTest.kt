package network.lapis.cloud.server.rpc

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

/**
 * Welle V1.4.4.2 "Geburtstage & Jubiläen" -- exercises [MemberAnniversaryService] directly through
 * throwaway routes, same house style [MemberFinancialHistoryServiceTest]/[SepaServiceTest] establish
 * (own fixtures, direct table inserts, `X-Member-Id` trusted-header auth, a fixed `today` injected
 * via the service's own `clock` constructor parameter -- no real-wall-clock dependence anywhere in
 * this suite).
 */
class MemberAnniversaryServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        // A fixed "today" for every test in this suite -- see [AnniversaryCalendar] KDoc for why the
        // service accepts an injectable clock at all.
        val today = LocalDate(2026, 6, 15)

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
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
            status: MemberStatus = MemberStatus.ACTIVE,
            joinedAt: LocalDate = LocalDate(2020, 1, 1),
            dateOfBirth: LocalDate? = null,
            anonymizedAt: LocalDateTime? = null,
            displayName: String = "Fixture Mitglied ${Uuid.random().toString().take(6)}",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[MemberTable.joinedAt] = joinedAt
                    it[MemberTable.dateOfBirth] = dateOfBirth
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

        /** Attaches a logback [ListAppender] to the ROOT logger for the duration of [block], returns the captured events. */
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

        fun Route.registerAnniversaryTestRoutes() {
            get("/test/anniv/get") {
                val windowDays = call.request.queryParameters["windowDays"]!!.toInt()
                val dto = MemberAnniversaryService(call = call, clock = { today }).getUpcomingAnniversaries(windowDays)
                call.respondText(
                    "${dto.windowDays}:${dto.from}:${dto.through}:${dto.entries.size}:" +
                        "${dto.eligibleMemberCount}:${dto.membersWithoutDateOfBirth}",
                )
            }
            get("/test/anniv/entries") {
                val windowDays = call.request.queryParameters["windowDays"]!!.toInt()
                val memberId = call.request.queryParameters["memberId"]
                val dto = MemberAnniversaryService(call = call, clock = { today }).getUpcomingAnniversaries(windowDays)
                val entries = if (memberId != null) dto.entries.filter { it.memberId == memberId } else dto.entries
                call.respondText(entries.joinToString("|") { "${it.kind}:${it.memberStatus}:${it.occursOn}:${it.years}:${it.emphasis}" })
            }
        }

        // ── windowDays validation ────────────────────────────────────────

        listOf(0, 91, -1).forEach { badWindow ->
            test("windowDays=$badWindow wird abgelehnt (BadRequestException)") {
                testApplication {
                    application {
                        install(StatusPages) { installAnniversaryExceptionHandlers() }
                        routing { registerAnniversaryTestRoutes() }
                    }
                    val board = createMember(email = "anniv-badwindow-$badWindow-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                    val response = client.get("/test/anniv/get?windowDays=$badWindow") { header("X-Member-Id", board.toString()) }
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        listOf(30, 60, 90).forEach { goodWindow ->
            test("windowDays=$goodWindow wird akzeptiert") {
                testApplication {
                    application {
                        install(StatusPages) { installAnniversaryExceptionHandlers() }
                        routing { registerAnniversaryTestRoutes() }
                    }
                    val board = createMember(email = "anniv-goodwindow-$goodWindow-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                    val response = client.get("/test/anniv/get?windowDays=$goodWindow") { header("X-Member-Id", board.toString()) }
                    response.status shouldBe HttpStatusCode.OK
                }
            }
        }

        // ── Rollen-Schwelle ──────────────────────────────────────────────

        listOf(AccountRole.MEMBER, AccountRole.TREASURER).forEach { deniedRole ->
            test("Rolle $deniedRole wird abgelehnt (ForbiddenException)") {
                testApplication {
                    application {
                        install(StatusPages) { installAnniversaryExceptionHandlers() }
                        routing { registerAnniversaryTestRoutes() }
                    }
                    val member = createMember(email = "anniv-role-$deniedRole-${Uuid.random()}@example.org", role = deniedRole)
                    val response = client.get("/test/anniv/get?windowDays=30") { header("X-Member-Id", member.toString()) }
                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        listOf(AccountRole.BOARD, AccountRole.ADMIN).forEach { allowedRole ->
            test("Rolle $allowedRole erhält Erfolg") {
                testApplication {
                    application {
                        install(StatusPages) { installAnniversaryExceptionHandlers() }
                        routing { registerAnniversaryTestRoutes() }
                    }
                    val member = createMember(email = "anniv-role-$allowedRole-${Uuid.random()}@example.org", role = allowedRole)
                    val response = client.get("/test/anniv/get?windowDays=30") { header("X-Member-Id", member.toString()) }
                    response.status shouldBe HttpStatusCode.OK
                }
            }
        }

        // ── Der zentrale Fund: anonymizedAt schließt aus, auch bei ACTIVE ──

        test(
            "anonymisiertes Mitglied (status=ACTIVE, joinedAt vor 10 Jahren) erscheint NICHT -- " +
                "der zentrale Norman/Jobs-Fund der Design-Session",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val board = createMember(email = "anniv-anon-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val anonymized =
                    createMember(
                        email = "anniv-anon-subject-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        status = MemberStatus.ACTIVE,
                        joinedAt = LocalDate(2016, 6, 15),
                        anonymizedAt = LocalDateTime(2026, 1, 1, 0, 0),
                    )
                val response =
                    client.get("/test/anniv/entries?windowDays=30&memberId=$anonymized") { header("X-Member-Id", board.toString()) }
                response.bodyAsText() shouldBe ""
            }
        }

        // ── DONOR-Jubiläum: eigene Zeile, memberStatus bleibt DONOR ────────

        test("DONOR mit joinedAt vor 10 Jahren erscheint als MEMBERSHIP_ANNIVERSARY mit memberStatus=DONOR") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val board = createMember(email = "anniv-donor-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val donor =
                    createMember(
                        email = "anniv-donor-subject-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        status = MemberStatus.DONOR,
                        joinedAt = LocalDate(2016, 6, 15),
                    )
                val response = client.get("/test/anniv/entries?windowDays=30&memberId=$donor") { header("X-Member-Id", board.toString()) }
                response.bodyAsText() shouldBe "MEMBERSHIP_ANNIVERSARY:DONOR:2026-06-15:10:NOTABLE"
            }
        }

        // ── FRIEND: kein Treffer, weder Geburtstag noch Jubiläum ───────────

        test("FRIEND erscheint nicht, obwohl joinedAt/dateOfBirth im Fenster liegen (Set-Ausschluss)") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val board = createMember(email = "anniv-friend-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val friend =
                    createMember(
                        email = "anniv-friend-subject-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        status = MemberStatus.FRIEND,
                        joinedAt = LocalDate(2016, 6, 15),
                        dateOfBirth = LocalDate(1990, 6, 20),
                    )
                val response = client.get("/test/anniv/entries?windowDays=30&memberId=$friend") { header("X-Member-Id", board.toString()) }
                response.bodyAsText() shouldBe ""
            }
        }

        // ── Fehlendes Geburtsdatum: keine Zeile, aber Coverage-Zähler ──────

        test("dateOfBirth=null erzeugt keine BIRTHDAY-Zeile, zählt aber in membersWithoutDateOfBirth") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val board = createMember(email = "anniv-nodob-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val subject =
                    createMember(
                        email = "anniv-nodob-subject-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        status = MemberStatus.ACTIVE,
                        // Not a milestone joinedAt (2 years, not 1 and not a multiple of 5) so this
                        // member also contributes no MEMBERSHIP_ANNIVERSARY row -- isolates the
                        // dateOfBirth==null/coverage assertion from the joinedAt-milestone logic.
                        joinedAt = LocalDate(2024, 6, 15),
                        dateOfBirth = null,
                    )
                // No entry at all for this specific member (neither BIRTHDAY nor a milestone MEMBERSHIP_ANNIVERSARY).
                val entriesForSubject =
                    client.get("/test/anniv/entries?windowDays=30&memberId=$subject") { header("X-Member-Id", board.toString()) }
                entriesForSubject.bodyAsText() shouldBe ""

                // The aggregate coverage counters DID grow -- this member is eligible (ACTIVE,
                // anonymizedAt IS NULL) and has no dateOfBirth on file.
                val overview = client.get("/test/anniv/get?windowDays=30") { header("X-Member-Id", board.toString()) }
                val fields = overview.bodyAsText().split(":")
                // fields = windowDays:from:through:entryCount:eligibleCount:missingDobCount
                (fields[4].toInt() >= 1) shouldBe true
                (fields[5].toInt() >= 1) shouldBe true
            }
        }

        // ── joinedAt in der Zukunft (Datenfehler): keine Zeile, kein Fehler ──

        test("joinedAt in der Zukunft (Importfehler) erzeugt keine Zeile und wirft keinen Fehler") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val board = createMember(email = "anniv-future-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val futureJoiner =
                    createMember(
                        email = "anniv-future-subject-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        status = MemberStatus.ACTIVE,
                        joinedAt = LocalDate(2027, 6, 15),
                    )
                val response =
                    client.get("/test/anniv/entries?windowDays=30&memberId=$futureJoiner") { header("X-Member-Id", board.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe ""
            }
        }

        // ── dateOfBirth in der Zukunft (Datenfehler): keine Zeile, kein Fehler ──

        test("dateOfBirth in der Zukunft (Importfehler) erzeugt keine BIRTHDAY-Zeile und wirft keinen Fehler") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val board = createMember(email = "anniv-futuredob-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val futureBirthdayMember =
                    createMember(
                        email = "anniv-futuredob-subject-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        status = MemberStatus.ACTIVE,
                        // Not a milestone joinedAt either, so this member contributes no
                        // MEMBERSHIP_ANNIVERSARY row -- isolates the assertion to the BIRTHDAY branch.
                        joinedAt = LocalDate(2024, 6, 15),
                        // today is 2026-06-15 (see suite-level `today`) -- a dateOfBirth in 2027 is a
                        // data-entry error (e.g. typo'd year on import), analogous to the joinedAt
                        // case above. Before the fix this produced a BIRTHDAY row with years=-1.
                        dateOfBirth = LocalDate(2027, 6, 20),
                    )
                val response =
                    client.get(
                        "/test/anniv/entries?windowDays=30&memberId=$futureBirthdayMember",
                    ) { header("X-Member-Id", board.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe ""
            }
        }

        // ── years == 1 -> FIRST_YEAR ────────────────────────────────────

        test("years==1 (frisch nach einem Jahr) erscheint mit emphasis=FIRST_YEAR") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val board = createMember(email = "anniv-firstyear-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val oneYearMember =
                    createMember(
                        email = "anniv-firstyear-subject-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        status = MemberStatus.ACTIVE,
                        joinedAt = LocalDate(2025, 6, 15),
                    )
                val response =
                    client.get("/test/anniv/entries?windowDays=30&memberId=$oneYearMember") { header("X-Member-Id", board.toString()) }
                response.bodyAsText() shouldBe "MEMBERSHIP_ANNIVERSARY:ACTIVE:2026-06-15:1:FIRST_YEAR"
            }
        }

        // ── years nicht durch 5 teilbar und != 1 -> keine Zeile ────────────

        test("years=7 (kein Vielfaches von 5, nicht 1) erzeugt KEINE MEMBERSHIP_ANNIVERSARY-Zeile") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val board = createMember(email = "anniv-sevenyear-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val sevenYearMember =
                    createMember(
                        email = "anniv-sevenyear-subject-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        status = MemberStatus.ACTIVE,
                        joinedAt = LocalDate(2019, 6, 15),
                    )
                val response =
                    client.get("/test/anniv/entries?windowDays=30&memberId=$sevenYearMember") { header("X-Member-Id", board.toString()) }
                response.bodyAsText() shouldBe ""
            }
        }

        // ── Deterministische Sortierung bei identischem occursOn ──────────

        test("zwei Mitglieder mit identischem occursOn sortieren deterministisch (zweimal ausgeführt, gleiches Ergebnis)") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val board = createMember(email = "anniv-tiebreak-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                createMember(
                    email = "anniv-tiebreak-a-${Uuid.random()}@example.org",
                    role = AccountRole.MEMBER,
                    status = MemberStatus.ACTIVE,
                    dateOfBirth = LocalDate(1990, 6, 20),
                    displayName = "AAA Tiebreak",
                )
                createMember(
                    email = "anniv-tiebreak-b-${Uuid.random()}@example.org",
                    role = AccountRole.MEMBER,
                    status = MemberStatus.ACTIVE,
                    dateOfBirth = LocalDate(1985, 6, 20),
                    displayName = "BBB Tiebreak",
                )
                val first = client.get("/test/anniv/entries?windowDays=30") { header("X-Member-Id", board.toString()) }.bodyAsText()
                val second = client.get("/test/anniv/entries?windowDays=30") { header("X-Member-Id", board.toString()) }.bodyAsText()
                first shouldBe second
            }
        }

        // ── Log-Redaction: kein Name, keine Id, kein Geburtsdatum ─────────

        test("Log-Zeile enthält weder Mitgliedsnamen noch memberId noch Geburtsdatum") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val board = createMember(email = "anniv-log-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val subjectDisplayName = "SehrEinzigartigerLogTestName"
                val subjectDob = LocalDate(1990, 6, 20)
                val subject =
                    createMember(
                        email = "anniv-log-subject-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        status = MemberStatus.ACTIVE,
                        dateOfBirth = subjectDob,
                        displayName = subjectDisplayName,
                    )

                val logEvents =
                    captureRootLogEvents {
                        runBlocking {
                            val response = client.get("/test/anniv/get?windowDays=30") { header("X-Member-Id", board.toString()) }
                            response.status shouldBe HttpStatusCode.OK
                        }
                    }

                val relevant = logEvents.filter { it.formattedMessage.contains("member anniversary overview read") }
                relevant.isEmpty() shouldBe false
                relevant.forEach { event ->
                    event.formattedMessage.contains(subjectDisplayName) shouldBe false
                    event.formattedMessage.contains(subject.toString()) shouldBe false
                    event.formattedMessage.contains(subjectDob.toString()) shouldBe false
                }
            }
        }
    })

private fun StatusPagesConfig.installAnniversaryExceptionHandlers() {
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}
