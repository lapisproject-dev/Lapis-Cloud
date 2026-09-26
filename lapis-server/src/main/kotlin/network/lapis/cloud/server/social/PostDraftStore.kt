package network.lapis.cloud.server.social

import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.McpPostDraftTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.McpPostDraftDto
import network.lapis.cloud.shared.domain.McpPostDraftStatus
import network.lapis.cloud.shared.domain.SocialPostVisibility
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.8.2 "MCP-Server: Schreibwerkzeuge" -- CRUD for `mcp_post_draft`, deliberately living
 * OUTSIDE `mcp/` (see `McpStructureTest` R1/R3: `mcp/` never writes Exposed rows outside three
 * narrowly-scoped exceptions) so both `mcp.tools.CreatePostDraftTool` (agent-facing, creation only)
 * and `rpc.SocialNetworkService` (member-facing RPC: list/edit/release/discard/restore) can share
 * it without either violating that boundary.
 *
 * **Every state-changing method here is `status = 'OPEN'`-conditioned in its own `UPDATE ... WHERE`
 * clause** (except [restoreOwned], the one transition whose FROM status is `DISCARDED`) -- a
 * double-click release/discard/edit can therefore never apply twice; the boolean return value is
 * simply "did this call actually change a row". Ownership is enforced the same way: every method
 * takes the calling member's own id and folds it into that same WHERE clause, so a foreign draft id
 * silently changes nothing rather than needing a separate existence check up front (no oracle).
 */
internal object PostDraftStore {
    /** `create_post_draft` -- see `docs/architecture/mcp-server.adoc` "The draft lifecycle". */
    const val MAX_OPEN_DRAFTS_PER_MEMBER = 10

    class DraftLimitReachedException(
        val openDraftCount: Int,
    ) : Exception("Maximum open draft count ($MAX_OPEN_DRAFTS_PER_MEMBER) already reached")

    /**
     * Locks the member row (same `SELECT ... FOR UPDATE` idiom as
     * [network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider.lockForDebit]) BEFORE
     * counting open drafts, all inside this one transaction -- otherwise two concurrent
     * `create_post_draft` calls could both observe `count < MAX_OPEN_DRAFTS_PER_MEMBER` and both
     * insert, defeating the cap under parallel load. Returns the new draft id and the resulting
     * open-draft count (for the tool's own response, see `CreatePostDraftTool`).
     */
    fun createDraft(
        memberId: Uuid,
        tokenId: Uuid?,
        agentLabel: String,
        content: String,
        visibility: SocialPostVisibility,
    ): Pair<Uuid, Int> =
        transaction {
            MemberTable
                .selectAll()
                .where { MemberTable.id eq memberId }
                .forUpdate()
                .singleOrNull()
                ?: error("Member $memberId not found while locking for draft creation")

            val openCount = countOpenLocked(memberId)
            if (openCount >= MAX_OPEN_DRAFTS_PER_MEMBER) throw DraftLimitReachedException(openDraftCount = openCount)

            val now = DbClock.nowLocalDateTime()
            val draftId = Uuid.random()
            McpPostDraftTable.insert {
                it[id] = draftId
                it[McpPostDraftTable.memberId] = memberId
                it[McpPostDraftTable.tokenId] = tokenId
                it[McpPostDraftTable.agentLabel] = agentLabel
                it[McpPostDraftTable.content] = content
                it[McpPostDraftTable.visibility] = visibility
                it[status] = McpPostDraftStatus.OPEN
                it[createdAt] = now
                it[updatedAt] = now
                it[statusChangedAt] = null
                it[releasedPostId] = null
            }
            draftId to (openCount + 1)
        }

    /**
     * Every `OPEN` **and** still-restorable `DISCARDED` draft for [memberId] -- the member's own
     * "KI-Entwürfe" screen. Welle V1.8.2b (review fix): a plain `listOpen` (OPEN only) made a
     * discarded draft invisible after the very next page load, even though `restoreOwned` keeps it
     * restorable for [network.lapis.cloud.server.social.PostDraftRetention.DISCARDED_RETENTION_DAYS]
     * -- the "Rückgängig" toast action was reachable only within the same browser session, never
     * across a reload. `RELEASED` drafts are deliberately excluded -- a released draft's own content
     * is already visible as the resulting `social_post`, see `markReleasedOwned` KDoc.
     *
     * **Sort order is computed in Kotlin, not SQL** -- `status` is an `enumerationByName` column, so
     * `ORDER BY status` would sort ALPHABETICALLY (`DISCARDED` < `OPEN`), not by the intended
     * "OPEN first" business order. `OPEN` drafts come first (newest first within that group), then
     * `DISCARDED` (newest first).
     */
    fun listVisible(memberId: Uuid): List<McpPostDraftDto> =
        transaction {
            McpPostDraftTable
                .selectAll()
                .where {
                    (McpPostDraftTable.memberId eq memberId) and
                        (McpPostDraftTable.status inList listOf(McpPostDraftStatus.OPEN, McpPostDraftStatus.DISCARDED))
                }.map { toDto(it) }
                .sortedWith(
                    compareBy<McpPostDraftDto> { if (it.status == McpPostDraftStatus.OPEN) 0 else 1 }
                        .thenByDescending { it.createdAt },
                )
        }

    fun countOpen(memberId: Uuid): Int = transaction { countOpenLocked(memberId) }

    /** Not row-locking on its own -- [createDraft] already holds the member-row lock when it calls this. */
    private fun countOpenLocked(memberId: Uuid): Int =
        McpPostDraftTable
            .selectAll()
            .where { (McpPostDraftTable.memberId eq memberId) and (McpPostDraftTable.status eq McpPostDraftStatus.OPEN) }
            .count()
            .toInt()

    /** `null` if [draftId] does not exist, or exists but belongs to a different member -- ownership and existence are deliberately indistinguishable here. */
    fun getOwned(
        memberId: Uuid,
        draftId: Uuid,
    ): ResultRow? =
        transaction {
            McpPostDraftTable
                .selectAll()
                .where { (McpPostDraftTable.id eq draftId) and (McpPostDraftTable.memberId eq memberId) }
                .singleOrNull()
        }

    /** Only an `OPEN` draft's `content`/`visibility` can be edited -- see class KDoc. */
    fun updateOwned(
        memberId: Uuid,
        draftId: Uuid,
        content: String,
        visibility: SocialPostVisibility,
    ): Boolean =
        transaction {
            val now = DbClock.nowLocalDateTime()
            McpPostDraftTable.update({
                (McpPostDraftTable.id eq draftId) and
                    (McpPostDraftTable.memberId eq memberId) and
                    (McpPostDraftTable.status eq McpPostDraftStatus.OPEN)
            }) {
                it[McpPostDraftTable.content] = content
                it[McpPostDraftTable.visibility] = visibility
                it[updatedAt] = now
            } > 0
        }

    /** `OPEN -> DISCARDED`. The client shows a "Rückgängig" toast afterwards -- see [restoreOwned]. */
    fun discardOwned(
        memberId: Uuid,
        draftId: Uuid,
    ): Boolean = transitionFromOpen(memberId = memberId, draftId = draftId, to = McpPostDraftStatus.DISCARDED)

    /**
     * `DISCARDED -> OPEN` -- the one transition here whose FROM status is not `OPEN` (the
     * "Rückgängig" toast action).
     *
     * **Review fix (V1.8.2 wave 2)**: this used to be a plain `UPDATE ... WHERE status =
     * 'DISCARDED'` with no cap check at all -- [MAX_OPEN_DRAFTS_PER_MEMBER] was enforced only at
     * creation ([createDraft]), so a member could push the open-draft count arbitrarily far above
     * the cap by discarding a full batch and then restoring it again (each "Rückgängig" click moves
     * one row back to `OPEN` regardless of how many are already open). Now locks the member row
     * FIRST (same idiom as [createDraft], same reason: two concurrent restores must not both
     * observe `count < MAX_OPEN_DRAFTS_PER_MEMBER` and both succeed), THEN checks the draft is
     * actually this member's own `DISCARDED` row (a foreign/missing/non-`DISCARDED` [draftId] is
     * still a plain `false`, never [DraftLimitReachedException] -- the cap is only ever a reason to
     * refuse a restore that would otherwise have happened) -- only THEN counts and enforces the cap.
     */
    fun restoreOwned(
        memberId: Uuid,
        draftId: Uuid,
    ): Boolean =
        transaction {
            MemberTable
                .selectAll()
                .where { MemberTable.id eq memberId }
                .forUpdate()
                .singleOrNull()
                ?: error("Member $memberId not found while locking for draft restore")

            val discardedDraftExists =
                McpPostDraftTable
                    .selectAll()
                    .where {
                        (McpPostDraftTable.id eq draftId) and
                            (McpPostDraftTable.memberId eq memberId) and
                            (McpPostDraftTable.status eq McpPostDraftStatus.DISCARDED)
                    }.limit(1)
                    .any()
            if (!discardedDraftExists) return@transaction false

            val openCount = countOpenLocked(memberId)
            if (openCount >= MAX_OPEN_DRAFTS_PER_MEMBER) throw DraftLimitReachedException(openDraftCount = openCount)

            val now = DbClock.nowLocalDateTime()
            McpPostDraftTable.update({
                (McpPostDraftTable.id eq draftId) and
                    (McpPostDraftTable.memberId eq memberId) and
                    (McpPostDraftTable.status eq McpPostDraftStatus.DISCARDED)
            }) {
                it[status] = McpPostDraftStatus.OPEN
                it[statusChangedAt] = now
                it[updatedAt] = now
            } > 0
        }

    /**
     * `OPEN -> RELEASED`, called by `rpc.SocialNetworkService.releaseMyPostDraft` from WITHIN its
     * own already-open transaction (Exposed's `transaction {}` joins an already-active transaction
     * on the same thread rather than nesting, so this call participates in that same commit/rollback
     * rather than starting a second one) -- see that method's KDoc for the full ordering
     * (ownership+OPEN check -> `createPostRow` -> this call -> audit log, all-or-nothing).
     *
     * **`content` is cleared to `""` (Welle V1.8.2b, Jobs' review call), the MOMENT a draft is
     * released, not after any later retention delay.** The reason is narrower than "DSGVO
     * minimization in general": `dsgvo.McpPersonalData` already covers `mcp_post_draft` in a
     * member's erasure export/delete (a hard `DELETE ... WHERE member_id = ?`), so a full member
     * erasure already removes this row. What that erasure does NOT reach is the **post-scoped**
     * pathway -- the tombstone a post-scoped erasure leaves on a single `social_post` row
     * (`rpc.SocialContentTombstone`, `content_erased_at`) has no counterpart on `mcp_post_draft` at
     * all: erasing the PUBLISHED post this draft became would
     * otherwise leave the ORIGINAL text sitting, unredacted, in this second table forever. Clearing
     * it here, unconditionally, closes that gap for every released draft -- not just ones later
     * erasure-requested -- which is simpler and strictly more protective than trying to keep the two
     * tables' erasure state in lockstep.
     */
    fun markReleasedOwned(
        memberId: Uuid,
        draftId: Uuid,
        postId: Uuid,
    ): Boolean =
        transaction {
            val now = DbClock.nowLocalDateTime()
            McpPostDraftTable.update({
                (McpPostDraftTable.id eq draftId) and
                    (McpPostDraftTable.memberId eq memberId) and
                    (McpPostDraftTable.status eq McpPostDraftStatus.OPEN)
            }) {
                it[status] = McpPostDraftStatus.RELEASED
                it[releasedPostId] = postId
                it[statusChangedAt] = now
                it[updatedAt] = now
                it[McpPostDraftTable.content] = ""
            } > 0
        }

    private fun transitionFromOpen(
        memberId: Uuid,
        draftId: Uuid,
        to: McpPostDraftStatus,
    ): Boolean =
        transaction {
            val now = DbClock.nowLocalDateTime()
            McpPostDraftTable.update({
                (McpPostDraftTable.id eq draftId) and
                    (McpPostDraftTable.memberId eq memberId) and
                    (McpPostDraftTable.status eq McpPostDraftStatus.OPEN)
            }) {
                it[status] = to
                it[statusChangedAt] = now
                it[updatedAt] = now
            } > 0
        }

    /** Public mapping entry point -- used internally by [listVisible] and by `rpc.SocialNetworkService`, which reads rows via [getOwned] directly (ownership already established there). */
    fun toDto(row: ResultRow): McpPostDraftDto =
        McpPostDraftDto(
            id = row[McpPostDraftTable.id].toString(),
            content = row[McpPostDraftTable.content],
            visibility = row[McpPostDraftTable.visibility],
            status = row[McpPostDraftTable.status],
            agentLabel = row[McpPostDraftTable.agentLabel],
            createdAt = row[McpPostDraftTable.createdAt],
            updatedAt = row[McpPostDraftTable.updatedAt],
            releasedPostId = row[McpPostDraftTable.releasedPostId]?.toString(),
            statusChangedAt = row[McpPostDraftTable.statusChangedAt],
        )
}
