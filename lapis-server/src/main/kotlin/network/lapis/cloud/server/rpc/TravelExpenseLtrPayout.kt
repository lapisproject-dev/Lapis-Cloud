package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.TravelExpenseLtrPayoutTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.economy.oracle.PriceOracleSnapshotStore
import network.lapis.cloud.server.economy.oracle.REFERENCE_DATE_ZONE
import network.lapis.cloud.server.economy.oracle.ReferenceRateConversion
import network.lapis.cloud.server.economy.oracle.ReferenceRateResolution
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.PriceOracleConfigDto
import network.lapis.cloud.shared.domain.ReferenceDateFallback
import network.lapis.cloud.shared.domain.ReferenceRateOutcome
import network.lapis.cloud.shared.domain.ReferenceRateResolutionDto
import network.lapis.cloud.shared.domain.ReferenceRateSourceDto
import network.lapis.cloud.shared.domain.TravelExpenseLtrBlocker
import network.lapis.cloud.shared.domain.TravelExpenseLtrPayoutDto
import network.lapis.cloud.shared.domain.TravelExpenseLtrPreviewDto
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.TravelExpenseSepaReference
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.security.MessageDigest
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Wire codes of this file (the client maps them in `TravelExpenseLabels`). */
internal const val LTR_PAYOUT_DISABLED = "LTR_PAYOUT_DISABLED"
internal const val LTR_RATE_OR_AMOUNT_CHANGED = "rate_or_amount_changed"
internal const val LTR_BALANCE_INSUFFICIENT = "LTR_BALANCE_INSUFFICIENT"

/**
 * V1.9.325 -- credits a travel expense report that is refunded in LTR, and reverses such a credit. **The ONLY writer of `travel_expense_ltr_payout`
 * and the only place that names [LtrLedgerEntryType.TRAVEL_REFUND] / [LtrLedgerEntryType.TRAVEL_REFUND_REVERSE] (tripwire).**
 *
 * The LTR is an **issuance by the organisation** (one credit row, no debit, no organisation account), like a tender payout or a meeting
 * recognition -- never a `MINT`, and nothing here feeds any merit or weighting. The amount is `(total - advance) / (anchorUnitsPerLtr * gold price)`,
 * rounded to 0.01 LTR (HALF_UP), at the closing gold rate of the day the trip ended (`travelTo`, moved back to the last LBMA trading day). The rate
 * logic is the one of the donation conversion, reached through [ReferenceRateConversion] only.
 *
 * Roles TREASURER/BOARD/ADMIN; the subject **and** the requester of a report may never credit or reverse it (four eyes), and the member who confirmed
 * a manual rate must not be the recipient. Lock order (deadlock freedom): report row `FOR UPDATE` first, then (reversal only) the member row.
 * No network access anywhere in this file.
 */
internal object TravelExpenseLtrPayout {
    val ROLES: Array<AccountRole> = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)
    const val MAX_CANDIDATES = 200
    private const val SETTLEMENT_LABEL = "Reisekostenerstattung"

    /** Everything that is the same for every report of one request, read once. */
    internal data class Context(
        val settings: TravelExpenseSettings,
        val config: PriceOracleConfigDto?,
        val accountsOk: Boolean,
        val today: LocalDate,
    )

    internal fun loadContext(): Context =
        Context(
            settings = TravelExpenseSettingsStore.load(),
            config = loadPriceOracleConfigOrNull(),
            accountsOk = TravelExpenseLtrPostingBridge.loadAccountsOrNull() != null,
            today = ServerClock.todayIn(REFERENCE_DATE_ZONE),
        )

    private fun payableCents(row: ResultRow): Long =
        row[TravelExpenseReportTable.totalAmount].toCents() - row[TravelExpenseReportTable.advanceAmount].toCents()

    private fun isEligible(memberId: Uuid): Boolean =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .singleOrNull()
            ?.get(MemberTable.status)
            ?.let { it in MemberStatusSets.LTR_ELIGIBLE } ?: false

    /** Precedence: see the plan; the first applicable blocker wins. `null` == may be credited (the rate itself is checked by the preview). */
    internal fun blockerOf(
        report: ResultRow,
        actor: CurrentMember?,
        ctx: Context,
        hasActivePayout: Boolean,
    ): TravelExpenseLtrBlocker? {
        if (report[TravelExpenseReportTable.status] != TravelExpenseReportStatus.EXECUTED ||
            report[TravelExpenseReportTable.postedJournalEntryId] == null
        ) {
            return TravelExpenseLtrBlocker.NOT_BOOKED
        }
        if (report[TravelExpenseReportTable.payoutChoice] != TravelExpensePayoutChoice.LTR) return TravelExpenseLtrBlocker.NOT_LTR
        if (payableCents(report) <= 0) return TravelExpenseLtrBlocker.NOTHING_PAYABLE
        if (!ctx.settings.ltrPayoutEnabled) return TravelExpenseLtrBlocker.DISABLED
        if (hasActivePayout) return TravelExpenseLtrBlocker.ALREADY_PAID
        if (actor != null &&
            (
                actor.memberId == report[TravelExpenseReportTable.subjectMemberId] ||
                    actor.memberId == report[TravelExpenseReportTable.requestedBy]
            )
        ) {
            return TravelExpenseLtrBlocker.OWN_REPORT
        }
        if (!ctx.accountsOk) return TravelExpenseLtrBlocker.ACCOUNTS_MISSING
        val config = ctx.config
        if (config == null || config.anchorAsset != AnchorAsset.GOLD_XAU) return TravelExpenseLtrBlocker.ORACLE_NOT_READY
        if (config.donationCurrency.uppercase() != "EUR") return TravelExpenseLtrBlocker.ORACLE_NOT_EUR
        if (report[TravelExpenseReportTable.travelTo] >= ctx.today) return TravelExpenseLtrBlocker.REFERENCE_DATE_NOT_PAST
        if (!isEligible(report[TravelExpenseReportTable.subjectMemberId])) return TravelExpenseLtrBlocker.RECIPIENT_NOT_ELIGIBLE
        return null
    }

    private data class Computation(
        val blocker: TravelExpenseLtrBlocker?,
        val resolution: ReferenceRateResolution?,
        val ltr: BigDecimal?,
        val fingerprint: String?,
        val eurCents: Long,
        val nextAttemptNo: Int,
        val config: PriceOracleConfigDto?,
    )

    private fun attemptsOf(reportId: Uuid): List<ResultRow> =
        TravelExpenseLtrPayoutTable.selectAll().where { TravelExpenseLtrPayoutTable.reportId eq reportId }.toList()

    private fun compute(
        report: ResultRow,
        actor: CurrentMember?,
        ctx: Context,
    ): Computation {
        val reportId = report[TravelExpenseReportTable.id]
        val attempts = attemptsOf(reportId)
        val hasActive = attempts.any { it[TravelExpenseLtrPayoutTable.reversedAt] == null }
        val nextAttempt = (attempts.maxOfOrNull { it[TravelExpenseLtrPayoutTable.attemptNo] } ?: 0) + 1
        val eurCents = payableCents(report)
        var blocker = blockerOf(report = report, actor = actor, ctx = ctx, hasActivePayout = hasActive)
        val config = ctx.config
        val canResolve =
            config != null &&
                config.anchorAsset == AnchorAsset.GOLD_XAU &&
                config.donationCurrency.uppercase() == "EUR" &&
                report[TravelExpenseReportTable.travelTo] < ctx.today &&
                report[TravelExpenseReportTable.status] == TravelExpenseReportStatus.EXECUTED
        val resolution =
            if (canResolve) {
                try {
                    ReferenceRateConversion.resolve(
                        config = requireNotNull(config),
                        referenceDate = report[TravelExpenseReportTable.travelTo],
                    )
                } catch (_: ConflictException) {
                    null
                }
            } else {
                null
            }
        val price = resolution?.price
        val ltr =
            if (resolution != null && resolution.outcome == ReferenceRateOutcome.RESOLVED && price != null && eurCents > 0) {
                ReferenceRateConversion.ltrFor(
                    amount = eurCents.centsToAmount(),
                    anchorUnitsPerLtr = requireNotNull(config).anchorUnitsPerLtr,
                    price = price,
                )
            } else {
                null
            }
        if (blocker == null) {
            blocker =
                when {
                    resolution == null || resolution.outcome != ReferenceRateOutcome.RESOLVED || price == null || ltr == null ->
                        TravelExpenseLtrBlocker.RATE_MISSING
                    resolution.manualRate?.confirmedBy == report[TravelExpenseReportTable.subjectMemberId] ->
                        TravelExpenseLtrBlocker.SELF_CONFIRMED_RATE
                    ReferenceRateConversion.isBelowMinimum(ltr) -> TravelExpenseLtrBlocker.AMOUNT_TOO_SMALL
                    else -> null
                }
        }
        val fingerprint =
            if (blocker == null && resolution != null && ltr != null && price != null) {
                fingerprintOf(
                    reportId = reportId,
                    eurCents = eurCents,
                    refId = requireNotNull(refIdOf(resolution)),
                    price = price,
                    anchorUnitsPerLtr = requireNotNull(config).anchorUnitsPerLtr,
                    ltr = ltr,
                    attemptNo = nextAttempt,
                )
            } else {
                null
            }
        return Computation(
            blocker = blocker,
            resolution = resolution,
            ltr = ltr,
            fingerprint = fingerprint,
            eurCents = eurCents,
            nextAttemptNo = nextAttempt,
            config = config,
        )
    }

    private fun refIdOf(resolution: ReferenceRateResolution): Uuid? = resolution.snapshot?.id ?: resolution.manualRate?.id

    /** SHA-256 over everything the credit depends on; a NEW [MessageDigest] per call (instances are not thread-safe). */
    internal fun fingerprintOf(
        reportId: Uuid,
        eurCents: Long,
        refId: Uuid,
        price: BigDecimal,
        anchorUnitsPerLtr: BigDecimal,
        ltr: BigDecimal,
        attemptNo: Int,
    ): String {
        val text =
            listOf(
                reportId.toString(),
                eurCents.toString(),
                refId.toString(),
                price.toPlainString(),
                anchorUnitsPerLtr.toPlainString(),
                ltr.toPlainString(),
                attemptNo.toString(),
            ).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun loadReport(
        reportId: Uuid,
        lock: Boolean,
    ): ResultRow {
        val query = TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq reportId }
        return (if (lock) query.forUpdate() else query).singleOrNull() ?: throw NotFoundException("Travel expense report not found")
    }

    private fun placeholderResolution(travelTo: LocalDate): ReferenceRateResolutionDto =
        ReferenceRateResolutionDto(
            referenceDate = travelTo,
            effectiveDate = travelTo,
            fallbackReason = ReferenceDateFallback.NONE,
            outcome = ReferenceRateOutcome.NO_DATA,
        )

    private fun toPreviewDto(
        report: ResultRow,
        c: Computation,
    ): TravelExpenseLtrPreviewDto =
        TravelExpenseLtrPreviewDto(
            reportId = report[TravelExpenseReportTable.id].toString(),
            eurCents = c.eurCents,
            referenceDate = report[TravelExpenseReportTable.travelTo],
            resolution =
                c.resolution?.let {
                    ReferenceRateConversion.toResolutionDto(resolution = it, recipient = report[TravelExpenseReportTable.subjectMemberId])
                } ?: placeholderResolution(report[TravelExpenseReportTable.travelTo]),
            ltrAmount = c.ltr,
            fingerprint = c.fingerprint,
            blocker = c.blocker,
            requiresRepeatReason = c.nextAttemptNo > 1,
        )

    // ── reads ───────────────────────────────────────────────────────────────────────────────

    /** Read only, one transaction, no network. */
    fun preview(
        reportId: Uuid,
        actor: CurrentMember,
    ): TravelExpenseLtrPreviewDto =
        transaction {
            val report = loadReport(reportId = reportId, lock = false)
            toPreviewDto(report = report, c = compute(report = report, actor = actor, ctx = loadContext()))
        }

    /** EXECUTED LTR reports without an active credit, oldest first, each with its blocker (`null` == ready). */
    fun candidates(actor: CurrentMember): List<Pair<ResultRow, TravelExpenseLtrBlocker?>> =
        transaction {
            val ctx = loadContext()
            val rows =
                TravelExpenseReportTable
                    .selectAll()
                    .where {
                        (TravelExpenseReportTable.status eq TravelExpenseReportStatus.EXECUTED) and
                            (TravelExpenseReportTable.payoutChoice eq TravelExpensePayoutChoice.LTR)
                    }.orderBy(TravelExpenseReportTable.executedAt to SortOrder.ASC, TravelExpenseReportTable.id to SortOrder.ASC)
                    .toList()
            val activeIds =
                if (rows.isEmpty()) {
                    emptySet()
                } else {
                    TravelExpenseLtrPayoutTable
                        .selectAll()
                        .where {
                            (TravelExpenseLtrPayoutTable.reportId inList rows.map { it[TravelExpenseReportTable.id] }) and
                                TravelExpenseLtrPayoutTable.reversedAt.isNull()
                        }.map { it[TravelExpenseLtrPayoutTable.reportId] }
                        .toSet()
                }
            rows
                .filter { it[TravelExpenseReportTable.id] !in activeIds }
                .take(MAX_CANDIDATES)
                .map { row ->
                    row to
                        (
                            blockerOf(report = row, actor = actor, ctx = ctx, hasActivePayout = false)
                                ?: compute(report = row, actor = actor, ctx = ctx).blocker
                        )
                }
        }

    /** The newest payout row of [reportId] as a DTO plus how many were reversed; `null` when the report has none. Inside an open transaction. */
    fun latestFor(reportId: Uuid): Pair<TravelExpenseLtrPayoutDto?, Int> {
        val rows = attemptsOf(reportId)
        if (rows.isEmpty()) return null to 0
        val newest = rows.maxBy { it[TravelExpenseLtrPayoutTable.attemptNo] }
        return toDto(newest) to rows.count { it[TravelExpenseLtrPayoutTable.reversedAt] != null }
    }

    private fun toDto(row: ResultRow): TravelExpenseLtrPayoutDto {
        val names =
            listOfNotNull(
                row[TravelExpenseLtrPayoutTable.triggeredBy],
                row[TravelExpenseLtrPayoutTable.rateConfirmedBy],
                row[TravelExpenseLtrPayoutTable.reversedBy],
            ).let { ids ->
                MemberTable
                    .selectAll()
                    .where { MemberTable.id inList ids }
                    .associate { it[MemberTable.id] to it[MemberTable.displayName] }
            }
        val snapshotId = row[TravelExpenseLtrPayoutTable.snapshotId]
        return TravelExpenseLtrPayoutDto(
            id = row[TravelExpenseLtrPayoutTable.id].toString(),
            attemptNo = row[TravelExpenseLtrPayoutTable.attemptNo],
            eurCents = row[TravelExpenseLtrPayoutTable.eurAmount].toCents(),
            ltrAmount = row[TravelExpenseLtrPayoutTable.ltrAmount],
            referenceDate = row[TravelExpenseLtrPayoutTable.referenceDate],
            effectiveDate = row[TravelExpenseLtrPayoutTable.effectiveDate],
            fallbackReason = row[TravelExpenseLtrPayoutTable.fallbackReason],
            rateBasis = row[TravelExpenseLtrPayoutTable.rateBasis],
            price = row[TravelExpenseLtrPayoutTable.anchorPrice],
            anchorAsset = row[TravelExpenseLtrPayoutTable.anchorAsset],
            currency = row[TravelExpenseLtrPayoutTable.currency],
            anchorUnitsPerLtr = row[TravelExpenseLtrPayoutTable.anchorUnitsPerLtr],
            sources =
                if (snapshotId == null) {
                    emptyList()
                } else {
                    PriceOracleSnapshotStore.sourcesOf(snapshotId).map {
                        ReferenceRateSourceDto(
                            sourceId = it.sourceId,
                            rawPrice = it.rawPrice,
                            observedAt = it.observedAt,
                            included = it.included,
                        )
                    }
                },
            triggeredAt = row[TravelExpenseLtrPayoutTable.triggeredAt],
            triggeredByDisplayName = names[row[TravelExpenseLtrPayoutTable.triggeredBy]] ?: "",
            rateConfirmedByDisplayName = row[TravelExpenseLtrPayoutTable.rateConfirmedBy]?.let { names[it] },
            ledgerEntryId = row[TravelExpenseLtrPayoutTable.ltrLedgerEntryId].toString(),
            reversedAt = row[TravelExpenseLtrPayoutTable.reversedAt],
            reversedByDisplayName = row[TravelExpenseLtrPayoutTable.reversedBy]?.let { names[it] },
        )
    }

    // ── manual rate ─────────────────────────────────────────────────────────────────────────

    /** Confirms a manual rate for the effective day of the trip end; never for the own report; returns the fresh preview. */
    fun confirmRate(
        reportId: Uuid,
        price: BigDecimal,
        evidenceText: String,
        acknowledgeDeviation: Boolean,
        actor: CurrentMember,
    ): TravelExpenseLtrPreviewDto {
        val now = DbClock.nowLocalDateTime()
        try {
            return transaction {
                val report = loadReport(reportId = reportId, lock = false)
                requireNotOwn(report = report, actor = actor)
                val ctx = loadContext()
                val blocker = blockerOf(report = report, actor = actor, ctx = ctx, hasActivePayout = false)
                if (blocker != null && blocker != TravelExpenseLtrBlocker.RECIPIENT_NOT_ELIGIBLE) throw ConflictException(blocker.name)
                ReferenceRateConversion.confirmManualRate(
                    config = requireNotNull(ctx.config),
                    referenceDate = report[TravelExpenseReportTable.travelTo],
                    price = price,
                    evidenceText = evidenceText,
                    acknowledgeDeviation = acknowledgeDeviation,
                    actor = actor,
                    now = now,
                )
                toPreviewDto(report = report, c = compute(report = report, actor = actor, ctx = ctx))
            }
        } catch (e: ExposedSQLException) {
            if (e.isUniqueViolation()) throw ConflictException("A rate for that day was just confirmed by someone else")
            throw e
        }
    }

    private fun requireNotOwn(
        report: ResultRow,
        actor: CurrentMember,
    ) {
        if (actor.memberId == report[TravelExpenseReportTable.subjectMemberId] ||
            actor.memberId == report[TravelExpenseReportTable.requestedBy]
        ) {
            throw ForbiddenException("Not allowed for the own report")
        }
    }

    private fun normalizedReason(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length < TravelExpenseSepaExport.MIN_REASON_LENGTH || trimmed.length > TravelExpenseSepaExport.MAX_REASON_LENGTH) {
            throw ConflictException("reason_length_invalid")
        }
        if (TravelExpenseSepaExport.containsIban(trimmed)) throw ConflictException("reason_contains_account_number")
        return trimmed
    }

    // ── credit ──────────────────────────────────────────────────────────────────────────────

    /** Credits the LTR in ONE transaction. Anything but a resolved rate that matches [fingerprint] credits nothing. */
    fun execute(
        reportId: Uuid,
        fingerprint: String,
        repeatReason: String?,
        actor: CurrentMember,
    ) {
        val now = DbClock.nowLocalDateTime()
        val bookingDate = OrganizationTimeZone.dateOf(now)
        transaction {
            // 1. the same row lock as the SEPA export
            val report = loadReport(reportId = reportId, lock = true)
            val subject = report[TravelExpenseReportTable.subjectMemberId]
            val ctx = loadContext()
            // 4. blockers (also covers: not booked, not LTR, disabled, already paid, own report, accounts, oracle, date, recipient)
            val c = compute(report = report, actor = actor, ctx = ctx)
            val blocker = c.blocker
            if (blocker == TravelExpenseLtrBlocker.OWN_REPORT || blocker == TravelExpenseLtrBlocker.SELF_CONFIRMED_RATE) {
                throw ForbiddenException(blocker.name)
            }
            if (blocker == TravelExpenseLtrBlocker.DISABLED) throw ConflictException(LTR_PAYOUT_DISABLED)
            if (blocker != null) throw ConflictException(blocker.name)
            val resolution = requireNotNull(c.resolution)
            val ltr = requireNotNull(c.ltr)
            val price = requireNotNull(resolution.price)
            val config = requireNotNull(c.config)
            if (!MessageDigest.isEqual(
                    fingerprint.toByteArray(Charsets.UTF_8),
                    requireNotNull(c.fingerprint).toByteArray(Charsets.UTF_8),
                )
            ) {
                throw ConflictException(LTR_RATE_OR_AMOUNT_CHANGED)
            }
            val reason =
                if (c.nextAttemptNo > 1) {
                    normalizedReason(repeatReason ?: throw ConflictException("reason_length_invalid"))
                } else {
                    if (!repeatReason.isNullOrBlank()) throw ConflictException("reason_not_allowed")
                    null
                }
            val eurAmount = c.eurCents.centsToAmount()

            val ledgerEntryId = Uuid.random()
            LtrLedgerEntryTable.insert {
                it[id] = ledgerEntryId
                it[memberId] = subject
                it[entryType] = LtrLedgerEntryType.TRAVEL_REFUND
                it[amountLtr] = ltr
                it[referenceType] = LtrLedgerReferenceType.TRAVEL_EXPENSE
                it[referenceId] = reportId
                it[note] = "$SETTLEMENT_LABEL ${TravelExpenseSepaReference.of(reportId.toString())}"
                it[createdBy] = actor.memberId
                it[createdAt] = now
            }
            val journalEntryId =
                TravelExpenseLtrPostingBridge.postSettlement(
                    reportId = reportId,
                    approvalJournalEntryId = requireNotNull(report[TravelExpenseReportTable.postedJournalEntryId]),
                    eurAmount = eurAmount,
                    on = bookingDate,
                    actorMemberId = actor.memberId,
                    actorRole = actor.role,
                )
            val snapshot = resolution.snapshot
            val manual = resolution.manualRate
            val payoutId = Uuid.random()
            try {
                withSavepoint(name = "travel_ltr_payout") {
                    TravelExpenseLtrPayoutTable.insert {
                        it[id] = payoutId
                        it[TravelExpenseLtrPayoutTable.reportId] = reportId
                        it[attemptNo] = c.nextAttemptNo
                        it[memberId] = subject
                        it[TravelExpenseLtrPayoutTable.eurAmount] = eurAmount
                        it[ltrAmount] = ltr
                        it[anchorAsset] = config.anchorAsset
                        it[currency] = config.donationCurrency.uppercase()
                        it[anchorPrice] = price
                        it[anchorUnitsPerLtr] = config.anchorUnitsPerLtr
                        it[rateBasis] = requireNotNull(resolution.basis)
                        it[referenceDate] = resolution.referenceDate
                        it[effectiveDate] = resolution.effectiveDate
                        it[fallbackReason] = resolution.fallbackReason
                        it[snapshotId] = snapshot?.id
                        it[referenceRateId] = manual?.id
                        it[quorumRequired] = resolution.quorumRequired
                        it[sourceCount] = snapshot?.sourceCount ?: 0
                        it[sourcesUsed] = snapshot?.sourcesUsed ?: "manual"
                        it[rateConfirmedBy] = manual?.confirmedBy
                        it[ltrLedgerEntryId] = ledgerEntryId
                        it[TravelExpenseLtrPayoutTable.journalEntryId] = journalEntryId
                        it[triggeredBy] = actor.memberId
                        it[triggeredAt] = now
                        it[TravelExpenseLtrPayoutTable.repeatReason] = reason
                        it[activeReportId] = reportId
                    }
                }
            } catch (e: ExposedSQLException) {
                if (e.isUniqueViolation()) throw ConflictException(TravelExpenseLtrBlocker.ALREADY_PAID.name)
                throw e
            }
            // Last: the audit entry. NEVER an amount, a rate or a reason text.
            AuditLogRecorder.record(
                actorMemberId = actor.memberId,
                actorRole = actor.role,
                entityType = AuditEntityType.TRAVEL_EXPENSE_LTR_PAYOUT,
                entityId = payoutId,
                action = AuditAction.CREATE,
                after =
                    buildJsonObject {
                        put("reportId", reportId.toString())
                        put("payoutId", payoutId.toString())
                        put("attemptNo", c.nextAttemptNo)
                        put("basis", resolution.basis!!.name)
                        put(if (snapshot != null) "snapshotId" else "referenceRateId", requireNotNull(refIdOf(resolution)).toString())
                        put("referenceDate", resolution.referenceDate.toString())
                        put("effectiveDate", resolution.effectiveDate.toString())
                        put("triggeredBy", actor.memberId.toString())
                        manual?.let { put("rateConfirmedBy", it.confirmedBy.toString()) }
                        put("repeatReasonLength", reason?.length ?: 0)
                    }.toString(),
                occurredAt = now,
            )
            logger.info { "travel expense LTR credit $payoutId booked for report $reportId (attempt ${c.nextAttemptNo})" }
        }
    }

    // ── reversal ────────────────────────────────────────────────────────────────────────────

    /** Reverses the active credit [payoutId] of [reportId]. Lock order: report row, then the member row. */
    fun reverse(
        reportId: Uuid,
        payoutId: Uuid,
        reason: String,
        actor: CurrentMember,
    ) {
        val now = DbClock.nowLocalDateTime()
        val bookingDate = OrganizationTimeZone.dateOf(now)
        val cleanReason = normalizedReason(reason)
        transaction {
            val report = loadReport(reportId = reportId, lock = true)
            requireNotOwn(report = report, actor = actor)
            val payout =
                TravelExpenseLtrPayoutTable
                    .selectAll()
                    .where { (TravelExpenseLtrPayoutTable.id eq payoutId) and (TravelExpenseLtrPayoutTable.reportId eq reportId) }
                    .singleOrNull() ?: throw NotFoundException("Travel expense report not found")
            if (payout[TravelExpenseLtrPayoutTable.reversedAt] != null) throw ConflictException("ALREADY_REVERSED")
            val member = payout[TravelExpenseLtrPayoutTable.memberId]
            val ltr = payout[TravelExpenseLtrPayoutTable.ltrAmount]
            val balances = LedgerBackedLtrBalanceProvider()
            balances.lockForDebit(member)
            if (balances.freeBalance(member) < ltr) throw ConflictException(LTR_BALANCE_INSUFFICIENT)

            val ledgerEntryId = Uuid.random()
            LtrLedgerEntryTable.insert {
                it[id] = ledgerEntryId
                it[memberId] = member
                it[entryType] = LtrLedgerEntryType.TRAVEL_REFUND_REVERSE
                it[amountLtr] = ltr.negate()
                it[referenceType] = LtrLedgerReferenceType.TRAVEL_EXPENSE
                it[referenceId] = reportId
                it[note] = "Storno $SETTLEMENT_LABEL ${TravelExpenseSepaReference.of(reportId.toString())}"
                it[createdBy] = actor.memberId
                it[createdAt] = now
            }
            val journalEntryId =
                TravelExpenseLtrPostingBridge.postReversal(
                    reportId = reportId,
                    originalJournalEntryId = payout[TravelExpenseLtrPayoutTable.journalEntryId],
                    on = bookingDate,
                    actorMemberId = actor.memberId,
                    actorRole = actor.role,
                )
            val changed =
                TravelExpenseLtrPayoutTable.update({
                    (TravelExpenseLtrPayoutTable.id eq payoutId) and TravelExpenseLtrPayoutTable.reversedAt.isNull()
                }) {
                    it[reversedAt] = now
                    it[reversedBy] = actor.memberId
                    it[reversalReason] = cleanReason
                    it[reversalLedgerEntryId] = ledgerEntryId
                    it[reversalJournalEntryId] = journalEntryId
                    it[activeReportId] = null
                }
            if (changed != 1) throw ConflictException("ALREADY_REVERSED")
            AuditLogRecorder.record(
                actorMemberId = actor.memberId,
                actorRole = actor.role,
                entityType = AuditEntityType.TRAVEL_EXPENSE_LTR_PAYOUT,
                entityId = payoutId,
                action = AuditAction.UPDATE,
                after =
                    buildJsonObject {
                        put("reportId", reportId.toString())
                        put("payoutId", payoutId.toString())
                        put("reversed", true)
                        put("reversedBy", actor.memberId.toString())
                        put("reasonLength", cleanReason.length)
                    }.toString(),
                occurredAt = now,
            )
            logger.info { "travel expense LTR credit $payoutId of report $reportId reversed" }
        }
    }

    // ── switch ──────────────────────────────────────────────────────────────────────────────

    /** Locks the single organization-settings row so a concurrent 'enable' and 'save accounts' serialize (no enabled=true with null accounts). */
    fun lockSettingsRow() {
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .forUpdate()
            .toList()
    }

    /** The one validation of the two LTR accounts, used by [setEnabled] and by the settings save while the switch is on. */
    fun requireValidAccounts(
        payableIdOrNull: Uuid?,
        settlementIdOrNull: Uuid?,
    ) {
        val payableId = payableIdOrNull ?: throw ConflictException("LTR_ENABLE_ACCOUNTS_MISSING")
        val settlementId = settlementIdOrNull ?: throw ConflictException("LTR_ENABLE_ACCOUNTS_MISSING")
        if (payableId == settlementId) throw ConflictException("LTR_ENABLE_ACCOUNTS_SAME")
        val payable =
            LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq payableId }.singleOrNull()
                ?: throw ConflictException("LTR_ENABLE_ACCOUNTS_MISSING")
        val settlement =
            LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq settlementId }.singleOrNull()
                ?: throw ConflictException("LTR_ENABLE_ACCOUNTS_MISSING")
        if (!payable[LedgerAccountTable.active] ||
            !settlement[LedgerAccountTable.active]
        ) {
            throw ConflictException("LTR_ENABLE_ACCOUNT_INACTIVE")
        }
        if (payable[LedgerAccountTable.type] !=
            LedgerAccountType.LIABILITY
        ) {
            throw ConflictException("LTR_ENABLE_PAYABLE_NOT_LIABILITY")
        }
        if (settlement[LedgerAccountTable.type] !in TravelExpenseLtrPostingBridge.SETTLEMENT_ACCOUNT_TYPES) {
            throw ConflictException("LTR_ENABLE_SETTLEMENT_TYPE")
        }
        if (CashRegisterGuard.loadCashRegisterAccountIds(listOf(payableId, settlementId)).isNotEmpty()) {
            throw ConflictException("LTR_ENABLE_CASH_ACCOUNT")
        }
    }

    /** ADMIN only (checked by the caller). Enabling validates the whole setup; disabling always works. */
    fun setEnabled(
        enabled: Boolean,
        actor: CurrentMember,
    ) {
        transaction {
            lockSettingsRow()
            val before = TravelExpenseSettingsStore.load()
            if (enabled) {
                val config = loadPriceOracleConfigOrNull()
                if (config == null || config.anchorAsset != AnchorAsset.GOLD_XAU) throw ConflictException("LTR_ENABLE_ORACLE_NOT_GOLD")
                if (config.donationCurrency.uppercase() != "EUR") throw ConflictException("LTR_ENABLE_ORACLE_NOT_EUR")
                requireValidAccounts(payableIdOrNull = before.ltrPayableAccountId, settlementIdOrNull = before.ltrSettlementAccountId)
            }
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[travelLtrPayoutEnabled] = enabled
            }
            if (before.ltrPayoutEnabled != enabled) {
                AuditLogRecorder.record(
                    actorMemberId = actor.memberId,
                    actorRole = actor.role,
                    entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    after = buildJsonObject { put("travelLtrPayoutEnabled", enabled) }.toString(),
                )
            }
        }
    }
}
