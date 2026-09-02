package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.WebhookEndpointTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.ApiKeyStore
import network.lapis.cloud.server.webhook.WebhookConfig
import network.lapis.cloud.server.webhook.WebhookDeliveryQueue
import network.lapis.cloud.server.webhook.WebhookEndpointDeactivation
import network.lapis.cloud.server.webhook.WebhookEndpointStore
import network.lapis.cloud.shared.domain.WebhookDeactivationReason
import network.lapis.cloud.shared.domain.WebhookDeliveryDto
import network.lapis.cloud.shared.domain.WebhookDeliveryPageDto
import network.lapis.cloud.shared.domain.WebhookEndpointDto
import network.lapis.cloud.shared.domain.WebhookEndpointSetResultDto
import network.lapis.cloud.shared.domain.WebhookEventType
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import network.lapis.cloud.shared.rpc.WebhookUrlMalformedException
import network.lapis.cloud.shared.rpc.WebhookUrlNotHttpsException
import network.lapis.cloud.shared.rpc.WebhookUrlNotPubliclyRoutableException
import network.lapis.cloud.shared.rpc.WebhookUrlTooLongException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.SecureRandom
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"
private val ADMIN_UUID = Uuid.parse(ADMIN_ID)

private fun randomKey(): ByteArray = ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes)

/**
 * Exercises [WebhookService]'s lifecycle CRUD end to end (role gate, D6 URL validation, secret
 * exposure discipline, ApiKeyService cascade regressions) -- mirrors [ApiKeyServiceTest]'s house
 * style (throwaway routes, `X-Member-Id` trusted-header auth).
 *
 * **Deliberately does NOT exercise `sendWebhookTestEvent`'s actual HTTP delivery outcome**: the
 * SSRF guard this same wave introduces correctly refuses every loopback/private address a local
 * test HTTP server would use, by design -- there is no publicly-routable HTTPS endpoint available
 * to point at from this test suite without network mocking, which is out of scope for this wave's
 * test budget. [network.lapis.cloud.server.webhook.WebhookSignerTest]/[network.lapis.cloud.server.webhook.OutboundUrlGuardTest]/
 * [network.lapis.cloud.server.webhook.WebhookDeliveryQueueTest] cover the mechanics that ARE
 * testable without a real network peer.
 */
class WebhookServiceTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)
        val secretBox = SecretBox(randomKey())
        val config =
            WebhookConfig(
                enabled = true,
                allowInsecureHttp = false,
                pollIntervalSeconds = 10,
                maxDeliveriesPerTick = 50,
                maxConcurrentDeliveries = 4,
                retentionDays = 30,
                secretEncryptionKey = null,
            )

        suspend fun testApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
            testApplication {
                application {
                    install(StatusPages) { installWebhookExceptionHandlers() }
                    routing {
                        registerWebhookTestRoutes(
                            config = config,
                            secretBox = secretBox,
                            configureLimiter = generousLimiter(),
                            rotateLimiter = generousLimiter(),
                            testLimiter = generousLimiter(),
                            deliveryLogLimiter = generousLimiter(),
                        )
                    }
                }
                block()
            }
        }

        test("listWebhookEndpoints/setWebhookUrl all require BOARD or ADMIN -- a plain MEMBER is forbidden") {
            testApp {
                client.get("/test/list") { header("X-Member-Id", MEMBER_ID) }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("setWebhookUrl on a fresh endpoint returns a whsec_lapis_ rawSecret; a second call (update) does not") {
            testApp {
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Test A", createdByMemberId = ADMIN_UUID).id }
                val createResp =
                    client.post("/test/set/$apiKeyId") {
                        header("X-Member-Id", ADMIN_ID)
                        setBody("https://example.com/hook-a")
                    }
                val created = Json.decodeFromString(WebhookEndpointSetResultDto.serializer(), createResp.bodyAsText())
                created.rawSecret.shouldNotBeNull()
                (created.rawSecret!!.startsWith("whsec_lapis_")) shouldBe true

                val updateResp =
                    client.post("/test/set/$apiKeyId") {
                        header("X-Member-Id", ADMIN_ID)
                        setBody("https://example.com/hook-a-changed")
                    }
                val updated = Json.decodeFromString(WebhookEndpointSetResultDto.serializer(), updateResp.bodyAsText())
                updated.rawSecret.shouldBeNull()
                updated.endpoint.url shouldBe "https://example.com/hook-a-changed"
            }
        }

        test("setWebhookUrl rejects http:// as NOT_HTTPS (WebhookUrlNotHttpsException -> mapped 422)") {
            testApp {
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Test B", createdByMemberId = ADMIN_UUID).id }
                val resp =
                    client.post("/test/set/$apiKeyId") {
                        header("X-Member-Id", ADMIN_ID)
                        setBody("http://example.com/hook")
                    }
                resp.status shouldBe HttpStatusCode.fromValue(422)
            }
        }

        test("setWebhookUrl rejects a loopback address as NOT_PUBLICLY_ROUTABLE") {
            testApp {
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Test C", createdByMemberId = ADMIN_UUID).id }
                val resp =
                    client.post("/test/set/$apiKeyId") {
                        header("X-Member-Id", ADMIN_ID)
                        setBody("https://127.0.0.1/hook")
                    }
                resp.status shouldBe HttpStatusCode.fromValue(423)
            }
        }

        test("setWebhookUrl rejects an overlong URL as TOO_LONG") {
            testApp {
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Test D", createdByMemberId = ADMIN_UUID).id }
                val resp =
                    client.post("/test/set/$apiKeyId") {
                        header("X-Member-Id", ADMIN_ID)
                        setBody(
                            "https://example.com/" + "a".repeat(2100),
                        )
                    }
                resp.status shouldBe HttpStatusCode.fromValue(424)
            }
        }

        test("listWebhookEndpoints never exposes the raw secret or secret_sealed -- only secretPrefix") {
            testApp {
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Test E", createdByMemberId = ADMIN_UUID).id }
                client.post("/test/set/$apiKeyId") {
                    header("X-Member-Id", ADMIN_ID)
                    setBody("https://example.com/hook-e")
                }
                val listResp = client.get("/test/list") { header("X-Member-Id", ADMIN_ID) }
                val endpoints =
                    Json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(WebhookEndpointDto.serializer()),
                        listResp.bodyAsText(),
                    )
                val endpoint = endpoints.single { it.apiKeyId == apiKeyId.toString() }
                endpoint.secretPrefix.startsWith("whsec_lapis_") shouldBe true
                (endpoint.secretPrefix.length < 30) shouldBe true
            }
        }

        test("rotateWebhookSecret invalidates the old secret immediately") {
            testApp {
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Test F", createdByMemberId = ADMIN_UUID).id }
                val createResp =
                    client.post("/test/set/$apiKeyId") {
                        header("X-Member-Id", ADMIN_ID)
                        setBody("https://example.com/hook-f")
                    }
                val created = Json.decodeFromString(WebhookEndpointSetResultDto.serializer(), createResp.bodyAsText())

                val rotateResp = client.post("/test/rotate/$apiKeyId") { header("X-Member-Id", ADMIN_ID) }
                val rotated = Json.decodeFromString(WebhookEndpointSetResultDto.serializer(), rotateResp.bodyAsText())
                rotated.rawSecret.shouldNotBeNull()
                rotated.rawSecret shouldNotBe created.rawSecret

                val sealedNow =
                    transaction {
                        WebhookEndpointTable
                            .selectAll()
                            .where {
                                WebhookEndpointTable.apiKeyId eq apiKeyId
                            }.single()[WebhookEndpointTable.secretSealed]
                    }
                val stillOpensAsOld =
                    runCatching { secretBox.open(sealed = sealedNow, aad = created.endpoint.id) }.getOrNull() == created.rawSecret
                stillOpensAsOld shouldBe false
            }
        }

        // ── Review-fix regressions: remove()/reactivate()/deliveries/test-event/enabled gate ──

        test(
            "removeWebhookUrl succeeds and cleans up delivery rows even when the endpoint has a delivery " +
                "(regression -- V15__webhooks.sql's webhook_delivery.endpoint_id FK has no ON DELETE CASCADE)",
        ) {
            testApp {
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Remove FK Test", createdByMemberId = ADMIN_UUID).id }
                client.post("/test/set/$apiKeyId") {
                    header("X-Member-Id", ADMIN_ID)
                    setBody("https://example.com/remove-fk-test")
                }
                // A loopback target so the delivery attempt below is rejected deterministically by
                // the SSRF guard rather than needing a real network peer -- see
                // WebhookDeliveryPollerTest for the full rationale of this idiom. Written DIRECTLY
                // (bypassing setWebhookUrl's own URL validation) precisely to produce the "endpoint
                // has at least one webhook_delivery row" precondition this regression needs.
                val endpointId = transaction { requireNotNull(WebhookEndpointStore.getByApiKeyId(apiKeyId)).id }
                transaction {
                    WebhookDeliveryQueue.insert(
                        endpointId = endpointId,
                        eventId = Uuid.random(),
                        eventType = WebhookEventType.MEMBER_CREATED,
                        entityId = Uuid.random(),
                        occurredAt = WebhookDeliveryQueue.nowLocalDateTime(),
                        payload = "{}",
                        now = WebhookDeliveryQueue.nowLocalDateTime(),
                    )
                }
                (WebhookDeliveryQueue.countByEndpoint(endpointId) >= 1) shouldBe true

                val removeResp = client.post("/test/remove/$apiKeyId") { header("X-Member-Id", ADMIN_ID) }
                removeResp.status shouldBe HttpStatusCode.OK

                transaction { WebhookEndpointStore.getByApiKeyId(apiKeyId) }.shouldBeNull()
                WebhookDeliveryQueue.countByEndpoint(endpointId) shouldBe 0L
            }
        }

        test("removeWebhookUrl on a non-existent endpoint is a silent no-op, not a NotFoundException") {
            testApp {
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Remove Noop Test", createdByMemberId = ADMIN_UUID).id }
                client.post("/test/remove/$apiKeyId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
            }
        }

        test("reactivateWebhookEndpoint flips a deactivated endpoint back to active, clearing the deactivation reason") {
            testApp {
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Reactivate Test", createdByMemberId = ADMIN_UUID).id }
                client.post("/test/set/$apiKeyId") {
                    header("X-Member-Id", ADMIN_ID)
                    setBody("https://example.com/reactivate-test")
                }
                WebhookEndpointDeactivation.deactivate(
                    apiKeyId = apiKeyId,
                    reason = WebhookDeactivationReason.MANUAL,
                    deactivatedByMemberId = ADMIN_UUID,
                )

                val reactivateResp = client.post("/test/reactivate/$apiKeyId") { header("X-Member-Id", ADMIN_ID) }
                reactivateResp.status shouldBe HttpStatusCode.OK
                val reactivated = Json.decodeFromString(WebhookEndpointDto.serializer(), reactivateResp.bodyAsText())
                reactivated.active shouldBe true
                reactivated.deactivationReason.shouldBeNull()
            }
        }

        test("reactivateWebhookEndpoint on an already-active endpoint throws NotFoundException (nothing to reactivate)") {
            testApp {
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Reactivate Noop Test", createdByMemberId = ADMIN_UUID).id }
                client.post("/test/set/$apiKeyId") {
                    header("X-Member-Id", ADMIN_ID)
                    setBody("https://example.com/reactivate-noop")
                }
                client.post("/test/reactivate/$apiKeyId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("listWebhookDeliveries coerces limit above MAX_DELIVERY_LOG_PAGE_SIZE down to 100, negative offset up to 0") {
            testApp {
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Deliveries Page Test", createdByMemberId = ADMIN_UUID).id }
                client.post("/test/set/$apiKeyId") {
                    header("X-Member-Id", ADMIN_ID)
                    setBody("https://example.com/deliveries-page")
                }
                val endpointId = transaction { requireNotNull(WebhookEndpointStore.getByApiKeyId(apiKeyId)).id }
                repeat(3) {
                    transaction {
                        WebhookDeliveryQueue.insert(
                            endpointId = endpointId,
                            eventId = Uuid.random(),
                            eventType = WebhookEventType.MEMBER_CREATED,
                            entityId = Uuid.random(),
                            occurredAt = WebhookDeliveryQueue.nowLocalDateTime(),
                            payload = "{}",
                            now = WebhookDeliveryQueue.nowLocalDateTime(),
                        )
                    }
                }

                val resp =
                    client.get("/test/deliveries/$apiKeyId?limit=99999&offset=-5") { header("X-Member-Id", ADMIN_ID) }
                resp.status shouldBe HttpStatusCode.OK
                val page = Json.decodeFromString(WebhookDeliveryPageDto.serializer(), resp.bodyAsText())
                page.limit shouldBe 100
                page.offset shouldBe 0
                page.totalCount shouldBe 3
                page.items.size shouldBe 3
            }
        }

        test("listWebhookDeliveries on a non-existent endpoint throws NotFoundException") {
            testApp {
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Deliveries Missing Test", createdByMemberId = ADMIN_UUID).id }
                client.get("/test/deliveries/$apiKeyId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("sendWebhookTestEvent on a deactivated endpoint throws ConflictException, never attempts delivery") {
            testApp {
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Test-Event Inactive Test", createdByMemberId = ADMIN_UUID).id }
                client.post("/test/set/$apiKeyId") {
                    header("X-Member-Id", ADMIN_ID)
                    setBody("https://example.com/test-event-inactive")
                }
                WebhookEndpointDeactivation.deactivate(
                    apiKeyId = apiKeyId,
                    reason = WebhookDeactivationReason.MANUAL,
                    deactivatedByMemberId = ADMIN_UUID,
                )
                client.post("/test/test-event/$apiKeyId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("sendWebhookTestEvent on a non-existent endpoint throws NotFoundException") {
            testApp {
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Test-Event Missing Test", createdByMemberId = ADMIN_UUID).id }
                client.post("/test/test-event/$apiKeyId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test(
            "setWebhookUrl/rotateWebhookSecret/reactivateWebhookEndpoint/sendWebhookTestEvent all throw ConflictException " +
                "when WebhookConfig.enabled is false, even though the encryption key IS configured (review fix -- see " +
                "WebhookConfig KDoc \"Review fix\")",
        ) {
            testApplication {
                val disabledConfig =
                    WebhookConfig(
                        enabled = false,
                        allowInsecureHttp = false,
                        pollIntervalSeconds = 10,
                        maxDeliveriesPerTick = 50,
                        maxConcurrentDeliveries = 4,
                        retentionDays = 30,
                        secretEncryptionKey = null,
                    )
                application {
                    install(StatusPages) { installWebhookExceptionHandlers() }
                    routing {
                        registerWebhookTestRoutes(
                            config = disabledConfig,
                            secretBox = secretBox,
                            configureLimiter = generousLimiter(),
                            rotateLimiter = generousLimiter(),
                            testLimiter = generousLimiter(),
                            deliveryLogLimiter = generousLimiter(),
                        )
                    }
                }
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Disabled Gate Test", createdByMemberId = ADMIN_UUID).id }

                client
                    .post("/test/set/$apiKeyId") {
                        header("X-Member-Id", ADMIN_ID)
                        setBody("https://example.com/disabled-gate")
                    }.status shouldBe HttpStatusCode.Conflict
                client.post("/test/rotate/$apiKeyId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.Conflict
                client.post("/test/reactivate/$apiKeyId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.Conflict
                client.post("/test/test-event/$apiKeyId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.Conflict

                // list/remove stay reachable regardless of `enabled` -- see removeWebhookUrl's own KDoc.
                client.get("/test/list") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                client.post("/test/remove/$apiKeyId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
            }
        }

        // ── S10 regression: ApiKeyService cascades ──────────────────────────────────────

        test("S10: ApiKeyService.reissueApiKey migrates an existing webhook endpoint to the new key id, secret unchanged, still active") {
            testApplication {
                application {
                    install(StatusPages) { installWebhookExceptionHandlers() }
                    routing {
                        registerWebhookTestRoutes(
                            config = config,
                            secretBox = secretBox,
                            configureLimiter = generousLimiter(),
                            rotateLimiter = generousLimiter(),
                            testLimiter = generousLimiter(),
                            deliveryLogLimiter = generousLimiter(),
                        )
                        registerApiKeyReissueTestRoute(generousLimiter())
                    }
                }
                val apiKeyIssue = transaction { ApiKeyStore.issue(label = "WH Reissue Test", createdByMemberId = ADMIN_UUID) }
                val setResp =
                    client.post("/test/set/${apiKeyIssue.id}") {
                        header("X-Member-Id", ADMIN_ID)
                        setBody("https://example.com/reissue-hook")
                    }
                val created = Json.decodeFromString(WebhookEndpointSetResultDto.serializer(), setResp.bodyAsText())

                val reissueResp = client.post("/test/reissue-only/${apiKeyIssue.id}") { header("X-Member-Id", ADMIN_ID) }
                reissueResp.status shouldBe HttpStatusCode.OK
                val newKeyId = Uuid.parse(reissueResp.bodyAsText())

                val migrated =
                    transaction {
                        network.lapis.cloud.server.webhook.WebhookEndpointStore
                            .getByApiKeyId(newKeyId)
                    }
                migrated.shouldNotBeNull()
                migrated.active shouldBe true
                migrated.revealSecret(secretBox) shouldBe created.rawSecret
                transaction {
                    network.lapis.cloud.server.webhook.WebhookEndpointStore
                        .getByApiKeyId(apiKeyIssue.id)
                }.shouldBeNull()
            }
        }

        test(
            "MAJOR review fix: reactivateWebhookEndpoint throws ConflictException (never re-activates) when the " +
                "underlying API key was revoked -- closes the KEY_REVOKED-cascade bypass",
        ) {
            testApp {
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Revoked Reactivate Test", createdByMemberId = ADMIN_UUID).id }
                client.post("/test/set/$apiKeyId") {
                    header("X-Member-Id", ADMIN_ID)
                    setBody("https://example.com/revoked-reactivate")
                }
                // Same cascade ApiKeyService.revokeApiKey drives: revoke the key, then deactivate its
                // endpoint with KEY_REVOKED (the endpoint the test below tries to reactivate).
                transaction { ApiKeyStore.revoke(id = apiKeyId, revokedByMemberId = ADMIN_UUID) }
                WebhookEndpointDeactivation.deactivate(
                    apiKeyId = apiKeyId,
                    reason = WebhookDeactivationReason.KEY_REVOKED,
                    deactivatedByMemberId = ADMIN_UUID,
                )

                client.post("/test/reactivate/$apiKeyId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.Conflict

                // The endpoint MUST stay inactive -- a single RPC call must never be able to undo the cascade.
                transaction { WebhookEndpointStore.getByApiKeyId(apiKeyId) }!!.active shouldBe false
            }
        }

        test(
            "MAJOR review fix: setWebhookUrl (both create and update) throws ConflictException when the " +
                "underlying API key was revoked",
        ) {
            testApp {
                // create path -- key revoked before any endpoint ever existed for it.
                val freshKeyId = transaction { ApiKeyStore.issue(label = "WH Revoked Create Test", createdByMemberId = ADMIN_UUID).id }
                transaction { ApiKeyStore.revoke(id = freshKeyId, revokedByMemberId = ADMIN_UUID) }
                client
                    .post("/test/set/$freshKeyId") {
                        header("X-Member-Id", ADMIN_ID)
                        setBody("https://example.com/revoked-create")
                    }.status shouldBe HttpStatusCode.Conflict
                transaction { WebhookEndpointStore.getByApiKeyId(freshKeyId) }.shouldBeNull()

                // update path -- endpoint already existed, key gets revoked afterwards (mirrors the
                // ApiKeyService.revokeApiKey cascade, which deactivates but does not remove the row).
                val existingKeyId = transaction { ApiKeyStore.issue(label = "WH Revoked Update Test", createdByMemberId = ADMIN_UUID).id }
                client.post("/test/set/$existingKeyId") {
                    header("X-Member-Id", ADMIN_ID)
                    setBody("https://example.com/revoked-update-original")
                }
                transaction { ApiKeyStore.revoke(id = existingKeyId, revokedByMemberId = ADMIN_UUID) }
                client
                    .post("/test/set/$existingKeyId") {
                        header("X-Member-Id", ADMIN_ID)
                        setBody("https://example.com/revoked-update-attempt")
                    }.status shouldBe HttpStatusCode.Conflict
                val untouched = transaction { WebhookEndpointStore.getByApiKeyId(existingKeyId) }
                untouched!!.url shouldBe "https://example.com/revoked-update-original"
            }
        }

        test(
            "F7 (Security-Audit-Fund, Runde 1, 2026-09-02): setWebhookUrl/reactivateWebhookEndpoint both throw " +
                "ConflictException when the underlying API key has EXPIRED, not just when it was explicitly revoked",
        ) {
            testApp {
                // create path -- key already expired before any endpoint ever existed for it.
                val expiredKeyId =
                    transaction {
                        ApiKeyStore
                            .issue(
                                label = "WH Expired Create Test",
                                createdByMemberId = ADMIN_UUID,
                                expiresAt = LocalDateTime(2020, 1, 1, 0, 0),
                            ).id
                    }
                client
                    .post("/test/set/$expiredKeyId") {
                        header("X-Member-Id", ADMIN_ID)
                        setBody("https://example.com/expired-create")
                    }.status shouldBe HttpStatusCode.Conflict
                transaction { WebhookEndpointStore.getByApiKeyId(expiredKeyId) }.shouldBeNull()

                // reactivate path -- endpoint existed and was deactivated (e.g. MANUAL) while the
                // key was still valid, key expires afterwards, an ADMIN then tries to bring the
                // endpoint back without noticing the underlying key can no longer authenticate.
                val laterExpiringKeyId =
                    transaction {
                        ApiKeyStore
                            .issue(
                                label = "WH Expired Reactivate Test",
                                createdByMemberId = ADMIN_UUID,
                                expiresAt = LocalDateTime(2020, 1, 1, 0, 0),
                            ).id
                    }
                // Endpoint created directly (bypassing setWebhookUrl's own now-expired-key guard,
                // which would otherwise refuse this precondition setup too) then deactivated, mirroring
                // an endpoint that was configured back when the key was still valid.
                transaction {
                    WebhookEndpointStore.create(
                        apiKeyId = laterExpiringKeyId,
                        url = "https://example.com/expired-reactivate",
                        createdByMemberId = ADMIN_UUID,
                        secretBox = secretBox,
                    )
                }
                WebhookEndpointDeactivation.deactivate(
                    apiKeyId = laterExpiringKeyId,
                    reason = WebhookDeactivationReason.MANUAL,
                    deactivatedByMemberId = ADMIN_UUID,
                )
                client
                    .post("/test/reactivate/$laterExpiringKeyId") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.Conflict
                transaction { WebhookEndpointStore.getByApiKeyId(laterExpiringKeyId) }!!.active shouldBe false
            }
        }

        test("KEY_REVOKED cascade: WebhookEndpointDeactivation.deactivate deactivates the endpoint and abandons its PENDING deliveries") {
            testApp {
                val apiKeyId = transaction { ApiKeyStore.issue(label = "WH Revoke Cascade Test", createdByMemberId = ADMIN_UUID).id }
                client.post("/test/set/$apiKeyId") {
                    header("X-Member-Id", ADMIN_ID)
                    setBody("https://example.com/revoke-cascade")
                }

                val result =
                    WebhookEndpointDeactivation.deactivate(
                        apiKeyId = apiKeyId,
                        reason = WebhookDeactivationReason.KEY_REVOKED,
                        deactivatedByMemberId = ADMIN_UUID,
                    )
                result.shouldNotBeNull()
                result.endpoint.active shouldBe false

                val listResp = client.get("/test/list") { header("X-Member-Id", ADMIN_ID) }
                val endpoints =
                    Json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(WebhookEndpointDto.serializer()),
                        listResp.bodyAsText(),
                    )
                endpoints.single { it.apiKeyId == apiKeyId.toString() }.active shouldBe false
            }
        }
    })

/** Real `ApiKeyService.reissueApiKey` call, response body = the new key's raw UUID string (not the full DTO -- keeps this test route trivial). */
private fun Route.registerApiKeyReissueTestRoute(reissueLimiter: FederationInboxRateLimiter) {
    post("/test/reissue-only/{id}") {
        val svc =
            ApiKeyService(
                call = call,
                issueRateLimiter = reissueLimiter,
                revokeRateLimiter = reissueLimiter,
                reissueRateLimiter = reissueLimiter,
            )
        val result = svc.reissueApiKey(id = call.parameters["id"]!!)
        call.respondText(result.apiKey.id)
    }
}

private fun StatusPagesConfig.installWebhookExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<WebhookUrlNotHttpsException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.fromValue(422)) }
    exception<WebhookUrlMalformedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.fromValue(423)) }
    exception<WebhookUrlNotPubliclyRoutableException> {
        call,
        cause,
        ->
        call.respondText(cause.message, status = HttpStatusCode.fromValue(423))
    }
    exception<WebhookUrlTooLongException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.fromValue(424)) }
}

private fun Route.registerWebhookTestRoutes(
    config: WebhookConfig,
    secretBox: SecretBox,
    configureLimiter: FederationInboxRateLimiter,
    rotateLimiter: FederationInboxRateLimiter,
    testLimiter: FederationInboxRateLimiter,
    deliveryLogLimiter: FederationInboxRateLimiter,
) {
    fun svc(call: io.ktor.server.application.ApplicationCall) =
        WebhookService(
            call = call,
            config = config,
            secretBox = secretBox,
            configureRateLimiter = configureLimiter,
            secretRotateRateLimiter = rotateLimiter,
            testRateLimiter = testLimiter,
            deliveryLogRateLimiter = deliveryLogLimiter,
        )
    get("/test/list") {
        val keys = svc(call).listWebhookEndpoints()
        call.respondText(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(WebhookEndpointDto.serializer()), keys))
    }
    post("/test/set/{apiKeyId}") {
        val url = call.receiveText()
        val result = svc(call).setWebhookUrl(apiKeyId = call.parameters["apiKeyId"]!!, url = url)
        call.respondText(Json.encodeToString(WebhookEndpointSetResultDto.serializer(), result))
    }
    post("/test/rotate/{apiKeyId}") {
        val result = svc(call).rotateWebhookSecret(apiKeyId = call.parameters["apiKeyId"]!!)
        call.respondText(Json.encodeToString(WebhookEndpointSetResultDto.serializer(), result))
    }
    post("/test/remove/{apiKeyId}") {
        svc(call).removeWebhookUrl(apiKeyId = call.parameters["apiKeyId"]!!)
        call.respondText("ok")
    }
    post("/test/reactivate/{apiKeyId}") {
        val result = svc(call).reactivateWebhookEndpoint(apiKeyId = call.parameters["apiKeyId"]!!)
        call.respondText(Json.encodeToString(WebhookEndpointDto.serializer(), result))
    }
    post("/test/test-event/{apiKeyId}") {
        val result = svc(call).sendWebhookTestEvent(apiKeyId = call.parameters["apiKeyId"]!!)
        call.respondText(Json.encodeToString(WebhookDeliveryDto.serializer(), result))
    }
    get("/test/deliveries/{apiKeyId}") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
        val result = svc(call).listWebhookDeliveries(apiKeyId = call.parameters["apiKeyId"]!!, limit = limit, offset = offset)
        call.respondText(Json.encodeToString(WebhookDeliveryPageDto.serializer(), result))
    }
}
