package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.articles.ArticleBulkApprover
import network.lapis.cloud.server.articles.ArticleImporter
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleBulkApproveResultDto
import network.lapis.cloud.shared.domain.ArticleImportPreviewDto
import network.lapis.cloud.shared.domain.ArticleImportResultDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IArticleImportService
import kotlin.uuid.Uuid

/**
 * Welle V1.9.105 -- admin import of legacy articles (see [IArticleImportService], `docs/api/article-import.adoc`).
 *
 * **Authorization is the FIRST statement of every method** (a source-scanning tripwire pins that): BOARD/ADMIN only, before any parsing,
 * hashing or rate-limit bookkeeping. The request body is size-limited BEFORE deserialization by [installArticleImportBodyLimit].
 */
class ArticleImportService(
    private val call: ApplicationCall,
    private val previewRateLimiter: FederationInboxRateLimiter,
    private val commitRateLimiter: FederationInboxRateLimiter,
    private val bulkApproveRateLimiter: FederationInboxRateLimiter,
) : IArticleImportService {
    override suspend fun previewArticleImport(
        json: String,
        organizationLabels: List<String>,
    ): ArticleImportPreviewDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)
        requireWithinRate(limiter = previewRateLimiter, memberId = current.memberId)
        val wallNow = OrganizationTimeZone.wallNowOf(DbClock.nowLocalDateTime())
        return ArticleImporter.preview(
            json = json,
            organizationLabels = organizationLabels,
            wallNow = wallNow,
            timeZoneId = OrganizationTimeZone.current().id,
        )
    }

    override suspend fun commitArticleImport(
        json: String,
        payloadSha256: String,
    ): ArticleImportResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)
        requireWithinRate(limiter = commitRateLimiter, memberId = current.memberId)
        val now = DbClock.nowLocalDateTime()
        return ArticleImporter.commit(
            json = json,
            payloadSha256 = payloadSha256,
            actorMemberId = current.memberId,
            actorRole = current.role,
            now = now,
            wallNow = OrganizationTimeZone.wallNowOf(now),
            orgZone = OrganizationTimeZone.current(),
        )
    }

    override suspend fun approveImportedArticles(articleIds: List<String>): ArticleBulkApproveResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)
        requireWithinRate(limiter = bulkApproveRateLimiter, memberId = current.memberId)
        if (articleIds.isEmpty()) throw BadRequestException("Keine Artikel angegeben.")
        // Counted BEFORE duplicates are removed and never truncated: a client that sends more than it can show is a bug, not something to hide.
        if (articleIds.size > ArticleBulkApprover.MAX_IDS_PER_CALL) {
            throw BadRequestException("Höchstens ${ArticleBulkApprover.MAX_IDS_PER_CALL} Artikel je Aufruf.")
        }
        val ids =
            articleIds
                .map { raw -> runCatching { Uuid.parse(raw) }.getOrElse { throw BadRequestException("Ungültige Artikelkennung.") } }
                .distinct()
        return ArticleBulkApprover.approve(
            ids = ids,
            actorMemberId = current.memberId,
            actorRole = current.role,
            now = DbClock.nowLocalDateTime(),
        )
    }

    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        memberId: Uuid,
    ) {
        if (!limiter.checkAndRecord("member:$memberId")) {
            throw ConflictException("Zu viele Anfragen -- bitte später erneut versuchen.")
        }
    }
}
