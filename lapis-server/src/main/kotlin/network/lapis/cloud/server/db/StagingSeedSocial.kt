package network.lapis.cloud.server.db

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.StagingSeedLedger.covers
import network.lapis.cloud.server.db.StagingSeedLedger.insertRows
import network.lapis.cloud.server.db.StagingSeedLedger.statusesOf
import network.lapis.cloud.server.db.generated.SocialPostBoostTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.SocialPostModerationSnapshot
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Sample data for the social network (V1.9.205): root posts of varying age, weight and visibility, comments in a thread, boosts, one post hidden by
 * its author and one removed for a legal reason.
 *
 * Writes what `SocialNetworkService.createPost` / `createComment` / `boostPost` / `hideOwnPost` / `removePostForLegalReason` leave behind: the post
 * row, its `SOCIAL_POST_STAKE` ledger row (note without any content excerpt, E5), the boost row plus `SOCIAL_POST_BOOST`. A comment takes the
 * visibility of its ROOT, a member who is only a friend never posts for members only. Only the legal removal is audited by the service (hash chain),
 * so only that is audited here -- with the default time, never back-dated, as the very last operation of the transaction.
 */
internal object StagingSeedSocial : StagingSeedPart {
    override val name: String = "social"

    private const val MODERATOR = 2

    data class PlannedPost(
        val index: Int,
        val parent: Int?,
        val depth: Int,
        val author: Int,
        val content: String,
        val visibility: SocialPostVisibility,
        val weight: BigDecimal,
        val daysAgo: Int,
        val state: SocialPostState = SocialPostState.VISIBLE,
        val stateReason: String? = null,
        val stateChangedBy: Int? = null,
        val stateDaysAgo: Int? = null,
    ) {
        val id: Uuid get() = SeedIds.social(index)
        val root: Int get() = if (depth == 0) index else rootOf.getValue(index)
    }

    data class PlannedBoost(
        val index: Int,
        val post: Int,
        val member: Int,
        val amount: BigDecimal,
        val daysAgo: Int,
        val hour: Int,
    )

    /** Append-only: the index is the id. Parents come before their children. */
    val posts: List<PlannedPost> =
        listOf(
            PlannedPost(
                index = 1,
                parent = null,
                depth = 0,
                author = 1,
                content = "Willkommen im Netzwerk des Testvereins! Hier entstehen unsere gemeinsamen Ideen (Demodaten).",
                visibility = SocialPostVisibility.PUBLIC,
                weight = bd("20.00"),
                daysAgo = 56,
            ),
            PlannedPost(
                index = 2,
                parent = null,
                depth = 0,
                author = 2,
                content = "Der Vorstand plant die Sommerfahrt, Vorschlaege sind willkommen (Demodaten).",
                visibility = SocialPostVisibility.MEMBERS_ONLY,
                weight = bd("10.00"),
                daysAgo = 42,
            ),
            PlannedPost(
                index = 3,
                parent = null,
                depth = 0,
                author = 3,
                content = "Kassenbericht im Ueberblick: solide Lage, keine Ueberraschungen (Demodaten).",
                visibility = SocialPostVisibility.MEMBERS_AND_EXTERNAL,
                weight = bd("5.00"),
                daysAgo = 28,
            ),
            PlannedPost(
                index = 4,
                parent = null,
                depth = 0,
                author = 10,
                content = "Ein Beitrag, der nach einer Meldung geprueft und entfernt wurde (Demodaten).",
                visibility = SocialPostVisibility.PUBLIC,
                weight = bd("2.00"),
                daysAgo = 21,
                state = SocialPostState.REMOVED_LEGAL,
                stateReason = "Nach Pruefung einer Meldung wegen eines Rechtsverstosses entfernt (Demodaten, fiktiv).",
                stateChangedBy = MODERATOR,
                stateDaysAgo = 19,
            ),
            PlannedPost(
                index = 5,
                parent = null,
                depth = 0,
                author = 5,
                content = "Ein Beitrag, den die Autorin selbst wieder versteckt hat (Demodaten).",
                visibility = SocialPostVisibility.MEMBERS_ONLY,
                weight = bd("1.00"),
                daysAgo = 14,
                state = SocialPostState.HIDDEN_BY_AUTHOR,
                stateChangedBy = 5,
                stateDaysAgo = 13,
            ),
            PlannedPost(
                index = 6,
                parent = null,
                depth = 0,
                author = 8,
                content = "Kurze Frage: Wer bringt zum Stammtisch Kuchen mit? (Demodaten)",
                visibility = SocialPostVisibility.PUBLIC,
                weight = bd("0.01"),
                daysAgo = 10,
            ),
            PlannedPost(
                index = 7,
                parent = null,
                depth = 0,
                author = 13,
                content = "Danke fuer die freundliche Aufnahme als Freundin des Vereins (Demodaten).",
                visibility = SocialPostVisibility.PUBLIC,
                weight = bd("1.00"),
                daysAgo = 6,
            ),
            PlannedPost(
                index = 8,
                parent = null,
                depth = 0,
                author = 21,
                content = "Ideensammlung fuer das Herbstfest, auch Gaeste duerfen mitlesen (Demodaten).",
                visibility = SocialPostVisibility.MEMBERS_AND_EXTERNAL,
                weight = bd("2.00"),
                daysAgo = 3,
            ),
            PlannedPost(
                index = 9,
                parent = null,
                depth = 0,
                author = 4,
                content = "Neu im Verein: Die Kaffeemaschine ist ersetzt, probiert sie aus (Demodaten).",
                visibility = SocialPostVisibility.PUBLIC,
                weight = bd("3.00"),
                daysAgo = 1,
            ),
            PlannedPost(
                index = 11,
                parent = 1,
                depth = 1,
                author = 2,
                content = "Schoene Idee, ich freue mich auf den Austausch (Demodaten).",
                visibility = SocialPostVisibility.PUBLIC,
                weight = bd("1.00"),
                daysAgo = 55,
            ),
            PlannedPost(
                index = 12,
                parent = 11,
                depth = 2,
                author = 1,
                content = "Danke dir, gerne mehr davon (Demodaten).",
                visibility = SocialPostVisibility.PUBLIC,
                weight = bd("0.50"),
                daysAgo = 54,
            ),
            PlannedPost(
                index = 13,
                parent = 3,
                depth = 1,
                author = 5,
                content = "Gut zu wissen, danke fuer die Zusammenfassung (Demodaten).",
                visibility = SocialPostVisibility.MEMBERS_AND_EXTERNAL,
                weight = bd("0.50"),
                daysAgo = 27,
            ),
        )

    val boosts: List<PlannedBoost> =
        listOf(
            PlannedBoost(index = 1, post = 1, member = 4, amount = bd("2.00"), daysAgo = 50, hour = 11),
            PlannedBoost(index = 2, post = 1, member = 9, amount = bd("1.00"), daysAgo = 40, hour = 12),
            PlannedBoost(index = 3, post = 3, member = 6, amount = bd("1.00"), daysAgo = 20, hour = 13),
            PlannedBoost(index = 4, post = 9, member = 7, amount = bd("0.50"), daysAgo = 0, hour = 9),
        )

    private val rootOf: Map<Int, Int> by lazy {
        val byIndex = posts.associateBy { it.index }
        posts.associate { post ->
            var current = post
            while (current.parent != null) current = byIndex.getValue(current.parent)
            post.index to current.index
        }
    }

    private fun bd(value: String): BigDecimal = BigDecimal(value)

    fun publishedStamp(
        post: PlannedPost,
        clock: SeedClock,
    ): LocalDateTime = clock.utcAt(daysAgo = post.daysAgo, hour = 10)

    fun boostStamp(
        boost: PlannedBoost,
        clock: SeedClock,
    ): LocalDateTime = clock.utcAt(daysAgo = boost.daysAgo, hour = boost.hour)

    private fun postRow(
        post: PlannedPost,
        clock: SeedClock,
    ) = SeedLedgerRow(
        id = SeedIds.social(0x1000 + post.index),
        member = post.author,
        type = LtrLedgerEntryType.SOCIAL_POST_STAKE,
        amount = post.weight.negate(),
        referenceType = LtrLedgerReferenceType.SOCIAL_POST,
        referenceId = post.id,
        // E5: like the service, no content excerpt, only the reference.
        note = StagingSeedLedger.note("Beitragsgewicht fuer Social Post ${post.id}"),
        at = publishedStamp(post = post, clock = clock),
    )

    private fun boostRow(
        boost: PlannedBoost,
        clock: SeedClock,
    ) = SeedLedgerRow(
        id = SeedIds.social(0x1100 + boost.index),
        member = boost.member,
        type = LtrLedgerEntryType.SOCIAL_POST_BOOST,
        amount = boost.amount.negate(),
        referenceType = LtrLedgerReferenceType.SOCIAL_POST,
        referenceId = SeedIds.social(boost.post),
        note = StagingSeedLedger.note("Boost fuer Social Post ${SeedIds.social(boost.post)}"),
        at = boostStamp(boost = boost, clock = clock),
    )

    /** All ledger rows the plan writes, for the balance simulation of the plan test. */
    fun ledgerPlan(clock: SeedClock): List<SeedLedgerRow> =
        posts.map { postRow(post = it, clock = clock) } + boosts.map { boostRow(boost = it, clock = clock) }

    private fun readableBy(
        visibility: SocialPostVisibility,
        status: MemberStatus?,
    ): Boolean =
        status != null &&
            status in MemberStatusSets.LTR_ELIGIBLE &&
            !(visibility == SocialPostVisibility.MEMBERS_ONLY && status in MemberStatusSets.NON_MEMBER)

    override fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult {
        val statuses = statusesOf(posts.flatMap { listOfNotNull(it.author, it.stateChangedBy) } + boosts.map { it.member })
        var inserted = 0
        var skipped = 0
        val removed = mutableListOf<PlannedPost>()
        posts.forEach { post ->
            val parentOk =
                post.parent == null ||
                    SocialPostTable
                        .selectAll()
                        .where { SocialPostTable.id eq SeedIds.social(post.parent) }
                        .singleOrNull()
                        ?.get(SocialPostTable.state) == SocialPostState.VISIBLE
            val row = postRow(post = post, clock = clock)
            val ok =
                SocialPostTable.selectAll().where { SocialPostTable.id eq post.id }.empty() &&
                    parentOk &&
                    readableBy(visibility = post.visibility, status = statuses[post.author]) &&
                    (post.stateChangedBy == null || post.stateChangedBy in statuses) &&
                    covers(listOf(row))
            if (!ok) {
                skipped++
                return@forEach
            }
            SocialPostTable.insert {
                it[id] = post.id
                it[parentId] = post.parent?.let { p -> SeedIds.social(p) }
                it[rootId] = SeedIds.social(post.root)
                it[depth] = post.depth
                it[authorMemberId] = SeedIds.member(post.author)
                it[content] = post.content
                it[visibility] = post.visibility
                it[initialWeightLtr] = post.weight
                it[publishedAt] = publishedStamp(post = post, clock = clock)
                it[state] = post.state
                it[stateChangedAt] = post.stateDaysAgo?.let { d -> clock.utcAt(daysAgo = d, hour = 12) }
                it[stateChangedBy] = post.stateChangedBy?.let { m -> SeedIds.member(m) }
                it[stateReason] = post.stateReason
                it[aiAssisted] = false
            }
            insertRows(listOf(row))
            if (post.state == SocialPostState.REMOVED_LEGAL) removed += post
            inserted++
        }
        boosts.forEach { boost ->
            val boostId = SeedIds.social(0x2000 + boost.index)
            val target = SocialPostTable.selectAll().where { SocialPostTable.id eq SeedIds.social(boost.post) }.singleOrNull()
            val row = boostRow(boost = boost, clock = clock)
            val ok =
                SocialPostBoostTable.selectAll().where { SocialPostBoostTable.id eq boostId }.empty() &&
                    target != null &&
                    target[SocialPostTable.state] == SocialPostState.VISIBLE &&
                    readableBy(visibility = target[SocialPostTable.visibility], status = statuses[boost.member]) &&
                    covers(listOf(row))
            if (!ok) {
                skipped++
                return@forEach
            }
            SocialPostBoostTable.insert {
                it[id] = boostId
                it[postId] = SeedIds.social(boost.post)
                it[memberId] = SeedIds.member(boost.member)
                it[amountLtr] = boost.amount
                it[boostedAt] = boostStamp(boost = boost, clock = clock)
            }
            insertRows(listOf(row))
            inserted++
        }
        // The audit chain is the last locking operation of the transaction, like in the live path. The snapshot never carries the content.
        removed.forEach { post ->
            AuditLogRecorder.record(
                actorMemberId = SeedIds.member(MODERATOR),
                actorRole = AccountRole.BOARD,
                entityType = AuditEntityType.SOCIAL_POST,
                entityId = post.id,
                action = AuditAction.UPDATE,
                before =
                    Json.encodeToString(
                        SocialPostModerationSnapshot(
                            state = SocialPostState.VISIBLE,
                            stateReason = null,
                            visibility = post.visibility,
                            contentErasedAt = null,
                        ),
                    ),
                after =
                    Json.encodeToString(
                        SocialPostModerationSnapshot(
                            state = SocialPostState.REMOVED_LEGAL,
                            stateReason = post.stateReason,
                            visibility = post.visibility,
                            contentErasedAt = null,
                        ),
                    ),
            )
        }
        return StagingSeedPartResult(inserted = inserted, skipped = skipped)
    }
}
