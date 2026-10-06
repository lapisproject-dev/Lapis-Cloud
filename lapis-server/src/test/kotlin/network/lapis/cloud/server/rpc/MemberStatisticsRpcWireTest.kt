package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.module
import network.lapis.cloud.shared.domain.AccountRole
import kotlin.uuid.Uuid

/**
 * Welle V1.9.59 -- the member-statistics service on the RAW RPC wire (full `module()`), like [BoardMemberMapRpcWireTest].
 * `/rpc/routeMemberStatisticsServiceManager0` is `getMemberCountHistory` (Kilua-RPC-KSP assigns route indices by declaration order).
 */
private const val GET_HISTORY_ROUTE = "/rpc/routeMemberStatisticsServiceManager0"
private const val HISTORY_BODY =
    """{"id":1,"jsonrpc":"2.0","method":"getMemberCountHistory","params":["{\"from\":\"1950-01-01\",\"to\":\"1951-12-31\",\"granularity\":\"QUARTER\"}"]}"""

class MemberStatisticsRpcWireTest :
    FunSpec({
        val fixtures = MemberStatisticsFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterEach { fixtures.cleanUp() }

        test("BOARD gets the figures: the declared fields are there, no member id and no identifying field name") {
            testApplication {
                application { module() }
                val (m1, m2) = fixtures.window1950()
                val board = fixtures.member(role = AccountRole.BOARD)
                val response =
                    client.post(GET_HISTORY_ROUTE) {
                        header("X-Member-Id", board.toString())
                        contentType(ContentType.Application.Json)
                        setBody(HISTORY_BODY)
                    }
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                listOf("points", "periodStart", "counts", "earliestDate", "today", "granularity").forEach { body shouldContain it }
                listOf(m1, m2, board).forEach { body shouldNotContain it.toString() }
                listOf("memberId", "displayName", "email").forEach { body shouldNotContain it }
            }
        }

        test("ADMIN is allowed too") {
            testApplication {
                application { module() }
                fixtures.window1950()
                val admin = fixtures.member(role = AccountRole.ADMIN)
                val body =
                    client
                        .post(GET_HISTORY_ROUTE) {
                            header("X-Member-Id", admin.toString())
                            contentType(ContentType.Application.Json)
                            setBody(HISTORY_BODY)
                        }.bodyAsText()
                body shouldContain "points"
            }
        }

        test("MEMBER, TREASURER and an anonymous caller get an error object, never a figure") {
            testApplication {
                application { module() }
                fixtures.window1950()
                val callers: List<Uuid?> =
                    listOf(
                        fixtures.member(role = AccountRole.MEMBER),
                        fixtures.member(role = AccountRole.TREASURER),
                        null,
                    )
                callers.forEach { caller ->
                    val response =
                        client.post(GET_HISTORY_ROUTE) {
                            caller?.let { header("X-Member-Id", it.toString()) }
                            contentType(ContentType.Application.Json)
                            setBody(HISTORY_BODY)
                        }
                    val body = response.bodyAsText()
                    body shouldNotContain "periodStart"
                    body shouldNotContain "counts"
                    // an unauthenticated or forbidden call is an error response (HTTP status or JSON-RPC error), never a result
                    (response.status != HttpStatusCode.OK || body.contains("\"error\"")) shouldBe true
                }
            }
        }
    })
