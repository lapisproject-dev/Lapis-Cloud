package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.module
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.uuid.Uuid

private const val LIMITS_TEST_ORIGIN = "http://localhost:8080"

private fun jpegBytes(
    width: Int,
    height: Int,
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, width, height)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
}

private fun coverForm(bytes: ByteArray) =
    formData { append("file", bytes, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"cover.jpg\"") }) }

/**
 * Welle V1.9.106 -- the 300 px minimum width of a cover upload depends on the article row's `imported` flag only: a board member uploading a
 * 300 x 200 image to an IMPORTED article succeeds (stored 300 x 200, not upscaled), the same image on an ordinary draft is the unchanged 422
 * "mindestens 800x600px", and 299 x 200 is refused on an imported article too.
 */
class ArticleCoverLimitsRoutesTest :
    FunSpec({
        val articleIds = mutableListOf<Uuid>()

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
                    val root = File("build/document-storage/article-covers")
                    covers.forEach {
                        root.resolve("$it.jpg").delete()
                        root.resolve("$it.png").delete()
                    }
                }
                // The members stay: the cover change of an imported article is audited (hash-chained, append-only) and references its actor.
            }
        }

        // The seeded demo members (the H2 database is shared with specs that count members, and the audit log keeps referencing its actors).
        fun member(role: AccountRole): Uuid = DevSeedData.demoMembers.first { it.role == role }.id

        fun article(
            authorId: Uuid,
            imported: Boolean,
            status: ArticleStatus,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ArticleTable.insert {
                    it[ArticleTable.id] = id
                    it[slug] = if (imported) "cover-limits-$id" else null
                    it[title] = "Cover-Limits"
                    it[excerpt] = "Auszug"
                    it[body] = "Inhalt"
                    it[ArticleTable.authorId] = authorId
                    it[ArticleTable.status] = status
                    it[submittedAt] = if (status != ArticleStatus.DRAFT) now else null
                    it[createdAt] = now
                    it[updatedAt] = now
                    it[ArticleTable.imported] = imported
                }
            }
            articleIds += id
            return id
        }

        test("an imported article takes a 300 x 200 cover (stored unscaled); an ordinary draft keeps the 800x600 rule and the same text") {
            testApplication {
                application { module() }
                val board = member(AccountRole.BOARD)
                val importedId = article(authorId = board, imported = true, status = ArticleStatus.SUBMITTED)
                val draftId = article(authorId = board, imported = false, status = ArticleStatus.DRAFT)

                val ok =
                    client.post("/api/articles/$importedId/cover") {
                        header(HttpHeaders.Origin, LIMITS_TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(coverForm(jpegBytes(width = 300, height = 200))))
                    }
                ok.status shouldBe HttpStatusCode.OK
                val coverId =
                    transaction {
                        ArticleTable.selectAll().where { ArticleTable.id eq importedId }.single()[ArticleTable.coverImageId]
                    }!!
                val stored = File("build/document-storage/article-covers").resolve("$coverId.jpg")
                val decoded = ImageIO.read(ByteArrayInputStream(stored.readBytes()))
                decoded.width shouldBe 300
                decoded.height shouldBe 200

                val rejected =
                    client.post("/api/articles/$draftId/cover") {
                        header(HttpHeaders.Origin, LIMITS_TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(coverForm(jpegBytes(width = 300, height = 200))))
                    }
                rejected.status shouldBe HttpStatusCode.UnprocessableEntity
                rejected.bodyAsText() shouldContain "mindestens 800x600px, je nach Ausrichtung"
            }
        }

        test("299 x 200 is refused on an imported article with the imported-limits text") {
            testApplication {
                application { module() }
                val board = member(AccountRole.BOARD)
                val importedId = article(authorId = board, imported = true, status = ArticleStatus.SUBMITTED)
                val response =
                    client.post("/api/articles/$importedId/cover") {
                        header(HttpHeaders.Origin, LIMITS_TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(coverForm(jpegBytes(width = 299, height = 200))))
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.bodyAsText() shouldContain "mindestens 300 px breit"
            }
        }
    })
