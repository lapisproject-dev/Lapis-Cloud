package network.lapis.cloud.server.rpc

import dev.kilua.rpc.types.Decimal
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.SocialPostBoostTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.economy.LtrBalanceProvider
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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
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
 * Soziales Netzwerk, Welle V1.1.1 "Fundament & Post-Kern" + Welle V1.1.2 "Kommentarbaum, Boosts,
 * rekursive Gesamtgewichtung" + Welle V1.1.3 "Öffentlicher SEO-Lesepfad" -- see
 * `32-social-network.kuml.kts` file header and [ISocialNetworkService] KDoc for the full fachlich
 * model. Write pattern is 1:1 modelled after `CrowdfundingService.submitProject` (rate limit ->
 * validate -> membership gate -> `lockForDebit` -> `freeBalance` check -> insert business row ->
 * insert ledger debit), see that method's own KDoc for the lock-ordering rationale this mirrors.
 * **Lock-Reihenfolge im gesamten Modul: POST-Zeile -> MEMBER-Zeile, niemals umgekehrt** -- sonst
 * Deadlock gegen einen parallelen `boostPost`/`createComment`, bzw. gegen
 * `CrowdfundingService`/`GovernanceService`.
 *
 * **Welle V1.1.3**: the actual load/aggregate/map pipeline behind [listTimeline]/[getPost]/
 * [getThread] was extracted (moved, not copied) into [SocialReadPipeline] -- it is now shared
 * verbatim with the new unauthenticated public HTTP read path
 * (`network.lapis.cloud.server.routes.SocialPublicRoutes`). This class retains everything
 * caller-specific: auth (`resolveCurrentMember`), rate limiting, and building the
 * [SocialVisibility.readableByCondition]-based `condition`/`nodeReadable` arguments the pipeline
 * takes as parameters. `SocialReadPipeline.SocialReadCaps.AUTHENTICATED` is passed at every call
 * site below -- the public path uses its own, stricter `.PUBLIC` caps instead, see that class KDoc.
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
                // Welle V1.1.4: requireActiveMembership -> requireLtrEligibleMembership (ACTIVE UND
                // FRIEND). Der zurückgegebene Status wird sofort für requireVisibilityAllowedFor
                // gebraucht -- ein FRIEND darf keine MEMBERS_ONLY-Sichtbarkeit wählen, siehe deren KDoc.
                val callerStatus = requireLtrEligibleMembership(memberId = current.memberId)
                requireVisibilityAllowedFor(status = callerStatus, visibility = input.visibility)

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
                // Welle V1.1.4: requireLtrEligibleMembership -- keine Sichtbarkeitsprüfung nötig wie
                // bei createPost, ein Kommentar erbt die Stufe vom Wurzel-Post (S5, rootVisibilityOf
                // unten) und der isReadable-Check gegen den Aufrufer läuft ohnehin schon direkt darunter.
                requireLtrEligibleMembership(memberId = current.memberId)

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
            // Welle V1.1.4: requireLtrEligibleMembership -- keine Sichtbarkeitsprüfung nötig, der
            // isReadable-Check gegen den Aufrufer läuft direkt darunter (gleiche Begründung wie bei
            // createComment).
            requireLtrEligibleMembership(memberId = current.memberId)
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
            // SocialReadPipeline.SocialReadCaps.AUTHENTICATED.workingSetRows below still caps
            // selfHiddenView as a separate, unconditional DoS backstop. Welle V1.1.2 extends the
            // same exemption to the subtree load below (the `horizon` argument passed to
            // SocialReadPipeline.timelinePage).
            if (!selfHiddenView) {
                condition = condition and (SocialPostTable.publishedAt greaterEq horizon)
            }

            val subtreeHorizon = if (selfHiddenView) null else horizon
            SocialReadPipeline.timelinePage(
                condition = condition,
                horizon = subtreeHorizon,
                limit = limit,
                offset = offset,
                now = now,
                viewerStatus = current.status,
                caps = SocialReadPipeline.SocialReadCaps.AUTHENTICATED,
                ltrBalanceProvider = ltrBalanceProvider,
            )
        }
    }

    /**
     * Sichtbarkeitsprüfung in der Query selbst (nicht nachträglich in Kotlin) -- ein nicht
     * gefundener Post und ein existierender, aber für [current] nicht sichtbarer Post liefern
     * identisch [NotFoundException], kein Existenz-Orakel (siehe Implementierungsplan § 7.2 X3).
     *
     * **Kostenfalle (Welle V1.1.2)**: [SocialReadPipeline.post] lädt zur Berechnung von
     * [SocialPostDto.totalCurrentWeightLtr] und den Zählern den ganzen Thread -- `getPost` auf
     * einen Knoten eines 5000-Knoten-Threads lädt jetzt den ganzen Thread. Das ist unvermeidbar
     * (das Gesamtgewicht ist definitionsgemäß eine Baumsumme) und durch
     * [SocialPostWeight.THREAD_MAX_NODES] + [readRateLimiter] gedeckelt.
     */
    override suspend fun getPost(id: String): SocialPostDto {
        val current = resolveCurrentMember(call)
        requireReadRateLimit(memberId = current.memberId)
        val postId = id.toSocialUuid()
        val now = DbClock.nowLocalDateTime()
        return transaction {
            SocialReadPipeline.post(
                postUuid = postId,
                condition = SocialVisibility.readableByCondition(status = current.status),
                now = now,
                viewerStatus = current.status,
                caps = SocialReadPipeline.SocialReadCaps.AUTHENTICATED,
                ltrBalanceProvider = ltrBalanceProvider,
            ) ?: throw NotFoundException("SocialPost $id not found")
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
            SocialReadPipeline.thread(
                rootUuid = rootUuid,
                condition = SocialVisibility.readableByCondition(status = current.status),
                nodeReadable = { v, s -> SocialVisibility.isReadable(visibility = v, state = s, status = current.status) },
                now = now,
                viewerStatus = current.status,
                caps = SocialReadPipeline.SocialReadCaps.AUTHENTICATED,
                ltrBalanceProvider = ltrBalanceProvider,
            ) ?: throw NotFoundException("SocialPost $rootId not found")
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
     * `WeightDecayClock.daysElapsed`, never calendar-day subtraction.
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
     * Welle V1.1.4. Ein [MemberStatusSets.NON_MEMBER]-Autor darf **keine**
     * [SocialPostVisibility.MEMBERS_ONLY]-Stufe wählen -- er könnte den entstehenden Post danach
     * weder lesen ([SocialVisibility.readableByCondition]) noch über [hideOwnPost] wieder
     * unsichtbar machen (dessen S-B1-Lesbarkeitsprüfung läuft VOR der Eigentümerprüfung), während
     * sein LTR-Einsatz unwiderruflich gebunden bliebe. Serverseitig, nicht nur clientseitig: der
     * Client filtert die Auswahl zwar ebenfalls (SocialNetworkScreen), aber die Invariante muss auch
     * gegen einen direkten RPC-Aufruf halten. [SocialPostVisibility.MEMBERS_AND_EXTERNAL] und
     * [SocialPostVisibility.PUBLIC] bleiben für ihn offen -- beide sind für ihn lesbar.
     *
     * Gilt nur für [createPost]: [createComment] erbt die Stufe vom Wurzel-Post (S5) und kann sie
     * gar nicht wählen, und ein Kommentar entsteht ohnehin nur unter einem für den Aufrufer bereits
     * lesbaren Elternknoten.
     *
     * **Security-Audit-Runde 1, F3 (2026-08-19, reine Dokumentation, kein Verhaltensfix)**: der
     * vorgelagerte [requireLtrEligibleMembership]-Aufruf in [createPost] liest den Status OHNE
     * `forUpdate` (`forUpdate = false`, der Default). Theoretisch könnte derselbe Nutzer in einer
     * zweiten, zeitgleichen Aktion in genau dem Fenster zwischen diesem Statuslesevorgang und dem
     * Commit von [createPost] seinen eigenen Status ändern (z. B. `applyForMembership` auslösen),
     * sodass die hier geprüfte `status`-Variable bereits geringfügig veraltet ist, wenn dieser
     * Guard sie auswertet. Kein Angreifergewinn -- der Nutzer kann sich nur gegen SICH SELBST
     * "racen" -- und strukturell identisch zu jedem Alt-Post eines später ausgetretenen Mitglieds
     * (ein bereits veröffentlichter Post bleibt unter seiner ursprünglichen Sichtbarkeit stehen).
     * Kein Fix nötig laut Audit; siehe [requireMembershipStatusIn] KDoc für die allgemeine
     * `forUpdate`-Faustregel, der dieser Aufruf bewusst folgt (`social_post` ist eine
     * Inhaltszeile, keine Autoritäts-Zeile).
     */
    private fun requireVisibilityAllowedFor(
        status: MemberStatus,
        visibility: SocialPostVisibility,
    ) {
        if (status in MemberStatusSets.NON_MEMBER && visibility == SocialPostVisibility.MEMBERS_ONLY) {
            throw ConflictException(
                "Sichtbarkeitsstufe MEMBERS_ONLY steht nur Mitgliedern der Organisation offen",
            )
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
     * Security-Audit-Fund S-A1 (2026-08-18): called by [createPost]/[createComment]/[boostPost]/
     * [hideOwnPost] AFTER their own write transaction has already committed -- opens a SECOND,
     * separate, lock-free read transaction that runs [SocialReadPipeline.post]'s subtree
     * aggregation with NO row lock held on either the POST or MEMBER row.
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
     * The row itself is already committed by the time this runs, so `condition = Op.TRUE` (a plain,
     * unlocked lookup by id alone) is sufficient and correct -- there is nothing left to protect
     * against a concurrent writer here, only against reading a row that does not exist yet, which
     * cannot happen since the write transaction above already committed it.
     */
    private fun loadPostAfterCommit(
        id: Uuid,
        now: LocalDateTime,
        viewerStatus: MemberStatus,
    ): SocialPostDto =
        transaction {
            SocialReadPipeline.post(
                postUuid = id,
                condition = Op.TRUE,
                now = now,
                viewerStatus = viewerStatus,
                caps = SocialReadPipeline.SocialReadCaps.AUTHENTICATED,
                ltrBalanceProvider = ltrBalanceProvider,
            ) ?: throw NotFoundException("SocialPost $id not found")
        }
}

internal fun String.toSocialUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
