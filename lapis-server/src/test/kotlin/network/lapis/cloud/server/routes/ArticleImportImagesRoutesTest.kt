package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.articles.TestZipBuilder
import network.lapis.cloud.server.articles.TestZipEntry
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.module
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleImportImageStatus
import network.lapis.cloud.shared.domain.ArticleImportImagesPreviewDto
import network.lapis.cloud.shared.domain.ArticleImportImagesResultDto
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.AuditEntityType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.uuid.Uuid

private const val IMAGES_TEST_ORIGIN = "http://localhost:8080"
private const val PREVIEW = "/api/articles/import/images/preview"
private const val COMMIT = "/api/articles/import/images/commit"
private val COVER_DIR = File("build/document-storage/article-covers")

private fun imageBytes(
    width: Int,
    height: Int,
    format: String = "jpeg",
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, width, height)
    g.color = Color.RED
    g.fillRect(0, 0, width / 3, height / 3)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
}

private fun multipart(
    zip: ByteArray,
    field: String,
    value: String,
) = MultiPartFormDataContent(
    formData {
        append("zip", zip, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"images.zip\"") })
        append(field, value)
    },
)

/** A body whose announced length is whatever the test says (or absent); it never needs to be read, the size guard answers first. */
private fun lengthOnly(length: Long?) =
    object : OutgoingContent.WriteChannelContent() {
        override val contentType = ContentType.MultiPart.FormData.withParameter("boundary", "x")
        override val contentLength = length

        override suspend fun writeTo(channel: ByteWriteChannel) {
            channel.flushAndClose()
        }
    }

/**
 * Welle V1.9.106 -- `POST /api/articles/import/images/{preview,commit}` through the REAL `module()`: the guard order (size before auth, origin,
 * role, rate limit), the honest preview (nothing written), the commit that only touches the articles of ONE import run, never overwrites a cover,
 * stores unscaled small images, and writes exactly one audit entry without file names.
 */
class ArticleImportImagesRoutesTest :
    FunSpec({
        val articleIds = mutableListOf<Uuid>()
        val runPrefix = "imgzip-${Uuid.random().toString().take(8)}"

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            transaction {
                if (articleIds.isNotEmpty()) {
                    val covers =
                        ArticleTable
                            .selectAll()
                            .where {
                                ArticleTable.id inList articleIds
                            }.mapNotNull { it[ArticleTable.coverImageId] }
                    ArticleTable.deleteWhere { id inList articleIds }
                    covers.forEach {
                        COVER_DIR.resolve("$it.jpg").delete()
                        COVER_DIR.resolve("$it.png").delete()
                    }
                }
            }
        }

        // The seeded demo members (the H2 database is shared with specs that count members, and the audit log keeps referencing its actors).
        fun member(role: AccountRole): Uuid = DevSeedData.demoMembers.first { it.role == role }.id

        fun importedArticle(
            slug: String,
            author: Uuid,
            batch: Uuid?,
            imported: Boolean = true,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ArticleTable.insert {
                    it[ArticleTable.id] = id
                    it[ArticleTable.slug] = slug
                    it[title] = "Importiert"
                    it[excerpt] = "x"
                    it[body] = "y"
                    it[authorId] = author
                    it[status] = ArticleStatus.SUBMITTED
                    it[submittedAt] = now
                    it[createdAt] = now
                    it[updatedAt] = now
                    it[ArticleTable.imported] = imported
                    it[importBatchId] = batch
                }
            }
            articleIds += id
            return id
        }

        fun coverOf(id: Uuid) = transaction { ArticleTable.selectAll().where { ArticleTable.id eq id }.single()[ArticleTable.coverImageId] }

        fun slugsJson(vararg s: String) = s.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

        fun auditCount(type: AuditEntityType) =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityType eq type }
                    .count()
                    .toInt()
            }

        test("size guard first: an oversized or length-less POST is 413 even without a session, before anything is read") {
            testApplication {
                application { module() }
                for (path in listOf(PREVIEW, COMMIT)) {
                    client
                        .post(path) {
                            header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                            setBody(lengthOnly(30L * 1024 * 1024))
                        }.status shouldBe HttpStatusCode.PayloadTooLarge
                    client
                        .post(path) {
                            header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                            setBody(lengthOnly(null))
                        }.status shouldBe HttpStatusCode.PayloadTooLarge
                }
            }
        }

        test("a cross-site origin is 403, MEMBER and TREASURER are 403, on both routes") {
            testApplication {
                application { module() }
                val board = member(AccountRole.BOARD)
                val ordinary = member(AccountRole.MEMBER)
                val treasurer = member(AccountRole.TREASURER)
                val zip = TestZipBuilder.simple("a.jpg" to imageBytes(width = 400, height = 300))
                client
                    .post(PREVIEW) {
                        header(HttpHeaders.Origin, "https://evil.example")
                        header("X-Member-Id", board.toString())
                        setBody(multipart(zip = zip, field = "slugs", value = slugsJson("a")))
                    }.status shouldBe HttpStatusCode.Forbidden
                for (who in listOf(ordinary, treasurer)) {
                    client
                        .post(PREVIEW) {
                            header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                            header("X-Member-Id", who.toString())
                            setBody(multipart(zip = zip, field = "slugs", value = slugsJson("a")))
                        }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .post(COMMIT) {
                            header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                            header("X-Member-Id", who.toString())
                            setBody(multipart(zip = zip, field = "importBatchId", value = Uuid.random().toString()))
                        }.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        test("the preview is rate limited per member: the fourth call within a minute is 429 with Retry-After") {
            testApplication {
                application { module() }
                val board = member(AccountRole.BOARD)
                val zip = TestZipBuilder.simple("a.jpg" to imageBytes(width = 400, height = 300))
                repeat(3) {
                    client
                        .post(PREVIEW) {
                            header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                            header("X-Member-Id", board.toString())
                            setBody(multipart(zip = zip, field = "slugs", value = slugsJson("$runPrefix-rl")))
                        }.status shouldBe HttpStatusCode.OK
                }
                val limited =
                    client.post(PREVIEW) {
                        header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(multipart(zip = zip, field = "slugs", value = slugsJson("$runPrefix-rl")))
                    }
                limited.status shouldBe HttpStatusCode.TooManyRequests
                (limited.headers[HttpHeaders.RetryAfter] != null) shouldBe true
            }
        }

        test("preview: every status, honest dimensions, and nothing is written (no file, no article change, no audit entry)") {
            testApplication {
                application { module() }
                val board = member(AccountRole.BOARD)
                val existingSlug = "$runPrefix-existing"
                importedArticle(existingSlug, board, batch = null, imported = false)
                val zip =
                    TestZipBuilder.build(
                        entries =
                            listOf(
                                TestZipEntry(name = "$runPrefix-ok.jpg", data = imageBytes(width = 300, height = 200)),
                                TestZipEntry(name = "$runPrefix-small.jpg", data = imageBytes(width = 299, height = 200)),
                                TestZipEntry(
                                    name = "$runPrefix-fake.png",
                                    data = "GIF89a-not-an-image".toByteArray() + Random(1).nextBytes(64),
                                ),
                                TestZipEntry(name = "$runPrefix-dup.jpg", data = imageBytes(width = 400, height = 300)),
                                TestZipEntry(name = "$runPrefix-dup.png", data = imageBytes(width = 400, height = 300, format = "png")),
                                TestZipEntry(name = "$existingSlug.jpg", data = imageBytes(width = 400, height = 300)),
                                TestZipEntry(name = "$runPrefix-extra.jpg", data = imageBytes(width = 400, height = 300)),
                                TestZipEntry(name = "__MACOSX/._x.jpg", data = "x".toByteArray()),
                                TestZipEntry(name = ".DS_Store", data = "x".toByteArray()),
                            ),
                    )
                val filesBefore = COVER_DIR.list()?.toSet().orEmpty()
                val auditsBefore = auditCount(AuditEntityType.ARTICLE_IMPORT_IMAGES_APPLIED)
                val response =
                    client.post(PREVIEW) {
                        header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(
                            multipart(
                                zip = zip,
                                field = "slugs",
                                value =
                                    slugsJson(
                                        "$runPrefix-ok",
                                        "$runPrefix-small",
                                        "$runPrefix-fake",
                                        "$runPrefix-dup",
                                        "$runPrefix-gone",
                                        existingSlug,
                                    ),
                            ),
                        )
                    }
                response.status shouldBe HttpStatusCode.OK
                val dto = Json.decodeFromString(ArticleImportImagesPreviewDto.serializer(), response.bodyAsText())
                val bySlug = dto.rows.associateBy { it.slug }
                bySlug.getValue("$runPrefix-ok").status shouldBe ArticleImportImageStatus.OK
                bySlug.getValue("$runPrefix-ok").width shouldBe 300
                bySlug.getValue("$runPrefix-ok").height shouldBe 200
                bySlug.getValue("$runPrefix-small").status shouldBe ArticleImportImageStatus.TOO_SMALL
                bySlug.getValue("$runPrefix-small").width shouldBe 299
                bySlug.getValue("$runPrefix-small").height shouldBe 200
                bySlug.getValue("$runPrefix-fake").status shouldBe ArticleImportImageStatus.INVALID_FORMAT
                bySlug.getValue("$runPrefix-dup").status shouldBe ArticleImportImageStatus.DUPLICATE
                bySlug.getValue("$runPrefix-gone").status shouldBe ArticleImportImageStatus.MISSING
                bySlug.getValue(existingSlug).status shouldBe ArticleImportImageStatus.IGNORED_EXISTING_ARTICLE
                dto.unmatchedFiles shouldBe listOf("$runPrefix-extra.jpg")
                dto.unmatchedCount shouldBe 1
                dto.ignoredSystemFiles shouldBe 2
                (COVER_DIR.list()?.toSet().orEmpty()) shouldBe filesBefore
                auditCount(AuditEntityType.ARTICLE_IMPORT_IMAGES_APPLIED) shouldBe auditsBefore
            }
        }

        test("a ZIP with a folder is 422 with the fixed sentence and without any file name; garbage is 422 too") {
            testApplication {
                application { module() }
                val board = member(AccountRole.BOARD)
                val folder = TestZipBuilder.simple("geheim/ordner.jpg" to imageBytes(width = 400, height = 300))
                val response =
                    client.post(PREVIEW) {
                        header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(multipart(zip = folder, field = "slugs", value = slugsJson("ordner")))
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain "direkt im ZIP"
                response.bodyAsText() shouldNotContain "geheim"
                client
                    .post(PREVIEW) {
                        header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(multipart(zip = "kein zip".toByteArray(), field = "slugs", value = slugsJson("ordner")))
                    }.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test("malformed requests are 400: an unexpected field, slugs that are not a JSON list, an invalid batch id, more than 100 slugs") {
            testApplication {
                application { module() }
                val board = member(AccountRole.BOARD)
                val zip = TestZipBuilder.simple("a.jpg" to imageBytes(width = 400, height = 300))
                client
                    .post(PREVIEW) {
                        header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(multipart(zip = zip, field = "imported", value = "true"))
                    }.status shouldBe HttpStatusCode.BadRequest
                client
                    .post(PREVIEW) {
                        header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(multipart(zip = zip, field = "slugs", value = "not json"))
                    }.status shouldBe HttpStatusCode.BadRequest
                client
                    .post(COMMIT) {
                        header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(multipart(zip = zip, field = "importBatchId", value = "nope"))
                    }.status shouldBe HttpStatusCode.BadRequest
                client
                    .post(COMMIT) {
                        header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(multipart(zip = zip, field = "slugs", value = "[]"))
                    }.status shouldBe HttpStatusCode.BadRequest
                val many = slugsJson(*(1..101).map { "s$it" }.toTypedArray())
                client
                    .post(PREVIEW) {
                        header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(multipart(zip = zip, field = "slugs", value = many))
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test(
            "commit: stores covers only for articles of that batch (small ones unscaled), never overwrites, one audit entry without file names",
        ) {
            testApplication {
                application { module() }
                val board = member(AccountRole.BOARD)
                val batch = Uuid.random()
                val otherBatch = Uuid.random()
                val ok = importedArticle("$runPrefix-c-ok", board, batch)
                val small = importedArticle("$runPrefix-c-small", board, batch)
                val hasCover = importedArticle("$runPrefix-c-has", board, batch)
                val foreign = importedArticle("$runPrefix-c-foreign", board, otherBatch)
                val plain = importedArticle("$runPrefix-c-plain", board, null, imported = false)
                val previousCover = Uuid.random()
                transaction { ArticleTable.update({ ArticleTable.id eq hasCover }) { it[coverImageId] = previousCover } }
                val zip =
                    TestZipBuilder.build(
                        entries =
                            listOf(
                                TestZipEntry(name = "$runPrefix-c-ok.jpg", data = imageBytes(width = 300, height = 200)),
                                TestZipEntry(name = "$runPrefix-c-small.jpg", data = imageBytes(width = 100, height = 100)),
                                TestZipEntry(name = "$runPrefix-c-has.jpg", data = imageBytes(width = 400, height = 300)),
                                TestZipEntry(name = "$runPrefix-c-foreign.jpg", data = imageBytes(width = 400, height = 300)),
                                TestZipEntry(name = "$runPrefix-c-plain.jpg", data = imageBytes(width = 400, height = 300)),
                            ),
                    )
                val seqBefore =
                    transaction {
                        AuditLogEntryTable.selectAll().count()
                    }
                val auditsBefore = auditCount(AuditEntityType.ARTICLE_IMPORT_IMAGES_APPLIED)
                val response =
                    client.post(COMMIT) {
                        header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(multipart(zip = zip, field = "importBatchId", value = batch.toString()))
                    }
                response.status shouldBe HttpStatusCode.OK
                val dto = Json.decodeFromString(ArticleImportImagesResultDto.serializer(), response.bodyAsText())
                dto.applied shouldBe 1
                dto.rejected shouldBe 1
                val bySlug = dto.rows.associateBy { it.slug }
                bySlug.getValue("$runPrefix-c-ok").status shouldBe ArticleImportImageStatus.OK
                bySlug.getValue("$runPrefix-c-small").status shouldBe ArticleImportImageStatus.TOO_SMALL
                bySlug.getValue("$runPrefix-c-has").status shouldBe ArticleImportImageStatus.IGNORED_EXISTING_ARTICLE
                (bySlug.containsKey("$runPrefix-c-foreign")) shouldBe false
                (bySlug.containsKey("$runPrefix-c-plain")) shouldBe false

                val stored = coverOf(ok)!!
                val decoded = ImageIO.read(ByteArrayInputStream(COVER_DIR.resolve("$stored.jpg").readBytes()))
                decoded.width shouldBe 300
                decoded.height shouldBe 200
                coverOf(small) shouldBe null
                coverOf(hasCover) shouldBe previousCover
                coverOf(foreign) shouldBe null
                coverOf(plain) shouldBe null

                auditCount(AuditEntityType.ARTICLE_IMPORT_IMAGES_APPLIED) shouldBe auditsBefore + 1
                val audit =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.ARTICLE_IMPORT_IMAGES_APPLIED) and
                                    (AuditLogEntryTable.entityId eq batch)
                            }.single()
                            .let { it[AuditLogEntryTable.afterSnapshot]!! }
                    }
                audit shouldContain ok.toString()
                audit shouldNotContain ".jpg"
                audit shouldNotContain runPrefix
                (transaction { AuditLogEntryTable.selectAll().count() } > seqBefore) shouldBe true

                // the same call again: nothing left to do, no second audit entry, the stored cover is not replaced
                val again =
                    client.post(COMMIT) {
                        header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(multipart(zip = zip, field = "importBatchId", value = batch.toString()))
                    }
                Json.decodeFromString(ArticleImportImagesResultDto.serializer(), again.bodyAsText()).applied shouldBe 0
                coverOf(ok) shouldBe stored
                auditCount(AuditEntityType.ARTICLE_IMPORT_IMAGES_APPLIED) shouldBe auditsBefore + 1
            }
        }

        test("commit with an unknown batch id applies nothing, writes no file and no audit entry") {
            testApplication {
                application { module() }
                val board = member(AccountRole.BOARD)
                val zip = TestZipBuilder.simple("$runPrefix-u.jpg" to imageBytes(width = 400, height = 300))
                val filesBefore = COVER_DIR.list()?.toSet().orEmpty()
                val auditsBefore = auditCount(AuditEntityType.ARTICLE_IMPORT_IMAGES_APPLIED)
                val response =
                    client.post(COMMIT) {
                        header(HttpHeaders.Origin, IMAGES_TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(multipart(zip = zip, field = "importBatchId", value = Uuid.random().toString()))
                    }
                response.status shouldBe HttpStatusCode.OK
                Json.decodeFromString(ArticleImportImagesResultDto.serializer(), response.bodyAsText()).applied shouldBe 0
                (COVER_DIR.list()?.toSet().orEmpty()) shouldBe filesBefore
                auditCount(AuditEntityType.ARTICLE_IMPORT_IMAGES_APPLIED) shouldBe auditsBefore
            }
        }
    })
