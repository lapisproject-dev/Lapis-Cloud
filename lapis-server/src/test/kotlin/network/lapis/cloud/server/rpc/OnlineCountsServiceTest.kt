package network.lapis.cloud.server.rpc

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.security.OnlineSessionCountsSupport
import network.lapis.cloud.server.security.OnlineSessionCountsSupport.minus
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OnlineCountsDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** V1.9.215 -- [OnlineCountsService]: ADMIN only, the figures, the constants and the clock stamp. Throwaway-route house style. */
class OnlineCountsServiceTest :
    FunSpec({
        val fixtures = MemberStatisticsFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterEach {
            OnlineSessionCountsSupport.cleanUp()
            fixtures.cleanUp()
        }

        fun run(memberId: Uuid?): HttpResponse {
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
                    }
                    routing {
                        get("/test/online-counts") {
                            val dto = OnlineCountsService(call = call).onlineCounts()
                            call.respondText(Json.encodeToString(OnlineCountsDto.serializer(), dto))
                        }
                    }
                }
                result =
                    TimeTestSupport.withServerClock(instant = "2200-01-01T00:00:00Z") {
                        client.get("/test/online-counts") { memberId?.let { header("X-Member-Id", it.toString()) } }
                    }
            }
            return result
        }

        test("no session -> 401") {
            run(memberId = null).status shouldBe HttpStatusCode.Unauthorized
        }

        test("MEMBER, BOARD and TREASURER are forbidden, whatever the status") {
            listOf(AccountRole.MEMBER, AccountRole.BOARD, AccountRole.TREASURER).forEach { role ->
                MemberStatus.entries.forEach { status ->
                    val id = fixtures.member(role = role, status = status)
                    withClue(clue = "$role / $status") { run(memberId = id).status shouldBe HttpStatusCode.Forbidden }
                }
            }
        }

        test("ADMIN gets the figures, the constants and the pinned clock") {
            val now = OnlineSessionCountsSupport.FAR_NOW
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val friend = fixtures.member(role = AccountRole.MEMBER, status = MemberStatus.FRIEND)
            val idle = fixtures.member(role = AccountRole.MEMBER)
            OnlineSessionCountsSupport.session(memberId = admin, lastUsedAt = now.minus(1.minutes))
            OnlineSessionCountsSupport.session(memberId = friend, lastUsedAt = now.minus(2.minutes))
            OnlineSessionCountsSupport.session(memberId = idle, lastUsedAt = null)
            val response = run(memberId = admin)
            response.status shouldBe HttpStatusCode.OK
            val dto = Json.decodeFromString(OnlineCountsDto.serializer(), runBlocking { response.bodyAsText() })
            dto.active.total shouldBe 2
            dto.active.members shouldBe 1
            dto.active.friends shouldBe 1
            dto.signedIn.total shouldBe 3
            dto.signedIn.members shouldBe 2
            dto.activeWindowMinutes shouldBe 15
            dto.sessionTtlHours shouldBe 8
            dto.computedAt shouldBe LocalDateTime.parse("2200-01-01T00:00:00")
        }
    })
