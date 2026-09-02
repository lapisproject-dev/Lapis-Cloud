package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionDto
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.MemberContributionSummaryDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MembershipTierDto
import network.lapis.cloud.shared.domain.MembershipTierInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val TREASURY_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)
private val BOARD_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

class ContributionService(
    private val call: ApplicationCall,
) : IContributionService {
    override suspend fun listMembershipTiers(): List<MembershipTierDto> =
        transaction {
            MembershipTierTable.selectAll().map { it.toMembershipTierDto() }
        }

    override suspend fun createMembershipTier(input: MembershipTierInput): MembershipTierDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        return transaction {
            val id = Uuid.random()
            MembershipTierTable.insert {
                it[MembershipTierTable.id] = id
                it[name] = input.name
                it[description] = input.description
                it[contributionAmount] = input.contributionAmount
                it[billingInterval] = input.billingInterval
                it[active] = input.active
                it[paymentTermDays] = input.paymentTermDays
            }
            MembershipTierTable
                .selectAll()
                .where { MembershipTierTable.id eq id }
                .single()
                .toMembershipTierDto()
        }
    }

    override suspend fun updateMembershipTier(
        id: String,
        input: MembershipTierInput,
    ): MembershipTierDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        val tierId = id.toTierUuid()
        return transaction {
            val updated =
                MembershipTierTable.update({ MembershipTierTable.id eq tierId }) {
                    it[name] = input.name
                    it[description] = input.description
                    it[contributionAmount] = input.contributionAmount
                    it[billingInterval] = input.billingInterval
                    it[active] = input.active
                    it[paymentTermDays] = input.paymentTermDays
                }
            if (updated == 0) throw NotFoundException("MembershipTier $id not found")
            MembershipTierTable
                .selectAll()
                .where { MembershipTierTable.id eq tierId }
                .single()
                .toMembershipTierDto()
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
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val tierRow =
                MembershipTierTable.selectAll().where { MembershipTierTable.id eq tierId }.singleOrNull()
                    ?: throw NotFoundException("MembershipTier $membershipTierId not found")
            val amountDue = tierRow[MembershipTierTable.contributionAmount]
            // V1.2.1: "Zahlungsziel" -- see 01-contribution.kuml.kts file header "Welle V1.2.1".
            // Deliberately periodStart-relative (not periodEnd/createdAt-relative): due_date is the
            // moment the OBLIGATION for this period starts running, independent of when the line
            // happened to be generated (a treasurer generating quarterly contributions a week late
            // must not silently shift every member's due date by that same week).
            val dueDate = periodStart.plus(tierRow[MembershipTierTable.paymentTermDays], DateTimeUnit.DAY)

            val activeMembers =
                MemberTable
                    .selectAll()
                    .where {
                        (MemberTable.membershipTierId eq tierId) and
                            (MemberTable.status eq MemberStatus.ACTIVE)
                    }.map { it[MemberTable.id] }

            var created = 0
            activeMembers.forEach { memberId ->
                val inserted =
                    ContributionTable.insertIgnore {
                        it[id] = Uuid.random()
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
                if (inserted.insertedCount > 0) created++
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
            val totalOpen = contributions.filter { it.status == ContributionStatus.OPEN }.sumAmount { it.amountDue }
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
    )
