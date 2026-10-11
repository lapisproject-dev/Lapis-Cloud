package network.lapis.cloud.server.articles

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.relaxSessionTimeouts
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleImportPreviewDto
import network.lapis.cloud.shared.domain.ArticleImportPreviewRowDto
import network.lapis.cloud.shared.domain.ArticleImportResultDto
import network.lapis.cloud.shared.domain.ArticleImportRowStatus
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * Welle V1.9.105 -- database side of the admin import of legacy ARTICLES. Validation lives in [ArticleImportPolicy] (pure); this class adds
 * the slug lookup and the single write transaction. Mirrors `EventImporter`.
 *
 * **The ONLY caller of `ArticleStore.insertImported`** (a source-scanning tripwire pins that). No network access of any kind (no HTTP client,
 * no mail, no webhook): there is no image field, covers are uploaded afterwards through the normal pipeline.
 *
 * **Audit never carries content**: per article only `{"imported":true,"slug":...}`, per run only counts and the payload checksum -- no title,
 * no body, no byline.
 */
internal object ArticleImporter {
    /** DB timeouts for the commit transaction, applied per transaction via `SET LOCAL` (Postgres only; see `relaxSessionTimeouts`). */
    private const val STATEMENT_TIMEOUT_MS = 30_000L
    private const val IDLE_TX_TIMEOUT_MS = 30_000L
    private const val LOCK_TIMEOUT_MS = 5_000L

    /** Dry run: reads at most one batch of slugs, writes nothing. */
    fun preview(
        json: String,
        organizationLabels: List<String>,
        wallNow: LocalDateTime,
        timeZoneId: String,
    ): ArticleImportPreviewDto {
        val labels = ArticleImportPolicy.normalizeOrganizationLabels(organizationLabels)
        val checks = ArticleImportPolicy.parse(json = json, wallNow = wallNow, organizationLabels = labels)
        val validSlugs = checks.filterIsInstance<ArticleImportPolicy.EntryCheck.Valid>().map { it.entry.slug }
        // ONE short read-only transaction; everything else happens outside it.
        val existing = transaction { ArticleStore.existingSlugs(validSlugs) }
        val rows =
            checks
                .map { check ->
                    when (check) {
                        is ArticleImportPolicy.EntryCheck.Invalid ->
                            ArticleImportPreviewRowDto(
                                index = check.index,
                                slug = check.slug,
                                title = check.title,
                                publishedAt = null,
                                byline = null,
                                status = ArticleImportRowStatus.ERROR,
                                reasons = check.reasons,
                                hints = emptyList(),
                            )
                        is ArticleImportPolicy.EntryCheck.Valid -> {
                            val skip = check.entry.slug in existing
                            ArticleImportPreviewRowDto(
                                index = check.index,
                                slug = check.entry.slug,
                                title = check.entry.title,
                                publishedAt = check.entry.publishedAtWall.toString(),
                                byline = check.entry.byline,
                                status = if (skip) ArticleImportRowStatus.SKIP_SLUG_EXISTS else ArticleImportRowStatus.CREATE,
                                reasons = emptyList(),
                                hints = if (skip) listOf("Slug existiert bereits, wird nicht verändert.") + check.hints else check.hints,
                            )
                        }
                    }
                }.sortedWith(compareBy({ it.status != ArticleImportRowStatus.ERROR }, { it.index }))
        return ArticleImportPreviewDto(
            rows = rows,
            createCount = rows.count { it.status == ArticleImportRowStatus.CREATE },
            skipCount = rows.count { it.status == ArticleImportRowStatus.SKIP_SLUG_EXISTS },
            errorCount = rows.count { it.status == ArticleImportRowStatus.ERROR },
            payloadSha256 = ArticleImportPolicy.sha256Hex(json),
            timeZoneId = timeZoneId,
        )
    }

    /**
     * Writes every valid, not-yet-existing entry in ONE transaction (all or nothing). [onAttempt] is a test seam, invoked after the slug read
     * of every run of the transaction block (Exposed re-runs the block on an `SQLException`; the block therefore holds only idempotent
     * database writes and rebuilds everything it needs from scratch on each run).
     */
    fun commit(
        json: String,
        payloadSha256: String,
        actorMemberId: Uuid,
        actorRole: AccountRole,
        now: LocalDateTime,
        wallNow: LocalDateTime,
        orgZone: TimeZone,
        onAttempt: () -> Unit = {},
    ): ArticleImportResultDto {
        val expected = ArticleImportPolicy.sha256Hex(json).toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(expected, payloadSha256.toByteArray(Charsets.US_ASCII))) {
            throw ConflictException("Der Inhalt wurde seit der Vorschau geändert. Bitte erneut prüfen.")
        }
        val checks = ArticleImportPolicy.parse(json = json, wallNow = wallNow)
        if (checks.any { it is ArticleImportPolicy.EntryCheck.Invalid }) {
            throw BadRequestException("Die Datei enthält Fehler. Bitte erneut prüfen.")
        }
        val entries = checks.filterIsInstance<ArticleImportPolicy.EntryCheck.Valid>().map { it.entry }

        // V1.9.106 -- ONE batch id per commit call, drawn OUTSIDE the transaction block so a re-run of the block (Exposed retries on SQLException)
        // reuses it: the image commit addresses exactly the articles of this run through it.
        val batchId = Uuid.random()
        val outcome =
            try {
                transaction {
                    relaxSessionTimeouts(
                        statementTimeoutMs = STATEMENT_TIMEOUT_MS,
                        idleInTransactionTimeoutMs = IDLE_TX_TIMEOUT_MS,
                        lockTimeoutMs = LOCK_TIMEOUT_MS,
                    )
                    // Re-read INSIDE the block: a slug that appeared since the preview (or since a previous run of this block) is skipped.
                    val existing = ArticleStore.existingSlugs(entries.map { it.slug })
                    // Test seam: runs AFTER the slug read and BEFORE the inserts of every run of this block.
                    onAttempt()
                    val created = mutableListOf<Pair<Uuid, String>>()
                    val skipped = mutableListOf<String>()
                    for (entry in entries) {
                        if (entry.slug in existing) {
                            skipped += entry.slug
                            continue
                        }
                        val id = Uuid.random()
                        // published_at is a class-A column (UTC system timestamp): convert the organization wall-clock typed in the payload.
                        val publishedAtUtc =
                            ServerClock
                                .wallToInstant(wall = entry.publishedAtWall, orgZone = orgZone)
                                .toLocalDateTime(ServerClock.zone)
                        ArticleStore.insertImported(
                            id = id,
                            importerId = actorMemberId,
                            slug = entry.slug,
                            title = entry.title,
                            excerpt = entry.excerpt,
                            body = entry.body,
                            publishedAt = publishedAtUtc,
                            byline = entry.byline,
                            importBatchId = batchId,
                            now = now,
                        )
                        created += id to entry.slug
                    }
                    // Audit LAST: AuditLogRecorder.record takes the chain-state row lock and holds it until commit.
                    if (created.isNotEmpty()) {
                        for ((id, slug) in created) {
                            AuditLogRecorder.record(
                                actorMemberId = actorMemberId,
                                actorRole = actorRole,
                                entityType = AuditEntityType.ARTICLE,
                                entityId = id,
                                action = AuditAction.CREATE,
                                after =
                                    buildJsonObject {
                                        put("imported", true)
                                        put("slug", slug)
                                    }.toString(),
                                occurredAt = now,
                            )
                        }
                        AuditLogRecorder.record(
                            actorMemberId = actorMemberId,
                            actorRole = actorRole,
                            entityType = AuditEntityType.ARTICLE_IMPORT,
                            entityId = Uuid.random(),
                            action = AuditAction.CREATE,
                            after =
                                buildJsonObject {
                                    put("created", created.size)
                                    put("skipped", skipped.size)
                                    put("payloadSha256", payloadSha256)
                                    put("importBatchId", batchId.toString())
                                }.toString(),
                            occurredAt = now,
                        )
                    }
                    ImportOutcome(created = created.size, skippedSlugs = skipped.toList())
                }
            } catch (e: Exception) {
                // A slug created concurrently between the in-block read and the insert (unique violation): report, everything rolled back.
                if (e.isUniqueViolation()) {
                    throw ConflictException("Ein Artikel mit gleichem Slug wurde gleichzeitig angelegt. Bitte erneut prüfen.")
                }
                throw e
            }
        return ArticleImportResultDto(
            created = outcome.created,
            skipped = outcome.skippedSlugs.size,
            skippedSlugs = outcome.skippedSlugs,
            importBatchId = if (outcome.created > 0) batchId.toString() else null,
        )
    }

    private class ImportOutcome(
        val created: Int,
        val skippedSlugs: List<String>,
    )
}
