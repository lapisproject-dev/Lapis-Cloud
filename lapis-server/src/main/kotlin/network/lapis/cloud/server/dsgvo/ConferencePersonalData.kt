package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [ConferenceRoomTable]/[ConferenceParticipationTable] (V1.0 Videokonferenzen, Wave 1). Two
 * member FKs across the two tables (`conference_room.created_by_member_id`,
 * `conference_participation.member_id`) -- same "actor plus subject(s)" shape
 * [PeerTransferPersonalData]/[AuctionPersonalData] already establish.
 *
 * **Retain-with-reason across the board**, same precedent as [AuctionPersonalData]: a room's
 * existence and who created it is a shared organizational meeting record other participants'
 * `conference_participation` rows depend on for context (removing the creator's identity would
 * orphan every other participant's own join-history row's meaning); a participation row is itself
 * this member's own attendance record, but is retained rather than deleted because it is also part
 * of the room's shared roster history (same reasoning [AuctionPersonalData] gives for
 * `auction_bid`). No `ltr_ledger_entry` rows are ever created by this domain (Wave 1 has no paid
 * tier), so nothing here is already covered elsewhere. Chat is explicitly NEVER persisted at all
 * (see `network.lapis.cloud.shared.domain.ConferenceChatMessage` KDoc) -- there is no third table to
 * cover.
 */
object ConferencePersonalData : PersonalDataContributor {
    override val sectionKey = "conference"
    override val displayName = "Videokonferenzen"
    override val coveredTables =
        setOf(
            ConferenceRoomTable,
            ConferenceParticipationTable,
        )

    override fun export(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("roomsCreated") {
                ConferenceRoomTable
                    .selectAll()
                    .where { ConferenceRoomTable.createdByMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ConferenceRoomTable.id].toString())
                                put("title", row[ConferenceRoomTable.title])
                                put("createdAt", row[ConferenceRoomTable.createdAt].toString())
                                put("endedAt", row[ConferenceRoomTable.endedAt]?.toString())
                            },
                        )
                    }
            }
            putJsonArray("participations") {
                ConferenceParticipationTable
                    .selectAll()
                    .where { ConferenceParticipationTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ConferenceParticipationTable.id].toString())
                                put("roomId", row[ConferenceParticipationTable.roomId].toString())
                                put("role", row[ConferenceParticipationTable.role].name)
                                put("joinedAt", row[ConferenceParticipationTable.joinedAt].toString())
                                put("leftAt", row[ConferenceParticipationTable.leftAt]?.toString())
                            },
                        )
                    }
            }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val roomsCreatedCount = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.createdByMemberId eq memberId }.count()
        val participationCount =
            ConferenceParticipationTable
                .selectAll()
                .where { ConferenceParticipationTable.memberId eq memberId }
                .count()

        return listOf(
            TableErasureOutcome(
                table = "conference_room",
                rowsRetained = roomsCreatedCount.toInt(),
                retentionReason =
                    "A room's creator identity is a shared organizational meeting record every other " +
                        "participant's own conference_participation row on that room depends on for context.",
            ),
            TableErasureOutcome(
                table = "conference_participation",
                rowsRetained = participationCount.toInt(),
                retentionReason =
                    "A join/leave record is part of the room's shared roster history, same treatment " +
                        "auction_bid receives for its own shared, auditable outcome computation.",
            ),
        )
    }
}
