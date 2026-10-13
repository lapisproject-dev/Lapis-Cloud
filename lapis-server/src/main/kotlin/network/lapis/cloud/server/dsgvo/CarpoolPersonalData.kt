package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.CarpoolParticipationTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
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
    override val coveredTables = setOf(CarpoolPostingTable, CarpoolParticipationTable)

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
                                put("meetingNote", row[CarpoolPostingTable.meetingNote])
                                put(
                                    "participantCount",
                                    CarpoolParticipationTable
                                        .selectAll()
                                        .where { CarpoolParticipationTable.postingId eq row[CarpoolPostingTable.id] }
                                        .count(),
                                )
                                put("createdAt", row[CarpoolPostingTable.createdAt].toString())
                                put("updatedAt", row[CarpoolPostingTable.updatedAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("carpoolParticipations") {
                CarpoolParticipationTable
                    .join(CarpoolPostingTable, JoinType.INNER, CarpoolParticipationTable.postingId, CarpoolPostingTable.id)
                    .selectAll()
                    .where { CarpoolParticipationTable.personId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[CarpoolParticipationTable.id].toString())
                                put("postingId", row[CarpoolParticipationTable.postingId].toString())
                                put("fromPlace", row[CarpoolPostingTable.fromPlace])
                                put("toPlace", row[CarpoolPostingTable.toPlace])
                                put("departureDate", row[CarpoolPostingTable.departureDate].toString())
                                put("role", "PASSENGER")
                                put("initiator", row[CarpoolParticipationTable.initiator].name)
                                put("status", row[CarpoolParticipationTable.status].name)
                                put("createdAt", row[CarpoolParticipationTable.createdAt].toString())
                                put("decidedAt", row[CarpoolParticipationTable.decidedAt]?.toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        // The person's own rides as a passenger first (this releases their seats), then their offers together with everybody else's
        // participations on them -- explicit deletes, independent of the foreign key's ON DELETE rule.
        val ownRides = CarpoolParticipationTable.deleteWhere { CarpoolParticipationTable.personId eq memberId }
        val ownPostingIds =
            CarpoolPostingTable
                .select(CarpoolPostingTable.id)
                .where { CarpoolPostingTable.authorMemberId eq memberId }
                .map { it[CarpoolPostingTable.id] }
        val onOwnPostings =
            if (ownPostingIds.isEmpty()) {
                0
            } else {
                CarpoolParticipationTable.deleteWhere {
                    CarpoolParticipationTable.postingId inList
                        ownPostingIds
                }
            }
        val deleted = CarpoolPostingTable.deleteWhere { CarpoolPostingTable.authorMemberId eq memberId }
        return listOf(
            TableErasureOutcome(table = "carpool_participation", rowsDeleted = ownRides + onOwnPostings),
            TableErasureOutcome(table = "carpool_posting", rowsDeleted = deleted),
        )
    }
}
