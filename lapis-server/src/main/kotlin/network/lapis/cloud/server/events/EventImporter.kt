package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.relaxSessionTimeouts
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.EventImportPreviewDto
import network.lapis.cloud.shared.domain.EventImportPreviewRowDto
import network.lapis.cloud.shared.domain.EventImportResultDto
import network.lapis.cloud.shared.domain.EventImportRowStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * Welle V1.9.82 -- database side of the admin import of PAST events. Validation lives in [EventImportPolicy] (pure); this class adds the
 * slug lookup and the single write transaction.
 *
 * **The ONLY place that writes `imported = true`** (a source-scanning tripwire test pins that). It deliberately does NOT call
 * [EventPolicy.validate]: that is the one place that forbids a start in the past for ordinary events and it stays byte-for-byte as it
 * was -- the import has its own, stricter "must already be over" rule in [EventImportPolicy].
 *
 * **No network access of any kind** (no HTTP client, no mail, no webhook): an online link is checked for shape only, never fetched.
 */
internal object EventImporter {
    /** DB timeouts for the commit transaction, applied per transaction via `SET LOCAL` (Postgres only; see `relaxSessionTimeouts`). */
    private const val STATEMENT_TIMEOUT_MS = 30_000L
    private const val IDLE_TX_TIMEOUT_MS = 30_000L
    private const val LOCK_TIMEOUT_MS = 5_000L

    /** Dry run: reads at most one batch of slugs, writes nothing. */
    fun preview(
        json: String,
        wallNow: LocalDateTime,
        timeZoneId: String,
    ): EventImportPreviewDto {
        val checks = EventImportPolicy.parse(json = json, wallNow = wallNow)
        val validSlugs = checks.filterIsInstance<EventImportPolicy.EntryCheck.Valid>().map { it.entry.slug }
        // ONE short read-only transaction; everything else happens outside it.
        val existing = transaction { existingSlugs(validSlugs) }
        val rows =
            checks
                .map { check ->
                    when (check) {
                        is EventImportPolicy.EntryCheck.Invalid ->
                            EventImportPreviewRowDto(
                                index = check.index,
                                slug = check.slug,
                                title = check.title,
                                status = EventImportRowStatus.ERROR,
                                reasons = check.reasons,
                                hints = emptyList(),
                                startsAt = null,
                                endsAt = null,
                            )
                        is EventImportPolicy.EntryCheck.Valid -> {
                            val skip = check.entry.slug in existing
                            EventImportPreviewRowDto(
                                index = check.index,
                                slug = check.entry.slug,
                                title = check.entry.title,
                                status = if (skip) EventImportRowStatus.SKIP_SLUG_EXISTS else EventImportRowStatus.CREATE,
                                reasons = emptyList(),
                                hints = check.hints,
                                startsAt = check.entry.startsAt,
                                endsAt = check.entry.endsAt,
                            )
                        }
                    }
                }.sortedWith(compareBy({ it.status != EventImportRowStatus.ERROR }, { it.index }))
        return EventImportPreviewDto(
            rows = rows,
            createCount = rows.count { it.status == EventImportRowStatus.CREATE },
            skipCount = rows.count { it.status == EventImportRowStatus.SKIP_SLUG_EXISTS },
            errorCount = rows.count { it.status == EventImportRowStatus.ERROR },
            payloadSha256 = EventImportPolicy.sha256Hex(json),
            timeZoneId = timeZoneId,
        )
    }

    /**
     * Writes every valid, not-yet-existing entry in ONE transaction (all or nothing). [onAttempt] is a test seam, invoked after the slug read of
     * every run of the transaction block (Exposed re-runs the block on an `SQLException`; the block therefore holds only idempotent
     * database writes and rebuilds everything it needs from scratch on each run).
     */
    fun commit(
        json: String,
        payloadSha256: String,
        actorMemberId: Uuid,
        actorRole: AccountRole,
        now: LocalDateTime,
        wallNow: LocalDateTime,
        onAttempt: () -> Unit = {},
    ): EventImportResultDto {
        val expected = EventImportPolicy.sha256Hex(json).toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(expected, payloadSha256.toByteArray(Charsets.US_ASCII))) {
            throw ConflictException("Der Inhalt wurde seit der Vorschau geändert. Bitte erneut prüfen.")
        }
        val checks = EventImportPolicy.parse(json = json, wallNow = wallNow)
        if (checks.any { it is EventImportPolicy.EntryCheck.Invalid }) {
            throw BadRequestException("Die Datei enthält Fehler. Bitte erneut prüfen.")
        }
        val entries = checks.filterIsInstance<EventImportPolicy.EntryCheck.Valid>().map { it.entry }

        val outcome =
            try {
                transaction {
                    relaxSessionTimeouts(
                        statementTimeoutMs = STATEMENT_TIMEOUT_MS,
                        idleInTransactionTimeoutMs = IDLE_TX_TIMEOUT_MS,
                        lockTimeoutMs = LOCK_TIMEOUT_MS,
                    )
                    // Re-read INSIDE the block: a slug that appeared since the preview (or since a previous run of this block) is skipped.
                    val existing = existingSlugs(entries.map { it.slug })
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
                        EventStore.insertEvent(
                            id = id,
                            slug = entry.slug,
                            title = entry.title,
                            description = entry.description,
                            locationText = entry.locationText,
                            onlineUrl = entry.onlineUrl,
                            startsAt = entry.startsAt,
                            endsAt = entry.endsAt,
                            capacity = null,
                            feeAmount = BigDecimal.ZERO,
                            feeCurrency = "EUR",
                            visibility = EventVisibility.PUBLIC,
                            registrationClosesAt = entry.startsAt,
                            createdAt = now,
                            createdBy = actorMemberId,
                            status = EventStatus.PUBLISHED,
                            summary = entry.summary,
                            coverImageAlt = entry.coverImageAlt,
                            onlineUrlPublic = entry.onlineUrlPublic,
                            imported = true,
                        )
                        created += id to entry.slug
                    }
                    // Audit LAST: AuditLogRecorder.record takes the chain-state row lock and holds it until commit.
                    if (created.isNotEmpty()) {
                        for ((id, slug) in created) {
                            AuditLogRecorder.record(
                                actorMemberId = actorMemberId,
                                actorRole = actorRole,
                                entityType = AuditEntityType.EVENT,
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
                            entityType = AuditEntityType.EVENT_IMPORT,
                            entityId = Uuid.random(),
                            action = AuditAction.CREATE,
                            after =
                                buildJsonObject {
                                    put("created", created.size)
                                    put("skipped", skipped.size)
                                    put("payloadSha256", payloadSha256)
                                }.toString(),
                            occurredAt = now,
                        )
                    }
                    ImportOutcome(created = created.size, skippedSlugs = skipped.toList())
                }
            } catch (e: Exception) {
                // A slug created concurrently between the in-block read and the insert (unique violation): report, everything rolled back.
                if (e.isUniqueViolation()) {
                    throw ConflictException(
                        "Eine Veranstaltung mit gleichem Slug wurde gleichzeitig angelegt. Bitte erneut prüfen.",
                    )
                }
                throw e
            }
        return EventImportResultDto(created = outcome.created, skipped = outcome.skippedSlugs.size, skippedSlugs = outcome.skippedSlugs)
    }

    private class ImportOutcome(
        val created: Int,
        val skippedSlugs: List<String>,
    )

    /** ONE batched lookup (at most [EventImportPolicy.MAX_ENTRIES] values) instead of one query per entry. Must run inside the caller's transaction. */
    private fun existingSlugs(slugs: List<String>): Set<String> {
        if (slugs.isEmpty()) return emptySet()
        return EventTable
            .select(EventTable.slug)
            .where { EventTable.slug inList slugs }
            .map { it[EventTable.slug] }
            .toSet()
    }
}
