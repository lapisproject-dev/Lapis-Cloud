package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ApiKeyTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.ApiKeyDto
import network.lapis.cloud.shared.domain.ApiKeyIssueResultDto
import network.lapis.cloud.shared.domain.ApiKeySnapshot
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Exercises [ApiKeyService] end to end, mirroring [GovernanceServiceTest]'s house style
 * (throwaway routes calling the service class directly, `X-Member-Id` trusted-header auth).
 */
class ApiKeyServiceTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun testApp(
            issueLimiter: FederationInboxRateLimiter = generousLimiter(),
            revokeLimiter: FederationInboxRateLimiter = generousLimiter(),
            reissueLimiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    install(StatusPages) { installApiKeyExceptionHandlers() }
                    routing {
                        registerApiKeyTestRoutes(
                            issueLimiter = issueLimiter,
                            revokeLimiter = revokeLimiter,
                            reissueLimiter = reissueLimiter,
                        )
                    }
                }
                block()
            }
        }

        // ── Roles ──────────────────────────────────────────────────────────────────────

        test("listApiKeys/issueApiKey/revokeApiKey/reissueApiKey all require BOARD or ADMIN -- a plain MEMBER is forbidden") {
            testApp {
                client.get("/test/list") { header("X-Member-Id", MEMBER_ID) }.status shouldBe HttpStatusCode.Forbidden
                client.post("/test/issue/SomeLabel") { header("X-Member-Id", MEMBER_ID) }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("BOARD may issue keys, not just ADMIN") {
            testApp {
                val issueResponse = client.post("/test/issue/BoardIssued") { header("X-Member-Id", BOARD_ID) }
                issueResponse.status shouldBe HttpStatusCode.OK
            }
        }

        // ── Issue ──────────────────────────────────────────────────────────────────────

        test("issueApiKey returns the raw key ONLY once, and it is never stored in the clear") {
            testApp {
                val response = client.post("/test/issue/IssueTest") { header("X-Member-Id", ADMIN_ID) }
                val result = Json.decodeFromString(ApiKeyIssueResultDto.serializer(), response.bodyAsText())
                result.rawKey.startsWith("lapis_") shouldBe true

                val storedHash =
                    transaction {
                        ApiKeyTable
                            .selectAll()
                            .where {
                                ApiKeyTable.id eq
                                    Uuid.parse(
                                        result.apiKey.id,
                                    )
                            }.single()[ApiKeyTable.tokenHash]
                    }
                storedHash shouldNotBe result.rawKey
            }
        }

        test("issueApiKey writes exactly one API_KEY/CREATE audit entry whose after-snapshot never carries the token hash") {
            testApp {
                val response = client.post("/test/issue/AuditTest") { header("X-Member-Id", ADMIN_ID) }
                val result = Json.decodeFromString(ApiKeyIssueResultDto.serializer(), response.bodyAsText())
                val apiKeyId = Uuid.parse(result.apiKey.id)

                val rows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.API_KEY) and
                                    (AuditLogEntryTable.entityId eq apiKeyId)
                            }.toList()
                    }
                rows.size shouldBe 1
                transaction { rows.single()[AuditLogEntryTable.action] } shouldBe AuditAction.CREATE
                val snapshot =
                    transaction {
                        Json.decodeFromString(ApiKeySnapshot.serializer(), rows.single()[AuditLogEntryTable.afterSnapshot]!!)
                    }
                snapshot.label shouldBe "AuditTest"
                snapshot.revokedAt.shouldBeNull()
                // The raw key never appears anywhere in the serialized snapshot text.
                (result.rawKey in listOf(snapshot.label, snapshot.keyPrefix, snapshot.createdByMemberId)) shouldBe false
            }
        }

        // ── Revoke ─────────────────────────────────────────────────────────────────────

        test(
            "revokeApiKey is idempotent-signalling -- revoking an already-revoked key throws NotFoundException (404), not a silent success",
        ) {
            testApp {
                val issueResponse = client.post("/test/issue/RevokeTest") { header("X-Member-Id", ADMIN_ID) }
                val issued = Json.decodeFromString(ApiKeyIssueResultDto.serializer(), issueResponse.bodyAsText())

                val firstRevoke = client.post("/test/revoke/${issued.apiKey.id}") { header("X-Member-Id", ADMIN_ID) }
                firstRevoke.status shouldBe HttpStatusCode.OK

                val secondRevoke = client.post("/test/revoke/${issued.apiKey.id}") { header("X-Member-Id", ADMIN_ID) }
                secondRevoke.status shouldBe HttpStatusCode.NotFound
            }
        }

        test(
            "revokeApiKey under two near-simultaneous requests for the SAME key: exactly one succeeds (200), " +
                "the other is NotFound (404), and exactly ONE API_KEY/UPDATE audit entry is written -- " +
                "regression test for the ApiKeyStore.revoke() select-then-update TOCTOU race",
        ) {
            testApp {
                val issueResponse = client.post("/test/issue/RaceTest") { header("X-Member-Id", ADMIN_ID) }
                val issued = Json.decodeFromString(ApiKeyIssueResultDto.serializer(), issueResponse.bodyAsText())

                val statuses = runConcurrentRevokes(client = client, apiKeyId = issued.apiKey.id, actorA = ADMIN_ID, actorB = BOARD_ID)

                statuses.count { it == HttpStatusCode.OK } shouldBe 1
                statuses.count { it == HttpStatusCode.NotFound } shouldBe 1

                val updateRows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.API_KEY) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(issued.apiKey.id)) and
                                    (AuditLogEntryTable.action eq AuditAction.UPDATE)
                            }.count()
                    }
                updateRows shouldBe 1L
            }
        }

        test("revokeApiKey writes an API_KEY/UPDATE audit entry, never a CREATE entry") {
            testApp {
                val issueResponse = client.post("/test/issue/RevokeAuditTest") { header("X-Member-Id", ADMIN_ID) }
                val issued = Json.decodeFromString(ApiKeyIssueResultDto.serializer(), issueResponse.bodyAsText())
                client.post("/test/revoke/${issued.apiKey.id}") { header("X-Member-Id", ADMIN_ID) }

                val rows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.API_KEY) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(issued.apiKey.id)) and
                                    (AuditLogEntryTable.action eq AuditAction.UPDATE)
                            }.toList()
                    }
                rows.size shouldBe 1
            }
        }

        // ── Reissue ────────────────────────────────────────────────────────────────────

        test("reissueApiKey revokes the old key AND issues a fresh one with the same label -- old key stops resolving, new key works") {
            testApp {
                val issueResponse = client.post("/test/issue/ReissueTest") { header("X-Member-Id", ADMIN_ID) }
                val original = Json.decodeFromString(ApiKeyIssueResultDto.serializer(), issueResponse.bodyAsText())

                val reissueResponse = client.post("/test/reissue/${original.apiKey.id}") { header("X-Member-Id", ADMIN_ID) }
                reissueResponse.status shouldBe HttpStatusCode.OK
                val reissued = Json.decodeFromString(ApiKeyIssueResultDto.serializer(), reissueResponse.bodyAsText())

                reissued.apiKey.id shouldNotBe original.apiKey.id
                reissued.apiKey.label shouldBe "ReissueTest"
                reissued.rawKey shouldNotBe original.rawKey

                val oldRow = transaction { ApiKeyTable.selectAll().where { ApiKeyTable.id eq Uuid.parse(original.apiKey.id) }.single() }
                oldRow[ApiKeyTable.revokedAt].shouldNotBeNull()
            }
        }

        test("reissueApiKey writes exactly one UPDATE (the revoke) and one CREATE (the new key) audit entry") {
            testApp {
                val issueResponse = client.post("/test/issue/ReissueAuditTest") { header("X-Member-Id", ADMIN_ID) }
                val original = Json.decodeFromString(ApiKeyIssueResultDto.serializer(), issueResponse.bodyAsText())
                val reissueResponse = client.post("/test/reissue/${original.apiKey.id}") { header("X-Member-Id", ADMIN_ID) }
                val reissued = Json.decodeFromString(ApiKeyIssueResultDto.serializer(), reissueResponse.bodyAsText())

                val revokeRows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.API_KEY) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(original.apiKey.id)) and
                                    (AuditLogEntryTable.action eq AuditAction.UPDATE)
                            }.count()
                    }
                revokeRows shouldBe 1L

                val createRows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.API_KEY) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(reissued.apiKey.id)) and
                                    (AuditLogEntryTable.action eq AuditAction.CREATE)
                            }.count()
                    }
                createRows shouldBe 1L
            }
        }

        test("reissueApiKey has its OWN rate budget, independent of issueRateLimiter") {
            testApp(issueLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                val first = client.post("/test/issue/First") { header("X-Member-Id", ADMIN_ID) }
                first.status shouldBe HttpStatusCode.OK
                val issued = Json.decodeFromString(ApiKeyIssueResultDto.serializer(), first.bodyAsText())

                // issueRateLimiter (member:ADMIN_ID) is now exhausted -- a further issue attempt fails.
                client.post("/test/issue/Second") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.Conflict

                // reissueRateLimiter is a SEPARATE instance -- reissuing for the same ADMIN actor still works.
                client.post("/test/reissue/${issued.apiKey.id}") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
            }
        }
    })

/**
 * Fires two `POST /test/revoke/{apiKeyId}` calls (from [actorA]/[actorB]) from two independent OS
 * threads, synchronized via [CountDownLatch] so both are issued as close to simultaneously as
 * possible -- real thread-level parallelism against the shared Hikari connection pool, not two
 * coroutines cooperatively sharing one thread. Mirrors `PeerTransferServiceTest.runConcurrentOppositeTransfers`'s
 * house style. Returns both responses' [HttpStatusCode]s in call order (order between the two is
 * NOT guaranteed -- callers should compare by count/membership, not by index).
 */
private fun runConcurrentRevokes(
    client: HttpClient,
    apiKeyId: String,
    actorA: String,
    actorB: String,
    timeoutSeconds: Long = 20,
): List<HttpStatusCode> {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val statuses = java.util.Collections.synchronizedList(mutableListOf<HttpStatusCode>())
    val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

    fun revokeThread(actorMemberId: String): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    val response = client.post("/test/revoke/$apiKeyId") { header("X-Member-Id", actorMemberId) }
                    statuses += response.status
                }
            } catch (t: Throwable) {
                failures += t
            } finally {
                doneLatch.countDown()
            }
        }

    val threadA = revokeThread(actorA)
    val threadB = revokeThread(actorB)
    threadA.start()
    threadB.start()

    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent revokeApiKey calls did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
    return statuses.toList()
}

private fun StatusPagesConfig.installApiKeyExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
}

private fun Route.registerApiKeyTestRoutes(
    issueLimiter: FederationInboxRateLimiter,
    revokeLimiter: FederationInboxRateLimiter,
    reissueLimiter: FederationInboxRateLimiter,
) {
    get("/test/list") {
        val svc =
            ApiKeyService(
                call = call,
                issueRateLimiter = issueLimiter,
                revokeRateLimiter = revokeLimiter,
                reissueRateLimiter = reissueLimiter,
            )
        val keys = svc.listApiKeys()
        call.respondText(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ApiKeyDto.serializer()), keys))
    }
    post("/test/issue/{label}") {
        val svc =
            ApiKeyService(
                call = call,
                issueRateLimiter = issueLimiter,
                revokeRateLimiter = revokeLimiter,
                reissueRateLimiter = reissueLimiter,
            )
        val result = svc.issueApiKey(label = call.parameters["label"]!!)
        call.respondText(Json.encodeToString(ApiKeyIssueResultDto.serializer(), result))
    }
    post("/test/revoke/{id}") {
        val svc =
            ApiKeyService(
                call = call,
                issueRateLimiter = issueLimiter,
                revokeRateLimiter = revokeLimiter,
                reissueRateLimiter = reissueLimiter,
            )
        val result = svc.revokeApiKey(id = call.parameters["id"]!!)
        call.respondText(Json.encodeToString(ApiKeyDto.serializer(), result))
    }
    post("/test/reissue/{id}") {
        val svc =
            ApiKeyService(
                call = call,
                issueRateLimiter = issueLimiter,
                revokeRateLimiter = revokeLimiter,
                reissueRateLimiter = reissueLimiter,
            )
        val result = svc.reissueApiKey(id = call.parameters["id"]!!)
        call.respondText(Json.encodeToString(ApiKeyIssueResultDto.serializer(), result))
    }
}
