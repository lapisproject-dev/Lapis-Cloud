package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Welle V1.9.59 "Mitgliederzahlen ueber Zeit" -- owns [MemberStatusHistoryTable], the append-only log of status changes behind the
 * member-count statistics.
 *
 * **The rows are KEPT on erasure, in every [ErasureMode]** (`rowsRetained`, with the reason below). A row carries only a status and
 * an instant, never a name or an address; the member row itself is anonymised, never deleted, so `member_id` afterwards points at
 * an anonymised row (`joined_at` and `date_of_death` stay there for the same reason, see [FoundationPersonalData]). Deleting the
 * rows instead would silently rewrite every past count of the organisation -- exactly the error of a statistic that only knows the
 * present. The log therefore adds no re-identification risk: there is no `changed_by` (the actor lives in the hash-chained
 * audit log), and the statistics endpoint only ever returns counts per period.
 *
 * The export carries the subject's own rows (Art. 15): status, previous status, instant and source.
 */
object MemberStatusHistoryPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "memberStatusHistory"
    override val displayName = "Mitgliederstatus-Verlauf"
    override val coveredTables = setOf(MemberStatusHistoryTable)

    private const val RETENTION_REASON =
        "Anonyme Mitgliederstatistik: enthaelt nur Status und Zeitpunkt; die Mitgliedszeile selbst wird anonymisiert und nie geloescht, " +
            "ein Loeschen wuerde alle vergangenen Mitgliederzahlen verfaelschen"

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            put(
                "memberStatusHistory",
                buildJsonArray {
                    MemberStatusHistoryTable
                        .selectAll()
                        .where { MemberStatusHistoryTable.memberId eq memberId }
                        .orderBy(MemberStatusHistoryTable.effectiveFrom to SortOrder.ASC)
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("effectiveFrom", row[MemberStatusHistoryTable.effectiveFrom].toString())
                                    put("status", row[MemberStatusHistoryTable.status])
                                    put("previousStatus", row[MemberStatusHistoryTable.previousStatus])
                                    put("source", row[MemberStatusHistoryTable.sourceKind])
                                },
                            )
                        }
                },
            )
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val count = MemberStatusHistoryTable.selectAll().where { MemberStatusHistoryTable.memberId eq memberId }.count()
        return listOf(
            TableErasureOutcome(
                table = "member_status_history",
                rowsRetained = count.toInt(),
                retentionReason = RETENTION_REASON,
            ),
        )
    }
}
