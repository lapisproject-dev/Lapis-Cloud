package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.payment.bankstatement.PaymentReferenceAllocator
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ContributionDto
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.MemberContributionSummaryDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MembershipTierDto
import network.lapis.cloud.shared.domain.MembershipTierInput
import network.lapis.cloud.shared.domain.MembershipTierOverviewDto
import network.lapis.cloud.shared.domain.MembershipTierRules
import network.lapis.cloud.shared.domain.MembershipTierSnapshot
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.MembershipTierIntervalLockedException
import network.lapis.cloud.shared.rpc.MembershipTierNameTakenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private val TREASURY_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)
private val BOARD_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

class ContributionService(
    private val call: ApplicationCall,
) : IContributionService {
    override suspend fun listMembershipTiers(): List<MembershipTierDto> {
        // V1.9.18: any authenticated caller, no role gate (the member-facing relief form reads this list) -- but no
        // longer reachable without a session at all, which the fee schedule never needed to be.
        resolveCurrentMember(call)
        return transaction {
            MembershipTierTable.selectAll().orderBy(MembershipTierTable.name).map { it.toMembershipTierDto() }
        }
    }

    override suspend fun listMembershipTierOverview(): MembershipTierOverviewDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        return transaction {
            val tiers = MembershipTierTable.selectAll().orderBy(MembershipTierTable.name).map { it.toMembershipTierDto() }
            // ONE aggregate query for every tier (never one count per tier). ACTIVE only: those are the
            // members generateContributionsForPeriod invoices. The group whose tier id is NULL is the
            // "active members without a tier" figure the screen's hint is built on.
            val memberCount = MemberTable.id.count()
            val counts =
                MemberTable
                    .select(MemberTable.membershipTierId, memberCount)
                    .where { MemberTable.status eq MemberStatus.ACTIVE }
                    .groupBy(MemberTable.membershipTierId)
                    .associate { it[MemberTable.membershipTierId] to it[memberCount].toInt() }
            MembershipTierOverviewDto(
                tiers = tiers,
                memberCounts = counts.entries.mapNotNull { (tierId, n) -> tierId?.let { it.toString() to n } }.toMap(),
                activeMembersWithoutTier = counts[null] ?: 0,
            )
        }
    }

    override suspend fun createMembershipTier(input: MembershipTierInput): MembershipTierDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        val normalized = normalizeTierInput(input)
        val now = DbClock.nowLocalDateTime()
        return transaction {
            if (nameKeyTaken(nameKey = normalized.nameKey, excludeId = null)) throw MembershipTierNameTakenException()
            val id = Uuid.random()
            try {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = id
                    it[name] = normalized.name
                    it[nameKey] = normalized.nameKey
                    it[description] = normalized.description
                    it[contributionAmount] = normalized.amount
                    it[billingInterval] = input.billingInterval
                    it[active] = input.active
                    it[paymentTermDays] = input.paymentTermDays
                }
            } catch (e: ExposedSQLException) {
                throw e.asNameTakenOrSelf()
            }
            val created = MembershipTierTable.selectAll().where { MembershipTierTable.id eq id }.single()
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.MEMBERSHIP_TIER,
                entityId = id,
                action = AuditAction.CREATE,
                before = null,
                after = Json.encodeToString(MembershipTierSnapshot.serializer(), created.toTierSnapshot()),
                occurredAt = now,
            )
            logger.info { "membership tier created: actor=${current.memberId} actorRole=${current.role} tierId=$id" }
            created.toMembershipTierDto()
        }
    }

    override suspend fun updateMembershipTier(
        id: String,
        input: MembershipTierInput,
    ): MembershipTierDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        val tierId = id.toTierUuid()
        val normalized = normalizeTierInput(input)
        val now = DbClock.nowLocalDateTime()
        return transaction {
            // Lock order: the tier row FIRST, then (inside AuditLogRecorder.record) the audit chain state.
            val row =
                MembershipTierTable
                    .selectAll()
                    .where { MembershipTierTable.id eq tierId }
                    .forUpdate()
                    .singleOrNull() ?: throw NotFoundException("MembershipTier $id not found")
            if (nameKeyTaken(nameKey = normalized.nameKey, excludeId = tierId)) throw MembershipTierNameTakenException()
            if (row[MembershipTierTable.billingInterval] != input.billingInterval) {
                // ACTIVE members only: they are the ones generateContributionsForPeriod invoices, so they are the
                // ones whose already-generated periods and SEPA schedule sit on the old interval. The overview's
                // per-tier count is the same figure, so the client's disabled select and this check agree.
                val activeAssigned =
                    MemberTable
                        .selectAll()
                        .where { (MemberTable.membershipTierId eq tierId) and (MemberTable.status eq MemberStatus.ACTIVE) }
                        .count()
                if (activeAssigned > 0) throw MembershipTierIntervalLockedException()
            }
            val before = row.toTierSnapshot()
            try {
                MembershipTierTable.update({ MembershipTierTable.id eq tierId }) {
                    it[name] = normalized.name
                    it[nameKey] = normalized.nameKey
                    it[description] = normalized.description
                    it[contributionAmount] = normalized.amount
                    it[billingInterval] = input.billingInterval
                    it[active] = input.active
                    it[paymentTermDays] = input.paymentTermDays
                }
            } catch (e: ExposedSQLException) {
                throw e.asNameTakenOrSelf()
            }
            val updated = MembershipTierTable.selectAll().where { MembershipTierTable.id eq tierId }.single()
            val after = updated.toTierSnapshot()
            // A no-op save (nothing changed) writes no audit entry -- same idiom MembershipTierAssignment.apply uses.
            if (after != before) {
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.MEMBERSHIP_TIER,
                    entityId = tierId,
                    action = AuditAction.UPDATE,
                    before = Json.encodeToString(MembershipTierSnapshot.serializer(), before),
                    after = Json.encodeToString(MembershipTierSnapshot.serializer(), after),
                    occurredAt = now,
                )
                logger.info { "membership tier updated: actor=${current.memberId} actorRole=${current.role} tierId=$tierId" }
            }
            updated.toMembershipTierDto()
        }
    }

    override suspend fun generateContributionsForPeriod(
        membershipTierId: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): Int {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        val tierId = membershipTierId.toTierUuid()
        if (periodStart > periodEnd) throw BadRequestException("periodStart must not be after periodEnd")
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val tierRow =
                MembershipTierTable.selectAll().where { MembershipTierTable.id eq tierId }.singleOrNull()
                    ?: throw NotFoundException("MembershipTier $membershipTierId not found")
            val amountDue = tierRow[MembershipTierTable.contributionAmount]
            // V1.9.18: a free tier (amount 0) is never invoiced -- a contribution line of 0.00 would otherwise
            // run through dunning, SEPA and the payment-reference allocator for nothing. A CLOSED tier
            // (active = false) deliberately keeps generating: closing only stops NEW assignments, the members
            // already on the tier still owe their contribution.
            if (amountDue.signum() == 0) return@transaction 0
            // V1.2.1: "Zahlungsziel" -- see 01-contribution.kuml.kts file header "Welle V1.2.1".
            // Deliberately periodStart-relative (not periodEnd/createdAt-relative): due_date is the
            // moment the OBLIGATION for this period starts running, independent of when the line
            // happened to be generated (a treasurer generating quarterly contributions a week late
            // must not silently shift every member's due date by that same week).
            val dueDate = periodStart.plus(tierRow[MembershipTierTable.paymentTermDays], DateTimeUnit.DAY)

            // Welle V1.4.4.5 Sterbefall-Workflow -- no code change needed here: the ACTIVE-only
            // filter below already structurally excludes MemberStatus.DECEASED (and every other
            // non-ACTIVE status) from ever generating a new contribution line. See
            // ContributionServiceTest for the verification test added by that wave.
            //
            // Welle V1.4.10 "Beitragsvergünstigungen": second structural exclusion condition next
            // to the ACTIVE filter above -- a member exempted for THIS period generates no
            // contribution line. "Ganz-oder-gar-nicht": the exemption must cover the FULL period
            // (from <= periodStart AND (until == null OR until >= periodEnd)) -- no anteilige
            // (partial-period) exemption in this wave, see ContributionExemptionRules KDoc and
            // CHANGELOG "bewusste Auslassung". Wortgleich zu
            // ContributionExemptionRules.isExemptForPeriod, hier als Exposed-Op formuliert, damit
            // der Ausschluss im SQL-WHERE statt in einer Kotlin-Nachfilterung passiert -- ein Test
            // (ContributionReliefExemptionFilterTest) verifiziert beide Formulierungen gegeneinander.
            val notExemptForPeriod =
                MemberTable.contributionExemptFrom.isNull() or
                    (MemberTable.contributionExemptFrom greater periodStart) or
                    (MemberTable.contributionExemptUntil.isNotNull() and (MemberTable.contributionExemptUntil less periodEnd))

            val activeMembers =
                MemberTable
                    .selectAll()
                    .where {
                        (MemberTable.membershipTierId eq tierId) and
                            (MemberTable.status eq MemberStatus.ACTIVE) and
                            notExemptForPeriod
                    }.map { it[MemberTable.id] }

            var created = 0
            activeMembers.forEach { memberId ->
                val newContributionId = Uuid.random()
                val inserted =
                    ContributionTable.insertIgnore {
                        it[id] = newContributionId
                        it[ContributionTable.memberId] = memberId
                        it[ContributionTable.membershipTierId] = tierId
                        it[ContributionTable.periodStart] = periodStart
                        it[ContributionTable.periodEnd] = periodEnd
                        it[ContributionTable.amountDue] = amountDue
                        it[status] = ContributionStatus.OPEN
                        it[createdAt] = now
                        it[ContributionTable.dueDate] = dueDate
                        it[paymentMethod] = ContributionPaymentMethod.MANUAL
                    }
                if (inserted.insertedCount > 0) {
                    created++
                    // Welle V1.4.5.1 "Kontoauszugs-Import" -- allocated eagerly here (not left to
                    // the invoice-render lazy path alone) so a treasurer generating a period's
                    // contributions can see the reference immediately, e.g. via a mail-merge that
                    // reads it before any single invoice PDF is ever rendered. See
                    // PaymentReferenceAllocator KDoc for why this is never a Postgres SEQUENCE.
                    PaymentReferenceAllocator.allocate(newContributionId)
                }
            }
            created
        }
    }

    override suspend fun listContributions(
        memberId: String?,
        status: ContributionStatus?,
        periodFrom: LocalDate?,
        periodTo: LocalDate?,
    ): List<ContributionDto> {
        val current = resolveCurrentMember(call)
        val effectiveMemberId =
            if (current.isPrivileged || current.role == AccountRole.TREASURER) {
                memberId?.toMemberUuid()
            } else {
                current.memberId
            }
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (effectiveMemberId != null) conditions += (ContributionTable.memberId eq effectiveMemberId)
            if (status != null) conditions += (ContributionTable.status eq status)
            if (periodFrom != null) conditions += (ContributionTable.periodStart greaterEq periodFrom)
            if (periodTo != null) conditions += (ContributionTable.periodEnd lessEq periodTo)

            val baseQuery = contributionJoin().selectAll()
            val query = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
            query.map { it.toContributionDto() }
        }
    }

    /**
     * V1.2.1 "Zahlungs-Fundament" fix (Befund B-1, see vault plan "Lapis Cloud V1.2 --
     * Zahlungsverkehr" Teil 0): before this wave, this method wrote ONLY the status/paidAt/
     * paidAmount/note fields -- no [network.lapis.cloud.server.db.generated.JournalEntryTable] row
     * was ever created, and no [AuditLogRecorder] entry either. It now additionally calls
     * [ContributionPostingBridge.postContributionPayment] (source = MANUAL, the only source this
     * wave has a caller for) inside this SAME transaction -- see that object's KDoc for the full
     * booking shape and its deliberate "degrades, does not throw" behaviour when the treasurer has
     * not yet configured the payment-account mapping in `OrganizationSettings`. On success (a
     * journal entry was actually booked) it writes ONE `AuditEntityType.JOURNAL_ENTRY` audit-log
     * entry, mirroring `AccountingService.insertJournalEntry`'s own behaviour exactly -- see
     * [ContributionPostingBridge]'s own KDoc "last locking operation" for why nothing else may lock
     * a row in this transaction after that call. If the payment-account mapping is unconfigured or
     * one of the mapped accounts is inactive, the bridge degrades to a no-op (no journal entry, no
     * audit-log entry) and this method's contribution status transition to `PAID` still goes
     * through unaffected -- see [ContributionPostingBridge] KDoc "Verhält sich degradierend statt
     * scheiternd". This is **not** true for every bridge outcome, though (Review Round 3,
     * 2026-08-19, SHOULD-1): if the mapping IS configured but the constructed postings would be
     * unbalanced, the bridge's `requireBalanced` check throws [ConflictException], which rolls back
     * this WHOLE transaction -- including the status/paidAt/paidAmount/note write.
     *
     * **Welle V1.2.8 "PSP-Checkout (Stripe)"**: this method is no longer [ContributionPostingBridge]'s
     * only caller -- `network.lapis.cloud.server.payment.psp.PspWebhookIngestion` is the second,
     * calling with `source = GATEWAY` once a Stripe `checkout.session.completed` webhook is
     * ingested. No behaviour change here: manual marking (this method, `source = MANUAL`) stays
     * available for every non-gateway channel (cash, bank transfer, other), and the two callers
     * share the exact same bridge, guard, and audit discipline.
     *
     * Callers must not
     * assume `markContributionPaid` always succeeds in flipping the status just because the bridge
     * "only degrades, never throws" -- that guarantee only covers the unconfigured-mapping/
     * inactive-account cases, not the unbalanced-postings case.
     *
     * **Idempotency guard (Review Round 1, 2026-08-19, CRITICAL-2):** the `UPDATE`'s `WHERE` clause
     * excludes every already-[ContributionStatusSets.SETTLED] contribution (`PAID`/`WAIVED`), so a
     * second call against a contribution that is already `PAID` matches zero rows instead of
     * silently re-running [ContributionPostingBridge.postContributionPayment] and posting a
     * duplicate journal entry. When zero rows match, this method distinguishes "doesn't exist"
     * ([NotFoundException]) from "exists but already settled" ([ConflictException]) by a follow-up
     * lookup, so a caller (and a treasurer accidentally double-clicking "als bezahlt markieren") can
     * tell the two apart instead of both surfacing as the same generic not-found error.
     */
    override suspend fun markContributionPaid(
        contributionId: String,
        paidAt: LocalDateTime,
        paidAmount: BigDecimal,
        note: String?,
    ): ContributionDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        val id = contributionId.toContributionUuid()
        return transaction {
            val updated =
                ContributionTable.update({
                    (ContributionTable.id eq id) and (ContributionTable.status notInList ContributionStatusSets.SETTLED)
                }) {
                    it[status] = ContributionStatus.PAID
                    it[ContributionTable.paidAt] = paidAt
                    it[ContributionTable.paidAmount] = paidAmount
                    if (note != null) it[ContributionTable.note] = note
                }
            if (updated == 0) {
                val existing = ContributionTable.selectAll().where { ContributionTable.id eq id }.singleOrNull()
                if (existing == null) {
                    throw NotFoundException("Contribution $contributionId not found")
                } else {
                    throw ConflictException(
                        "Contribution $contributionId is already settled (status=${existing[ContributionTable.status]}) -- already paid",
                    )
                }
            }
            ContributionPostingBridge.postContributionPayment(
                contributionId = id,
                paidAmount = paidAmount,
                paidAt = paidAt,
                source = ContributionPaymentMethod.MANUAL,
                providerFee = null,
                actorMemberId = current.memberId,
                actorRole = current.role,
                voucherReference = null,
            )
            // Welle V1.3.2 "Webhooks" (ausgehend) -- see ContributionPaymentEvents KDoc for why
            // this fires alongside the status flip above rather than depending on the accounting
            // bridge's own (possibly no-op) outcome. No payment_transaction row exists for a
            // MANUAL settlement, so the contribution's own id doubles as the transactionId.
            ContributionPaymentEvents.publishPaid(contributionId = id, paidAt = paidAt, amount = paidAmount, transactionId = id.toString())
            loadContribution(id)
        }
    }

    /**
     * **Idempotency / settlement guard (Review Round 2, 2026-08-19, MAJOR):** symmetric to
     * [markContributionPaid]'s own guard from Review Round 1 -- the `UPDATE`'s `WHERE` clause
     * excludes every already-[ContributionStatusSets.SETTLED] contribution (`PAID`/`WAIVED`), so a
     * BOARD member cannot waive a contribution that a treasurer already marked `PAID` (which would
     * otherwise silently orphan the [ContributionPostingBridge]-booked journal entry -- the general
     * ledger would still show the money received while the member's own summary reads `WAIVED`/€0,
     * with no reversal and no audit trail of the waive at all). When zero rows match, this method
     * distinguishes "doesn't exist" ([NotFoundException]) from "exists but already settled"
     * ([ConflictException]) the same way [markContributionPaid] does.
     */
    override suspend fun markContributionWaived(
        contributionId: String,
        note: String?,
    ): ContributionDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val id = contributionId.toContributionUuid()
        return transaction {
            val updated =
                ContributionTable.update({
                    (ContributionTable.id eq id) and (ContributionTable.status notInList ContributionStatusSets.SETTLED)
                }) {
                    it[status] = ContributionStatus.WAIVED
                    if (note != null) it[ContributionTable.note] = note
                }
            if (updated == 0) {
                val existing = ContributionTable.selectAll().where { ContributionTable.id eq id }.singleOrNull()
                if (existing == null) {
                    throw NotFoundException("Contribution $contributionId not found")
                } else {
                    throw ConflictException(
                        "Contribution $contributionId is already settled (status=${existing[ContributionTable.status]}) -- cannot waive",
                    )
                }
            }
            loadContribution(id)
        }
    }

    /**
     * **Bugfix, Befund B-1 (Design-Review V1.4.4.1 "Beitragshistorie"):** [totalOpen] used to sum
     * only [ContributionStatus.OPEN] rows -- a contribution that had progressed to
     * [ContributionStatus.OVERDUE]/[ContributionStatus.RETURNED]/[ContributionStatus.IN_DUNNING]
     * (still genuinely owed, per [ContributionStatusSets.OUTSTANDING]) silently vanished from this
     * summary's "offen" figure, even though `PspCheckoutSection.kt` already filtered its own donor-
     * facing "still owed" check on [ContributionStatusSets.OUTSTANDING] correctly -- the two views
     * of the same member's finances disagreed. Now sums the full [ContributionStatusSets.OUTSTANDING]
     * set, matching [PspCheckoutSection]'s own posture.
     */
    override suspend fun getMemberContributionSummary(memberId: String): MemberContributionSummaryDto {
        val current = resolveCurrentMember(call)
        val requestedId = memberId.toMemberUuid()
        if (!current.isPrivileged && current.role != AccountRole.TREASURER && current.memberId != requestedId) {
            throw ForbiddenException()
        }
        return transaction {
            val contributions =
                contributionJoin()
                    .selectAll()
                    .where { ContributionTable.memberId eq requestedId }
                    .map { it.toContributionDto() }
            val totalDue = contributions.sumAmount { it.amountDue }
            val totalPaid = contributions.filter { it.status == ContributionStatus.PAID }.sumAmount { it.paidAmount ?: it.amountDue }
            val totalOpen = contributions.filter { it.status in ContributionStatusSets.OUTSTANDING }.sumAmount { it.amountDue }
            MemberContributionSummaryDto(
                memberId = memberId,
                totalDue = totalDue,
                totalPaid = totalPaid,
                totalOpen = totalOpen,
                contributions = contributions,
            )
        }
    }

    private fun loadContribution(id: Uuid): ContributionDto =
        contributionJoin()
            .selectAll()
            .where { ContributionTable.id eq id }
            .single()
            .toContributionDto()

    /**
     * Explicit join, not `ContributionTable innerJoin MemberTable innerJoin MembershipTierTable`:
     * both [ContributionTable.membershipTierId] and [MemberTable.membershipTierId] reference
     * [MembershipTierTable.id], so Exposed's implicit FK-based join resolution can't tell which
     * path to use and throws `IllegalStateException: ... multiple primary key <-> foreign key
     * references`. Joining on [ContributionTable.membershipTierId] explicitly disambiguates it.
     */
    private fun contributionJoin() =
        ContributionTable
            .innerJoin(MemberTable)
            .join(MembershipTierTable, JoinType.INNER, ContributionTable.membershipTierId, MembershipTierTable.id)
}

private fun List<ContributionDto>.sumAmount(selector: (ContributionDto) -> BigDecimal): BigDecimal =
    fold(BigDecimal.ZERO) { acc, dto -> acc + selector(dto) }

private fun String.toTierUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

private fun String.toMemberUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

private fun String.toContributionUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

private fun ResultRow.toMembershipTierDto(): MembershipTierDto =
    MembershipTierDto(
        id = this[MembershipTierTable.id].toString(),
        name = this[MembershipTierTable.name],
        description = this[MembershipTierTable.description],
        contributionAmount = this[MembershipTierTable.contributionAmount],
        billingInterval = this[MembershipTierTable.billingInterval],
        active = this[MembershipTierTable.active],
        paymentTermDays = this[MembershipTierTable.paymentTermDays],
    )

private fun ResultRow.toContributionDto(): ContributionDto =
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
        paymentReference = this[ContributionTable.paymentReference],
    )

/** The validated, normalized form of a [MembershipTierInput]: what is actually written. */
private class NormalizedTierInput(
    val name: String,
    val nameKey: String,
    val description: String,
    val amount: BigDecimal,
)

private val MAX_TIER_AMOUNT = BigDecimal(MembershipTierRules.MAX_CONTRIBUTION_AMOUNT_TEXT)

/**
 * Server-authoritative validation of a tier input (the client pre-checks the same
 * [MembershipTierRules], but only the server is trusted). Every failure is a [BadRequestException]:
 * the client shows a field-level message for each of these BEFORE the call, so reaching the server
 * with one means a stale/hand-made request. The one validation that genuinely needs a typed answer --
 * the duplicate name -- is [MembershipTierNameTakenException], thrown by the callers.
 */
private fun normalizeTierInput(input: MembershipTierInput): NormalizedTierInput {
    val name = MembershipTierRules.normalizeName(input.name)
    if (!MembershipTierRules.isValidName(name)) {
        throw BadRequestException("name must be 1-${MembershipTierRules.NAME_MAX_LENGTH} characters, no control characters")
    }
    val description = input.description.trim()
    if (description.length > MembershipTierRules.DESCRIPTION_MAX_LENGTH) {
        throw BadRequestException("description must be at most ${MembershipTierRules.DESCRIPTION_MAX_LENGTH} characters")
    }
    val amount: BigDecimal = input.contributionAmount
    if (amount.signum() < 0 || amount > MAX_TIER_AMOUNT) {
        throw BadRequestException("contributionAmount must be between 0 and ${MembershipTierRules.MAX_CONTRIBUTION_AMOUNT_TEXT}")
    }
    // Scale is enforced here, not only in the client: DECIMAL(12,2) would silently round a third decimal.
    if (amount.stripTrailingZeros().scale() > MembershipTierRules.MAX_AMOUNT_SCALE) {
        throw BadRequestException("contributionAmount must have at most ${MembershipTierRules.MAX_AMOUNT_SCALE} decimal places")
    }
    if (input.paymentTermDays !in 0..MembershipTierRules.MAX_PAYMENT_TERM_DAYS) {
        throw BadRequestException("paymentTermDays must be between 0 and ${MembershipTierRules.MAX_PAYMENT_TERM_DAYS}")
    }
    return NormalizedTierInput(
        name = name,
        nameKey = MembershipTierRules.nameKey(name),
        description = description,
        amount = amount.setScale(MembershipTierRules.MAX_AMOUNT_SCALE),
    )
}

private fun nameKeyTaken(
    nameKey: String,
    excludeId: Uuid?,
): Boolean =
    MembershipTierTable
        .selectAll()
        .where {
            if (excludeId == null) {
                MembershipTierTable.nameKey eq nameKey
            } else {
                (MembershipTierTable.nameKey eq nameKey) and (MembershipTierTable.id neq excludeId)
            }
        }.count() > 0

/**
 * Race backstop for the pre-check in [nameKeyTaken]: the UNIQUE index `uq_membership_tier_name_key` is the
 * actual guard. Only a violation of THAT index becomes [MembershipTierNameTakenException]; any other SQL
 * failure is rethrown unchanged, never misreported as a duplicate name (the constraint name is matched
 * case-insensitively -- H2 reports it in upper case).
 */
private fun ExposedSQLException.asNameTakenOrSelf(): Exception =
    if (message?.contains("uq_membership_tier_name_key", ignoreCase = true) == true) {
        logger.warn { "membership tier write hit uq_membership_tier_name_key" }
        MembershipTierNameTakenException()
    } else {
        this
    }

private fun ResultRow.toTierSnapshot(): MembershipTierSnapshot =
    MembershipTierSnapshot(
        name = this[MembershipTierTable.name],
        description = this[MembershipTierTable.description],
        contributionAmount = this[MembershipTierTable.contributionAmount].toPlainString(),
        billingInterval = this[MembershipTierTable.billingInterval],
        active = this[MembershipTierTable.active],
        paymentTermDays = this[MembershipTierTable.paymentTermDays],
    )
