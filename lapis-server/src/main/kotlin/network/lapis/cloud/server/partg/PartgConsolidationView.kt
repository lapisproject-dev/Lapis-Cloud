package network.lapis.cloud.server.partg

import network.lapis.cloud.server.partg.report.PartgConsolidation
import network.lapis.cloud.server.partg.report.PartgDonorConsolidation
import network.lapis.cloud.shared.domain.PartgConsolidationDto

/**
 * V1.9.290 (R5) -- turns ONE consistent [ReportSnapshot] into the screen model of the consolidation (positions, matching table, statement B,
 * hints P17..P21). Pure glue between the snapshot and the computing cores; reads nothing itself.
 */
internal object PartgConsolidationView {
    fun build(
        year: Int,
        snapshot: ReportSnapshot,
        takeOverPending: Map<Int, Set<String>>,
    ): PartgConsolidationDto {
        val result =
            PartgConsolidation.compute(
                year = year,
                units = snapshot.units,
                entered = snapshot.entered.values,
                consolidation = snapshot.consolidation,
                takeOverPending = takeOverPending,
            )
        val data = snapshot.consolidation[year]
        val donors =
            PartgDonorConsolidation.compute(
                units = snapshot.units,
                annex = snapshot.annex[year].orEmpty(),
                combinations = data?.combinations.orEmpty(),
            )
        val order = snapshot.units.withIndex().associate { it.value.unitKey to it.index }
        val findings =
            PartgConsolidation
                .findings(
                    year = year,
                    units = snapshot.units,
                    entered = snapshot.entered.values,
                    consolidation = snapshot.consolidation,
                    donors = donors,
                ).findings
                .sortedWith(
                    compareBy({
                        it.unitKey?.let { key ->
                            order[key]
                        } ?: Int.MAX_VALUE
                    }, { it.code.ordinal }, { it.positionCode.orEmpty() }),
                )
        return PartgConsolidationDto(
            year = year,
            revision = data?.revision ?: 0L,
            annexRevisions = snapshot.annex[year].orEmpty().mapValues { it.value.revision },
            included = result.included,
            excluded = result.excluded,
            priorExcluded = result.priorExcluded,
            rows = result.rows,
            priorTransfersUnmatched = result.priorTransfersUnmatched,
            transfers = result.transfers,
            unmatched = result.unmatched,
            donors = donors.donors,
            candidates = donors.candidates,
            candidateTotal = donors.candidateTotal,
            findings = findings,
        )
    }
}
