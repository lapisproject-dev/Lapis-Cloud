package network.lapis.cloud.server.articles

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.relaxSessionTimeouts
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleBulkApproveResultDto
import network.lapis.cloud.shared.domain.ArticleBulkSkipDto
import network.lapis.cloud.shared.domain.ArticleBulkSkipReason
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.106 -- approves many IMPORTED, `SUBMITTED` articles in ONE transaction (alles oder nichts bei einem Datenbankfehler; fachliche
 * Ausnahmen sind keine Fehler, sondern Einträge der Skip-Liste). The single caller is `ArticleImportService.approveImportedArticles`, which has
 * already authorized the caller (BOARD/ADMIN) and rate-limited the call.
 *
 * **Same four-eyes rule as `ArticleService.approveArticle`**, through the one shared predicate
 * [ArticleEditorialAccess.isOwnArticleForReview], evaluated against the FRESHLY row-locked row: the member who ran an import never approves it.
 * Rows are locked in ascending id order (`ORDER BY id ... FOR UPDATE`) so two concurrent bulk approvals over overlapping ids cannot deadlock;
 * the loser of a race finds the row already `PUBLISHED` and reports it as [ArticleBulkSkipReason.NOT_SUBMITTED].
 *
 * **No external effect:** no mail per article (a bulk approval of imports would be a mail flood to a board member who is not even the author),
 * no webhook. A source-scanning tripwire pins that this file references no notifier.
 *
 * **Audit:** exactly ONE `ARTICLE_BULK_APPROVED` entry, and only if at least one article was approved (a repeated call stays silent). It carries
 * counts, the import batch ids and the approved article ids -- never a title, a text or a byline. Written last: `AuditLogRecorder.record` takes
 * the chain-state row lock and holds it until commit.
 */
internal object ArticleBulkApprover {
    const val MAX_IDS_PER_CALL = 100

    /** DB timeouts for the transaction, applied per transaction via `SET LOCAL` (Postgres only; see `relaxSessionTimeouts`). */
    private const val STATEMENT_TIMEOUT_MS = 30_000L
    private const val IDLE_TX_TIMEOUT_MS = 30_000L
    private const val LOCK_TIMEOUT_MS = 5_000L

    /** At most this many distinct batch ids are listed in the audit entry; beyond it only their count is written. */
    private const val MAX_BATCH_IDS_IN_AUDIT = 10

    /** [ids] is deduplicated (first occurrence wins) by the caller or here; the skip list keeps request order. */
    fun approve(
        ids: List<Uuid>,
        actorMemberId: Uuid,
        actorRole: AccountRole,
        now: LocalDateTime,
    ): ArticleBulkApproveResultDto {
        val requested = ids.distinct()
        return transaction {
            relaxSessionTimeouts(
                statementTimeoutMs = STATEMENT_TIMEOUT_MS,
                idleInTransactionTimeoutMs = IDLE_TX_TIMEOUT_MS,
                lockTimeoutMs = LOCK_TIMEOUT_MS,
            )
            val rows =
                ArticleTable
                    .selectAll()
                    .where { ArticleTable.id inList requested }
                    .orderBy(ArticleTable.id)
                    .forUpdate()
                    .associateBy { it[ArticleTable.id] }
            val approved = mutableListOf<Uuid>()
            val batchIds = linkedSetOf<Uuid>()
            val skipped = mutableListOf<ArticleBulkSkipDto>()

            fun skip(
                id: Uuid,
                title: String?,
                reason: ArticleBulkSkipReason,
            ) {
                skipped += ArticleBulkSkipDto(articleId = id.toString(), title = title, reason = reason)
            }
            for (id in requested) {
                val row = rows[id]
                if (row == null) {
                    skip(id, null, ArticleBulkSkipReason.NOT_FOUND)
                    continue
                }
                val title = row[ArticleTable.title]
                when {
                    !row[ArticleTable.imported] -> skip(id, title, ArticleBulkSkipReason.NOT_IMPORTED)
                    row[ArticleTable.status] != ArticleStatus.SUBMITTED -> skip(id, title, ArticleBulkSkipReason.NOT_SUBMITTED)
                    ArticleEditorialAccess.isOwnArticleForReview(row = row, memberId = actorMemberId) ->
                        skip(id, title, ArticleBulkSkipReason.OWN_IMPORT)
                    else -> {
                        val slug =
                            row[ArticleTable.slug] ?: ArticlePolicy.slugFor(title = title) { candidate ->
                                ArticleStore.slugTaken(slug = candidate, excludingId = id)
                            }
                        val updated =
                            ArticleTable.update({
                                (ArticleTable.id eq id) and (ArticleTable.status eq ArticleStatus.SUBMITTED) and
                                    (ArticleTable.imported eq true)
                            }) {
                                it[status] = ArticleStatus.PUBLISHED
                                it[ArticleTable.slug] = slug
                                it[reviewedBy] = actorMemberId
                                it[reviewedAt] = now
                                // The historic publication date of the legacy article stays; only a missing one is set (same rule as approveArticle).
                                if (row[ArticleTable.publishedAt] == null) it[publishedAt] = now
                                it[updatedAt] = now
                            }
                        if (updated == 0) {
                            skip(id, title, ArticleBulkSkipReason.NOT_SUBMITTED)
                        } else {
                            approved += id
                            row[ArticleTable.importBatchId]?.let { batchIds += it }
                        }
                    }
                }
            }
            if (approved.isNotEmpty()) {
                AuditLogRecorder.record(
                    actorMemberId = actorMemberId,
                    actorRole = actorRole,
                    entityType = AuditEntityType.ARTICLE_BULK_APPROVED,
                    entityId = Uuid.random(),
                    action = AuditAction.UPDATE,
                    after =
                        buildJsonObject {
                            put("approvedCount", approved.size)
                            put("skippedCount", skipped.size)
                            put("importBatchCount", batchIds.size)
                            if (batchIds.size <= MAX_BATCH_IDS_IN_AUDIT) {
                                put("importBatchIds", JsonArray(batchIds.map { JsonPrimitive(it.toString()) }))
                            }
                            put("approvedArticleIds", JsonArray(approved.map { JsonPrimitive(it.toString()) }))
                        }.toString(),
                    occurredAt = now,
                )
            }
            ArticleBulkApproveResultDto(
                approvedCount = approved.size,
                approvedIds = approved.map { it.toString() },
                skipped = skipped,
            )
        }
    }
}
