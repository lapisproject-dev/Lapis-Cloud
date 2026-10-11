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

@Serializable
data class ArticleImportResultDto(
    val created: Int,
    val skipped: Int,
    val skippedSlugs: List<String>,
)
