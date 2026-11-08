package network.lapis.cloud.server.partg.report

import network.lapis.cloud.server.partg.PartgLedgerProposals
import network.lapis.cloud.server.partg.ReportSnapshot
import network.lapis.cloud.shared.domain.PartgReportScope
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.291 (R6) -- the ONE assembly of the report document from a consistent snapshot, shared by the export route (the working state) and the
 * writer that keeps a version. Extracted from the export route without a change of behaviour: findings, the consolidation and the donor list
 * for the whole party, the document itself.
 */
internal object PartgReportDocuments {
    /** The finished document plus what the export reports to the audit log (counters). */
    class Built(
        val document: PartgReportDocument,
        val findingCount: Int,
    )

    /**
     * Pure apart from ONE read: the bookings not yet taken over (`takeOverPending`) are read in their own transaction, only for the whole-party scope.
     * Must not be called inside a transaction that should stay short; callers run it outside their write transaction.
     */
    fun build(
        snapshot: ReportSnapshot,
        year: Int,
        scope: PartgReportScope,
    ): PartgReportDocument = buildWithCounters(snapshot = snapshot, year = year, scope = scope).document

    fun buildWithCounters(
        snapshot: ReportSnapshot,
        year: Int,
        scope: PartgReportScope,
    ): Built {
        // V1.9.290 (R5): the whole-party export also carries the consolidation (the route is federal-only: the gate above it)
        val whole = scope is PartgReportScope.All
        val donors =
            if (whole) {
                PartgDonorConsolidation.compute(
                    units = snapshot.units,
                    annex = snapshot.annex[year].orEmpty(),
                    combinations = snapshot.consolidation[year]?.combinations.orEmpty(),
                )
            } else {
                null
            }
        val consolidation =
            if (whole) {
                val keys = snapshot.units.map { it.unitKey }
                val pending =
                    transaction {
                        listOf(
                            year,
                            year - 1,
                        ).associateWith { PartgLedgerProposals.takeOverPending(year = it, unitKeys = keys) }
                    }
                PartgConsolidation.compute(
                    year = year,
                    units = snapshot.units,
                    entered = snapshot.entered.values,
                    consolidation = snapshot.consolidation,
                    takeOverPending = pending,
                )
            } else {
                null
            }
        val findings =
            PartgPlausibility.check(
                year = year,
                units = snapshot.units,
                entered = snapshot.entered.values,
                annex = snapshot.annex,
                notes = snapshot.notes,
                consolidation =
                    donors?.let {
                        PartgConsolidation.findings(
                            year = year,
                            units = snapshot.units,
                            entered = snapshot.entered.values,
                            consolidation = snapshot.consolidation,
                            donors = it,
                        )
                    },
            )
        val document =
            PartgReportAssembler.assemble(
                organizationName = snapshot.organizationName,
                year = year,
                scope = scope,
                units = snapshot.units,
                entered = snapshot.entered.values,
                annex = snapshot.annex,
                notes = snapshot.notes,
                findings = findings,
                consolidation = consolidation,
                consolidatedDonors = donors,
            )
        return Built(document = document, findingCount = findings.size)
    }
}
