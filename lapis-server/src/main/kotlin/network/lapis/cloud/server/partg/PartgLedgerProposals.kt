package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.partg.report.PartgLedgerFacts
import network.lapis.cloud.server.partg.report.PartgLedgerProposal
import network.lapis.cloud.server.partg.report.PartgProposalAccount
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import network.lapis.cloud.shared.domain.PartgLedgerRules
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgUnitLevel
import network.lapis.cloud.shared.domain.PartgValueSource
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

    /**
     * V1.9.290 (R5): the units of [year] that book in Lapis (mode A or B, never a subordinate summary) and HAVE posted bookings. If such a unit has
     * no stored value, its bookings were never taken over, and the consolidation says so instead of "no data". Must run inside a transaction.
     */
    fun takeOverPending(
        year: Int,
        unitKeys: Collection<String>,
    ): Set<String> {
        val modes = PartgLedgerLinkStore.modes(year)
        val counts = PartgLedgerReader.postedCountsByChapter(year)
        return unitKeys
            .filter { key ->
                val unit = PartgUnit.parse(key) ?: return@filter false
                val mode = modes[key] ?: PartgBookkeepingMode.C
                unit.level != PartgUnitLevel.SUBORDINATE &&
                    mode != PartgBookkeepingMode.C &&
                    (counts[chapterOf(unit)] ?: 0) > 0
            }.toSet()
    }

    /**
     * V1.9.292 (R4b): moved here from `PartyReportService` without a change of behaviour (the unit-part export uses it too). Must run inside a
     * transaction. The bookings-related facts of the plausibility assistant, per federal or state unit that books in Lapis or has bookings anyway.
     */
    fun ledgerFacts(
        year: Int,
        units: List<PartgUnitDto>,
    ): Map<String, PartgLedgerFacts> {
        val modes = PartgLedgerLinkStore.modes(year)
        val counts = PartgLedgerReader.postedCountsByChapter(year)
        val result = HashMap<String, PartgLedgerFacts>()
        units.forEach { dto ->
            val unit = PartgUnit.parse(dto.unitKey) ?: return@forEach
            if (unit.level == PartgUnitLevel.SUBORDINATE) return@forEach
            val mode = modes[unit.key()] ?: PartgBookkeepingMode.C
            val bookings = counts[chapterOf(unit)] ?: 0
            if (mode == PartgBookkeepingMode.C && bookings == 0) return@forEach
            result[unit.key()] =
                if (mode == PartgBookkeepingMode.C) {
                    PartgLedgerFacts(
                        mode = mode,
                        bookingCount = bookings,
                        unmappedWithActivity = 0,
                        proposal = emptyMap(),
                        ledgerSourced = emptySet(),
                    )
                } else {
                    val sheet = PartgReportStore.findSheet(year = year, unit = unit)
                    PartgLedgerFacts(
                        mode = mode,
                        bookingCount = bookings,
                        unmappedWithActivity = unmappedWithActivity(year = year, unit = unit),
                        proposal = proposalFor(year = year, unit = unit),
                        ledgerSourced =
                            sheet
                                ?.let { PartgReportStore.valueSources(it.id) }
                                .orEmpty()
                                .filterValues { it == PartgValueSource.LEDGER }
                                .keys,
                    )
                }
        }
        return result
    }

    /** What the unit part of one unit needs from the bookings (V1.9.292, R4b). */
    class UnitPartFacts(
        val ledger: Map<String, PartgLedgerFacts>,
        val mode: PartgBookkeepingMode,
        val postedEntryCount: Int,
    )

    /** V1.9.292 (R4b): the ledger facts, the mode and the POSTED count of ONE unit. A subordinate summary has no bookings. Run inside a transaction. */
    fun unitPartFacts(
        year: Int,
        unit: PartgUnit,
        units: List<PartgUnitDto>,
    ): UnitPartFacts {
        val key = unit.key()
        val posted =
            if (unit.level ==
                PartgUnitLevel.SUBORDINATE
            ) {
                0
            } else {
                PartgLedgerReader.postedCount(year = year, chapterId = chapterOf(unit))
            }
        return UnitPartFacts(
            ledger = ledgerFacts(year = year, units = units.filter { it.unitKey == key }),
            mode = PartgLedgerLinkStore.modes(year)[key] ?: PartgBookkeepingMode.C,
            postedEntryCount = posted,
        )
    }
}
