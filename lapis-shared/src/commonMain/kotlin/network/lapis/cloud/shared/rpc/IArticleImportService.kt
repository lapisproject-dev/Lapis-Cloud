package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.ArticleImportPreviewDto
import network.lapis.cloud.shared.domain.ArticleImportResultDto

/**
 * Welle V1.9.105 "Artikel-Import" -- admin import of legacy articles from a JSON payload (see `docs/api/article-import.adoc`). Deliberately its
 * OWN RPC interface, not more methods on [IArticleService]: the request path gets a dedicated body-size guard before deserialization.
 *
 * Role: BOARD/ADMIN for every method. Imported articles are created as `PUBLISHED` with the original publication date and a byline of kind
 * `IMPORTED`, without a title image; their slug must not exist yet (an existing slug is skipped, never updated).
 */
@RpcService
interface IArticleImportService {
    /**
     * Dry run: parses and validates [json], writes nothing. [organizationLabels] (at most 5, 1..120 characters each) are the names the board
     * accepts as organization bylines; a byline that equals none of them (case-insensitive) is flagged as "may be a personal name".
     */
    suspend fun previewArticleImport(
        json: String,
        organizationLabels: List<String>,
    ): ArticleImportPreviewDto

    /**
     * Writes all valid entries in ONE transaction (all or nothing). [payloadSha256] must be the hash returned by the preview for the very same
     * [json] string, otherwise a `ConflictException` is thrown and nothing is written.
     */
    suspend fun commitArticleImport(
        json: String,
        payloadSha256: String,
    ): ArticleImportResultDto
}
