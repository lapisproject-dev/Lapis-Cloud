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
import kotlinx.datetime.number
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnniversaryEmphasis
import network.lapis.cloud.shared.domain.AnniversaryEntryKind
import network.lapis.cloud.shared.domain.MemberAnniversaryOverviewDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TodaysAnniversariesDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.RateLimitedException
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

        // V1.9.276: limiter handed to the "today" route; null = an unlimited one per request.
        var todayLimiter: FederationInboxRateLimiter? = null

        fun Route.registerAnniversaryTestRoutes() {
            get("/test/anniv/today") {
                val day = LocalDate.parse(call.request.queryParameters["day"]!!)
                val limiter = todayLimiter ?: FederationInboxRateLimiter(maxRequests = 100_000)
                val dto = MemberAnniversaryService(call = call, clock = { day }, todayRateLimiter = limiter).getTodaysAnniversaries()
                call.respondText(Json.encodeToString(TodaysAnniversariesDto.serializer(), dto))
            }
            get("/test/anniv/upcoming") {
                val day = LocalDate.parse(call.request.queryParameters["day"]!!)
                val dto = MemberAnniversaryService(call = call, clock = { day }).getUpcomingAnniversaries(1)
                call.respondText(Json.encodeToString(MemberAnniversaryOverviewDto.serializer(), dto))
            }
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

        // ── V1.9.276: getTodaysAnniversaries (dashboard card "Heute") ──────

        fun todayDay(n: Int) = LocalDate(2031, 9, 17).let { LocalDate(it.year + n, it.month, it.day) }

        suspend fun io.ktor.client.HttpClient.todayOf(
            member: Uuid,
            day: LocalDate,
        ): TodaysAnniversariesDto =
            Json.decodeFromString(
                TodaysAnniversariesDto.serializer(),
                get("/test/anniv/today?day=$day") { header("X-Member-Id", member.toString()) }.bodyAsText(),
            )

        suspend fun io.ktor.client.HttpClient.upcomingTodayAsToday(
            member: Uuid,
            day: LocalDate,
        ): List<network.lapis.cloud.shared.domain.TodaysAnniversaryDto> =
            Json
                .decodeFromString(
                    MemberAnniversaryOverviewDto.serializer(),
                    get("/test/anniv/upcoming?day=$day") { header("X-Member-Id", member.toString()) }.bodyAsText(),
                ).entries
                .filter { it.occursOn == day }
                .map {
                    network.lapis.cloud.shared.domain.TodaysAnniversaryDto(
                        memberId = it.memberId,
                        memberDisplayName = it.memberDisplayName,
                        kind = it.kind,
                        years = it.years,
                        emphasis = it.emphasis,
                    )
                }

        AccountRole.entries.forEach { role ->
            test("today: Rolle $role -- nur BOARD und ADMIN, Rate-Limit wird bei abgelehnter Rolle nicht verbraucht") {
                testApplication {
                    application {
                        install(StatusPages) { installAnniversaryExceptionHandlers() }
                        routing { registerAnniversaryTestRoutes() }
                    }
                    todayLimiter = FederationInboxRateLimiter(maxRequests = 1)
                    try {
                        val caller = createMember(email = "anniv-today-role-$role-${Uuid.random()}@example.org", role = role)
                        val allowed = role == AccountRole.BOARD || role == AccountRole.ADMIN
                        val first = client.get("/test/anniv/today?day=${todayDay(0)}") { header("X-Member-Id", caller.toString()) }
                        first.status shouldBe (if (allowed) HttpStatusCode.OK else HttpStatusCode.Forbidden)
                        if (!allowed) {
                            // a refused role must not have used the budget (maxRequests = 1): the same limiter still serves a BOARD member
                            val board =
                                createMember(email = "anniv-today-role-b-$role-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                            client.get("/test/anniv/today?day=${todayDay(0)}") { header("X-Member-Id", board.toString()) }.status shouldBe
                                HttpStatusCode.OK
                            // and the refused caller still gets Forbidden, never TooManyRequests
                            client.get("/test/anniv/today?day=${todayDay(0)}") { header("X-Member-Id", caller.toString()) }.status shouldBe
                                HttpStatusCode.Forbidden
                        }
                    } finally {
                        todayLimiter = null
                    }
                }
            }
        }

        test("today: gleiche Treffer wie getUpcomingAnniversaries(1) am selben Tag, gemischte Fixtures") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val day = todayDay(0)
                val board = createMember(email = "anniv-today-eq-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val birthday =
                    createMember(
                        email = "anniv-today-eq-b-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        dateOfBirth = LocalDate(1976, day.month.number, day.day),
                        joinedAt = LocalDate(2024, 2, 3),
                    )
                val jubilee =
                    createMember(
                        email = "anniv-today-eq-j-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        joinedAt = LocalDate(2021, day.month.number, day.day),
                    )
                val tomorrow =
                    createMember(
                        email = "anniv-today-eq-t-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        dateOfBirth = LocalDate(1976, day.month.number, day.day + 1),
                    )
                val today = client.todayOf(board, day)
                val upcoming = client.upcomingTodayAsToday(board, day)
                today.entries shouldBe upcoming.take(MemberAnniversaryService.TODAY_CAP)
                today.totalCount shouldBe upcoming.size
                today.entries.map { it.memberId }.containsAll(listOf(birthday.toString(), jubilee.toString())) shouldBe true
                today.entries.none { it.memberId == tomorrow.toString() } shouldBe true
            }
        }

        listOf(
            LocalDate(2031, 2, 28) to Triple(true, true, false),
            LocalDate(2032, 2, 29) to Triple(true, false, false),
            LocalDate(2031, 3, 1) to Triple(false, false, true),
        ).forEach { (day, expected) ->
            test("today: Schalttag-Vorfilter am $day (29.02.-Geburtstag, 28.02., 01.03.) entspricht dem Kalender") {
                testApplication {
                    application {
                        install(StatusPages) { installAnniversaryExceptionHandlers() }
                        routing { registerAnniversaryTestRoutes() }
                    }
                    val board = createMember(email = "anniv-today-leap-$day-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                    val leap =
                        createMember(
                            email = "anniv-today-leap-a-${Uuid.random()}@example.org",
                            role = AccountRole.MEMBER,
                            dateOfBirth = LocalDate(2000, 2, 29),
                            joinedAt = LocalDate(2024, 6, 15),
                        )
                    val feb28 =
                        createMember(
                            email = "anniv-today-leap-b-${Uuid.random()}@example.org",
                            role = AccountRole.MEMBER,
                            dateOfBirth = LocalDate(2000, 2, 28),
                            joinedAt = LocalDate(2024, 6, 15),
                        )
                    val mar1 =
                        createMember(
                            email = "anniv-today-leap-c-${Uuid.random()}@example.org",
                            role = AccountRole.MEMBER,
                            dateOfBirth = LocalDate(2000, 3, 1),
                            joinedAt = LocalDate(2024, 6, 15),
                        )
                    val today = client.todayOf(board, day)
                    val ids = today.entries.map { it.memberId }.toSet()
                    // memberIds and equality of both paths; the age counts are covered by the leap-day years tests below
                    (leap.toString() in ids) shouldBe expected.first
                    (feb28.toString() in ids) shouldBe (day.month.number == 2 && day.day == 28)
                    (mar1.toString() in ids) shouldBe expected.third
                    today.entries shouldBe client.upcomingTodayAsToday(board, day).take(MemberAnniversaryService.TODAY_CAP)
                }
            }
        }

        listOf(
            Triple(LocalDate(2025, 2, 28), 25, AnniversaryEmphasis.MAJOR),
            Triple(LocalDate(2031, 2, 28), 31, AnniversaryEmphasis.STANDARD),
            Triple(LocalDate(2032, 2, 29), 32, AnniversaryEmphasis.STANDARD),
        ).forEach { (day, expectedYears, expectedEmphasis) ->
            test("today: 29.02.-Geburtstag am $day zaehlt $expectedYears vollendete Jahre") {
                testApplication {
                    application {
                        install(StatusPages) { installAnniversaryExceptionHandlers() }
                        routing { registerAnniversaryTestRoutes() }
                    }
                    val board = createMember(email = "anniv-years-b-$day-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                    val born =
                        createMember(
                            email = "anniv-years-a-$day-${Uuid.random()}@example.org",
                            role = AccountRole.MEMBER,
                            dateOfBirth = LocalDate(2000, 2, 29),
                        )
                    val entry = client.todayOf(board, day).entries.single { it.memberId == born.toString() }
                    entry.kind shouldBe AnniversaryEntryKind.BIRTHDAY
                    entry.years shouldBe expectedYears
                    entry.emphasis shouldBe expectedEmphasis
                    client.upcomingTodayAsToday(board, day).single { it.memberId == born.toString() } shouldBe entry
                }
            }
        }

        test("today: 29.02.-Eintritt zaehlt am 28.02. eines Nicht-Schaltjahres 1 bzw. 5 Jahre (Meilensteine)") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val day = LocalDate(2025, 2, 28)
                val board = createMember(email = "anniv-years-ms-b-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val one =
                    createMember(
                        email = "anniv-years-ms-1-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        joinedAt = LocalDate(2024, 2, 29),
                    )
                val five =
                    createMember(
                        email = "anniv-years-ms-5-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        joinedAt = LocalDate(2020, 2, 29),
                    )
                val entries = client.todayOf(board, day).entries.associateBy { it.memberId }
                val first = entries.getValue(one.toString())
                first.kind shouldBe AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY
                first.years shouldBe 1
                first.emphasis shouldBe AnniversaryEmphasis.FIRST_YEAR
                val fifth = entries.getValue(five.toString())
                fifth.kind shouldBe AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY
                fifth.years shouldBe 5
            }
        }

        test("today: nur Meilenstein-Jubiläen (3 Jahre fehlt; 1, 5, 10, 25 Jahre mit passender Hervorhebung)") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val day = todayDay(0)
                val board = createMember(email = "anniv-today-ms-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val byYears =
                    listOf(3, 1, 5, 10, 25).associateWith { years ->
                        createMember(
                            email = "anniv-today-ms-$years-${Uuid.random()}@example.org",
                            role = AccountRole.MEMBER,
                            joinedAt = LocalDate(day.year - years, day.month, day.day),
                        )
                    }
                val entries = client.todayOf(board, day).entries.associateBy { it.memberId }
                entries.containsKey(byYears.getValue(3).toString()) shouldBe false
                entries.getValue(byYears.getValue(1).toString()).emphasis shouldBe AnniversaryEmphasis.FIRST_YEAR
                entries.getValue(byYears.getValue(5).toString()).emphasis shouldBe AnniversaryEmphasis.STANDARD
                entries.getValue(byYears.getValue(10).toString()).emphasis shouldBe AnniversaryEmphasis.NOTABLE
                entries.getValue(byYears.getValue(25).toString()).emphasis shouldBe AnniversaryEmphasis.MAJOR
                entries.getValue(byYears.getValue(25).toString()).kind shouldBe AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY
            }
        }

        test("today: ausgeschlossen sind alle Status ausser ACTIVE/DONOR und anonymisierte Mitglieder; DONOR ist enthalten") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val day = todayDay(0)
                val board = createMember(email = "anniv-today-ex-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val dob = LocalDate(1980, day.month.number, day.day)
                val byStatus =
                    MemberStatus.entries.associateWith { status ->
                        createMember(
                            email = "anniv-today-ex-$status-${Uuid.random()}@example.org",
                            role = AccountRole.MEMBER,
                            status = status,
                            dateOfBirth = dob,
                        )
                    }
                val anonymized =
                    createMember(
                        email = "anniv-today-ex-anon-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        dateOfBirth = dob,
                        anonymizedAt = LocalDateTime(2030, 1, 1, 0, 0),
                    )
                val ids =
                    client
                        .todayOf(board, day)
                        .entries
                        .map { it.memberId }
                        .toSet()
                MemberStatus.entries.forEach { status ->
                    (byStatus.getValue(status).toString() in ids) shouldBe (status == MemberStatus.ACTIVE || status == MemberStatus.DONOR)
                }
                (anonymized.toString() in ids) shouldBe false
            }
        }

        test("today: Deckel 50 mit totalCount ueber dem Deckel") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val day = LocalDate(2033, 11, 23)
                val board = createMember(email = "anniv-today-cap-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val before = client.todayOf(board, day).totalCount
                repeat(53) { i ->
                    createMember(
                        email = "anniv-today-cap-$i-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        dateOfBirth = LocalDate(1950 + i, 11, 23),
                        displayName = "Deckel Person $i",
                    )
                }
                val dto = client.todayOf(board, day)
                dto.entries.size shouldBe MemberAnniversaryService.TODAY_CAP
                dto.totalCount shouldBe before + 53
            }
        }

        test("today: Reihenfolge BIRTHDAY vor MEMBERSHIP_ANNIVERSARY, dann Name, dann memberId") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val day = LocalDate(2034, 4, 9)
                val board = createMember(email = "anniv-today-ord-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val dob = LocalDate(1990, 4, 9)
                val ids =
                    listOf("Zora", "Anton", "Maja", "Maja").map { name ->
                        createMember(
                            email = "anniv-today-ord-$name-${Uuid.random()}@example.org",
                            role = AccountRole.MEMBER,
                            dateOfBirth = dob,
                            displayName = "Ord $name",
                        )
                    }
                val jubilee =
                    createMember(
                        email = "anniv-today-ord-j-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        joinedAt = LocalDate(2029, 4, 9),
                        displayName = "Ord Aaa",
                    )
                val mine = ids.map { it.toString() }.toSet() + jubilee.toString()
                val entries = client.todayOf(board, day).entries.filter { it.memberId in mine }
                entries.map { it.memberDisplayName } shouldBe listOf("Ord Anton", "Ord Maja", "Ord Maja", "Ord Zora", "Ord Aaa")
                entries.map { it.kind } shouldBe List(4) { AnniversaryEntryKind.BIRTHDAY } + AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY
                val maja = entries.filter { it.memberDisplayName == "Ord Maja" }.map { it.memberId }
                maja shouldBe maja.sorted()
            }
        }

        test("today: JSON enthaelt kein Datum, keinen Status, kein occursOn/originalDate/memberStatus") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val day = todayDay(0)
                val board = createMember(email = "anniv-today-json-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                createMember(
                    email = "anniv-today-json-s-${Uuid.random()}@example.org",
                    role = AccountRole.MEMBER,
                    status = MemberStatus.DONOR,
                    dateOfBirth = LocalDate(1970, day.month.number, day.day),
                )
                val raw = client.get("/test/anniv/today?day=$day") { header("X-Member-Id", board.toString()) }.bodyAsText()
                (raw.length > 20) shouldBe true
                Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(raw) shouldBe false
                MemberStatus.entries.forEach { raw.contains(it.name) shouldBe false }
                listOf("occursOn", "originalDate", "memberStatus", "shiftedFromLeapDay").forEach { raw.contains(it) shouldBe false }
            }
        }

        test("today: Log-Zeile enthaelt weder Namen noch Id noch Geburtsdatum") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                val day = todayDay(0)
                val board = createMember(email = "anniv-today-log-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val name = "SehrEinzigartigerHeuteLogName"
                val dob = LocalDate(1960, day.month.number, day.day)
                val subject =
                    createMember(
                        email = "anniv-today-log-s-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        dateOfBirth = dob,
                        displayName = name,
                    )
                val events =
                    captureRootLogEvents {
                        runBlocking {
                            client.get("/test/anniv/today?day=$day") { header("X-Member-Id", board.toString()) }.status shouldBe
                                HttpStatusCode.OK
                        }
                    }
                val relevant = events.filter { it.formattedMessage.contains("member anniversaries today read") }
                relevant.isEmpty() shouldBe false
                relevant.forEach {
                    it.formattedMessage.contains(name) shouldBe false
                    it.formattedMessage.contains(subject.toString()) shouldBe false
                    it.formattedMessage.contains(dob.toString()) shouldBe false
                }
            }
        }

        test("today: nach TODAY_MAX_CALLS Aufrufen folgt RateLimitedException (429), je Mitglied") {
            testApplication {
                application {
                    install(StatusPages) { installAnniversaryExceptionHandlers() }
                    routing { registerAnniversaryTestRoutes() }
                }
                todayLimiter = FederationInboxRateLimiter(maxRequests = MemberAnniversaryService.TODAY_MAX_CALLS)
                try {
                    val board = createMember(email = "anniv-today-rl-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                    val other = createMember(email = "anniv-today-rl-o-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                    repeat(MemberAnniversaryService.TODAY_MAX_CALLS) {
                        client.get("/test/anniv/today?day=${todayDay(0)}") { header("X-Member-Id", board.toString()) }.status shouldBe
                            HttpStatusCode.OK
                    }
                    client.get("/test/anniv/today?day=${todayDay(0)}") { header("X-Member-Id", board.toString()) }.status shouldBe
                        HttpStatusCode.TooManyRequests
                    client.get("/test/anniv/today?day=${todayDay(0)}") { header("X-Member-Id", other.toString()) }.status shouldBe
                        HttpStatusCode.OK
                } finally {
                    todayLimiter = null
                }
            }
        }

        test("today: Application uebergibt den geteilten Limiter (kein Fallback pro Instanz in Produktion)") {
            val application = java.io.File("src/main/kotlin/network/lapis/cloud/server/Application.kt").readText()
            application.contains("todayRateLimiter = memberAnniversaryTodayLimiter") shouldBe true
        }
    })

private fun StatusPagesConfig.installAnniversaryExceptionHandlers() {
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
    exception<RateLimitedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.TooManyRequests) }
}
