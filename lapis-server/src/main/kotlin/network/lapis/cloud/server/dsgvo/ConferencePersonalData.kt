package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTrackTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [ConferenceRoomTable]/[ConferenceParticipationTable] (V1.0 Videokonferenzen, Wave 1) and
 * [ConferenceRecordingTable]/[ConferenceRecordingTrackTable] (V1.0 Wave 2 "Aufzeichnung"). Three
 * member-FK-bearing columns across the four tables (`conference_room.created_by_member_id`,
 * `conference_participation.member_id`, `conference_recording.started_by_member_id`) -- same
 * "actor plus subject(s)" shape [PeerTransferPersonalData]/[AuctionPersonalData] already
 * establish. [ConferenceRecordingTrackTable] carries NO member FK of its own
 * (`participant_identity` is a plain string echo of a LiveKit identity, not a `«Column».fkEntity`
 * -- see `28-conference-recording.kuml.kts` file header) but is covered here anyway for
 * completeness (one contributor per domain section, matching how this object already covers
 * `conference_participation` alongside `conference_room`).
 *
 * **Retain-with-reason across the board**, same precedent as [AuctionPersonalData]: a room's
 * existence and who created it is a shared organizational meeting record other participants'
 * `conference_participation` rows depend on for context (removing the creator's identity would
 * orphan every other participant's own join-history row's meaning); a participation row is itself
 * this member's own attendance record, but is retained rather than deleted because it is also part
 * of the room's shared roster history (same reasoning [AuctionPersonalData] gives for
 * `auction_bid`). A `conference_recording` row is retained for the identical reason its Wave 2 own
 * KDoc gives for auditing it in the first place (§32 BGB/GoBD: "who started recording this
 * meeting, when" must remain provable) -- anonymizing the starter would undermine the very
 * accountability trail the recording (and its own `AuditLogEntryTable` rows) exist to provide.
 * `conference_recording_track` rows are retained as part of that same recording's own shared
 * technical record. No `ltr_ledger_entry` rows are ever created by this domain (no paid tier), so
 * nothing here is already covered elsewhere. Chat is explicitly NEVER persisted at all (see
 * `network.lapis.cloud.shared.domain.ConferenceChatMessage` KDoc) -- there is no fifth table to
 * cover. The composed recording FILE itself (once `conference_recording.document_id` is set) is a
 * `document`/`document_version` row -- covered by `DocumentPersonalData`, not here.
 */
object ConferencePersonalData : PersonalDataContributor {
    override val sectionKey = "conference"
    override val displayName = "Videokonferenzen"
    override val coveredTables =
        setOf(
            ConferenceRoomTable,
            ConferenceParticipationTable,
            ConferenceRecordingTable,
            ConferenceRecordingTrackTable,
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
            putJsonArray("recordingsStarted") {
                ConferenceRecordingTable
                    .selectAll()
                    .where { ConferenceRecordingTable.startedByMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ConferenceRecordingTable.id].toString())
                                put("roomId", row[ConferenceRecordingTable.roomId].toString())
                                put("status", row[ConferenceRecordingTable.status].name)
                                put("startedAt", row[ConferenceRecordingTable.startedAt].toString())
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
        val recordingIds =
            ConferenceRecordingTable
                .selectAll()
                .where { ConferenceRecordingTable.startedByMemberId eq memberId }
                .map { it[ConferenceRecordingTable.id] }
        val recordingTrackCount =
            if (recordingIds.isEmpty()) {
                0L
            } else {
                ConferenceRecordingTrackTable
                    .selectAll()
                    .where { ConferenceRecordingTrackTable.recordingId inList recordingIds }
                    .count()
            }

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
            TableErasureOutcome(
                table = "conference_recording",
                rowsRetained = recordingIds.size,
                retentionReason =
                    "Who started recording a meeting, when, is a GoBD/section-32-BGB accountability " +
                        "fact this wave's own audit-log entries already make provable -- anonymizing " +
                        "the starter here would undermine that same trail.",
            ),
            TableErasureOutcome(
                table = "conference_recording_track",
                rowsRetained = recordingTrackCount.toInt(),
                retentionReason =
                    "Part of the retained recording's own shared technical record, same treatment as conference_participation.",
            ),
        )
    }
}
