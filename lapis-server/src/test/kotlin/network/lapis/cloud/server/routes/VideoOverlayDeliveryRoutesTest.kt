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
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.memberbio.PublicProfilesFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoFixtures
import network.lapis.cloud.server.videooverlays.VideoOverlayStorage
import network.lapis.cloud.server.videooverlays.VideoOverlayStore
import network.lapis.cloud.server.videooverlays.VideoOverlayTestState
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** Status, every header except the clock, and the body -- what an outside observer can tell apart. */
private data class ResponseShape(
    val status: HttpStatusCode,
    val headers: Map<String, List<String>>,
    val body: List<Byte>,
)

private suspend fun HttpResponse.shape(): ResponseShape =
    ResponseShape(
        status = status,
        headers =
            headers
                .entries()
                .filter { it.key != HttpHeaders.Date && it.key != HttpHeaders.ContentLength }
                .associate { it.key to it.value },
        body = bodyAsBytes().toList(),
    )

/**
 * Welle V1.9.129 "Video-Overlays" -- the authenticated delivery route: exact header set (no CORS), ADMIN preparation while the option is
 * off, and ONE byte-identical 404 for every kind of miss (unknown, malformed, deleted, missing file, option off, member not eligible).
 */
class VideoOverlayDeliveryRoutesTest :
    FunSpec({
        val fixtures = PublicProfilesFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { VideoOverlayTestState.reset() }
        afterSpec {
            VideoOverlayTestState.setOffered(false)
            VideoOverlayTestState.reset()
            fixtures.cleanup()
        }

        fun newRoot(): File = MemberPhotoFixtures.freshRoot("video-overlay-delivery")

        fun Routing.install(
            storage: VideoOverlayStorage,
            readLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
        ) {
            registerVideoOverlayRoutes(
                storage = storage,
                baseUrl = "https://lapis.example.org",
                uploadRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                readRateLimiter = readLimiter,
            )
        }

        fun withApp(
            storage: VideoOverlayStorage,
            readLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
            application { routing { install(storage, readLimiter) } }
            block()
        }

        suspend fun HttpClient.fetch(
            actor: Uuid?,
            token: String,
        ): HttpResponse =
            get("/api/video-overlays/$token") {
                if (actor != null) header("X-Member-Id", actor.toString())
            }

        test("a member gets 200 with the exact header set while the option is on -- and NO CORS") {
            val root = newRoot()
            val storage = VideoOverlayStorage(root)
            VideoOverlayTestState.setOffered(true)
            val (_, token) = VideoOverlayTestState.seed(storage = storage)
            withApp(storage) {
                val member = fixtures.newMember()
                val response = client.fetch(member, token)
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldBe "image/png"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                response.headers["Cross-Origin-Resource-Policy"] shouldBe "same-origin"
                response.headers["Content-Security-Policy"] shouldBe "default-src 'none'; sandbox"
                response.headers["Referrer-Policy"] shouldBe "no-referrer"
                response.headers[HttpHeaders.ContentDisposition] shouldBe "inline; filename=\"overlay.png\""
                response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("private, max-age=86400, immutable")
                response.headers.names().none { it.startsWith("Access-Control-", ignoreCase = true) } shouldBe true
                ImageIO.read(response.bodyAsBytes().inputStream()).width shouldBe 600
            }
        }

        test("an ADMIN may fetch while the option is off (preparation); a member may not") {
            val root = newRoot()
            val storage = VideoOverlayStorage(root)
            VideoOverlayTestState.setOffered(false)
            val (_, token) = VideoOverlayTestState.seed(storage = storage)
            withApp(storage) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val member = fixtures.newMember()
                client.fetch(admin, token).status shouldBe HttpStatusCode.OK
                client.fetch(member, token).status shouldBe HttpStatusCode.NotFound
            }
        }

        test("every kind of miss is the SAME 404: identical status, identical headers, identical (empty) body") {
            val root = newRoot()
            val storage = VideoOverlayStorage(root)
            VideoOverlayTestState.setOffered(true)
            val (_, token) = VideoOverlayTestState.seed(storage = storage)
            val (goneId, goneToken) = VideoOverlayTestState.seed(storage = storage, name = "Datei fehlt")
            val (deletedId, deletedToken) = VideoOverlayTestState.seed(storage = storage, name = "Geloescht")
            File(root, "$goneId.png").delete() // the row stays, the FILE is missing
            transaction { VideoOverlayStore.delete(deletedId) } // the row is gone, the file is still there
            withApp(storage) {
                val member = fixtures.newMember()
                val notEligible = fixtures.newMember(status = MemberStatus.APPLICATION)
                val shapes = mutableListOf<ResponseShape>()
                for (
                candidate in
                listOf(
                    "A".repeat(43),
                    "abc",
                    "A".repeat(42),
                    "A".repeat(44),
                    "${"A".repeat(42)}!",
                    "..%2F..%2Fetc%2Fpasswd",
                    goneToken,
                    deletedToken,
                )
                ) {
                    val response = client.fetch(member, candidate)
                    response.status shouldBe HttpStatusCode.NotFound
                    shapes += response.shape()
                }
                // A valid token the caller may not see (status not eligible) looks exactly the same.
                shapes += client.fetch(notEligible, token).shape()
                // ... and so does a valid token while the option is off.
                VideoOverlayTestState.setOffered(false)
                shapes += client.fetch(member, token).shape()
                shapes.map { it.status }.toSet() shouldBe setOf(HttpStatusCode.NotFound)
                shapes.toSet().size shouldBe 1
                shapes.first().body shouldBe emptyList()
                shapes.first().headers["X-Content-Type-Options"] shouldBe listOf("nosniff")
                shapes.first().headers[HttpHeaders.CacheControl] shouldBe listOf("no-store")
            }
        }

        test("no session is 401") {
            val root = newRoot()
            val storage = VideoOverlayStorage(root)
            VideoOverlayTestState.setOffered(true)
            val (_, token) = VideoOverlayTestState.seed(storage = storage)
            withApp(storage) {
                client.fetch(null, token).status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("the per-member read rate limit answers 429") {
            val root = newRoot()
            val storage = VideoOverlayStorage(root)
            VideoOverlayTestState.setOffered(true)
            val (_, token) = VideoOverlayTestState.seed(storage = storage)
            withApp(storage, FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                val member = fixtures.newMember()
                client.fetch(member, token).status shouldBe HttpStatusCode.OK
                client.fetch(member, token).status shouldBe HttpStatusCode.TooManyRequests
            }
        }
    })
