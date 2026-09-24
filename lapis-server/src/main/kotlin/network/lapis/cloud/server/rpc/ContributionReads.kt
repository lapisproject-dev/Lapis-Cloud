package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.ContributionStatusSets
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** Self-service summary of one member's own contribution ledger -- no other member's name or data. */
internal data class ContributionSelfSummary(
    val totalDue: BigDecimal,
    val totalPaid: BigDecimal,
    val totalOpen: BigDecimal,
    val openContributionCount: Int,
)

/**
 * Welle V1.8.1 "MCP-Server" -- extracted from [ContributionService.getMemberContributionSummary]'s
 * own query (same `contributionJoin`, same [ContributionStatusSets.OUTSTANDING] "open" definition),
 * narrowed to a self-only, name-free shape for `network.lapis.cloud.server.mcp.tools
 * .GetMyContributionStatusTool`. **Must run inside an already-open `transaction {}`**, same
 * contract as [MemberReads]/[GovernanceReads].
 */
internal object ContributionReads {
    fun summaryFor(memberId: Uuid): ContributionSelfSummary {
        val rows =
            ContributionTable
                .join(MembershipTierTable, JoinType.INNER, ContributionTable.membershipTierId, MembershipTierTable.id)
                .selectAll()
                .where { ContributionTable.memberId eq memberId }
                .toList()
        val totalDue = rows.fold(BigDecimal.ZERO) { acc, row -> acc + row[ContributionTable.amountDue] }
        val totalPaid =
            rows
                .filter { it[ContributionTable.status] == ContributionStatus.PAID }
                .fold(BigDecimal.ZERO) { acc, row -> acc + (row[ContributionTable.paidAmount] ?: row[ContributionTable.amountDue]) }
        val outstanding = rows.filter { it[ContributionTable.status] in ContributionStatusSets.OUTSTANDING }
        val totalOpen = outstanding.fold(BigDecimal.ZERO) { acc, row -> acc + row[ContributionTable.amountDue] }
        return ContributionSelfSummary(
            totalDue = totalDue,
            totalPaid = totalPaid,
            totalOpen = totalOpen,
            openContributionCount = outstanding.size,
        )
    }
}
