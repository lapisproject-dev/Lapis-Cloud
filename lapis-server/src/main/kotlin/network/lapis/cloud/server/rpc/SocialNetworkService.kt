package network.lapis.cloud.server.rpc

import dev.kilua.rpc.types.Decimal
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SocialPostBoostTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.economy.WeightDecayClock
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.SocialCommentInput
import network.lapis.cloud.shared.domain.SocialPostDto
import network.lapis.cloud.shared.domain.SocialPostInput
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.domain.SocialThreadDto
import network.lapis.cloud.shared.domain.SocialTimelinePageDto
import network.lapis.cloud.shared.domain.SocialTimelineQuery
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.ISocialNetworkService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** Content-Längenbegrenzung -- current-understanding constant, same disclaimer class as `CrowdfundingService.MIN_INITIAL_WEIGHT_LTR`. DoS-/Missbrauchs-Guard, keine fachliche Konzept-Vorgabe. */
private const val MAX_CONTENT_LENGTH = 5_000

/** [SocialTimelineQuery.limit] Deckelung -- Pagination-DoS-Guard, analog zu anderen `coerceIn`-Pagination-Caps in diesem Codebase. */
private const val MAX_TIMELINE_LIMIT = 100

/**
 * Review-Fund S1 (2026-08-18): defensiver DB-seitiger Deckel für [SocialNetworkService.listTimeline]s
 * Kandidaten-Menge -- UNABHÄNGIG von [SocialTimelineQuery.limit]/`.offset` (der Seitengröße), weil
 * die eigentliche Sortierung nach Gewicht in Kotlin passiert (Gewicht ist nirgends eine SQL-Spalte,
 * siehe [SocialPostWeight] KDoc), nicht per SQL `ORDER BY` -- ein `.limit(offset + limit)` VOR der
 * Sortierung würde beliebige statt die tatsächlich gewichtsstärksten Zeilen liefern und die
 * Rangfolge stillschweigend verfälschen. Dieser Deckel greift zusätzlich zum
 * [SocialPostWeight.RANKING_HORIZON_DAYS]-Zeitfenster-Filter (der die Kandidatenmenge fachlich
 * bereits stark eingrenzt) als reiner Speicher-/Query-Größen-Backstop für den Fall, dass eine
 * Organisation binnen des Horizonts dennoch ungewöhnlich viele Posts anhäuft -- bei
 * SQL-seitigem `ORDER BY published_at DESC` vor dem Cut werden im (praktisch unerreichbaren)
 * Überlauf-Fall die neuesten Beiträge bevorzugt behalten, ein plausibler Least-Surprise-Kompromiss,
 * kein Korrektheits-Anspruch für Rang N jenseits dieses Deckels. NEU-5 (Review Runde 2,
 * 2026-08-18): der `RANKING_HORIZON_DAYS`-Filter gilt NICHT für `selfHiddenView` (die eigene
 * Übersicht des Autors, siehe [SocialNetworkService.listTimeline]) -- dieser Deckel hier bleibt
 * dort trotzdem als unabhängiger Speicher-Backstop aktiv.
 */
private const val MAX_TIMELINE_WORKING_SET_ROWS = 2_000

/**
 * Soziales Netzwerk, Welle V1.1.1 "Fundament & Post-Kern" + Welle V1.1.2 "Kommentarbaum, Boosts,
 * rekursive Gesamtgewichtung" -- see `32-social-network.kuml.kts` file header and
 * [ISocialNetworkService] KDoc for the full fachlich model. Write pattern is 1:1 modelled after
 * `CrowdfundingService.submitProject` (rate limit -> validate -> membership gate ->
 * `lockForDebit` -> `freeBalance` check -> insert business row -> insert ledger debit), see that
 * method's own KDoc for the lock-ordering rationale this mirrors. **Lock-Reihenfolge im gesamten
 * Modul: POST-Zeile -> MEMBER-Zeile, niemals umgekehrt** -- sonst Deadlock gegen einen parallelen
 * `boostPost`/`createComment`, bzw. gegen `CrowdfundingService`/`GovernanceService`.
 *
 * [createRateLimiter]/[readRateLimiter]/[boostRateLimiter] are request-rate limiters (not
 * failure-rate ones, see [FederationInboxRateLimiter] KDoc) -- module-scoped, constructed once in
 * `Application.module`, NEVER as a constructor default (a default-argument instance would be
 * minted fresh on every `registerService` factory-lambda invocation, silently defeating the
 * throttle -- see that function's own KDoc "conferenceGuestAccessRateLimiter" note for the
 * documented precedent of this exact mistake class). [readRateLimiter] was added in Review-Fund S1
 * (2026-08-18) -- until then [listTimeline]/[getPost] had no rate limit at all, unlike every other
 * public-facing read path in this codebase (see e.g. `streamingReadRateLimiter`/
 * `conferenceListRateLimiter`). [boostRateLimiter] is NEW in Welle V1.1.2 -- see
 * [requireBoostRateLimit] KDoc for why it is its OWN limiter rather than sharing
 * [createRateLimiter]'s budget the way [createComment] does.
 */
class SocialNetworkService(
    private val call: ApplicationCall,
    private val createRateLimiter: FederationInboxRateLimiter,
    private val readRateLimiter: FederationInboxRateLimiter,
    private val boostRateLimiter: FederationInboxRateLimiter,
    private val ltrBalanceProvider: LtrBalanceProvider = LedgerBackedLtrBalanceProvider(),
) : ISocialNetworkService {
    override suspend fun createPost(input: SocialPostInput): SocialPostDto {
        val current = resolveCurrentMember(call)
        requireRateLimit(memberId = current.memberId)
        val normalized = normalizeWeight(weight = input.initialWeightLtr)
        requireContentWithinLimits(input.content)
        val now = DbClock.nowLocalDateTime()
        // Security-Audit-Fund S-A1 (2026-08-18): the write transaction below ends the moment the
        // new row is committed -- it no longer also loads/aggregates the subtree for the returned
        // DTO. See [loadPostAfterCommit] KDoc for why.
        val postId =
            transaction {
                // Welle 1: requireActiveMembership -- ab Welle V1.1.4: requireLtrEligibleMembership
                // (siehe Implementierungsplan § 4.5 und § 4.9).
                requireActiveMembership(memberId = current.memberId)

                // Serializes this debit-causing read-then-write against every other LTR-debiting call
                // for this same member -- see LtrBalanceProvider.lockForDebit KDoc, same idiom as
                // CrowdfundingService.submitProject.
                ltrBalanceProvider.lockForDebit(current.memberId)
                val freeBalance = ltrBalanceProvider.freeBalance(current.memberId)
                if (normalized > freeBalance) {
                    throw ConflictException("initialWeightLtr $normalized exceeds free LTR balance $freeBalance")
                }

                val postId = Uuid.random()
                SocialPostTable.insert {
                    it[id] = postId
                    it[parentId] = null
                    it[rootId] = postId
                    it[depth] = 0
                    it[authorMemberId] = current.memberId
                    it[content] = input.content
                    it[visibility] = input.visibility
                    it[initialWeightLtr] = normalized
                    it[publishedAt] = now
                    it[state] = SocialPostState.VISIBLE
                    it[stateChangedAt] = null
                    it[stateChangedBy] = null
                    it[stateReason] = null
                }
                LtrLedgerEntryTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = current.memberId
                    it[entryType] = LtrLedgerEntryType.SOCIAL_POST_STAKE
                    it[amountLtr] = normalized.negate()
                    it[referenceType] = LtrLedgerReferenceType.SOCIAL_POST
                    it[referenceId] = postId
                    // E5: die Note traegt bewusst KEINEN Inhaltsausschnitt, nur eine ID-Referenz -- der
                    // Inhaltsbezug wird zur Lesezeit ueber referenceId hergestellt (LtrLedgerScreen.kt
                    // Deep-Link seit Welle V1.1.2, siehe SocialNetworkScreen.kt/Routing.kt).
                    it[note] = "Beitragsgewicht fuer Social Post $postId"
                    it[createdBy] = null
                    it[createdAt] = now
                }
                postId
            }
        return loadPostAfterCommit(id = postId, now = now, viewerStatus = current.status)
    }

    /**
     * S5 (Welle V1.1.2): [SocialCommentInput.visibility] does not exist -- the new comment inherits
     * its ROOT post's `visibility`, read explicitly from `root_id`, never copied from the direct
     * parent (see [rootVisibilityOf] KDoc). `REMOVED_LEGAL` == "existiert nicht", checked BEFORE
     * [SocialVisibility.isReadable] and BEFORE the ordinary `state != VISIBLE` conflict, exactly the
     * [hideOwnPost]/[getPost] precedent this domain already established.
     */
    override suspend fun createComment(input: SocialCommentInput): SocialPostDto {
        val current = resolveCurrentMember(call)
        // N-2: shares createRateLimiter's budget with createPost/hideOwnPost -- see that limiter's
        // own KDoc "N-2" paragraph. A comment is a stake-binding creation, same frequency class.
        requireRateLimit(memberId = current.memberId)
        val parentUuid = input.parentId.toSocialUuid()
        val normalized = normalizeWeight(weight = input.initialWeightLtr)
        requireContentWithinLimits(input.content)
        val now = DbClock.nowLocalDateTime()
        // Security-Audit-Fund S-A1 (2026-08-18): see [createPost] KDoc note / [loadPostAfterCommit]
        // KDoc -- the subtree aggregation for the returned DTO no longer runs while this write
        // transaction's row locks (parent POST row, then MEMBER row) are still held.
        val commentId =
            transaction {
                requireActiveMembership(memberId = current.memberId)

                // Sperre 1 (von 2). Lock-Reihenfolge im gesamten Modul: POST-Zeile -> MEMBER-Zeile.
                // Niemals umgekehrt -- sonst Deadlock gegen einen parallelen boostPost/createComment.
                val parent =
                    SocialPostTable
                        .selectAll()
                        .where { SocialPostTable.id eq parentUuid }
                        .forUpdate()
                        .singleOrNull()
                        ?: throw NotFoundException("SocialPost ${input.parentId} not found")

                if (parent[SocialPostTable.state] == SocialPostState.REMOVED_LEGAL) {
                    throw NotFoundException("SocialPost ${input.parentId} not found")
                }
                // Der Aufrufer darf den Elternknoten ueberhaupt sehen? Sonst koennte man an einen
                // MEMBERS_ONLY-Post "blind" andocken und seine Existenz erschliessen (kein Orakel).
                if (!SocialVisibility.isReadable(
                        visibility = parent[SocialPostTable.visibility],
                        state = parent[SocialPostTable.state],
                        status = current.status,
                    )
                ) {
                    throw NotFoundException("SocialPost ${input.parentId} not found")
                }
                if (parent[SocialPostTable.state] != SocialPostState.VISIBLE) {
                    throw ConflictException("SocialPost ${input.parentId} is not VISIBLE")
                }
                val commentDepth = parent[SocialPostTable.depth] + 1
                if (commentDepth > SocialPostWeight.MAX_DEPTH) {
                    throw ConflictException("Maximale Verschachtelungstiefe erreicht")
                }

                val commentVisibility = rootVisibilityOf(parent)

                // Sperre 2. Serialisiert diesen Debit gegen jeden anderen LTR-Debit desselben Mitglieds.
                ltrBalanceProvider.lockForDebit(current.memberId)
                val freeBalance = ltrBalanceProvider.freeBalance(current.memberId)
                if (normalized > freeBalance) {
                    throw ConflictException("initialWeightLtr $normalized exceeds free LTR balance $freeBalance")
                }

                val commentId = Uuid.random()
                SocialPostTable.insert {
                    it[id] = commentId
                    it[SocialPostTable.parentId] = parentUuid
                    it[rootId] = parent[SocialPostTable.rootId]
                    it[depth] = commentDepth
                    it[authorMemberId] = current.memberId
                    it[content] = input.content
                    it[visibility] = commentVisibility
                    it[initialWeightLtr] = normalized
                    it[publishedAt] = now
                    it[state] = SocialPostState.VISIBLE
                    it[stateChangedAt] = null
                    it[stateChangedBy] = null
                    it[stateReason] = null
                }
                LtrLedgerEntryTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = current.memberId
                    it[entryType] = LtrLedgerEntryType.SOCIAL_POST_STAKE
                    it[amountLtr] = normalized.negate()
                    it[referenceType] = LtrLedgerReferenceType.SOCIAL_POST
                    it[referenceId] = commentId
                    it[note] = "Beitragsgewicht fuer Social Post $commentId"
                    it[createdBy] = null
                    it[createdAt] = now
                }
                commentId
            }
        return loadPostAfterCommit(id = commentId, now = now, viewerStatus = current.status)
    }

    /**
     * Monetäres Like -- siehe [ISocialNetworkService.boostPost] KDoc für die volle fachliche
     * Beschreibung (S3/S4/E6/K5). **Lock-Kontention bewusst akzeptiert**: `FOR UPDATE` auf die
     * Post-Zeile serialisiert alle Boosts auf denselben Beitrag. Die Alternative (ungesperrt lesen)
     * öffnet ein TOCTOU-Fenster (ein Boost landet unter einem in derselben Sekunde rechtlich
     * entfernten Post) -- einheitliches Verhalten ist hier mehr wert als Durchsatz.
     */
    override suspend fun boostPost(
        postId: String,
        amountLtr: Decimal,
    ): SocialPostDto {
        val current = resolveCurrentMember(call)
        requireBoostRateLimit(memberId = current.memberId)
        val id = postId.toSocialUuid()
        val normalized = normalizeWeight(weight = amountLtr, fieldName = "amountLtr")
        val now = DbClock.nowLocalDateTime()
        // Security-Audit-Fund S-A1 (2026-08-18): see [createPost] KDoc note / [loadPostAfterCommit]
        // KDoc -- the subtree aggregation for the returned DTO no longer runs while this write
        // transaction's row locks (POST row, then MEMBER row) are still held.
        transaction {
            requireActiveMembership(memberId = current.memberId)
            val post =
                SocialPostTable
                    .selectAll()
                    .where { SocialPostTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("SocialPost $postId not found")
            if (post[SocialPostTable.state] == SocialPostState.REMOVED_LEGAL) {
                throw NotFoundException("SocialPost $postId not found")
            }
            if (!SocialVisibility.isReadable(
                    visibility = post[SocialPostTable.visibility],
                    state = post[SocialPostTable.state],
                    status = current.status,
                )
            ) {
                throw NotFoundException("SocialPost $postId not found")
            }
            if (post[SocialPostTable.state] != SocialPostState.VISIBLE) {
                throw ConflictException("SocialPost $postId is not VISIBLE")
            }

            // E6: Doppelklick-Fenster. Kein UNIQUE-Constraint (S3) -- ein legitimer zweiter Boost am
            // Folgetag muss moeglich bleiben; identischer Betrag desselben Mitglieds auf denselben
            // Post binnen 5 Sekunden ist dagegen praktisch immer ein Doppel-Submit.
            val windowStart =
                (now.toInstant(TimeZone.UTC) - SocialPostWeight.BOOST_DUPLICATE_WINDOW).toLocalDateTime(TimeZone.UTC)
            val duplicate =
                SocialPostBoostTable
                    .selectAll()
                    .where {
                        (SocialPostBoostTable.postId eq id) and
                            (SocialPostBoostTable.memberId eq current.memberId) and
                            (SocialPostBoostTable.amountLtr eq normalized) and
                            (SocialPostBoostTable.boostedAt greaterEq windowStart)
                    }.limit(1)
                    .any()
            if (duplicate) throw ConflictException("Identischer Boost wurde soeben schon gebucht -- bitte kurz warten.")

            // Sperre 2 -- Reihenfolge Post -> Member, s. o.
            ltrBalanceProvider.lockForDebit(current.memberId)
            val freeBalance = ltrBalanceProvider.freeBalance(current.memberId)
            if (normalized > freeBalance) {
                throw ConflictException("amountLtr $normalized exceeds free LTR balance $freeBalance")
            }

            SocialPostBoostTable.insert {
                it[SocialPostBoostTable.id] = Uuid.random()
                it[SocialPostBoostTable.postId] = id
                it[memberId] = current.memberId
                it[SocialPostBoostTable.amountLtr] = normalized
                it[boostedAt] = now
            }
            LtrLedgerEntryTable.insert {
                it[LtrLedgerEntryTable.id] = Uuid.random()
                it[memberId] = current.memberId
                it[entryType] = LtrLedgerEntryType.SOCIAL_POST_BOOST
                it[LtrLedgerEntryTable.amountLtr] = normalized.negate()
                it[referenceType] = LtrLedgerReferenceType.SOCIAL_POST
                // K5: die POST-Id, nicht die Boost-Id -- der Deep-Link soll auf den Beitrag zeigen.
                it[referenceId] = id
                it[note] = "Boost fuer Social Post $id" // E5: kein Inhaltsausschnitt
                it[createdBy] = null
                it[createdAt] = now
            }
        }
        return loadPostAfterCommit(id = id, now = now, viewerStatus = current.status)
    }

    override suspend fun listTimeline(query: SocialTimelineQuery): SocialTimelinePageDto {
        val current = resolveCurrentMember(call)
        requireReadRateLimit(memberId = current.memberId)
        val now = DbClock.nowLocalDateTime()
        val horizon = rankingHorizon(now)
        val limit = query.limit.coerceIn(1, MAX_TIMELINE_LIMIT)
        val offset = query.offset.coerceAtLeast(0)
        return transaction {
            val parentUuid = query.parentId?.toSocialUuid()
            val authorUuid = query.authorMemberId?.toSocialUuid()
            val selfHiddenView = query.includeHidden && authorUuid == current.memberId

            // Fund #4 (Review Runde 1, 2026-08-18): a parentId filter used to become nothing more
            // than `SocialPostTable.parentId eq parentUuid` -- if that parent was itself
            // HIDDEN_BY_AUTHOR (K2), its children kept coming back here even though getThread
            // already suppresses them for the exact same parent. Same "existiert nicht" gate every
            // other read/write of a parent post in this service already uses
            // (createComment/boostPost/getThread): readable AND VISIBLE, else NotFoundException --
            // never a silent empty page, which would still leak the parent's existence/state via a
            // 200-with-zero-rows vs 404 distinction.
            if (parentUuid != null) {
                val parentRow =
                    SocialPostTable
                        .selectAll()
                        .where { (SocialPostTable.id eq parentUuid) and SocialVisibility.readableByCondition(status = current.status) }
                        .singleOrNull()
                        ?: throw NotFoundException("SocialPost ${query.parentId} not found")
                if (parentRow[SocialPostTable.state] != SocialPostState.VISIBLE) {
                    throw NotFoundException("SocialPost ${query.parentId} not found")
                }
            }

            var condition: Op<Boolean> =
                SocialVisibility.readableByCondition(status = current.status) and
                    (if (parentUuid != null) SocialPostTable.parentId eq parentUuid else SocialPostTable.parentId.isNull())
            condition =
                if (selfHiddenView) {
                    condition and
                        (SocialPostTable.state inList listOf(SocialPostState.VISIBLE, SocialPostState.HIDDEN_BY_AUTHOR)) and
                        (SocialPostTable.authorMemberId eq current.memberId)
                } else {
                    condition and (SocialPostTable.state eq SocialPostState.VISIBLE)
                }
            if (authorUuid != null && !selfHiddenView) {
                condition = condition and (SocialPostTable.authorMemberId eq authorUuid)
            }
            // Review-Fund S1 (2026-08-18): a listTimeline call with NO horizon filter loaded the
            // entire (unbounded, ever-growing) social_post table on every call -- see
            // SocialPostWeight.RANKING_HORIZON_DAYS KDoc for the decay-derived cutoff this applies.
            //
            // NEU-5 (Review Runde 2, 2026-08-18): this filter must NOT apply to selfHiddenView --
            // that branch is the author's own complete overview of their own posts (not a
            // "ranking" in the weight-sorted sense the horizon exists to bound), so an own post
            // older than the horizon must not silently vanish from the author's own view of it.
            // MAX_TIMELINE_WORKING_SET_ROWS below still caps selfHiddenView as a separate,
            // unconditional DoS backstop. Welle V1.1.2 extends the same exemption to the subtree
            // load below (loadSubtreeRows' own `horizon` argument).
            if (!selfHiddenView) {
                condition = condition and (SocialPostTable.publishedAt greaterEq horizon)
            }

            // Security-Audit-Fund S-3 (2026-08-18): ranking only ever needs id/publishedAt/rootId
            // here (Welle V1.1.2: rootId added, to resolve the candidate roots' full subtrees below)
            // -- loading every OTHER column (content up to 5000 chars, visibility, state, ...) for
            // up to MAX_TIMELINE_WORKING_SET_ROWS rows just to discard all but <=MAX_TIMELINE_LIMIT
            // of them afterwards was pure waste. Project only the ranking-relevant columns here; the
            // full row is loaded further below, ONLY for the <=MAX_TIMELINE_LIMIT ids that actually
            // survive paging.
            val rankingRows =
                SocialPostTable
                    .select(
                        SocialPostTable.id,
                        SocialPostTable.publishedAt,
                        SocialPostTable.rootId,
                        // Fund #9 (Review Runde 1, 2026-08-18): needed below ONLY as the ranking
                        // comparator's fallback when totalWeightById is missing an entry -- still a
                        // single scalar column, not the S-3 violation `content` would be.
                        SocialPostTable.initialWeightLtr,
                    ).where { condition }
                    // Defensive backstop, independent of the horizon filter above -- see
                    // MAX_TIMELINE_WORKING_SET_ROWS KDoc for why this can't just be
                    // .limit(offset + limit): sorting by weight happens below, in Kotlin, not here.
                    .orderBy(SocialPostTable.publishedAt, SortOrder.DESC)
                    .limit(MAX_TIMELINE_WORKING_SET_ROWS)
                    .toList()

            // Welle V1.1.2: resolve every candidate root's FULL subtree in ONE additional query
            // (root_id inList ...), then aggregate Gesamtgewicht in Kotlin -- see SocialPostWeight
            // KDoc "Live-rekursive Berechnung zur Lesezeit, ohne SQL-Rekursion".
            val rootIds = rankingRows.map { it[SocialPostTable.rootId] }.distinct()
            val subtreeHorizon = if (selfHiddenView) null else horizon
            val subtreeRows =
                loadSubtreeRows(rootIds = rootIds, horizon = subtreeHorizon, maxRows = SocialPostWeight.TIMELINE_MAX_DESCENDANT_ROWS)
            val weightNodes = subtreeRows.map { it.toWeightNode() }
            val subtreeIds = subtreeRows.map { it[SocialPostTable.id] }
            val boosts = loadBoosts(postIds = subtreeIds, maxRows = SocialPostWeight.TIMELINE_MAX_BOOST_ROWS)
            // Review-Fund S1 (2026-08-18) lesson, still true in V1.1.2: compute every row's weight
            // EXACTLY ONCE into a map before sorting -- never inside the comparator.
            //
            // Security-Audit-Fund S-A2 (2026-08-18): a single aggregateWeightsUnrounded call yields
            // BOTH totalWeightById and ownWeightById from the same internal fold -- see that
            // function's KDoc. Previously ownWeightByIdOf recomputed every node's own weight a
            // SECOND time below, discarding the internal ownById map aggregateWeightsUnrounded (nee
            // totalWeightsUnrounded) already built and threw away.
            val aggregated = SocialPostWeight.aggregateWeightsUnrounded(nodes = weightNodes, boostsByPostId = boosts, now = now)
            val totalWeightById = aggregated.totalById

            // Fund #9 (Review Runde 1, 2026-08-18): the fallback here must match toDtos' own
            // fallback (Eigengewicht, not BigDecimal.ZERO) -- a missing entry in totalWeightById
            // only happens in the (practically unreachable) TIMELINE_MAX_DESCENDANT_ROWS overflow
            // case, and a ZERO fallback here while toDtos still shows the row's real own weight
            // would rank the SAME row far lower than what its own displayed weight suggests.
            // Recomputing ownWeightUnrounded costs nothing extra: initialWeightLtr/publishedAt are
            // already part of this lightweight ranking projection.
            val ranked =
                rankingRows.sortedWith(
                    compareByDescending<ResultRow> { row ->
                        totalWeightById[row[SocialPostTable.id]] ?: SocialPostWeight.ownWeightUnrounded(
                            initialWeightLtr = row[SocialPostTable.initialWeightLtr],
                            publishedAt = row[SocialPostTable.publishedAt],
                            now = now,
                        )
                    }.thenByDescending { it[SocialPostTable.publishedAt] }
                        .thenBy { it[SocialPostTable.id].toString() },
                )
            // S-3: resolve the final page's ids from the lightweight ranking above FIRST, then load
            // full rows for ONLY those ids -- never for the whole (up to 2000-row) candidate set.
            val pageIds = ranked.drop(offset).take(limit).map { it[SocialPostTable.id] }
            val fullRowById =
                if (pageIds.isEmpty()) {
                    emptyMap()
                } else {
                    // N-1 (Welle V1.1.2): re-apply `condition`, not just `id inList pageIds` -- the
                    // ids already came from a condition-filtered set, so this changes nothing
                    // observable, but keeps the visibility invariant explicit at every query where
                    // it must hold (defense in depth, deliberately NOT "optimized away").
                    SocialPostTable
                        .selectAll()
                        .where { (SocialPostTable.id inList pageIds) and condition }
                        .associateBy { it[SocialPostTable.id] }
                }
            // `inList` gives no row-order guarantee of its own -- rebuild the page in ranked order.
            val page = pageIds.mapNotNull { fullRowById[it] }

            val stateById = subtreeRows.associate { it[SocialPostTable.id] to it[SocialPostTable.state] }
            val suppressed = SocialPostWeight.suppressedIds(nodes = weightNodes, stateById = stateById)
            val countsById = SocialPostWeight.descendantCounts(weightNodes.filter { it.id !in suppressed })
            val boostCountById = boosts.mapValues { it.value.size }
            val ownWeightById = aggregated.ownById

            SocialTimelinePageDto(
                posts =
                    toDtos(
                        rows = page,
                        now = now,
                        viewerStatus = current.status,
                        totalWeightById = totalWeightById,
                        countsById = countsById,
                        boostCountById = boostCountById,
                        ownWeightById = ownWeightById,
                    ),
                totalRankedCount = ranked.size,
                rankingHorizonFrom = ranked.lastOrNull()?.get(SocialPostTable.publishedAt) ?: now,
            )
        }
    }

    /**
     * Sichtbarkeitsprüfung in der Query selbst (nicht nachträglich in Kotlin) -- ein nicht
     * gefundener Post und ein existierender, aber für [current] nicht sichtbarer Post liefern
     * identisch [NotFoundException], kein Existenz-Orakel (siehe Implementierungsplan § 7.2 X3).
     *
     * **Kostenfalle (Welle V1.1.2)**: ergänzt um einen Teilbaum-Load (`rootId eq row[rootId]`,
     * gedeckelt bei [SocialPostWeight.THREAD_MAX_NODES]) zur Berechnung von
     * [SocialPostDto.totalCurrentWeightLtr] und den Zählern -- `getPost` auf einen Knoten eines
     * 5000-Knoten-Threads lädt jetzt den ganzen Thread. Das ist unvermeidbar (das Gesamtgewicht ist
     * definitionsgemäß eine Baumsumme) und durch [SocialPostWeight.THREAD_MAX_NODES] +
     * [readRateLimiter] gedeckelt.
     */
    override suspend fun getPost(id: String): SocialPostDto {
        val current = resolveCurrentMember(call)
        requireReadRateLimit(memberId = current.memberId)
        val postId = id.toSocialUuid()
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val row =
                SocialPostTable
                    .selectAll()
                    .where { (SocialPostTable.id eq postId) and SocialVisibility.readableByCondition(status = current.status) }
                    .singleOrNull()
                    ?: throw NotFoundException("SocialPost $id not found")
            dtoWithSubtreeAggregation(row = row, now = now, viewerStatus = current.status)
        }
    }

    /**
     * Vollständiger Teilbaum ab [rootId] -- siehe [ISocialNetworkService.getThread] KDoc (K3/K4).
     * Eine Nicht-Wurzel-ID oder eine nicht-`VISIBLE` Wurzel liefern identisch `NotFoundException`
     * (K3: kein Existenz-Orakel über ein leeres `nodes` mit gefülltem `totalNodeCount`).
     */
    override suspend fun getThread(rootId: String): SocialThreadDto {
        val current = resolveCurrentMember(call)
        requireReadRateLimit(memberId = current.memberId)
        val rootUuid = rootId.toSocialUuid()
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val rootRow =
                SocialPostTable
                    .selectAll()
                    .where { (SocialPostTable.id eq rootUuid) and SocialVisibility.readableByCondition(status = current.status) }
                    .singleOrNull()
                    ?: throw NotFoundException("SocialPost $rootId not found")
            // K4: only a genuine root id is accepted -- a comment id must resolve via getPost(id).rootId first.
            if (rootRow[SocialPostTable.rootId] != rootUuid) throw NotFoundException("SocialPost $rootId not found")
            // K3: no existence oracle -- a hidden/removed root is NotFound, not an empty-but-counted thread.
            if (rootRow[SocialPostTable.state] != SocialPostState.VISIBLE) throw NotFoundException("SocialPost $rootId not found")

            val subtreeRows =
                loadSubtreeRows(
                    rootIds = listOf(rootUuid),
                    horizon = null,
                    maxRows = SocialPostWeight.THREAD_MAX_NODES + 1,
                    rootFirst = true,
                )
            val totalNodeCount = subtreeRows.size
            val truncated = totalNodeCount > SocialPostWeight.THREAD_MAX_NODES
            val limitedRows = if (truncated) subtreeRows.take(SocialPostWeight.THREAD_MAX_NODES) else subtreeRows

            val weightNodes = limitedRows.map { it.toWeightNode() }
            val nodeIds = limitedRows.map { it[SocialPostTable.id] }
            val boosts = loadBoosts(postIds = nodeIds, maxRows = SocialPostWeight.TIMELINE_MAX_BOOST_ROWS)
            // Security-Audit-Fund S-A2 (2026-08-18): one aggregateWeightsUnrounded call yields both
            // maps -- see listTimeline's own identical fix for the full rationale.
            val aggregated = SocialPostWeight.aggregateWeightsUnrounded(nodes = weightNodes, boostsByPostId = boosts, now = now)
            val totalWeightById = aggregated.totalById
            val stateById = limitedRows.associate { it[SocialPostTable.id] to it[SocialPostTable.state] }
            val suppressed = SocialPostWeight.suppressedIds(nodes = weightNodes, stateById = stateById)
            val countsById = SocialPostWeight.descendantCounts(weightNodes.filter { it.id !in suppressed })
            val boostCountById = boosts.mapValues { it.value.size }
            val ownWeightById = aggregated.ownById

            // X4 Defense-in-Depth: exclude a node whose OWN visibility/state, checked individually
            // (not inherited from the root), is not readable for this caller -- on top of state-based
            // suppression above.
            val displayableIds =
                limitedRows
                    .filter { row ->
                        val id = row[SocialPostTable.id]
                        id !in suppressed &&
                            SocialVisibility.isReadable(
                                visibility = row[SocialPostTable.visibility],
                                state = row[SocialPostTable.state],
                                status = current.status,
                            )
                    }.map { it[SocialPostTable.id] }
            // [loadSubtreeRows]' slim projection has no authorMemberId/content/stateReason -- [toDtos]
            // needs the FULL row. Reload full rows for ONLY the ids that survive filtering, same "full
            // row only for what actually renders" discipline as [listTimeline]'s own N-1 page reload.
            //
            // Fund #11 (Review Runde 1, 2026-08-18): re-apply [SocialVisibility.readableByCondition]
            // here too, not just `id inList displayableIds` -- [displayableIds] was already
            // visibility-filtered above via [SocialVisibility.isReadable] on the in-memory rows, so
            // this changes nothing observable today, but keeps the visibility invariant explicit at
            // EVERY query where it must hold, exactly the defense-in-depth discipline
            // [listTimeline]'s own N-1 fix documents -- deliberately NOT "optimized away".
            val fullRowById =
                if (displayableIds.isEmpty()) {
                    emptyMap()
                } else {
                    SocialPostTable
                        .selectAll()
                        .where {
                            (SocialPostTable.id inList displayableIds) and
                                SocialVisibility.readableByCondition(status = current.status)
                        }.associateBy { it[SocialPostTable.id] }
                }
            val preorder = buildPreorder(rows = displayableIds.mapNotNull { fullRowById[it] }, totalWeightById = totalWeightById)

            SocialThreadDto(
                nodes =
                    toDtos(
                        rows = preorder,
                        now = now,
                        viewerStatus = current.status,
                        totalWeightById = totalWeightById,
                        countsById = countsById,
                        boostCountById = boostCountById,
                        ownWeightById = ownWeightById,
                    ),
                truncated = truncated,
                totalNodeCount = totalNodeCount,
            )
        }
    }

    /**
     * NEU-4 (Review Runde 2, 2026-08-18): a [SocialPostState.REMOVED_LEGAL] post is treated as
     * not-existent for EVERY caller, including its own author (see [SocialVisibility
     * .readableByCondition] KDoc "identisch zu 'existiert nicht'", and [getPost]'s own
     * `NotFoundException` for the same state). This method selects by id alone (not via
     * [SocialVisibility.readableByCondition], which is written for read paths and would also apply
     * a visibility-tier filter this author-only write path doesn't want) and previously fell
     * through into the generic `state != VISIBLE` branch below, throwing `ConflictException` --
     * leaking BOTH that the post exists AND that it is legally removed to its own author, exactly
     * the oracle [getPost] is written to avoid. The REMOVED_LEGAL check below runs BEFORE the
     * [ForbiddenException] ownership check for the same reason [SocialVisibility] excludes it
     * unconditionally: nobody, not even the author, may distinguish "does not exist" from
     * "exists but is legally removed" via this endpoint.
     *
     * Security-Audit-Fund S-2 (2026-08-18): [requireRateLimit] runs FIRST, before [id] is even
     * parsed -- same "reject before touching the DB" ordering [createPost] already uses. Before
     * this fix, every call took a `SELECT ... FOR UPDATE` (holding a pool connection) with no
     * throttle at all, unlike every other write/read path in this service.
     *
     * **K2 (Welle V1.1.2): kein Cascade-Write.** Diese Methode schreibt ausschließlich ihre EIGENE
     * Zeile -- die Kaskade auf Kind-Posts entsteht ausschließlich zur LESEZEIT über
     * [SocialPostWeight.suppressedIds] ([getThread]). Ein Cascade-`UPDATE` würde Zeilen FREMDER
     * Autoren umschreiben (`HIDDEN_BY_AUTHOR` heißt wörtlich "vom Autor versteckt" -- es auf einen
     * fremden Kommentar zu schreiben wäre eine falsche Zuschreibung in einer unveränderlichen
     * Historie), wäre unbeschränkt groß (ein 5000-Knoten-Thread = 5000 Zeilen in einer Transaktion,
     * die zusätzlich `lockForDebit`-Sperren blockiert), und die Unterdrückung ist über den ohnehin
     * für die Gewichtsrechnung bereits geladenen Teilbaum GRATIS -- siehe
     * [SocialPostWeight.suppressedIds] KDoc.
     *
     * **Security-Audit-Fund S-B1 (2026-08-18): Sichtbarkeitsprüfung VOR der Eigentümerprüfung.**
     * Vorher lief die [ForbiddenException]-Eigentümerprüfung VOR der Sichtbarkeitsprüfung -- ein
     * authentifizierter Aufrufer konnte dadurch über den Unterschied `NotFoundException` (nicht
     * lesbar) vs. `ForbiddenException` (lesbar, aber fremder Post) ein Existenz-Orakel für
     * eigentlich unsichtbare Posts konstruieren (z. B. einen `MEMBERS_ONLY`-Post als
     * `NON_MEMBER`-Aufrufer erschließen, obwohl dieser Aufrufer ihn nie zu Gesicht bekommen dürfte).
     * Jeder andere Endpunkt dieses Service ([getPost]/[getThread]/[createComment]/[boostPost]/
     * [listTimeline]) prüft [SocialVisibility.isReadable]/`readableByCondition` bereits VOR jeder
     * anderen, spezifischeren Prüfung -- dieselbe Reihenfolge gilt jetzt auch hier.
     */
    override suspend fun hideOwnPost(postId: String): SocialPostDto {
        val current = resolveCurrentMember(call)
        requireRateLimit(memberId = current.memberId)
        val id = postId.toSocialUuid()
        val now = DbClock.nowLocalDateTime()
        // Security-Audit-Fund S-A1 (2026-08-18): see [createPost] KDoc note / [loadPostAfterCommit]
        // KDoc -- the subtree aggregation for the returned DTO no longer runs while this write
        // transaction's POST row lock is still held.
        transaction {
            val row =
                SocialPostTable
                    .selectAll()
                    .where { SocialPostTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("SocialPost $postId not found")
            if (row[SocialPostTable.state] == SocialPostState.REMOVED_LEGAL) {
                throw NotFoundException("SocialPost $postId not found")
            }
            // S-B1: readability gate BEFORE the ownership check -- see this method's own KDoc.
            if (!SocialVisibility.isReadable(
                    visibility = row[SocialPostTable.visibility],
                    state = row[SocialPostTable.state],
                    status = current.status,
                )
            ) {
                throw NotFoundException("SocialPost $postId not found")
            }
            if (row[SocialPostTable.authorMemberId] != current.memberId) throw ForbiddenException()
            if (row[SocialPostTable.state] != SocialPostState.VISIBLE) {
                throw ConflictException("SocialPost $postId is already ${row[SocialPostTable.state]}")
            }
            val updated =
                SocialPostTable.update({
                    (SocialPostTable.id eq id) and (SocialPostTable.state eq SocialPostState.VISIBLE)
                }) {
                    it[state] = SocialPostState.HIDDEN_BY_AUTHOR
                    it[stateChangedAt] = now
                    it[stateChangedBy] = current.memberId
                }
            if (updated == 0) throw ConflictException("SocialPost $postId was concurrently changed -- retry")
        }
        return loadPostAfterCommit(id = id, now = now, viewerStatus = current.status)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────────────

    /**
     * Shared by [createPost], [createComment], and, since Security-Audit-Fund S-2 (2026-08-18),
     * [hideOwnPost] too -- all three are mutating actions gated by the same [createRateLimiter]
     * budget, deliberately NOT separate limiter instances (see [requireBoostRateLimit] KDoc for why
     * [boostPost] is the one exception).
     */
    private fun requireRateLimit(memberId: Uuid) {
        if (!createRateLimiter.checkAndRecord(memberId.toString())) {
            throw ConflictException("Zu viele Beiträge in kurzer Zeit -- bitte kurz warten und erneut versuchen.")
        }
    }

    /** Review-Fund S1 (2026-08-18): [listTimeline]/[getPost]/[getThread] previously had no rate limit at all. */
    private fun requireReadRateLimit(memberId: Uuid) {
        if (!readRateLimiter.checkAndRecord(memberId.toString())) {
            throw ConflictException("Zu viele Anfragen in kurzer Zeit -- bitte kurz warten und erneut versuchen.")
        }
    }

    /**
     * NEU Welle V1.1.2 (K6). [boostPost] gets its OWN module-scoped limiter (30/min) rather than
     * sharing [createRateLimiter]'s budget the way [createComment] does: a boost is a frequent,
     * cheap gesture (one click, plausibly several times per session), while
     * `createPost`/`createComment`/`hideOwnPost` are rarer, more consequential creations. A shared
     * budget would either throttle legitimate boosting or effectively raise the post-spam ceiling
     * to accommodate it -- neither is acceptable, so this action class gets its own budget instead.
     */
    private fun requireBoostRateLimit(memberId: Uuid) {
        if (!boostRateLimiter.checkAndRecord(memberId.toString())) {
            throw ConflictException("Zu viele Boosts in kurzer Zeit -- bitte kurz warten und erneut versuchen.")
        }
    }

    /**
     * Review-Fund S1 (2026-08-18): [now] minus [SocialPostWeight.RANKING_HORIZON_DAYS], via
     * `Instant`-difference against a fixed [TimeZone.UTC] reference -- same discipline as
     * [WeightDecayClock.daysElapsed], never calendar-day subtraction.
     */
    private fun rankingHorizon(now: LocalDateTime): LocalDateTime =
        (now.toInstant(TimeZone.UTC) - SocialPostWeight.RANKING_HORIZON_DAYS.days).toLocalDateTime(TimeZone.UTC)

    /**
     * Fund #10 (Review Runde 1, 2026-08-18): [fieldName] is spliced into both thrown messages --
     * before this fix, a [boostPost] call that failed this check got an "initialWeightLtr ..."
     * `ConflictException`, which is simply wrong for a boost amount (there is no `initialWeightLtr`
     * field anywhere near [boostPost]'s own [ISocialNetworkService.boostPost] signature).
     */
    private fun normalizeWeight(
        weight: BigDecimal,
        fieldName: String = "initialWeightLtr",
    ): BigDecimal {
        if (weight.scale() > 2) throw ConflictException("$fieldName must have at most 2 decimal places")
        val normalized = weight.setScale(2, RoundingMode.UNNECESSARY)
        if (normalized < SocialPostWeight.MIN_WEIGHT_LTR) {
            throw ConflictException("$fieldName $normalized is below the minimum of ${SocialPostWeight.MIN_WEIGHT_LTR} LTR")
        }
        return normalized
    }

    private fun requireContentWithinLimits(content: String) {
        if (content.isBlank()) throw ConflictException("content must not be blank")
        if (content.length > MAX_CONTENT_LENGTH) {
            throw ConflictException("content exceeds the maximum length of $MAX_CONTENT_LENGTH characters")
        }
    }

    /**
     * S5: liest die Sichtbarkeit des WURZEL-Posts, nicht des direkten Elternteils -- explizit über
     * `root_id` nachgeschlagen (eine zusätzliche Punkt-Query, wenn `parent.parentId != null`), NICHT
     * vom Elternteil abgeschrieben. Das ist der einzige Ort, an dem diese Invariante beim Schreiben
     * entsteht -- eine einmal eingeschleuste abweichende Zeile würde sich sonst nach unten
     * fortpflanzen, wenn spätere Kommentare einfach `parent[visibility]` kopierten.
     */
    private fun rootVisibilityOf(parent: ResultRow): SocialPostVisibility {
        val rootId = parent[SocialPostTable.rootId]
        if (parent[SocialPostTable.id] == rootId) return parent[SocialPostTable.visibility]
        return SocialPostTable.selectAll().where { SocialPostTable.id eq rootId }.single()[SocialPostTable.visibility]
    }

    /**
     * Lädt den vollständigen Wald unter [rootIds] in EINER Query (`root_id inList rootIds`) -- die
     * Wurzeln selbst eingeschlossen, weil `root_id` für eine Wurzel auf sie selbst zeigt
     * (V4-Invariante). KEIN ebenenweiser Abstieg, KEIN `WITH RECURSIVE`: `root_id` (S1) macht den
     * Teilbaum zu einem flachen Prädikat, und die eigentliche Rekursion findet in
     * [SocialPostWeight.totalWeightsUnrounded] statt -- in Kotlin/`BigDecimal`, weil die
     * Zerfallsmathematik niemals in SQL wandern darf (`POWER()` ist in H2 wie in Postgres
     * Fließkomma, das bricht die 40-Jahre-Reproduzierbarkeit).
     *
     * Schlanke Projektion (`id`, `parentId`, `rootId`, `depth`, `initialWeightLtr`, `publishedAt`,
     * `state`, `visibility`) -- NIEMALS `selectAll()`: `content` ist bis zu 5000 Zeichen und wird
     * für die Aggregation nie gebraucht (dieselbe Lehre wie Security-Fund S-3 auf der Wurzel-Ebene).
     * [horizon] ist `null` für [getThread]/[getPost] (dort will man den vollständigen Thread) und
     * gesetzt für [listTimeline].
     *
     * **Fund #2 (Review Runde 1, 2026-08-18)**: ein reiner `publishedAt DESC`-Cut lässt bei jedem
     * Überlauf (`maxRows` überschritten) die WURZEL herausfallen -- die Wurzel ist per Definition
     * der ÄLTESTE Knoten ihres Teilbaums, also immer das erste Opfer eines "behalte die neuesten N"-
     * Schnitts. Fehlt die Wurzel im Zeilensatz, findet [buildPreorder] (das nur bei `parentId ==
     * null` startet) keine Wurzel mehr und liefert einen LEEREN Thread mit `truncated = true` --
     * ein übergroßer, aber realer Thread, der wie ein leerer aussieht, und ein günstiger DoS-Hebel
     * (ca. 5000 billige Kommentare auf einen Post, und der gesamte Thread verschwindet für jeden
     * Leser). [rootFirst] behebt das für die Thread-LESEPFADE ([getThread]/
     * [dtoWithSubtreeAggregation]): Sortierung `depth ASC, publishedAt ASC` statt `publishedAt
     * DESC` -- die Wurzel hat `depth = 0`, ist also IMMER die allererste Zeile, und weil jedes Kind
     * per Konstruktion `depth = parent.depth + 1` hat, kann ein `.limit()`/`.take()`-Schnitt auf
     * dieser Sortierung nur Knoten verlieren, deren ELTERNTEIL bereits früher in derselben
     * Sortierung enthalten war -- kein erreichbares-aber-elternloses Kind entsteht. [listTimeline]s
     * eigener Teilbaum-Load behält bewusst das ALTE `publishedAt DESC`-Verhalten (siehe
     * [SocialPostWeight.TIMELINE_MAX_DESCENDANT_ROWS] KDoc "die jüngsten Nachfahren tragen das
     * meiste Gewicht") -- dort sind die Kandidaten-Wurzeln bereits über `rankingRows` fixiert, eine
     * dort verlorene Wurzel ist also nicht dasselbe Fehlerbild wie hier.
     */
    private fun loadSubtreeRows(
        rootIds: List<Uuid>,
        horizon: LocalDateTime?,
        maxRows: Int,
        rootFirst: Boolean = false,
    ): List<ResultRow> {
        if (rootIds.isEmpty()) return emptyList()
        var condition: Op<Boolean> = SocialPostTable.rootId inList rootIds
        if (horizon != null) {
            condition = condition and (SocialPostTable.publishedAt greaterEq horizon)
        }
        val query =
            SocialPostTable
                .select(
                    SocialPostTable.id,
                    SocialPostTable.parentId,
                    SocialPostTable.rootId,
                    SocialPostTable.depth,
                    SocialPostTable.initialWeightLtr,
                    SocialPostTable.publishedAt,
                    SocialPostTable.state,
                    SocialPostTable.visibility,
                ).where { condition }
        val sorted =
            if (rootFirst) {
                query.orderBy(SocialPostTable.depth to SortOrder.ASC, SocialPostTable.publishedAt to SortOrder.ASC)
            } else {
                query.orderBy(SocialPostTable.publishedAt, SortOrder.DESC)
            }
        return sorted.limit(maxRows).toList()
    }

    /** Alle Boosts zu [postIds] in EINER Query -- Anti-N+1-Muster wie `CrowdfundingService.reactionCountsByProject`. */
    private fun loadBoosts(
        postIds: List<Uuid>,
        maxRows: Int,
    ): Map<Uuid, List<SocialPostWeight.BoostContribution>> {
        if (postIds.isEmpty()) return emptyMap()
        return SocialPostBoostTable
            .select(SocialPostBoostTable.postId, SocialPostBoostTable.amountLtr, SocialPostBoostTable.boostedAt)
            .where { SocialPostBoostTable.postId inList postIds }
            .orderBy(SocialPostBoostTable.boostedAt, SortOrder.DESC)
            .limit(maxRows)
            .toList()
            .groupBy { it[SocialPostBoostTable.postId] }
            .mapValues { (_, rows) ->
                rows.map {
                    SocialPostWeight.BoostContribution(
                        amountLtr = it[SocialPostBoostTable.amountLtr],
                        boostedAt = it[SocialPostBoostTable.boostedAt],
                    )
                }
            }
    }

    /**
     * K1: builds the flat preorder [rows] must be delivered in for [SocialThreadDto.nodes] --
     * root(s) first, then each node's children ordered by [totalWeightById] descending, tiebreak
     * `publishedAt` ascending, then `id`. Recursion depth is bounded by [SocialPostWeight.MAX_DEPTH]
     * (64, enforced at write time in [createComment]), so this is never at risk of a stack overflow
     * regardless of a thread's breadth (up to [SocialPostWeight.THREAD_MAX_NODES] siblings at any
     * one level are fine -- only the recursion DEPTH matters for stack safety, and that is capped).
     */
    private fun buildPreorder(
        rows: List<ResultRow>,
        totalWeightById: Map<Uuid, BigDecimal>,
    ): List<ResultRow> {
        val byParent = rows.filter { it[SocialPostTable.parentId] != null }.groupBy { it[SocialPostTable.parentId] }
        val roots = rows.filter { it[SocialPostTable.parentId] == null }
        val siblingComparator =
            compareByDescending<ResultRow> { totalWeightById[it[SocialPostTable.id]] ?: BigDecimal.ZERO }
                .thenBy { it[SocialPostTable.publishedAt] }
                .thenBy { it[SocialPostTable.id].toString() }
        val result = mutableListOf<ResultRow>()

        fun visit(node: ResultRow) {
            result += node
            byParent[node[SocialPostTable.id]].orEmpty().sortedWith(siblingComparator).forEach { visit(it) }
        }
        roots.sortedWith(siblingComparator).forEach { visit(it) }
        return result
    }

    private fun ResultRow.toWeightNode(): SocialPostWeight.WeightNode =
        SocialPostWeight.WeightNode(
            id = this[SocialPostTable.id],
            parentId = this[SocialPostTable.parentId],
            depth = this[SocialPostTable.depth],
            initialWeightLtr = this[SocialPostTable.initialWeightLtr],
            publishedAt = this[SocialPostTable.publishedAt],
        )

    /**
     * Shared by [getPost] and [loadPostOrThrow]: loads [row]'s full subtree (via its `root_id`),
     * aggregates Gesamtgewicht/Zähler/Boosts, and returns the DTO for exactly this one row. See
     * [getPost] KDoc "Kostenfalle" for the cost this incurs on a large thread.
     */
    private fun dtoWithSubtreeAggregation(
        row: ResultRow,
        now: LocalDateTime,
        viewerStatus: MemberStatus,
    ): SocialPostDto {
        val rootId = row[SocialPostTable.rootId]
        val subtreeRows =
            loadSubtreeRows(rootIds = listOf(rootId), horizon = null, maxRows = SocialPostWeight.THREAD_MAX_NODES, rootFirst = true)
        val weightNodes = subtreeRows.map { it.toWeightNode() }
        val nodeIds = subtreeRows.map { it[SocialPostTable.id] }
        val boosts = loadBoosts(postIds = nodeIds, maxRows = SocialPostWeight.TIMELINE_MAX_BOOST_ROWS)
        // Security-Audit-Fund S-A2 (2026-08-18): one aggregateWeightsUnrounded call yields both
        // maps -- see listTimeline's own identical fix for the full rationale.
        val aggregated = SocialPostWeight.aggregateWeightsUnrounded(nodes = weightNodes, boostsByPostId = boosts, now = now)
        val totalWeightById = aggregated.totalById
        val stateById = subtreeRows.associate { it[SocialPostTable.id] to it[SocialPostTable.state] }
        val suppressed = SocialPostWeight.suppressedIds(nodes = weightNodes, stateById = stateById)
        val countsById = SocialPostWeight.descendantCounts(weightNodes.filter { it.id !in suppressed })
        val boostCountById = boosts.mapValues { it.value.size }
        val ownWeightById = aggregated.ownById
        return toDtos(
            rows = listOf(row),
            now = now,
            viewerStatus = viewerStatus,
            totalWeightById = totalWeightById,
            countsById = countsById,
            boostCountById = boostCountById,
            ownWeightById = ownWeightById,
        ).single()
    }

    private fun loadPostOrThrow(
        id: Uuid,
        now: LocalDateTime,
        viewerStatus: MemberStatus,
    ): SocialPostDto {
        val row =
            SocialPostTable.selectAll().where { SocialPostTable.id eq id }.singleOrNull()
                ?: throw NotFoundException("SocialPost $id not found")
        return dtoWithSubtreeAggregation(row = row, now = now, viewerStatus = viewerStatus)
    }

    /**
     * Security-Audit-Fund S-A1 (2026-08-18): called by [createPost]/[createComment]/[boostPost]/
     * [hideOwnPost] AFTER their own write transaction has already committed -- opens a SECOND,
     * separate, lock-free read transaction that runs [loadPostOrThrow]'s subtree aggregation (up to
     * [SocialPostWeight.THREAD_MAX_NODES] rows plus up to [SocialPostWeight.TIMELINE_MAX_BOOST_ROWS]
     * boost rows) with NO row lock held on either the POST or MEMBER row.
     *
     * Before this fix, that same aggregation ran INSIDE the write transaction, while the `SELECT
     * ... FOR UPDATE` lock(s) taken earlier in that same transaction (POST row for
     * `boostPost`/`createComment`/`hideOwnPost`, then the MEMBER row via
     * `LtrBalanceProvider.lockForDebit`) were still held. On a large thread, that serialized every
     * OTHER boost/comment against the same root behind the row lock for as long as the
     * aggregation took -- and because every blocked writer holds its own pool connection while it
     * waits, enough concurrent load against one large thread could exhaust the entire
     * `LAPIS_DB_POOL_SIZE` connection pool (default 10, see [network.lapis.cloud.server.db
     * .DatabaseConfig]), starving the WHOLE application, not just the social network module.
     *
     * The row itself is already committed by the time this runs, so a plain, unlocked `SELECT` is
     * sufficient and correct -- there is nothing left to protect against a concurrent writer here,
     * only against reading a row that does not exist yet, which cannot happen since the write
     * transaction above already committed it.
     */
    private fun loadPostAfterCommit(
        id: Uuid,
        now: LocalDateTime,
        viewerStatus: MemberStatus,
    ): SocialPostDto = transaction { loadPostOrThrow(id = id, now = now, viewerStatus = viewerStatus) }

    /**
     * Batched author lookup (display name + free LTR balance) -- ONE query for every distinct
     * author across [rows], not one query per row, same anti-N+1 discipline as
     * `CrowdfundingService.reactionCountsByProject`/`LedgerBackedLtrBalanceProvider.freeBalances`.
     *
     * Security-Audit-Fund S-1 (2026-08-18): [SocialPostDto.authorFreeBalanceLtr] is only populated
     * for a [viewerStatus] that is [MemberStatusSets.ORGANIZATION_MEMBER] -- same tier
     * `LtrLedgerService.getMemberBalance` already requires for a FOREIGN member's balance (there
     * via `LTR_TREASURY_ROLES`, an even narrower gate; here it's every ORGANIZATION_MEMBER,
     * because the "Gewicht des Autors" figure is a deliberate, member-facing meritocracy feature
     * of this timeline -- see [SocialNetworkScreen.kt][network.lapis.cloud.client
     * .renderSocialPostCard]'s own KDoc). A [MemberStatusSets.NON_MEMBER] reader (self-registered
     * FRIEND, no board approval needed) or an APPLICATION/WITHDRAWN/REJECTED caller gets `null`
     * for every row instead -- and [ltrBalanceProvider.freeBalances] is skipped entirely for that
     * caller, so this is also one fewer query for every non-member timeline read.
     *
     * **Welle V1.1.2**: [totalWeightById]/[countsById]/[boostCountById]/[ownWeightById] are
     * PRE-COMPUTED by the caller, NEVER re-derived here per row (S1 lesson -- see
     * [SocialPostWeight] KDoc). A missing entry in any of them (can happen for [loadPostOrThrow]
     * right after an insert, before any real subtree existed to load from -- though in practice
     * [dtoWithSubtreeAggregation] always loads at least the row itself) defaults to Eigengewicht/0,
     * never an exception, never a silent `getValue` crash on the production path.
     */
    private fun toDtos(
        rows: List<ResultRow>,
        now: LocalDateTime,
        viewerStatus: MemberStatus,
        totalWeightById: Map<Uuid, BigDecimal>,
        countsById: Map<Uuid, SocialPostWeight.DescendantCounts>,
        boostCountById: Map<Uuid, Int>,
        ownWeightById: Map<Uuid, BigDecimal>,
    ): List<SocialPostDto> {
        if (rows.isEmpty()) return emptyList()
        val authorIds = rows.map { it[SocialPostTable.authorMemberId] }.distinct()
        val displayNames =
            MemberTable
                .selectAll()
                .where { MemberTable.id inList authorIds }
                .associate { it[MemberTable.id] to it[MemberTable.displayName] }
        val freeBalances =
            if (viewerStatus in MemberStatusSets.ORGANIZATION_MEMBER) {
                ltrBalanceProvider.freeBalances(authorIds)
            } else {
                emptyMap()
            }
        return rows.map { row ->
            val id = row[SocialPostTable.id]
            val authorId = row[SocialPostTable.authorMemberId]
            val ownWeight =
                ownWeightById[id] ?: SocialPostWeight.ownWeightUnrounded(
                    initialWeightLtr = row[SocialPostTable.initialWeightLtr],
                    publishedAt = row[SocialPostTable.publishedAt],
                    now = now,
                )
            val totalWeight = totalWeightById[id] ?: ownWeight
            val counts = countsById[id] ?: SocialPostWeight.DescendantCounts(direct = 0, total = 0)
            SocialPostDto(
                id = id.toString(),
                parentId = row[SocialPostTable.parentId]?.toString(),
                rootId = row[SocialPostTable.rootId].toString(),
                depth = row[SocialPostTable.depth],
                authorMemberId = authorId.toString(),
                authorDisplayName = displayNames[authorId] ?: "",
                authorFreeBalanceLtr = freeBalances[authorId],
                content = row[SocialPostTable.content],
                visibility = row[SocialPostTable.visibility],
                state = row[SocialPostTable.state],
                stateReason = row[SocialPostTable.stateReason],
                initialWeightLtr = row[SocialPostTable.initialWeightLtr],
                ownCurrentWeightLtr = WeightDecayClock.round2(ownWeight),
                totalCurrentWeightLtr = WeightDecayClock.round2(totalWeight),
                directCommentCount = counts.direct,
                totalDescendantCount = counts.total,
                boostCount = boostCountById[id] ?: 0,
                publishedAt = row[SocialPostTable.publishedAt],
            )
        }
    }
}

internal fun String.toSocialUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
