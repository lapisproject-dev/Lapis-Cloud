package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.TrustAnchorPoolMemberTable
import network.lapis.cloud.server.db.generated.TrustAnchorSigningKeyTable
import network.lapis.cloud.server.federation.TrustAnchorChainVerification
import network.lapis.cloud.server.federation.TrustAnchorConfig
import network.lapis.cloud.server.federation.TrustAnchorKeyMaterial
import network.lapis.cloud.server.federation.TrustAnchorPoolStore
import network.lapis.cloud.server.federation.TrustAnchorSigningKeyProvisioner
import network.lapis.cloud.server.federation.TrustAnchorSigningKeyStore
import network.lapis.cloud.shared.domain.TrustAnchorSigningKeyStatus
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.URLEncoder
import kotlin.uuid.Uuid

/**
 * Exercises `GET /.well-known/openid-federation` and `GET /federation/trust-anchor/fetch` end to
 * end through the real routes -- the "opt-in via non-empty pool" gate, the published Entity
 * Configuration/Subordinate Statement actually verifying via [TrustAnchorChainVerification] (the
 * same code a real remote verifier would run), pool-member removal taking effect immediately, and
 * key-rollover/revocation being reflected in the published `jwks` (grace period for `RETIRED`,
 * immediate exclusion for `REVOKED`) -- the publishing-side mirror of
 * [network.lapis.cloud.server.federation.TrustAnchorChainVerificationTest]'s consuming-side
 * coverage.
 */
class TrustAnchorRoutesTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TrustAnchorSigningKeyProvisioner.ensureProvisioned()
        }

        test("GET /.well-known/openid-federation is 404 while the pool is empty (opt-in gate)") {
            transaction {
                TrustAnchorPoolStore.listAll().forEach {
                    TrustAnchorPoolStore.remove(
                        it[TrustAnchorPoolMemberTable.homeServerUri],
                    )
                }
            }
            testApplication {
                routing { registerTrustAnchorRoutes() }
                val response = client.get("/.well-known/openid-federation")
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("GET /federation/trust-anchor/fetch?sub=... is 404 while the pool is empty") {
            transaction {
                TrustAnchorPoolStore.listAll().forEach {
                    TrustAnchorPoolStore.remove(
                        it[TrustAnchorPoolMemberTable.homeServerUri],
                    )
                }
            }
            testApplication {
                routing { registerTrustAnchorRoutes() }
                val response = client.get("/federation/trust-anchor/fetch?sub=https://home.example")
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test(
            "once a pool member exists, the Entity Configuration + Subordinate Statement are both published " +
                "and independently verify via TrustAnchorChainVerification (the same code a real verifier runs)",
        ) {
            val homeServerUri = "https://home-${Uuid.random()}.example"
            transaction { TrustAnchorPoolStore.insert(homeServerUri, LocalDateTime(2026, 1, 1, 0, 0)) }

            testApplication {
                routing { registerTrustAnchorRoutes() }

                val ecResponse = client.get("/.well-known/openid-federation")
                ecResponse.status shouldBe HttpStatusCode.OK
                val ecCompact = ecResponse.bodyAsText()
                val verifiedEc = TrustAnchorChainVerification.verifyEntityConfiguration(ecCompact, TrustAnchorConfig.entityUri)
                requireNotNull(verifiedEc)
                verifiedEc.fetchEndpoint shouldBe TrustAnchorConfig.fetchEndpointUri

                val subEncoded = URLEncoder.encode(homeServerUri, "UTF-8")
                val stResponse = client.get("/federation/trust-anchor/fetch?sub=$subEncoded")
                stResponse.status shouldBe HttpStatusCode.OK
                val stCompact = stResponse.bodyAsText()

                val valid =
                    TrustAnchorChainVerification.verifySubordinateStatement(
                        stCompact,
                        TrustAnchorConfig.entityUri,
                        homeServerUri,
                        verifiedEc.jwksJson,
                    )
                valid shouldBe true
            }

            transaction { TrustAnchorPoolStore.remove(homeServerUri) }
        }

        test("a home server NOT in the pool gets 404 from the fetch endpoint") {
            val poolMemberUri = "https://home-${Uuid.random()}.example"
            transaction { TrustAnchorPoolStore.insert(poolMemberUri, LocalDateTime(2026, 1, 1, 0, 0)) }

            testApplication {
                routing { registerTrustAnchorRoutes() }
                val notAMemberUri = URLEncoder.encode("https://not-a-pool-member-${Uuid.random()}.example", "UTF-8")
                val response = client.get("/federation/trust-anchor/fetch?sub=$notAMemberUri")
                response.status shouldBe HttpStatusCode.NotFound
            }

            transaction { TrustAnchorPoolStore.remove(poolMemberUri) }
        }

        test("removing a pool member makes the next fetch 404 (the actual 'remove from pool' mechanism)") {
            val homeServerUri = "https://home-${Uuid.random()}.example"
            transaction { TrustAnchorPoolStore.insert(homeServerUri, LocalDateTime(2026, 1, 1, 0, 0)) }

            testApplication {
                routing { registerTrustAnchorRoutes() }
                val subEncoded = URLEncoder.encode(homeServerUri, "UTF-8")
                client.get("/federation/trust-anchor/fetch?sub=$subEncoded").status shouldBe HttpStatusCode.OK

                transaction { TrustAnchorPoolStore.remove(homeServerUri) }

                client.get("/federation/trust-anchor/fetch?sub=$subEncoded").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("missing 'sub' query parameter is 400") {
            val homeServerUri = "https://home-${Uuid.random()}.example"
            transaction { TrustAnchorPoolStore.insert(homeServerUri, LocalDateTime(2026, 1, 1, 0, 0)) }

            testApplication {
                routing { registerTrustAnchorRoutes() }
                client.get("/federation/trust-anchor/fetch").status shouldBe HttpStatusCode.BadRequest
            }

            transaction { TrustAnchorPoolStore.remove(homeServerUri) }
        }

        test(
            "key rollover: after rotation, a Subordinate Statement still verifies (new ACTIVE key signs, " +
                "old RETIRED key stays published) -- and after revoking the old key, its jwks entry disappears",
        ) {
            val homeServerUri = "https://home-${Uuid.random()}.example"
            transaction { TrustAnchorPoolStore.insert(homeServerUri, LocalDateTime(2026, 1, 1, 0, 0)) }
            val originalActiveKid = transaction { TrustAnchorSigningKeyStore.findActive()!![TrustAnchorSigningKeyTable.kid] }

            testApplication {
                routing { registerTrustAnchorRoutes() }
                val subEncoded = URLEncoder.encode(homeServerUri, "UTF-8")

                // Statement signed by the ORIGINAL key, fetched before rotation.
                val preRotationStatement = client.get("/federation/trust-anchor/fetch?sub=$subEncoded").bodyAsText()

                // Rotate -- mirrors what TrustAnchorService.rotateSigningKey does, called directly
                // here (route-level test, no RPC session plumbing needed).
                val now = LocalDateTime(2026, 1, 2, 0, 0)
                transaction {
                    val active = TrustAnchorSigningKeyStore.findActive(forUpdate = true)!!
                    TrustAnchorSigningKeyStore.retire(active[TrustAnchorSigningKeyTable.id], now)
                    TrustAnchorKeyMaterial.insertNewKey(status = TrustAnchorSigningKeyStatus.ACTIVE, now = now)
                }

                val ecCompact = client.get("/.well-known/openid-federation").bodyAsText()
                val verifiedEc = TrustAnchorChainVerification.verifyEntityConfiguration(ecCompact, TrustAnchorConfig.entityUri)
                requireNotNull(verifiedEc)

                // Grace period: the statement signed BEFORE rotation (by the now-RETIRED key) still verifies.
                TrustAnchorChainVerification.verifySubordinateStatement(
                    preRotationStatement,
                    TrustAnchorConfig.entityUri,
                    homeServerUri,
                    verifiedEc.jwksJson,
                ) shouldBe true

                // A freshly-fetched statement is signed by the NEW active key and also verifies.
                val postRotationStatement = client.get("/federation/trust-anchor/fetch?sub=$subEncoded").bodyAsText()
                TrustAnchorChainVerification.verifySubordinateStatement(
                    postRotationStatement,
                    TrustAnchorConfig.entityUri,
                    homeServerUri,
                    verifiedEc.jwksJson,
                ) shouldBe true

                // Compromise response: revoke the original (now RETIRED) key.
                transaction {
                    TrustAnchorSigningKeyStore.revokeRow(
                        TrustAnchorSigningKeyStore.findByKid(originalActiveKid)!![TrustAnchorSigningKeyTable.id],
                        now,
                    )
                }

                val ecAfterRevokeCompact = client.get("/.well-known/openid-federation").bodyAsText()
                val verifiedEcAfterRevoke =
                    TrustAnchorChainVerification.verifyEntityConfiguration(
                        ecAfterRevokeCompact,
                        TrustAnchorConfig.entityUri,
                    )
                requireNotNull(verifiedEcAfterRevoke)

                // The statement signed by the now-REVOKED key no longer verifies against the freshly-fetched jwks.
                TrustAnchorChainVerification.verifySubordinateStatement(
                    preRotationStatement,
                    TrustAnchorConfig.entityUri,
                    homeServerUri,
                    verifiedEcAfterRevoke.jwksJson,
                ) shouldBe false
            }

            transaction { TrustAnchorPoolStore.remove(homeServerUri) }
        }
    })
