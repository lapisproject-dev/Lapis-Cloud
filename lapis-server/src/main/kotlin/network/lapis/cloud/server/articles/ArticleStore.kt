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
    /** Welle V1.4.36 -- widened from `private` to `internal` so `ArticleService.listSubmittedArticles`/`.listPublishedArticles` can cap against the SAME constant this store already enforces on [listPublishedNewestFirst], rather than a second, independently-chosen number drifting from it. */
    internal const val MAX_PAGE_SIZE = 200

    /**
     * Security fix (post-V1.4.36 review, DoS finding): hard ceiling on how many non-`PUBLISHED`
     * rows a single author may hold at once -- enforced in `ArticleService.saveDraft`'s `id == null`
     * (create) branch via [countEditableByAuthor], BEFORE [insertDraft] runs. Without this an active
     * member could script an unbounded loop of draft creations (each row up to
     * `ArticlePolicy.BODY_MAX` = 100 000 body characters) and fill the shared PostgreSQL volume.
     *
     * Security fix (round-2 finding, post-review): the round-1 version of this cap counted only
     * `DRAFT`/`REJECTED` rows and excluded `SUBMITTED`, with the KDoc claiming `SUBMITTED` rows
     * "already require board review to be created". That claim was false -- `submitArticle` is
     * called by the AUTHOR alone (`requireOwnedRowForUpdate` + `validateForSubmit`, no board
     * involvement, no rate limit, no cap of its own). An author could loop
     * `saveDraft(null, ...)` + `submitArticle(id)`, moving each row from `DRAFT` straight to
     * `SUBMITTED` so it never counted against the cap, bypassing it entirely (bounded only by
     * `draftCreateRateLimiter`, i.e. still ~2,880 rows/day/account with no ceiling over time) --
     * and also flooding the board's `listSubmittedOldestFirst` review queue, pushing real
     * submissions out of its capped, oldest-first window. `withdrawArticle` (`SUBMITTED` ->
     * `DRAFT`) then broke the cap's own invariant on top of that, since it could turn an unbounded
     * pile of `SUBMITTED` rows back into an unbounded pile of `DRAFT` rows.
     *
     * Fix: [countEditableByAuthor] now counts every row that is NOT `PUBLISHED` -- i.e.
     * `DRAFT` + `REJECTED` + `SUBMITTED`. Moving a row between these three statuses
     * (`submitArticle`/`withdrawArticle`) never changes an author's non-`PUBLISHED` row count, so
     * neither transition can be used to escape the cap. Only `approveArticle` (board-gated, Vier-
     * Augen-Prinzip, an author can never approve their own article) removes a row from this count
     * by moving it to `PUBLISHED`.
     */
    internal const val MAX_DRAFTS_PER_AUTHOR = 100

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

    /**
     * Security fix (post-V1.4.36 review, DoS finding): capped at [MAX_PAGE_SIZE], same discipline
     * [listSubmittedOldestFirst]/[listPublishedNewestFirst] already enforce -- previously unbounded,
     * so a member with a few thousand rows (see [MAX_DRAFTS_PER_AUTHOR] KDoc for how that becomes
     * possible without the create-side cap) would force the server to build a response of hundreds
     * of MB, including every row's full `body`, on a single `listMyArticles` call.
     */
    fun listByAuthor(authorId: Uuid): List<ResultRow> =
        ArticleTable
            .selectAll()
            .where { ArticleTable.authorId eq authorId }
            .orderBy(ArticleTable.updatedAt to SortOrder.DESC)
            .limit(MAX_PAGE_SIZE)
            .toList()

    /**
     * Security fix (post-V1.4.36 review, round 2): backs the [MAX_DRAFTS_PER_AUTHOR] cap
     * `ArticleService.saveDraft` enforces on new-draft creation -- counts every row NOT
     * `PUBLISHED` (`DRAFT` + `REJECTED` + `SUBMITTED`), see that constant's KDoc for why the
     * round-1 version (which excluded `SUBMITTED`) was bypassable via submit/withdraw.
     */
    fun countEditableByAuthor(authorId: Uuid): Long =
        ArticleTable
            .selectAll()
            .where {
                (ArticleTable.authorId eq authorId) and (ArticleTable.status neq ArticleStatus.PUBLISHED)
            }.count()

    /** Welle V1.4.36 -- deckelt auf [MAX_PAGE_SIZE], gleiche Disziplin wie [listPublishedNewestFirst] (vorher unbegrenzt). */
    fun listSubmittedOldestFirst(limit: Int = MAX_PAGE_SIZE): List<ResultRow> =
        ArticleTable
            .selectAll()
            .where { ArticleTable.status eq ArticleStatus.SUBMITTED }
            .orderBy(ArticleTable.submittedAt to SortOrder.ASC)
            .limit(limit.coerceIn(1, MAX_PAGE_SIZE))
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

    /** Welle V1.4.36 -- mirrors `EventStore.setCoverImageId`: returns the PREVIOUS `cover_image_id` (may be `null`) so the caller can delete the now-orphaned file, same "Optional-shaped result" discipline `ArticleCoverRoutes`'/`EventCoverRoutes`' own `SetCoverOutcome` establishes at the route layer. Caller already holds a `FOR UPDATE` lock on [id] (see `ArticleCoverRoutes`). */
    fun setCoverImageId(
        id: Uuid,
        coverImageId: Uuid?,
    ): Uuid? {
        val previous = getOrNull(id)?.get(ArticleTable.coverImageId)
        ArticleTable.update({ ArticleTable.id eq id }) {
            it[ArticleTable.coverImageId] = coverImageId
        }
        return previous
    }

    /** Welle V1.4.36 -- convenience for the public cover-image route: `null` for an unknown slug, a non-PUBLISHED article, or a PUBLISHED article with no cover set -- the caller (`ArticlePublicRoutes`) folds all three into the identical 404, so this function deliberately does not distinguish them either. */
    fun getCoverImageIdForPublishedSlug(slug: String): Uuid? = getPublishedBySlugOrNull(slug)?.get(ArticleTable.coverImageId)

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
