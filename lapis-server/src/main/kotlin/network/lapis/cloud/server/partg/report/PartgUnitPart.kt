package network.lapis.cloud.server.partg.report

import network.lapis.cloud.server.partg.PartgLedgerProposals
import network.lapis.cloud.server.partg.ReportSnapshot
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import network.lapis.cloud.shared.domain.PartgFindingCode
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgUnit
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.292 (R4b) -- the ONE source of the unit part a chapter treasurer sees: the preview RPC and the PDF/XLSX export both call [build], so the
 * screen and the file can never differ.
 *
 * The part is the money of ONE unit and nothing else: no statements A-F (donors, sponsors and enterprises are personal data of third parties), no
 * notes, no consolidation (it reads all units), and only the hints [ALLOWED_FINDINGS] of that unit. The document is built with empty annex and
 * notes on purpose, and the renderers additionally skip those sections ([PartgReportAudience.UNIT_PART]).
 */
internal object PartgUnitPart {
    /** The only hints a unit part may carry: P05 (variance), P14, P15 and P16 (bookings). Pinned by a test over all codes. */
    val ALLOWED_FINDINGS: Set<PartgFindingCode> =
        setOf(PartgFindingCode.P05, PartgFindingCode.P14, PartgFindingCode.P15, PartgFindingCode.P16)

    class Built(
        val document: PartgReportDocument,
        val findingCount: Int,
        val mode: PartgBookkeepingMode,
        val postedEntryCount: Int,
    )

    /** Must run OUTSIDE a transaction the caller wants to keep short: it opens its own for the ledger facts. */
    fun build(
        snapshot: ReportSnapshot,
        year: Int,
        unit: PartgUnit,
    ): Built {
        val unitKey = unit.key()
        val facts = transaction { PartgLedgerProposals.unitPartFacts(year = year, unit = unit, units = snapshot.units) }
        val findings: List<PartgFindingDto> =
            PartgPlausibility
                .check(
                    year = year,
                    units = snapshot.units,
                    entered = snapshot.entered.values,
                    annex = emptyMap(),
                    notes = emptyMap(),
                    ledger = facts.ledger,
                    consolidation = null,
                ).filter { it.unitKey == unitKey && it.code in ALLOWED_FINDINGS }
        val document =
            PartgReportAssembler.assemble(
                organizationName = snapshot.organizationName,
                year = year,
                scope = PartgReportScope.Single(unit),
                units = snapshot.units,
                entered = snapshot.entered.values,
                annex = emptyMap(),
                notes = emptyMap(),
                findings = findings,
                consolidation = null,
                consolidatedDonors = null,
            )
        return Built(document = document, findingCount = findings.size, mode = facts.mode, postedEntryCount = facts.postedEntryCount)
    }
}
