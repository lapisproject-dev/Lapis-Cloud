package network.lapis.cloud.server.payment.sepa

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.SepaDebitBatchTable
import network.lapis.cloud.server.db.generated.SepaDebitItemTable
import network.lapis.cloud.server.db.generated.SepaMandateTable
import network.lapis.cloud.server.db.generated.SepaReturnTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.resetGeneratedBatchesForUnusableMandate
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaDebitItemStatus
import network.lapis.cloud.shared.domain.SepaMandateStatus
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V1.2.2 "SEPA-Lastschriftmandate". Application-scoped poller for the three time-driven SEPA
 * transitions this wave introduces. Mirrors [network.lapis.cloud.server.conference.RecordingPoller]
 * literally -- ONE coroutine (`SupervisorJob() + Dispatchers.IO`), `while (isActive) { tick(); delay
 * (interval) }`, [tick] public and exception-safe at two levels, [start]/[stop] idempotent, NO
 * in-memory state (every phase re-queries its candidates fresh every tick -- restart reconciliation).
 *
 * **This poller never touches accounting.** It calls neither `ContributionPostingBridge` nor
 * `AccountingService` nor `CashRegisterGuard`; it creates no `JournalEntry` and no `Posting` row.
 * Reason: `ContributionPostingBridge.postContributionPayment` requires a NON-nullable
 * `actorMemberId` (`journal_entry.created_by` is `NOT NULL`), and the decision "sentinel system
 * member vs. nullable column" was explicitly deferred by Welle V1.2.1 as a human decision (plan D-5).
 * This wave does not make that decision either -- it sidesteps it. The poller only marks readiness;
 * a human triggers the posting via `ISepaService.settleBatch`.
 */
class SepaBatchPoller(
    private val sepaConfig: SepaConfig,
    private val clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
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
                    delay(sepaConfig.pollIntervalSeconds.seconds)
                }
            }
    }

    /** Cancels the poll loop -- for tests/graceful shutdown. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * One poll pass, three phases. Exception-safe at two levels (whole tick + each row individually)
     * so a single broken row never stops the others, and tests can call this directly with zero
     * timing dependency -- same contract as [network.lapis.cloud.server.conference.RecordingPoller.tick].
     *
     * A complete no-op if `organization_settings.sepa_debit_enabled` is `false` -- an independent,
     * second gate on top of [SepaConfig.pollerEnabled] (same "sandbox-first" discipline as
     * `LetterxpressPostalMailProvider`: a staging instance seeded with a production dump must never
     * accidentally lapse real mandates).
     */
    suspend fun tick() {
        try {
            val sepaEnabled =
                transaction {
                    OrganizationSettingsTable
                        .selectAll()
                        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                        .singleOrNull()
                        ?.get(OrganizationSettingsTable.sepaDebitEnabled) == true
                }
            if (!sepaEnabled) return

            val now = clock()
            runPhaseA(now)
            runPhaseB(now)
            runPhaseC(now)
        } catch (e: Throwable) {
            logger.warn(e) { "SepaBatchPoller: tick failed" }
        }
    }

    /**
     * Phase A -- mandate expiry after 36 months without use.
     *
     * Security Round 3 (2026-08-20, F-1b): once a mandate is actually flipped to EXPIRED below,
     * [resetGeneratedBatchesForUnusableMandate] is now called for it -- before this fix, this phase
     * flipped the mandate via a bare table update and wrote only its own mandate-level audit entry,
     * leaving ANY of that mandate's still-PENDING items in a DRAFT/NOTIFIED/GENERATED batch
     * completely untouched, exactly the same gap [SepaBatchPoller.runPhaseB]'s own NEW-1 fix already
     * closed for the membership-withdrawal path. A batch already GENERATED at the moment a mandate's
     * 36-month non-use clock lapses kept a stale pain.008 file on disk that still authorized debiting
     * the now-expired mandate's account. [network.lapis.cloud.server.rpc.SepaService.markBatchSubmitted]
     * ALSO now re-checks mandate validity itself (Security Round 3, F-1a) as a poller-independent
     * backstop -- this call is defense-in-depth/prompt cleanup, not the only guard: it resets the
     * batch promptly rather than silently waiting for a treasurer to hit the submission-time check.
     * `actorMemberId = null, actorRole = null` -- SAME system-actor convention this phase already
     * uses for its own mandate-level [AuditLogRecorder.record] call just below: this poller runs in a
     * background coroutine with no authenticated human caller at all.
     */
    private fun runPhaseA(now: LocalDateTime) {
        val candidates =
            transaction {
                SepaMandateTable
                    .selectAll()
                    .where { SepaMandateTable.status eq SepaMandateStatus.ACTIVE }
                    .map { it.toMandateRow() }
            }
        for (mandate in candidates) {
            try {
                val expiresAt = SepaConfig.mandateExpiryDate(grantedAt = mandate.grantedAt.date, lastUsedAt = mandate.lastUsedAt)
                if (expiresAt >= now.date) continue
                transaction {
                    val updated =
                        SepaMandateTable.update({
                            (SepaMandateTable.id eq mandate.id) and
                                (SepaMandateTable.status eq SepaMandateStatus.ACTIVE)
                        }) {
                            it[status] = SepaMandateStatus.EXPIRED
                        }
                    if (updated > 0) {
                        AuditLogRecorder.record(
                            actorMemberId = null,
                            actorRole = null,
                            entityType = AuditEntityType.SEPA_MANDATE,
                            entityId = mandate.id,
                            action = AuditAction.UPDATE,
                            occurredAt = now,
                        )
                        resetGeneratedBatchesForUnusableMandate(mandateId = mandate.id, actorMemberId = null, actorRole = null)
                    }
                }
            } catch (e: Throwable) {
                logger.warn(e) { "SepaBatchPoller: phase A failed for mandate ${mandate.id}" }
            }
        }
    }

    /**
     * Phase B -- mandate lapses when the member's status becomes WITHDRAWN/REJECTED.
     *
     * Review Round 1 (2026-08-19, CRITICAL, discovered via M-1's new test coverage -- see class
     * KDoc): `SepaMandateTable innerJoin MemberTable`'s bare, implicit-join form throws
     * `IllegalStateException` at RUNTIME ("multiple primary key <-> foreign key references") --
     * `sepa_mandate` has THREE foreign keys into `member` (`member_id`/`revoked_by`/`created_by`),
     * so Exposed cannot auto-infer which one this join means. This made Phase B a complete, silent
     * no-op from V1.2.2's first implementation onward -- WITHDRAWN/REJECTED members' mandates were
     * NEVER auto-revoked by the poller, on every deployment that has it enabled, and (per its own
     * exception-swallowing `tick()` contract) failed silently into a WARN log line no one was
     * watching for. Fixed by naming the join column explicitly -- same
     * `.join(Table, JoinType.INNER, col1, col2)` idiom every other multi-FK-into-member join in
     * this codebase already uses (e.g. `LtrLedgerService.ledgerEntryJoin`/`DsgvoService`'s
     * `ErasureRequestTable` join).
     *
     * Security Round 2 (2026-08-20, NEW-1): once a mandate is actually flipped to REVOKED below,
     * [resetGeneratedBatchesForUnusableMandate] is now called for it -- before this fix, this phase
     * left ANY of that mandate's still-PENDING items in a DRAFT/NOTIFIED/GENERATED batch completely
     * untouched, so a batch already GENERATED at the moment a member's membership lapses kept a
     * stale pain.008 file on disk that still authorized debiting the now-withdrawn/rejected member.
     * `actorMemberId = null, actorRole = null` -- SAME system-actor convention this phase already
     * uses for its own mandate-level [AuditLogRecorder.record] call just below: this poller runs in
     * a background coroutine with no authenticated human caller at all.
     *
     * Security finding fix (2026-08-26, LOW/latent, feature/v1.2.11-member-csv-import): the
     * WITHDRAWN/REJECTED literal pair below used to be hardcoded here, so V1.2.11's new DECEASED
     * terminal status silently fell through this defense-in-depth revocation -- a member set to
     * DECEASED (only reachable via a direct `psql` status change today, see `MemberStatus` KDoc)
     * kept its ACTIVE mandate and any already-GENERATED pain.008 file untouched. Not exploitable
     * via the normal debit-generation path, which is a positive
     * [MemberStatusSets.ORGANIZATION_MEMBER] allowlist that DECEASED was never in (see
     * `SepaService`'s contribution-eligibility check) -- this is purely the belt-and-suspenders
     * layer. Now reads [MemberStatusSets.MEMBERSHIP_ENDED] instead, so a future terminal status
     * gets this revocation for free too.
     *
     * Welle V1.2.12: the actual revocation body was extracted to the per-MEMBER
     * [revokeMandatesForEndedMembership] (shared with `network.lapis.cloud.server.rpc
     * .MemberService.updateMemberStatus`, see that function's own KDoc) -- this method's job is now
     * only candidate DISCOVERY (which members currently have an ACTIVE mandate AND a
     * MEMBERSHIP_ENDED status) and per-member error isolation. The granularity is now per MEMBER,
     * not per MANDATE (a member has at most one ACTIVE mandate in practice, but this makes no such
     * assumption) -- [network.lapis.cloud.server.payment.sepa.SepaBatchPollerTest]'s Phase-B
     * assertions check RESULTS, not iteration granularity, and stay green across this refactor;
     * that is this extraction's own regression proof.
     *
     * The explicit `.join(MemberTable, JoinType.INNER, SepaMandateTable.memberId, MemberTable.id)`
     * below MUST stay explicit -- `sepa_mandate` has THREE foreign keys into `member`
     * (`member_id`/`revoked_by`/`created_by`), so Exposed's implicit `innerJoin` throws
     * `IllegalStateException` at runtime (see "Review Round 1" above, the exact bug that made this
     * whole phase a silent no-op from V1.2.2 until 2026-08-19).
     */
    private fun runPhaseB(now: LocalDateTime) {
        val candidateMemberIds =
            transaction {
                SepaMandateTable
                    .join(MemberTable, JoinType.INNER, SepaMandateTable.memberId, MemberTable.id)
                    .select(SepaMandateTable.memberId)
                    .where {
                        (SepaMandateTable.status eq SepaMandateStatus.ACTIVE) and
                            (MemberTable.status inList MemberStatusSets.MEMBERSHIP_ENDED)
                    }.map { it[SepaMandateTable.memberId] }
                    .distinct()
            }
        for (memberId in candidateMemberIds) {
            try {
                transaction {
                    revokeMandatesForEndedMembership(memberId = memberId, actorMemberId = null, actorRole = null, now = now)
                }
            } catch (e: Throwable) {
                logger.warn(e) { "SepaBatchPoller: phase B failed for member $memberId" }
            }
        }
    }

    /** Phase C -- items of a SUBMITTED batch past the 8-week return window, with no return, become SETTLEABLE. */
    private fun runPhaseC(now: LocalDateTime) {
        val eligibleBatchIds =
            transaction {
                SepaDebitBatchTable
                    .selectAll()
                    .where { SepaDebitBatchTable.status eq SepaDebitBatchStatus.SUBMITTED }
                    .mapNotNull { row ->
                        val submittedAt = row[SepaDebitBatchTable.submittedAt] ?: return@mapNotNull null
                        val eligibleFrom = submittedAt.date.plus(SepaConfig.RETURN_WINDOW_DAYS, DateTimeUnit.DAY)
                        if (eligibleFrom <= now.date) row[SepaDebitBatchTable.id] else null
                    }
            }
        for (batchId in eligibleBatchIds) {
            try {
                transaction {
                    val pendingItemIds =
                        SepaDebitItemTable
                            .selectAll()
                            .where {
                                (SepaDebitItemTable.batchId eq batchId) and (SepaDebitItemTable.status eq SepaDebitItemStatus.PENDING)
                            }.map { it[SepaDebitItemTable.id] }
                    if (pendingItemIds.isEmpty()) return@transaction
                    val returnedItemIds =
                        SepaReturnTable
                            .selectAll()
                            .where { SepaReturnTable.debitItemId inList pendingItemIds }
                            .map { it[SepaReturnTable.debitItemId] }
                            .toSet()
                    val settleableIds = pendingItemIds - returnedItemIds
                    if (settleableIds.isEmpty()) return@transaction
                    // C-1 fix (Review Round 1, 2026-08-19, CRITICAL): the candidate SELECT above takes
                    // no row lock, so a treasurer's recordReturn() can flip one of these ids from
                    // PENDING to RETURNED (and insert its sepa_return row) in the gap between that
                    // SELECT and this UPDATE. Without the re-check below, this UPDATE -- which
                    // previously matched purely on `id` -- would still flip that now-RETURNED item back
                    // to SETTLEABLE, resurrecting a returned/never-collected debit into the settleable
                    // pool where settleBatch would book it as paid. Mirrors the SAME
                    // `and (status eq ...)` re-check Phase A/Phase B already apply on their own UPDATEs.
                    val updated =
                        SepaDebitItemTable.update({
                            (SepaDebitItemTable.id inList settleableIds) and (SepaDebitItemTable.status eq SepaDebitItemStatus.PENDING)
                        }) {
                            it[status] = SepaDebitItemStatus.SETTLEABLE
                            it[settleableAt] = now.date
                        }
                    if (updated > 0) {
                        AuditLogRecorder.record(
                            actorMemberId = null,
                            actorRole = null,
                            entityType = AuditEntityType.SEPA_DEBIT_BATCH,
                            entityId = batchId,
                            action = AuditAction.UPDATE,
                            occurredAt = now,
                        )
                    }
                }
            } catch (e: Throwable) {
                logger.warn(e) { "SepaBatchPoller: phase C failed for batch $batchId" }
            }
        }
    }
}

/** Plain data snapshot of a `sepa_mandate` row -- never held across a suspension point as a live [ResultRow]. */
private data class MandateRow(
    val id: Uuid,
    val grantedAt: LocalDateTime,
    val lastUsedAt: kotlinx.datetime.LocalDate?,
)

private fun ResultRow.toMandateRow() =
    MandateRow(
        id = this[SepaMandateTable.id],
        grantedAt = this[SepaMandateTable.grantedAt],
        lastUsedAt = this[SepaMandateTable.lastUsedAt],
    )
