package network.lapis.cloud.server.bulletin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.BulletinNoticeTable
import network.lapis.cloud.server.gallery.AttachedImageStorage
import network.lapis.cloud.server.gallery.GalleryFixtures
import network.lapis.cloud.server.gallery.galleryTestApplication
import network.lapis.cloud.server.module
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.AttachedImageUploadResultDto
import network.lapis.cloud.shared.domain.GalleryUploadErrorCode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.NoticeStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.uuid.Uuid

private const val ORIGIN = "http://localhost:8080"
private val NOTICE = AttachedImageOwnerType.NOTICE

private fun picture(format: String): ByteArray {
    val image = BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.ORANGE
    g.fillRect(0, 0, 300, 200)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
}

/**
 * V1.9.297 -- the ONE picture of a bulletin-board notice, through the real gallery route and the real gallery service: only the author uploads,
 * only ACTIVE members see the picture (a guest and a friend get the bare 404), the delivery is `private, no-store` (a withdrawal or an expiry
 * must take effect at once), a second picture is refused, an SVG and a 6 MB file are refused, and BOARD can remove a picture. No new route.
 */
class NoticeImageRoutesTest :
    FunSpec({
        val gallery =
            GalleryFixtures(sharedStorage = AttachedImageStorage.fromEnvironment(documentStorageRoot = File("build/document-storage")))
        val notices = NoticeFixtures()
        val json = Json { ignoreUnknownKeys = true }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            notices.cleanUp()
            gallery.cleanUp()
        }

        fun error(body: String): GalleryUploadErrorCode? = json.decodeFromString(AttachedImageUploadResultDto.serializer(), body).error

        suspend fun HttpClient.upload(
            member: Uuid?,
            ownerId: Uuid,
            bytes: ByteArray,
        ): HttpResponse =
            post("/api/images/NOTICE/$ownerId") {
                header(HttpHeaders.Origin, ORIGIN)
                if (member != null) header("X-Member-Id", member.toString())
                setBody(
                    MultiPartFormDataContent(
                        formData { append("file", bytes, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"x.jpg\"") }) },
                    ),
                )
            }

        test("the author uploads one picture; a second one is refused as full; the picture is delivered private and never cached") {
            testApplication {
                application { module() }
                val author = gallery.member(label = "author")
                val reader = gallery.member(label = "reader")
                val notice = notices.notice(author = author)
                val first = client.upload(author, notice, picture("jpeg"))
                first.status shouldBe HttpStatusCode.OK
                val dto = json.decodeFromString(AttachedImageUploadResultDto.serializer(), first.bodyAsText())
                val second = client.upload(author, notice, picture("png"))
                second.status shouldBe HttpStatusCode.Conflict
                error(second.bodyAsText()) shouldBe GalleryUploadErrorCode.GALLERY_FULL
                gallery.rows(type = NOTICE, ownerId = notice) shouldHaveSize 1
                listOf(author, reader).forEach { who ->
                    val get = client.get(dto.url!!) { header("X-Member-Id", who.toString()) }
                    get.status shouldBe HttpStatusCode.OK
                    // even with the correct version tag: no cached copy may outlive a withdrawal
                    get.headers[HttpHeaders.CacheControl] shouldBe "private, no-store"
                    get.headers["Cross-Origin-Resource-Policy"] shouldBe "same-origin"
                    get.headers["X-Content-Type-Options"] shouldBe "nosniff"
                }
            }
        }

        test("a stranger cannot upload (403); a guest and a friend get the bare 404 for upload and for the picture") {
            testApplication {
                application { module() }
                val author = gallery.member(label = "author")
                val stranger = gallery.member(label = "stranger")
                val guest = gallery.member(label = "guest", status = MemberStatus.GUEST)
                val friend = gallery.member(label = "friend", status = MemberStatus.FRIEND)
                val notice = notices.notice(author = author)
                val own = gallery.addImage(type = NOTICE, ownerId = notice, caller = gallery.current(memberId = author))
                val foreign = client.upload(stranger, notice, picture("jpeg"))
                foreign.status shouldBe HttpStatusCode.Forbidden
                error(foreign.bodyAsText()) shouldBe GalleryUploadErrorCode.FORBIDDEN
                listOf(guest, friend).forEach { outsider ->
                    client.upload(outsider, notice, picture("jpeg")).status shouldBe HttpStatusCode.NotFound
                    client.get("/api/images/${own.id}") { header("X-Member-Id", outsider.toString()) }.status shouldBe
                        HttpStatusCode.NotFound
                }
                client.get("/api/images/${own.id}").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("after a withdrawal, a removal, an expiry or when the author left, the picture is a 404 and no upload is accepted") {
            testApplication {
                application { module() }
                val author = gallery.member(label = "author")
                val reader = gallery.member(label = "reader")
                val states: List<Pair<String, (Uuid) -> Unit>> =
                    listOf(
                        "withdrawn" to { id ->
                            transaction {
                                BulletinNoticeTable.update({ BulletinNoticeTable.id eq id }) {
                                    it[status] = NoticeStatus.WITHDRAWN
                                    it[withdrawnAt] =
                                        network.lapis.cloud.server.db.DbClock
                                            .nowLocalDateTime()
                                }
                            }
                        },
                        "expired" to { id -> notices.expire(id) },
                    )
                states.forEach { (_, close) ->
                    val notice = notices.notice(author = author)
                    val row = gallery.addImage(type = NOTICE, ownerId = notice, caller = gallery.current(memberId = author))
                    client.get("/api/images/${row.id}") { header("X-Member-Id", reader.toString()) }.status shouldBe HttpStatusCode.OK
                    close(notice)
                    client.get("/api/images/${row.id}") { header("X-Member-Id", reader.toString()) }.status shouldBe HttpStatusCode.NotFound
                    client.upload(author, notice, picture("jpeg")).status shouldBe HttpStatusCode.NotFound
                }
                val leaving = gallery.member(label = "leaver")
                val orphaned = notices.notice(author = leaving)
                val row = gallery.addImage(type = NOTICE, ownerId = orphaned, caller = gallery.current(memberId = leaving))
                notices.setStatus(member = leaving, status = MemberStatus.WITHDRAWN)
                client.get("/api/images/${row.id}") { header("X-Member-Id", reader.toString()) }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("an SVG and a file above 5 MiB are refused with their stable codes and leave nothing behind") {
            testApplication {
                application { module() }
                val author = gallery.member(label = "author")
                val notice = notices.notice(author = author)
                val before = gallery.filesOnDisk()
                val svg =
                    client.upload(
                        author,
                        notice,
                        "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".toByteArray(),
                    )
                svg.status shouldBe HttpStatusCode.UnsupportedMediaType
                error(svg.bodyAsText()) shouldBe GalleryUploadErrorCode.IMAGE_TYPE_NOT_ALLOWED
                val big =
                    client.upload(
                        author,
                        notice,
                        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(6 * 1024 * 1024),
                    )
                big.status shouldBe HttpStatusCode.PayloadTooLarge
                error(big.bodyAsText()) shouldBe GalleryUploadErrorCode.IMAGE_TOO_LARGE
                gallery.rows(type = NOTICE, ownerId = notice).size shouldBe 0
                gallery.filesOnDisk() shouldBe before
            }
        }

        test(
            "the gallery service: the alt text is set once, no caption, no cover, no reorder; BOARD removes a picture, a stranger cannot",
        ) {
            val author = gallery.member(label = "author")
            val board = gallery.member(label = "board", role = AccountRole.BOARD)
            val stranger = gallery.member(label = "stranger")
            val notice = notices.notice(author = author)
            val row = gallery.addImage(type = NOTICE, ownerId = notice, caller = gallery.current(memberId = author))
            galleryTestApplication(fixtures = gallery) {
                val view = call(member = author) { service(it).list(ownerType = NOTICE, ownerId = notice.toString()) }
                view.canEdit shouldBe true
                view.maxImages shouldBe 1
                attempt(
                    member = author,
                ) { service(it).updateCaption(imageId = row.id.toString(), caption = "Unterschrift", altText = null) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
                call(member = author) { service(it).updateCaption(imageId = row.id.toString(), caption = null, altText = "Ein Fahrrad") }
                    .images
                    .single()
                    .altText shouldBe "Ein Fahrrad"
                attempt(member = author) {
                    service(it).setCover(row.id.toString())
                }.exceptionOrNull().shouldBeInstanceOf<network.lapis.cloud.shared.rpc.GalleryLockedException>()
                attempt(member = stranger) {
                    service(it).delete(row.id.toString())
                }.exceptionOrNull().shouldBeInstanceOf<network.lapis.cloud.shared.rpc.ForbiddenException>()
                call(member = board) { service(it).delete(row.id.toString()) }.images.size shouldBe 0
                attempt(member = stranger) { service(it).list(ownerType = NOTICE, ownerId = Uuid.random().toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
            }
        }
    })
