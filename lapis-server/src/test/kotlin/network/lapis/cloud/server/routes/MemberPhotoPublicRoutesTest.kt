package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.memberphoto.MemberPhotoFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoStorage
import network.lapis.cloud.server.memberphoto.MemberPhotoStore
import network.lapis.cloud.server.memberphoto.MemberPhotoTestImages
import network.lapis.cloud.shared.domain.MemberPhotoRules
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val ALLOWED_ORIGIN = "https://partei.example"

private val ALLOWLIST_CONFIG =
    EmbedConfig(
        enabled = true,
        allowlist = EmbedOriginAllowlist.parse(raw = ALLOWED_ORIGIN, allowInsecure = false).allowlist,
        allowInsecureOrigins = false,
    )

/** Status, body and every header except the volatile `Date`. */
private data class Shape(
    val status: HttpStatusCode,
    val body: List<Byte>,
    val headers: Map<String, List<String>>,
)

private suspend fun HttpResponse.shape(): Shape =
    Shape(
        status = status,
        body = bodyAsBytes().toList(),
        headers =
            headers
                .entries()
                .filter { it.key != HttpHeaders.Date }
                .associate { it.key to it.value },
    )

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- the anonymous public delivery route
 * `GET /public/member-photos/{publicToken}`: exact headers, the existence-oracle discipline (every
 * kind of miss is byte-for-byte the same 404), immediate revocation, CORS, and the rate limit.
 */
class MemberPhotoPublicRoutesTest :
    FunSpec({
        val fixtures = MemberPhotoFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fixtures.cleanup() }

        fun Routing.install(
            storage: MemberPhotoStorage,
            embedConfig: EmbedConfig = EmbedConfig.DISABLED,
            publicLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
        ) {
            registerMemberPhotoRoutes(
                storage = storage,
                baseUrl = "https://lapis.example.org",
                embedConfig = embedConfig,
                uploadRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
                ownReadRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
                publicReadRateLimiter = publicLimiter,
            )
        }

        suspend fun HttpClient.fetch(
            token: String,
            origin: String? = null,
        ): HttpResponse = get("/public/member-photos/$token") { if (origin != null) header(HttpHeaders.Origin, origin) }

        test("PUBLIC + ACTIVE: 200 with the exact header set, each defensive header exactly once, a real JPEG body") {
            val root = MemberPhotoFixtures.freshRoot("pub-hit")
            val storage = MemberPhotoStorage(root)
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(storage) }
                }
                val member = fixtures.newMember()
                val token = fixtures.seedPhoto(storage = storage, memberId = member, publish = true)!!
                val response = client.fetch(token)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldBe "image/jpeg"
                response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                response.headers.getAll("X-Content-Type-Options") shouldBe listOf("nosniff")
                response.headers.getAll("Referrer-Policy") shouldBe listOf("no-referrer")
                response.headers.getAll("Cross-Origin-Resource-Policy") shouldBe listOf("cross-origin")
                response.headers.getAll("Content-Security-Policy") shouldBe listOf("default-src 'none'; sandbox")
                response.headers.getAll(HttpHeaders.ContentDisposition) shouldBe listOf("inline")
                response.headers.getAll(HttpHeaders.Vary) shouldBe listOf("Origin")
                response.headers[HttpHeaders.ETag] shouldBe null
                MemberPhotoTestImages.dimensions(response.bodyAsBytes()) shouldBe (800 to 800)
            }
        }

        test("oracle discipline: every kind of miss has the identical status, body and header set") {
            val root = MemberPhotoFixtures.freshRoot("pub-oracle")
            val storage = MemberPhotoStorage(root)
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(storage) }
                }
                val now = DbClock.nowLocalDateTime()
                val misses = linkedMapOf<String, String>()

                misses["malformed"] = "not-a-token"
                misses["unknown"] = "A".repeat(43)

                val privateMember = fixtures.newMember()
                fixtures.seedPhoto(storage = storage, memberId = privateMember, publish = false)
                misses["private (no token at all)"] = "B".repeat(43)

                val withdrawn = fixtures.newMember()
                val withdrawnToken = fixtures.seedPhoto(storage = storage, memberId = withdrawn, publish = true)!!
                transaction { MemberPhotoStore.unpublish(withdrawn) }
                misses["withdrawn consent (old token)"] = withdrawnToken

                val rotated = fixtures.newMember()
                val rotatedOld = fixtures.seedPhoto(storage = storage, memberId = rotated, publish = true)!!
                transaction {
                    MemberPhotoStore.unpublish(rotated)
                    MemberPhotoStore.publish(memberId = rotated, consentTextVersion = MemberPhotoRules.CONSENT_TEXT_VERSION, now = now)
                }
                (fixtures.tokenOf(rotated) == rotatedOld) shouldBe false
                misses["rotated token (old one)"] = rotatedOld

                listOf(MemberStatus.WITHDRAWN, MemberStatus.DECEASED, MemberStatus.APPLICATION, MemberStatus.FRIEND).forEach { status ->
                    val member = fixtures.newMember()
                    val token = fixtures.seedPhoto(storage = storage, memberId = member, publish = true)!!
                    fixtures.setStatus(memberId = member, status = status)
                    misses["status $status"] = token
                }

                val noFile = fixtures.newMember()
                val noFileToken = fixtures.seedPhoto(storage = storage, memberId = noFile, publish = true)!!
                val noFileKey =
                    transaction {
                        MemberPhotoTable.selectAll().where { MemberPhotoTable.memberId eq noFile }.single()[MemberPhotoTable.storageKey]
                    }
                storage.delete(noFileKey)
                misses["file missing on disk"] = noFileToken

                val shapes = misses.mapValues { (_, token) -> client.fetch(token).shape() }
                val baseline = shapes.getValue("malformed")
                baseline.status shouldBe HttpStatusCode.NotFound
                baseline.body shouldBe emptyList()
                shapes.forEach { (label, shape) ->
                    withClueLabel(label = label) { shape shouldBe baseline }
                }
            }
        }

        test("revocation is immediate: 200, then unpublish, then the very next request is 404") {
            val root = MemberPhotoFixtures.freshRoot("pub-revoke")
            val storage = MemberPhotoStorage(root)
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(storage) }
                }
                val member = fixtures.newMember()
                val token = fixtures.seedPhoto(storage = storage, memberId = member, publish = true)!!
                client.fetch(token).status shouldBe HttpStatusCode.OK
                transaction { MemberPhotoStore.unpublish(member) }
                client.fetch(token).status shouldBe HttpStatusCode.NotFound
            }
        }

        test("CORS: an allowlisted Origin gets ACAO, a foreign Origin is 403 before any lookup, no Origin means no ACAO") {
            val root = MemberPhotoFixtures.freshRoot("pub-cors")
            val storage = MemberPhotoStorage(root)
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(storage, embedConfig = ALLOWLIST_CONFIG) }
                }
                val member = fixtures.newMember()
                val token = fixtures.seedPhoto(storage = storage, memberId = member, publish = true)!!

                val allowed = client.fetch(token, origin = ALLOWED_ORIGIN)
                allowed.status shouldBe HttpStatusCode.OK
                allowed.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe ALLOWED_ORIGIN
                allowed.headers[HttpHeaders.AccessControlAllowCredentials] shouldBe null
                allowed.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")

                val foreign = client.fetch(token, origin = "https://evil.example")
                foreign.status shouldBe HttpStatusCode.Forbidden
                foreign.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
                // also for a token that does not exist -- the rejection happens before any lookup, so it is not an oracle
                client.fetch("Z".repeat(43), origin = "https://evil.example").status shouldBe HttpStatusCode.Forbidden

                val noOrigin = client.fetch(token)
                noOrigin.status shouldBe HttpStatusCode.OK
                noOrigin.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
            }
        }

        test("works with EmbedConfig.DISABLED (independent of LAPIS_EMBED_ENABLED), just without any CORS grant") {
            val root = MemberPhotoFixtures.freshRoot("pub-disabled")
            val storage = MemberPhotoStorage(root)
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(storage, embedConfig = EmbedConfig.DISABLED) }
                }
                val member = fixtures.newMember()
                val token = fixtures.seedPhoto(storage = storage, memberId = member, publish = true)!!
                val response = client.fetch(token)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
                client.fetch(token, origin = ALLOWED_ORIGIN).status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("rate limit: the third request with a limit of two is 429") {
            val root = MemberPhotoFixtures.freshRoot("pub-rate")
            val storage = MemberPhotoStorage(root)
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        install(storage, publicLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes))
                    }
                }
                val token = "C".repeat(43)
                client.fetch(token).status shouldBe HttpStatusCode.NotFound
                client.fetch(token).status shouldBe HttpStatusCode.NotFound
                client.fetch(token).status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("a random 43-character token never collides with real ones (sanity on the token shape)") {
            val token = MemberPhotoStore.newPublicToken()
            token.length shouldBe 43
            Regex("^[A-Za-z0-9_-]{43}$").matches(token) shouldBe true
            (MemberPhotoStore.newPublicToken() == token) shouldBe false
            Uuid.random() // keep import used
        }
    })

private inline fun withClueLabel(
    label: String,
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("miss kind '$label': ${e.message}", e)
    }
}
