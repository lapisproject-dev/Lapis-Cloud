// V1.0 Videokonferenzen (Kleinsitzung), Wave 3 "Externes Streaming" -- see the concept document
// ("03 Bereiche/Lapis Cloud/Videokonferenzen.md", vault) for the full fachlich model this
// implements, and this repo's own CLAUDE.md "Standard-Implementierungs-Workflow" for the
// 4-Agenten-Pipeline this wave runs under. This file models exactly the three tables this wave
// adds -- a NEW table trio, not a widening of `conference_recording`/`conference_recording_track`
// (28-conference-recording.kuml.kts), same "business table carries fachlich detail, a later wave's
// own table carries its own concern" split 27-conference.kuml.kts's own header documents.
//
// **Everything in this file was verified against the real running LiveKit stack**
// (`livekit/livekit-server:v1.13.5` + `livekit/egress:v1.13.0`, Colima, 2026-08-09) with a
// `bluenviron/mediamtx` container as a real RTMP destination -- see the wave plan's own
// "scopeDecisions" document for the full raw-Twirp-JSON evidence. Three findings shaped THIS
// file's shape specifically:
//
// 1. **Track Egress (28-conference-recording.kuml.kts's model) cannot do RTMP** -- streaming needs
//    a *composited* request (`StartRoomCompositeEgress`/`StartParticipantEgress`), a fundamentally
//    different LiveKit call shape than recording's per-track egress. This is why streaming gets
//    its own table trio rather than reusing/widening `conference_recording_track`.
// 2. **Multi-destination is a single Start call with N URLs**, and `EgressInfo.stream_results`
//    comes back as a genuine per-URL array (status/error/retries/last_retry_at) -- hence
//    `conference_stream_target` is a real one-row-per-destination child table of
//    `conference_stream`, not a denormalized list column.
// 3. **LiveKit both REDACTS the stream key in every URL it echoes back** (`.../live/probekey1` ->
//    `.../live/{pro...ey1}`) **and reorders `stream_results` relative to the request** (sent
//    key1,key2 -> received key2,key1 in one live capture). Neither exact-URL nor index-based
//    matching can therefore associate a `StreamInfo` entry back to the `conference_stream_target`
//    row that started it -- this is the ENTIRE reason `url_fingerprint` exists on that table (see
//    its own attribute-level comment below for the exact mechanism): it is computed server-side at
//    start time by applying LiveKit's own redaction rule to the plaintext URL this codebase sent,
//    and `StreamPoller` (a later wave step) matches `StreamInfo.url` against THIS stored value,
//    never against the plaintext URL or the request's own array position.
//
// **Credential storage — this wave introduces this codebase's first at-rest encryption
// primitive.** A full grep of `lapis-server`/`lapis-shared` for
// `encrypt|Cipher|AES|GCM|SecretKeySpec|KeyGenerator|javax.crypto` before this wave turns up only
// HMAC (`TurnCredentialMinter`) and JWS signing (`LiveKitAccessToken`) -- neither is at-rest
// encryption. `conference_stream_destination.stream_key_ciphertext` is therefore the first column
// in this codebase whose plaintext is recovered via
// `network.lapis.cloud.server.crypto.SecretBox` (AES-256-GCM, see that object's own KDoc) rather
// than stored in the clear or merely signed. **The plaintext stream key is never returned to any
// RPC caller, ever, at any role, including immediately after it is saved** -- see
// `network.lapis.cloud.shared.domain.ConferenceStreamDestinationDto.streamKeyMask` (a later wave
// step) KDoc "the constant `********`, never a partial value".
//
// **FK-naming choice**: every member-/room-/destination-/stream-referencing FK in this file
// (`room_id`, `started_by_member_id`, `created_by_member_id`, `destination_id`, `stream_id`) is
// modelled as a plain «Column» UUID attribute with «Column».fkEntity, NEVER a UML association --
// same domain-wide policy 21-auction.kuml.kts's own header documents at length, already followed
// by 27-conference.kuml.kts and 28-conference-recording.kuml.kts.
//
// **This file carries minimal id-only Member/ConferenceRoom stubs** (owned by Foundation/this same
// Conference domain respectively), purely so `UmlToErmTransformer` can resolve this file's
// «Column».fkEntity overrides within this single-file evaluation -- same cross-domain-stub pattern
// 28-conference-recording.kuml.kts's own header explains at length. No Document stub is needed
// here (unlike 28-conference-recording.kuml.kts) -- streaming never produces a Dokumentenablage
// row; the RTMP output lives entirely on the external platform.
//
// **Liveness/lifecycle via an explicit status enum, NOT nullable timestamps alone** -- same
// reasoning 28-conference-recording.kuml.kts's own header gives for `conference_recording.status`.
// `conference_stream.status` is a real six-state machine (STARTING/LIVE/PAUSED/STOPPING/ENDED/
// FAILED); `started_at`/`paused_at`/`ended_at` are denormalized display timestamps, not the state
// machine's own source of truth. Unlike recording, streaming's `startStream` DOES call LiveKit
// SYNCHRONOUSLY (see `network.lapis.cloud.server.rpc.ConferenceStreamingService` KDoc, a later
// wave step, for the exact two-transaction ordering this implies) -- there is exactly one egress
// per stream, known up front, and a moderator needs immediate feedback on the obvious failures.
//
// **Pause is stop+restart on the SAME row, not two rows.** LiveKit has NO pause primitive
// (verified live: removing every output URL via `UpdateStream` drives the egress to
// `EGRESS_ENDING` -> `egress_complete`, it does not suspend). `pauseStream` = `StopEgress` +
// `status = PAUSED`, meeting untouched. `resumeStream` = a FRESH `Start...Egress` to the same
// destinations, a NEW `livekit_egress_id` written onto this SAME `conference_stream` row, and
// `restart_count` incremented -- never a new `conference_stream` row for a resume, which is why
// `livekit_egress_id` is nullable-then-overwritable rather than an immutable identity column.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "ConferenceStreaming") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors every other domain's own Member stub. Resolves
    // conference_stream_destination.created_by_member_id's and conference_stream
    // .started_by_member_id's «Column».fkEntity overrides within this single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Conference-domain-owned stub (27-conference.kuml.kts owns the real table) — id-only, same
    // cross-domain-stub pattern. Resolves conference_stream.room_id's «Column».fkEntity override.
    val conferenceRoom = classOf(name = "ConferenceRoom") {
        stereotype("Entity") { "tableName" to "conference_room"; "kotlinObjectName" to "ConferenceRoomTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order is load-bearing: ConferenceStreamSchemaDriftTest asserts ErmDataType.Enum
    // .values in exactly this order, matching
    // network.lapis.cloud.shared.domain.ConferenceStreamPlatform. Pure UX/validation metadata --
    // the server builds every destination's ingest URL identically regardless of this value, see
    // that Kotlin enum's own KDoc.
    val conferenceStreamPlatform = enumOf(name = "ConferenceStreamPlatform") {
        literal(name = "YOUTUBE")
        literal(name = "TWITCH")
        literal(name = "PEERTUBE")
        literal(name = "OWNCAST")
        literal(name = "GENERIC_RTMP")
    }

    // Literal order is load-bearing (see above), matching
    // network.lapis.cloud.shared.domain.ConferenceStreamLayout.
    val conferenceStreamLayout = enumOf(name = "ConferenceStreamLayout") {
        literal(name = "GRID")
        literal(name = "SPEAKER")
        literal(name = "SINGLE_PARTICIPANT")
    }

    // Literal order is load-bearing (see above), matching
    // network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode. STANDARD is the verified-
    // working preset; LOW_LATENCY's `advanced` encoding block is unverified as of this step -- see
    // that Kotlin enum's own KDoc for the go/no-go this implies for the eventual UI control.
    val conferenceStreamLatencyMode = enumOf(name = "ConferenceStreamLatencyMode") {
        literal(name = "LOW_LATENCY")
        literal(name = "STANDARD")
    }

    // Literal order is load-bearing (see above), matching
    // network.lapis.cloud.shared.domain.ConferenceStreamStatus. See file header "Liveness/
    // lifecycle via an explicit status enum" for why this is a real, authoritative state machine.
    val conferenceStreamStatus = enumOf(name = "ConferenceStreamStatus") {
        literal(name = "STARTING")
        literal(name = "LIVE")
        literal(name = "PAUSED")
        literal(name = "STOPPING")
        literal(name = "ENDED")
        literal(name = "FAILED")
    }

    // Literal order is load-bearing (see above), matching
    // network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus. Mirrors LiveKit's own
    // `livekit.StreamInfo.Status` (ACTIVE/FINISHED/FAILED) plus a PENDING pre-reconciliation state
    // this codebase adds for the gap between INSERT and the first StreamPoller tick.
    val conferenceStreamTargetStatus = enumOf(name = "ConferenceStreamTargetStatus") {
        literal(name = "PENDING")
        literal(name = "ACTIVE")
        literal(name = "FINISHED")
        literal(name = "FAILED")
    }

    val conferenceStreamDestination = classOf(name = "ConferenceStreamDestination") {
        stereotype("Entity") {
            "tableName" to "conference_stream_destination"
            "kotlinObjectName" to "ConferenceStreamDestinationTable"
        }
        // UNIQUE, single-column, class-level «Index» -- same idiom
        // conference_recording_track.egress_id already establishes in this domain, chosen over an
        // attribute-level "unique" flag for consistency with that immediate sibling file.
        stereotype("Index") {
            "columns" to listOf("label")
            "unique" to true
            "name" to "uq_conference_stream_destination_label"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Mandatory, unique — the ONLY human-facing way an ADMIN tells two destinations apart,
        // since the stream key is NEVER shown again after save (see file header "Credential
        // storage") and rtmpUrl alone can be identical across two channels on the same platform
        // (e.g. two YouTube channels both using the shared rtmp://a.rtmp.youtube.com/live2
        // endpoint, differing only in the key). VARCHAR(120) headroom, matching this codebase's
        // general "leave room on an operator-typed short identifier" convention.
        attribute(name = "label", type = "String") {
            stereotype("Column") { "columnName" to "label"; "sqlType" to "VARCHAR(120)" }
        }
        attribute(name = "platform", type = conferenceStreamPlatform) {
            stereotype("Column") {
                "columnName" to "platform"
                "enumType" to "network.lapis.cloud.shared.domain.ConferenceStreamPlatform"
            }
        }
        // The ingest BASE URL only (e.g. "rtmp://a.rtmp.youtube.com/live2") -- the stream key is
        // NEVER part of this string; the two are concatenated in-memory only at StartEgress time
        // (network.lapis.cloud.server.rpc.ConferenceStreamingService, a later wave step). Public
        // information for YouTube/Twitch, operator-owned for PeerTube/Owncast/generic RTMP.
        // VARCHAR(500) mirrors this codebase's general external-URL column width (e.g.
        // network.lapis.cloud.server.federation config columns).
        attribute(name = "rtmpUrl", type = "String") {
            stereotype("Column") { "columnName" to "rtmp_url"; "sqlType" to "VARCHAR(500)" }
        }
        // AES-256-GCM ciphertext, network.lapis.cloud.server.crypto.SecretBox's own versioned
        // `v1:<b64url iv>:<b64url ct||tag>` wire format -- see file header "Credential storage".
        // NEVER decrypted anywhere but network.lapis.cloud.server.rpc.ConferenceStreamingService
        // .startStream/resumeStream's own in-memory StartEgress call (a later wave step); NEVER
        // logged, NEVER placed in a DTO. VARCHAR(1024) headroom well beyond any realistic stream
        // key length once base64url-encoded with IV+tag overhead.
        attribute(name = "streamKeyCiphertext", type = "String") {
            stereotype("Column") { "columnName" to "stream_key_ciphertext"; "sqlType" to "VARCHAR(1024)" }
        }
        // Stamped on every createDestination/updateDestination(newStreamKey != null) call -- lets
        // an ADMIN answer "is this the key I rotated last week?" without ever seeing the key
        // itself, see network.lapis.cloud.shared.domain.ConferenceStreamDestinationDto (a later
        // wave step) KDoc.
        attribute(name = "streamKeySetAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "stream_key_set_at" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header
        // "FK-naming choice". Destination CRUD is ADMIN-only (see the wave's RPC contract), so this
        // is always an ADMIN's own member id, but the column itself carries no role constraint --
        // authorization is enforced at the RPC layer, never at the schema layer.
        attribute(name = "createdByMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        // Soft on/off without deleting stored credentials -- setDestinationEnabled (a later wave
        // step) toggles this; a disabled destination cannot be chosen by startStream but its
        // history in conference_stream_target rows (past streams that used it) is untouched.
        attribute(name = "enabled", type = "Boolean") {
            defaultValue = "TRUE"
            stereotype("Column") { "columnName" to "enabled" }
        }
    }

    val conferenceStream = classOf(name = "ConferenceStream") {
        stereotype("Entity") { "tableName" to "conference_stream"; "kotlinObjectName" to "ConferenceStreamTable" }
        stereotype("Index") { "columns" to listOf("room_id"); "name" to "idx_conference_stream_room" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_conference_stream_status" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> conference_room (id), NOT NULL. Plain «Column» UUID attribute -- see file
        // header. No "at most one active stream per room" constraint is expressed at the SQL layer
        // (same dialect-portability reasoning conference_recording.room_id's own comment gives) --
        // ConferenceStreamingService.startStream enforces it in-transaction with a `forUpdate()`
        // row lock on the room, the exact fix Wave 2's own security loop landed for recordings.
        attribute(name = "roomId", type = "UUID") {
            stereotype("Column") { "columnName" to "room_id"; "fkEntity" to "ConferenceRoom" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header.
        attribute(name = "startedByMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "started_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "status", type = conferenceStreamStatus) {
            stereotype("Column") {
                "columnName" to "status"
                "enumType" to "network.lapis.cloud.shared.domain.ConferenceStreamStatus"
            }
        }
        attribute(name = "layout", type = conferenceStreamLayout) {
            stereotype("Column") {
                "columnName" to "layout"
                "enumType" to "network.lapis.cloud.shared.domain.ConferenceStreamLayout"
            }
        }
        attribute(name = "latencyMode", type = conferenceStreamLatencyMode) {
            stereotype("Column") {
                "columnName" to "latency_mode"
                "enumType" to "network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode"
            }
        }
        // NULL unless layout == SINGLE_PARTICIPANT, in which case it is the LiveKit participant
        // identity (the target member's own UUID string, same convention
        // conference_recording_track.participant_identity establishes) StartParticipantEgress
        // composites. Kept as a plain String, NOT a «Column».fkEntity Member reference — same
        // "point-in-time LiveKit identity echo, not a live relationship" reasoning
        // conference_recording_track.participant_identity's own comment gives.
        attribute(name = "participantIdentity", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "participant_identity"; "sqlType" to "VARCHAR(64)" }
        }
        // LiveKit's own `EG_...` egress id for the CURRENTLY active attempt -- NULL only in the
        // brief window between the STARTING row's insert and the (outside-transaction) LiveKit
        // call succeeding, see file header "an explicit status enum". Unlike
        // conference_recording_track.egress_id, this is NOT unique — resumeStream overwrites it
        // with a fresh egress id on the SAME row (see file header "Pause is stop+restart on the
        // SAME row"), so the same conference_stream row legitimately carries several different
        // egress ids over its lifetime, one at a time.
        attribute(name = "livekitEgressId", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "livekit_egress_id"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "startedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "started_at" }
        }
        // NULL until pauseStream (StopEgress, see file header). Denormalized display timestamp,
        // NOT the state-machine source of truth -- see file header "Liveness/lifecycle".
        attribute(name = "pausedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "paused_at" }
        }
        // NULL until stopStream or StreamPoller's own auto-stop (max duration ceiling, or the
        // room's own ended_at going non-null) transitions the row to ENDED/FAILED.
        attribute(name = "endedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "ended_at" }
        }
        // Incremented on every resumeStream call -- see file header "Pause is stop+restart on the
        // SAME row". Zero for a stream that has never been paused/resumed.
        attribute(name = "restartCount", type = "Int") {
            defaultValue = "0"
            stereotype("Column") { "columnName" to "restart_count" }
        }
        // Fixed-vocabulary, sanitized German text ONLY -- never a raw Twirp error body. A real
        // LiveKit error observed live echoes the destination HOST back
        // ("Failed to connect: Error resolving “nonexistent-host-xyz”: Name or service
        // not known") -- this column, like conference_recording.failure_reason, is a SECURITY
        // boundary, not just a UX field. See
        // network.lapis.cloud.shared.domain.ConferenceStreamDto.failureReason (a later wave step)
        // KDoc for the fixed German vocabulary StreamPoller maps onto it.
        attribute(name = "failureReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "failure_reason"; "sqlType" to "VARCHAR(500)" }
        }
    }

    val conferenceStreamTarget = classOf(name = "ConferenceStreamTarget") {
        stereotype("Entity") {
            "tableName" to "conference_stream_target"
            "kotlinObjectName" to "ConferenceStreamTargetTable"
        }
        // Non-unique -- StreamPoller's own "list every target of this stream" reconciliation
        // query, same idiom conference_recording_track.recording_id's own class-level «Index»
        // establishes for the identical per-parent-lookup shape.
        stereotype("Index") { "columns" to listOf("stream_id"); "name" to "idx_conference_stream_target_stream" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> conference_stream (id), NOT NULL. Plain «Column» UUID attribute -- see file
        // header "FK-naming choice".
        attribute(name = "streamId", type = "UUID") {
            stereotype("Column") { "columnName" to "stream_id"; "fkEntity" to "ConferenceStream" }
        }
        // Real FK -> conference_stream_destination (id), NOT NULL. Plain «Column» UUID attribute --
        // see file header. Deliberately NOT cascade-deleted / NOT prevented from referencing a
        // later-disabled destination — a target row is a historical record of what THIS stream
        // actually streamed to, independent of the destination's current enabled state.
        attribute(name = "destinationId", type = "UUID") {
            stereotype("Column") { "columnName" to "destination_id"; "fkEntity" to "ConferenceStreamDestination" }
        }
        attribute(name = "status", type = conferenceStreamTargetStatus) {
            stereotype("Column") {
                "columnName" to "status"
                "enumType" to "network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus"
            }
        }
        // **Why this column exists at all**: LiveKit redacts the stream key in every URL it echoes
        // back in `EgressInfo.stream_results[].url` (`rtmp://host:1935/live/probekey1` comes back
        // as `rtmp://host:1935/live/{pro...ey1}` -- first 3 chars + "..." + last 3 chars of the key
        // segment, in braces, verified live for a 9-char key) AND reorders `stream_results`
        // relative to the request order (sent key1,key2 -> received key2,key1 in one live
        // capture). Neither exact-URL matching nor request-array-index matching can therefore
        // associate a `StreamInfo` entry back to the `conference_stream_target` row that started
        // it. The fix: at StartEgress time, ConferenceStreamingService (a later wave step)
        // computes this column by applying LiveKit's OWN redaction rule to the plaintext URL it is
        // about to send, and StreamPoller (a later wave step) matches every `StreamInfo.url` it
        // receives back against this stored, pre-computed value -- never against the plaintext URL
        // (which is never persisted in ANY column, see conference_stream_destination.rtmp_url/
        // .stream_key_ciphertext) and never against array position. The exact redaction format is
        // verified only for a 9-char key as of this step; a later wave step must re-verify it for
        // a short key (< 6 chars) and a long one, and fall back to "one egress per destination" if
        // the rule turns out not to be stable. VARCHAR(255) headroom well beyond any realistic
        // redacted-URL length.
        attribute(name = "urlFingerprint", type = "String") {
            stereotype("Column") { "columnName" to "url_fingerprint"; "sqlType" to "VARCHAR(255)" }
        }
        // NANOSECOND epoch timestamps -- deliberately NOT LocalDateTime, matching the raw
        // precision Egress's own `stream_results[].started_at`/`.ended_at` wire values carry (same
        // cross-endpoint inconsistency conference_recording_track.started_at_epoch_nanos's own
        // comment documents: ListRooms/ListParticipants use SECOND epoch strings, Egress uses
        // NANOSECOND epoch strings).
        attribute(name = "startedAtEpochNanos", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "started_at_epoch_nanos" }
        }
        attribute(name = "endedAtEpochNanos", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "ended_at_epoch_nanos" }
        }
        // Refreshed from `stream_results[].retries` on every StreamPoller tick (a later wave
        // step) -- LiveKit's own reconnect-attempt counter for this specific destination URL.
        attribute(name = "retries", type = "Int") {
            defaultValue = "0"
            stereotype("Column") { "columnName" to "retries" }
        }
        // Fixed-vocabulary, sanitized German text ONLY -- same security-boundary discipline
        // conference_stream.failure_reason's own comment documents, applied per-destination here
        // (one bad stream key must never leak its own destination host into this column).
        attribute(name = "failureReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "failure_reason"; "sqlType" to "VARCHAR(500)" }
        }
    }
}
