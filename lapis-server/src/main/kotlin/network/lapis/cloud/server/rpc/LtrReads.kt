package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** One self-service LTR ledger row -- no other member's name, no `createdBy` actor name (that identifies a treasury operator, still not this tool's business). */
internal data class LtrSelfEntry(
    val entryType: LtrLedgerEntryType,
    val amountLtr: BigDecimal,
    val note: String?,
    val createdAt: LocalDateTime,
)

/**
 * Welle V1.8.1 "MCP-Server" -- narrowed, self-only read extracted from
 * [LtrLedgerService.getMyBalance]/[LtrLedgerService.listMyEntries], reused verbatim including their
 * own [network.lapis.cloud.server.rpc.requireLtrEligibleMembership] guard (see
 * `network.lapis.cloud.server.mcp.tools.GetMyLtrBalanceTool` -- the guard is NOT dropped, an
 * MCP-connected FRIEND/GUEST/APPLICATION/WITHDRAWN/REJECTED member gets the exact same
 * [network.lapis.cloud.shared.rpc.ForbiddenException]-shaped refusal the RPC surface already
 * gives). **Must run inside an already-open `transaction {}`**, same contract as [MemberReads].
 */
internal object LtrReads {
    private val balanceProvider = LedgerBackedLtrBalanceProvider()

    fun balanceFor(memberId: Uuid): BigDecimal {
        requireLtrEligibleMembership(memberId = memberId)
        return balanceProvider.freeBalance(memberId)
    }

    fun entriesFor(
        memberId: Uuid,
        limit: Int,
    ): List<LtrSelfEntry> {
        requireLtrEligibleMembership(memberId = memberId)
        return LtrLedgerEntryTable
            .selectAll()
            .where { LtrLedgerEntryTable.memberId eq memberId }
            .orderBy(LtrLedgerEntryTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map {
                LtrSelfEntry(
                    entryType = it[LtrLedgerEntryTable.entryType],
                    amountLtr = it[LtrLedgerEntryTable.amountLtr],
                    note = it[LtrLedgerEntryTable.note],
                    createdAt = it[LtrLedgerEntryTable.createdAt],
                )
            }
    }
}
