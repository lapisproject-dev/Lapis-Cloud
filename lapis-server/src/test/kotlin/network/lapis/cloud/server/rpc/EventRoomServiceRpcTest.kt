package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventRoomInput
import network.lapis.cloud.shared.domain.EventRoomStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.4 "Raumverwaltung für Veranstaltungen" -- RPC-surface coverage of [EventRoomService]
 * via throwaway test routes + `X-Member-Id` header, same house style [EventServiceRpcTest]/
 * [network.lapis.cloud.server.rpc.ApiKeyServiceTest] already establish. Covers the permission
 * matrix (MEMBER/TREASURER -> 403, BOARD/ADMIN -> success) and CRUD happy path.
 */
class EventRoomServiceRpcTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdRoomIds.isNotEmpty()) EventRoomTable.deleteWhere { id inList createdRoomIds }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createMember(role: AccountRole): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventRoomServiceRpcTest Mitglied"
                    it[email] = "eventroomrpc-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
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

        fun Route.registerEventRoomTestRoutes() {
            fun serviceFor(call: io.ktor.server.application.ApplicationCall) = EventRoomService(call = call)
            post("/test/event-room/list") {
                val rooms = serviceFor(call).listRooms(includeInactive = true)
                call.respondText(rooms.size.toString())
            }
            post("/test/event-room/create") {
                val name = call.request.queryParameters["name"] ?: "Testraum-${Uuid.random()}"
                val room = serviceFor(call).createRoom(EventRoomInput(name = name, capacity = 20, equipmentTags = listOf("Beamer", "WLAN")))
                createdRoomIds += Uuid.parse(room.id)
                call.respondText(room.id)
            }
            post("/test/event-room/{id}/deactivate") {
                val room = serviceFor(call).deactivateRoom(id = call.parameters["id"]!!)
                call.respondText(room.status.name)
            }
            post("/test/event-room/{id}/activate") {
                val room = serviceFor(call).activateRoom(id = call.parameters["id"]!!)
                call.respondText(room.status.name)
            }
            post("/test/event-room/create-with-tags") {
                val tagCount = call.request.queryParameters["tagCount"]!!.toInt()
                val tagLength = call.request.queryParameters["tagLength"]!!.toInt()
                val name = call.request.queryParameters["name"] ?: "TagTestraum-${Uuid.random()}"
                val tags = List(tagCount) { "A".repeat(tagLength) }
                val room = serviceFor(call).createRoom(EventRoomInput(name = name, capacity = 20, equipmentTags = tags))
                createdRoomIds += Uuid.parse(room.id)
                call.respondText(room.id)
            }
        }

        fun StatusPagesConfig.installEventRoomExceptionHandlers() {
            exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
            exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
            exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
            exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
            exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
        }

        test("MEMBER is forbidden from listing rooms") {
            testApplication {
                application {
                    install(StatusPages) { installEventRoomExceptionHandlers() }
                    routing { registerEventRoomTestRoutes() }
                }
                val member = createMember(AccountRole.MEMBER)
                val response = client.post("/test/event-room/list") { header("X-Member-Id", member.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("TREASURER is forbidden from creating a room") {
            testApplication {
                application {
                    install(StatusPages) { installEventRoomExceptionHandlers() }
                    routing { registerEventRoomTestRoutes() }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val response = client.post("/test/event-room/create") { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("BOARD can create, list, deactivate and reactivate a room") {
            testApplication {
                application {
                    install(StatusPages) { installEventRoomExceptionHandlers() }
                    routing { registerEventRoomTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)

                val createResponse =
                    client.post("/test/event-room/create?name=Konferenzraum-${Uuid.random()}") {
                        header("X-Member-Id", board.toString())
                    }
                createResponse.status shouldBe HttpStatusCode.OK
                val roomId = createResponse.bodyAsText()

                val listResponse = client.post("/test/event-room/list") { header("X-Member-Id", board.toString()) }
                listResponse.status shouldBe HttpStatusCode.OK

                val deactivateResponse =
                    client.post("/test/event-room/$roomId/deactivate") { header("X-Member-Id", board.toString()) }
                deactivateResponse.status shouldBe HttpStatusCode.OK
                deactivateResponse.bodyAsText() shouldBe EventRoomStatus.INACTIVE.name

                val activateResponse = client.post("/test/event-room/$roomId/activate") { header("X-Member-Id", board.toString()) }
                activateResponse.status shouldBe HttpStatusCode.OK
                activateResponse.bodyAsText() shouldBe EventRoomStatus.ACTIVE.name
            }
        }

        test("ADMIN can create a room; a duplicate name is rejected with Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installEventRoomExceptionHandlers() }
                    routing { registerEventRoomTestRoutes() }
                }
                val admin = createMember(AccountRole.ADMIN)
                val sharedName = "Duplikat-RPC-Raum-${Uuid.random()}"

                val first = client.post("/test/event-room/create?name=$sharedName") { header("X-Member-Id", admin.toString()) }
                first.status shouldBe HttpStatusCode.OK

                val second = client.post("/test/event-room/create?name=$sharedName") { header("X-Member-Id", admin.toString()) }
                second.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "50 tags of 100 chars each pass both per-tag checks but the joined CSV overflows " +
                "equipment_tags VARCHAR(1000) -- must be a clean BadRequest, not a raw DB exception",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installEventRoomExceptionHandlers() }
                    routing { registerEventRoomTestRoutes() }
                }
                val admin = createMember(AccountRole.ADMIN)

                // 50 tags * 100 chars + 49 comma separators = 5049 chars, comfortably over
                // VARCHAR(1000) -- yet tagCount==50<=MAX_EQUIPMENT_TAGS and tagLength==100<=
                // MAX_TAG_LENGTH, so neither individual check would catch this.
                val response =
                    client.post("/test/event-room/create-with-tags?tagCount=50&tagLength=100") {
                        header("X-Member-Id", admin.toString())
                    }

                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldBe
                    "Die Ausstattungsmerkmale sind in der Summe zu lang (höchstens 1000 Zeichen, aktuell 5049)."
            }
        }

        test("49 tags of 20 chars each stay within the CSV limit and are accepted") {
            testApplication {
                application {
                    install(StatusPages) { installEventRoomExceptionHandlers() }
                    routing { registerEventRoomTestRoutes() }
                }
                val admin = createMember(AccountRole.ADMIN)

                // 40 tags * 20 chars + 39 separators = 839 -- comfortably under the VARCHAR(1000)
                // limit, positive control for the negative test above.
                val response =
                    client.post("/test/event-room/create-with-tags?tagCount=40&tagLength=20") {
                        header("X-Member-Id", admin.toString())
                    }

                response.status shouldBe HttpStatusCode.OK
            }
        }
    })
