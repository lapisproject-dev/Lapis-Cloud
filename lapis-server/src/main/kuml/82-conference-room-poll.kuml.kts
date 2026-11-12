// Welle V1.9.304 "Raum-Umfragen" (A) -- see network.lapis.cloud.server.conference.roompoll / network.lapis.cloud.server.rpc.ConferenceRoomPollService,
// V153__conference_room_poll.sql, docs/features/room-polls.adoc and docs/architecture/room-polls.adoc for the full rationale.
//
// Four tables: `conference_room_poll` (the poll; NO actor column, the actor of start/end is in the audit log), `conference_room_poll_option`,
// `conference_room_poll_voted` (WHO answered: personal data, deleted when the poll ends) and `conference_room_poll_answer` (WHAT was answered: NO
// member id, NO time, random id). The last two are NEVER joined (RoomPollAnswerAnonymityTripwireTest).
//
// Enum-like columns (`counting_mode`, `status`, `close_reason`, `voter_group`) are Strings here with the width in `sqlType`; the generated Exposed
// objects map them to the shared enums by hand-edited column types in the same style as the other domains. CHECK constraints (lifecycle coherence,
// lengths, the multiple-of-5 snapshot, the guest weight rule) and the audit CHECK widening are plain SQL in V153. `open_room_id` is a plain UUID
// column: a NULL-able copy of `room_id` that is set only while the poll is OPEN; its UNIQUE index is "at most one open poll per room".
// Cross-domain stubs (id-only): ConferenceRoom and Member, owned by their own model files.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "ConferenceRoomPoll") {
    applyProfile(ermMappingProfile)

    classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    classOf(name = "ConferenceRoom") {
        stereotype("Entity") { "tableName" to "conference_room"; "kotlinObjectName" to "ConferenceRoomTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    classOf(name = "ConferenceRoomPoll") {
        stereotype("Entity") { "tableName" to "conference_room_poll"; "kotlinObjectName" to "ConferenceRoomPollTable" }
        stereotype("Index") { "columns" to listOf("open_room_id"); "name" to "uq_conference_room_poll_open_room" }
        stereotype("Index") { "columns" to listOf("room_id", "started_at"); "name" to "idx_conference_room_poll_room_started" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "roomId", type = "UUID") {
            stereotype("Column") { "columnName" to "room_id"; "fkEntity" to "ConferenceRoom" }
        }
        attribute(name = "openRoomId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "open_room_id" }
        }
        attribute(name = "question", type = "String") {
            stereotype("Column") { "columnName" to "question"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "countingMode", type = "String") {
            stereotype("Column") { "columnName" to "counting_mode"; "sqlType" to "VARCHAR(10)" }
        }
        attribute(name = "status", type = "String") {
            stereotype("Column") { "columnName" to "status"; "sqlType" to "VARCHAR(6)" }
        }
        // Class A (UTC).
        attribute(name = "startedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "started_at" }
        }
        attribute(name = "closedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "closed_at" }
        }
        attribute(name = "closeReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "close_reason"; "sqlType" to "VARCHAR(10)" }
        }
        attribute(name = "stateVersion", type = "Long") {
            stereotype("Column") { "columnName" to "state_version" }
        }
        attribute(name = "snapshotAnswerCount", type = "Int") {
            stereotype("Column") { "columnName" to "snapshot_answer_count" }
        }
        attribute(name = "snapshotJson", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "snapshot_json"; "sqlType" to "VARCHAR(8000)" }
        }
        attribute(name = "resultJson", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "result_json"; "sqlType" to "VARCHAR(8000)" }
        }
    }

    classOf(name = "ConferenceRoomPollOption") {
        stereotype("Entity") { "tableName" to "conference_room_poll_option"; "kotlinObjectName" to "ConferenceRoomPollOptionTable" }
        stereotype("Index") { "columns" to listOf("poll_id", "position"); "name" to "uq_conference_room_poll_option_position" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "pollId", type = "UUID") {
            stereotype("Column") { "columnName" to "poll_id"; "fkEntity" to "ConferenceRoomPoll" }
        }
        attribute(name = "position", type = "Int") {
            stereotype("Column") { "columnName" to "position" }
        }
        attribute(name = "text", type = "String") {
            stereotype("Column") { "columnName" to "text"; "sqlType" to "VARCHAR(80)" }
        }
    }

    classOf(name = "ConferenceRoomPollVoted") {
        stereotype("Entity") { "tableName" to "conference_room_poll_voted"; "kotlinObjectName" to "ConferenceRoomPollVotedTable" }
        stereotype("Index") { "columns" to listOf("poll_id", "member_id"); "name" to "uq_conference_room_poll_voted" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "pollId", type = "UUID") {
            stereotype("Column") { "columnName" to "poll_id"; "fkEntity" to "ConferenceRoomPoll" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
    }

    classOf(name = "ConferenceRoomPollAnswer") {
        stereotype("Entity") { "tableName" to "conference_room_poll_answer"; "kotlinObjectName" to "ConferenceRoomPollAnswerTable" }
        stereotype("Index") { "columns" to listOf("poll_id"); "name" to "idx_conference_room_poll_answer_poll" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "pollId", type = "UUID") {
            stereotype("Column") { "columnName" to "poll_id"; "fkEntity" to "ConferenceRoomPoll" }
        }
        attribute(name = "optionId", type = "UUID") {
            stereotype("Column") { "columnName" to "option_id"; "fkEntity" to "ConferenceRoomPollOption" }
        }
        attribute(name = "voterGroup", type = "String") {
            stereotype("Column") { "columnName" to "voter_group"; "sqlType" to "VARCHAR(18)" }
        }
        attribute(name = "weightLtr", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "weight_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
    }
}
