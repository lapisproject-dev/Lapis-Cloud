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
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventCateringOrderTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CateringOrderInput
import network.lapis.cloud.shared.domain.CateringOrderStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
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
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.4.3.5 "Catering-Management für Veranstaltungen" -- RPC-surface coverage of
 * [CateringService] via throwaway test routes + `X-Member-Id` header, same house style
 * [EventRoomServiceRpcTest] already establishes. Covers the permission matrix (MEMBER/TREASURER
 * -> 403, BOARD/ADMIN -> success), CRUD happy path, status transitions, and the validation
 * gates (`quantity`, `description`, `allergenNotes`, unknown `eventId`).
 */
class CateringServiceRpcTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()
        val createdOrderIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdOrderIds.isNotEmpty()) EventCateringOrderTable.deleteWhere { id inList createdOrderIds }
                if (createdEventIds.isNotEmpty()) EventTable.deleteWhere { id inList createdEventIds }
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
                    it[displayName] = "CateringServiceRpcTest Mitglied"
                    it[email] = "cateringrpc-${Uuid.random()}@example.org"
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

        fun createEvent(createdBy: Uuid): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "catering-rpc-test-$id"
                    it[title] = "CateringServiceRpcTest-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = now
                    it[endsAt] = now
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[EventTable.createdAt] = now
                    it[EventTable.createdBy] = createdBy
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id
        }

        fun Route.registerCateringTestRoutes() {
            fun serviceFor(call: io.ktor.server.application.ApplicationCall) = CateringService(call = call)
            post("/test/catering/list") {
                val eventId = call.request.queryParameters["eventId"]!!
                val orders = serviceFor(call).listCateringOrders(eventId)
                call.respondText(orders.size.toString())
            }
            post("/test/catering/create") {
                val eventId = call.request.queryParameters["eventId"]!!
                val description = call.request.queryParameters["description"] ?: "Vegetarisches Buffet"
                val quantity = call.request.queryParameters["quantity"]?.toInt() ?: 20
                val allergenNotes = call.request.queryParameters["allergenNotes"]
                val order =
                    serviceFor(call).createCateringOrder(
                        CateringOrderInput(
                            eventId = eventId,
                            description = description,
                            quantity = quantity,
                            allergenNotes = allergenNotes,
                        ),
                    )
                createdOrderIds += Uuid.parse(order.id)
                call.respondText(order.id)
            }
            post("/test/catering/{id}/update") {
                val eventId = call.request.queryParameters["eventId"]!!
                val description = call.request.queryParameters["description"] ?: "Aktualisiertes Buffet"
                val quantity = call.request.queryParameters["quantity"]?.toInt() ?: 30
                val order =
                    serviceFor(call).updateCateringOrder(
                        id = call.parameters["id"]!!,
                        input = CateringOrderInput(eventId = eventId, description = description, quantity = quantity),
                    )
                call.respondText("${order.description}|${order.eventId}")
            }
            post("/test/catering/{id}/status") {
                val status = CateringOrderStatus.valueOf(call.request.queryParameters["status"]!!)
                val order = serviceFor(call).setCateringOrderStatus(id = call.parameters["id"]!!, status = status)
                call.respondText(order.status.name)
            }
            post("/test/catering/{id}/delete") {
                serviceFor(call).deleteCateringOrder(id = call.parameters["id"]!!)
                call.respondText("OK")
            }
        }

        fun StatusPagesConfig.installCateringExceptionHandlers() {
            exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
            exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
            exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
            exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
            exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
        }

        test("MEMBER is forbidden from listing catering orders") {
            testApplication {
                application {
                    install(StatusPages) { installCateringExceptionHandlers() }
                    routing { registerCateringTestRoutes() }
                }
                val member = createMember(AccountRole.MEMBER)
                val board = createMember(AccountRole.BOARD)
                val eventId = createEvent(board)
                val response =
                    client.post("/test/catering/list?eventId=$eventId") { header("X-Member-Id", member.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("TREASURER is forbidden from creating a catering order") {
            testApplication {
                application {
                    install(StatusPages) { installCateringExceptionHandlers() }
                    routing { registerCateringTestRoutes() }
                }
                val treasurer = createMember(AccountRole.TREASURER)
                val board = createMember(AccountRole.BOARD)
                val eventId = createEvent(board)
                val response =
                    client.post("/test/catering/create?eventId=$eventId") { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("BOARD can create, list, update, change status of, and delete a catering order") {
            testApplication {
                application {
                    install(StatusPages) { installCateringExceptionHandlers() }
                    routing { registerCateringTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val eventId = createEvent(board)

                val createResponse =
                    client.post("/test/catering/create?eventId=$eventId&description=Vegetarisch&quantity=20") {
                        header("X-Member-Id", board.toString())
                    }
                createResponse.status shouldBe HttpStatusCode.OK
                val orderId = createResponse.bodyAsText()

                val listResponse = client.post("/test/catering/list?eventId=$eventId") { header("X-Member-Id", board.toString()) }
                listResponse.status shouldBe HttpStatusCode.OK
                listResponse.bodyAsText() shouldBe "1"

                val updateResponse =
                    client.post("/test/catering/$orderId/update?eventId=$eventId&description=Vegan&quantity=25") {
                        header("X-Member-Id", board.toString())
                    }
                updateResponse.status shouldBe HttpStatusCode.OK
                updateResponse.bodyAsText() shouldBe "Vegan|$eventId"

                val orderedResponse =
                    client.post("/test/catering/$orderId/status?status=ORDERED") { header("X-Member-Id", board.toString()) }
                orderedResponse.status shouldBe HttpStatusCode.OK
                orderedResponse.bodyAsText() shouldBe CateringOrderStatus.ORDERED.name

                val deliveredResponse =
                    client.post("/test/catering/$orderId/status?status=DELIVERED") { header("X-Member-Id", board.toString()) }
                deliveredResponse.status shouldBe HttpStatusCode.OK
                deliveredResponse.bodyAsText() shouldBe CateringOrderStatus.DELIVERED.name

                val deleteResponse = client.post("/test/catering/$orderId/delete") { header("X-Member-Id", board.toString()) }
                deleteResponse.status shouldBe HttpStatusCode.OK

                val afterDeleteList = client.post("/test/catering/list?eventId=$eventId") { header("X-Member-Id", board.toString()) }
                afterDeleteList.bodyAsText() shouldBe "0"
            }
        }

        test("ADMIN creating a catering order for an unknown event is rejected with NotFound") {
            testApplication {
                application {
                    install(StatusPages) { installCateringExceptionHandlers() }
                    routing { registerCateringTestRoutes() }
                }
                val admin = createMember(AccountRole.ADMIN)
                val bogusEventId = Uuid.random()
                val response =
                    client.post("/test/catering/create?eventId=$bogusEventId") { header("X-Member-Id", admin.toString()) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("a non-positive quantity is rejected with BadRequest") {
            testApplication {
                application {
                    install(StatusPages) { installCateringExceptionHandlers() }
                    routing { registerCateringTestRoutes() }
                }
                val admin = createMember(AccountRole.ADMIN)
                val eventId = createEvent(admin)
                val response =
                    client.post("/test/catering/create?eventId=$eventId&quantity=0") { header("X-Member-Id", admin.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("a blank description is rejected with BadRequest") {
            testApplication {
                application {
                    install(StatusPages) { installCateringExceptionHandlers() }
                    routing { registerCateringTestRoutes() }
                }
                val admin = createMember(AccountRole.ADMIN)
                val eventId = createEvent(admin)
                val response =
                    client.post("/test/catering/create?eventId=$eventId&description=%20%20") { header("X-Member-Id", admin.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("allergenNotes over 1000 characters is rejected with a clean BadRequest, not a raw DB exception") {
            testApplication {
                application {
                    install(StatusPages) { installCateringExceptionHandlers() }
                    routing { registerCateringTestRoutes() }
                }
                val admin = createMember(AccountRole.ADMIN)
                val eventId = createEvent(admin)
                val tooLong = "A".repeat(1001)
                val response =
                    client.post("/test/catering/create?eventId=$eventId&allergenNotes=$tooLong") {
                        header("X-Member-Id", admin.toString())
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("allergenNotes at exactly 1000 characters is accepted") {
            testApplication {
                application {
                    install(StatusPages) { installCateringExceptionHandlers() }
                    routing { registerCateringTestRoutes() }
                }
                val admin = createMember(AccountRole.ADMIN)
                val eventId = createEvent(admin)
                val exactlyFits = "A".repeat(1000)
                val response =
                    client.post("/test/catering/create?eventId=$eventId&allergenNotes=$exactlyFits") {
                        header("X-Member-Id", admin.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                createdOrderIds += Uuid.parse(response.bodyAsText())
            }
        }

        test("a description over 500 characters is rejected with BadRequest") {
            testApplication {
                application {
                    install(StatusPages) { installCateringExceptionHandlers() }
                    routing { registerCateringTestRoutes() }
                }
                val admin = createMember(AccountRole.ADMIN)
                val eventId = createEvent(admin)
                val tooLong = "A".repeat(501)
                val response =
                    client.post("/test/catering/create?eventId=$eventId&description=$tooLong") {
                        header("X-Member-Id", admin.toString())
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("a description at exactly 500 characters is accepted") {
            testApplication {
                application {
                    install(StatusPages) { installCateringExceptionHandlers() }
                    routing { registerCateringTestRoutes() }
                }
                val admin = createMember(AccountRole.ADMIN)
                val eventId = createEvent(admin)
                val exactlyFits = "A".repeat(500)
                val response =
                    client.post("/test/catering/create?eventId=$eventId&description=$exactlyFits") {
                        header("X-Member-Id", admin.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                createdOrderIds += Uuid.parse(response.bodyAsText())
            }
        }

        test("updateCateringOrder with a different eventId actually reassigns the order to that event") {
            testApplication {
                application {
                    install(StatusPages) { installCateringExceptionHandlers() }
                    routing { registerCateringTestRoutes() }
                }
                val admin = createMember(AccountRole.ADMIN)
                val originalEventId = createEvent(admin)
                val otherEventId = createEvent(admin)

                val createResponse =
                    client.post("/test/catering/create?eventId=$originalEventId&description=Buffet&quantity=10") {
                        header("X-Member-Id", admin.toString())
                    }
                createResponse.status shouldBe HttpStatusCode.OK
                val orderId = createResponse.bodyAsText()
                createdOrderIds += Uuid.parse(orderId)

                val updateResponse =
                    client.post("/test/catering/$orderId/update?eventId=$otherEventId&description=Buffet&quantity=10") {
                        header("X-Member-Id", admin.toString())
                    }
                updateResponse.status shouldBe HttpStatusCode.OK
                updateResponse.bodyAsText() shouldBe "Buffet|$otherEventId"

                val originalEventList =
                    client.post("/test/catering/list?eventId=$originalEventId") { header("X-Member-Id", admin.toString()) }
                originalEventList.bodyAsText() shouldBe "0"

                val otherEventList =
                    client.post("/test/catering/list?eventId=$otherEventId") { header("X-Member-Id", admin.toString()) }
                otherEventList.bodyAsText() shouldBe "1"
            }
        }
    })
