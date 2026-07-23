package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.CrowdfundingDistributionTable
import network.lapis.cloud.server.db.generated.CrowdfundingProjectTable
import network.lapis.cloud.server.db.generated.CrowdfundingReactionTable
import network.lapis.cloud.server.db.generated.CrowdfundingSubmissionGateTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.CrowdfundingDistributionDto
import network.lapis.cloud.shared.domain.CrowdfundingProjectDto
import network.lapis.cloud.shared.domain.CrowdfundingProjectInput
import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.CrowdfundingReactionDto
import network.lapis.cloud.shared.domain.CrowdfundingReactionValue
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ICrowdfundingService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val CF_BOARD_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)
private val CF_TREASURY_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

/** "Aktuelle Annahme, vor Produktiveinsatz zu verifizieren" -- same disclaimer class as [CrowdfundingWeightDecay]'s constants. */
private val MIN_INITIAL_WEIGHT_LTR: BigDecimal = BigDecimal("1.00")

/**
 * Fixed per-payer platform-operation deduction, applied once per DISTINCT paying member before
 * the remaining pool is split -- "aktuelle Annahme, vor Produktiveinsatz zu verifizieren", same
 * disclaimer class as [CrowdfundingWeightDecay]'s constants.
 */
private val MIN_PLATFORM_CONTRIBUTION_EUR: BigDecimal = BigDecimal("2.00")

/** Fixed sentinel id of the single [CrowdfundingSubmissionGateTable] row, seeded directly in `V1__baseline.sql`. */
private val CROWDFUNDING_SUBMISSION_GATE_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000f4")

/**
 * Internes Crowdfunding (V0.6.1) -- see [ICrowdfundingService] KDoc and `17-crowdfunding.kuml.kts`
 * file header for the full fachlich model. [ltrBalanceProvider] defaults to
 * [LedgerBackedLtrBalanceProvider], same seam [GovernanceService] uses.
 */
class CrowdfundingService(
    private val call: ApplicationCall,
    private val ltrBalanceProvider: LtrBalanceProvider = LedgerBackedLtrBalanceProvider(),
) : ICrowdfundingService {
    /**
     * Serializes concurrent submissions via a `SELECT ... FOR UPDATE` on the single
     * [CrowdfundingSubmissionGateTable] row -- taken FIRST (before computing the decaying
     * top-weight threshold), not last, since the entire point is to make "read the current top
     * weight, then decide whether this stake clears it" atomic across concurrent callers. This is
     * a genuinely different lock-ordering role than [network.lapis.cloud.server.audit
     * .AuditLogRecorder]'s own singleton-row lock (which that object's KDoc requires to be taken
     * LAST) -- no [network.lapis.cloud.server.audit.AuditLogRecorder] call happens in this
     * transaction at all (Crowdfunding is not one of the four entity types
     * `14-audit-log.kuml.kts` covers, and extending that scope is out of bounds for this wave),
     * so there is no second lock this could deadlock against.
     */
    override suspend fun submitProject(input: CrowdfundingProjectInput): CrowdfundingProjectDto {
        val current = resolveCurrentMember(call)
        val weight = input.initialWeightLtr
        if (weight.scale() > 2) throw ConflictException("initialWeightLtr must have at most 2 decimal places")
        val normalizedWeight = weight.setScale(2, RoundingMode.UNNECESSARY)
        val now = nowLocalDateTime()
        return transaction {
            requireActiveMembership(current.memberId)
            CrowdfundingSubmissionGateTable
                .selectAll()
                .where { CrowdfundingSubmissionGateTable.id eq CROWDFUNDING_SUBMISSION_GATE_ID }
                .forUpdate()
                .singleOrNull()
                ?: error("CrowdfundingSubmissionGate row $CROWDFUNDING_SUBMISSION_GATE_ID not found -- baseline seed missing?")

            val topWeight =
                CrowdfundingProjectTable
                    .selectAll()
                    .where { CrowdfundingProjectTable.status neq CrowdfundingProjectStatus.REJECTED }
                    .maxOfOrNull { row ->
                        CrowdfundingWeightDecay.currentWeight(
                            row[CrowdfundingProjectTable.initialWeightLtr],
                            row[CrowdfundingProjectTable.submittedAt],
                            now,
                        )
                    } ?: BigDecimal.ZERO.setScale(2)
            val hurdle = maxOf(MIN_INITIAL_WEIGHT_LTR, topWeight)
            if (normalizedWeight < hurdle) {
                throw ConflictException("initialWeightLtr $normalizedWeight is below the current entry hurdle $hurdle")
            }

            // Serializes this debit-causing read-then-write against every other LTR-debiting call
            // for this same member (e.g. a concurrent GovernanceService.castVoteBallot stake, or a
            // second concurrent submitProject) -- see LtrBalanceProvider.lockForDebit KDoc for the
            // TOCTOU this closes. Taken AFTER the submission-gate lock above (consistent lock
            // ordering: gate row, then member row -- no other call site ever locks the gate row, so
            // this ordering cannot deadlock against castVoteBallot's member-only lock).
            ltrBalanceProvider.lockForDebit(current.memberId)
            val freeBalance = ltrBalanceProvider.freeBalance(current.memberId)
            if (normalizedWeight > freeBalance) {
                throw ConflictException("initialWeightLtr $normalizedWeight exceeds free LTR balance $freeBalance")
            }

            val newProjectId = Uuid.random()
            CrowdfundingProjectTable.insert {
                it[CrowdfundingProjectTable.id] = newProjectId
                it[title] = input.title
                it[description] = input.description
                it[submitterMemberId] = current.memberId
                it[initialWeightLtr] = normalizedWeight
                it[status] = CrowdfundingProjectStatus.PENDING
                it[rejectionReason] = null
                it[reviewedBy] = null
                it[reviewedAt] = null
                it[submittedAt] = now
            }
            LtrLedgerEntryTable.insert {
                it[LtrLedgerEntryTable.id] = Uuid.random()
                it[memberId] = current.memberId
                it[entryType] = LtrLedgerEntryType.PROJECT_STAKE
                it[amountLtr] = normalizedWeight.negate()
                it[referenceType] = LtrLedgerReferenceType.CROWDFUNDING_PROJECT
                it[referenceId] = newProjectId
                it[note] = "Sichtbarkeits-Gewicht fuer Crowdfunding-Projekt '${input.title}'"
                it[createdBy] = null
                it[createdAt] = now
            }
            loadProject(newProjectId, now)
        }
    }

    override suspend fun listProjects(statusFilter: CrowdfundingProjectStatus?): List<CrowdfundingProjectDto> {
        resolveCurrentMember(call)
        val now = nowLocalDateTime()
        return transaction {
            val condition: Op<Boolean>? = statusFilter?.let { CrowdfundingProjectTable.status eq it }
            val query = if (condition != null) projectJoin().selectAll().where { condition } else projectJoin().selectAll()
            val rows = query.toList()
            val counts = reactionCountsByProject(rows.map { it[CrowdfundingProjectTable.id] })
            rows.map { it.toProjectDto(now, counts) }
        }
    }

    override suspend fun getProject(id: String): CrowdfundingProjectDto {
        resolveCurrentMember(call)
        val projectId = id.toProjectUuid()
        val now = nowLocalDateTime()
        return transaction { loadProject(projectId, now) }
    }

    /**
     * Concurrency: [requireProjectRow] is called with `forUpdate = true`, taking a row-level lock
     * on this project BEFORE [requireDecidable] reads its status -- so a second, concurrent board
     * decision on the same project (e.g. one caller approving while another rejects) blocks until
     * the first commits, then re-reads the now-decided status and fails
     * [requireDecidable]'s `PENDING` check instead of silently racing. The `UPDATE` itself is
     * additionally a compare-and-swap (`status eq PENDING` in the WHERE clause, checked-for-zero
     * afterwards) as defense in depth against the same lost-update -- see
     * [CrowdfundingProjectTable] call sites' KDoc pattern.
     */
    override suspend fun approveProject(id: String): CrowdfundingProjectDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*CF_BOARD_ROLES)
        val projectId = id.toProjectUuid()
        val now = nowLocalDateTime()
        return transaction {
            val row = requireProjectRow(projectId, forUpdate = true)
            requireDecidable(row, now)
            val updated =
                CrowdfundingProjectTable.update({
                    (CrowdfundingProjectTable.id eq projectId) and (CrowdfundingProjectTable.status eq CrowdfundingProjectStatus.PENDING)
                }) {
                    it[status] = CrowdfundingProjectStatus.APPROVED
                    it[reviewedBy] = current.memberId
                    it[reviewedAt] = now
                }
            if (updated == 0) {
                throw ConflictException("CrowdfundingProject $projectId was concurrently decided -- retry")
            }
            loadProject(projectId, now)
        }
    }

    /** See [approveProject] KDoc for the row-lock + compare-and-swap concurrency contract shared by both. */
    override suspend fun rejectProject(
        id: String,
        reason: String,
    ): CrowdfundingProjectDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*CF_BOARD_ROLES)
        if (reason.isBlank()) throw ConflictException("rejectProject requires a non-blank reason")
        val projectId = id.toProjectUuid()
        val now = nowLocalDateTime()
        return transaction {
            val row = requireProjectRow(projectId, forUpdate = true)
            requireDecidable(row, now)
            val updated =
                CrowdfundingProjectTable.update({
                    (CrowdfundingProjectTable.id eq projectId) and (CrowdfundingProjectTable.status eq CrowdfundingProjectStatus.PENDING)
                }) {
                    it[status] = CrowdfundingProjectStatus.REJECTED
                    it[rejectionReason] = reason
                    it[reviewedBy] = current.memberId
                    it[reviewedAt] = now
                }
            if (updated == 0) {
                throw ConflictException("CrowdfundingProject $projectId was concurrently decided -- retry")
            }
            loadProject(projectId, now)
        }
    }

    override suspend fun castReaction(
        projectId: String,
        value: CrowdfundingReactionValue,
    ): CrowdfundingReactionDto {
        val current = resolveCurrentMember(call)
        val pId = projectId.toProjectUuid()
        val now = nowLocalDateTime()
        return transaction {
            val projectRow = requireProjectRow(pId)
            if (effectiveStatusOf(projectRow, now) != CrowdfundingProjectStatus.APPROVED) {
                throw ConflictException("Project $projectId is not yet approved -- reactions/donations are not open")
            }
            val existing =
                CrowdfundingReactionTable
                    .selectAll()
                    .where { (CrowdfundingReactionTable.projectId eq pId) and (CrowdfundingReactionTable.memberId eq current.memberId) }
                    .singleOrNull()
            val id =
                if (existing == null) {
                    val newId = Uuid.random()
                    CrowdfundingReactionTable.insert {
                        it[CrowdfundingReactionTable.id] = newId
                        it[CrowdfundingReactionTable.projectId] = pId
                        it[CrowdfundingReactionTable.memberId] = current.memberId
                        it[CrowdfundingReactionTable.reactionValue] = value
                        it[castAt] = now
                    }
                    newId
                } else {
                    val existingId = existing[CrowdfundingReactionTable.id]
                    CrowdfundingReactionTable.update({ CrowdfundingReactionTable.id eq existingId }) {
                        it[CrowdfundingReactionTable.reactionValue] = value
                        it[castAt] = now
                    }
                    existingId
                }
            reactionJoin()
                .selectAll()
                .where { CrowdfundingReactionTable.id eq id }
                .single()
                .toReactionDto()
        }
    }

    override suspend fun retractReaction(projectId: String) {
        val current = resolveCurrentMember(call)
        val pId = projectId.toProjectUuid()
        transaction {
            CrowdfundingReactionTable.deleteWhere {
                (CrowdfundingReactionTable.projectId eq pId) and (CrowdfundingReactionTable.memberId eq current.memberId)
            }
        }
    }

    override suspend fun getMyReaction(projectId: String): List<CrowdfundingReactionDto> {
        val current = resolveCurrentMember(call)
        val pId = projectId.toProjectUuid()
        return transaction {
            reactionJoin()
                .selectAll()
                .where { (CrowdfundingReactionTable.projectId eq pId) and (CrowdfundingReactionTable.memberId eq current.memberId) }
                .singleOrNull()
                ?.toReactionDto()
                .let(::listOfNotNull)
        }
    }

    override suspend fun computeMonthlyDistribution(
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): List<CrowdfundingDistributionDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*CF_TREASURY_ROLES)
        if (periodEnd < periodStart) throw ConflictException("periodEnd must not be before periodStart")
        val now = nowLocalDateTime()
        return transaction {
            val startBound = LocalDateTime(periodStart.year, periodStart.month, periodStart.day, 0, 0, 0)
            val endBound = LocalDateTime(periodEnd.year, periodEnd.month, periodEnd.day, 23, 59, 59)
            val paidCondition =
                (ContributionTable.status eq ContributionStatus.PAID) and
                    (ContributionTable.paidAt.isNotNull()) and
                    (ContributionTable.paidAt greaterEq startBound) and
                    (ContributionTable.paidAt lessEq endBound)
            val paidRows = ContributionTable.selectAll().where { paidCondition }.toList()
            val totalPaid =
                paidRows.fold(BigDecimal.ZERO.setScale(2)) { acc, row ->
                    acc + (row[ContributionTable.paidAmount] ?: row[ContributionTable.amountDue])
                }
            val distinctPayerCount = paidRows.map { it[ContributionTable.memberId] }.toSet().size
            val deduction = MIN_PLATFORM_CONTRIBUTION_EUR.multiply(BigDecimal(distinctPayerCount)).setScale(2, RoundingMode.UNNECESSARY)
            val pool = (totalPaid - deduction).let { if (it.signum() < 0) BigDecimal.ZERO.setScale(2) else it }

            val approvedProjectIds =
                CrowdfundingProjectTable
                    .selectAll()
                    .toList()
                    .filter { effectiveStatusOf(it, now) == CrowdfundingProjectStatus.APPROVED }
                    .map { it[CrowdfundingProjectTable.id] }
            val counts = reactionCountsByProject(approvedProjectIds)
            val baskets =
                approvedProjectIds.associateWith { pId ->
                    counts[pId]?.let { (like, dislike) -> (like - dislike).coerceAtLeast(0) }
                        ?: 0
                }

            val allocation = CrowdfundingDistributionCalculator.allocate(baskets, pool)
            allocation.forEach { (projectId, amount) ->
                CrowdfundingDistributionTable.insertIgnore {
                    it[id] = Uuid.random()
                    it[CrowdfundingDistributionTable.projectId] = projectId
                    it[CrowdfundingDistributionTable.periodStart] = periodStart
                    it[CrowdfundingDistributionTable.periodEnd] = periodEnd
                    it[basketTotalAtDistribution] = baskets.getValue(projectId)
                    it[amountEur] = amount
                    it[computedAt] = now
                    it[triggeredBy] = current.memberId
                }
            }
            loadDistributions(
                (CrowdfundingDistributionTable.periodStart eq periodStart) and (CrowdfundingDistributionTable.periodEnd eq periodEnd),
            )
        }
    }

    override suspend fun listDistributions(projectId: String?): List<CrowdfundingDistributionDto> {
        resolveCurrentMember(call)
        val condition = projectId?.let { CrowdfundingDistributionTable.projectId eq it.toProjectUuid() }
        return transaction { loadDistributions(condition) }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────────────

    // requireActiveMembership was extracted to network.lapis.cloud.server.rpc.MembershipGuards
    // (V0.6.4) so PoliticianService can reuse it -- see that file's KDoc.

    /**
     * [forUpdate] takes a `SELECT ... FOR UPDATE` row lock on this project before returning it --
     * required by [approveProject]/[rejectProject] to close the lost-update race between two
     * concurrent board decisions on the same project (see their shared KDoc). Read-only callers
     * ([castReaction]) leave it `false`.
     */
    private fun requireProjectRow(
        id: Uuid,
        forUpdate: Boolean = false,
    ): ResultRow {
        val query = CrowdfundingProjectTable.selectAll().where { CrowdfundingProjectTable.id eq id }
        return (if (forUpdate) query.forUpdate() else query).singleOrNull()
            ?: throw NotFoundException("CrowdfundingProject $id not found")
    }

    /** Guards `approveProject`/`rejectProject`: only while PENDING and not already auto-approved by silence. */
    private fun requireDecidable(
        row: ResultRow,
        now: LocalDateTime,
    ) {
        val status = row[CrowdfundingProjectTable.status]
        if (status != CrowdfundingProjectStatus.PENDING) {
            throw ConflictException("CrowdfundingProject ${row[CrowdfundingProjectTable.id]} already decided ($status)")
        }
        if (CrowdfundingWeightDecay.isAutoApproved(row[CrowdfundingProjectTable.submittedAt], now)) {
            throw ConflictException(
                "CrowdfundingProject ${row[CrowdfundingProjectTable.id]} was already auto-approved by silence -- a board decision is no longer possible",
            )
        }
    }

    private fun effectiveStatusOf(
        row: ResultRow,
        now: LocalDateTime,
    ): CrowdfundingProjectStatus {
        val status = row[CrowdfundingProjectTable.status]
        if (status != CrowdfundingProjectStatus.PENDING) return status
        return if (CrowdfundingWeightDecay.isAutoApproved(row[CrowdfundingProjectTable.submittedAt], now)) {
            CrowdfundingProjectStatus.APPROVED
        } else {
            CrowdfundingProjectStatus.PENDING
        }
    }

    private fun loadProject(
        id: Uuid,
        now: LocalDateTime,
    ): CrowdfundingProjectDto {
        val row =
            projectJoin().selectAll().where { CrowdfundingProjectTable.id eq id }.singleOrNull()
                ?: throw NotFoundException("CrowdfundingProject $id not found")
        val counts = reactionCountsByProject(listOf(id))
        return row.toProjectDto(now, counts)
    }

    private fun loadDistributions(condition: Op<Boolean>?): List<CrowdfundingDistributionDto> {
        val query = distributionJoin().selectAll()
        return (if (condition != null) query.where { condition } else query).map { it.toDistributionDto() }
    }

    /**
     * One query for every relevant project's reactions (`projectId inList projectIds`), grouped
     * in Kotlin -- NOT one query per project. Bounded by the number of projects passed in, not by
     * total reaction-row count across the whole table, closing the N+1/DoS gap
     * `computeMonthlyDistribution`'s basket calculation would otherwise have across many
     * projects. Returns `projectId -> (likeCount, dislikeCount)`; a project with no reactions at
     * all is simply absent from the result (callers default to `0, 0`).
     */
    private fun reactionCountsByProject(projectIds: List<Uuid>): Map<Uuid, Pair<Int, Int>> {
        if (projectIds.isEmpty()) return emptyMap()
        val rows =
            CrowdfundingReactionTable
                .selectAll()
                .where { CrowdfundingReactionTable.projectId inList projectIds }
                .toList()
        return rows
            .groupBy { it[CrowdfundingReactionTable.projectId] }
            .mapValues { (_, reactions) ->
                val likes = reactions.count { it[CrowdfundingReactionTable.reactionValue] == CrowdfundingReactionValue.LIKE }
                val dislikes = reactions.count { it[CrowdfundingReactionTable.reactionValue] == CrowdfundingReactionValue.DISLIKE }
                likes to dislikes
            }
    }

    /**
     * Explicit join, not `CrowdfundingProjectTable innerJoin MemberTable`:
     * [CrowdfundingProjectTable] has TWO FKs to [MemberTable] (`submitterMemberId`/`reviewedBy`),
     * so Exposed's implicit FK-based join resolution can't tell which path to use -- same
     * disambiguation [ContributionService.contributionJoin]'s own KDoc documents.
     */
    private fun projectJoin() =
        CrowdfundingProjectTable.join(MemberTable, JoinType.INNER, CrowdfundingProjectTable.submitterMemberId, MemberTable.id)

    private fun reactionJoin() =
        CrowdfundingReactionTable.join(MemberTable, JoinType.INNER, CrowdfundingReactionTable.memberId, MemberTable.id)

    private fun distributionJoin() =
        (CrowdfundingDistributionTable innerJoin CrowdfundingProjectTable)
            .join(MemberTable, JoinType.INNER, CrowdfundingDistributionTable.triggeredBy, MemberTable.id)

    private fun memberDisplayName(memberId: Uuid?): String? =
        memberId?.let { id ->
            MemberTable
                .selectAll()
                .where { MemberTable.id eq id }
                .singleOrNull()
                ?.get(MemberTable.displayName)
        }

    private fun ResultRow.toProjectDto(
        now: LocalDateTime,
        counts: Map<Uuid, Pair<Int, Int>>,
    ): CrowdfundingProjectDto {
        val id = this[CrowdfundingProjectTable.id]
        val submittedAt = this[CrowdfundingProjectTable.submittedAt]
        val initialWeight = this[CrowdfundingProjectTable.initialWeightLtr]
        val (likeCount, dislikeCount) = counts[id] ?: (0 to 0)
        val status = this[CrowdfundingProjectTable.status]
        val effectiveStatus =
            if (status != CrowdfundingProjectStatus.PENDING) {
                status
            } else if (CrowdfundingWeightDecay.isAutoApproved(submittedAt, now)) {
                CrowdfundingProjectStatus.APPROVED
            } else {
                CrowdfundingProjectStatus.PENDING
            }
        return CrowdfundingProjectDto(
            id = id.toString(),
            title = this[CrowdfundingProjectTable.title],
            description = this[CrowdfundingProjectTable.description],
            submitterMemberId = this[CrowdfundingProjectTable.submitterMemberId].toString(),
            submitterDisplayName = this[MemberTable.displayName],
            initialWeightLtr = initialWeight,
            currentWeightLtr = CrowdfundingWeightDecay.currentWeight(initialWeight, submittedAt, now),
            status = status,
            effectiveStatus = effectiveStatus,
            isAutoApproved = CrowdfundingWeightDecay.isAutoApproved(submittedAt, now),
            rejectionReason = this[CrowdfundingProjectTable.rejectionReason],
            reviewedById = this[CrowdfundingProjectTable.reviewedBy]?.toString(),
            reviewedByDisplayName = memberDisplayName(this[CrowdfundingProjectTable.reviewedBy]),
            reviewedAt = this[CrowdfundingProjectTable.reviewedAt],
            submittedAt = submittedAt,
            likeCount = likeCount,
            dislikeCount = dislikeCount,
            basketTotal = (likeCount - dislikeCount).coerceAtLeast(0),
        )
    }

    private fun ResultRow.toReactionDto(): CrowdfundingReactionDto =
        CrowdfundingReactionDto(
            id = this[CrowdfundingReactionTable.id].toString(),
            projectId = this[CrowdfundingReactionTable.projectId].toString(),
            memberId = this[CrowdfundingReactionTable.memberId].toString(),
            memberDisplayName = this[MemberTable.displayName],
            value = this[CrowdfundingReactionTable.reactionValue],
            castAt = this[CrowdfundingReactionTable.castAt],
        )

    private fun ResultRow.toDistributionDto(): CrowdfundingDistributionDto =
        CrowdfundingDistributionDto(
            id = this[CrowdfundingDistributionTable.id].toString(),
            projectId = this[CrowdfundingDistributionTable.projectId].toString(),
            projectTitle = this[CrowdfundingProjectTable.title],
            periodStart = this[CrowdfundingDistributionTable.periodStart],
            periodEnd = this[CrowdfundingDistributionTable.periodEnd],
            basketTotalAtDistribution = this[CrowdfundingDistributionTable.basketTotalAtDistribution],
            amountEur = this[CrowdfundingDistributionTable.amountEur],
            computedAt = this[CrowdfundingDistributionTable.computedAt],
            triggeredById = this[CrowdfundingDistributionTable.triggeredBy].toString(),
            triggeredByDisplayName = this[MemberTable.displayName],
        )

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    private fun String.toProjectUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}
