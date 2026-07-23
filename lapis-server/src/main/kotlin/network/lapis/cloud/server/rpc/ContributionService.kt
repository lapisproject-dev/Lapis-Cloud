package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionDto
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.MemberContributionSummaryDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MembershipTierDto
import network.lapis.cloud.shared.domain.MembershipTierInput
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
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Clock
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
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return transaction {
            val tierRow =
                MembershipTierTable.selectAll().where { MembershipTierTable.id eq tierId }.singleOrNull()
                    ?: throw NotFoundException("MembershipTier $membershipTierId not found")
            val amountDue = tierRow[MembershipTierTable.contributionAmount]

            val activeMembers =
                MemberTable
                    .selectAll()
                    .where {
                        (MemberTable.membershipTierId eq tierId) and
                            (MemberTable.status eq MemberStatus.AKTIV)
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
                ContributionTable.update({ ContributionTable.id eq id }) {
                    it[status] = ContributionStatus.PAID
                    it[ContributionTable.paidAt] = paidAt
                    it[ContributionTable.paidAmount] = paidAmount
                    if (note != null) it[ContributionTable.note] = note
                }
            if (updated == 0) throw NotFoundException("Contribution $contributionId not found")
            loadContribution(id)
        }
    }

    override suspend fun markContributionWaived(
        contributionId: String,
        note: String?,
    ): ContributionDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val id = contributionId.toContributionUuid()
        return transaction {
            val updated =
                ContributionTable.update({ ContributionTable.id eq id }) {
                    it[status] = ContributionStatus.WAIVED
                    if (note != null) it[ContributionTable.note] = note
                }
            if (updated == 0) throw NotFoundException("Contribution $contributionId not found")
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
    )
