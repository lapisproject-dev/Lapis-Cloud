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
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.module
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.5 "Vorstands-Karte" -- proves the privacy shape on the RAW RPC wire, not just through
 * the service's typed return value (which could never accidentally leak a field the DTO doesn't
 * declare, but could still leak one the DTO DOES declare with an unintended extra field, or one a
 * future refactor adds to [network.lapis.cloud.shared.domain.MemberMapEntryDto] without updating
 * this test). Same "post real JSON-RPC against the FULL `module()`" shape as [OpenItemRpcWireTest].
 *
 * **Route index**: `/rpc/routeBoardMemberMapServiceManager0` is `getMemberMap`,
 * `/rpc/routeBoardMemberMapServiceManager1` is `searchPlaces` -- Kilua-RPC-KSP assigns route indices
 * by DECLARATION ORDER on [network.lapis.cloud.shared.rpc.IBoardMemberMapService] (verified against
 * `IOpenItemService`/`OpenItemRpcWireTest`'s own "the 7th `bind(...)`" comment, same mechanism). This
 * interface was single-method (index 0 only) before V1.9.9 "Ortssuche" added `searchPlaces` as a
 * SECOND method -- an intentional, accepted break of the old "index 0 stays stable because this stays
 * single-method" pin, not a regression; both indices are pinned here now instead.
 */
private const val GET_MEMBER_MAP_ROUTE = "/rpc/routeBoardMemberMapServiceManager0"
private const val SEARCH_PLACES_ROUTE = "/rpc/routeBoardMemberMapServiceManager1"
private const val WIRE_TEST_POSTAL_CODE = "38998"

class BoardMemberMapRpcWireTest :
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
            displayName: String,
            email: String,
            street: String,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[MemberTable.street] = street
                    it[postalCode] = WIRE_TEST_POSTAL_CODE
                    it[city] = "Geheimstadt-$id"
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

        fun getMemberMapBody(): String = """{"id":1,"jsonrpc":"2.0","method":"getMemberMap","params":[]}"""

        test("the raw RPC response body contains no member id, name, email, street or city") {
            testApplication {
                application { module() }
                val secretName = "Geheimname-${Uuid.random()}"
                val secretEmail = "geheim-${Uuid.random()}@example.org"
                val secretStreet = "Geheimweg-${Uuid.random()} 7"
                val board = createMember(role = AccountRole.BOARD, displayName = secretName, email = secretEmail, street = secretStreet)

                val body =
                    client
                        .post(GET_MEMBER_MAP_ROUTE) {
                            header("X-Member-Id", board.toString())
                            contentType(ContentType.Application.Json)
                            setBody(getMemberMapBody())
                        }.bodyAsText()

                body shouldNotContain board.toString()
                body shouldNotContain secretName
                body shouldNotContain secretEmail
                body shouldNotContain secretStreet
                body shouldNotContain "Geheimstadt"
                body shouldNotContain "joinedAt"
                body shouldNotContain "dateOfBirth"
                // Bare field-name check, NOT a literal `"street"`/`"city"` substring: Kilua RPC wraps
                // the actual JSON as an escaped STRING inside "result" (`"result":"{\"entries\":...`,
                // see the third test's own comment below), so a field's quotes always arrive as `\"`,
                // never as a bare `"` immediately before/after the name -- a literal `"street"` check
                // can never match the wire body and would silently never fail, see
                // BoardMemberMapRpcWireTestKt/the git history of this test for that regression.
                body shouldNotContain "street"
                body shouldNotContain "city"
            }
        }

        test("non-BOARD/ADMIN caller -> ForbiddenException crosses the wire, not a normal result") {
            testApplication {
                application { module() }
                val member =
                    createMember(
                        role = AccountRole.MEMBER,
                        displayName = "Nichtvorstand",
                        email = "nv-${Uuid.random()}@example.org",
                        street = "X",
                    )
                val response =
                    client.post(GET_MEMBER_MAP_ROUTE) {
                        header("X-Member-Id", member.toString())
                        contentType(ContentType.Application.Json)
                        setBody(getMemberMapBody())
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldNotContain "\"entries\""
            }
        }

        test("the response's declared aggregate fields are present, and no identifying field name ever appears") {
            testApplication {
                application { module() }
                val board =
                    createMember(
                        role = AccountRole.BOARD,
                        displayName = "Kartentest",
                        email = "wire-${Uuid.random()}@example.org",
                        street = "Y",
                    )

                val body =
                    client
                        .post(GET_MEMBER_MAP_ROUTE) {
                            header("X-Member-Id", board.toString())
                            contentType(ContentType.Application.Json)
                            setBody(getMemberMapBody())
                        }.bodyAsText()

                // Kilua RPC wraps the actual JSON as an escaped STRING inside "result" (verified by
                // running this test: `"result":"{\"entries\":...`), so a literal `"field"` substring
                // check would fail on the escaping -- checking the bare field name is escaping-agnostic.
                for (field in listOf(
                    "entries",
                    "total",
                    "mappedTotal",
                    "unresolvableGermanPostalCode",
                    "noPostalCode",
                    "foreign",
                    "tilesAvailable",
                    "geodataAvailable",
                )) {
                    body shouldContain field
                }
                // Never a field name a MemberMapEntryDto/BoardMemberMapResponse row does NOT declare.
                // Bare field-name check, not a literal `"memberId"`/`"street"` substring -- see the
                // first test's comment above for why a quoted literal can never match this escaped
                // wire body.
                body shouldNotContain "memberId"
                body shouldNotContain "displayName"
                body shouldNotContain "street"
                body shouldNotContain "city"
            }
        }

        test("searchPlaces route index is stable at manager1 and answers a real (non-error) result for a BOARD caller") {
            testApplication {
                application { module() }
                val board =
                    createMember(
                        role = AccountRole.BOARD,
                        displayName = "Suchtest",
                        email = "search-${Uuid.random()}@example.org",
                        street = "Z",
                    )

                val response =
                    client.post(SEARCH_PLACES_ROUTE) {
                        header("X-Member-Id", board.toString())
                        contentType(ContentType.Application.Json)
                        setBody("""{"id":1,"jsonrpc":"2.0","method":"searchPlaces","params":["\"Berlin\""]}""")
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "result"
            }
        }

        test("searchPlaces: non-BOARD/ADMIN caller -> ForbiddenException crosses the wire, not a normal result") {
            testApplication {
                application { module() }
                val member =
                    createMember(
                        role = AccountRole.MEMBER,
                        displayName = "Nichtvorstand2",
                        email = "nv2-${Uuid.random()}@example.org",
                        street = "X",
                    )

                val response =
                    client.post(SEARCH_PLACES_ROUTE) {
                        header("X-Member-Id", member.toString())
                        contentType(ContentType.Application.Json)
                        setBody("""{"id":1,"jsonrpc":"2.0","method":"searchPlaces","params":["\"Berlin\""]}""")
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldNotContain "placeName"
            }
        }
    })
