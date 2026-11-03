package network.lapis.cloud.server.partg

import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Everything an export or the plausibility check needs, read as ONE consistent picture. */
internal class ReportSnapshot(
    val organizationName: String,
    val units: List<PartgUnitDto>,
    val entered: PartgReportStore.EnteredSnapshot,
    val annex: Map<Int, Map<String, PartgAnnexStore.AnnexData>>,
    val notes: Map<Int, PartgAnnexStore.NotesData>,
)

/**
 * V1.9.269 (R3) -- the consistent read of the sheets, the separate statements and the notes.
 *
 * Why a fence: the data lives in several tables with child lists, so ONE statement cannot read it (R2 could, with one sheet-value join). Under
 * READ COMMITTED the sequence of reads could see a half-applied write. Every writer bumps the revision of its header in the SAME commit as the
 * child rows, and a revision only ever increases, so: revisions before == revisions after proves that no write committed in between (no ABA).
 * On a difference the whole read is repeated, at most [MAX_ATTEMPTS] times, then it is a [PartyReportRevisionConflictException].
 */
internal object PartgReportSnapshotReader {
    const val MAX_ATTEMPTS = 3

    /** Test seam: called between the reads of every attempt. */
    internal var betweenReadsHook: (attempt: Int) -> Unit = {}

    fun read(years: Collection<Int>): ReportSnapshot {
        repeat(MAX_ATTEMPTS) { attempt ->
            val snapshot =
                transaction {
                    val before = revisions(years)
                    val organization =
                        OrganizationSettingsTable
                            .selectAll()
                            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                            .singleOrNull()
                            ?.get(OrganizationSettingsTable.name)
                            .orEmpty()
                    val units = PartgReportStore.units()
                    val entered = PartgReportStore.enteredSnapshot(years)
                    val annex = PartgAnnexStore.annexSnapshot(years)
                    betweenReadsHook(attempt)
                    val after = revisions(years)
                    if (before == after) {
                        ReportSnapshot(
                            organizationName = organization,
                            units = units,
                            entered = entered,
                            annex = annex.annex,
                            notes = annex.notes,
                        )
                    } else {
                        null
                    }
                }
            if (snapshot != null) return snapshot
        }
        throw PartyReportRevisionConflictException()
    }

    private fun revisions(years: Collection<Int>): Map<String, Long> =
        PartgReportStore.sheetRevisions(years) + PartgAnnexStore.headerRevisions(years)
}
