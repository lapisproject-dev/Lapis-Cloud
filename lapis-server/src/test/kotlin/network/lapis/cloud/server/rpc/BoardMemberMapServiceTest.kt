package network.lapis.cloud.server.rpc

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
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.membermap.PmtilesBasemap
import network.lapis.cloud.server.membermap.PostalCodeCentroidIndex
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val TEST_POSTAL_CODE = "38999"

/**
 * Exercises [BoardMemberMapService] directly through throwaway routes -- same house style
 * [MemberAnniversaryServiceTest] establishes. Uses [TEST_POSTAL_CODE] (a valid-shaped, but
 * deliberately unassigned German postal code, verified absent from `DevSeedData`/`StagingSeedData`)
 * so counts can be asserted as ABSOLUTE numbers rather than deltas against a shared H2 database --
 * every fixture member in this suite shares it, and every other test in the shared H2 database never
 * uses it.
 */
class BoardMemberMapServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
            createdMemberIds.clear()
        }

        fun createMember(
            role: AccountRole,
            status: MemberStatus,
            anonymizedAt: LocalDateTime? = null,
            postalCode: String? = TEST_POSTAL_CODE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Kartentest-Mitglied ${Uuid.random().toString().take(6)}"
                    it[email] = "member-map-test-${Uuid.random()}@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[MemberTable.postalCode] = postalCode
                    it[country] = null
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

        fun Route.registerMemberMapTestRoutes(
            basemap: PmtilesBasemap,
            centroids: PostalCodeCentroidIndex?,
        ) {
            get("/test/membermap") {
                val dto = BoardMemberMapService(call = call, basemap = basemap, centroids = centroids).getMemberMap()
                val ownEntry = dto.entries.firstOrNull { it.postalCode == TEST_POSTAL_CODE }
                call.respondText(
                    "${ownEntry?.count ?: 0}:${dto.tilesAvailable}:${dto.geodataAvailable}",
                )
            }
        }

        fun runTest(
            basemap: PmtilesBasemap = PmtilesBasemap(null),
            centroids: PostalCodeCentroidIndex? = null,
            memberId: Uuid?,
        ): HttpResponse {
            lateinit var result: HttpResponse
            testApplication {
                application {
                    install(StatusPages) { installMemberMapExceptionHandlers() }
                    routing { registerMemberMapTestRoutes(basemap, centroids) }
                }
                result = client.get("/test/membermap") { memberId?.let { header("X-Member-Id", it.toString()) } }
            }
            return result
        }

        test("no auth header -> 401") {
            runTest(memberId = null).status shouldBe HttpStatusCode.Unauthorized
        }

        test("MEMBER role -> 403") {
            val member = createMember(role = AccountRole.MEMBER, status = MemberStatus.ACTIVE)
            runTest(memberId = member).status shouldBe HttpStatusCode.Forbidden
        }

        test("TREASURER role -> 403") {
            val treasurer = createMember(role = AccountRole.TREASURER, status = MemberStatus.ACTIVE)
            runTest(memberId = treasurer).status shouldBe HttpStatusCode.Forbidden
        }

        test(
            "one ACTIVE member on TEST_POSTAL_CODE, plus one each of WITHDRAWN/DECEASED/DONOR/APPLICATION/GUEST/FRIEND " +
                "and one ACTIVE-but-anonymized member on the SAME postal code -> count is exactly 1",
        ) {
            val board = createMember(role = AccountRole.BOARD, status = MemberStatus.ACTIVE)
            createMember(role = AccountRole.MEMBER, status = MemberStatus.WITHDRAWN)
            createMember(role = AccountRole.MEMBER, status = MemberStatus.DECEASED)
            createMember(role = AccountRole.MEMBER, status = MemberStatus.DONOR)
            createMember(role = AccountRole.MEMBER, status = MemberStatus.APPLICATION)
            createMember(role = AccountRole.MEMBER, status = MemberStatus.GUEST)
            createMember(role = AccountRole.MEMBER, status = MemberStatus.FRIEND)
            createMember(role = AccountRole.MEMBER, status = MemberStatus.ACTIVE, anonymizedAt = LocalDateTime(2026, 1, 1, 0, 0))

            val response = runTest(memberId = board)
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            // `board` itself is BOARD/ACTIVE on TEST_POSTAL_CODE too -- exactly ONE eligible entry total.
            body.substringBefore(":") shouldBe "1"
        }

        test("no basemap configured -> tilesAvailable = false") {
            val board = createMember(role = AccountRole.BOARD, status = MemberStatus.ACTIVE)
            val body = runTest(memberId = board, basemap = PmtilesBasemap(null)).bodyAsText()
            body.split(":")[1] shouldBe "false"
        }

        test("centroids = null -> geodataAvailable = false, counts unaffected") {
            val board = createMember(role = AccountRole.BOARD, status = MemberStatus.ACTIVE)
            val body = runTest(memberId = board, centroids = null).bodyAsText()
            val parts = body.split(":")
            parts[0] shouldBe "1"
            parts[2] shouldBe "false"
        }
    })

private fun StatusPagesConfig.installMemberMapExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
}
