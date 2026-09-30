package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.module
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleCoverResultDto
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.uuid.Uuid

private const val TEST_ORIGIN = "http://localhost:8080"

private fun uploadFormData(bytes: ByteArray) =
    formData {
        append("file", bytes, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"cover.jpg\"") })
    }

private fun renderJpegBytes(
    width: Int = 1000,
    height: Int = 800,
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, width, height)
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "jpeg", out)
    return out.toByteArray()
}

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- `POST`/`DELETE`/`GET
 * /api/articles/{id}/cover`. Exercises [registerArticleCoverRoutes] through the REAL
 * `application { module() }`, same idiom [EventCoverRoutesTest] establishes -- every layer this
 * feature actually touches in production (StatusPages, `resolveCurrentMember`, ownership/status
 * checks, `EventCoverImageProcessor`, `EventCoverStorage`) runs together.
 */
class ArticleCoverRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdArticleIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdArticleIds.isNotEmpty()) {
                    val coverIds =
                        ArticleTable
                            .selectAll()
                            .where { ArticleTable.id inList createdArticleIds }
                            .mapNotNull { it[ArticleTable.coverImageId] }
                    ArticleTable.deleteWhere { id inList createdArticleIds }
                    val root = java.io.File("build/document-storage/article-covers")
                    coverIds.forEach { id ->
                        root.resolve("$id.jpg").delete()
                        root.resolve("$id.png").delete()
                    }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createMember(
            email: String,
            role: AccountRole = AccountRole.MEMBER,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "ArticleCoverRoutesTest Mitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun createArticle(
            authorId: Uuid,
            status: ArticleStatus = ArticleStatus.DRAFT,
            coverImageId: Uuid? = null,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ArticleTable.insert {
                    it[ArticleTable.id] = id
                    it[ArticleTable.slug] = if (status == ArticleStatus.PUBLISHED) "article-cover-test-$id" else null
                    it[ArticleTable.title] = "Article-Cover-Test"
                    it[ArticleTable.excerpt] = "Auszug"
                    it[ArticleTable.body] = "Inhalt"
                    it[ArticleTable.coverImageId] = coverImageId
                    it[ArticleTable.authorId] = authorId
                    it[ArticleTable.status] = status
                    it[ArticleTable.submittedAt] = if (status != ArticleStatus.DRAFT) now else null
                    it[ArticleTable.reviewedBy] = null
                    it[ArticleTable.reviewedAt] = null
                    it[ArticleTable.rejectionReason] = null
                    it[ArticleTable.publishedAt] = if (status == ArticleStatus.PUBLISHED) now else null
                    it[ArticleTable.createdAt] = now
                    it[ArticleTable.updatedAt] = now
                }
            }
            createdArticleIds += id
            return id
        }

        fun decodeResult(text: String): ArticleCoverResultDto = Json.decodeFromString(ArticleCoverResultDto.serializer(), text)

        test("upload DRAFT -> 200, coverImageUrl present, article.cover_image_id set") {
            testApplication {
                application { module() }
                val author = createMember("article-cover-draft-${Uuid.random()}@example.org")
                val articleId = createArticle(authorId = author)
                val response =
                    client.post("/api/articles/$articleId/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", author.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                response.status shouldBe HttpStatusCode.OK
                val dto = decodeResult(response.bodyAsText())
                dto.coverImageUrl.shouldNotBeNull()
                dto.coverImageUrl!! shouldContain "/api/articles/$articleId/cover?v="
                val stored =
                    transaction { ArticleTable.selectAll().where { ArticleTable.id eq articleId }.single()[ArticleTable.coverImageId] }
                stored.shouldNotBeNull()
            }
        }

        test("upload SUBMITTED -> 409, upload PUBLISHED -> 409") {
            testApplication {
                application { module() }
                val author = createMember("article-cover-submitted-${Uuid.random()}@example.org")
                val submittedId = createArticle(authorId = author, status = ArticleStatus.SUBMITTED)
                val publishedId = createArticle(authorId = author, status = ArticleStatus.PUBLISHED)
                val submittedResponse =
                    client.post("/api/articles/$submittedId/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", author.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                submittedResponse.status shouldBe HttpStatusCode.Conflict

                val publishedResponse =
                    client.post("/api/articles/$publishedId/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", author.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                publishedResponse.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("cross-site Origin -> 403") {
            testApplication {
                application { module() }
                val author = createMember("article-cover-origin-${Uuid.random()}@example.org")
                val articleId = createArticle(authorId = author)
                val response =
                    client.post("/api/articles/$articleId/cover") {
                        header(HttpHeaders.Origin, "https://evil.example")
                        header("X-Member-Id", author.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("upload on a foreign member's article -> 404 (existence oracle, not 403)") {
            testApplication {
                application { module() }
                val owner = createMember("article-cover-owner-${Uuid.random()}@example.org")
                val stranger = createMember("article-cover-stranger-${Uuid.random()}@example.org")
                val articleId = createArticle(authorId = owner)
                val response =
                    client.post("/api/articles/$articleId/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", stranger.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("unsupported content (GIF magic bytes) -> 415") {
            testApplication {
                application { module() }
                val author = createMember("article-cover-gif-${Uuid.random()}@example.org")
                val articleId = createArticle(authorId = author)
                val response =
                    client.post("/api/articles/$articleId/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", author.toString())
                        setBody(MultiPartFormDataContent(uploadFormData("GIF89a".toByteArray() + ByteArray(20))))
                    }
                response.status shouldBe HttpStatusCode.UnsupportedMediaType
            }
        }

        test("a perfectly valid SVG is still 415 (V1.9.21: SVG is accepted for the chapter crest ONLY)") {
            testApplication {
                application { module() }
                val author = createMember("article-cover-svg-${Uuid.random()}@example.org")
                val articleId = createArticle(authorId = author)
                val response =
                    client.post("/api/articles/$articleId/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", author.toString())
                        setBody(
                            MultiPartFormDataContent(
                                uploadFormData(
                                    "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><circle cx=\"5\" cy=\"5\" r=\"4\" fill=\"#c00\"/></svg>"
                                        .toByteArray(),
                                ),
                            ),
                        )
                    }
                response.status shouldBe HttpStatusCode.UnsupportedMediaType
            }
        }

        test("too small (below MIN_LONG_EDGE_PX/MIN_SHORT_EDGE_PX) -> 422") {
            testApplication {
                application { module() }
                val author = createMember("article-cover-toosmall-${Uuid.random()}@example.org")
                val articleId = createArticle(authorId = author)
                val response =
                    client.post("/api/articles/$articleId/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", author.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes(width = 100, height = 80))))
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test("no file part -> 400/415-family (no image at all)") {
            testApplication {
                application { module() }
                val author = createMember("article-cover-nofile-${Uuid.random()}@example.org")
                val articleId = createArticle(authorId = author)
                val response =
                    client.post("/api/articles/$articleId/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", author.toString())
                        setBody(MultiPartFormDataContent(formData { }))
                    }
                (response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.UnsupportedMediaType) shouldBe true
            }
        }

        test("replacing a cover deletes the previous file, keeps the same articleId in the URL") {
            testApplication {
                application { module() }
                val author = createMember("article-cover-replace-${Uuid.random()}@example.org")
                val articleId = createArticle(authorId = author)
                val first =
                    client.post("/api/articles/$articleId/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", author.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                first.status shouldBe HttpStatusCode.OK
                val firstCoverId =
                    transaction {
                        ArticleTable.selectAll().where { ArticleTable.id eq articleId }.single()[ArticleTable.coverImageId]
                    }!!

                val second =
                    client.post("/api/articles/$articleId/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", author.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes(width = 1200, height = 900))))
                    }
                second.status shouldBe HttpStatusCode.OK
                val secondCoverId =
                    transaction {
                        ArticleTable.selectAll().where { ArticleTable.id eq articleId }.single()[ArticleTable.coverImageId]
                    }!!
                (secondCoverId != firstCoverId) shouldBe true

                val oldFile = java.io.File("build/document-storage/article-covers/$firstCoverId.jpg")
                oldFile.exists() shouldBe false
            }
        }

        test("WICHTIG: the authenticated GET gives a foreign member and an anonymous caller the same 404") {
            testApplication {
                application { module() }
                val owner = createMember("article-cover-get-owner-${Uuid.random()}@example.org")
                val stranger = createMember("article-cover-get-stranger-${Uuid.random()}@example.org")
                val articleId = createArticle(authorId = owner, status = ArticleStatus.SUBMITTED, coverImageId = Uuid.random())

                val foreignResponse = client.get("/api/articles/$articleId/cover") { header("X-Member-Id", stranger.toString()) }
                foreignResponse.status shouldBe HttpStatusCode.NotFound

                val anonymousResponse = client.get("/api/articles/$articleId/cover")
                anonymousResponse.status shouldBe HttpStatusCode.NotFound
            }
        }

        // -- DELETE /api/articles/{id}/cover --

        test("DELETE removes the file from storage, clears cover_image_id, and returns coverImageUrl=null") {
            testApplication {
                application { module() }
                val author = createMember("article-cover-delete-${Uuid.random()}@example.org")
                val articleId = createArticle(authorId = author)
                val upload =
                    client.post("/api/articles/$articleId/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", author.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                upload.status shouldBe HttpStatusCode.OK
                val coverId =
                    transaction { ArticleTable.selectAll().where { ArticleTable.id eq articleId }.single()[ArticleTable.coverImageId] }!!
                val file = java.io.File("build/document-storage/article-covers/$coverId.jpg")
                file.exists() shouldBe true

                val response =
                    client.delete("/api/articles/$articleId/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", author.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                val dto = decodeResult(response.bodyAsText())
                dto.coverImageUrl shouldBe null
                file.exists() shouldBe false
                val stored =
                    transaction { ArticleTable.selectAll().where { ArticleTable.id eq articleId }.single()[ArticleTable.coverImageId] }
                stored shouldBe null
            }
        }

        test("DELETE SUBMITTED -> 409, DELETE PUBLISHED -> 409") {
            testApplication {
                application { module() }
                val author = createMember("article-cover-delete-submitted-${Uuid.random()}@example.org")
                val submittedId = createArticle(authorId = author, status = ArticleStatus.SUBMITTED, coverImageId = Uuid.random())
                val publishedId = createArticle(authorId = author, status = ArticleStatus.PUBLISHED, coverImageId = Uuid.random())

                val submittedResponse =
                    client.delete("/api/articles/$submittedId/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", author.toString())
                    }
                submittedResponse.status shouldBe HttpStatusCode.Conflict

                val publishedResponse =
                    client.delete("/api/articles/$publishedId/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", author.toString())
                    }
                publishedResponse.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("DELETE on a foreign member's article -> 404 (existence oracle, not 403)") {
            testApplication {
                application { module() }
                val owner = createMember("article-cover-delete-owner-${Uuid.random()}@example.org")
                val stranger = createMember("article-cover-delete-stranger-${Uuid.random()}@example.org")
                val articleId = createArticle(authorId = owner, coverImageId = Uuid.random())
                val response =
                    client.delete("/api/articles/$articleId/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", stranger.toString())
                    }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("DELETE with cross-site Origin -> 403") {
            testApplication {
                application { module() }
                val author = createMember("article-cover-delete-origin-${Uuid.random()}@example.org")
                val articleId = createArticle(authorId = author, coverImageId = Uuid.random())
                val response =
                    client.delete("/api/articles/$articleId/cover") {
                        header(HttpHeaders.Origin, "https://evil.example")
                        header("X-Member-Id", author.toString())
                    }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("WICHTIG: board sees 404 for a DRAFT article's cover, 200 for SUBMITTED") {
            testApplication {
                application { module() }
                val author = createMember("article-cover-get-author-${Uuid.random()}@example.org")
                val board = createMember("article-cover-get-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val draftId = createArticle(authorId = author, status = ArticleStatus.DRAFT)
                // Give the draft a cover directly (bypassing the upload route) so this test isolates
                // the GET route's own status-gate, not the upload route's own DRAFT/REJECTED gate.
                val coverId = Uuid.random()
                transaction { ArticleTable.update({ ArticleTable.id eq draftId }) { it[ArticleTable.coverImageId] = coverId } }

                val draftAsBoard = client.get("/api/articles/$draftId/cover") { header("X-Member-Id", board.toString()) }
                draftAsBoard.status shouldBe HttpStatusCode.NotFound

                val submittedId = createArticle(authorId = author, status = ArticleStatus.SUBMITTED, coverImageId = Uuid.random())
                // The GET route only serves bytes that exist on disk -- write a real file so the
                // status-gate itself (not "file missing on disk") is what this assertion isolates.
                val submittedCoverId =
                    transaction { ArticleTable.selectAll().where { ArticleTable.id eq submittedId }.single()[ArticleTable.coverImageId] }!!
                val storageRoot = java.io.File("build/document-storage/article-covers").apply { mkdirs() }
                storageRoot.resolve("$submittedCoverId.jpg").writeBytes(renderJpegBytes())

                val submittedAsBoard = client.get("/api/articles/$submittedId/cover") { header("X-Member-Id", board.toString()) }
                submittedAsBoard.status shouldBe HttpStatusCode.OK
            }
        }
    })
