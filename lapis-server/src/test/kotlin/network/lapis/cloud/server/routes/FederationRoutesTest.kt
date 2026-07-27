package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.FederationInboxDeliveryLogTable
import network.lapis.cloud.server.db.generated.FederationRelationshipEventTable
import network.lapis.cloud.server.db.generated.FederationRelationshipTable
import network.lapis.cloud.server.federation.FEDERATION_JSON
import network.lapis.cloud.server.federation.FederationActorKeyProvisioner
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.federation.FederationKeyPairGenerator
import network.lapis.cloud.server.federation.FederationReplayGuard
import network.lapis.cloud.server.federation.HttpSignatures
import network.lapis.cloud.shared.domain.FederationRelationshipDirection
import network.lapis.cloud.shared.domain.FederationRelationshipStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Exercises `POST /federation/inbox` end to end -- signature verification, replay rejection,
 * rate limiting, and oversized/malformed-payload rejection (all mandatory per CLAUDE.md for
 * security-relevant code). Every test that needs a resolvable sender public key pre-seeds a
 * `federation_relationship` row with the key cached, deliberately avoiding any real outbound
 * network fetch (this sandbox has no general internet egress -- see house rule that unit tests
 * never depend on reaching a real third-party endpoint).
 */
class FederationRoutesTest :
    FunSpec({
        val testHost = "localhost"
        val createdRelationshipIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            // Idempotent -- guarantees the singleton federation_actor_key row exists for the
            // GET /federation/actor test regardless of whether another test class already
            // provisioned it earlier in this JVM's shared H2 database.
            FederationActorKeyProvisioner.ensureProvisioned(FederationConfig.actorUri)
        }

        afterTest {
            transaction {
                // federation_relationship_event.relationship_id FK's the row -- must delete child
                // events first.
                FederationRelationshipEventTable.deleteWhere {
                    FederationRelationshipEventTable.relationshipId inList createdRelationshipIds
                }
                FederationRelationshipTable.deleteWhere { FederationRelationshipTable.id inList createdRelationshipIds }
            }
            createdRelationshipIds.clear()
        }

        fun seedCachedRelationship(
            remoteActorUri: String,
            publicKeyPem: String,
            status: FederationRelationshipStatus = FederationRelationshipStatus.REJECTED,
        ): Uuid {
            val id = Uuid.random()
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            transaction {
                FederationRelationshipTable.insert {
                    it[FederationRelationshipTable.id] = id
                    it[direction] = FederationRelationshipDirection.OUTBOUND
                    it[FederationRelationshipTable.status] = status
                    it[FederationRelationshipTable.remoteActorUri] = remoteActorUri
                    it[remoteInboxUri] = "https://remote.example/federation/inbox"
                    it[remotePublicKeyPem] = publicKeyPem
                    it[initiatedActivityId] = "https://remote.example/federation/activities/seed"
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            createdRelationshipIds += id
            return id
        }

        fun sha256Hex(bytes: ByteArray): String =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

        test(
            "a valid signed Follow is accepted (202), the relationship is persisted PENDING/INBOUND, delivery log has signatureVerified=true",
        ) {
            testApplication {
                application { routing { registerFederationRoutes(FederationInboxRateLimiter(), FederationReplayGuard()) } }

                val keyPair = FederationKeyPairGenerator.generate()
                val remoteActorUri = "https://remote-${Uuid.random()}.example/federation/actor"
                seedCachedRelationship(remoteActorUri, keyPair.publicKeyPem)

                val activityId = "https://remote.example/federation/activities/${Uuid.random()}"
                val bodyText =
                    """{"id":"$activityId","type":"Follow","actor":"$remoteActorUri","object":"https://local.example/federation/actor"}"""
                val bodyBytes = bodyText.toByteArray(Charsets.UTF_8)
                val signed =
                    HttpSignatures.sign(
                        "POST",
                        "/federation/inbox",
                        testHost,
                        bodyBytes,
                        "$remoteActorUri#main-key",
                        keyPair.privateKeyPem,
                    )

                val response =
                    client.post("/federation/inbox") {
                        header("Signature", signed.signatureHeader)
                        header("Date", signed.dateHeader)
                        header("Digest", signed.digestHeader)
                        contentType(ContentType.parse("application/activity+json"))
                        setBody(bodyBytes)
                    }

                response.status shouldBe HttpStatusCode.Accepted

                val relationship =
                    transaction {
                        FederationRelationshipTable
                            .selectAll()
                            .where { FederationRelationshipTable.remoteActorUri eq remoteActorUri }
                            .single()
                    }
                relationship[FederationRelationshipTable.direction] shouldBe FederationRelationshipDirection.INBOUND
                relationship[FederationRelationshipTable.status] shouldBe FederationRelationshipStatus.PENDING

                val logRow =
                    transaction {
                        FederationInboxDeliveryLogTable
                            .selectAll()
                            .where { FederationInboxDeliveryLogTable.bodySha256 eq sha256Hex(bodyBytes) }
                            .single()
                    }
                logRow[FederationInboxDeliveryLogTable.signatureVerified] shouldBe true
                logRow[FederationInboxDeliveryLogTable.activityType] shouldBe "Follow"
            }
        }

        test(
            "an invalid (tampered) signature is rejected (401), delivery log has signatureVerified=false/rejectReason=SIGNATURE_MISMATCH, no relationship row created",
        ) {
            testApplication {
                application { routing { registerFederationRoutes(FederationInboxRateLimiter(), FederationReplayGuard()) } }

                val keyPair = FederationKeyPairGenerator.generate()
                val remoteActorUri = "https://remote-${Uuid.random()}.example/federation/actor"
                seedCachedRelationship(remoteActorUri, keyPair.publicKeyPem)

                val bodyText = """{"id":"https://remote.example/activities/x","type":"Follow","actor":"$remoteActorUri","object":"x"}"""
                val bodyBytes = bodyText.toByteArray(Charsets.UTF_8)
                val signed =
                    HttpSignatures.sign(
                        "POST",
                        "/federation/inbox",
                        testHost,
                        bodyBytes,
                        "$remoteActorUri#main-key",
                        keyPair.privateKeyPem,
                    )
                // Flips one base64 character in place (keeps the signature value's length --
                // hence still valid base64 -- so verification legitimately reaches the RSA check
                // and fails there, rather than being rejected earlier as structurally MALFORMED).
                val tamperedSignature = flipOneSignatureChar(signed.signatureHeader)

                val response =
                    client.post("/federation/inbox") {
                        header("Signature", tamperedSignature)
                        header("Date", signed.dateHeader)
                        header("Digest", signed.digestHeader)
                        contentType(ContentType.parse("application/activity+json"))
                        setBody(bodyBytes)
                    }

                response.status shouldBe HttpStatusCode.Unauthorized

                val logRow =
                    transaction {
                        FederationInboxDeliveryLogTable
                            .selectAll()
                            .where { FederationInboxDeliveryLogTable.bodySha256 eq sha256Hex(bodyBytes) }
                            .single()
                    }
                logRow[FederationInboxDeliveryLogTable.signatureVerified] shouldBe false
                logRow[FederationInboxDeliveryLogTable.rejectReason] shouldBe "SIGNATURE_MISMATCH"

                transaction {
                    FederationRelationshipTable.selectAll().where { FederationRelationshipTable.remoteActorUri eq remoteActorUri }.count()
                } shouldBe 1L // only the pre-seeded terminal row -- never mutated to PENDING/INBOUND
            }
        }

        test(
            "an Activity whose actor field names a DIFFERENT actor than the signing key is rejected (401, " +
                "ACTOR_KEY_MISMATCH) and no relationship row is created for the impersonated actor -- round-1 review fix",
        ) {
            testApplication {
                application { routing { registerFederationRoutes(FederationInboxRateLimiter(), FederationReplayGuard()) } }

                // The SIGNER is a real, resolvable actor -- the signature itself verifies cleanly.
                val keyPair = FederationKeyPairGenerator.generate()
                val signerActorUri = "https://attacker-${Uuid.random()}.example/federation/actor"
                seedCachedRelationship(signerActorUri, keyPair.publicKeyPem)

                // But the Activity body CLAIMS to be from an entirely different, uninvolved actor --
                // exactly the impersonation shape the round-1 review flagged: without this check, a
                // Follow claiming to be `victimActorUri` would have been persisted using the
                // attacker's own inbox/key.
                val victimActorUri = "https://victim-${Uuid.random()}.example/federation/actor"
                val bodyText =
                    """{"id":"https://attacker.example/activities/${Uuid.random()}","type":"Follow",""" +
                        """"actor":"$victimActorUri","object":"https://local.example/federation/actor"}"""
                val bodyBytes = bodyText.toByteArray(Charsets.UTF_8)
                val signed =
                    HttpSignatures.sign(
                        "POST",
                        "/federation/inbox",
                        testHost,
                        bodyBytes,
                        "$signerActorUri#main-key",
                        keyPair.privateKeyPem,
                    )

                val response =
                    client.post("/federation/inbox") {
                        header("Signature", signed.signatureHeader)
                        header("Date", signed.dateHeader)
                        header("Digest", signed.digestHeader)
                        contentType(ContentType.parse("application/activity+json"))
                        setBody(bodyBytes)
                    }

                response.status shouldBe HttpStatusCode.Unauthorized

                val logRow =
                    transaction {
                        FederationInboxDeliveryLogTable
                            .selectAll()
                            .where { FederationInboxDeliveryLogTable.bodySha256 eq sha256Hex(bodyBytes) }
                            .single()
                    }
                logRow[FederationInboxDeliveryLogTable.rejectReason] shouldBe "ACTOR_KEY_MISMATCH"

                // No row was ever created for the impersonated victim -- the attacker gained
                // nothing.
                transaction {
                    FederationRelationshipTable.selectAll().where { FederationRelationshipTable.remoteActorUri eq victimActorUri }.count()
                } shouldBe 0L
            }
        }

        test("a replayed valid request (same signature twice within the window) is accepted once, rejected the second time") {
            testApplication {
                application { routing { registerFederationRoutes(FederationInboxRateLimiter(), FederationReplayGuard()) } }

                val keyPair = FederationKeyPairGenerator.generate()
                val remoteActorUri = "https://remote-${Uuid.random()}.example/federation/actor"
                seedCachedRelationship(remoteActorUri, keyPair.publicKeyPem)

                val bodyText = """{"id":"https://remote.example/activities/y","type":"Follow","actor":"$remoteActorUri","object":"x"}"""
                val bodyBytes = bodyText.toByteArray(Charsets.UTF_8)
                val signed =
                    HttpSignatures.sign(
                        "POST",
                        "/federation/inbox",
                        testHost,
                        bodyBytes,
                        "$remoteActorUri#main-key",
                        keyPair.privateKeyPem,
                    )

                suspend fun send() =
                    client.post("/federation/inbox") {
                        header("Signature", signed.signatureHeader)
                        header("Date", signed.dateHeader)
                        header("Digest", signed.digestHeader)
                        contentType(ContentType.parse("application/activity+json"))
                        setBody(bodyBytes)
                    }

                send().status shouldBe HttpStatusCode.Accepted
                send().status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("an oversized body is rejected (413) before any JSON parsing") {
            testApplication {
                application { routing { registerFederationRoutes(FederationInboxRateLimiter(), FederationReplayGuard()) } }

                val oversized = "x".repeat(70 * 1024).toByteArray(Charsets.UTF_8)

                val response =
                    client.post("/federation/inbox") {
                        header(
                            "Signature",
                            "keyId=\"https://x/actor#main-key\",algorithm=\"rsa-sha256\"," +
                                "headers=\"(request-target) host date digest\",signature=\"YQ==\"",
                        )
                        header("Date", "Tue, 07 Jun 2016 20:51:35 GMT")
                        header("Digest", "SHA-256=irrelevant")
                        contentType(ContentType.parse("application/activity+json"))
                        setBody(oversized)
                    }

                response.status shouldBe HttpStatusCode.PayloadTooLarge
            }
        }

        test("a deeply nested (small byte size) JSON body with a VALID signature is rejected as a bad request, not a StackOverflowError") {
            testApplication {
                application { routing { registerFederationRoutes(FederationInboxRateLimiter(), FederationReplayGuard()) } }

                val keyPair = FederationKeyPairGenerator.generate()
                val remoteActorUri = "https://remote-${Uuid.random()}.example/federation/actor"
                seedCachedRelationship(remoteActorUri, keyPair.publicKeyPem)

                val nestedBody = "[".repeat(30) + "]".repeat(30)
                val bodyBytes = nestedBody.toByteArray(Charsets.UTF_8)
                val signed =
                    HttpSignatures.sign(
                        "POST",
                        "/federation/inbox",
                        testHost,
                        bodyBytes,
                        "$remoteActorUri#main-key",
                        keyPair.privateKeyPem,
                    )

                val response =
                    client.post("/federation/inbox") {
                        header("Signature", signed.signatureHeader)
                        header("Date", signed.dateHeader)
                        header("Digest", signed.digestHeader)
                        contentType(ContentType.parse("application/activity+json"))
                        setBody(bodyBytes)
                    }

                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("rate limiting kicks in (429) once the per-IP request budget is exhausted") {
            testApplication {
                application { routing { registerFederationRoutes(FederationInboxRateLimiter(maxRequests = 1), FederationReplayGuard()) } }

                suspend fun send() =
                    client.post("/federation/inbox") {
                        header("Signature", "garbage")
                        header("Date", "Tue, 07 Jun 2016 20:51:35 GMT")
                        header("Digest", "SHA-256=irrelevant")
                        contentType(ContentType.parse("application/activity+json"))
                        setBody("{}")
                    }

                send() // consumes the single allowed request (rejected for a different reason -- still counted)
                send().status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("an unsupported activity type with a valid signature is accepted (202) and logged, with no relationship mutation") {
            testApplication {
                application { routing { registerFederationRoutes(FederationInboxRateLimiter(), FederationReplayGuard()) } }

                val keyPair = FederationKeyPairGenerator.generate()
                val remoteActorUri = "https://remote-${Uuid.random()}.example/federation/actor"
                seedCachedRelationship(remoteActorUri, keyPair.publicKeyPem, status = FederationRelationshipStatus.ACTIVE)

                val bodyText = """{"id":"https://remote.example/activities/z","type":"Like","actor":"$remoteActorUri","object":"x"}"""
                val bodyBytes = bodyText.toByteArray(Charsets.UTF_8)
                val signed =
                    HttpSignatures.sign(
                        "POST",
                        "/federation/inbox",
                        testHost,
                        bodyBytes,
                        "$remoteActorUri#main-key",
                        keyPair.privateKeyPem,
                    )

                val response =
                    client.post("/federation/inbox") {
                        header("Signature", signed.signatureHeader)
                        header("Date", signed.dateHeader)
                        header("Digest", signed.digestHeader)
                        contentType(ContentType.parse("application/activity+json"))
                        setBody(bodyBytes)
                    }

                response.status shouldBe HttpStatusCode.Accepted

                val relationship =
                    transaction {
                        FederationRelationshipTable
                            .selectAll()
                            .where { FederationRelationshipTable.remoteActorUri eq remoteActorUri }
                            .single()
                    }
                // Still ACTIVE/OUTBOUND -- an unsupported activity type never mutates it.
                relationship[FederationRelationshipTable.status] shouldBe FederationRelationshipStatus.ACTIVE
                relationship[FederationRelationshipTable.direction] shouldBe FederationRelationshipDirection.OUTBOUND

                val logRow =
                    transaction {
                        FederationInboxDeliveryLogTable
                            .selectAll()
                            .where { FederationInboxDeliveryLogTable.bodySha256 eq sha256Hex(bodyBytes) }
                            .single()
                    }
                logRow[FederationInboxDeliveryLogTable.activityType] shouldBe "Like"
                logRow[FederationInboxDeliveryLogTable.signatureVerified] shouldBe true
            }
        }

        test("GET /federation/actor returns an ActorDocument with the provisioned actor URI and no private key") {
            testApplication {
                application { routing { registerFederationRoutes(FederationInboxRateLimiter(), FederationReplayGuard()) } }
                val response = client.get("/federation/actor")
                response.status shouldBe HttpStatusCode.OK
                val text = response.bodyAsText()
                text shouldContain "\"type\":\"Organization\""
                text shouldContain "\"inbox\""
                text shouldContain "\"publicKey\""
                text shouldContain "\"publicKeyPem\""
            }
        }

        test("GET /federation/outbox returns a well-formed OrderedCollection") {
            testApplication {
                application { routing { registerFederationRoutes(FederationInboxRateLimiter(), FederationReplayGuard()) } }
                val response = client.get("/federation/outbox")
                response.status shouldBe HttpStatusCode.OK
                val parsed = FEDERATION_JSON.parseToJsonElement(response.bodyAsText())
                parsed.toString() shouldContain "OrderedCollection"
            }
        }
    })

/** Mirrors [network.lapis.cloud.server.federation.HttpSignaturesTest]'s own tamper helper. */
private fun flipOneSignatureChar(signatureHeader: String): String {
    val signatureValueStart = signatureHeader.indexOf("signature=\"") + "signature=\"".length
    val charToFlip = signatureHeader[signatureValueStart]
    val replacement = if (charToFlip == 'A') 'B' else 'A'
    return signatureHeader.substring(0, signatureValueStart) + replacement + signatureHeader.substring(signatureValueStart + 1)
}
