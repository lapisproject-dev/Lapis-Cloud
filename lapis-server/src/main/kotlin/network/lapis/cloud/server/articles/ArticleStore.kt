package network.lapis.cloud.server.articles

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.shared.domain.ArticleBylineKind
import network.lapis.cloud.shared.domain.ArticleDraftInput
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
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
        // V1.9.105: an imported article has no human author in Lapis (author_id is the importing board member, who only executed the import) --
        // it is never "own", so it can neither be edited nor submitted through the author path.
        if (row[ArticleTable.authorId] != authorId || row[ArticleTable.imported]) throw NotFoundException("Article $id not found")
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
            .where { (ArticleTable.authorId eq authorId) and (ArticleTable.imported eq false) }
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
                (ArticleTable.authorId eq authorId) and (ArticleTable.status neq ArticleStatus.PUBLISHED) and
                    (ArticleTable.imported eq false)
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

    /**
     * Welle V1.9.105 -- one page of the public archive feed: PUBLISHED only, newest first, stable on equal `published_at` through `id`.
     * Returns up to `limit + 1` rows (the caller uses the extra row for `hasMore`). [limit] and [offset] are bounded by the caller.
     */
    fun listPublishedArchivePage(
        limit: Int,
        offset: Long,
    ): List<ResultRow> =
        ArticleTable
            .selectAll()
            .where { ArticleTable.status eq ArticleStatus.PUBLISHED }
            .orderBy(ArticleTable.publishedAt to SortOrder.DESC, ArticleTable.id to SortOrder.DESC)
            .limit(limit.coerceIn(1, MAX_PAGE_SIZE) + 1)
            .offset(offset.coerceAtLeast(0L))
            .toList()

    /** Welle V1.9.105 -- slugs that exist in ANY status (a draft or rejected article blocks its slug too). One batched lookup. */
    fun existingSlugs(slugs: Collection<String>): Set<String> {
        if (slugs.isEmpty()) return emptySet()
        return ArticleTable
            .select(ArticleTable.slug)
            .where { ArticleTable.slug inList slugs.toList() }
            .mapNotNull { it[ArticleTable.slug] }
            .toSet()
    }

    /**
     * Welle V1.9.105 -- the ONLY place that writes `imported = true` (a source-scanning tripwire pins that). The row is `SUBMITTED` (four-eyes: a
     * DIFFERENT board member approves it; it is never published directly) with the original [publishedAt] (UTC system timestamp), the author line
     * of kind `IMPORTED` (if any), and NO cover. [importerId] is the importing board member: stored as `author_id`/`byline_set_by` only because the
     * schema needs a member -- they executed the import, they did not write the article. Every row of one import call shares [importBatchId]
     * (V1.9.106), which the image commit and the audit use to address exactly that run.
     */
    fun insertImported(
        id: Uuid,
        importerId: Uuid,
        slug: String,
        title: String,
        excerpt: String,
        body: String,
        publishedAt: LocalDateTime,
        byline: String?,
        importBatchId: Uuid,
        now: LocalDateTime,
    ) {
        ArticleTable.insert {
            it[ArticleTable.id] = id
            it[ArticleTable.slug] = slug
            it[ArticleTable.title] = title
            it[ArticleTable.excerpt] = excerpt
            it[ArticleTable.body] = body
            it[authorId] = importerId
            // Four-eyes: an import NEVER publishes directly. It lands as SUBMITTED; a DIFFERENT board member approves it through the normal
            // review flow (`approveArticle` keeps the historic published_at of an imported article).
            it[status] = ArticleStatus.SUBMITTED
            it[submittedAt] = now
            it[reviewedBy] = null
            it[reviewedAt] = null
            it[ArticleTable.publishedAt] = publishedAt
            it[createdAt] = now
            it[updatedAt] = now
            it[ArticleTable.byline] = byline
            it[bylineKind] = if (byline != null) ArticleBylineKind.IMPORTED else null
            it[bylineSetBy] = if (byline != null) importerId else null
            it[bylineSetAt] = if (byline != null) now else null
            it[imported] = true
            it[ArticleTable.importBatchId] = importBatchId
        }
    }

    /** One imported article of an import run, as the image commit sees it. */
    data class ImportBatchTarget(
        val id: Uuid,
        val slug: String,
        val imported: Boolean,
        val hasCover: Boolean,
    )

    /**
     * Welle V1.9.106 -- the articles of ONE import run (`import_batch_id`), imported rows only (an unknown or foreign batch id yields an empty
     * list, which the image commit treats as "nothing to do"). Capped, ordered by id (the lock order of the commit).
     */
    fun importBatchTargets(batchId: Uuid): List<ImportBatchTarget> =
        ArticleTable
            .selectAll()
            .where { (ArticleTable.importBatchId eq batchId) and (ArticleTable.imported eq true) }
            .orderBy(ArticleTable.id)
            .limit(MAX_PAGE_SIZE * 5)
            .mapNotNull { row ->
                row[ArticleTable.slug]?.let {
                    ImportBatchTarget(
                        id = row[ArticleTable.id],
                        slug = it,
                        // the ONLY source of the flag that picks the cover limits of a batch target (a tripwire pins it)
                        imported = row[ArticleTable.imported],
                        hasCover = row[ArticleTable.coverImageId] != null,
                    )
                }
            }

    /** Welle V1.9.105 -- writes the five byline columns of [id] (all `null` = no byline). Caller holds the row lock. */
    fun updateByline(
        id: Uuid,
        byline: String?,
        kind: ArticleBylineKind?,
        consentAt: LocalDateTime?,
        setBy: Uuid,
        now: LocalDateTime,
    ) {
        ArticleTable.update({ ArticleTable.id eq id }) {
            it[ArticleTable.byline] = byline
            it[bylineKind] = kind
            it[bylineConsentAt] = consentAt
            it[bylineSetBy] = setBy
            it[bylineSetAt] = now
            it[updatedAt] = now
        }
    }

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

    /**
     * Welle V1.9.100 -- the ONLY insert `McpArticleDraftStore` uses. `status` is hard-wired to
     * `DRAFT`; `slug`, `coverImageId`, `submittedAt` and `publishedAt` are never touched.
     * [agentLabel] is a snapshot of the MCP connection label and stays on the row for good:
     * [insertDraft]/[updateDraft] never write `mcp_agent_label`, so a manual edit does not clear it.
     */
    fun insertMcpDraft(
        authorId: Uuid,
        title: String,
        excerpt: String,
        body: String,
        agentLabel: String,
        now: LocalDateTime,
    ): Uuid {
        val id = Uuid.random()
        ArticleTable.insert {
            it[ArticleTable.id] = id
            it[ArticleTable.title] = title
            it[ArticleTable.excerpt] = excerpt
            it[ArticleTable.body] = body
            it[ArticleTable.authorId] = authorId
            it[status] = ArticleStatus.DRAFT
            it[mcpAgentLabel] = agentLabel
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
