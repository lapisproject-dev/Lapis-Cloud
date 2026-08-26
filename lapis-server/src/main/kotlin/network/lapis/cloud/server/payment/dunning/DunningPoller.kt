package network.lapis.cloud.server.payment.dunning

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DunningLevelTable
import network.lapis.cloud.server.db.generated.DunningNoticeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostalDeliveryLogTable
import network.lapis.cloud.server.db.generated.SepaMandateTable
import network.lapis.cloud.server.pdf.MahnungPdfGenerator
import network.lapis.cloud.server.postal.PostalDispatchOutcome
import network.lapis.cloud.server.postal.PostalMailProvider
import network.lapis.cloud.server.routes.loadMailmergeMember
import network.lapis.cloud.server.routes.loadOrganizationSettingsDto
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.ContributionDto
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.DunningNoticeStatus
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.PostalDeliveryStatus
import network.lapis.cloud.shared.domain.SepaMandateStatus
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Default page size for [DunningPoller.loadPhaseBCandidatePage]'s keyset-paginated `ORDER BY
 * due_date ASC, id ASC LIMIT ...` scan over DUNNABLE contributions -- bounds BOTH the number of
 * rows materialized into the heap per page AND the size of the `contribution_id inList ...`
 * bind-parameter list built from that page, well under PostgreSQL's ~32,767 bind-parameter limit,
 * regardless of how many DUNNABLE (or chronically undunnable, e.g. SEPA-covered) contributions the
 * organization accumulates. See the security review finding this fixes. Overridable per
 * [DunningPoller] instance via its constructor -- production always uses this default; tests
 * inject a small value to deterministically exercise the multi-page path without creating hundreds
 * of fixture rows.
 */
private const val PHASE_B_QUERY_BATCH_SIZE = 500

/**
 * Per-tick cap for [DunningPoller.runPhaseC]'s `status = ISSUED AND document_id IS NULL` orphan
 * scan -- without it, a sustained archive-storage outage (full/read-only volume, see
 * [DunningPoller.runPhaseC] KDoc) grows the orphan backlog monotonically (every failed
 * [archiveDunningNoticePdf] retry in Phase B rolls back without ever setting `document_id`), and an
 * unbounded `.map { it[DunningNoticeTable.id] }` scan plus a full PDF-regeneration-and-archive
 * attempt PER orphan turns a single tick into a near-unbounded amount of work -- starving Phase A/B
 * in the SAME `while (isActive)` loop (see [DunningPoller.start]). Ordered `issuedAt ASC` (oldest
 * gap first) so partial progress under a persistent backlog is still meaningful; each tick heals up
 * to this many, unhealed rows simply remain candidates for the NEXT tick (their `document_id` stays
 * `NULL`, no cursor/paging state needed across ticks). See the security review finding this fixes.
 */
private const val PHASE_C_MAX_PER_TICK = 200

/**
 * Welle V1.2.7 "Automatisiertes Mahnwesen". Application-scoped poller, wörtlich nach
 * [network.lapis.cloud.server.payment.sepa.SepaBatchPoller]/
 * [network.lapis.cloud.server.conference.RecordingPoller] modelliert -- ONE coroutine
 * (`SupervisorJob() + Dispatchers.IO`), `while (isActive) { tick(); delay(interval) }`, [tick]
 * public and exception-safe at two levels, [start]/[stop] idempotent, NO in-memory state (every
 * phase re-queries its candidates fresh every tick -- restart reconciliation).
 *
 * **This poller never touches accounting.** See `34-dunning.kuml.kts` file header "Scope" -- no
 * `AccountingService`/`ContributionPostingBridge`/`CashRegisterGuard` call anywhere in this class.
 */
class DunningPoller(
    private val dunningConfig: DunningConfig,
    private val documentStorageRoot: File,
    private val postalMailProvider: PostalMailProvider,
    private val clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
    /** Test-only override -- see [PHASE_B_QUERY_BATCH_SIZE] KDoc. */
    private val phaseBQueryBatchSize: Int = PHASE_B_QUERY_BATCH_SIZE,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    /** Idempotent -- a second call while already running is a no-op. */
    fun start() {
        if (loopJob != null) return
        loopJob =
            scope.launch {
                while (isActive) {
                    tick()
                    delay(dunningConfig.pollIntervalSeconds.seconds)
                }
            }
    }

    /** Cancels the poll loop -- for tests/graceful shutdown. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * One poll pass, three phases. Exception-safe at two levels (whole tick + each row
     * individually) so a single broken row never stops the others, and tests can call this
     * directly with zero timing dependency -- same contract as `SepaBatchPoller.tick`.
     *
     * A complete no-op if `organization_settings.dunning_enabled` is `false` -- an independent,
     * second gate on top of [DunningConfig.pollerEnabled] (same "sandbox-first" discipline as
     * `SepaBatchPoller`'s own `sepaDebitEnabled` check): a staging instance seeded with a
     * production dump must never accidentally mail real dunning letters. Phase A (purely
     * time-derived `OPEN -> OVERDUE`) is DELIBERATELY gated behind the SAME flag, even though it
     * writes no audit entry and sends no letter -- flipping contribution status is itself a
     * dunning-domain side effect that a disabled feature must not perform.
     */
    suspend fun tick() {
        try {
            val dunningEnabled =
                transaction {
                    OrganizationSettingsTable
                        .selectAll()
                        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                        .singleOrNull()
                        ?.get(OrganizationSettingsTable.dunningEnabled) == true
                }
            if (!dunningEnabled) return

            val now = clock()
            runPhaseA(now)
            runPhaseB(now)
            runPhaseC(now)
        } catch (e: Throwable) {
            logger.warn(e) { "DunningPoller: tick failed" }
        }
    }

    /**
     * Phase A -- purely time-derived Faelligstellung, `OPEN -> OVERDUE` once `due_date` has
     * passed. **No audit entry** -- a rein zeitabgeleiteter Zustandswechsel ohne menschliche
     * Entscheidung und ohne Geldbewegung; ein Eintrag pro Beitrag wuerde die hash-gekettete Chain
     * (globale Sperre, siehe `AuditLogRecorder` KDoc) unnoetig fluten. Guarded on `status = OPEN`
     * in the UPDATE itself -- idempotent, re-running it twice is harmless.
     */
    private fun runPhaseA(now: LocalDateTime) {
        try {
            transaction {
                ContributionTable.update({
                    (ContributionTable.status eq ContributionStatus.OPEN) and (ContributionTable.dueDate less now.date)
                }) {
                    it[status] = ContributionStatus.OVERDUE
                }
            }
        } catch (e: Throwable) {
            logger.warn(e) { "DunningPoller: phase A failed" }
        }
    }

    /**
     * Phase B -- the actual escalation run. Candidates are scanned in [PHASE_B_QUERY_BATCH_SIZE]
     * pages, `ORDER BY due_date ASC` (oldest debt first), over every contribution with a status in
     * [ContributionStatusSets.DUNNABLE] whose NEXT escalation level is actually due today (own
     * grace-period pre-computation, an APPROXIMATION of [issueDunningNotice]'s
     * `respectSchedule = true` check -- see below for why exactness is no longer required). Each
     * candidate goes through the SAME [issueDunningNotice] path a manual RPC override uses
     * (`respectSchedule = true`, `actorMemberId = null` -- system actor, same convention
     * `SepaBatchPoller` already establishes), in its own `try/catch` so one broken row never stops
     * the rest.
     *
     * **The quota is spent on actual issuance, never on a mere candidate.** [DunningConfig
     * .maxNoticesPerTick] is enforced by counting [DunningIssueOutcome.Issued] outcomes as they
     * happen (`issued`, incremented only in the `Issued` branch below) and stopping once that
     * count is reached -- NOT by pre-selecting `maxNoticesPerTick` candidates and letting
     * [issueDunningNotice] reject some of them. A contribution [issueDunningNotice] turns away as
     * [DunningIssueOutcome.NoFurtherLevel], [DunningIssueOutcome.NotDue], [DunningIssueOutcome
     * .AlreadyIssued], or [DunningIssueOutcome.Superseded] (a concurrent cancel/reset raced this
     * exact call -- see that outcome's own KDoc) simply falls through to the next candidate
     * WITHOUT consuming a quota slot -- an organization whose oldest-due-date contributions have
     * already exhausted every configured level can no longer starve out the genuinely overdue
     * contributions ranked behind them, at ANY multiple of [DunningConfig.maxNoticesPerTick], not
     * just within one page. See the security review finding this fixes (round 2 -- round 1 already
     * fixed the analogous [DunningIssueOutcome.NoFurtherLevel] starvation case by excluding
     * contributions with no next level from the candidate list in the first place).
     *
     * **SEPA-coverage IS mirrored in the candidate SQL** (unlike an earlier version of this KDoc,
     * which deliberately left it out of the pre-filter on the reasoning that a SEPA-covered
     * candidate only ever cost one wasted [issueDunningNotice] call, never a lost quota slot -- see
     * [loadPhaseBCandidatePage] KDoc for why that reasoning broke down and this fix reinstates the
     * mirror, same "two places, kept in sync deliberately" idiom the `respectSchedule`/grace-day
     * eligibility check right below already establishes for this exact candidate query).
     *
     * **Paged, not `maxNoticesPerTick`-capped, and never `.toList()`-materializing the whole
     * DUNNABLE set**: because the quota is no longer spent on candidates, the candidate query can
     * no longer use `LIMIT maxNoticesPerTick` either -- doing so would silently reintroduce the
     * exact starvation this fix removes (a `LIMIT 200` page consisting entirely of exhausted rows
     * would never even see a real candidate). Instead, [loadPhaseBCandidatePage] pages through
     * DUNNABLE (and not-SEPA-blocked) contributions in fixed [phaseBQueryBatchSize] chunks via
     * `ORDER BY due_date ASC, id ASC LIMIT [phaseBQueryBatchSize]` **keyset** pagination (a
     * `(due_date, id) > (lastDueDate, lastId)` predicate carried from page to page, NOT `OFFSET`),
     * bounding both the per-page heap footprint and the per-page `contribution_id inList ...`
     * bind-parameter count -- an organization with tens of thousands of DUNNABLE (or permanently
     * SEPA-covered / exhausted) contributions no longer risks either an unbounded heap load or a
     * `PSQLException` from exceeding PostgreSQL's bind-parameter limit. Keyset (not `OFFSET`)
     * specifically because `due_date` is massively non-unique in this domain (a whole billing
     * period's contributions share one `due_date`) and rows in the current page transition from
     * OVERDUE to IN_DUNNING mid-scan (`ContributionTable.update` in [issueDunningNotice] Phase 2)
     * -- an `OFFSET`-based page boundary is defined purely by ROW POSITION in the (re-)sorted
     * result set, which a concurrent UPDATE can shift for rows still tied on `due_date`, silently
     * skipping or re-visiting rows across pages; the `(due_date, id)` tuple boundary this fix uses
     * instead identifies a page boundary by VALUE, immune to that reshuffling. See the security
     * review finding this fixes (two independent findings: the starvation fix above, and this
     * paging fix).
     *
     * On [DunningIssueOutcome.Issued]: archives the PDF ([archiveDunningNoticePdf]), then -- only
     * if [DunningConfig.postalDispatchEnabled] AND `organizationSettings.postalMailEnabled` AND
     * the recipient has a complete postal address -- dispatches the SAME in-memory PDF bytes via
     * [postalMailProvider] and links the resulting `postal_delivery_log` row. **At-most-once
     * postal dispatch**: a failed dispatch is recorded as `FAILED` and is NEVER retried by a later
     * tick -- a lost letter is a visible gap in the overview, a duplicated letter costs real money
     * and confuses the member.
     */
    private suspend fun runPhaseB(now: LocalDateTime) {
        val activeLevels =
            transaction {
                DunningLevelTable
                    .selectAll()
                    .where { DunningLevelTable.active eq true }
                    .orderBy(DunningLevelTable.levelNumber, SortOrder.ASC)
                    .toList()
            }
        if (activeLevels.isEmpty()) return

        var issued = 0
        var cursor: PhaseBCursor? = null
        while (issued < dunningConfig.maxNoticesPerTick) {
            val page = loadPhaseBCandidatePage(activeLevels = activeLevels, now = now, after = cursor)
            for (contributionId in page.candidateIds) {
                if (issued >= dunningConfig.maxNoticesPerTick) break
                try {
                    val outcome =
                        issueDunningNotice(
                            request =
                                DunningIssueRequest(
                                    contributionId = contributionId,
                                    actorMemberId = null,
                                    actorRole = null,
                                    now = now,
                                    respectSchedule = true,
                                ),
                            storageRoot = documentStorageRoot,
                        )
                    if (outcome !is DunningIssueOutcome.Issued) continue
                    issued++

                    archiveDunningNoticePdf(
                        storageRoot = documentStorageRoot,
                        noticeId = outcome.noticeId,
                        pdfBytes = outcome.pdfBytes,
                        levelName = outcome.levelName,
                        uploadedBy = outcome.documentUploaderId,
                    )
                    maybeDispatchByPost(
                        noticeId = outcome.noticeId,
                        pdfBytes = outcome.pdfBytes,
                        recipient = outcome.recipient,
                        levelName = outcome.levelName,
                        now = now,
                    )
                } catch (e: Throwable) {
                    logger.warn(e) { "DunningPoller: phase B failed for contribution $contributionId" }
                }
            }
            // Fewer rows than a full page -- the DUNNABLE (and not-SEPA-blocked) scan is exhausted,
            // no cursor further out could yield more, regardless of `issued`. Otherwise advance to
            // the next page via the keyset cursor (still gated by the outer
            // `while (issued < maxNoticesPerTick)`). `page.lastCursor` is only ever `null` when
            // `pageRows` was empty, which the rowCount check above already short-circuits on.
            if (page.rowCount < phaseBQueryBatchSize) break
            cursor = page.lastCursor ?: break
        }
    }

    /**
     * Deterministic keyset-pagination cursor for [loadPhaseBCandidatePage] -- the `(due_date, id)`
     * tuple of the LAST raw row on the previous page (before the eligibility filter), so the next
     * page's `WHERE (due_date, id) > (cursor.dueDate, cursor.id)` picks up exactly where the
     * previous one left off regardless of concurrent status UPDATEs on rows within the same
     * `due_date`. `id` (not e.g. `created_at`) as the tiebreaker purely because it is already
     * indexed (primary key) and totally ordered -- no claim of chronological meaning. See
     * [runPhaseB] KDoc "keyset, not OFFSET" for why an `OFFSET`-based page boundary broke down.
     */
    private data class PhaseBCursor(
        val dueDate: LocalDate,
        val id: Uuid,
    )

    /** One [phaseBQueryBatchSize]-bounded page of [runPhaseB] candidates. [rowCount] is the raw DUNNABLE-and-not-SEPA-blocked row count fetched (BEFORE the grace-day eligibility filter) -- used only to detect the last page. [lastCursor] is `null` iff [rowCount] is `0`. */
    private data class PhaseBCandidatePage(
        val candidateIds: List<Uuid>,
        val rowCount: Int,
        val lastCursor: PhaseBCursor?,
    )

    /**
     * Loads and filters ONE page (see [phaseBQueryBatchSize]) of `runPhaseB` candidates, keyset-
     * paginated via [after] -- `ORDER BY due_date ASC, id ASC LIMIT [phaseBQueryBatchSize]`, with a
     * `(due_date, id) > (after.dueDate, after.id)` predicate when [after] is non-`null`. Eligibility
     * mirrors [issueDunningNotice]'s checks in TWO layers:
     *
     * 1. **SQL-level**: the DUNNABLE-status filter AND the SEPA-mandate coverage guard (`payment_method
     *    = SEPA_DEBIT AND status != RETURNED AND EXISTS(an ACTIVE mandate for this member)`) -- an
     *    earlier version of this poller deliberately left the SEPA guard OUT of this SQL filter (see
     *    round-2 KDoc history on [runPhaseB]), reasoning that [runPhaseB] no longer spends the quota
     *    on a mere candidate, so a SEPA-covered row slipping through only costs one wasted
     *    [issueDunningNotice] call. That reasoning did not account for an organization where MOST
     *    OVERDUE contributions are SEPA-covered (the common case, not an edge case, wherever direct
     *    debit is the default payment method): every tick would then scan every page of the entire
     *    DUNNABLE set just to find the few genuinely dunnable candidates, unbounded in the number of
     *    [issueDunningNotice] calls (each opening multiple `SELECT`s) even though `issued` stays at
     *    or near `0`. Mirroring the guard in SQL closes that -- same "two places, kept in sync
     *    deliberately" trade-off the grace-day check right below already accepts for this exact
     *    query, not a new kind of risk. See the security review finding this fixes.
     * 2. **Kotlin-level** (below, unchanged): the `nextLevel` + `respectSchedule = true` grace-day
     *    computation, which needs `dunning_notice` history batch-loaded per page and is impractical
     *    to express as a single correlated SQL predicate.
     */
    private fun loadPhaseBCandidatePage(
        activeLevels: List<ResultRow>,
        now: LocalDateTime,
        after: PhaseBCursor?,
    ): PhaseBCandidatePage =
        transaction {
            val sepaCoverageBlocksIssuance =
                (ContributionTable.paymentMethod eq ContributionPaymentMethod.SEPA_DEBIT) and
                    (ContributionTable.status neq ContributionStatus.RETURNED) and
                    exists(
                        SepaMandateTable
                            .selectAll()
                            .where {
                                (SepaMandateTable.memberId eq ContributionTable.memberId) and
                                    (SepaMandateTable.status eq SepaMandateStatus.ACTIVE)
                            },
                    )
            var condition: Op<Boolean> =
                (ContributionTable.status inList ContributionStatusSets.DUNNABLE.toList()) and
                    not(sepaCoverageBlocksIssuance)
            if (after != null) {
                condition =
                    condition and
                    (
                        (ContributionTable.dueDate greater after.dueDate) or
                            ((ContributionTable.dueDate eq after.dueDate) and (ContributionTable.id greater after.id))
                    )
            }

            val pageRows =
                ContributionTable
                    .selectAll()
                    .where { condition }
                    .orderBy(ContributionTable.dueDate, SortOrder.ASC)
                    .orderBy(ContributionTable.id, SortOrder.ASC)
                    .limit(phaseBQueryBatchSize)
                    .toList()
            if (pageRows.isEmpty()) return@transaction PhaseBCandidatePage(candidateIds = emptyList(), rowCount = 0, lastCursor = null)

            // `inList` here is bounded by phaseBQueryBatchSize bind parameters, never by the
            // organization's total DUNNABLE (or ever-dunned) volume -- see PHASE_B_QUERY_BATCH_SIZE
            // KDoc.
            val pageIds = pageRows.map { it[ContributionTable.id] }
            val noticesByContribution =
                DunningNoticeTable
                    .selectAll()
                    .where { DunningNoticeTable.contributionId inList pageIds }
                    .groupBy { it[DunningNoticeTable.contributionId] }

            val candidateIds =
                pageRows.mapNotNull { row ->
                    val contributionId = row[ContributionTable.id]
                    val notices = noticesByContribution[contributionId].orEmpty()
                    val currentCycle = notices.currentCycleNumber()
                    val liveInCycle =
                        notices.filter {
                            it[DunningNoticeTable.cycleNumber] == currentCycle &&
                                it[DunningNoticeTable.status] != DunningNoticeStatus.CANCELLED
                        }
                    val lastNotice = liveInCycle.maxByOrNull { it[DunningNoticeTable.levelNumber] }
                    val lastLevelNumber = lastNotice?.get(DunningNoticeTable.levelNumber) ?: 0
                    val nextLevel =
                        activeLevels.firstOrNull { it[DunningLevelTable.levelNumber] > lastLevelNumber }
                            ?: return@mapNotNull null
                    // See `dunningReferenceDate` KDoc -- same cancellation-floor cooldown
                    // [issueDunningNotice]'s own Phase 1 respectSchedule check applies, mirrored
                    // here so this pre-filter's APPROXIMATION (see this function's own KDoc) never
                    // lets a just-reset/-cancelled cycle's contribution back onto a candidate page
                    // before issueDunningNotice would itself agree it is due. See the security
                    // review finding this fixes.
                    val referenceDate =
                        dunningReferenceDate(
                            allNoticesForContribution = notices,
                            lastLiveNotice = lastNotice,
                            dueDate = row[ContributionTable.dueDate],
                        )
                    val dueOn = referenceDate.plus(nextLevel[DunningLevelTable.graceDays], DateTimeUnit.DAY)
                    if (dueOn > now.date) return@mapNotNull null
                    contributionId
                }
            val lastRow = pageRows.last()
            PhaseBCandidatePage(
                candidateIds = candidateIds,
                rowCount = pageRows.size,
                lastCursor = PhaseBCursor(dueDate = lastRow[ContributionTable.dueDate], id = lastRow[ContributionTable.id]),
            )
        }

    /** See class KDoc "Phase B" for the four-condition guard: [DunningConfig.postalDispatchEnabled], `organizationSettings.postalMailEnabled`, and a complete recipient address. */
    private suspend fun maybeDispatchByPost(
        noticeId: Uuid,
        pdfBytes: ByteArray,
        recipient: MemberDto,
        levelName: String,
        now: LocalDateTime,
    ) {
        if (!dunningConfig.postalDispatchEnabled) return
        val postalMailEnabled =
            transaction {
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .singleOrNull()
                    ?.get(OrganizationSettingsTable.postalMailEnabled) == true
            }
        if (!postalMailEnabled) return
        if (recipient.street == null || recipient.postalCode == null || recipient.city == null || recipient.country == null) return

        val outcome =
            postalMailProvider.dispatchLetter(
                pdfBytes = pdfBytes,
                recipientName = recipient.displayName,
                recipientStreet = recipient.street.orEmpty(),
                recipientPostalCode = recipient.postalCode.orEmpty(),
                recipientCity = recipient.city.orEmpty(),
                recipientCountry = recipient.country.orEmpty(),
            )
        val logId = Uuid.random()
        transaction {
            val status: PostalDeliveryStatus
            val providerReference: String?
            val errorMessage: String?
            when (outcome) {
                is PostalDispatchOutcome.Dispatched -> {
                    status = PostalDeliveryStatus.SENT
                    providerReference = outcome.providerReference
                    errorMessage = null
                }
                is PostalDispatchOutcome.Failed -> {
                    status = PostalDeliveryStatus.FAILED
                    providerReference = null
                    errorMessage = outcome.sanitizedErrorMessage
                }
            }
            PostalDeliveryLogTable.insert {
                it[id] = logId
                it[recipientMemberId] = Uuid.parse(recipient.id)
                it[documentReference] = "Mahnung: $levelName"
                it[dispatchedAt] = now
                it[PostalDeliveryLogTable.status] = status
                it[PostalDeliveryLogTable.providerReference] = providerReference
                it[PostalDeliveryLogTable.errorMessage] = errorMessage
            }
            // Linked regardless of SENT vs. FAILED -- `toDunningNoticeDto` derives
            // `postalDeliveryStatus` purely from this link, and `null` specifically means "never
            // dispatched by post" (see Dunning.kt KDoc). Linking only on SENT left a FAILED dispatch
            // indistinguishable from "postal dispatch was never attempted", contradicting this
            // class's own KDoc "a lost letter is a visible gap in the overview" promise -- see the
            // security review finding this fixes.
            DunningNoticeTable.update({ DunningNoticeTable.id eq noticeId }) {
                it[postalDeliveryLogId] = logId
            }
        }
    }

    /**
     * Phase C -- self-healing. Notices left with `status = ISSUED AND document_id IS NULL` (a
     * crash between [issueDunningNotice]'s Phase 2 and [archiveDunningNoticePdf]) get their PDF
     * regenerated and re-archived. **No retry of the postal dispatch** -- see class KDoc "Phase B"
     * at-most-once discipline; this phase only heals the archive, never re-sends a letter.
     *
     * **Capped at [PHASE_C_MAX_PER_TICK] per tick** (unlike an earlier version of this phase, which
     * loaded and attempted EVERY orphaned notice unconditionally) -- see that constant's own KDoc
     * for why an unbounded scan here can starve Phase A/B under a sustained archive-storage outage.
     */
    private fun runPhaseC(now: LocalDateTime) {
        val orphanedNoticeIds =
            transaction {
                DunningNoticeTable
                    .selectAll()
                    .where { (DunningNoticeTable.status eq DunningNoticeStatus.ISSUED) and DunningNoticeTable.documentId.isNull() }
                    .orderBy(DunningNoticeTable.issuedAt, SortOrder.ASC)
                    .limit(PHASE_C_MAX_PER_TICK)
                    .map { it[DunningNoticeTable.id] }
            }
        for (noticeId in orphanedNoticeIds) {
            try {
                val noticeRow =
                    transaction {
                        DunningNoticeTable.selectAll().where { DunningNoticeTable.id eq noticeId }.singleOrNull()
                    } ?: continue
                val contributionId = noticeRow[DunningNoticeTable.contributionId]
                // Two separate queries, not a single DunningNoticeTable-joined-with-Contribution/
                // Member walk: dunning_notice ALSO has its own FK into member (created_by), so a
                // bare multi-table join risks the same "multiple primary key <-> foreign key
                // references" ambiguity `ContributionService.contributionJoin`'s own KDoc documents
                // -- avoided here by never joining DunningNoticeTable and MemberTable in one query.
                val contributionRow =
                    transaction {
                        contributionJoinForPoller()
                            .selectAll()
                            .where { ContributionTable.id eq contributionId }
                            .singleOrNull()
                    } ?: continue
                val memberId = contributionRow[ContributionTable.memberId]
                val recipient = transaction { loadMailmergeMember(memberId) } ?: continue
                val organization = transaction { loadOrganizationSettingsDto() }
                val contributionDto =
                    ContributionDto(
                        id = contributionRow[ContributionTable.id].toString(),
                        memberId = memberId.toString(),
                        memberDisplayName = recipient.displayName,
                        membershipTierId = contributionRow[ContributionTable.membershipTierId].toString(),
                        membershipTierName = contributionRow[MembershipTierTable.name],
                        periodStart = contributionRow[ContributionTable.periodStart],
                        periodEnd = contributionRow[ContributionTable.periodEnd],
                        amountDue = contributionRow[ContributionTable.amountDue],
                        status = contributionRow[ContributionTable.status],
                        paidAt = contributionRow[ContributionTable.paidAt],
                        paidAmount = contributionRow[ContributionTable.paidAmount],
                        note = contributionRow[ContributionTable.note],
                        createdAt = contributionRow[ContributionTable.createdAt],
                        dueDate = contributionRow[ContributionTable.dueDate],
                        paymentMethod = contributionRow[ContributionTable.paymentMethod],
                    )
                val row = noticeRow
                val pdfBytes =
                    MahnungPdfGenerator.generate(
                        contribution = contributionDto,
                        member = recipient,
                        organization = organization,
                        levelName = row[DunningNoticeTable.levelName],
                        levelNumber = row[DunningNoticeTable.levelNumber],
                        feeAmount = row[DunningNoticeTable.feeAmount],
                        respondBy = row[DunningNoticeTable.respondBy],
                        issuedOn = row[DunningNoticeTable.issuedAt].date,
                    )
                // Same attribution rule [issueDunningNotice]'s own Phase 2 uses for a system-issued
                // notice (`created_by IS NULL`): fall back to the last ADMIN who acknowledged the
                // compliance disclaimer -- NEVER the gemahnte member. See
                // [lastComplianceAcknowledgerMemberId] KDoc and the security review finding this
                // fixes ("uploaderId = ... ?: memberId" mis-attributed the debtor as the uploader).
                val uploaderId = row[DunningNoticeTable.createdBy] ?: transaction { lastComplianceAcknowledgerMemberId() }
                archiveDunningNoticePdf(
                    storageRoot = documentStorageRoot,
                    noticeId = noticeId,
                    pdfBytes = pdfBytes,
                    levelName = row[DunningNoticeTable.levelName],
                    uploadedBy = uploaderId,
                )
            } catch (e: Throwable) {
                logger.warn(e) { "DunningPoller: phase C failed for notice $noticeId" }
            }
        }
    }
}

/** Same explicit-join disambiguation as `network.lapis.cloud.server.rpc.ContributionService.contributionJoin` -- see that function's own KDoc. */
private fun contributionJoinForPoller() =
    ContributionTable
        .innerJoin(MemberTable)
        .join(MembershipTierTable, org.jetbrains.exposed.v1.core.JoinType.INNER, ContributionTable.membershipTierId, MembershipTierTable.id)
