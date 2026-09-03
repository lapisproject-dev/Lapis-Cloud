package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.ConferenceBreakoutAssignmentTable
import network.lapis.cloud.server.db.generated.ConferenceBreakoutRoomTable
import network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTrackTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [ConferenceRoomTable]/[ConferenceParticipationTable] (V1.0 Videokonferenzen, Wave 1),
 * [ConferenceRecordingTable]/[ConferenceRecordingTrackTable] (V1.0 Wave 2 "Aufzeichnung"),
 * [ConferenceStreamDestinationTable]/[ConferenceStreamTable]/[ConferenceStreamTargetTable] (V1.0
 * Wave 3 "Externes Streaming"), and [ConferenceGuestConsentAcknowledgmentTable] (V1.0 Wave 5
 * "Föderations-Gastbeitritt"). Six member-FK-bearing columns across the eight tables
 * (`conference_room.created_by_member_id`, `conference_participation.member_id`,
 * `conference_recording.started_by_member_id`, `conference_stream_destination
 * .created_by_member_id`, `conference_stream.started_by_member_id`,
 * `conference_guest_consent_acknowledgment.member_id`) -- same "actor plus subject(s)" shape
 * [PeerTransferPersonalData]/[AuctionPersonalData] already establish.
 * [ConferenceRecordingTrackTable]/[ConferenceStreamTargetTable] carry NO member FK of their own
 * (the former's `participant_identity` is a plain string echo of a LiveKit identity, the latter
 * only resolves to `conference_stream`/`conference_stream_destination`, never to `member`
 * directly -- see `28-conference-recording.kuml.kts`/`29-conference-streaming.kuml.kts` file
 * headers) but both are covered here anyway for completeness (one contributor per domain section,
 * matching how this object already covers `conference_participation` alongside `conference_room`).
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
 * technical record. `conference_stream_destination` rows are retained for the SAME accountability
 * reasoning as `conference_room`'s creator -- an ADMIN's identity as the one who bound the
 * organization's external publishing credential (its YouTube/PeerTube channel) into the system is
 * itself a GoBD-relevant governance fact, quite apart from the fact that erasing it would silently
 * corrupt every `conference_stream_target` row that ever streamed through that destination.
 * `conference_stream` rows are retained for the identical §32-BGB/GoBD reason `conference_recording`
 * already establishes ("who started a public live stream, when" must remain provable) --
 * `conference_stream_target` rows are retained as part of that same stream's own shared technical
 * record, same treatment `conference_recording_track` receives. No `ltr_ledger_entry` rows are ever
 * created by this domain (no paid tier), so nothing here is already covered elsewhere. Chat is
 * explicitly NEVER persisted at all (see `network.lapis.cloud.shared.domain.ConferenceChatMessage`
 * KDoc) -- there is no additional table to cover for it. The composed recording FILE itself (once
 * `conference_recording.document_id` is set) is a `document`/`document_version` row -- covered by
 * `DocumentPersonalData`, not here. Streaming never produces such a file (the RTMP output lives
 * entirely on the external platform), so there is no equivalent hand-off for Wave 3.
 *
 * **`conference_guest_consent_acknowledgment` (Wave 5) is retained, never erased -- a deliberate
 * departure from the "shared record" reasoning above.** The proof that a federated guest was shown
 * and acknowledged this room's DSGVO consent text before joining is the organization's OWN
 * Rechenschaftsnachweis under Art. 5(2)/7(1) DSGVO -- erasing it would destroy the very record that
 * documents the lawfulness of processing that data subject's audio/video in that meeting.
 * `homeserver_url`/`organization_name` are exported/retained exactly as snapshotted at consent time
 * (see `30-conference-guest-access.kuml.kts` file header) -- never re-resolved live, so a
 * subsequent home-server change or organization rename does not retroactively alter what this
 * record proves the guest was shown.
 *
 * **`conference_breakout_room`/`conference_breakout_assignment` (V1.0 Wave 6 "Breakout-Räume")** --
 * retained for the SAME "shared record" reasoning as `conference_room`/`conference_participation`
 * above: a breakout room's creator identity (always the parent room's own moderator) is part of the
 * shared meeting record every assigned participant's own `conference_breakout_assignment` row
 * depends on for context, and an assignment row is itself part of the room's shared breakout
 * history, same treatment `conference_participation`/`auction_bid` already receive.
 *

 * **The stream key is never part of any export or erasure output here** --
 * `conference_stream_destination.stream_key_ciphertext` is never read by this object; export
 * surfaces only the same non-secret fields `ConferenceStreamDestinationDto` itself would (label/
 * platform/rtmpUrl/timestamps), matching `network.lapis.cloud.server.crypto.SecretBox`'s own
 * "the plaintext key is never returned to any RPC caller" posture end to end.
 */
object ConferencePersonalData : MemberPersonalDataContributor {
    override val sectionKey = "conference"
    override val displayName = "Videokonferenzen"
    override val coveredTables =
        setOf(
            ConferenceRoomTable,
            ConferenceParticipationTable,
            ConferenceRecordingTable,
            ConferenceRecordingTrackTable,
            ConferenceStreamDestinationTable,
            ConferenceStreamTable,
            ConferenceStreamTargetTable,
            ConferenceGuestConsentAcknowledgmentTable,
            ConferenceBreakoutRoomTable,
            ConferenceBreakoutAssignmentTable,
        )

    override fun exportMember(memberId: Uuid) =
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
            // V1.0 Videokonferenzen, Wave 3 "Externes Streaming" -- NEVER streamKeyCiphertext, see
            // class KDoc "The stream key is never part of any export".
            putJsonArray("streamDestinationsCreated") {
                ConferenceStreamDestinationTable
                    .selectAll()
                    .where { ConferenceStreamDestinationTable.createdByMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ConferenceStreamDestinationTable.id].toString())
                                put("label", row[ConferenceStreamDestinationTable.label])
                                put("platform", row[ConferenceStreamDestinationTable.platform].name)
                                put("rtmpUrl", row[ConferenceStreamDestinationTable.rtmpUrl])
                                put("createdAt", row[ConferenceStreamDestinationTable.createdAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("streamsStarted") {
                ConferenceStreamTable
                    .selectAll()
                    .where { ConferenceStreamTable.startedByMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ConferenceStreamTable.id].toString())
                                put("roomId", row[ConferenceStreamTable.roomId].toString())
                                put("status", row[ConferenceStreamTable.status].name)
                                put("startedAt", row[ConferenceStreamTable.startedAt].toString())
                            },
                        )
                    }
            }
            // V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt" -- never streamKeyCiphertext-
            // style secrets here either; consentSha256 is a hash of a PUBLIC disclaimer text, not a
            // secret, so exporting it is harmless and lets the subject verify which exact wording
            // they acknowledged.
            putJsonArray("guestConsentAcknowledgments") {
                ConferenceGuestConsentAcknowledgmentTable
                    .selectAll()
                    .where { ConferenceGuestConsentAcknowledgmentTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ConferenceGuestConsentAcknowledgmentTable.id].toString())
                                put("roomId", row[ConferenceGuestConsentAcknowledgmentTable.roomId].toString())
                                put("acknowledgedAt", row[ConferenceGuestConsentAcknowledgmentTable.acknowledgedAt].toString())
                                put("consentVersion", row[ConferenceGuestConsentAcknowledgmentTable.consentVersion])
                                put("consentSha256", row[ConferenceGuestConsentAcknowledgmentTable.consentSha256])
                                put("homeserverUrl", row[ConferenceGuestConsentAcknowledgmentTable.homeserverUrl])
                                put("organizationName", row[ConferenceGuestConsentAcknowledgmentTable.organizationName])
                            },
                        )
                    }
            }
            // V1.0 Videokonferenzen, Wave 6 "Breakout-Räume".
            putJsonArray("breakoutRoomsCreated") {
                ConferenceBreakoutRoomTable
                    .selectAll()
                    .where { ConferenceBreakoutRoomTable.createdByMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ConferenceBreakoutRoomTable.id].toString())
                                put("parentRoomId", row[ConferenceBreakoutRoomTable.parentRoomId].toString())
                                put("label", row[ConferenceBreakoutRoomTable.label])
                                put("createdAt", row[ConferenceBreakoutRoomTable.createdAt].toString())
                                put("closedAt", row[ConferenceBreakoutRoomTable.closedAt]?.toString())
                            },
                        )
                    }
            }
            putJsonArray("breakoutAssignments") {
                ConferenceBreakoutAssignmentTable
                    .selectAll()
                    .where { ConferenceBreakoutAssignmentTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ConferenceBreakoutAssignmentTable.id].toString())
                                put("breakoutRoomId", row[ConferenceBreakoutAssignmentTable.breakoutRoomId].toString())
                                put("assignedAt", row[ConferenceBreakoutAssignmentTable.assignedAt].toString())
                                put("recalledAt", row[ConferenceBreakoutAssignmentTable.recalledAt]?.toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
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
        val streamDestinationsCreatedCount =
            ConferenceStreamDestinationTable
                .selectAll()
                .where { ConferenceStreamDestinationTable.createdByMemberId eq memberId }
                .count()
        val streamIds =
            ConferenceStreamTable
                .selectAll()
                .where { ConferenceStreamTable.startedByMemberId eq memberId }
                .map { it[ConferenceStreamTable.id] }
        val streamTargetCount =
            if (streamIds.isEmpty()) {
                0L
            } else {
                ConferenceStreamTargetTable
                    .selectAll()
                    .where { ConferenceStreamTargetTable.streamId inList streamIds }
                    .count()
            }
        val guestConsentAcknowledgmentCount =
            ConferenceGuestConsentAcknowledgmentTable
                .selectAll()
                .where { ConferenceGuestConsentAcknowledgmentTable.memberId eq memberId }
                .count()
        // V1.0 Videokonferenzen, Wave 6 "Breakout-Räume".
        val breakoutRoomsCreatedCount =
            ConferenceBreakoutRoomTable
                .selectAll()
                .where { ConferenceBreakoutRoomTable.createdByMemberId eq memberId }
                .count()
        val breakoutAssignmentCount =
            ConferenceBreakoutAssignmentTable
                .selectAll()
                .where { ConferenceBreakoutAssignmentTable.memberId eq memberId }
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
            TableErasureOutcome(
                table = "conference_stream_destination",
                rowsRetained = streamDestinationsCreatedCount.toInt(),
                retentionReason =
                    "Same accountability reasoning as conference_room's creator: an ADMIN's identity as " +
                        "the one who bound the organization's external publishing credential into the " +
                        "system is a GoBD-relevant governance fact, and erasing it would orphan every " +
                        "conference_stream_target row that ever streamed through this destination.",
            ),
            TableErasureOutcome(
                table = "conference_stream",
                rowsRetained = streamIds.size,
                retentionReason =
                    "Who started a public live stream, when, is the identical GoBD/section-32-BGB " +
                        "accountability fact conference_recording already establishes -- anonymizing " +
                        "the starter here would undermine that same trail.",
            ),
            TableErasureOutcome(
                table = "conference_stream_target",
                rowsRetained = streamTargetCount.toInt(),
                retentionReason =
                    "Part of the retained stream's own shared technical record, same treatment as conference_recording_track.",
            ),
            TableErasureOutcome(
                table = "conference_guest_consent_acknowledgment",
                rowsRetained = guestConsentAcknowledgmentCount.toInt(),
                retentionReason =
                    "The proof that a federated guest was shown and acknowledged this room's DSGVO " +
                        "consent text before joining is the organization's own Rechenschaftsnachweis " +
                        "under Art. 5(2)/7(1) DSGVO -- erasing it would destroy the very record that " +
                        "documents the lawfulness of processing this data subject's audio/video in " +
                        "that meeting.",
            ),
            TableErasureOutcome(
                table = "conference_breakout_room",
                rowsRetained = breakoutRoomsCreatedCount.toInt(),
                retentionReason =
                    "A breakout room's creator identity is a shared meeting record every assigned " +
                        "participant's own conference_breakout_assignment row depends on for context, " +
                        "same treatment conference_room's own creator receives.",
            ),
            TableErasureOutcome(
                table = "conference_breakout_assignment",
                rowsRetained = breakoutAssignmentCount.toInt(),
                retentionReason =
                    "An assignment record is part of the breakout room's shared roster history, same " +
                        "treatment conference_participation receives for its own shared join/leave record.",
            ),
        )
    }
}
