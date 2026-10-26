package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.gallery.AttachedImageStorage
import network.lapis.cloud.server.gallery.AttachedImageStore
import network.lapis.cloud.server.gallery.GalleryFixtures
import network.lapis.cloud.server.gallery.GalleryUrls
import network.lapis.cloud.server.module
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.AttachedImageUploadResultDto
import network.lapis.cloud.shared.domain.GalleryRules
import network.lapis.cloud.shared.domain.GalleryUploadErrorCode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val ORIGIN = "http://localhost:8080"
private val AUCTION = AttachedImageOwnerType.AUCTION_LISTING
private val SOCIAL = AttachedImageOwnerType.SOCIAL_POST

private fun LocalDateTime.minus15Minutes(): LocalDateTime = toInstant(TimeZone.UTC).minus(15.minutes).toLocalDateTime(TimeZone.UTC)

private fun picture(
    format: String,
    width: Int = 300,
    height: Int = 200,
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.ORANGE
    g.fillRect(0, 0, width, height)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
}

private fun parts(vararg files: ByteArray) =
    formData {
        files.forEachIndexed { index, bytes ->
            append("file$index", bytes, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"evil$index.php.jpg\"") })
        }
    }

private fun pngClaiming(
    width: Int,
    height: Int,
): ByteArray {
    val bytes = picture(format = "png", width = 8, height = 8)
    val start = 16

    fun putInt(
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
    putInt(start, width)
    putInt(start + 4, height)
    putInt(start + 13, CRC32().apply { update(bytes, start - 4, 17) }.value.toInt())
    return bytes
}

/**
 * V1.9.222 -- the two gallery routes through the REAL `module()` (StatusPages, session resolution, the processor, the storage, the
 * audit
 * chain). The
 * codes are the stable JSON error codes; every refusal is JSON, never free text.
 */
class AttachedImageRoutesTest :
    FunSpec({
        val fx = GalleryFixtures(sharedStorage = AttachedImageStorage.fromEnvironment(documentStorageRoot = File("build/document-storage")))
        val json = Json { ignoreUnknownKeys = true }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { fx.setAuctionEnabled(true) }
        afterSpec { fx.cleanUp() }

        fun error(body: String): GalleryUploadErrorCode? = json.decodeFromString(AttachedImageUploadResultDto.serializer(), body).error

        suspend fun HttpClient.upload(
            member: Uuid?,
            ownerType: String,
            ownerId: String,
            vararg files: ByteArray,
            origin: String? = ORIGIN,
        ): HttpResponse =
            post("/api/images/$ownerType/$ownerId") {
                if (origin != null) header(HttpHeaders.Origin, origin)
                if (member != null) header("X-Member-Id", member.toString())
                setBody(MultiPartFormDataContent(parts(*files)))
            }

        test("CSRF: a foreign Origin and Origin null are refused with a JSON code before anything else") {
            testApplication {
                application { module() }
                val seller = fx.member(label = "seller")
                val auction = fx.auction(seller = seller)
                for (origin in listOf("https://evil.example", "null")) {
                    val response = client.upload(seller, "AUCTION_LISTING", auction.toString(), picture(format = "jpeg"), origin = origin)
                    response.status shouldBe HttpStatusCode.Forbidden
                    error(response.bodyAsText()) shouldBe GalleryUploadErrorCode.INVALID_ORIGIN
                }
                fx.rows(type = AUCTION, ownerId = auction).size shouldBe 0
            }
        }

        test("no session -> 401, unknown owner type (exact enum name required) and malformed or unknown owner -> 404") {
            testApplication {
                application { module() }
                val seller = fx.member(label = "seller")
                val auction = fx.auction(seller = seller)
                client.upload(null, "AUCTION_LISTING", auction.toString(), picture(format = "jpeg")).status shouldBe
                    HttpStatusCode.Unauthorized
                for ((type, id) in listOf(
                    "auction_listing" to auction.toString(),
                    "SOCIALPOST" to auction.toString(),
                    "AUCTION_LISTING" to "not-a-uuid",
                    "AUCTION_LISTING" to Uuid.random().toString(),
                )) {
                    val response = client.upload(seller, type, id, picture(format = "jpeg"))
                    response.status shouldBe HttpStatusCode.NotFound
                    error(response.bodyAsText()) shouldBe GalleryUploadErrorCode.NOT_FOUND
                }
            }
        }

        test("only the creator uploads: another member gets 403, a disabled auction is a 404 for the creator too") {
            testApplication {
                application { module() }
                val seller = fx.member(label = "seller")
                val other = fx.member(label = "other")
                val auction = fx.auction(seller = seller)
                val foreign = client.upload(other, "AUCTION_LISTING", auction.toString(), picture(format = "jpeg"))
                foreign.status shouldBe HttpStatusCode.Forbidden
                error(foreign.bodyAsText()) shouldBe GalleryUploadErrorCode.FORBIDDEN
                fx.setAuctionEnabled(false)
                client.upload(seller, "AUCTION_LISTING", auction.toString(), picture(format = "jpeg")).status shouldBe
                    HttpStatusCode.NotFound
            }
        }

        test("a JPEG, a PNG and a WebP upload succeed; the picture is delivered with exactly the private, hardened headers") {
            testApplication {
                application { module() }
                val seller = fx.member(label = "seller")
                val auction = fx.auction(seller = seller)
                val webp = requireNotNull(AttachedImageRoutesTest::class.java.getResourceAsStream("/gallery/lossy.webp")).readBytes()
                val uploads = listOf(picture(format = "jpeg") to "image/jpeg", picture(format = "png") to "image/png", webp to "image/jpeg")
                val ids =
                    uploads.map { (bytes, expectedType) ->
                        val response = client.upload(seller, "AUCTION_LISTING", auction.toString(), bytes)
                        response.status shouldBe HttpStatusCode.OK
                        response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                        val dto = json.decodeFromString(AttachedImageUploadResultDto.serializer(), response.bodyAsText())
                        dto.url.shouldNotBeNull() shouldContain "/api/images/"
                        val get = client.get(dto.url!!) { header("X-Member-Id", seller.toString()) }
                        get.status shouldBe HttpStatusCode.OK
                        get.headers[HttpHeaders.ContentType] shouldBe expectedType
                        get.headers[HttpHeaders.ContentDisposition] shouldBe
                            "inline; filename=\"bild.${if (expectedType == "image/png") "png" else "jpg"}\""
                        get.headers["X-Content-Type-Options"] shouldBe "nosniff"
                        get.headers["Content-Security-Policy"] shouldBe "default-src 'none'"
                        get.headers["Cross-Origin-Resource-Policy"] shouldBe "same-origin"
                        get.headers["Referrer-Policy"] shouldBe "no-referrer"
                        get.headers[HttpHeaders.CacheControl] shouldBe "private, max-age=31536000, immutable"
                        get.bodyAsBytes().size shouldBe (get.headers[HttpHeaders.ContentLength]?.toInt() ?: get.bodyAsBytes().size)
                        dto.id!!
                    }
                fx.rows(type = AUCTION, ownerId = auction).map { it.id.toString() } shouldBe ids
                // a stale or missing version tag is not cached forever
                val row = fx.rows(type = AUCTION, ownerId = auction).first()
                val withoutTag = client.get("/api/images/${row.id}") { header("X-Member-Id", seller.toString()) }
                withoutTag.headers[HttpHeaders.CacheControl] shouldBe "private, no-cache"
                val wrongTag = client.get("/api/images/${row.id}?v=deadbeef") { header("X-Member-Id", seller.toString()) }
                wrongTag.headers[HttpHeaders.CacheControl] shouldBe "private, no-cache"
            }
        }

        test(
            "wrong content is refused with stable codes: GIF 415, junk behind a JPEG header 422, 9000 px header 422, two files 400",
        ) {
            testApplication {
                application { module() }
                val seller = fx.member(label = "seller")
                val auction = fx.auction(seller = seller)
                val filesBefore = fx.filesOnDisk()
                val gif = client.upload(seller, "AUCTION_LISTING", auction.toString(), "GIF89a".toByteArray() + ByteArray(30))
                gif.status shouldBe HttpStatusCode.UnsupportedMediaType
                error(gif.bodyAsText()) shouldBe GalleryUploadErrorCode.IMAGE_TYPE_NOT_ALLOWED
                val junk =
                    client.upload(
                        seller,
                        "AUCTION_LISTING",
                        auction.toString(),
                        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(64),
                    )
                junk.status shouldBe HttpStatusCode.UnprocessableEntity
                error(junk.bodyAsText()) shouldBe GalleryUploadErrorCode.IMAGE_UNREADABLE
                val huge = client.upload(seller, "AUCTION_LISTING", auction.toString(), pngClaiming(width = 9000, height = 100))
                huge.status shouldBe HttpStatusCode.UnprocessableEntity
                error(huge.bodyAsText()) shouldBe GalleryUploadErrorCode.IMAGE_DIMENSIONS_EXCEEDED
                val two = client.upload(seller, "AUCTION_LISTING", auction.toString(), picture(format = "jpeg"), picture(format = "jpeg"))
                two.status shouldBe HttpStatusCode.BadRequest
                fx.rows(type = AUCTION, ownerId = auction).size shouldBe 0
                fx.filesOnDisk() shouldBe filesBefore // a refused upload leaves nothing on disk
            }
        }

        test("more than 5 MiB is refused with 413 (Content-Length and streaming)") {
            testApplication {
                application { module() }
                val seller = fx.member(label = "seller")
                val auction = fx.auction(seller = seller)
                val response =
                    client.upload(
                        seller,
                        "AUCTION_LISTING",
                        auction.toString(),
                        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(6 * 1024 * 1024),
                    )
                response.status shouldBe HttpStatusCode.PayloadTooLarge
                error(response.bodyAsText()) shouldBe GalleryUploadErrorCode.IMAGE_TOO_LARGE
            }
        }

        test("a frozen gallery is 409 GALLERY_LOCKED and a full one is 409 GALLERY_FULL; the refused file is not left behind") {
            testApplication {
                application { module() }
                val seller = fx.member(label = "seller")
                val locked = fx.auction(seller = seller)
                fx.bid(auctionId = locked, bidder = fx.member(label = "bidder"))
                val frozen = client.upload(seller, "AUCTION_LISTING", locked.toString(), picture(format = "jpeg"))
                frozen.status shouldBe HttpStatusCode.Conflict
                error(frozen.bodyAsText()) shouldBe GalleryUploadErrorCode.GALLERY_LOCKED
                val full = fx.auction(seller = seller)
                repeat(10) { fx.addImage(type = AUCTION, ownerId = full, caller = fx.current(memberId = seller), byteSize = 10) }
                val before = fx.filesOnDisk()
                val response = client.upload(seller, "AUCTION_LISTING", full.toString(), picture(format = "jpeg"))
                response.status shouldBe HttpStatusCode.Conflict
                error(response.bodyAsText()) shouldBe GalleryUploadErrorCode.GALLERY_FULL
                fx.filesOnDisk() shouldBe before
            }
        }

        test("the upload budget is 30 per member and hour: the 31st request is 429 with Retry-After") {
            testApplication {
                application { module() }
                val seller = fx.member(label = "seller")
                repeat(30) {
                    client.upload(seller, "AUCTION_LISTING", Uuid.random().toString(), picture(format = "jpeg")).status shouldBe
                        HttpStatusCode.NotFound
                }
                val limited = client.upload(seller, "AUCTION_LISTING", Uuid.random().toString(), picture(format = "jpeg"))
                limited.status shouldBe HttpStatusCode.TooManyRequests
                limited.headers[HttpHeaders.RetryAfter].shouldNotBeNull()
                error(limited.bodyAsText()) shouldBe GalleryUploadErrorCode.RATE_LIMITED
            }
        }

        test("delivery: an unknown id, an invisible owner, a missing file and an anonymous caller get the IDENTICAL bare 404") {
            testApplication {
                application { module() }
                val seller = fx.member(label = "seller")
                val auction = fx.auction(seller = seller)
                val withFile = fx.addImage(type = AUCTION, ownerId = auction, caller = fx.current(memberId = seller))
                val withoutFile = fx.addImage(type = AUCTION, ownerId = auction, caller = fx.current(memberId = seller))
                fx.storage.delete(withoutFile.storageKey)

                fun signature(r: HttpResponse) = Triple(r.status, r.headers.names().toSortedSet(), r.headers[HttpHeaders.ContentLength])

                val unknown = client.get("/api/images/${Uuid.random()}") { header("X-Member-Id", seller.toString()) }
                val missingFile = client.get("/api/images/${withoutFile.id}?v=x") { header("X-Member-Id", seller.toString()) }
                val anonymous = client.get("/api/images/${withFile.id}")
                val malformed = client.get("/api/images/not-a-uuid") { header("X-Member-Id", seller.toString()) }
                fx.setAuctionEnabled(false)
                val invisible = client.get("/api/images/${withFile.id}") { header("X-Member-Id", seller.toString()) }
                listOf(unknown, missingFile, anonymous, malformed, invisible).forEach { it.status shouldBe HttpStatusCode.NotFound }
                val reference = signature(unknown)
                listOf(missingFile, anonymous, malformed, invisible).forEach { signature(it).first shouldBe reference.first }
                listOf(unknown, missingFile, anonymous, malformed, invisible).map { it.bodyAsText() }.toSet().size shouldBe 1
            }
        }

        // ── V1.9.223: pictures in social posts ──────────────────────────────────────────────
        test(
            "social post: the author uploads, delivery is private/no-store even with the version tag, an auction picture stays immutable",
        ) {
            testApplication {
                application { module() }
                val author = fx.member(label = "author")
                val post = fx.socialPost(author = author)
                val response = client.upload(author, "SOCIAL_POST", post.toString(), picture(format = "jpeg"))
                response.status shouldBe HttpStatusCode.OK
                val dto = json.decodeFromString(AttachedImageUploadResultDto.serializer(), response.bodyAsText())
                dto.url.shouldNotBeNull() shouldContain "?v="
                val get = client.get(dto.url!!) { header("X-Member-Id", author.toString()) }
                get.status shouldBe HttpStatusCode.OK
                get.headers[HttpHeaders.CacheControl] shouldBe "private, no-store"
                get.headers[HttpHeaders.ContentType] shouldBe "image/jpeg"
                get.headers["X-Content-Type-Options"] shouldBe "nosniff"
                get.headers["Cross-Origin-Resource-Policy"] shouldBe "same-origin"
                get.headers["Referrer-Policy"] shouldBe "no-referrer"
                // an ACTIVE member reads a PUBLIC post's picture; the auction branch keeps its long cache
                client.get(dto.url!!) { header("X-Member-Id", fx.member(label = "reader").toString()) }.status shouldBe HttpStatusCode.OK
                val auction = fx.auction(seller = author)
                val auctionRow = fx.addImage(type = AUCTION, ownerId = auction, caller = fx.current(memberId = author))
                val auctionGet =
                    client.get("/api/images/${auctionRow.id}?v=${GalleryUrls.versionTag(auctionRow.storageKey)}") {
                        header("X-Member-Id", author.toString())
                    }
                auctionGet.headers[HttpHeaders.CacheControl] shouldBe "private, max-age=31536000, immutable"
            }
        }

        test(
            "social post delivery: anonymous, invisible, hidden, comment and unknown all give the identical bare 404; a stranger cannot upload",
        ) {
            testApplication {
                application { module() }
                val author = fx.member(label = "author")
                val friend = fx.member(label = "friend", status = MemberStatus.FRIEND)
                val closed = fx.socialPost(author = author, visibility = SocialPostVisibility.MEMBERS_ONLY)
                val hidden = fx.socialPost(author = author)
                val root = fx.socialPost(author = author)
                val comment = fx.socialPost(author = author, parentId = root)
                val closedRow = fx.addImage(type = SOCIAL, ownerId = closed, caller = fx.current(memberId = author))
                val hiddenRow = fx.addImage(type = SOCIAL, ownerId = hidden, caller = fx.current(memberId = author))
                // a row below a comment cannot be created through the API: plant it to prove the read path ignores it
                val plantedComment =
                    transaction {
                        AttachedImageStore.insertInTx(
                            id = Uuid.random(),
                            type = SOCIAL,
                            ownerId = comment,
                            storageKey = Uuid.random(),
                            mimeType = "image/jpeg",
                            byteSize = 5,
                            width = 5,
                            height = 5,
                            uploadedBy = author,
                            createdAt = DbClock.nowLocalDateTime(),
                        )
                    }
                fx.setPostState(postId = hidden, state = SocialPostState.HIDDEN_BY_AUTHOR)
                val responses =
                    listOf(
                        client.get("/api/images/${closedRow.id}"),
                        client.get("/api/images/${closedRow.id}") { header("X-Member-Id", friend.toString()) },
                        client.get("/api/images/${hiddenRow.id}") { header("X-Member-Id", author.toString()) },
                        client.get("/api/images/${plantedComment.id}") { header("X-Member-Id", author.toString()) },
                        client.get("/api/images/${Uuid.random()}") { header("X-Member-Id", author.toString()) },
                    )
                responses.forEach { it.status shouldBe HttpStatusCode.NotFound }
                responses.map { it.bodyAsText() }.toSet().size shouldBe 1
                responses.map { it.headers.names().toSortedSet() }.toSet().size shouldBe 1
                val stranger = fx.member(label = "stranger")
                client.upload(stranger, "SOCIAL_POST", root.toString(), picture(format = "jpeg")).also {
                    it.status shouldBe HttpStatusCode.Forbidden
                    error(it.bodyAsText()) shouldBe GalleryUploadErrorCode.FORBIDDEN
                }
                client.upload(author, "SOCIAL_POST", comment.toString(), picture(format = "jpeg")).also {
                    it.status shouldBe HttpStatusCode.NotFound
                    error(it.bodyAsText()) shouldBe GalleryUploadErrorCode.NOT_FOUND
                }
                client
                    .upload(
                        author,
                        "SOCIAL_POST",
                        root.toString(),
                        picture(format = "jpeg"),
                        origin = "https://evil.example",
                    ).status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test(
            "social post: the 21st picture within a day is 429 DAILY_LIMIT_REACHED with Retry-After, the window closing is 409 GALLERY_LOCKED",
        ) {
            testApplication {
                application { module() }
                val author = fx.member(label = "author")
                repeat(5) {
                    val post = fx.socialPost(author = author)
                    repeat(GalleryRules.SOCIAL_POST_MAX_IMAGES) {
                        client.upload(author, "SOCIAL_POST", post.toString(), picture(format = "jpeg")).status shouldBe HttpStatusCode.OK
                    }
                }
                val limited = client.upload(author, "SOCIAL_POST", fx.socialPost(author = author).toString(), picture(format = "jpeg"))
                limited.status shouldBe HttpStatusCode.TooManyRequests
                limited.headers[HttpHeaders.RetryAfter].shouldNotBeNull()
                error(limited.bodyAsText()) shouldBe GalleryUploadErrorCode.DAILY_LIMIT_REACHED
                val other = fx.member(label = "window")
                val old = fx.socialPost(author = other, publishedAt = DbClock.nowLocalDateTime().minus15Minutes())
                val locked = client.upload(other, "SOCIAL_POST", old.toString(), picture(format = "jpeg"))
                locked.status shouldBe HttpStatusCode.Conflict
                error(locked.bodyAsText()) shouldBe GalleryUploadErrorCode.GALLERY_LOCKED
            }
        }
    })
