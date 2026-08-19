package network.lapis.cloud.server.rpc

import dev.kilua.rpc.types.Decimal
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SocialPostBoostTable
import network.lapis.cloud.server.db.generated.SocialPostErasureTable
import network.lapis.cloud.server.db.generated.SocialPostReportTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.SocialCommentInput
import network.lapis.cloud.shared.domain.SocialPostDto
import network.lapis.cloud.shared.domain.SocialPostErasureDto
import network.lapis.cloud.shared.domain.SocialPostErasureInput
import network.lapis.cloud.shared.domain.SocialPostErasureStatus
import network.lapis.cloud.shared.domain.SocialPostInput
import network.lapis.cloud.shared.domain.SocialPostModerationSnapshot
import network.lapis.cloud.shared.domain.SocialPostRemovalNoticeDto
import network.lapis.cloud.shared.domain.SocialPostReportDto
import network.lapis.cloud.shared.domain.SocialPostReportInput
import network.lapis.cloud.shared.domain.SocialPostReportStatus
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.domain.SocialThreadDto
import network.lapis.cloud.shared.domain.SocialTimelinePageDto
import network.lapis.cloud.shared.domain.SocialTimelineQuery
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.ISocialNetworkService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
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

/** Welle V1.1.5 -- BOARD/ADMIN, Muster `ContributionService.kt`'s eigene `X_BOARD_ROLES`-Konstante. */
private val SOCIAL_MODERATION_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/** `SocialPostTable.stateReason`-Spaltenbreite, Pflicht-Begründung von [SocialNetworkService.removePostForLegalReason]. */
private const val MAX_MODERATION_REASON_LENGTH = 2_000

/** `SocialPostErasureTable.requester_contact`-Spaltenbreite. */
private const val MAX_CONTACT_LENGTH = 320

/** `SocialPostErasureTable.reason`-Spaltenbreite. */
private const val MAX_ERASURE_REASON_LENGTH = 4_000

/** Pagination-Deckel für [SocialNetworkService.listReports]/[SocialNetworkService.listContentErasures] -- Muster `AuditLogService.MAX_PAGE_SIZE`. */
private const val MAX_MODERATION_PAGE_SIZE = 200

/** Kürzung von `content` für [SocialPostReportDto.postExcerpt] -- kein Volltext in der Moderationsliste nötig. */
private const val REPORT_POST_EXCERPT_LENGTH = 300

/**
 * `SocialPostReportTable.decision_note`/`SocialPostErasureTable.decision_note`-Spaltenbreite (beide
 * `VARCHAR(2000)`, siehe `32-social-network.kuml.kts`). Review-Fund 5 (Runde 1, 2026-08-19): ohne
 * diese Prüfung wirft ein `note`-Wert oberhalb der Spaltenbreite eine rohe `ExposedSQLException` ->
 * HTTP 500 statt eines sauberen `ConflictException`, anders als jedes andere Freitextfeld dieser
 * Welle ([requireModerationReason]/[requireErasureReason]/[requireContactLength]).
 */
private const val MAX_DECISION_NOTE_LENGTH = 2_000

/**
 * Welle V1.1.5 (E-B). Defensiv: [SocialNetworkService.removePostForLegalReason] erzwingt eine
 * nichtleere Begründung, aber weder ein Renderer noch [SocialNetworkService.getRemovalNotice]
 * dürfen je auf `null` laufen -- selbe Konstante wie `SocialPublicRoutes.LEGAL_REMOVAL_FALLBACK_REASON`,
 * bewusst dupliziert (zwei-Zeilen, nicht-domänenlogische Textkonstante, dieselbe Duplikations-
 * Disziplin wie `rankingHorizon` in `SocialPublicRoutes.kt`).
 */
internal const val LEGAL_REMOVAL_FALLBACK_REASON = "Dieser Beitrag wurde aus rechtlichen Gründen entfernt."

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
    /** Welle V1.1.5 -- `removePostForLegalReason`/`decideReport`/`decideContentErasure`/`executeContentErasure` (BOARD/ADMIN, 20/min). */
    private val moderationRateLimiter: FederationInboxRateLimiter,
    /** Welle V1.1.5 -- `reportPost` (jeder authentifizierte Aufrufer, 5/Stunde). */
    private val reportRateLimiter: FederationInboxRateLimiter,
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

            // Welle V1.1.5 (E-C, DSA Art. 17): selfHiddenView baut ab jetzt auf
            // SocialVisibility.ownAuthorViewCondition statt readableByCondition -- der Autor darf
            // seine EIGENEN Posts unabhaengig von seiner heutigen Sichtbarkeitsstufe sehen (dieselbe
            // Begruendung, aus der hideOwnPost bewusst kein Membership-Gate hat). readableByCondition
            // wuerde REMOVED_LEGAL unbedingt ausschliessen (ein `and` kann das nicht wieder aufheben)
            // -- genau der Zielkonflikt, den E-C aufloest: der Autor muss seine Entfernung + Grund
            // sehen koennen.
            val baseVisibilityCondition: Op<Boolean> =
                if (selfHiddenView) {
                    SocialVisibility.ownAuthorViewCondition(authorMemberId = current.memberId)
                } else {
                    SocialVisibility.readableByCondition(status = current.status)
                }
            val parentCondition: Op<Boolean> =
                if (parentUuid != null) SocialPostTable.parentId eq parentUuid else SocialPostTable.parentId.isNull()
            var condition: Op<Boolean> = baseVisibilityCondition and parentCondition
            condition =
                if (selfHiddenView) {
                    condition and
                        (
                            SocialPostTable.state inList
                                listOf(SocialPostState.VISIBLE, SocialPostState.HIDDEN_BY_AUTHOR, SocialPostState.REMOVED_LEGAL)
                        ) and
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

    // ── Welle V1.1.5 -- Moderation (DSA Art. 16/6) ──────────────────────────────────────────

    /**
     * Bewusst NICHT [hideOwnPost] kopiert -- siehe [ISocialNetworkService.removePostForLegalReason]
     * KDoc für die fachlichen Unterschiede. Rollen-Gate läuft als ALLERERSTE Anweisung, VOR jedem
     * Ressourcen-Lookup (Stolperfalle 3: eine Garbage-UUID muss `ForbiddenException` liefern, nicht
     * `NotFoundException`). **Fasst `SocialVisibility.isReadable` bewusst NICHT auf** -- ein
     * Moderator muss auch einen `MEMBERS_ONLY`-Post entfernen können, den er selbst nicht lesen
     * dürfte.
     */
    override suspend fun removePostForLegalReason(
        postId: String,
        reason: String,
    ): SocialPostDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*SOCIAL_MODERATION_ROLES)
        requireModerationRateLimit(memberId = current.memberId)
        val id = postId.toSocialUuid()
        requireModerationReason(reason)
        val now = DbClock.nowLocalDateTime()
        transaction {
            // Sperre 1. Bewusst KEIN SocialVisibility.isReadable-Gate hier (siehe Methoden-KDoc).
            val row =
                SocialPostTable
                    .selectAll()
                    .where { SocialPostTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("SocialPost $postId not found")
            // Kein Existenz-Orakel zu schuetzen: der Aufrufer ist bereits BOARD/ADMIN und darf den
            // Zustand kennen -- ConflictException statt NotFoundException, bewusste Abweichung von
            // hideOwnPost.
            if (row[SocialPostTable.state] == SocialPostState.REMOVED_LEGAL) {
                throw ConflictException("SocialPost $postId is already REMOVED_LEGAL")
            }
            val updated =
                SocialPostTable.update({
                    (SocialPostTable.id eq id) and (SocialPostTable.state neq SocialPostState.REMOVED_LEGAL)
                }) {
                    it[state] = SocialPostState.REMOVED_LEGAL
                    it[stateChangedAt] = now
                    it[stateChangedBy] = current.memberId
                    it[stateReason] = reason
                }
            if (updated == 0) throw ConflictException("SocialPost $postId was concurrently changed -- retry")

            // Offene Meldungen auf diesen Post automatisch schliessen -- decisionNote ist ein FESTER
            // interner Text, NIE der nun oeffentliche `reason` (Addendum § 4/Teil 3).
            SocialPostReportTable.update({
                (SocialPostReportTable.postId eq id) and
                    (SocialPostReportTable.status inList listOf(SocialPostReportStatus.OPEN, SocialPostReportStatus.UNDER_REVIEW))
            }) {
                it[status] = SocialPostReportStatus.ACTION_TAKEN
                it[decidedBy] = current.memberId
                it[decidedAt] = now
                it[decisionNote] = "Beitrag entfernt, siehe Moderationsbegruendung"
            }

            // Als LETZTE sperrende Operation -- Deadlock-Vertrag (Stolperfalle 2). Snapshot traegt
            // NIEMALS content (Stolperfalle 1).
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.SOCIAL_POST,
                entityId = id,
                action = AuditAction.UPDATE,
                before =
                    Json.encodeToString(
                        SocialPostModerationSnapshot(
                            state = row[SocialPostTable.state],
                            stateReason = row[SocialPostTable.stateReason],
                            visibility = row[SocialPostTable.visibility],
                            contentErasedAt = row[SocialPostTable.contentErasedAt],
                        ),
                    ),
                after =
                    Json.encodeToString(
                        SocialPostModerationSnapshot(
                            state = SocialPostState.REMOVED_LEGAL,
                            stateReason = reason,
                            visibility = row[SocialPostTable.visibility],
                            contentErasedAt = row[SocialPostTable.contentErasedAt],
                        ),
                    ),
            )
        }
        // condition = Op.TRUE (bewusst, siehe loadPostAfterCommit KDoc) -- der BOARD-Aufrufer soll
        // das Ergebnis inkl. state/stateReason sehen.
        return loadPostAfterCommit(id = id, now = now, viewerStatus = current.status)
    }

    /**
     * Kein Rollen-Gate -- jeder authentifizierte Aufrufer. Enumeration-Härtung: die Antwort ist
     * IMMER `Unit`, egal ob der Post existiert, für den Aufrufer lesbar ist, oder gar nicht (sonst
     * ein Existenz-Orakel für `MEMBERS_ONLY`-Posts, dieselbe Klasse Lücke wie S-B1). Der Autor darf
     * seinen eigenen Post nicht melden -- ebenfalls stiller No-Op.
     */
    override suspend fun reportPost(input: SocialPostReportInput) {
        val current = resolveCurrentMember(call)
        requireReportRateLimit(memberId = current.memberId)
        val postUuid = input.postId.toSocialUuid()
        val now = DbClock.nowLocalDateTime()
        // Geteilte Kernlogik mit dem oeffentlichen POST /s/{id}/report-Weg -- siehe
        // SocialReportSubmission KDoc ("die einzige Stelle, an der diese Frage beantwortet wird").
        transaction {
            SocialReportSubmission.submitAuthenticated(
                postId = postUuid,
                category = input.category,
                description = input.description,
                reporterContact = input.reporterContact,
                goodFaithConfirmed = input.goodFaithConfirmed,
                reporterMemberId = current.memberId,
                readableCondition = SocialVisibility.readableByCondition(status = current.status),
                now = now,
            )
        }
    }

    override suspend fun listReports(
        status: SocialPostReportStatus?,
        beforeReportedAt: LocalDateTime?,
        beforeId: String?,
    ): List<SocialPostReportDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*SOCIAL_MODERATION_ROLES)
        requireReadRateLimit(memberId = current.memberId)
        // Security-Audit-Fund MAJOR-2 (2026-08-19): the cursor is a COMPOSITE (reportedAt, id) --
        // only applied when BOTH halves are present (matches the interface KDoc: one set without
        // the other is treated as "no cursor", never an error). See [ISocialNetworkService
        // .listReports] KDoc for why a single sequence-number-style column does not exist here.
        val cursorId = beforeId?.let { it.toSocialUuid() }
        return transaction {
            // Bewusst KEIN SocialVisibility-Filter -- siehe SocialVisibility.moderationReadableCondition
            // KDoc: der Vorstand muss eine Meldung zu einem bereits REMOVED_LEGAL/MEMBERS_ONLY-Post
            // im Kontext sehen koennen.
            val conditions = mutableListOf<Op<Boolean>>()
            if (status != null) conditions += (SocialPostReportTable.status eq status)
            if (beforeReportedAt != null && cursorId != null) {
                conditions +=
                    (SocialPostReportTable.reportedAt less beforeReportedAt) or
                    (
                        (SocialPostReportTable.reportedAt eq beforeReportedAt) and
                            (SocialPostReportTable.id less cursorId)
                    )
            }
            val baseQuery =
                SocialPostReportTable
                    .join(SocialPostTable, JoinType.INNER, SocialPostReportTable.postId, SocialPostTable.id)
                    .selectAll()
            val filtered = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
            filtered
                .orderBy(SocialPostReportTable.reportedAt to SortOrder.DESC, SocialPostReportTable.id to SortOrder.DESC)
                .limit(MAX_MODERATION_PAGE_SIZE)
                .map { it.toReportDto() }
        }
    }

    override suspend fun decideReport(
        reportId: String,
        decision: SocialPostReportStatus,
        note: String?,
    ): SocialPostReportDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*SOCIAL_MODERATION_ROLES)
        requireModerationRateLimit(memberId = current.memberId)
        requireDecisionNoteLength(note)
        if (decision == SocialPostReportStatus.OPEN) throw ConflictException("Cannot set a report back to OPEN")
        val id = reportId.toSocialUuid()
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val row =
                SocialPostReportTable
                    .selectAll()
                    .where { SocialPostReportTable.id eq id }
                    .forUpdate()
                    .singleOrNull() ?: throw NotFoundException("SocialPostReport $reportId not found")
            val currentStatus = row[SocialPostReportTable.status]
            if (currentStatus != SocialPostReportStatus.OPEN && currentStatus != SocialPostReportStatus.UNDER_REVIEW) {
                throw ConflictException("SocialPostReport $reportId is not OPEN/UNDER_REVIEW")
            }
            SocialPostReportTable.update({ SocialPostReportTable.id eq id }) {
                it[SocialPostReportTable.status] = decision
                it[decidedBy] = current.memberId
                it[decidedAt] = now
                it[decisionNote] = note
            }
            // entityId = die POST-Id (nicht die Report-Id), damit listAuditLog(entityId = postId)
            // die vollstaendige Moderationsgeschichte eines Beitrags an einer Stelle zeigt.
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.SOCIAL_POST,
                entityId = row[SocialPostReportTable.postId],
                action = AuditAction.UPDATE,
            )
            loadReportDto(id)
        }
    }

    // ── Welle V1.1.5 -- DSGVO-Content-Hard-Delete (post-bezogener Art.-17-Antrag) ───────────

    override suspend fun requestContentErasure(input: SocialPostErasureInput): SocialPostErasureDto {
        val current = resolveCurrentMember(call)
        requireRateLimit(memberId = current.memberId)
        val postUuid = input.postId.toSocialUuid()
        requireErasureReason(input.reason)
        requireContactLength(input.requesterContact)
        val subjectUuid = input.subjectMemberId?.toSocialUuid()
        // self-or-ADMIN (Muster DsgvoService.requireSelfOrAdmin) -- ein Aufrufer darf fuer sich
        // selbst (subjectUuid == current.memberId ODER subjectUuid == null) beantragen; ein ADMIN
        // darf zusaetzlich im Namen einer externen betroffenen Person beantragen.
        if (subjectUuid != null && subjectUuid != current.memberId && current.role != AccountRole.ADMIN) {
            throw ForbiddenException()
        }
        val now = DbClock.nowLocalDateTime()
        return transaction {
            if (subjectUuid != null) {
                val subjectExists = MemberTable.select(MemberTable.id).where { MemberTable.id eq subjectUuid }.firstOrNull() != null
                if (!subjectExists) throw NotFoundException("Member ${input.subjectMemberId} not found")
            }
            // Enumeration-Härtung (Review-Fund 1, Runde 1 2026-08-19): fuer einen Nicht-ADMIN muss
            // ein fuer ihn unlesbarer Post (z. B. MEMBERS_ONLY per geleaktem Link) dieselbe
            // NotFoundException liefern wie ein tatsaechlich nicht existierender Post -- sonst ein
            // Existenz-/Lesbarkeits-Orakel derselben Klasse wie das historische S-B1 und
            // [reportPost]s eigene Haertung. ADMIN bleibt bewusst bei der reinen Existenzpruefung
            // (Plan § 3.5: ADMIN muss "im Namen einer externen betroffenen Person" auch fuer einen
            // fuer ihn selbst nicht lesbaren MEMBERS_ONLY-Post beantragen koennen, analog zu
            // [listReports]' eigener bewusster Umgehung der Sichtbarkeit).
            val postVisible =
                if (current.role == AccountRole.ADMIN) {
                    SocialPostTable.select(SocialPostTable.id).where { SocialPostTable.id eq postUuid }.firstOrNull() != null
                } else {
                    SocialPostTable
                        .select(SocialPostTable.id)
                        .where { (SocialPostTable.id eq postUuid) and SocialVisibility.readableByCondition(status = current.status) }
                        .firstOrNull() != null
                }
            if (!postVisible) throw NotFoundException("SocialPost ${input.postId} not found")
            val id = Uuid.random()
            SocialPostErasureTable.insert {
                it[SocialPostErasureTable.id] = id
                it[SocialPostErasureTable.postId] = postUuid
                it[requestedAt] = now
                it[requestedBy] = current.memberId
                it[SocialPostErasureTable.subjectMemberId] = subjectUuid
                it[requesterContact] = input.requesterContact
                it[reason] = input.reason
                it[status] = SocialPostErasureStatus.REQUESTED
            }
            loadErasureDto(id)
        }
    }

    override suspend fun listContentErasures(
        status: SocialPostErasureStatus?,
        beforeRequestedAt: LocalDateTime?,
        beforeId: String?,
    ): List<SocialPostErasureDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        requireReadRateLimit(memberId = current.memberId)
        // Security-Audit-Fund MAJOR-2 (2026-08-19): see [listReports] KDoc -- same composite-cursor
        // reasoning, mirrored here with requestedAt/id.
        val cursorId = beforeId?.let { it.toSocialUuid() }
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (status != null) conditions += (SocialPostErasureTable.status eq status)
            if (beforeRequestedAt != null && cursorId != null) {
                conditions +=
                    (SocialPostErasureTable.requestedAt less beforeRequestedAt) or
                    (
                        (SocialPostErasureTable.requestedAt eq beforeRequestedAt) and
                            (SocialPostErasureTable.id less cursorId)
                    )
            }
            val baseQuery = SocialPostErasureTable.selectAll()
            val filtered = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
            filtered
                .orderBy(SocialPostErasureTable.requestedAt to SortOrder.DESC, SocialPostErasureTable.id to SortOrder.DESC)
                .limit(MAX_MODERATION_PAGE_SIZE)
                .map { it.toErasureDto() }
        }
    }

    override suspend fun decideContentErasure(
        erasureId: String,
        approve: Boolean,
        note: String?,
    ): SocialPostErasureDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        requireModerationRateLimit(memberId = current.memberId)
        requireDecisionNoteLength(note)
        val id = erasureId.toSocialUuid()
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val row =
                SocialPostErasureTable
                    .selectAll()
                    .where { SocialPostErasureTable.id eq id }
                    .forUpdate()
                    .singleOrNull() ?: throw NotFoundException("SocialPostErasure $erasureId not found")
            if (row[SocialPostErasureTable.status] != SocialPostErasureStatus.REQUESTED) {
                throw ConflictException("SocialPostErasure $erasureId is not in REQUESTED state")
            }
            val newStatus = if (approve) SocialPostErasureStatus.APPROVED else SocialPostErasureStatus.REJECTED
            SocialPostErasureTable.update({ SocialPostErasureTable.id eq id }) {
                it[status] = newStatus
                it[decidedBy] = current.memberId
                it[decidedAt] = now
                it[decisionNote] = note
            }
            // Security-Audit-Fund (Runde 1, 2026-08-19): [decideReport] already records an audit-log
            // entry for its approve/reject/dismiss decision -- this method's equivalent decision
            // (arguably the MORE consequential of the two: it gates an eventual real content
            // deletion via [executeContentErasure]) previously did not, an asymmetric accountability
            // trail. Same shape as [decideReport]'s own call: entityId is the POST id (not the
            // erasure id), so `listAuditLog(entityId = postId)` shows a post's full moderation
            // history -- including erasure decisions -- in one place. As the LAST locking database
            // operation in this transaction (per AuditLogRecorder's own deadlock-avoidance contract)
            // -- loadErasureDto below is a plain, unlocked read.
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.SOCIAL_POST,
                entityId = row[SocialPostErasureTable.postId],
                action = AuditAction.UPDATE,
            )
            loadErasureDto(id)
        }
    }

    /**
     * Schreibt `content` mit [SocialContentTombstone.ON_POST_REQUEST] -- fasst `state` NIE an
     * (orthogonal zu [removePostForLegalReason], siehe [SocialPostDto.contentErasedAt] KDoc).
     * Idempotent: ein bereits getombstoneter Post wird NICHT erneut ueberschrieben ("erster
     * Schreiber gewinnt"), der Antrag wird trotzdem auf `EXECUTED` gesetzt.
     */
    override suspend fun executeContentErasure(erasureId: String): SocialPostErasureDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        requireModerationRateLimit(memberId = current.memberId)
        val id = erasureId.toSocialUuid()
        val now = DbClock.nowLocalDateTime()
        return transaction {
            // Sperrreihenfolge: Erasure-Zeile zuerst (serialisiert zwei gleichzeitige execute-Aufrufe
            // auf denselben Antrag), dann die Post-Zeile.
            val erasureRow =
                SocialPostErasureTable
                    .selectAll()
                    .where { SocialPostErasureTable.id eq id }
                    .forUpdate()
                    .singleOrNull() ?: throw NotFoundException("SocialPostErasure $erasureId not found")
            if (erasureRow[SocialPostErasureTable.status] != SocialPostErasureStatus.APPROVED) {
                throw ConflictException("SocialPostErasure $erasureId is not APPROVED")
            }
            val postId = erasureRow[SocialPostErasureTable.postId]
            val postRow =
                SocialPostTable
                    .selectAll()
                    .where { SocialPostTable.id eq postId }
                    .forUpdate()
                    .singleOrNull() ?: throw NotFoundException("SocialPost $postId not found")
            val alreadyTombstoned = postRow[SocialPostTable.contentErasedAt] != null
            if (!alreadyTombstoned) {
                SocialPostTable.update({ SocialPostTable.id eq postId }) {
                    it[content] = SocialContentTombstone.ON_POST_REQUEST
                    it[contentErasedAt] = now
                    it[contentErasureNote] = "Post-bezogener Loeschantrag $id, Art. 17 DSGVO"
                }
            }
            SocialPostErasureTable.update({ SocialPostErasureTable.id eq id }) {
                it[status] = SocialPostErasureStatus.EXECUTED
                it[executedAt] = now
            }
            // Als LETZTE sperrende Operation. Snapshot traegt NIEMALS content.
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.SOCIAL_POST,
                entityId = postId,
                action = AuditAction.UPDATE,
                before =
                    Json.encodeToString(
                        SocialPostModerationSnapshot(
                            state = postRow[SocialPostTable.state],
                            stateReason = postRow[SocialPostTable.stateReason],
                            visibility = postRow[SocialPostTable.visibility],
                            contentErasedAt = postRow[SocialPostTable.contentErasedAt],
                        ),
                    ),
                after =
                    Json.encodeToString(
                        SocialPostModerationSnapshot(
                            state = postRow[SocialPostTable.state],
                            stateReason = postRow[SocialPostTable.stateReason],
                            visibility = postRow[SocialPostTable.visibility],
                            contentErasedAt = if (alreadyTombstoned) postRow[SocialPostTable.contentErasedAt] else now,
                        ),
                    ),
            )
            loadErasureDto(id)
        }
    }

    // ── Welle V1.1.5 -- oeffentlicher Entfernungshinweis fuer nicht-oeffentliche Beitraege (E-B) ──

    override suspend fun getRemovalNotice(postId: String): SocialPostRemovalNoticeDto {
        val current = resolveCurrentMember(call)
        requireReadRateLimit(memberId = current.memberId)
        val postUuid = postId.toSocialUuid()
        return transaction {
            val row =
                SocialPostTable
                    .select(
                        SocialPostTable.visibility,
                        SocialPostTable.stateReason,
                        SocialPostTable.stateChangedAt,
                        SocialPostTable.publishedAt,
                        SocialPostTable.authorMemberId,
                    ).where {
                        (SocialPostTable.id eq postUuid) and
                            SocialVisibility.removalNoticeReadableCondition(status = current.status)
                    }.singleOrNull() ?: throw NotFoundException("SocialPost $postId not found")
            SocialPostRemovalNoticeDto(
                postId = postUuid.toString(),
                visibility = row[SocialPostTable.visibility],
                removedAt = row[SocialPostTable.stateChangedAt] ?: row[SocialPostTable.publishedAt],
                reason = row[SocialPostTable.stateReason]?.takeIf { it.isNotBlank() } ?: LEGAL_REMOVAL_FALLBACK_REASON,
                isOwnPost = row[SocialPostTable.authorMemberId] == current.memberId,
            )
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────────────

    /**
     * Shared by [createPost], [createComment], and, since Security-Audit-Fund S-2 (2026-08-18),
     * [hideOwnPost] too -- all three are mutating actions gated by the same [createRateLimiter]
     * budget, deliberately NOT separate limiter instances (see [requireBoostRateLimit] KDoc for why
     * [boostPost] is the one exception). Stale-KDoc fix (Security-Audit Runde 1, 2026-08-19):
     * [requestContentErasure] (Welle V1.1.5) is a FOURTH sharer of this same budget -- this KDoc
     * previously still only named three, even though that method has called this function since
     * the wave landed.
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

    /** Welle V1.1.5 -- [removePostForLegalReason]/[decideReport]/[decideContentErasure]/[executeContentErasure] (BOARD/ADMIN, 20/min). */
    private fun requireModerationRateLimit(memberId: Uuid) {
        if (!moderationRateLimiter.checkAndRecord(memberId.toString())) {
            throw ConflictException("Zu viele Moderationsaktionen in kurzer Zeit -- bitte kurz warten und erneut versuchen.")
        }
    }

    /** Welle V1.1.5 -- [reportPost] (jeder authentifizierte Aufrufer, 5/Stunde). */
    private fun requireReportRateLimit(memberId: Uuid) {
        if (!reportRateLimiter.checkAndRecord(memberId.toString())) {
            throw ConflictException("Zu viele Meldungen in kurzer Zeit -- bitte kurz warten und erneut versuchen.")
        }
    }

    private fun requireModerationReason(reason: String) {
        if (reason.isBlank()) throw ConflictException("reason must not be blank")
        if (reason.length > MAX_MODERATION_REASON_LENGTH) {
            throw ConflictException("reason exceeds the maximum length of $MAX_MODERATION_REASON_LENGTH characters")
        }
    }

    private fun requireErasureReason(reason: String) {
        if (reason.isBlank()) throw ConflictException("reason must not be blank")
        if (reason.length > MAX_ERASURE_REASON_LENGTH) {
            throw ConflictException("reason exceeds the maximum length of $MAX_ERASURE_REASON_LENGTH characters")
        }
    }

    private fun requireContactLength(contact: String?) {
        if (contact != null && contact.length > MAX_CONTACT_LENGTH) {
            throw ConflictException("contact exceeds the maximum length of $MAX_CONTACT_LENGTH characters")
        }
    }

    /** [decideReport]/[decideContentErasure] -- siehe [MAX_DECISION_NOTE_LENGTH] KDoc. */
    private fun requireDecisionNoteLength(note: String?) {
        if (note != null && note.length > MAX_DECISION_NOTE_LENGTH) {
            throw ConflictException("note exceeds the maximum length of $MAX_DECISION_NOTE_LENGTH characters")
        }
    }

    private fun loadReportDto(id: Uuid): SocialPostReportDto =
        SocialPostReportTable
            .join(SocialPostTable, JoinType.INNER, SocialPostReportTable.postId, SocialPostTable.id)
            .selectAll()
            .where { SocialPostReportTable.id eq id }
            .single()
            .toReportDto()

    private fun loadErasureDto(id: Uuid): SocialPostErasureDto =
        SocialPostErasureTable
            .selectAll()
            .where { SocialPostErasureTable.id eq id }
            .single()
            .toErasureDto()

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

private fun ResultRow.toReportDto(): SocialPostReportDto =
    SocialPostReportDto(
        id = this[SocialPostReportTable.id].toString(),
        postId = this[SocialPostReportTable.postId].toString(),
        postExcerpt = this[SocialPostTable.content].take(REPORT_POST_EXCERPT_LENGTH),
        postState = this[SocialPostTable.state],
        postVisibility = this[SocialPostTable.visibility],
        reportedAt = this[SocialPostReportTable.reportedAt],
        reporterMemberId = this[SocialPostReportTable.reporterMemberId]?.toString(),
        // MINOR-4 (Security-Audit Runde 1, 2026-08-19): see SocialPostReportDto.reporterContact KDoc.
        reporterContact = this[SocialPostReportTable.reporterContact],
        category = this[SocialPostReportTable.category],
        description = this[SocialPostReportTable.description],
        goodFaithConfirmed = this[SocialPostReportTable.goodFaithConfirmed],
        status = this[SocialPostReportTable.status],
        decidedBy = this[SocialPostReportTable.decidedBy]?.toString(),
        decidedAt = this[SocialPostReportTable.decidedAt],
        decisionNote = this[SocialPostReportTable.decisionNote],
    )

private fun ResultRow.toErasureDto(): SocialPostErasureDto =
    SocialPostErasureDto(
        id = this[SocialPostErasureTable.id].toString(),
        postId = this[SocialPostErasureTable.postId].toString(),
        requestedAt = this[SocialPostErasureTable.requestedAt],
        requestedBy = this[SocialPostErasureTable.requestedBy]?.toString(),
        subjectMemberId = this[SocialPostErasureTable.subjectMemberId]?.toString(),
        requesterContact = this[SocialPostErasureTable.requesterContact],
        reason = this[SocialPostErasureTable.reason],
        status = this[SocialPostErasureTable.status],
        decidedBy = this[SocialPostErasureTable.decidedBy]?.toString(),
        decidedAt = this[SocialPostErasureTable.decidedAt],
        decisionNote = this[SocialPostErasureTable.decisionNote],
        executedAt = this[SocialPostErasureTable.executedAt],
        sourceReportId = this[SocialPostErasureTable.sourceReportId]?.toString(),
    )
