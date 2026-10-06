package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberCountGranularity
import network.lapis.cloud.shared.domain.MemberCountHistoryDto
import network.lapis.cloud.shared.domain.MemberCountHistoryQuery
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import kotlin.uuid.Uuid

/**
 * Welle V1.9.59 -- [MemberStatisticsService]: who may read the history, which ranges are accepted, and the figures themselves.
 * Same throwaway-route house style as [BoardMemberMapServiceTest].
 */
class MemberStatisticsServiceTest :
    FunSpec({
        val fixtures = MemberStatisticsFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterEach { fixtures.cleanUp() }

        fun run(
            memberId: Uuid?,
            from: String = "1950-01-01",
            to: String = "1951-12-31",
            granularity: MemberCountGranularity = MemberCountGranularity.QUARTER,
        ): HttpResponse {
            lateinit var result: HttpResponse
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                        exception<BadRequestException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.BadRequest)
                        }
                    }
                    routing {
                        get("/test/member-statistics") {
                            val query =
                                MemberCountHistoryQuery(
                                    from = LocalDate.parse(call.request.queryParameters["from"]!!),
                                    to = LocalDate.parse(call.request.queryParameters["to"]!!),
                                    granularity = MemberCountGranularity.valueOf(call.request.queryParameters["g"]!!),
                                )
                            val dto = MemberStatisticsService(call = call).getMemberCountHistory(query)
                            call.respondText(Json.encodeToString(MemberCountHistoryDto.serializer(), dto))
                        }
                    }
                }
                result =
                    client.get("/test/member-statistics") {
                        memberId?.let { header("X-Member-Id", it.toString()) }
                        parameter("from", from)
                        parameter("to", to)
                        parameter("g", granularity.name)
                    }
            }
            return result
        }

        fun decode(response: HttpResponse): MemberCountHistoryDto =
            Json.decodeFromString(MemberCountHistoryDto.serializer(), runBlockingText(response))

        test("no session -> 401") {
            run(memberId = null).status shouldBe HttpStatusCode.Unauthorized
        }

        test("every role except BOARD and ADMIN is forbidden, whatever the member's status") {
            AccountRole.entries.filter { it != AccountRole.BOARD && it != AccountRole.ADMIN }.forEach { role ->
                MemberStatus.entries.forEach { status ->
                    val id = fixtures.member(role = role, status = status)
                    withClue(clue = "$role / $status") { run(memberId = id).status shouldBe HttpStatusCode.Forbidden }
                }
            }
        }

        test("the role gate comes first: a forbidden caller with an invalid range still gets 403, not 400") {
            val member = fixtures.member(role = AccountRole.MEMBER)
            run(memberId = member, from = "2026-05-01", to = "2026-01-01").status shouldBe HttpStatusCode.Forbidden
        }

        test("BOARD and ADMIN read the figures: 8 quarters, hand-computed counts, every status present") {
            fixtures.window1950()
            listOf(AccountRole.BOARD, AccountRole.ADMIN).forEach { role ->
                val caller = fixtures.member(role = role)
                val response = run(memberId = caller)
                response.status shouldBe HttpStatusCode.OK
                val dto = decode(response)
                dto.points.size shouldBe 8
                dto.points.map { it.periodStart.toString() } shouldBe
                    listOf("1950-01-01", "1950-04-01", "1950-07-01", "1950-10-01", "1951-01-01", "1951-04-01", "1951-07-01", "1951-10-01")
                dto.points.forEach { it.counts.keys shouldBe MemberStatus.entries.toSet() }
                // Q1 1950: M1 ACTIVE (Mar 5) + M2 ACTIVE (Mar 20); the APPLICATION of Feb 10 is gone again
                dto.points[0].counts[MemberStatus.ACTIVE] shouldBe 2
                dto.points[0].counts[MemberStatus.APPLICATION] shouldBe 0
                dto.points[4].counts[MemberStatus.ACTIVE] shouldBe 2
                // Q2 1951 ends after M1 withdrew (1 June)
                dto.points[5].counts[MemberStatus.ACTIVE] shouldBe 1
                dto.points[5].counts[MemberStatus.WITHDRAWN] shouldBe 1
                dto.points.none { it.current } shouldBe true
                dto.earliestDate.toString() shouldBe "1950-02-10"
                dto.today shouldBe OrganizationTimeZone.today()
            }
        }

        test("the response carries no member id: not the fixtures', not any UUID-shaped value") {
            val (m1, m2) = fixtures.window1950()
            val caller = fixtures.member(role = AccountRole.BOARD)
            val body = run(memberId = caller).bodyAsText()
            listOf(m1, m2, caller).forEach { body shouldNotContain it.toString() }
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").containsMatchIn(body) shouldBe false
            body shouldNotContain "memberId"
        }

        test("a range containing today ends with the current period, counted up to now") {
            val caller = fixtures.member(role = AccountRole.ADMIN)
            val today = OrganizationTimeZone.today()
            val dto =
                decode(run(memberId = caller, from = today.toString(), to = today.toString(), granularity = MemberCountGranularity.MONTH))
            dto.points.size shouldBe 1
            dto.points.single().current shouldBe true
            dto.points.single().reconstructed shouldBe false
        }

        test("invalid ranges are rejected with 400, never coarsened: from after to, to in the future, from before 1900, 241 periods") {
            val caller = fixtures.member(role = AccountRole.ADMIN)
            val today = OrganizationTimeZone.today()
            run(memberId = caller, from = "1951-05-01", to = "1950-01-01").status shouldBe HttpStatusCode.BadRequest
            run(memberId = caller, from = "1950-01-01", to = today.plus(DatePeriod(days = 1)).toString()).status shouldBe
                HttpStatusCode.BadRequest
            run(memberId = caller, from = "1899-12-31", to = "1950-01-01").status shouldBe HttpStatusCode.BadRequest
            // 2000-01 .. 2020-01 is 241 months, 2000-01 .. 2019-12 is 240
            run(memberId = caller, from = "2000-01-01", to = "2020-01-31", granularity = MemberCountGranularity.MONTH).status shouldBe
                HttpStatusCode.BadRequest
            val atLimit = run(memberId = caller, from = "2000-01-01", to = "2019-12-31", granularity = MemberCountGranularity.MONTH)
            atLimit.status shouldBe HttpStatusCode.OK
            decode(atLimit).points.size shouldBe 240
        }

        test("reconstructed: points up to the end of the backfill are flagged, later ones are not (backfill = a row without recorded_at)") {
            val m = fixtures.member(role = AccountRole.MEMBER)
            // a V72-style reconstructed row, then the first LIVE row, recorded on 1 August 1950
            fixtures.history(
                memberId = m,
                at = "1950-01-05T10:00:00",
                status = MemberStatus.FRIEND,
                previous = null,
                source = "BACKFILL_ASSUMED",
                recordedAt = null,
            )
            fixtures.history(
                memberId = m,
                at = "1950-08-01T09:00:00",
                status = MemberStatus.ACTIVE,
                previous = MemberStatus.FRIEND,
                source = "LIVE",
                recordedAt = "1950-08-01T09:00:00",
            )
            val caller = fixtures.member(role = AccountRole.BOARD)
            val dto = decode(run(memberId = caller))
            dto.reconstructedBefore shouldBe LocalDateTime.parse("1950-08-01T09:00:00")
            // quarter ends 1 Apr, 1 Jul (before the first live row) are reconstructions; 1 Oct and later are not
            dto.points.map { it.reconstructed } shouldBe listOf(true, true, false, false, false, false, false, false)
        }

        test("only live rows: nothing is flagged") {
            fixtures.window1950()
            val caller = fixtures.member(role = AccountRole.BOARD)
            val dto = decode(run(memberId = caller))
            dto.reconstructedBefore shouldBe null
            dto.points.none { it.reconstructed } shouldBe true
        }

        test("an IMPORT row extends the reconstructed range to its recorded_at") {
            val m = fixtures.member(role = AccountRole.MEMBER)
            fixtures.history(
                memberId = m,
                at = "1950-01-10T00:00:00",
                status = MemberStatus.ACTIVE,
                previous = null,
                source = "IMPORT",
                recordedAt = "1951-03-01T08:00:00",
            )
            val caller = fixtures.member(role = AccountRole.ADMIN)
            val dto = decode(run(memberId = caller))
            dto.reconstructedBefore shouldBe LocalDateTime.parse("1951-03-01T08:00:00")
            // quarter ends 1950-04-01 .. 1951-01-01 are before the import's recorded_at; Q1 1951 ends 1951-04-01 (after) -> not flagged
            dto.points.map { it.reconstructed } shouldBe listOf(true, true, true, true, false, false, false, false)
            body(dto).shouldContain("1951-03-01")
        }
    })

private fun body(dto: MemberCountHistoryDto): String = Json.encodeToString(MemberCountHistoryDto.serializer(), dto)

private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }

private fun runBlockingText(response: HttpResponse): String = kotlinx.coroutines.runBlocking { response.bodyAsText() }
