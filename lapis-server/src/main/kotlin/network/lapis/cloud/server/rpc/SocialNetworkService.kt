package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
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
import network.lapis.cloud.shared.domain.SocialPostDto
import network.lapis.cloud.shared.domain.SocialPostInput
import network.lapis.cloud.shared.domain.SocialPostState
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
 * Soziales Netzwerk, Welle V1.1.1 "Fundament & Post-Kern" -- see `32-social-network.kuml.kts` file
 * header and [ISocialNetworkService] KDoc for the full fachlich model. Write pattern is 1:1
 * modelled after `CrowdfundingService.submitProject` (rate limit -> validate -> membership gate ->
 * `lockForDebit` -> `freeBalance` check -> insert business row -> insert ledger debit), see that
 * method's own KDoc for the lock-ordering rationale this mirrors.
 *
 * [createRateLimiter]/[readRateLimiter] are request-rate limiters (not failure-rate ones, see
 * [FederationInboxRateLimiter] KDoc) -- module-scoped, constructed once in `Application.module`,
 * NEVER as a constructor default (a default-argument instance would be minted fresh on every
 * `registerService` factory-lambda invocation, silently defeating the throttle -- see that
 * function's own KDoc "conferenceGuestAccessRateLimiter" note for the documented precedent of this
 * exact mistake class). [readRateLimiter] was added in Review-Fund S1 (2026-08-18) -- until then
 * [listTimeline]/[getPost] had no rate limit at all, unlike every other public-facing read path in
 * this codebase (see e.g. `streamingReadRateLimiter`/`conferenceListRateLimiter`).
 */
class SocialNetworkService(
    private val call: ApplicationCall,
    private val createRateLimiter: FederationInboxRateLimiter,
    private val readRateLimiter: FederationInboxRateLimiter,
    private val ltrBalanceProvider: LtrBalanceProvider = LedgerBackedLtrBalanceProvider(),
) : ISocialNetworkService {
    override suspend fun createPost(input: SocialPostInput): SocialPostDto {
        val current = resolveCurrentMember(call)
        requireRateLimit(memberId = current.memberId)
        val normalized = normalizeWeight(input.initialWeightLtr)
        requireContentWithinLimits(input.content)
        val now = DbClock.nowLocalDateTime()
        return transaction {
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
                // Offene Entscheidung E5 (Plan § 8): die Note traegt bewusst KEINEN
                // Inhaltsausschnitt, nur eine ID-Referenz -- siehe Plan § 7.2 X12 "Ledger-Note als
                // Injection-Vektor"/DSGVO-Tombstone-Vollstaendigkeit. Ein Inhaltsausschnitt wuerde
                // ein spaeteres DSGVO-Tombstone (Welle V1.1.5) unterlaufen, weil der Kontoauszug den
                // Original-Inhalt sonst dauerhaft ueberleben liesse. Der Inhaltsbezug soll
                // stattdessen zur Lesezeit ueber referenceId hergestellt werden -- korrigiert
                // (Review-Fund G4, 2026-08-18): das ist noch NICHT umgesetzt, LtrLedgerScreen.kt
                // zeigt heute nur den referenceType-Namen an, keinen aufgeloesten Deep-Link. Ein
                // spaeterer Client-Ausbau soll referenceId live gegen SocialNetworkService.getPost
                // (o. ae.) aufloesen; bis dahin ist referenceId nur maschinenlesbar gespeichert.
                it[note] = "Beitragsgewicht fuer Social Post $postId"
                it[createdBy] = null
                it[createdAt] = now
            }
            loadPostOrThrow(id = postId, now = now, viewerStatus = current.status)
        }
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
            // unconditional DoS backstop.
            if (!selfHiddenView) {
                condition = condition and (SocialPostTable.publishedAt greaterEq horizon)
            }

            // Security-Audit-Fund S-3 (2026-08-18): ranking (see weightById/ranked below) only ever
            // needs id/publishedAt/initialWeightLtr -- loading every OTHER column (content up to
            // 5000 chars, visibility, state, ...) for up to MAX_TIMELINE_WORKING_SET_ROWS rows just
            // to discard all but <=MAX_TIMELINE_LIMIT of them afterwards was pure waste. Project
            // only the ranking-relevant columns here; the full row is loaded further below, ONLY
            // for the <=MAX_TIMELINE_LIMIT ids that actually survive paging.
            val rankingRows =
                SocialPostTable
                    .select(SocialPostTable.id, SocialPostTable.publishedAt, SocialPostTable.initialWeightLtr)
                    .where { condition }
                    // Defensive backstop, independent of the horizon filter above -- see
                    // MAX_TIMELINE_WORKING_SET_ROWS KDoc for why this can't just be
                    // .limit(offset + limit): sorting by weight happens below, in Kotlin, not here.
                    .orderBy(SocialPostTable.publishedAt, SortOrder.DESC)
                    .limit(MAX_TIMELINE_WORKING_SET_ROWS)
                    .toList()
            // Review-Fund S1 (2026-08-18): compute every row's weight EXACTLY ONCE into a map
            // before sorting -- the previous `compareByDescending { ownWeight(...) }` re-evaluated
            // the (BigDecimal.pow-based) weight selector on every comparator invocation during the
            // O(n log n) sort, i.e. O(n log n) weight computations instead of O(n).
            val weightById = rankingRows.associate { it[SocialPostTable.id] to ownWeight(row = it, now = now) }
            val ranked =
                rankingRows.sortedWith(
                    compareByDescending<ResultRow> { weightById.getValue(it[SocialPostTable.id]) }
                        .thenByDescending { it[SocialPostTable.publishedAt] }
                        .thenBy { it[SocialPostTable.id].toString() },
                )
            // S-3: resolve the final page's ids from the lightweight ranking above FIRST, then load
            // full rows for ONLY those ids -- never for the whole (up to 2000-row) candidate set.
            val pageIds = ranked.drop(offset).take(limit).map { it[SocialPostTable.id] }
            val fullRowById =
                if (pageIds.isEmpty()) {
                    emptyMap()
                } else {
                    SocialPostTable.selectAll().where { SocialPostTable.id inList pageIds }.associateBy { it[SocialPostTable.id] }
                }
            // `inList` gives no row-order guarantee of its own -- rebuild the page in ranked order.
            val page = pageIds.mapNotNull { fullRowById[it] }
            SocialTimelinePageDto(
                posts = toDtos(rows = page, now = now, viewerStatus = current.status),
                totalRankedCount = ranked.size,
                rankingHorizonFrom = ranked.lastOrNull()?.get(SocialPostTable.publishedAt) ?: now,
            )
        }
    }

    /**
     * Sichtbarkeitsprüfung in der Query selbst (nicht nachträglich in Kotlin) -- ein nicht
     * gefundener Post und ein existierender, aber für [current] nicht sichtbarer Post liefern
     * identisch [NotFoundException], kein Existenz-Orakel (siehe Implementierungsplan § 7.2 X3).
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
            toDtos(rows = listOf(row), now = now, viewerStatus = current.status).single()
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
     */
    override suspend fun hideOwnPost(postId: String): SocialPostDto {
        val current = resolveCurrentMember(call)
        requireRateLimit(memberId = current.memberId)
        val id = postId.toSocialUuid()
        val now = DbClock.nowLocalDateTime()
        return transaction {
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
            loadPostOrThrow(id = id, now = now, viewerStatus = current.status)
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────────────

    /**
     * Shared by [createPost] and, since Security-Audit-Fund S-2 (2026-08-18), [hideOwnPost] too --
     * both are mutating actions gated by the same [createRateLimiter] budget, deliberately NOT a
     * separate limiter instance (the simplest fix that still closes the gap: [hideOwnPost] is a
     * low-frequency action for any real caller, so sharing the create budget costs nothing in
     * practice while avoiding a fourth module-scoped limiter for one rarely-called mutation).
     */
    private fun requireRateLimit(memberId: Uuid) {
        if (!createRateLimiter.checkAndRecord(memberId.toString())) {
            throw ConflictException("Zu viele Beiträge in kurzer Zeit -- bitte kurz warten und erneut versuchen.")
        }
    }

    /** Review-Fund S1 (2026-08-18): [listTimeline]/[getPost] previously had no rate limit at all. */
    private fun requireReadRateLimit(memberId: Uuid) {
        if (!readRateLimiter.checkAndRecord(memberId.toString())) {
            throw ConflictException("Zu viele Anfragen in kurzer Zeit -- bitte kurz warten und erneut versuchen.")
        }
    }

    /**
     * Review-Fund S1 (2026-08-18): [now] minus [SocialPostWeight.RANKING_HORIZON_DAYS], via
     * `Instant`-difference against a fixed [TimeZone.UTC] reference -- same discipline as
     * [WeightDecayClock.daysElapsed], never calendar-day subtraction.
     */
    private fun rankingHorizon(now: LocalDateTime): LocalDateTime =
        (now.toInstant(TimeZone.UTC) - SocialPostWeight.RANKING_HORIZON_DAYS.days).toLocalDateTime(TimeZone.UTC)

    private fun normalizeWeight(weight: BigDecimal): BigDecimal {
        if (weight.scale() > 2) throw ConflictException("initialWeightLtr must have at most 2 decimal places")
        val normalized = weight.setScale(2, RoundingMode.UNNECESSARY)
        if (normalized < SocialPostWeight.MIN_WEIGHT_LTR) {
            throw ConflictException("initialWeightLtr $normalized is below the minimum of ${SocialPostWeight.MIN_WEIGHT_LTR} LTR")
        }
        return normalized
    }

    private fun requireContentWithinLimits(content: String) {
        if (content.isBlank()) throw ConflictException("content must not be blank")
        if (content.length > MAX_CONTENT_LENGTH) {
            throw ConflictException("content exceeds the maximum length of $MAX_CONTENT_LENGTH characters")
        }
    }

    private fun ownWeight(
        row: ResultRow,
        now: LocalDateTime,
    ): BigDecimal =
        SocialPostWeight.ownWeightUnrounded(
            initialWeightLtr = row[SocialPostTable.initialWeightLtr],
            publishedAt = row[SocialPostTable.publishedAt],
            now = now,
        )

    private fun loadPostOrThrow(
        id: Uuid,
        now: LocalDateTime,
        viewerStatus: MemberStatus,
    ): SocialPostDto {
        val row =
            SocialPostTable.selectAll().where { SocialPostTable.id eq id }.singleOrNull()
                ?: throw NotFoundException("SocialPost $id not found")
        return toDtos(rows = listOf(row), now = now, viewerStatus = viewerStatus).single()
    }

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
     */
    private fun toDtos(
        rows: List<ResultRow>,
        now: LocalDateTime,
        viewerStatus: MemberStatus,
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
            val authorId = row[SocialPostTable.authorMemberId]
            SocialPostDto(
                id = row[SocialPostTable.id].toString(),
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
                ownCurrentWeightLtr = WeightDecayClock.round2(ownWeight(row = row, now = now)),
                publishedAt = row[SocialPostTable.publishedAt],
            )
        }
    }
}

internal fun String.toSocialUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
