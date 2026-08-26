package network.lapis.cloud.server.payment.dunning

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DunningComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.DunningLevelTable
import network.lapis.cloud.server.db.generated.DunningNoticeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.SepaMandateTable
import network.lapis.cloud.server.pdf.MahnungPdfGenerator
import network.lapis.cloud.server.routes.archiveGeneratedPdf
import network.lapis.cloud.server.routes.loadMailmergeMember
import network.lapis.cloud.server.routes.loadOrganizationSettingsDto
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ContributionDto
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DunningNoticeSnapshot
import network.lapis.cloud.shared.domain.DunningNoticeStatus
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.SepaMandateStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.2.7 "Automatisiertes Mahnwesen". Request for [issueDunningNotice] -- the ONE shared
 * issuance path both [DunningPoller] (`respectSchedule = true`, `actorMemberId = null`) AND every
 * manual RPC override in `network.lapis.cloud.server.rpc.DunningService`
 * (`respectSchedule = false`, a real member actor) go through. Direct lesson from the V1.2.2
 * security review's own "four paths, one helper" finding.
 */
internal data class DunningIssueRequest(
    val contributionId: Uuid,
    val actorMemberId: Uuid?,
    val actorRole: AccountRole?,
    val now: LocalDateTime,
    /** Poller: `true` (deadlines count). Manual RPC: `false` (a treasurer may dun immediately). */
    val respectSchedule: Boolean,
)

/**
 * Feature gate for every WRITE method: `dunning_enabled == true` AND at least one active
 * `dunning_level` row exists. Shared by `network.lapis.cloud.server.rpc.DunningService` (every
 * WRITE RPC method) AND `network.lapis.cloud.server.routes.registerDunningRoutes`'s `preview.pdf`
 * route -- that route used to duplicate [issueDunningNotice]'s level-selection logic WITHOUT this
 * gate, so a treasurer could generate full dunning-notice PDFs (postal address + amount owed) even
 * with `dunning_enabled = false` and the disclaimer never acknowledged, defeating the "five
 * independent safeguards" [DunningPoller]/[DunningConfig] KDoc promises. Lifted out of
 * `DunningService` (which used to keep this `private`) into this shared, transaction-independent
 * top-level function so both callers use the exact SAME gate rather than two copies drifting apart.
 * See the security review finding this fixes. Mirrors `SepaService.requireSepaUsable`'s own
 * three-part shape (minus the disclaimer-acknowledgment check, which `dunning_enabled` already
 * implies -- see [network.lapis.cloud.server.rpc.DunningService.enableDunning]).
 */
internal fun requireDunningUsable() {
    transaction {
        val enabled =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
                ?.get(OrganizationSettingsTable.dunningEnabled) == true
        if (!enabled) {
            throw ConflictException(
                "Automatisiertes Mahnwesen ist deaktiviert -- zuerst getDunningComplianceDisclaimer aufrufen und " +
                    "per enableDunning aktivieren.",
            )
        }
        val hasActiveLevel =
            DunningLevelTable
                .selectAll()
                .where { DunningLevelTable.active eq true }
                .limit(1)
                .any()
        if (!hasActiveLevel) {
            throw ConflictException("Keine aktive Mahnstufe konfiguriert -- zuerst mindestens eine Mahnstufe anlegen.")
        }
    }
}

internal sealed interface DunningIssueOutcome {
    data class Issued(
        val noticeId: Uuid,
        val levelNumber: Int,
        val levelName: String,
        val pdfBytes: ByteArray,
        val recipient: MemberDto,
        val respondBy: LocalDate,
        /**
         * Who [network.lapis.cloud.server.routes.DocumentArchiving.archiveGeneratedPdf]'s NOT NULL
         * `document.created_by` should be attributed to. For a human-triggered notice this is that
         * member's own id. For a poller-issued notice (`actorMemberId == null`) there is no human
         * actor in the request itself -- this instead falls back to whichever ADMIN most recently
         * acknowledged [network.lapis.cloud.server.rpc.DunningComplianceDisclaimer] (the person who
         * turned dunning on in the first place), a real and meaningful attribution rather than an
         * invented sentinel member (same "no sentinel system member" deferral
         * `network.lapis.cloud.server.payment.sepa.SepaBatchPoller`'s own KDoc documents for
         * accounting's `journal_entry.created_by`).
         */
        val documentUploaderId: Uuid,
    ) : DunningIssueOutcome

    /** Configured, but the next level's `graceDays` have not elapsed yet -- only reachable when `respectSchedule = true`. */
    data object NotDue : DunningIssueOutcome

    /** Every configured active level has already been used up in the current cycle. */
    data object NoFurtherLevel : DunningIssueOutcome

    /** `dunning_level` has zero active rows -- see [DunningConfig] KDoc "independent safeguards". */
    data object NoLevelsConfigured : DunningIssueOutcome

    /** Not in [ContributionStatusSets.DUNNABLE], or covered by an active SEPA mandate (unless RETURNED). */
    data class NotDunnable(
        val reason: String,
    ) : DunningIssueOutcome

    /** The (contribution, cycle, level) slot is already occupied -- the `uq_dunning_notice_slot` idempotency anchor fired. */
    data object AlreadyIssued : DunningIssueOutcome

    /**
     * Phase 1's `currentCycle`/next-level snapshot no longer matches reality once Phase 2 re-derives
     * it under the contribution-row lock -- e.g. a concurrent [DunningService.cancelDunningNotice]
     * or [DunningService.resetDunning] whole-cycle-cancelled the very cycle Phase 1 computed, or
     * live-in-cycle already advanced past the planned level, in the real-wall-clock window Phase 1's
     * transaction closes but PDF generation (outside any transaction) leaves open before Phase 2
     * acquires its lock. Distinct from [AlreadyIssued] (the exact planned slot is occupied): this
     * covers the broader case where the planned slot itself is no longer the right one to fill. The
     * caller should simply treat this like [AlreadyIssued] -- the request is stale, a fresh call (a
     * later poller tick, or the user retrying) computes the right slot from scratch. See the
     * security review finding this fixes.
     */
    data object Superseded : DunningIssueOutcome
}

/** Plain, transaction-independent snapshot handed from Phase 1 to the PDF step and Phase 2. */
private data class PreparedIssuance(
    val contribution: ContributionDto,
    val recipient: MemberDto,
    val organization: OrganizationSettingsDto,
    val levelId: Uuid,
    val levelNumber: Int,
    val levelName: String,
    val feeAmount: BigDecimal?,
    val cycleNumber: Int,
    val respondBy: LocalDate,
)

/**
 * See [DunningIssueRequest] KDoc. Three phases, deliberately split (same discipline
 * `network.lapis.cloud.server.rpc.SepaService.generateBatchFile`'s own KDoc establishes -- never
 * generate a PDF or do file I/O while holding a DB transaction open):
 *
 * 1. **Phase 1** (`transaction {}`, read-only): resolve the contribution/member/org, the DUNNABLE/
 *    SEPA-coverage guards, the next escalation level and its due-date, and build immutable DTO
 *    snapshots to carry across the un-transacted PDF step.
 * 2. **PDF generation** (no transaction open): [MahnungPdfGenerator.generate].
 * 3. **Phase 2** (`transaction {}`, writing): re-locks the contribution row, re-checks it is still
 *    DUNNABLE (closes the TOCTOU window a concurrent payment could have opened), inserts the
 *    `dunning_notice` row (catching the `uq_dunning_notice_slot` unique-constraint violation as
 *    [DunningIssueOutcome.AlreadyIssued] rather than letting it propagate), flips the contribution
 *    to `IN_DUNNING`, and writes the [AuditEntityType.DUNNING_NOTICE] audit entry as the LAST
 *    locking operation of the transaction (same vertrag [AuditLogRecorder.record] documents).
 * 4. **Phase 3**, run by the caller AFTER this function returns an [DunningIssueOutcome.Issued]:
 *    [network.lapis.cloud.server.routes.archiveGeneratedPdf] + set `document_id` -- kept OUTSIDE
 *    this function (unlike `SepaService.generateBatchFile`'s own inline Phase 3) so
 *    [DunningPoller] Phase C can reuse exactly the same archive-and-link step for a notice whose
 *    `document_id` is transiently `null` after a crash between Phase 2 and archiving.
 */
internal fun issueDunningNotice(
    request: DunningIssueRequest,
    storageRoot: File,
    /**
     * Test-only seam, invoked after PDF generation and BEFORE Phase 2's `forUpdate()` lock is
     * acquired -- exactly the real race window a concurrent [DunningService.cancelDunningNotice] /
     * [DunningService.resetDunning] can exploit (PDF generation runs outside any transaction and
     * takes genuine wall-clock time; no production caller overrides this, same "injectable-for-tests
     * lambda" idiom [DunningPoller]'s own `clock` parameter already establishes). Lets
     * `DunningIssuanceTest` deterministically reproduce the stale-`prepared`-snapshot race this
     * function's own Phase 2 re-check below closes, instead of relying on timing. See the security
     * review finding this fixes.
     */
    onBeforePhase2Lock: () -> Unit = {},
): DunningIssueOutcome {
    val prepared =
        transaction {
            val contributionRow =
                contributionJoin()
                    .selectAll()
                    .where { ContributionTable.id eq request.contributionId }
                    .singleOrNull()
                    ?: return@transaction DunningIssueOutcome.NotDunnable("Beitrag nicht gefunden.")

            val status = contributionRow[ContributionTable.status]
            if (status !in ContributionStatusSets.DUNNABLE) {
                return@transaction DunningIssueOutcome.NotDunnable(
                    "Beitrag hat den Status $status -- nicht mahnfaehig (erlaubt: " +
                        "${ContributionStatusSets.DUNNABLE.joinToString(", ")}).",
                )
            }

            val paymentMethod = contributionRow[ContributionTable.paymentMethod]
            val memberId = contributionRow[ContributionTable.memberId]
            if (paymentMethod == ContributionPaymentMethod.SEPA_DEBIT && status != ContributionStatus.RETURNED) {
                val hasActiveMandate =
                    SepaMandateTable
                        .selectAll()
                        .where { (SepaMandateTable.memberId eq memberId) and (SepaMandateTable.status eq SepaMandateStatus.ACTIVE) }
                        .limit(1)
                        .any()
                if (hasActiveMandate) {
                    return@transaction DunningIssueOutcome.NotDunnable(
                        "Beitrag ist per SEPA-Lastschrift gedeckt (aktives Mandat) -- der naechste Lastschriftlauf zieht ein.",
                    )
                }
            }

            val activeLevels =
                DunningLevelTable
                    .selectAll()
                    .where { DunningLevelTable.active eq true }
                    .orderBy(DunningLevelTable.levelNumber, SortOrder.ASC)
                    .toList()
            if (activeLevels.isEmpty()) return@transaction DunningIssueOutcome.NoLevelsConfigured

            val allNoticesForContribution =
                DunningNoticeTable
                    .selectAll()
                    .where { DunningNoticeTable.contributionId eq request.contributionId }
                    .toList()
            val currentCycle = allNoticesForContribution.currentCycleNumber()
            val lastNotice =
                allNoticesForContribution
                    .filter {
                        it[DunningNoticeTable.cycleNumber] == currentCycle &&
                            it[DunningNoticeTable.status] != DunningNoticeStatus.CANCELLED
                    }.maxByOrNull { it[DunningNoticeTable.levelNumber] }

            val lastLevelNumber = lastNotice?.get(DunningNoticeTable.levelNumber) ?: 0
            val nextLevelRow =
                activeLevels.firstOrNull { it[DunningLevelTable.levelNumber] > lastLevelNumber }
                    ?: return@transaction DunningIssueOutcome.NoFurtherLevel

            if (request.respectSchedule) {
                val referenceDate =
                    dunningReferenceDate(
                        allNoticesForContribution = allNoticesForContribution,
                        lastLiveNotice = lastNotice,
                        dueDate = contributionRow[ContributionTable.dueDate],
                    )
                val dueOn = referenceDate.plus(nextLevelRow[DunningLevelTable.graceDays], DateTimeUnit.DAY)
                if (dueOn > request.now.date) return@transaction DunningIssueOutcome.NotDue
            }

            val respondBy = request.now.date.plus(nextLevelRow[DunningLevelTable.responseDays], DateTimeUnit.DAY)
            val contributionDto = contributionRow.toContributionDtoForDunning()
            val recipient = loadMailmergeMember(memberId) ?: return@transaction DunningIssueOutcome.NotDunnable("Mitglied nicht gefunden.")
            val organization = loadOrganizationSettingsDto()

            // Security review finding: `DunningService.validateLevelInput` only rejects a fee on
            // `levelNumber == 1` at CONFIGURATION time -- an ADMIN could still configure ONLY
            // level 2 (with a fee) and no level 1 at all, or deactivate level 1 after the fact, and
            // this contribution's very first notice would then be `nextLevelRow` with a fee attached
            // regardless of its `levelNumber`. [hasDeliveredNoticeInCycle] `false` means EXACTLY "no
            // notice has ever actually been DELIVERED in this cycle yet" (round-2 fix: not merely
            // "no live notice", which a `SKIPPED` row also satisfied -- see that function's own
            // KDoc) -- i.e. the notice about to be created here is the first one this
            // contribution/cycle will ever see, independent of which configured `levelNumber`
            // happens to be selected. Forcing the fee to `null` in that case is what the disclaimer
            // (`DunningComplianceDisclaimer.TEXT` "eine Gebuehr auf der ersten konfigurierten
            // Mahnstufe wird abgelehnt") actually promises -- structurally, not merely via a
            // config-time check that active/inactive-level churn can bypass. See the security
            // review finding this fixes.
            val effectiveFeeAmount =
                if (allNoticesForContribution.hasDeliveredNoticeInCycle(currentCycle)) {
                    nextLevelRow[DunningLevelTable.feeAmount]
                } else {
                    null
                }

            PreparedIssuance(
                contribution = contributionDto,
                recipient = recipient,
                organization = organization,
                levelId = nextLevelRow[DunningLevelTable.id],
                levelNumber = nextLevelRow[DunningLevelTable.levelNumber],
                levelName = nextLevelRow[DunningLevelTable.name],
                feeAmount = effectiveFeeAmount,
                cycleNumber = currentCycle,
                respondBy = respondBy,
            )
        }

    if (prepared !is PreparedIssuance) return prepared as DunningIssueOutcome

    // Phase "PDF generation" -- deliberately outside any transaction.
    val pdfBytes =
        MahnungPdfGenerator.generate(
            contribution = prepared.contribution,
            member = prepared.recipient,
            organization = prepared.organization,
            levelName = prepared.levelName,
            levelNumber = prepared.levelNumber,
            feeAmount = prepared.feeAmount,
            respondBy = prepared.respondBy,
            issuedOn = request.now.date,
        )

    onBeforePhase2Lock()

    return transaction {
        val lockedContribution =
            ContributionTable
                .selectAll()
                .where { ContributionTable.id eq request.contributionId }
                .forUpdate()
                .singleOrNull()
                ?: return@transaction DunningIssueOutcome.NotDunnable("Beitrag nicht gefunden.")
        if (lockedContribution[ContributionTable.status] !in ContributionStatusSets.DUNNABLE) {
            return@transaction DunningIssueOutcome.NotDunnable(
                "Beitrag hat sich zwischenzeitlich geaendert (Status: ${lockedContribution[ContributionTable.status]}).",
            )
        }

        // Re-derive the CURRENT cycle/level under the contribution-row lock just acquired above --
        // `prepared.cycleNumber`/`prepared.levelNumber` are a Phase 1 SNAPSHOT that can go stale in
        // the real wall-clock gap between Phase 1's own transaction and this one (PDF generation runs
        // OUTSIDE any transaction). Locking the contribution row serializes this transaction against
        // a concurrent cancelDunningNotice/resetDunning (which locks the SAME row first, see their
        // own "Locking" KDoc), but serialization alone does not make the STALE snapshot correct --
        // without this re-check, a whole-cycle-cancel that lands in the gap bumps
        // `currentCycleNumber()` to a fresh cycle while `alreadyOccupied` below still tests the OLD,
        // now-vacated (contribution, prepared.cycleNumber, prepared.levelNumber) slot, which is free
        // -- so the insert below would silently resurrect a notice inside a cycle the caller was just
        // told is fully cancelled. See the security review finding this fixes for the concrete
        // ladder-freeze scenario.
        val liveNoticesNow =
            DunningNoticeTable
                .selectAll()
                .where { DunningNoticeTable.contributionId eq request.contributionId }
                .toList()
        val currentCycleNow = liveNoticesNow.currentCycleNumber()
        val lastLevelNumberNow =
            liveNoticesNow
                .filter {
                    it[DunningNoticeTable.cycleNumber] == currentCycleNow &&
                        it[DunningNoticeTable.status] != DunningNoticeStatus.CANCELLED
                }.maxOfOrNull { it[DunningNoticeTable.levelNumber] } ?: 0
        if (currentCycleNow != prepared.cycleNumber || lastLevelNumberNow >= prepared.levelNumber) {
            return@transaction DunningIssueOutcome.Superseded
        }

        val alreadyOccupied =
            DunningNoticeTable
                .selectAll()
                .where {
                    (DunningNoticeTable.contributionId eq request.contributionId) and
                        (DunningNoticeTable.cycleNumber eq prepared.cycleNumber) and
                        (DunningNoticeTable.levelNumber eq prepared.levelNumber)
                }.limit(1)
                .any()
        if (alreadyOccupied) return@transaction DunningIssueOutcome.AlreadyIssued

        val documentUploaderId = request.actorMemberId ?: lastComplianceAcknowledgerMemberId()

        val noticeId = Uuid.random()
        try {
            DunningNoticeTable.insert {
                it[id] = noticeId
                it[contributionId] = request.contributionId
                it[dunningLevelId] = prepared.levelId
                it[cycleNumber] = prepared.cycleNumber
                it[levelNumber] = prepared.levelNumber
                it[levelName] = prepared.levelName
                it[feeAmount] = prepared.feeAmount
                it[amountDue] = lockedContribution[ContributionTable.amountDue]
                it[status] = DunningNoticeStatus.ISSUED
                it[issuedAt] = request.now
                it[respondBy] = prepared.respondBy
                it[documentId] = null
                it[postalDeliveryLogId] = null
                it[createdBy] = request.actorMemberId
                it[cancelledAt] = null
                it[cancellationReason] = null
            }
        } catch (e: ExposedSQLException) {
            // The `uq_dunning_notice_slot` unique-constraint violation this function's own KDoc
            // documents -- caught here (rather than propagating) exactly as documented. The
            // `forUpdate()` lock above already makes this practically unreachable for a race on the
            // SAME contribution (poller vs. manual RPC, or two manual RPCs); this is the same
            // "pre-check is racy, the DB-level UNIQUE is the real backstop" idiom
            // `RegistrationService.registerFriend`/`AccountingService.createLedgerAccount`/
            // `PoliticianService.grantPoliticianStatus`/`ElectionService.castElectionBallot` each
            // already establish for THEIR OWN first-write races.
            return@transaction DunningIssueOutcome.AlreadyIssued
        }
        ContributionTable.update({
            (ContributionTable.id eq request.contributionId) and (ContributionTable.status neq ContributionStatus.PAID)
        }) {
            it[status] = ContributionStatus.IN_DUNNING
        }

        AuditLogRecorder.record(
            actorMemberId = request.actorMemberId,
            actorRole = request.actorRole,
            entityType = AuditEntityType.DUNNING_NOTICE,
            entityId = noticeId,
            action = AuditAction.CREATE,
            before = null,
            after =
                Json.encodeToString(
                    DunningNoticeSnapshot.serializer(),
                    DunningNoticeSnapshot(
                        contributionId = request.contributionId.toString(),
                        cycleNumber = prepared.cycleNumber,
                        levelNumber = prepared.levelNumber,
                        levelName = prepared.levelName,
                        status = DunningNoticeStatus.ISSUED,
                        amountDue = lockedContribution[ContributionTable.amountDue],
                        feeAmount = prepared.feeAmount,
                        respondBy = prepared.respondBy,
                        documentId = null,
                        issuedBySystem = request.actorMemberId == null,
                    ),
                ),
        )

        DunningIssueOutcome.Issued(
            noticeId = noticeId,
            levelNumber = prepared.levelNumber,
            levelName = prepared.levelName,
            pdfBytes = pdfBytes,
            recipient = prepared.recipient,
            respondBy = prepared.respondBy,
            documentUploaderId = documentUploaderId,
        )
    }
}

/**
 * Who a SYSTEM-issued (`actorMemberId == null`) notice's document should be attributed to -- the
 * ADMIN who most recently acknowledged [network.lapis.cloud.server.rpc.DunningComplianceDisclaimer]
 * (the person who turned dunning on in the first place). Shared by [issueDunningNotice]'s own Phase
 * 2 AND [DunningPoller]'s self-healing Phase C -- Phase C used to fall back to the DEBTOR
 * (`row[DunningNoticeTable.createdBy] ?: memberId`) instead, which directly contradicted this exact
 * attribution rule and, on a crash-then-heal path, mis-attributed an `ADMIN_ONLY` dunning-notice PDF
 * to the gemahnte member. See the security review finding this fixes. MUST be called inside an open
 * `transaction {}`.
 */
internal fun lastComplianceAcknowledgerMemberId(): Uuid =
    DunningComplianceAcknowledgmentTable
        .selectAll()
        .orderBy(DunningComplianceAcknowledgmentTable.acknowledgedAt, SortOrder.DESC)
        .limit(1)
        .singleOrNull()
        ?.get(DunningComplianceAcknowledgmentTable.acknowledgedByMemberId)
        ?: error(
            "No dunning_compliance_acknowledgment row found to attribute a system-issued notice's " +
                "document to -- dunning cannot be enabled without one, this should be unreachable.",
        )

/**
 * Archives [pdfBytes] for [noticeId] and links `document_id` -- the shared "Phase 3" both
 * [issueDunningNotice]'s callers and [DunningPoller]'s self-healing Phase C use. Idempotent by
 * construction: a re-run for a notice that already has a `document_id` simply overwrites it with a
 * fresh archive (accepted, same trade-off `SepaService.generateBatchFile`'s own KDoc documents for
 * an orphaned-document edge case).
 */
internal fun archiveDunningNoticePdf(
    storageRoot: File,
    noticeId: Uuid,
    pdfBytes: ByteArray,
    levelName: String,
    uploadedBy: Uuid,
) {
    val documentId =
        archiveGeneratedPdf(
            storageRoot = storageRoot,
            folderName = "Mahnungen",
            fileName = "mahnung-$noticeId.pdf",
            title = "$levelName ($noticeId)",
            bytes = pdfBytes,
            uploadedBy = uploadedBy,
            accessLevel = DocumentAccessLevel.ADMIN_ONLY,
        )
    transaction {
        DunningNoticeTable.update({ DunningNoticeTable.id eq noticeId }) {
            it[DunningNoticeTable.documentId] = documentId
        }
    }
}

/**
 * The one place "what is the CURRENT dunning cycle for this contribution's notices" is computed --
 * shared by [issueDunningNotice] and `network.lapis.cloud.server.rpc.DunningService.resetDunning`.
 * The highest `cycle_number` among all notices (any status), UNLESS every notice in that highest
 * cycle is `CANCELLED` -- in which case the cycle has been fully reset and the effective current
 * cycle is one higher. This is what lets [issueDunningNotice] resume at level 1 in a FRESH cycle
 * number after `resetDunning`, rather than colliding with the just-cancelled slot's still-occupied
 * `uq_dunning_notice_slot` unique index entry (contribution_id, cycle_number, level_number).
 */
internal fun List<ResultRow>.currentCycleNumber(): Int {
    val maxCycle = maxOfOrNull { it[DunningNoticeTable.cycleNumber] } ?: return 1
    val anyLiveInMaxCycle =
        any {
            it[DunningNoticeTable.cycleNumber] == maxCycle &&
                it[DunningNoticeTable.status] != DunningNoticeStatus.CANCELLED
        }
    return if (anyLiveInMaxCycle) maxCycle else maxCycle + 1
}

/**
 * The date grace-day escalation windows are measured FROM. If a notice is still live in the
 * current cycle ([lastLiveNotice] non-`null`), that notice's own `issuedAt` date, exactly as
 * before. Otherwise -- a brand-new or just-reset/-cancelled cycle with nothing live in it yet --
 * NOT simply [dueDate], but the LATER of [dueDate] and the most recent `cancelled_at` across
 * [allNoticesForContribution] (any cycle, any level).
 *
 * Security review MAJOR finding (round 2): [DunningService.resetDunning]/[DunningService
 * .cancelDunningNotice] are themselves rate-limited (`requireWithinRate`, 10/member/minute), but
 * that limiter bounds how often a TREASURER can CALL reset/cancel, not how soon the escalation
 * ladder is allowed to fire again afterwards. Falling back to the original, already-in-the-past
 * [dueDate] once a cycle is wiped meant the very next [DunningPoller] tick (as little as
 * `MIN_POLL_INTERVAL_SECONDS` = 60s later, see [DunningConfig]) found `dueOn <= today` immediately
 * and re-issued -- and, with postal dispatch enabled, re-MAILED -- a fresh level-1 notice with ZERO
 * elapsed grace period. A single reset per poll interval was already enough to harass one member
 * indefinitely, an order of magnitude below the 10/minute call budget the earlier (round-1) fix
 * added and which therefore never actually bound this. Anchoring the reference date on the
 * cancellation event instead forces the FULL configured `graceDays` to elapse again after every
 * reset/cancel -- a per-CONTRIBUTION cooldown, independent of and in addition to the per-ACTOR call
 * rate limit. `maxOf(dueDate, ...)` rather than the cancellation date alone: a contribution that was
 * never dunned before its (still-future) `dueDate` must not have its very first escalation window
 * pulled forward by an unrelated past cancellation on some other, already-resolved cycle. See the
 * security review finding this fixes.
 */
internal fun dunningReferenceDate(
    allNoticesForContribution: List<ResultRow>,
    lastLiveNotice: ResultRow?,
    dueDate: LocalDate,
): LocalDate {
    if (lastLiveNotice != null) return lastLiveNotice[DunningNoticeTable.issuedAt].date
    val mostRecentCancellation = allNoticesForContribution.mapNotNull { it[DunningNoticeTable.cancelledAt]?.date }.maxOrNull()
    return if (mostRecentCancellation != null) maxOf(dueDate, mostRecentCancellation) else dueDate
}

/**
 * Whether at least one notice has been ACTUALLY DELIVERED (`status == ISSUED`) in [cycleNumber] --
 * the correct condition for the dunning-fee guard (see [issueDunningNotice]'s own
 * `effectiveFeeAmount` comment and `registerDunningRoutes`'s `preview.pdf` mirror).
 *
 * Security review LOW finding: the guard used to test "any LIVE (non-`CANCELLED`) notice in the
 * cycle" -- [DunningNoticeStatus.SKIPPED] counted as live too. [DunningService.skipDunningLevel]
 * creates a `SKIPPED` notice with `feeAmount = null`, NO PDF, NO archival, NO postal dispatch -- the
 * member receives literally nothing for it. Treating a `SKIPPED` row as "a notice has already gone
 * out" let a treasurer skip level 1 and have the member's very FIRST actually-received letter (level
 * 2) carry level 2's fee, contradicting `DunningComplianceDisclaimer.TEXT`'s structural promise that
 * a fee is never charged on the first notice a member actually gets. Filtering on `ISSUED`
 * specifically (not `!= CANCELLED`) fixes that; the *level-selection* logic (which level counts as
 * "already used", i.e. `lastLevelNumber` in [issueDunningNotice]/`preview.pdf`) is UNCHANGED and
 * still treats `SKIPPED` as used -- only which notice counts as "the first one the member actually
 * received" changes. See the security review finding this fixes.
 */
internal fun List<ResultRow>.hasDeliveredNoticeInCycle(cycleNumber: Int): Boolean =
    any { it[DunningNoticeTable.cycleNumber] == cycleNumber && it[DunningNoticeTable.status] == DunningNoticeStatus.ISSUED }

/**
 * Explicit join, not `ContributionTable innerJoin MemberTable innerJoin MembershipTierTable`: both
 * `ContributionTable.membershipTierId` and `MemberTable.membershipTierId` reference
 * `MembershipTierTable.id`, so Exposed's implicit FK-based join resolution can't tell which path to
 * use and throws `IllegalStateException`. Mirrors `ContributionService.contributionJoin` exactly.
 */
private fun contributionJoin() =
    ContributionTable
        .innerJoin(MemberTable)
        .join(MembershipTierTable, JoinType.INNER, ContributionTable.membershipTierId, MembershipTierTable.id)

private fun ResultRow.toContributionDtoForDunning(): ContributionDto =
    ContributionDto(
        id = this[ContributionTable.id].toString(),
        memberId = this[ContributionTable.memberId].toString(),
        memberDisplayName = this[MemberTable.displayName],
        membershipTierId = this[ContributionTable.membershipTierId].toString(),
        membershipTierName = this[MembershipTierTable.name],
        periodStart = this[ContributionTable.periodStart],
        periodEnd = this[ContributionTable.periodEnd],
        amountDue = this[ContributionTable.amountDue],
        status = this[ContributionTable.status],
        paidAt = this[ContributionTable.paidAt],
        paidAmount = this[ContributionTable.paidAmount],
        note = this[ContributionTable.note],
        createdAt = this[ContributionTable.createdAt],
        dueDate = this[ContributionTable.dueDate],
        paymentMethod = this[ContributionTable.paymentMethod],
    )
