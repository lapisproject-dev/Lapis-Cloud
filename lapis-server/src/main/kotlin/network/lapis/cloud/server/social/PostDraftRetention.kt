package network.lapis.cloud.server.social

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.McpPostDraftTable
import network.lapis.cloud.shared.domain.McpPostDraftStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.days

/**
 * Same "no direct `LocalDateTime` +/- `Duration` in kotlinx-datetime" idiom as
 * `network.lapis.cloud.server.events.plusDuration` -- goes via UTC `Instant`, never a calendar-unit
 * overload (this is a fixed-length retention window, not a calendar concept).
 */
private fun LocalDateTime.minusDays(days: Long): LocalDateTime = (toInstant(TimeZone.UTC) - days.days).toLocalDateTime(TimeZone.UTC)

/**
 * Welle V1.8.2b (MINOR-3) -- `mcp_post_draft` grows without bound otherwise: every
 * `create_post_draft` call, every discard, every release leaves a permanent row. Deliberately
 * SHORTER than every other retention window in this codebase (`ContributionReliefRedaction`'s 12
 * months, `PublicRankingConsentPersonalData`'s export caps) because a draft is disposable AI-agent
 * scratch content, not a legally significant record -- Jobs' review call: 7 days for a
 * [McpPostDraftStatus.DISCARDED] draft (long enough for the member's own "Wiederherstellen" undo
 * window in `AiDraftsScreen`, see that constant's own KDoc requirement to stay in sync with
 * [DISCARDED_RETENTION_DAYS] here), 90 days for a [McpPostDraftStatus.RELEASED] one (its `content`
 * is already cleared at release time, see `PostDraftStore.markReleasedOwned` -- this row is kept
 * only for the release audit trail / [network.lapis.cloud.server.db.generated.McpPostDraftTable
 * .releasedPostId] linkage, not for its text). [McpPostDraftStatus.OPEN] is **never** deleted here,
 * regardless of age -- this poller only ever removes rows the member has already discarded or
 * released, never content still awaiting their decision.
 *
 * **Deliberately lives in `social/`, not `mcp/`** -- same `McpStructureTest` R1/R3 boundary
 * reasoning as [PostDraftStore] itself (see that object's KDoc): this is bookkeeping on a table
 * `rpc.SocialNetworkService` and `mcp.tools.CreatePostDraftTool` both already touch, not MCP-layer
 * logic.
 */
internal object PostDraftRetention {
    const val DISCARDED_RETENTION_DAYS = 7L
    const val RELEASED_RETENTION_DAYS = 90L

    /**
     * DoS-Deckel, same "Pagination-Caps" posture as [network.lapis.cloud.server.contribution
     * .ContributionReliefRedaction.MAX_CANDIDATES_PER_RUN] -- bounds how many rows a single tick can
     * ever delete. Any excess is picked up by the next daily tick; a retention deadline measured in
     * days tolerates that.
     *
     * Review fix (V1.8.2b follow-up): this cap is applied to the SQL query itself, AFTER the
     * age/cutoff predicate below already restricted the result to rows that are actually due --
     * never as a `LIMIT` on the unfiltered `status eq status` set. An earlier version limited the
     * unfiltered set first and evaluated the cutoff in Kotlin afterwards: once an instance
     * accumulated more not-yet-due rows of a status than this cap (well within reach at the default
     * quotas -- up to ~90,000 RELEASED rows can accumulate inside the 90-day window alone), the
     * `LIMIT` could return an arbitrary physical-order subset made up entirely of NOT-yet-due rows,
     * every tick, forever -- deleting nothing and never making progress on a real backlog, silently
     * contradicting this class's own "any excess is picked up by the next daily tick" guarantee.
     */
    private const val MAX_DELETED_PER_STATUS_PER_RUN = 5_000

    /**
     * `status_changed_at`, falling back to `updated_at` -- pushed into the SQL `WHERE` itself
     * (review fix, see [MAX_DELETED_PER_STATUS_PER_RUN] KDoc for why the cutoff can no longer be a
     * Kotlin-side filter applied after a `LIMIT`), but still explicit about the NULL fallback rather
     * than relying on the SQL truth that `NULL < cutoff` is never true: a freshly inserted `OPEN`
     * draft has `status_changed_at = NULL`, so an implicit-fallback `statusChangedAt less cutoff`
     * alone would already exclude it correctly, but this function does not touch `OPEN` rows at all
     * regardless (see class KDoc) -- the explicit `isNull() and (updatedAt less cutoff)` branch
     * below is only ever reached for a DISCARDED/RELEASED row whose `status_changed_at` was never
     * set, same case the fixed test "falls back to updatedAt" pins.
     *
     * Only `id` is selected (not `selectAll()`): the age predicate no longer needs
     * `statusChangedAt`/`updatedAt` back in Kotlin, and materializing every column -- including
     * `content TEXT` (up to `SocialNetworkService`'s `MAX_CONTENT_LENGTH` = 5,000 characters,
     * cleared to `""` on release but still up to that length while DISCARDED) -- for up to
     * [MAX_DELETED_PER_STATUS_PER_RUN] rows per status per tick would be pure waste.
     *
     * @return (deleted DISCARDED rows, deleted RELEASED rows).
     */
    fun deleteDueRows(now: LocalDateTime): Pair<Int, Int> {
        val discardedCutoff = now.minusDays(DISCARDED_RETENTION_DAYS)
        val releasedCutoff = now.minusDays(RELEASED_RETENTION_DAYS)
        val deletedDiscarded = deleteDueForStatus(status = McpPostDraftStatus.DISCARDED, cutoff = discardedCutoff)
        val deletedReleased = deleteDueForStatus(status = McpPostDraftStatus.RELEASED, cutoff = releasedCutoff)
        return deletedDiscarded to deletedReleased
    }

    /**
     * `internal`, not `private`, and `limit` an (defaulted) parameter rather than a hardcoded
     * reference to [MAX_DELETED_PER_STATUS_PER_RUN] -- same testability posture as
     * `AccountingExportPoller.MAX_ITEMS_PER_TICK`/`AccountingExportStore.duePendingItemIds`'s own
     * `limit` parameter: exercising the real production cap (5,000) end-to-end would mean inserting
     * 5,001+ rows per test, which this codebase's other capped pollers avoid by keeping the constant
     * itself tiny (`MAX_ITEMS_PER_TICK = 3`) rather than by parameterizing it -- not an option here,
     * because [MAX_DELETED_PER_STATUS_PER_RUN] is a genuine production DoS-Deckel sized for real
     * traffic, not a test-friendly default. `PostDraftRetentionTest` passes a small `limit` directly
     * to reach the same cap-then-backlog-progress behavior cheaply, while [deleteDueRows] -- the only
     * production call site -- always relies on the default.
     */
    internal fun deleteDueForStatus(
        status: McpPostDraftStatus,
        cutoff: LocalDateTime,
        limit: Int = MAX_DELETED_PER_STATUS_PER_RUN,
    ): Int =
        transaction {
            val dueIds =
                McpPostDraftTable
                    .select(McpPostDraftTable.id)
                    .where {
                        (McpPostDraftTable.status eq status) and
                            (
                                (McpPostDraftTable.statusChangedAt less cutoff) or
                                    (McpPostDraftTable.statusChangedAt.isNull() and (McpPostDraftTable.updatedAt less cutoff))
                            )
                    }
                    // Deterministic progress across ticks (oldest-due-first), same reasoning as
                    // `ContributionReliefRedaction.redactDueReasonTexts`'s own `orderBy` KDoc --
                    // matters only while the due set is itself larger than the cap; `id` as a
                    // tie-breaker for rows sharing the exact same anchor.
                    .orderBy(McpPostDraftTable.statusChangedAt to SortOrder.ASC, McpPostDraftTable.id to SortOrder.ASC)
                    .limit(limit)
                    .map { it[McpPostDraftTable.id] }
            if (dueIds.isEmpty()) {
                0
            } else {
                // Re-conditioned on `status eq status` (Review-idiom, same double-guard as
                // `PostDraftStore`'s own transitions) -- harmless belt-and-braces against a status
                // change racing this delete between the SELECT above and this DELETE.
                McpPostDraftTable.deleteWhere { (McpPostDraftTable.id inList dueIds) and (McpPostDraftTable.status eq status) }
            }
        }
}
