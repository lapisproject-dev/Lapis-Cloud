package network.lapis.cloud.server.articles

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.string.shouldStartWith
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val BASE_URL = "https://test.invalid"

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- covers [ArticleCoverPolicy
 * .coverImageUrlFor]'s PUBLISHED-vs-every-other-status URL-shape decision (class KDoc). No
 * existing test exercised this directly before -- `ArticleServiceTest`/`ArticleCoverRoutesTest`
 * only ever assert on `ArticleCoverResultDto`/route status codes, never on
 * [ArticleDto.coverImageUrl] itself across a status transition.
 */
class ArticleCoverPolicyTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdArticleIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdArticleIds.isNotEmpty()) ArticleTable.deleteWhere { id inList createdArticleIds }
                if (createdMemberIds.isNotEmpty()) MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        fun createMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "ArticleCoverPolicyTest Mitglied"
                    it[email] = "article-cover-policy-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        fun rowFor(
            status: ArticleStatus,
            slug: String?,
            coverImageId: Uuid?,
        ): org.jetbrains.exposed.v1.core.ResultRow {
            val author = createMember()
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ArticleTable.insert {
                    it[ArticleTable.id] = id
                    it[ArticleTable.slug] = slug
                    it[title] = "Cover-Policy-Test"
                    it[excerpt] = "Auszug"
                    it[body] = "Inhalt"
                    it[ArticleTable.coverImageId] = coverImageId
                    it[authorId] = author
                    it[ArticleTable.status] = status
                    it[submittedAt] = if (status != ArticleStatus.DRAFT) now else null
                    it[reviewedBy] = null
                    it[reviewedAt] = null
                    it[rejectionReason] = null
                    it[publishedAt] = if (status == ArticleStatus.PUBLISHED) now else null
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            createdArticleIds += id
            return transaction { ArticleTable.selectAll().where { ArticleTable.id eq id }.single() }
        }

        test("no cover set (any status) -> null, regardless of slug/PUBLISHED") {
            val row = rowFor(status = ArticleStatus.PUBLISHED, slug = "no-cover-published", coverImageId = null)
            ArticleCoverPolicy.coverImageUrlFor(baseUrl = BASE_URL, row = row).shouldBeNull()
        }

        test("PUBLISHED with a slug -> the public /aktuelles/{slug}/bild URL") {
            val coverId = Uuid.random()
            val row = rowFor(status = ArticleStatus.PUBLISHED, slug = "veroeffentlicht-mit-cover", coverImageId = coverId)
            val url = ArticleCoverPolicy.coverImageUrlFor(baseUrl = BASE_URL, row = row)
            url.shouldStartWith("$BASE_URL/aktuelles/veroeffentlicht-mit-cover/bild?v=")
        }

        test("DRAFT/SUBMITTED/REJECTED with a cover -> the authenticated /api/articles/{id}/cover URL, never the public one") {
            val coverId = Uuid.random()
            listOf(ArticleStatus.DRAFT, ArticleStatus.SUBMITTED, ArticleStatus.REJECTED).forEach { status ->
                val row = rowFor(status = status, slug = null, coverImageId = coverId)
                val url = ArticleCoverPolicy.coverImageUrlFor(baseUrl = BASE_URL, row = row)
                url.shouldStartWith("$BASE_URL/api/articles/${row[ArticleTable.id]}/cover?v=")
            }
        }

        test("PUBLISHED-but-slug-less (cannot happen by construction, handled defensively) -> falls back to the authenticated URL") {
            val coverId = Uuid.random()
            val row = rowFor(status = ArticleStatus.PUBLISHED, slug = null, coverImageId = coverId)
            val url = ArticleCoverPolicy.coverImageUrlFor(baseUrl = BASE_URL, row = row)
            url.shouldStartWith("$BASE_URL/api/articles/${row[ArticleTable.id]}/cover?v=")
        }
    })
