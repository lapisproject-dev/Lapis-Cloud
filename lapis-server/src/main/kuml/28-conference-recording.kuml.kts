// V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- see the concept document
// ("03 Bereiche/Lapis Cloud/Videokonferenzen.md", vault, the 2026-08-01 architecture decision, "Track
// Egress + asynchrone Komposition") for the full fachlich model this implements, and this repo's own
// CLAUDE.md "Standard-Implementierungs-Workflow" for the 4-Agenten-Pipeline this wave runs under.
// This file models exactly the two tables this wave adds -- see 27-conference.kuml.kts's own file
// header "Scope-cuts" for why these are a NEW table pair, not a widening of `conference_room`
// ("business table carries fachlich detail, a later wave's own table carries its own concern" split,
// same precedent 21-auction.kuml.kts's header documents for `ltr_ledger_entry`).
//
// **The single most consequential architectural fact this schema encodes**: LiveKit Track Egress
// does NOT transcode -- it exports ONE track per egress request, as-is. A meeting with N
// participants therefore needs N (or more, camera+mic+screen) `StartTrackEgress` calls, each
// producing its own raw file. `conference_recording_track` is the per-track bookkeeping this
// requires; `conference_recording` is the per-MEETING lifecycle record the eventual composed file
// (a `document`/`document_version` row, created once composition succeeds) hangs off of. See
// network.lapis.cloud.server.conference.LiveKitEgressClient KDoc for the LiveKit-side Twirp surface
// these two tables coordinate, and a later wave's `RecordingPoller`/`RecordingComposer` for how
// `RECORDING -> STOPPING -> PROCESSING -> READY`/`FAILED` actually advances (this wave's own
// `ConferenceRecordingService.startRecording`/`stopRecording` only ever write `RECORDING`/
// `STOPPING` -- the poller drives every later transition, see that class's own KDoc "startRecording
// only inserts the row").
//
// **Storage/access decision (see the wave plan's own "Storage and access" section for the full
// reasoning)**: the composed recording is stored as a regular `document`/`document_version` under
// the existing Dokumentenablage, NOT a bespoke byte-storage column here. `conference_recording`
// therefore owns only LIFECYCLE state (status/timestamps/failure) plus a nullable `document_id`
// filled in once composition succeeds -- access control is entirely `document.access_level`
// (`DocumentAccessLevel`, re-declared locally below, chosen by the moderator at `startRecording`
// time, defaulting to BOARD_ONLY) plus one additional "the recording's own starter can always see
// it" carve-out applied in code (`ConferenceRecordingAccess.mayAccess`), never a second access-
// control column on this table.
//
// **`raw_dir` is ALWAYS exactly the recording's own UUID, never operator or LiveKit input.** It
// exists as a persisted column (rather than deriving `{recordingId}` inline at every read site)
// purely so a future rename/relocation of the raw-storage layout has one place to change --
// `network.lapis.cloud.server.conference.RecordingRawFiles.resolveWithin` (a later wave) treats any
// OTHER path component (in particular `conference_recording_track.file_name`, which arrives from
// LiveKit's own `file_results[].filename` and must be treated as untrusted) as attacker-adjacent and
// resolves it strictly under `{hostRawRoot}/{raw_dir}/` -- see that function's own KDoc.
//
// **FK-naming choice**: every member-/room-/document-referencing FK in this file (`room_id`,
// `started_by_member_id`, `document_id`, `recording_id`) is modelled as a plain «Column» UUID
// attribute with «Column».fkEntity, NEVER a UML association -- same domain-wide policy
// 21-auction.kuml.kts's own header documents at length (and the V0.6.4
// `politician_reaction.rater_member_id` bug that policy exists to prevent), already followed by
// 27-conference.kuml.kts's own `created_by_member_id`/`room_id`/`member_id`.
//
// **This file carries minimal id-only Member/ConferenceRoom/Document stubs** (owned by Foundation/
// this same Conference domain/Document respectively), purely so `UmlToErmTransformer` can resolve
// this file's «Column».fkEntity overrides within this single-file evaluation -- same cross-domain-
// stub pattern every other domain file in this codebase already establishes (see
// 18-peer-transfer.kuml.kts's own header for the canonical explanation, and 05-governance.kuml.kts
// for the Document-stub precedent specifically).
//
// **Liveness/lifecycle via nullable timestamps plus an explicit status enum, NOT nullable-timestamps
// alone.** Unlike `conference_room.ended_at IS NULL` (a genuine two-state liveness flag,
// 27-conference.kuml.kts file header), `conference_recording.status` needs a real five-state machine
// (RECORDING/STOPPING/PROCESSING/READY/FAILED) that `stopped_at`/`ready_at` alone cannot express --
// e.g. a recording can be `STOPPING` with `stopped_at` already set but `ready_at` still NULL for a
// long time (waiting on track-egress finalization), which a pure-nullable-timestamp scheme could not
// distinguish from "still RECORDING, stop never requested". The explicit `status` column is therefore
// the authoritative state; `stopped_at`/`ready_at` are denormalized timestamps FOR DISPLAY, not the
// state machine's own source of truth (mirrors `journal_entry.status` + `posted_at` in
// 10-accounting.kuml.kts, the closest existing precedent for "explicit status enum plus a
// denormalized milestone timestamp").
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "ConferenceRecording") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors every other domain's own Member stub. Resolves
    // conference_recording.started_by_member_id's «Column».fkEntity override within this
    // single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Conference-domain-owned stub (27-conference.kuml.kts owns the real table) — id-only, same
    // cross-domain-stub pattern. Resolves conference_recording.room_id's «Column».fkEntity override.
    val conferenceRoom = classOf(name = "ConferenceRoom") {
        stereotype("Entity") { "tableName" to "conference_room"; "kotlinObjectName" to "ConferenceRoomTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Document-domain-owned stub (02-document.kuml.kts owns the real table) — id-only, same
    // pattern 05-governance.kuml.kts already establishes for meeting.protocol_document_id.
    // Resolves conference_recording.document_id's «Column».fkEntity override.
    val document = classOf(name = "Document") {
        stereotype("Entity") { "tableName" to "document"; "kotlinObjectName" to "DocumentTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Document-domain-owned enum (02-document.kuml.kts's own documentAccessLevel), re-declared
    // locally since kUML has no cross-file model-import mechanism — same re-declaration idiom
    // 14-audit-log.kuml.kts's own accountRole establishes. Literal order matches
    // network.lapis.cloud.shared.domain.DocumentAccessLevel exactly (this wave adds no new
    // literals to that enum, only reuses it).
    val documentAccessLevel = enumOf(name = "DocumentAccessLevel") {
        literal(name = "PUBLIC_MEMBERS")
        literal(name = "BOARD_ONLY")
        literal(name = "ADMIN_ONLY")
    }

    // Literal order is load-bearing: ConferenceRecordingSchemaDriftTest asserts ErmDataType.Enum
    // .values in exactly this order, matching
    // network.lapis.cloud.shared.domain.ConferenceRecordingStatus. See file header "Liveness/
    // lifecycle via nullable timestamps plus an explicit status enum" for why this is a real,
    // authoritative state machine, not a display-only convenience.
    val conferenceRecordingStatus = enumOf(name = "ConferenceRecordingStatus") {
        literal(name = "RECORDING")
        literal(name = "STOPPING")
        literal(name = "PROCESSING")
        literal(name = "READY")
        literal(name = "FAILED")
    }

    // Literal order is load-bearing (see above), matching
    // network.lapis.cloud.shared.domain.ConferenceRecordingTrackSource. Mirrors LiveKit's own
    // `livekit.proto` TrackSource enum (see network.lapis.cloud.server.conference.LiveKitTrackInfo
    // KDoc for the wire-verified/unverified split per literal).
    val conferenceRecordingTrackSource = enumOf(name = "ConferenceRecordingTrackSource") {
        literal(name = "CAMERA")
        literal(name = "MICROPHONE")
        literal(name = "SCREEN_SHARE")
        literal(name = "SCREEN_SHARE_AUDIO")
        literal(name = "UNKNOWN")
    }

    // Literal order is load-bearing (see above), matching
    // network.lapis.cloud.shared.domain.ConferenceRecordingTrackStatus. This wave's own
    // per-track state machine (independent of LiveKit's own `EgressStatus` wire strings, which
    // network.lapis.cloud.server.conference.LiveKitEgressInfo.status carries as a plain,
    // caller-interpreted String -- a later wave's RecordingPoller is the one place that maps
    // LiveKit's EGRESS_* wire values onto these five literals).
    val conferenceRecordingTrackStatus = enumOf(name = "ConferenceRecordingTrackStatus") {
        literal(name = "STARTING")
        literal(name = "ACTIVE")
        literal(name = "COMPLETE")
        literal(name = "FAILED")
        literal(name = "ABORTED")
    }

    val conferenceRecording = classOf(name = "ConferenceRecording") {
        stereotype("Entity") { "tableName" to "conference_recording"; "kotlinObjectName" to "ConferenceRecordingTable" }
        stereotype("Index") { "columns" to listOf("room_id"); "name" to "idx_conference_recording_room" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_conference_recording_status" }
        stereotype("Index") {
            "columns" to listOf("started_by_member_id")
            "name" to "idx_conference_recording_started_by"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> conference_room (id), NOT NULL. Plain «Column» UUID attribute -- see file
        // header "FK-naming choice". No `at most one active recording per room` constraint is
        // expressed at the SQL layer (a partial unique index on (room_id) WHERE status IN
        // ('RECORDING','STOPPING') would need dialect-specific syntax this schema generator does
        // not support) -- ConferenceRecordingService.startRecording enforces it in-transaction
        // instead, see that method's own KDoc.
        attribute(name = "roomId", type = "UUID") {
            stereotype("Column") { "columnName" to "room_id"; "fkEntity" to "ConferenceRoom" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header.
        attribute(name = "startedByMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "started_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "startedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "started_at" }
        }
        // NULL until stopRecording (or the poller's own auto-stop, see RecordingPoller KDoc, a
        // later wave) transitions RECORDING -> STOPPING. Denormalized display timestamp, NOT the
        // state-machine source of truth -- see file header.
        attribute(name = "stoppedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "stopped_at" }
        }
        // NULL until composition succeeds and status becomes READY. See file header.
        attribute(name = "readyAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "ready_at" }
        }
        attribute(name = "status", type = conferenceRecordingStatus) {
            stereotype("Column") {
                "columnName" to "status"
                "enumType" to "network.lapis.cloud.shared.domain.ConferenceRecordingStatus"
            }
        }
        // Chosen by the moderator at startRecording time (default BOARD_ONLY) -- see file header
        // "Storage/access decision". Re-declared locally as documentAccessLevel above; the actual
        // access CHECK this column enforces at read time is `document.access_level`'s value on the
        // eventual composed document, not this column directly once document_id is set -- this
        // column is the ORIGIN choice, kept even after document_id is filled so a FAILED recording
        // (which never gets a document row) still records what the moderator intended.
        attribute(name = "accessLevel", type = documentAccessLevel) {
            stereotype("Column") {
                "columnName" to "access_level"
                "enumType" to "network.lapis.cloud.shared.domain.DocumentAccessLevel"
            }
        }
        // NULL until composition succeeds -- the Dokumentenablage row backing the composed file.
        // Real FK -> document (id), nullable. Plain «Column» UUID attribute -- see file header.
        attribute(name = "documentId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "document_id"; "fkEntity" to "Document" }
        }
        // ALWAYS exactly this row's own `id` as a string -- see file header "raw_dir is ALWAYS
        // exactly the recording's own UUID". VARCHAR(64) headroom, not the exact 36-char UUID
        // length, matching this codebase's general "leave a little headroom on server-generated
        // identifier columns" convention (e.g. conference_room.livekit_room_name VARCHAR(64) for a
        // `lc-<uuid4>` value).
        attribute(name = "rawDir", type = "String") {
            stereotype("Column") { "columnName" to "raw_dir"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "durationSeconds", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "duration_seconds" }
        }
        attribute(name = "fileSizeBytes", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "file_size_bytes" }
        }
        // Fixed-vocabulary, sanitized German text ONLY -- never raw ffmpeg stderr, never a raw
        // Twirp error body. See network.lapis.cloud.shared.domain.ConferenceRecordingDto
        // .failureReason KDoc "a security boundary, not just a UX field" (this column is that
        // DTO field's persisted backing).
        attribute(name = "failureReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "failure_reason"; "sqlType" to "VARCHAR(500)" }
        }
        // Incremented before each compose attempt, capped at 2 by a later wave's RecordingPoller --
        // see that class's own KDoc "compose_attempts capped at 2" for why (a deterministically-
        // broken input must never loop forever).
        attribute(name = "composeAttempts", type = "Int") {
            defaultValue = "0"
            stereotype("Column") { "columnName" to "compose_attempts" }
        }
    }

    val conferenceRecordingTrack = classOf(name = "ConferenceRecordingTrack") {
        stereotype("Entity") {
            "tableName" to "conference_recording_track"
            "kotlinObjectName" to "ConferenceRecordingTrackTable"
        }
        stereotype("Index") {
            "columns" to listOf("recording_id")
            "name" to "idx_conference_recording_track_recording"
        }
        stereotype("Index") {
            "columns" to listOf("egress_id")
            "unique" to true
            "name" to "uq_conference_recording_track_egress_id"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> conference_recording (id), NOT NULL. Plain «Column» UUID attribute -- see
        // file header "FK-naming choice".
        attribute(name = "recordingId", type = "UUID") {
            stereotype("Column") { "columnName" to "recording_id"; "fkEntity" to "ConferenceRecording" }
        }
        // LiveKit's own `EG_...` id (network.lapis.cloud.server.conference.LiveKitEgressInfo
        // .egressId) -- UNIQUE, pinned via the class-level «Index» above, same "single-column
        // UNIQUE via named «Index»" idiom every other domain in this codebase uses (e.g.
        // ledger_account.account_number, audit_log_entry.sequence_number).
        attribute(name = "egressId", type = "String") {
            stereotype("Column") { "columnName" to "egress_id"; "sqlType" to "VARCHAR(64)" }
        }
        // LiveKit's own `TR_...` track id (network.lapis.cloud.server.conference.LiveKitTrackInfo
        // .sid) -- NOT unique on its own (the SAME track could in principle be the target of a
        // second StartTrackEgress after a first one FAILED/ABORTED and the poller retries a fresh
        // egress for it; egress_id, not livekit_track_id, is this table's real dedup key).
        attribute(name = "livekitTrackId", type = "String") {
            stereotype("Column") { "columnName" to "livekit_track_id"; "sqlType" to "VARCHAR(64)" }
        }
        // The member UUID string LiveKit carries as the participant's own `identity`/JWT `sub`
        // claim (network.lapis.cloud.server.conference.LiveKitAccessToken.mintParticipantToken
        // KDoc "identity ... always the member's UUID string") -- kept as a plain String, NOT a
        // «Column».fkEntity Member reference, because a track can legitimately outlive the
        // member row it names nothing about (this column is a point-in-time LiveKit identity
        // echo, not a live relationship this schema needs to enforce referentially).
        attribute(name = "participantIdentity", type = "String") {
            stereotype("Column") { "columnName" to "participant_identity"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "trackSource", type = conferenceRecordingTrackSource) {
            stereotype("Column") {
                "columnName" to "track_source"
                "enumType" to "network.lapis.cloud.shared.domain.ConferenceRecordingTrackSource"
            }
        }
        attribute(name = "status", type = conferenceRecordingTrackStatus) {
            stereotype("Column") {
                "columnName" to "status"
                "enumType" to "network.lapis.cloud.shared.domain.ConferenceRecordingTrackStatus"
            }
        }
        // NANOSECOND epoch timestamps -- deliberately NOT LocalDateTime, matching the raw
        // precision network.lapis.cloud.server.conference.LiveKitEgressInfo.startedAtEpochNanos/
        // .endedAtEpochNanos carry (see that class's own KDoc "a genuine cross-endpoint
        // inconsistency in LiveKit's own wire format" -- ListRooms/ListParticipants use SECOND
        // epoch strings, Egress uses NANOSECOND epoch strings). A later wave's RecordingComposer
        // needs the raw nanosecond precision to compute each input's own offset into the composed
        // timeline (t0 = min(started_at) across all tracks) -- converting to LocalDateTime here
        // would silently lose exactly the precision that offset computation needs.
        attribute(name = "startedAtEpochNanos", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "started_at_epoch_nanos" }
        }
        attribute(name = "endedAtEpochNanos", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "ended_at_epoch_nanos" }
        }
        // The REAL LiveKit-assigned filename, WITH extension (LiveKitEgressFileInfo.filename) --
        // NOT a caller-constructed path. See network.lapis.cloud.server.conference
        // .RecordingRawFiles (a later wave) KDoc for why this column is treated as attacker-
        // adjacent untrusted input once read back off this row, not just an opaque display string.
        attribute(name = "fileName", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "file_name"; "sqlType" to "VARCHAR(512)" }
        }
        attribute(name = "durationMs", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "duration_ms" }
        }
        attribute(name = "sizeBytes", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "size_bytes" }
        }
    }
}
