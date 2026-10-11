package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * Welle V1.9.105 "Artikel-Import" -- outcome of one entry of an article import payload in the dry run. [SKIP_SLUG_EXISTS] entries are never
 * updated, only skipped; any [ERROR] entry blocks the whole commit.
 */
@Serializable
enum class ArticleImportRowStatus { CREATE, SKIP_SLUG_EXISTS, ERROR }

/**
 * One row of the article import preview. [reasons] holds fixed German error texts (never echoing payload content, only entry index and field
 * name), [hints] non-blocking remarks. [publishedAt] is the naive organization wall-clock value as it will be shown (`yyyy-MM-ddTHH:mm`,
 * not converted, hence a plain string); [byline] is the author line taken from the payload (shown to the board so it can check for personal names).
 */
@Serializable
data class ArticleImportPreviewRowDto(
    val index: Int,
    val slug: String?,
    val title: String?,
    val publishedAt: String?,
    val byline: String?,
    val status: ArticleImportRowStatus,
    val reasons: List<String>,
    val hints: List<String>,
)

/**
 * Dry-run result. [payloadSha256] is a checksum over the UTF-8 bytes of the exact payload string; the commit call must send the same string
 * plus this hash (protection against an accidental edit between preview and commit, NOT a security boundary).
 */
@Serializable
data class ArticleImportPreviewDto(
    val rows: List<ArticleImportPreviewRowDto>,
    val createCount: Int,
    val skipCount: Int,
    val errorCount: Int,
    val payloadSha256: String,
    val timeZoneId: String,
)

/**
 * [importBatchId] (V1.9.106) identifies this import run (UUID string; `null` if nothing was created). The image upload that may follow
 * (`POST /api/articles/import/images/commit`) addresses exactly the articles of this run through it.
 */
@Serializable
data class ArticleImportResultDto(
    val created: Int,
    val skipped: Int,
    val skippedSlugs: List<String>,
    val importBatchId: String? = null,
)

/** Welle V1.9.106 -- why one article of a bulk approval was NOT approved. */
@Serializable
enum class ArticleBulkSkipReason { OWN_IMPORT, NOT_IMPORTED, NOT_SUBMITTED, NOT_FOUND }

/**
 * One article a bulk approval left alone. [title] is returned to the calling board member only (for the plain-text list in the UI); it never
 * reaches the audit log. `null` for [ArticleBulkSkipReason.NOT_FOUND].
 */
@Serializable
data class ArticleBulkSkipDto(
    val articleId: String,
    val title: String?,
    val reason: ArticleBulkSkipReason,
)

/** Welle V1.9.106 -- outcome of [network.lapis.cloud.shared.rpc.IArticleImportService.approveImportedArticles]; skipped entries are in request order. */
@Serializable
data class ArticleBulkApproveResultDto(
    val approvedCount: Int,
    val approvedIds: List<String>,
    val skipped: List<ArticleBulkSkipDto>,
)

/** Welle V1.9.106 -- state of one image of the image ZIP in the preview and in the commit result. */
@Serializable
enum class ArticleImportImageStatus { OK, TOO_SMALL, INVALID_FORMAT, MISSING, IGNORED_EXISTING_ARTICLE, DUPLICATE, NO_IMAGE_PACKAGE }

/** One row of the image preview/result: [width]/[height] are set for [ArticleImportImageStatus.OK] (the stored size) and [ArticleImportImageStatus.TOO_SMALL] (the oriented original). */
@Serializable
data class ArticleImportImageRowDto(
    val slug: String,
    val status: ArticleImportImageStatus,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * Welle V1.9.106 -- preview of an image ZIP (`POST /api/articles/import/images/preview`). [unmatchedFiles] are the (already validated) names of ZIP
 * images that belong to no article of the import, at most 100; [unmatchedCount] their number; [ignoredSystemFiles] the count of `__MACOSX`/`.DS_Store` entries.
 */
@Serializable
data class ArticleImportImagesPreviewDto(
    val rows: List<ArticleImportImageRowDto>,
    val unmatchedFiles: List<String>,
    val unmatchedCount: Int,
    val ignoredSystemFiles: Int,
)

/** Welle V1.9.106 -- result of an image commit (`POST /api/articles/import/images/commit`): [applied] covers stored, [rejected] images that were refused. */
@Serializable
data class ArticleImportImagesResultDto(
    val applied: Int,
    val rejected: Int,
    val rows: List<ArticleImportImageRowDto>,
)
