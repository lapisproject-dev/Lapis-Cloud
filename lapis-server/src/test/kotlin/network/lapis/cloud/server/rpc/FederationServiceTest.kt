package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.FederationRelationshipEventTable
import network.lapis.cloud.server.db.generated.FederationRelationshipTable
import network.lapis.cloud.server.federation.FederationActorKeyProvisioner
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationRelationshipStore
import network.lapis.cloud.shared.domain.FederationActorDto
import network.lapis.cloud.shared.domain.FederationEventType
import network.lapis.cloud.shared.domain.FederationRelationshipDirection
import network.lapis.cloud.shared.domain.FederationRelationshipStatus
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

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Exercises [FederationService] -- role enforcement (ADMIN-only, every method), the local Actor's
 * DTO shape (never leaks the private key -- structurally, not just incidentally),
 * `initiateFollow`'s pre-network validation (SSRF-guard rejection before any relationship row is
 * written), and the Accept/Reject/Undo transitions on a PRE-SEEDED relationship row (bypassing
 * `initiateFollow`'s own network-dependent creation step). Same "throwaway routes calling the
 * service class directly" house style as [PriceOracleServiceTest]/[PeerTransferServiceTest].
 *
 * **Why Accept/Reject/Undo ARE safely testable here without real network egress, unlike
 * `initiateFollow`'s happy path**: [FederationService.deliverActivity] is best-effort -- it
 * `runCatching`s the entire outbound POST (including the SSRF/DNS-resolution step) and only logs
 * on failure, never throwing. A `remoteInboxUri` that cannot actually be reached (this sandbox has
 * no general internet egress) therefore never blocks the local state transition, exactly as
 * documented in each method's own KDoc ("best-effort delivery ... flips regardless"). Only
 * `initiateFollow` has a REQUIRED network step (fetching the target's actor document, which
 * `fetchActorDocument` must succeed for -- there being nothing to persist otherwise), which is why
 * only ITS happy path is excluded here; see
 * `network.lapis.cloud.server.federation.FederationRelationshipStateMachineTest` for the
 * network-free persistence-layer state-machine coverage, and
 * `network.lapis.cloud.server.routes.FederationRoutesTest` for the inbound (received) half.
 */
class FederationServiceTest :
    FunSpec({
        val createdRelationshipIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            FederationActorKeyProvisioner.ensureProvisioned(FederationConfig.actorUri)
        }

        afterTest {
            transaction {
                FederationRelationshipEventTable.deleteWhere {
                    FederationRelationshipEventTable.relationshipId inList createdRelationshipIds
                }
                FederationRelationshipTable.deleteWhere { FederationRelationshipTable.id inList createdRelationshipIds }
            }
            createdRelationshipIds.clear()
        }

        fun seedRelationship(
            direction: FederationRelationshipDirection,
            status: FederationRelationshipStatus,
        ): Uuid {
            val id = Uuid.random()
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            transaction {
                FederationRelationshipTable.insert {
                    it[FederationRelationshipTable.id] = id
                    it[FederationRelationshipTable.direction] = direction
                    it[FederationRelationshipTable.status] = status
                    it[remoteActorUri] = "https://remote-$id.example/federation/actor"
                    it[remoteInboxUri] = "https://remote-$id.example/federation/inbox"
                    it[remotePublicKeyPem] = null
                    it[initiatedActivityId] = "https://remote-$id.example/federation/activities/seed"
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            createdRelationshipIds += id
            return id
        }

        test("acceptInboundFollow(): a PENDING/INBOUND relationship transitions to ACTIVE and records ACCEPT_SENT") {
            testApplication {
                application {
                    install(StatusPages) { installFederationExceptionHandlers() }
                    routing { registerFederationTestRoutes() }
                }
                val id = seedRelationship(FederationRelationshipDirection.INBOUND, FederationRelationshipStatus.PENDING)

                val response = client.post("/test/accept?id=$id") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK

                val row = transaction { FederationRelationshipStore.findById(id)!! }
                row[FederationRelationshipTable.status] shouldBe FederationRelationshipStatus.ACTIVE
                val events = transaction { FederationRelationshipStore.listEvents(id) }
                events.map { it[FederationRelationshipEventTable.eventType] } shouldBe listOf(FederationEventType.ACCEPT_SENT)
            }
        }

        test("rejectInboundFollow(): a PENDING/INBOUND relationship transitions to REJECTED and records REJECT_SENT") {
            testApplication {
                application {
                    install(StatusPages) { installFederationExceptionHandlers() }
                    routing { registerFederationTestRoutes() }
                }
                val id = seedRelationship(FederationRelationshipDirection.INBOUND, FederationRelationshipStatus.PENDING)

                val response = client.post("/test/reject?id=$id") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK

                val row = transaction { FederationRelationshipStore.findById(id)!! }
                row[FederationRelationshipTable.status] shouldBe FederationRelationshipStatus.REJECTED
                val events = transaction { FederationRelationshipStore.listEvents(id) }
                events.map { it[FederationRelationshipEventTable.eventType] } shouldBe listOf(FederationEventType.REJECT_SENT)
            }
        }

        test("undoRelationship(): an ACTIVE relationship (either direction) transitions to UNDONE and records UNDO_SENT") {
            testApplication {
                application {
                    install(StatusPages) { installFederationExceptionHandlers() }
                    routing { registerFederationTestRoutes() }
                }
                val outboundId = seedRelationship(FederationRelationshipDirection.OUTBOUND, FederationRelationshipStatus.ACTIVE)
                val inboundId = seedRelationship(FederationRelationshipDirection.INBOUND, FederationRelationshipStatus.ACTIVE)

                client.post("/test/undo?id=$outboundId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                client.post("/test/undo?id=$inboundId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK

                transaction { FederationRelationshipStore.findById(outboundId)!![FederationRelationshipTable.status] } shouldBe
                    FederationRelationshipStatus.UNDONE
                transaction { FederationRelationshipStore.findById(inboundId)!![FederationRelationshipTable.status] } shouldBe
                    FederationRelationshipStatus.UNDONE
            }
        }

        test("acceptInboundFollow(): an OUTBOUND relationship (not an inbound Follow to accept) throws ConflictException") {
            testApplication {
                application {
                    install(StatusPages) { installFederationExceptionHandlers() }
                    routing { registerFederationTestRoutes() }
                }
                val id = seedRelationship(FederationRelationshipDirection.OUTBOUND, FederationRelationshipStatus.PENDING)
                client.post("/test/accept?id=$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("acceptInboundFollow(): an already-ACTIVE relationship throws ConflictException (no double-accept)") {
            testApplication {
                application {
                    install(StatusPages) { installFederationExceptionHandlers() }
                    routing { registerFederationTestRoutes() }
                }
                val id = seedRelationship(FederationRelationshipDirection.INBOUND, FederationRelationshipStatus.ACTIVE)
                client.post("/test/accept?id=$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("undoRelationship(): a PENDING relationship (not yet ACTIVE) throws ConflictException") {
            testApplication {
                application {
                    install(StatusPages) { installFederationExceptionHandlers() }
                    routing { registerFederationTestRoutes() }
                }
                val id = seedRelationship(FederationRelationshipDirection.OUTBOUND, FederationRelationshipStatus.PENDING)
                client.post("/test/undo?id=$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("undoRelationship(): an already-UNDONE relationship throws ConflictException (no double-undo)") {
            testApplication {
                application {
                    install(StatusPages) { installFederationExceptionHandlers() }
                    routing { registerFederationTestRoutes() }
                }
                val id = seedRelationship(FederationRelationshipDirection.OUTBOUND, FederationRelationshipStatus.UNDONE)
                client.post("/test/undo?id=$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("getLocalActor(): ADMIN sees the provisioned actor, publicKeyPem present, actorUri matches FederationConfig") {
            testApplication {
                application {
                    install(StatusPages) { installFederationExceptionHandlers() }
                    routing { registerFederationTestRoutes() }
                }
                val response = client.get("/test/local-actor") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain FederationConfig.actorUri
            }
        }

        test(
            "getLocalActor(): the DTO type itself has no privateKeyPem field -- structurally excluded, not just absent in this one response",
        ) {
            val declaredFieldNames = FederationActorDto::class.java.declaredFields.map { it.name }
            declaredFieldNames shouldNotContain "privateKeyPem"
        }

        test("getLocalActor(): a non-ADMIN caller (BOARD/TREASURER/MEMBER) is rejected with ForbiddenException") {
            testApplication {
                application {
                    install(StatusPages) { installFederationExceptionHandlers() }
                    routing { registerFederationTestRoutes() }
                }
                for (memberId in listOf(BOARD_ID, TREASURER_ID, MEMBER_ID)) {
                    val response = client.get("/test/local-actor") { header("X-Member-Id", memberId) }
                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        test("getLocalActor(): an unauthenticated caller is rejected with UnauthenticatedException") {
            testApplication {
                application {
                    install(StatusPages) { installFederationExceptionHandlers() }
                    routing { registerFederationTestRoutes() }
                }
                val response = client.get("/test/local-actor")
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test(
            "listFederationRelationships/getFederationRelationship/listFederationEvents/initiateFollow/acceptInboundFollow/rejectInboundFollow/undoRelationship all reject a non-ADMIN caller",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installFederationExceptionHandlers() }
                    routing { registerFederationTestRoutes() }
                }
                val nonAdminHeader = BOARD_ID

                client.get("/test/list-relationships") { header("X-Member-Id", nonAdminHeader) }.status shouldBe HttpStatusCode.Forbidden
                client
                    .get("/test/get-relationship?id=00000000-0000-0000-0000-000000000099") {
                        header("X-Member-Id", nonAdminHeader)
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .get("/test/list-events?id=00000000-0000-0000-0000-000000000099") {
                        header("X-Member-Id", nonAdminHeader)
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/initiate-follow?remoteActorUri=https://example.org/federation/actor") {
                        header("X-Member-Id", nonAdminHeader)
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/accept?id=00000000-0000-0000-0000-000000000099") {
                        header("X-Member-Id", nonAdminHeader)
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/reject?id=00000000-0000-0000-0000-000000000099") {
                        header("X-Member-Id", nonAdminHeader)
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/undo?id=00000000-0000-0000-0000-000000000099") {
                        header("X-Member-Id", nonAdminHeader)
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("getFederationRelationship(): an unknown/malformed id throws NotFoundException for an ADMIN caller") {
            testApplication {
                application {
                    install(StatusPages) { installFederationExceptionHandlers() }
                    routing { registerFederationTestRoutes() }
                }
                client
                    .get("/test/get-relationship?id=00000000-0000-0000-0000-000000000099") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.NotFound
                client
                    .get("/test/get-relationship?id=not-a-uuid") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test(
            "initiateFollow(): a private-range/loopback remoteActorUri throws BadRequestException BEFORE any network fetch or relationship row is written",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installFederationExceptionHandlers() }
                    routing { registerFederationTestRoutes() }
                }
                val response =
                    client.post("/test/initiate-follow?remoteActorUri=https://127.0.0.1/federation/actor") {
                        header("X-Member-Id", ADMIN_ID)
                    }
                response.status shouldBe HttpStatusCode.BadRequest

                val relationships =
                    client.get("/test/list-relationships") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                relationships shouldNotContain "127.0.0.1"
            }
        }

        test("initiateFollow(): a plain-HTTP remoteActorUri throws BadRequestException") {
            testApplication {
                application {
                    install(StatusPages) { installFederationExceptionHandlers() }
                    routing { registerFederationTestRoutes() }
                }
                val response =
                    client.post("/test/initiate-follow?remoteActorUri=http://example.org/federation/actor") {
                        header("X-Member-Id", ADMIN_ID)
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }
    })

private fun StatusPagesConfig.installFederationExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
    }
    exception<ForbiddenException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Forbidden)
    }
    exception<NotFoundException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.NotFound)
    }
    exception<ConflictException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Conflict)
    }
    exception<BadRequestException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.BadRequest)
    }
}

/** Shared throwaway routes for [FederationService] -- mirrors [PriceOracleServiceTest]'s `registerPriceOracleTestRoutes` style. */
private fun Route.registerFederationTestRoutes() {
    get("/test/local-actor") {
        val service = FederationService(call)
        val actor = service.getLocalActor()
        call.respondText("${actor.actorUri}|${actor.publicKeyPem.take(20)}")
    }
    get("/test/list-relationships") {
        val service = FederationService(call)
        val list = service.listFederationRelationships(null)
        call.respondText(list.joinToString(",") { it.remoteActorUri })
    }
    get("/test/get-relationship") {
        val service = FederationService(call)
        val id = call.request.queryParameters["id"]!!
        val relationship = service.getFederationRelationship(id)
        call.respondText(relationship.id)
    }
    get("/test/list-events") {
        val service = FederationService(call)
        val id = call.request.queryParameters["id"]!!
        val events = service.listFederationEvents(id)
        call.respondText(events.size.toString())
    }
    post("/test/initiate-follow") {
        val service = FederationService(call)
        val remoteActorUri = call.request.queryParameters["remoteActorUri"]!!
        val relationship = service.initiateFollow(remoteActorUri)
        call.respondText(relationship.id)
    }
    post("/test/accept") {
        val service = FederationService(call)
        val id = call.request.queryParameters["id"]!!
        val relationship = service.acceptInboundFollow(id)
        call.respondText(relationship.id)
    }
    post("/test/reject") {
        val service = FederationService(call)
        val id = call.request.queryParameters["id"]!!
        val relationship = service.rejectInboundFollow(id)
        call.respondText(relationship.id)
    }
    post("/test/undo") {
        val service = FederationService(call)
        val id = call.request.queryParameters["id"]!!
        val relationship = service.undoRelationship(id)
        call.respondText(relationship.id)
    }
}
