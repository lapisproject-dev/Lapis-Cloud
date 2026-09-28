package network.lapis.cloud.server.articles

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.shared.domain.ArticleDraftInput
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Reine Exposed-Datenzugriffsschicht für `article` -- opens NO `transaction {}` of its own, same
 * convention every other `*Store` in this codebase follows (the caller -- `ArticleService` --
 * already has one open).
 */
internal object ArticleStore {
    private const val MAX_PAGE_SIZE = 200

    fun getOrNull(id: Uuid): ResultRow? = ArticleTable.selectAll().where { ArticleTable.id eq id }.singleOrNull()

    fun getOrThrow(id: Uuid): ResultRow = getOrNull(id) ?: throw NotFoundException("Article $id not found")

    /** `FOR UPDATE` row lock -- every transition-writing function in `ArticleService` takes this first. */
    fun lockForUpdate(id: Uuid): ResultRow? =
        ArticleTable
            .selectAll()
            .where { ArticleTable.id eq id }
            .forUpdate()
            .singleOrNull()

    /** Same as [lockForUpdate] but throws instead of returning `null` -- the common case, every caller already knows the id must exist. */
    fun lockForUpdateOrThrow(id: Uuid): ResultRow = lockForUpdate(id) ?: throw NotFoundException("Article $id not found")

    /** Own-article variant -- throws `NotFoundException` (not `ForbiddenException`) for a different author's article, same existence-oracle discipline the rest of this codebase applies to ownership checks. */
    fun requireOwnedRowForUpdate(
        id: Uuid,
        authorId: Uuid,
    ): ResultRow {
        val row = lockForUpdateOrThrow(id)
        if (row[ArticleTable.authorId] != authorId) throw NotFoundException("Article $id not found")
        return row
    }

    fun slugTaken(
        slug: String,
        excludingId: Uuid,
    ): Boolean =
        ArticleTable
            .selectAll()
            .where { (ArticleTable.slug eq slug) and (ArticleTable.id neq excludingId) }
            .limit(1)
            .any()

    fun listByAuthor(authorId: Uuid): List<ResultRow> =
        ArticleTable
            .selectAll()
            .where { ArticleTable.authorId eq authorId }
            .orderBy(ArticleTable.updatedAt to SortOrder.DESC)
            .toList()

    fun listSubmittedOldestFirst(): List<ResultRow> =
        ArticleTable
            .selectAll()
            .where { ArticleTable.status eq ArticleStatus.SUBMITTED }
            .orderBy(ArticleTable.submittedAt to SortOrder.ASC)
            .toList()

    fun listPublishedNewestFirst(limit: Int): List<ResultRow> =
        ArticleTable
            .selectAll()
            .where { ArticleTable.status eq ArticleStatus.PUBLISHED }
            .orderBy(ArticleTable.publishedAt to SortOrder.DESC)
            .limit(limit.coerceIn(1, MAX_PAGE_SIZE))
            .toList()

    fun getPublishedBySlugOrNull(slug: String): ResultRow? =
        ArticleTable
            .selectAll()
            .where { (ArticleTable.slug eq slug) and (ArticleTable.status eq ArticleStatus.PUBLISHED) }
            .singleOrNull()

    fun insertDraft(
        authorId: Uuid,
        input: ArticleDraftInput,
        now: LocalDateTime,
    ): Uuid {
        val id = Uuid.random()
        ArticleTable.insert {
            it[ArticleTable.id] = id
            it[title] = input.title
            it[excerpt] = input.excerpt
            it[body] = input.body
            it[ArticleTable.authorId] = authorId
            it[status] = ArticleStatus.DRAFT
            it[createdAt] = now
            it[updatedAt] = now
        }
        return id
    }

    fun updateDraft(
        id: Uuid,
        input: ArticleDraftInput,
        now: LocalDateTime,
    ) {
        ArticleTable.update({ ArticleTable.id eq id }) {
            it[title] = input.title
            it[excerpt] = input.excerpt
            it[body] = input.body
            it[updatedAt] = now
        }
    }

    fun deleteById(id: Uuid) {
        ArticleTable.deleteWhere { ArticleTable.id eq id }
    }
}
