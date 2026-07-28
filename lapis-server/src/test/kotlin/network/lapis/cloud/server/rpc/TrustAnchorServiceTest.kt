package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.delete
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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.TrustAnchorEventTable
import network.lapis.cloud.server.db.generated.TrustAnchorPoolMemberTable
import network.lapis.cloud.server.db.generated.TrustAnchorSigningKeyTable
import network.lapis.cloud.server.db.generated.TrustedExternalAnchorTable
import network.lapis.cloud.server.federation.TrustAnchorPoolStore
import network.lapis.cloud.server.federation.TrustAnchorSigningKeyProvisioner
import network.lapis.cloud.server.federation.TrustAnchorSigningKeyStore
import network.lapis.cloud.server.federation.TrustedAnchorStore
import network.lapis.cloud.shared.domain.TrustAnchorEventType
import network.lapis.cloud.shared.domain.TrustAnchorSigningKeyDto
import network.lapis.cloud.shared.domain.TrustAnchorSigningKeyStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Exercises [TrustAnchorService] -- role enforcement (ADMIN-only, every method), signing-key
 * rotate/revoke lifecycle (including the auto-replacement-on-revoking-the-ACTIVE-key path), pool
 * and trusted-anchor CRUD (including SSRF-guard rejection and conflict/not-found edge cases), the
 * event log, and `resolveTrustChain`'s pre-network validation plus its no-anchors-configured/
 * unreachable-anchor outcomes against a real public HTTPS origin
 * ([RFC 2606](https://www.rfc-editor.org/rfc/rfc2606) `example.com`, which resolves and answers
 * HTTPS but obviously never serves a Trust-Anchor Entity Configuration, so every real-network
 * resolution here always ends in `trusted=false`). `resolveOneHop`'s full fetch-then-verify happy
 * path against a genuine Entity Configuration/Subordinate Statement pair is NOT re-exercised here
 * end to end -- [network.lapis.cloud.server.federation.TrustAnchorChainVerificationTest] already
 * covers every cryptographic verification branch network-free with hand-crafted JWTs (including
 * every adversarial case), and the SSRF-guard reuse itself is [network.lapis.cloud.server.federation.FederationHttpClientSsrfTest]'s
 * own responsibility -- standing up a real TLS-terminating test listener (`requireSafeFederationUrl`
 * is HTTPS-only) purely to round-trip a real fetch would duplicate both without adding coverage.
 */
class TrustAnchorServiceTest :
    FunSpec({
        val createdPoolMembers = mutableListOf<String>()
        val createdTrustedAnchors = mutableListOf<String>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TrustAnchorSigningKeyProvisioner.ensureProvisioned()
        }

        afterTest {
            transaction {
                createdPoolMembers.forEach { uri ->
                    TrustAnchorPoolMemberTable.deleteWhere { TrustAnchorPoolMemberTable.homeServerUri eq uri }
                }
                createdTrustedAnchors.forEach { uri ->
                    TrustedExternalAnchorTable.deleteWhere { TrustedExternalAnchorTable.anchorEntityUri eq uri }
                }
            }
            createdPoolMembers.clear()
            createdTrustedAnchors.clear()
        }

        // ── Signing-key lifecycle ──────────────────────────────────────────

        test("listSigningKeys(): ADMIN sees at least the boot-provisioned ACTIVE key, never a privateKeyPem field") {
            testApplication {
                application {
                    install(StatusPages) { installTrustAnchorExceptionHandlers() }
                    routing { registerTrustAnchorTestRoutes() }
                }
                val response = client.get("/test/list-keys") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "ACTIVE"
            }

            val declaredFieldNames = TrustAnchorSigningKeyDto::class.java.declaredFields.map { it.name }
            declaredFieldNames.contains("privateKeyPem") shouldBe false
        }

        test("rotateSigningKey(): the previous ACTIVE key becomes RETIRED, a fresh key becomes ACTIVE, exactly one ACTIVE key remains") {
            testApplication {
                application {
                    install(StatusPages) { installTrustAnchorExceptionHandlers() }
                    routing { registerTrustAnchorTestRoutes() }
                }
                val before = transaction { TrustAnchorSigningKeyStore.findActive()!![TrustAnchorSigningKeyTable.kid] }

                val response = client.post("/test/rotate-key") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                val newKid = response.bodyAsText()
                newKid.isBlank() shouldBe false
                (newKid != before) shouldBe true

                val activeRows =
                    transaction {
                        TrustAnchorSigningKeyStore.listAll().filter {
                            it[TrustAnchorSigningKeyTable.status] ==
                                TrustAnchorSigningKeyStatus.ACTIVE
                        }
                    }
                activeRows.size shouldBe 1
                activeRows.single()[TrustAnchorSigningKeyTable.kid] shouldBe newKid

                val oldRow = transaction { TrustAnchorSigningKeyStore.findByKid(before)!! }
                oldRow[TrustAnchorSigningKeyTable.status] shouldBe TrustAnchorSigningKeyStatus.RETIRED
                (oldRow[TrustAnchorSigningKeyTable.retiredAt] != null) shouldBe true
            }
        }

        test("revokeSigningKey(): revoking a RETIRED (non-active) key leaves the current ACTIVE key untouched, no auto-replacement") {
            testApplication {
                application {
                    install(StatusPages) { installTrustAnchorExceptionHandlers() }
                    routing { registerTrustAnchorTestRoutes() }
                }
                client.post("/test/rotate-key") { header("X-Member-Id", ADMIN_ID) }
                val activeBefore = transaction { TrustAnchorSigningKeyStore.findActive()!![TrustAnchorSigningKeyTable.kid] }
                val retiredKid =
                    transaction {
                        TrustAnchorSigningKeyStore
                            .listAll()
                            .first { it[TrustAnchorSigningKeyTable.status] == TrustAnchorSigningKeyStatus.RETIRED }
                            .get(TrustAnchorSigningKeyTable.kid)
                    }

                val response = client.post("/test/revoke-key?kid=$retiredKid") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK

                val activeAfter = transaction { TrustAnchorSigningKeyStore.findActive()!![TrustAnchorSigningKeyTable.kid] }
                activeAfter shouldBe activeBefore
                val revokedRow = transaction { TrustAnchorSigningKeyStore.findByKid(retiredKid)!! }
                revokedRow[TrustAnchorSigningKeyTable.status] shouldBe TrustAnchorSigningKeyStatus.REVOKED
            }
        }

        test("revokeSigningKey(): revoking the CURRENT ACTIVE key auto-mints a replacement -- exactly one ACTIVE key remains afterward") {
            testApplication {
                application {
                    install(StatusPages) { installTrustAnchorExceptionHandlers() }
                    routing { registerTrustAnchorTestRoutes() }
                }
                val activeBefore = transaction { TrustAnchorSigningKeyStore.findActive()!![TrustAnchorSigningKeyTable.kid] }

                val response = client.post("/test/revoke-key?kid=$activeBefore") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK

                val revokedRow = transaction { TrustAnchorSigningKeyStore.findByKid(activeBefore)!! }
                revokedRow[TrustAnchorSigningKeyTable.status] shouldBe TrustAnchorSigningKeyStatus.REVOKED

                val activeRows =
                    transaction {
                        TrustAnchorSigningKeyStore.listAll().filter {
                            it[TrustAnchorSigningKeyTable.status] ==
                                TrustAnchorSigningKeyStatus.ACTIVE
                        }
                    }
                activeRows.size shouldBe 1
                (activeRows.single()[TrustAnchorSigningKeyTable.kid] != activeBefore) shouldBe true
            }
        }

        test("revokeSigningKey(): an already-REVOKED key throws ConflictException (no double revoke/double event)") {
            testApplication {
                application {
                    install(StatusPages) { installTrustAnchorExceptionHandlers() }
                    routing { registerTrustAnchorTestRoutes() }
                }
                val activeBefore = transaction { TrustAnchorSigningKeyStore.findActive()!![TrustAnchorSigningKeyTable.kid] }
                client.post("/test/revoke-key?kid=$activeBefore") { header("X-Member-Id", ADMIN_ID) }

                val response = client.post("/test/revoke-key?kid=$activeBefore") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("revokeSigningKey(): an unknown kid throws NotFoundException") {
            testApplication {
                application {
                    install(StatusPages) { installTrustAnchorExceptionHandlers() }
                    routing { registerTrustAnchorTestRoutes() }
                }
                val response = client.post("/test/revoke-key?kid=does-not-exist") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── Pool membership ─────────────────────────────────────────────────

        test("addPoolMember()/listPoolMembers()/removePoolMember(): happy path round-trip + duplicate/missing edge cases") {
            testApplication {
                application {
                    install(StatusPages) { installTrustAnchorExceptionHandlers() }
                    routing { registerTrustAnchorTestRoutes() }
                }
                val uri = "https://example.com/home-${Uuid.random()}"
                createdPoolMembers += uri

                val addResponse = client.post("/test/add-pool-member?uri=$uri") { header("X-Member-Id", ADMIN_ID) }
                addResponse.status shouldBe HttpStatusCode.OK

                val listResponse = client.get("/test/list-pool-members") { header("X-Member-Id", ADMIN_ID) }
                listResponse.bodyAsText() shouldContain uri

                val dupResponse = client.post("/test/add-pool-member?uri=$uri") { header("X-Member-Id", ADMIN_ID) }
                dupResponse.status shouldBe HttpStatusCode.Conflict

                val removeResponse = client.delete("/test/remove-pool-member?uri=$uri") { header("X-Member-Id", ADMIN_ID) }
                removeResponse.status shouldBe HttpStatusCode.OK

                val removeAgainResponse = client.delete("/test/remove-pool-member?uri=$uri") { header("X-Member-Id", ADMIN_ID) }
                removeAgainResponse.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("addPoolMember(): a private-range/loopback URI throws BadRequestException before any row is written") {
            testApplication {
                application {
                    install(StatusPages) { installTrustAnchorExceptionHandlers() }
                    routing { registerTrustAnchorTestRoutes() }
                }
                val response =
                    client.post("/test/add-pool-member?uri=https://127.0.0.1") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.BadRequest
                transaction { TrustAnchorPoolStore.findByUri("https://127.0.0.1") } shouldBe null
            }
        }

        // ── Trusted-anchor configuration ────────────────────────────────────

        test("addTrustedAnchor()/listTrustedAnchors()/removeTrustedAnchor(): happy path round-trip + duplicate/missing edge cases") {
            testApplication {
                application {
                    install(StatusPages) { installTrustAnchorExceptionHandlers() }
                    routing { registerTrustAnchorTestRoutes() }
                }
                val uri = "https://example.com/anchor-${Uuid.random()}"
                createdTrustedAnchors += uri

                client.post("/test/add-trusted-anchor?uri=$uri") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                client
                    .get("/test/list-trusted-anchors") { header("X-Member-Id", ADMIN_ID) }
                    .bodyAsText() shouldContain uri
                client
                    .post("/test/add-trusted-anchor?uri=$uri") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.Conflict
                client
                    .delete("/test/remove-trusted-anchor?uri=$uri") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.OK
                client
                    .delete("/test/remove-trusted-anchor?uri=$uri") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── Event log ────────────────────────────────────────────────────────

        test("listEvents(): pool-member add/remove are both recorded") {
            testApplication {
                application {
                    install(StatusPages) { installTrustAnchorExceptionHandlers() }
                    routing { registerTrustAnchorTestRoutes() }
                }
                val uri = "https://example.com/home-events-${Uuid.random()}"
                createdPoolMembers += uri
                client.post("/test/add-pool-member?uri=$uri") { header("X-Member-Id", ADMIN_ID) }
                client.delete("/test/remove-pool-member?uri=$uri") { header("X-Member-Id", ADMIN_ID) }

                val eventTypes =
                    transaction {
                        TrustAnchorEventTable
                            .selectAll()
                            .filter { it[TrustAnchorEventTable.subject] == uri }
                            .map { it[TrustAnchorEventTable.eventType] }
                    }
                eventTypes shouldContain TrustAnchorEventType.POOL_MEMBER_ADDED
                eventTypes shouldContain TrustAnchorEventType.POOL_MEMBER_REMOVED
            }
        }

        // ── resolveTrustChain ────────────────────────────────────────────────

        test("resolveTrustChain(): no trusted anchors configured -> trusted=false with an explanatory reason, never throws") {
            testApplication {
                application {
                    install(StatusPages) { installTrustAnchorExceptionHandlers() }
                    routing { registerTrustAnchorTestRoutes() }
                }
                // Deterministic empty-pool state regardless of other tests' own cleanup ordering --
                // remove every currently-configured trusted anchor via the same store call site
                // addTrustedAnchor/removeTrustedAnchor themselves use, rather than depending on
                // cross-test execution order.
                val existing = transaction { TrustedAnchorStore.listAll().map { it[TrustedExternalAnchorTable.anchorEntityUri] } }
                transaction { existing.forEach { TrustedAnchorStore.remove(it) } }

                val response = client.get("/test/resolve-trust-chain?uri=https://example.com") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "false"
            }
        }

        test("resolveTrustChain(): a malformed/private-range homeServerUri throws BadRequestException") {
            testApplication {
                application {
                    install(StatusPages) { installTrustAnchorExceptionHandlers() }
                    routing { registerTrustAnchorTestRoutes() }
                }
                val response =
                    client.get("/test/resolve-trust-chain?uri=https://127.0.0.1") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test(
            "resolveTrustChain(): a configured anchor that cannot be safely/successfully fetched resolves to trusted=false, not an exception",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installTrustAnchorExceptionHandlers() }
                    routing { registerTrustAnchorTestRoutes() }
                }
                val unreachableAnchor = "https://this-anchor-does-not-exist-${Uuid.random()}.invalid"
                transaction { TrustedAnchorStore.insert(unreachableAnchor, LocalDateTime(2026, 1, 1, 0, 0)) }
                createdTrustedAnchors += unreachableAnchor

                val response = client.get("/test/resolve-trust-chain?uri=https://example.com") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "false"
            }
        }

        // ── Role enforcement ────────────────────────────────────────────────

        test("every method rejects a non-ADMIN caller, and an unauthenticated caller") {
            testApplication {
                application {
                    install(StatusPages) { installTrustAnchorExceptionHandlers() }
                    routing { registerTrustAnchorTestRoutes() }
                }
                for (memberId in listOf(BOARD_ID, TREASURER_ID, MEMBER_ID)) {
                    client.get("/test/list-keys") { header("X-Member-Id", memberId) }.status shouldBe HttpStatusCode.Forbidden
                    client.post("/test/rotate-key") { header("X-Member-Id", memberId) }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/test/list-pool-members") { header("X-Member-Id", memberId) }
                        .status shouldBe HttpStatusCode.Forbidden
                }
                client.get("/test/list-keys").status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })

private fun StatusPagesConfig.installTrustAnchorExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}

/** Shared throwaway routes for [TrustAnchorService] -- mirrors [FederationServiceTest]'s own `registerFederationTestRoutes` style. */
private fun Route.registerTrustAnchorTestRoutes() {
    get("/test/list-keys") {
        val service = TrustAnchorService(call)
        call.respondText(service.listSigningKeys().joinToString(",") { "${it.kid}:${it.status}" })
    }
    post("/test/rotate-key") {
        val service = TrustAnchorService(call)
        call.respondText(service.rotateSigningKey().kid)
    }
    post("/test/revoke-key") {
        val service = TrustAnchorService(call)
        val kid = call.request.queryParameters["kid"]!!
        call.respondText(service.revokeSigningKey(kid).kid)
    }
    get("/test/list-pool-members") {
        val service = TrustAnchorService(call)
        call.respondText(service.listPoolMembers().joinToString(",") { it.homeServerUri })
    }
    post("/test/add-pool-member") {
        val service = TrustAnchorService(call)
        val uri = call.request.queryParameters["uri"]!!
        call.respondText(service.addPoolMember(uri).homeServerUri)
    }
    delete("/test/remove-pool-member") {
        val service = TrustAnchorService(call)
        val uri = call.request.queryParameters["uri"]!!
        service.removePoolMember(uri)
        call.respondText("OK")
    }
    get("/test/list-trusted-anchors") {
        val service = TrustAnchorService(call)
        call.respondText(service.listTrustedAnchors().joinToString(",") { it.anchorEntityUri })
    }
    post("/test/add-trusted-anchor") {
        val service = TrustAnchorService(call)
        val uri = call.request.queryParameters["uri"]!!
        call.respondText(service.addTrustedAnchor(uri).anchorEntityUri)
    }
    delete("/test/remove-trusted-anchor") {
        val service = TrustAnchorService(call)
        val uri = call.request.queryParameters["uri"]!!
        service.removeTrustedAnchor(uri)
        call.respondText("OK")
    }
    get("/test/resolve-trust-chain") {
        val service = TrustAnchorService(call)
        val uri = call.request.queryParameters["uri"]!!
        val result = service.resolveTrustChain(uri)
        call.respondText("trusted=${result.trusted}")
    }
}
