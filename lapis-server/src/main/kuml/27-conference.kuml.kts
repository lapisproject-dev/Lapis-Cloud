// V1.0 Videokonferenzen (Kleinsitzung), Wave 1 -- see the concept document
// ("03 Bereiche/Lapis Cloud/Videokonferenzen.md", vault) for the full fachlich model this
// implements, and this repo's own CLAUDE.md "Standard-Implementierungs-Workflow" for the 4-Agenten-
// Pipeline this wave runs under. This file models exactly the two tables this server persists for
// the LiveKit-backed video-conference feature -- see network.lapis.cloud.server.conference.
// ConferenceConfig/LiveKitAccessToken/LiveKitAdminClient (already landed) for the LiveKit-side
// integration these two tables coordinate, and IConferenceService/ConferenceService (this wave) for
// the RPC surface that reads/writes them.
//
// **LiveKit itself owns no persistent state this server relies on** -- a `conference_room` row is
// the durable record of a room's existence/ownership/lifetime; `conference_room.livekit_room_name`
// (a server-generated `lc-<uuid4>`, NEVER derived from user text) is the only join key between this
// table and the LiveKit SFU's own in-memory room registry. LiveKit is treated as an external,
// ephemeral, potentially-restarted system -- see IConferenceService.listActiveRooms KDoc for the
// lazy reconciliation this asymmetry requires (a room LiveKit no longer knows about, past the empty-
// timeout grace, is closed here even though nothing told this server so via a webhook -- Wave 1
// deliberately has no webhook consumer, see the wave plan's own "Webhooks: not in Wave 1" section).
//
// **Two-tier role model, ENTIRELY derived from `conference_room.created_by_member_id`, not a
// separate authority table.** `conference_participation.role` is a per-JOIN snapshot (MODERATOR iff
// that participation's `member_id` equals the room's `created_by_member_id` at join time,
// PARTICIPANT otherwise) -- it is NOT the source of authorization truth for `endRoom`/
// `removeParticipant` (ConferenceService re-derives moderator-or-privileged status from
// `conference_room.created_by_member_id` plus `CurrentMember.isPrivileged` on every call, never
// trusts a persisted role column for an authorization decision -- same "recompute from source data,
// never trust a cached/persisted authorization flag" discipline this codebase applies everywhere
// else). The column exists purely as a legible per-join record (what role did this member hold
// WHEN they joined), consumed by `listParticipants` for display.
//
// **`conference_participation` is APPEND-ONLY per join, exactly like `session` (22-session.kuml.kts)
// -- NOT one row per (room, member) upserted in place.** A member who leaves and rejoins the same
// room gets a SECOND participation row, `left_at` on the first one now set. This mirrors
// `session.revoked_at`'s own "compute liveness from a nullable timestamp column, no separate status
// enum, multiple historical rows per subject are expected" idiom (see that file's own header) rather
// than `auction_bid`'s upsert-per-(parent,member) idiom -- a join/leave history is exactly the kind
// of repeated-event log `session` already established the precedent for, whereas `auction_bid`'s
// upsert shape exists ONLY because a bidder's CURRENT standing maximum is the one thing that number
// means (there is no fachlich value in a bid-raise history for auction settlement math). Here, by
// contrast, "how many times did X join/leave" is exactly what a future attendance-derivation wave
// (out of scope this wave, see IConferenceService KDoc "Out of scope") would want to read.
//
// **FK-naming choice**: EVERY member-referencing and room-referencing FK in this file
// (`created_by_member_id`, `room_id`, `member_id`) is modelled as a plain «Column» UUID attribute
// with «Column».fkEntity, NEVER a UML association -- same domain-wide policy 21-auction.kuml.kts's
// own header documents at length (and the V0.6.4 `politician_reaction.rater_member_id` bug that
// policy exists to prevent).
//
// **Liveness via nullable timestamps, no separate status enum** -- `conference_room.ended_at IS
// NULL` means the room is active, `conference_participation.left_at IS NULL` means that join is
// still open. Same idiom `22-session.kuml.kts` (`revoked_at`)/`18-peer-transfer.kuml.kts` already
// establish, chosen over a redundant status enum that could silently drift from the timestamp.
//
// **This file carries a minimal id-only Member stub** (owned by Foundation), purely so
// `UmlToErmTransformer` can resolve `created_by_member_id`/`member_id`'s «Column».fkEntity overrides
// within this single-file evaluation -- same cross-domain-stub pattern every other domain file in
// this codebase already establishes (see 18-peer-transfer.kuml.kts's own header for the canonical
// explanation).
//
// **Scope-cuts (deliberate, documented here so a reviewer does not mistake these for gaps -- see
// IConferenceService KDoc "Out of scope" for the authoritative, exhaustive list)**:
//  - No chat persistence table -- Wave-1 chat rides the LiveKit data channel only (ephemeral,
//    unpersisted, dies with the room), never reaches this server's database at all. See
//    IConferenceService KDoc.
//  - No webhook-delivery-log table (unlike `federation_inbox_delivery_log`/
//    `oidc_guest_login_event`) -- Wave 1 has no webhook consumer to log deliveries for.
//  - No recording/egress columns of any kind on `conference_room` -- Track Egress is a later wave's
//    own, separate table(s), not a widening of this one (same "business table carries fachlich
//    detail, a later wave's own table carries its own concern" split 21-auction.kuml.kts's header
//    documents for `ltr_ledger_entry`).
//
// **Wave 5 "Föderations-Gastbeitritt" addition**: `conference_room.allowFederationGuests`
// (default `false`) -- the per-room opt-in that gates `MemberStatus.GAST` out of `joinRoom` unless
// its creator/moderator explicitly enables it. Every room created before this wave (and every room
// created by the D1 one-click flow, which never sets it) is guest-CLOSED by default -- loosening
// `joinRoom` without this column would have made every existing and future room guest-joinable
// with zero creator consent. Set at creation time via `ConferenceRoomInput.allowFederationGuests`
// (client never sets it -- see IConferenceService KDoc) and toggled while the room is active via
// `IConferenceService.setRoomGuestAccess` (same `requireModeratorOrPrivileged` gate `endRoom`/
// `removeParticipant`/`renameRoom` already use). See `30-conference-guest-access.kuml.kts` for the
// companion per-join DSGVO consent-acknowledgment table this wave also adds.
//
// **Wave 9 "Stream-Pause bei geheimen Abstimmungen" addition**: `conference_room.meeting_id`
// (nullable, NOT unique -- a room's own lifetime is independent of the meeting it is bound to, and
// several rooms MAY legitimately carry the same meeting, e.g. a fresh room created after a crash) --
// the ONE FK linking this Wave-1-standalone domain to `05-governance.kuml.kts`'s Meeting (the
// "Termin -> Konferenzraum" coupling the old Scope-cuts note above used to defer). Set from INSIDE a
// running room by a moderator via `IConferenceService.setRoomMeeting`, exactly the `setRoomGuestAccess`
// precedent this same header already documents -- never at `createRoom` time. See
// `network.lapis.cloud.server.rpc.SecretBallotStreamLock` (server, this same wave) KDoc for the
// derived query this FK makes possible; this file itself carries no Election/SystemicConsensus
// knowledge beyond the bare `meeting` id stub below.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Conference") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors every other domain's own Member stub. Resolves
    // created_by_member_id/member_id's «Column».fkEntity overrides within this single-file
    // evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Foundation-owned stub — id-only, added Wave 9 "Stream-Pause bei geheimen Abstimmungen" purely
    // so UmlToErmTransformer can resolve conference_room.meeting_id's «Column».fkEntity override
    // within this single-file evaluation -- same cross-domain-stub pattern the Member stub above
    // already establishes. See file header "Wave 9 addition".
    val meeting = classOf(name = "Meeting") {
        stereotype("Entity") { "tableName" to "meeting"; "kotlinObjectName" to "MeetingTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order is load-bearing: ConferenceSchemaDriftTest (a future step of this wave) asserts
    // ErmDataType.Enum.values in exactly this order, matching
    // network.lapis.cloud.shared.domain.ConferenceRole.
    val conferenceRole = enumOf(name = "ConferenceRole") {
        literal(name = "MODERATOR")
        literal(name = "PARTICIPANT")
    }

    val conferenceRoom = classOf(name = "ConferenceRoom") {
        stereotype("Entity") { "tableName" to "conference_room"; "kotlinObjectName" to "ConferenceRoomTable" }
        stereotype("Index") { "columns" to listOf("created_by_member_id"); "name" to "idx_conference_room_created_by" }
        stereotype("Index") { "columns" to listOf("ended_at"); "name" to "idx_conference_room_ended_at" }
        // Wave 9 "Stream-Pause bei geheimen Abstimmungen" addition -- see file header "Wave 9
        // addition".
        stereotype("Index") { "columns" to listOf("meeting_id"); "name" to "idx_conference_room_meeting" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(200)" }
        }
        // Default "" (never NULL) -- IConferenceService.createRoom populates this server-side
        // (D1: "one button, no form", see the Wave 1 design review) with no user-facing description
        // field in Wave 1's UI, but the column stays a plain NOT NULL VARCHAR for forward
        // compatibility, same "schema has more fields than the UI exposes yet" judgement call the
        // design review's own Rams note makes explicit.
        attribute(name = "description", type = "String") {
            stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(1000)" }
        }
        // The ONLY join key to LiveKit's own in-memory room registry -- server-generated
        // `lc-<uuid4>`, NEVER derived from `title` or any other user-supplied text. See file header
        // "LiveKit itself owns no persistent state".
        attribute(name = "livekitRoomName", type = "String") {
            stereotype("Column") { "columnName" to "livekit_room_name"; "sqlType" to "VARCHAR(64)"; "unique" to true }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header
        // "FK-naming choice". The single source of truth for the MODERATOR/PARTICIPANT authority
        // split -- see file header "Two-tier role model".
        attribute(name = "createdByMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        // NULL while the room is active -- set either by an explicit endRoom (moderator/BOARD/
        // ADMIN) or by listActiveRooms' lazy reconciliation once LiveKit no longer knows the room
        // and the empty-timeout grace has passed. See file header "LiveKit itself owns no
        // persistent state" and IConferenceService.listActiveRooms KDoc.
        attribute(name = "endedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "ended_at" }
        }
        // Wave-1 "Kleinsitzung" ceiling, copied onto the row at creation time from
        // ConferenceConfig.maxParticipants (itself defaulting to 25, see that class's own KDoc) --
        // denormalized here (rather than only ever existing as server config) so a row created under
        // one configured ceiling keeps ITS OWN ceiling even if an operator later changes
        // LAPIS_CONFERENCE_MAX_PARTICIPANTS, same "row remembers the value that applied when it was
        // created" reasoning `auction.listing_fee_ltr` already establishes for its own flat fee.
        attribute(name = "maxParticipants", type = "Int") {
            stereotype("Column") { "columnName" to "max_participants" }
        }
        // V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt" -- the per-room opt-in that
        // gates MemberStatus.GAST out of joinRoom. Defaults to FALSE so every room created before
        // this wave (and every room created by the D1 one-click flow) is guest-CLOSED unless its
        // creator/moderator explicitly opts in -- loosening joinRoom without this column would
        // have made every existing and future room guest-joinable with zero creator consent. Set
        // at creation time via ConferenceRoomInput.allowFederationGuests and toggled while the
        // room is active via IConferenceService.setRoomGuestAccess (same
        // requireModeratorOrPrivileged gate endRoom/removeParticipant/renameRoom already use). See
        // file header "Wave 5 addition".
        attribute(name = "allowFederationGuests", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "allow_federation_guests" }
        }
        // V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" addition -- see
        // file header "Wave 9 addition". NULL means this room is not bound to any Sitzung (the
        // default for every existing and newly-created room). Deliberately NOT unique -- several
        // rooms MAY carry the same meeting (e.g. a fresh room created after a crash), and all
        // rooms bound to a meeting are treated identically by
        // network.lapis.cloud.server.rpc.SecretBallotStreamLock. Set from INSIDE a running room by
        // a moderator via IConferenceService.setRoomMeeting -- never at createRoom time, same "one
        // button, no form" D1 posture allowFederationGuests's own comment documents.
        attribute(name = "meetingId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "meeting_id"; "fkEntity" to "Meeting" }
        }
    }

    val conferenceParticipation = classOf(name = "ConferenceParticipation") {
        stereotype("Entity") {
            "tableName" to "conference_participation"
            "kotlinObjectName" to "ConferenceParticipationTable"
        }
        stereotype("Index") { "columns" to listOf("room_id"); "name" to "idx_conference_participation_room" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_conference_participation_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> conference_room (id), NOT NULL. Plain «Column» UUID attribute -- see file
        // header "FK-naming choice".
        attribute(name = "roomId", type = "UUID") {
            stereotype("Column") { "columnName" to "room_id"; "fkEntity" to "ConferenceRoom" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header.
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        // Per-join snapshot, NOT the authorization source of truth -- see file header "Two-tier
        // role model".
        attribute(name = "role", type = conferenceRole) {
            stereotype("Column") { "columnName" to "role"; "enumType" to "network.lapis.cloud.shared.domain.ConferenceRole" }
        }
        attribute(name = "joinedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "joined_at" }
        }
        // NULL while this join is still open -- see file header "conference_participation is
        // APPEND-ONLY per join".
        attribute(name = "leftAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "left_at" }
        }
    }
}
