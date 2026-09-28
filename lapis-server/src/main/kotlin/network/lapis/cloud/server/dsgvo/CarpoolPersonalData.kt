package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Welle V1.9.12 "Mitfahrerzentrale" -- `carpool_posting` trägt personenbezogene Daten (Ort-/
 * Zeit-Muster eines Mitglieds).
 *
 * **Erasure**: anders als [CommunicationPersonalData]'s `direct_message` (die eine Gegenpartei-
 * Kopie hat, an der ein Dritter Interesse haben könnte) hat ein `carpool_posting` keine
 * Gegenpartei-Kopie -- `eraseMember` löscht deshalb unabhängig vom [ErasureMode] immer hart, nichts
 * wird zurückbehalten.
 */
object CarpoolPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "carpool"
    override val displayName = "Mitfahrerzentrale"
    override val coveredTables = setOf(CarpoolPostingTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("carpoolPostings") {
                CarpoolPostingTable
                    .selectAll()
                    .where { CarpoolPostingTable.authorMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[CarpoolPostingTable.id].toString())
                                put("type", row[CarpoolPostingTable.type].name)
                                put("fromPlace", row[CarpoolPostingTable.fromPlace])
                                put("toPlace", row[CarpoolPostingTable.toPlace])
                                put("departureDate", row[CarpoolPostingTable.departureDate].toString())
                                put("departureTime", row[CarpoolPostingTable.departureTime]?.toString())
                                put("seatsOffered", row[CarpoolPostingTable.seatsOffered])
                                put("notes", row[CarpoolPostingTable.notes])
                                put("createdAt", row[CarpoolPostingTable.createdAt].toString())
                                put("updatedAt", row[CarpoolPostingTable.updatedAt].toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val deleted = CarpoolPostingTable.deleteWhere { CarpoolPostingTable.authorMemberId eq memberId }
        return listOf(TableErasureOutcome(table = "carpool_posting", rowsDeleted = deleted))
    }
}
