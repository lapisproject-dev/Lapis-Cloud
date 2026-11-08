package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.partg.report.PartgLedgerProposal
import network.lapis.cloud.server.partg.report.PartgProposalAccount
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import network.lapis.cloud.shared.domain.PartgLedgerRules
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitLevel
import kotlin.uuid.Uuid

/**
 * V1.9.289 (R4a) -- glue between the stores: the proposal of ONE unit and year, computed from the stored mapping and the bookings. Runs inside the
 * caller's transaction. The ONE place that decides whether a proposal exists at all ([PartgLedgerRules.proposalAvailable]).
 */
internal object PartgLedgerProposals {
    /** The chapter whose bookings feed [unit]: `null` for the federal level. A subordinate summary has no bookings. */
    fun chapterOf(unit: PartgUnit): Uuid? = unit.chapterId?.let(Uuid::parse)

    fun available(
        unit: PartgUnit,
        mode: PartgBookkeepingMode,
    ): Boolean = PartgLedgerRules.proposalAvailable(level = unit.level, mode = mode)

    /** Position code to the proposed signed cents (empty when nothing is mapped). Does NOT check the mode. */
    fun proposalFor(
        year: Int,
        unit: PartgUnit,
    ): Map<String, Long> {
        if (unit.level == PartgUnitLevel.SUBORDINATE) return emptyMap()
        val chapterId = chapterOf(unit)
        val mappings = PartgLedgerLinkStore.mappings()
        if (mappings.isEmpty()) return emptyMap()
        val types = PartgLedgerReader.accounts().associate { it.id to it.type }
        val accounts =
            mappings.values
                .filter { !it.excluded }
                .mapNotNull { mapping ->
                    types[mapping.ledgerAccountId]?.let {
                        mapping.ledgerAccountId to
                            PartgProposalAccount(type = it, positionCode = mapping.positionCode)
                    }
                }.toMap()
        if (accounts.isEmpty()) return emptyMap()
        val flows = PartgLedgerReader.flowsByAccount(year = year, chapterId = chapterId)
        val balances = PartgLedgerReader.balancesByAccount(asOf = LocalDate(year, 12, 31), chapterId = chapterId)
        return PartgLedgerProposal.compute(accounts = accounts, flows = flows, balances = balances)
    }

    /** Accounts without a mapping that have postings of [unit] in [year] (count). Equity and inactive accounts count too: an unmapped account is unmapped. */
    fun unmappedWithActivity(
        year: Int,
        unit: PartgUnit,
    ): Int {
        if (unit.level == PartgUnitLevel.SUBORDINATE) return 0
        val mapped = PartgLedgerLinkStore.mappings().keys
        val flows = PartgLedgerReader.flowsByAccount(year = year, chapterId = chapterOf(unit))
        return flows.entries.count { (accountId, sums) -> accountId !in mapped && sums.postingCount > 0 }
    }
}
