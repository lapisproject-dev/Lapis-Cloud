package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LtrLedgerBalanceDto
import network.lapis.cloud.shared.domain.LtrLedgerEntryDto
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MintLtrInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ILtrLedgerService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val LTR_TREASURY_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

/**
 * "Aktuelle Annahme, vor Produktiveinsatz zu verifizieren" -- same disclaimer class as
 * [PartyDonationComplianceCalculator]'s threshold constants. A floor purely to reject an
 * obviously-wrong `mintLtr` call (a truly zero-or-negative grant makes no sense as a MINT), not a
 * fachlich-motivated minimum grant size.
 */
private val MIN_MINT_LTR = BigDecimal("0.01")

private const val MAX_ENTRY_LIST_LIMIT = 1000

/**
 * V0.6.1 (Internes Crowdfunding) LTR-Ledger RPC surface -- see [ILtrLedgerService] KDoc. Reads
 * are always self-service unless the caller holds [LTR_TREASURY_ROLES]; the only write path is
 * [mintLtr] (a credit) -- binding a project stake (a debit) happens inside
 * [CrowdfundingService.submitProject], not here, since that write is only ever a side effect of
 * that specific fachlich action, never a standalone RPC call.
 */
class LtrLedgerService(
    private val call: ApplicationCall,
    private val ltrBalanceProvider: LtrBalanceProvider = LedgerBackedLtrBalanceProvider(),
) : ILtrLedgerService {
    override suspend fun getMyBalance(): LtrLedgerBalanceDto {
        val current = resolveCurrentMember(call)
        return transaction {
            LtrLedgerBalanceDto(memberId = current.memberId.toString(), freeBalanceLtr = ltrBalanceProvider.freeBalance(current.memberId))
        }
    }

    override suspend fun getMemberBalance(memberId: String): LtrLedgerBalanceDto {
        val current = resolveCurrentMember(call)
        val targetId = memberId.toMemberUuidOrThrow()
        if (targetId != current.memberId) current.requireRole(*LTR_TREASURY_ROLES)
        return transaction {
            LtrLedgerBalanceDto(memberId = targetId.toString(), freeBalanceLtr = ltrBalanceProvider.freeBalance(targetId))
        }
    }

    override suspend fun listMyEntries(limit: Int): List<LtrLedgerEntryDto> {
        val current = resolveCurrentMember(call)
        val boundedLimit = limit.coerceIn(1, MAX_ENTRY_LIST_LIMIT)
        return transaction { loadEntries(LtrLedgerEntryTable.memberId eq current.memberId, boundedLimit) }
    }

    override suspend fun listMemberEntries(
        memberId: String,
        limit: Int,
    ): List<LtrLedgerEntryDto> {
        val current = resolveCurrentMember(call)
        val targetId = memberId.toMemberUuidOrThrow()
        if (targetId != current.memberId) current.requireRole(*LTR_TREASURY_ROLES)
        val boundedLimit = limit.coerceIn(1, MAX_ENTRY_LIST_LIMIT)
        return transaction { loadEntries(LtrLedgerEntryTable.memberId eq targetId, boundedLimit) }
    }

    override suspend fun mintLtr(input: MintLtrInput): LtrLedgerEntryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*LTR_TREASURY_ROLES)
        val targetId = input.memberId.toMemberUuidOrThrow()
        val amount = input.amountLtr
        if (amount.scale() > 2) throw ConflictException("amountLtr must have at most 2 decimal places")
        val normalizedAmount = amount.setScale(2, RoundingMode.UNNECESSARY)
        if (normalizedAmount < MIN_MINT_LTR) throw ConflictException("amountLtr must be at least $MIN_MINT_LTR")
        val now = nowLocalDateTime()
        return transaction {
            MemberTable.selectAll().where { MemberTable.id eq targetId }.singleOrNull()
                ?: throw NotFoundException("Member ${input.memberId} not found")
            val newEntryId = Uuid.random()
            LtrLedgerEntryTable.insert {
                it[LtrLedgerEntryTable.id] = newEntryId
                it[memberId] = targetId
                it[entryType] = LtrLedgerEntryType.MINT
                it[amountLtr] = normalizedAmount
                it[referenceType] = null
                it[referenceId] = null
                it[note] = input.note
                it[createdBy] = current.memberId
                it[createdAt] = now
            }
            loadEntry(newEntryId)
        }
    }

    /**
     * Explicit join, not `LtrLedgerEntryTable innerJoin MemberTable`: [LtrLedgerEntryTable] has
     * TWO FKs to [MemberTable] (`memberId` and `createdBy`), so Exposed's implicit FK-based join
     * resolution can't tell which path to use and throws `IllegalStateException: ... multiple
     * primary key <-> foreign key references` -- same disambiguation
     * [ContributionService.contributionJoin]'s own KDoc documents for its analogous case.
     */
    private fun ledgerEntryJoin() = LtrLedgerEntryTable.join(MemberTable, JoinType.INNER, LtrLedgerEntryTable.memberId, MemberTable.id)

    private fun loadEntries(
        condition: Op<Boolean>,
        limit: Int,
    ): List<LtrLedgerEntryDto> =
        ledgerEntryJoin()
            .selectAll()
            .where { condition }
            .orderBy(LtrLedgerEntryTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toLtrLedgerEntryDto() }

    private fun loadEntry(id: Uuid): LtrLedgerEntryDto =
        ledgerEntryJoin()
            .selectAll()
            .where { LtrLedgerEntryTable.id eq id }
            .single()
            .toLtrLedgerEntryDto()

    private fun memberDisplayName(memberId: Uuid?): String? =
        memberId?.let { id ->
            MemberTable
                .selectAll()
                .where { MemberTable.id eq id }
                .singleOrNull()
                ?.get(MemberTable.displayName)
        }

    private fun ResultRow.toLtrLedgerEntryDto(): LtrLedgerEntryDto =
        LtrLedgerEntryDto(
            id = this[LtrLedgerEntryTable.id].toString(),
            memberId = this[LtrLedgerEntryTable.memberId].toString(),
            memberDisplayName = this[MemberTable.displayName],
            entryType = this[LtrLedgerEntryTable.entryType],
            amountLtr = this[LtrLedgerEntryTable.amountLtr],
            referenceType = this[LtrLedgerEntryTable.referenceType],
            referenceId = this[LtrLedgerEntryTable.referenceId]?.toString(),
            note = this[LtrLedgerEntryTable.note],
            createdById = this[LtrLedgerEntryTable.createdBy]?.toString(),
            createdByDisplayName = memberDisplayName(this[LtrLedgerEntryTable.createdBy]),
            createdAt = this[LtrLedgerEntryTable.createdAt],
        )

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    private fun String.toMemberUuidOrThrow(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}
