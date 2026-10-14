package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.VideoOverlayAssetTable
import network.lapis.cloud.server.memberbio.PublicProfilesFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoFixtures
import network.lapis.cloud.server.videooverlays.VideoOverlayInstanceLogo
import network.lapis.cloud.server.videooverlays.VideoOverlayStorage
import network.lapis.cloud.server.videooverlays.VideoOverlayTestImages
import network.lapis.cloud.server.videooverlays.VideoOverlayTestState
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.VideoOverlayAdminLibraryDto
import network.lapis.cloud.shared.domain.VideoOverlayKind
import network.lapis.cloud.shared.domain.VideoOverlayLibraryDto
import network.lapis.cloud.shared.domain.VideoOverlayRules
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.uuid.Uuid

private val json = Json

/**
 * Welle V1.9.129 -- [VideoOverlayService]: what a member sees (nothing unless offered and eligible), the synthetic instance-logo element,
 * the ADMIN-only administration (rename, move, delete) and the audit entries it writes (kind, name and sizes only -- never token or hash).
 */
class VideoOverlayServiceTest :
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

        fun newRoot(): File = MemberPhotoFixtures.freshRoot("video-overlay-service")

        fun withApp(
            storage: VideoOverlayStorage,
            instanceLogo: VideoOverlayInstanceLogo = VideoOverlayInstanceLogo.none(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
            application {
                install(StatusPages) {
                    exception<UnauthenticatedException> {
                        call,
                        cause,
                        ->
                        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                    }
                    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
                    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                }
                routing { registerOverlayServiceTestRoutes(storage = storage, instanceLogo = instanceLogo) }
            }
            block()
        }

        suspend fun HttpClient.library(who: Uuid): VideoOverlayLibraryDto =
            json.decodeFromString(get("/t/library") { header("X-Member-Id", who.toString()) }.bodyAsText())

        suspend fun HttpClient.admin(
            path: String,
            who: Uuid,
        ): HttpResponse = post(path) { header("X-Member-Id", who.toString()) }

        fun adminDto(text: String): VideoOverlayAdminLibraryDto = json.decodeFromString(text)

        fun pngLogo(
            dir: File,
            name: String = "logo.png",
        ): File = File(dir, name).also { it.writeBytes(VideoOverlayTestImages.logoPng(width = 400, height = 100)) }

        test("a member sees nothing while the option is off -- not even names or paths; an ADMIN's member view is empty too") {
            val root = newRoot()
            val storage = VideoOverlayStorage(root)
            VideoOverlayTestState.setOffered(false)
            VideoOverlayTestState.seed(storage = storage, name = "Geheimer Name")
            withApp(storage) {
                val member = fixtures.newMember()
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                for (who in listOf(member, admin)) {
                    val dto = client.library(who)
                    dto.offered shouldBe false
                    dto.items shouldBe emptyList()
                }
            }
        }

        test("with the option on the library lists the stored elements in order; a member who may not enter conferences sees nothing") {
            val root = newRoot()
            val storage = VideoOverlayStorage(root)
            VideoOverlayTestState.setOffered(true)
            VideoOverlayTestState.seed(storage = storage, name = "Eins")
            VideoOverlayTestState.seed(storage = storage, kind = VideoOverlayKind.FRAME, name = "Zwei")
            withApp(storage) {
                val member = fixtures.newMember()
                val applicant = fixtures.newMember(status = MemberStatus.APPLICATION)
                val dto = client.library(member)
                dto.offered shouldBe true
                dto.items.map { it.name } shouldBe listOf("Eins", "Zwei")
                dto.items.map { it.kind } shouldBe listOf(VideoOverlayKind.LOGO, VideoOverlayKind.FRAME)
                dto.items.forEach { it.imagePath.startsWith("/api/video-overlays/") shouldBe true }
                client.library(applicant).items shouldBe emptyList()
            }
        }

        test("a PNG instance logo is the first element; an SVG logo and no logo are not offered") {
            val root = newRoot()
            val storage = VideoOverlayStorage(root)
            VideoOverlayTestState.setOffered(true)
            VideoOverlayTestState.seed(storage = storage, name = "Eins")
            val dir = MemberPhotoFixtures.freshRoot("instance-logo")
            val png = VideoOverlayInstanceLogo.resolve(ResolvedBranding(title = "PdV", logoAvailable = true, logoPath = pngLogo(dir).path))
            withApp(storage, png) {
                val member = fixtures.newMember()
                val items = client.library(member).items
                items.map { it.name } shouldBe listOf("PdV", "Eins")
                items.first().id shouldBe VideoOverlayRules.INSTANCE_LOGO_ID
                items.first().instanceLogo shouldBe true
                items.first().imagePath shouldBe "/api/branding/logo"
                items.first().width shouldBe 400
                items.first().height shouldBe 100
            }
            val svgFile = File(dir, "logo.svg").also { it.writeText("<svg xmlns=\"http://www.w3.org/2000/svg\"/>") }
            val svg = VideoOverlayInstanceLogo.resolve(ResolvedBranding(title = "PdV", logoAvailable = true, logoPath = svgFile.path))
            svg.item shouldBe null
            svg.reason shouldBe "svg"
            VideoOverlayInstanceLogo.resolve(ResolvedBranding(title = "PdV", logoAvailable = false, logoPath = null)).reason shouldBe "none"
            val webp = File(dir, "logo.webp").also { it.writeBytes(ByteArray(40)) }
            val webpLogo = VideoOverlayInstanceLogo.resolve(ResolvedBranding(title = "PdV", logoAvailable = true, logoPath = webp.path))
            webpLogo.item!!.width shouldBe 0
        }

        test("the administration is ADMIN-only: BOARD, TREASURER, MEMBER and anonymous callers are refused and nothing changes") {
            val root = newRoot()
            val storage = VideoOverlayStorage(root)
            val (id, _) = VideoOverlayTestState.seed(storage = storage, name = "Eins")
            withApp(storage) {
                for (role in listOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.MEMBER)) {
                    val who = fixtures.newMember(role = role)
                    client.admin("/t/admin-library", who).status shouldBe HttpStatusCode.Forbidden
                    client.admin("/t/rename?id=$id&name=Neu", who).status shouldBe HttpStatusCode.Forbidden
                    client.admin("/t/delete?id=$id", who).status shouldBe HttpStatusCode.Forbidden
                    client.admin("/t/move?id=$id&up=true", who).status shouldBe HttpStatusCode.Forbidden
                }
                client.post("/t/delete?id=$id").status shouldBe HttpStatusCode.Unauthorized
                VideoOverlayTestState.rows().single()[VideoOverlayAssetTable.name] shouldBe "Eins"
                File(root, "$id.png").exists() shouldBe true
            }
        }

        test("rename trims, validates and audits as UPDATE with before and after; no token or hash reaches the audit chain") {
            val root = newRoot()
            val storage = VideoOverlayStorage(root)
            val (id, token) = VideoOverlayTestState.seed(storage = storage, name = "Alt")
            withApp(storage) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val renamed = adminDto(client.admin("/t/rename?id=$id&name=%20Neu%20", admin).bodyAsText())
                renamed.items
                    .single()
                    .item.name shouldBe "Neu"
                client.admin("/t/rename?id=$id&name=", admin).status shouldBe HttpStatusCode.BadRequest
                client.admin("/t/rename?id=$id&name=${"x".repeat(61)}", admin).status shouldBe HttpStatusCode.BadRequest
                client.admin("/t/rename?id=${Uuid.random()}&name=Egal", admin).status shouldBe HttpStatusCode.NotFound
                client.admin("/t/rename?id=${VideoOverlayRules.INSTANCE_LOGO_ID}&name=Egal", admin).status shouldBe
                    HttpStatusCode.BadRequest
                val audit = VideoOverlayTestState.auditSnapshots()
                audit.size shouldBe 2
                audit[0] shouldContain "\"name\":\"Alt\""
                audit[1] shouldContain "\"name\":\"Neu\""
                audit.forEach {
                    it shouldNotContain token
                    it shouldNotContain "0".repeat(64)
                }
            }
        }

        test("move swaps neighbours, is a no-op at the edge (no audit), and renumbers a library with ties") {
            val root = newRoot()
            val storage = VideoOverlayStorage(root)
            val (a, _) = VideoOverlayTestState.seed(storage = storage, name = "A")
            val (b, _) = VideoOverlayTestState.seed(storage = storage, name = "B")
            val (c, _) = VideoOverlayTestState.seed(storage = storage, name = "C")
            withApp(storage) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                adminDto(client.admin("/t/move?id=$c&up=true", admin).bodyAsText()).items.map { it.item.name } shouldBe
                    listOf("A", "C", "B")
                adminDto(client.admin("/t/move?id=$a&up=true", admin).bodyAsText()).items.map { it.item.name } shouldBe
                    listOf("A", "C", "B")
                adminDto(client.admin("/t/move?id=$b&up=true", admin).bodyAsText()).items.map { it.item.name } shouldBe
                    listOf("A", "B", "C")
                VideoOverlayTestState.auditSnapshots().size shouldBe 4 // two real moves, two entries each (before + after)
                // The audit records WHAT moved and WHERE TO: C went from index 2 to 1 (before != after).
                val snapshots = VideoOverlayTestState.auditSnapshots()
                snapshots[0] shouldContain "\"position\":2"
                snapshots[1] shouldContain "\"position\":1"
                snapshots[0] shouldNotBe snapshots[1]
                // Ties in sort_order must not turn a move into a no-op.
                transaction { exec("UPDATE video_overlay_asset SET sort_order = 7") }
                val ordered = adminDto(client.admin("/t/admin-library", admin).bodyAsText()).items.map { it.item.name }
                val last = ordered.last()
                val lastId = VideoOverlayTestState.rows().first { it[VideoOverlayAssetTable.name] == last }[VideoOverlayAssetTable.id]
                val moved = adminDto(client.admin("/t/move?id=$lastId&up=true", admin).bodyAsText()).items.map { it.item.name }
                moved shouldBe (ordered.dropLast(2) + listOf(last, ordered[ordered.size - 2]))
            }
        }

        test("delete removes the row and the file, audits as VOID, and a second delete is NotFound; the instance logo is not deletable") {
            val root = newRoot()
            val storage = VideoOverlayStorage(root)
            val (id, token) = VideoOverlayTestState.seed(storage = storage, name = "Weg")
            VideoOverlayTestState.seed(storage = storage, name = "Bleibt")
            withApp(storage) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                File(root, "$id.png").exists() shouldBe true
                val after = adminDto(client.admin("/t/delete?id=$id", admin).bodyAsText())
                after.items.map { it.item.name } shouldBe listOf("Bleibt")
                File(root, "$id.png").exists() shouldBe false
                client.admin("/t/delete?id=$id", admin).status shouldBe HttpStatusCode.NotFound
                client.admin("/t/delete?id=${VideoOverlayRules.INSTANCE_LOGO_ID}", admin).status shouldBe HttpStatusCode.BadRequest
                val audit = VideoOverlayTestState.auditSnapshots()
                audit.single() shouldContain "\"name\":\"Weg\""
                audit.single() shouldNotContain token
                // The audit action of the removal is VOID (the audit vocabulary has no DELETE).
                VideoOverlayTestState.auditActions() shouldBe listOf(AuditAction.VOID)
            }
        }
    })

private fun Route.registerOverlayServiceTestRoutes(
    storage: VideoOverlayStorage,
    instanceLogo: VideoOverlayInstanceLogo,
) {
    fun service(call: io.ktor.server.application.ApplicationCall) =
        VideoOverlayService(call = call, storage = storage, instanceLogo = instanceLogo)
    get("/t/library") { call.respondText(Json.encodeToString(VideoOverlayLibraryDto.serializer(), service(call).getLibrary())) }
    post("/t/admin-library") {
        call.respondText(Json.encodeToString(VideoOverlayAdminLibraryDto.serializer(), service(call).getAdminLibrary()))
    }
    post("/t/rename") {
        val q = call.request.queryParameters
        call.respondText(
            Json.encodeToString(VideoOverlayAdminLibraryDto.serializer(), service(call).renameItem(id = q["id"]!!, name = q["name"]!!)),
        )
    }
    post("/t/delete") {
        call.respondText(
            Json.encodeToString(VideoOverlayAdminLibraryDto.serializer(), service(call).deleteItem(call.request.queryParameters["id"]!!)),
        )
    }
    post("/t/move") {
        val q = call.request.queryParameters
        call.respondText(
            Json.encodeToString(
                VideoOverlayAdminLibraryDto.serializer(),
                service(call).moveItem(id = q["id"]!!, up = q["up"]!!.toBoolean()),
            ),
        )
    }
}
