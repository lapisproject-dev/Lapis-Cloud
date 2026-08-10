// V1.0 Videokonferenzen (Kleinsitzung), Wave 6 "Breakout-Räume" -- see the concept document
// ("03 Bereiche/Lapis Cloud/Videokonferenzen.md", vault) for the fachlich requirement and
// IConferenceBreakoutService KDoc for the full authorization matrix and design decisions this
// schema implements. A breakout room is a REAL, separate LiveKit room (own `livekit_room_name`,
// same `lc-<uuid4>`-shaped generation `27-conference.kuml.kts`'s own `conference_room` establishes,
// own prefix `lc-bo-` for log/debug legibility) that exists only for the lifetime of one "batch" --
// at most ONE open batch (`closed_at IS NULL`) per `parent_room_id` at a time, enforced in
// `ConferenceBreakoutService.createBreakoutRooms`, never at the DB level (same "authorization/
// invariant enforcement lives in the service layer, not a DB constraint" posture every other
// Conference table already takes). This means "the currently active breakout rooms for room X" is
// always just `WHERE parent_room_id = X AND closed_at IS NULL` -- no separate "batch"/"session"
// grouping table needed, and a moderator's "alle zurückholen" always means "close every open
// breakout room for this parent," unambiguously.
//
// **Deliberately NOT a clone of `conference_room`.** `conference_breakout_room` omits `description`,
// `allow_federation_guests` (a breakout room has no independent guest-access toggle -- it inherits
// the parent's), and any embedded moderator concept. Every one of those concepts stays anchored to
// the parent `conference_room` -- a second full `conference_room`-shaped row would imply an
// independent lifecycle/authority that doesn't exist and would tempt future code to (wrongly) call
// `IConferenceService.endRoom`/`renameRoom`/`setRoomGuestAccess` against a breakout room id. A
// dedicated, deliberately smaller table makes the "breakout rooms are not first-class meetings"
// boundary a schema fact, not a convention someone has to remember.
//
// **`conference_breakout_assignment` is APPEND-ONLY per assignment**, exactly like
// `conference_participation` (`joined_at`/`left_at`) and `session` (`revoked_at`) -- see
// `27-conference.kuml.kts` file header "Liveness via nullable timestamps". A member reassigned from
// breakout room A to B gets `assigned_at = ...` on a NEW row for B, `recalled_at` stamped on the OLD
// row for A (never upserted in place) -- this keeps a legible per-member breakout history for the
// lifetime of one meeting. `recalled_at` is stamped by THREE distinct paths:
//  (a) `ConferenceBreakoutService.recallAll` (moderator-initiated, all rows of the batch),
//  (b) `ConferenceBreakoutService.returnToMainRoom` (self-service, the caller's own row only --
//      mirrors `leaveRoom`'s "closes only the caller's own" no-IDOR pattern), and
//  (c) `ConferenceBreakoutCoordinator.closeAllBreakoutRoomsForRoom` (parent `conference_room` ended
//      without an explicit recall first -- see `IConferenceService.endRoom`'s Wave 6 addition).
//
// **The authorization-critical query** ("does member X hold an OPEN assignment to breakout room Y")
// is covered by `idx_conference_breakout_assignment_room` combined with the `recalled_at IS NULL`
// predicate -- no separate composite index needed at Kleinsitzung scale (<=25 participants per room,
// <=20 breakout rooms per batch, see `ConferenceBreakoutService.MAX_BREAKOUT_ROOMS`).
//
// **FK-naming choice**: every member-/room-/breakout-room-referencing FK in this file
// (`parent_room_id`, `created_by_member_id`, `breakout_room_id`, `member_id`) is modelled as a plain
// «Column» UUID attribute with «Column».fkEntity, NEVER a UML association -- same domain-wide policy
// `21-auction.kuml.kts`'s own header documents at length, already followed throughout
// `27/28/29/30-conference*.kuml.kts`.
//
// **No new `AuditEntityType` literal.** Breakout create/assign/recall is ephemeral live-meeting
// stagecraft, not a governance/financial/cross-org-trust fact -- matches the existing precedent that
// `endRoom`/`removeParticipant`/`renameRoom` are also unaudited (only Wave 5's
// `setRoomGuestAccess`, a cross-org trust decision, is audited). See `IConferenceBreakoutService`
// KDoc for this deliberate, precedent-matching non-decision.
//
// **This file carries minimal id-only Member/ConferenceRoom stubs** (owned by Foundation/this same
// Conference domain respectively), purely so `UmlToErmTransformer` can resolve this file's
// «Column».fkEntity overrides within this single-file evaluation -- same cross-domain-stub pattern
// `28/29/30-conference*.kuml.kts` already establish.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "ConferenceBreakout") {
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

    // Conference-domain-owned stub (27-conference.kuml.kts owns the real table) — id-only, same
    // cross-domain-stub pattern 28/29/30 already establish. Resolves
    // conference_breakout_room.parent_room_id's «Column».fkEntity override.
    val conferenceRoom = classOf(name = "ConferenceRoom") {
        stereotype("Entity") { "tableName" to "conference_room"; "kotlinObjectName" to "ConferenceRoomTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val conferenceBreakoutRoom = classOf(name = "ConferenceBreakoutRoom") {
        stereotype("Entity") {
            "tableName" to "conference_breakout_room"
            "kotlinObjectName" to "ConferenceBreakoutRoomTable"
        }
        stereotype("Index") { "columns" to listOf("parent_room_id"); "name" to "idx_conference_breakout_room_parent" }
        stereotype("Index") { "columns" to listOf("closed_at"); "name" to "idx_conference_breakout_room_closed_at" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> conference_room (id), NOT NULL. Plain «Column» UUID attribute -- see file
        // header "FK-naming choice".
        attribute(name = "parentRoomId", type = "UUID") {
            stereotype("Column") { "columnName" to "parent_room_id"; "fkEntity" to "ConferenceRoom" }
        }
        // Moderator-chosen or auto-generated ("Breakout-Raum N") label -- see
        // ConferenceBreakoutPlanInput.roomLabels KDoc.
        attribute(name = "label", type = "String") {
            stereotype("Column") { "columnName" to "label"; "sqlType" to "VARCHAR(120)" }
        }
        // The ONLY join key to LiveKit's own in-memory room registry for THIS breakout room --
        // server-generated `lc-bo-<uuid4>`, NEVER derived from `label` or any other user-supplied
        // text. See file header.
        attribute(name = "livekitRoomName", type = "String") {
            stereotype("Column") { "columnName" to "livekit_room_name"; "sqlType" to "VARCHAR(64)"; "unique" to true }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header.
        attribute(name = "createdByMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        // NULL while this breakout room's batch is still open -- see file header "at most ONE open
        // batch per parent_room_id at a time".
        attribute(name = "closedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "closed_at" }
        }
    }

    val conferenceBreakoutAssignment = classOf(name = "ConferenceBreakoutAssignment") {
        stereotype("Entity") {
            "tableName" to "conference_breakout_assignment"
            "kotlinObjectName" to "ConferenceBreakoutAssignmentTable"
        }
        stereotype("Index") { "columns" to listOf("breakout_room_id"); "name" to "idx_conference_breakout_assignment_room" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_conference_breakout_assignment_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> conference_breakout_room (id), NOT NULL. Plain «Column» UUID attribute -- see
        // file header "FK-naming choice".
        attribute(name = "breakoutRoomId", type = "UUID") {
            stereotype("Column") { "columnName" to "breakout_room_id"; "fkEntity" to "ConferenceBreakoutRoom" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header.
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "assignedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "assigned_at" }
        }
        // NULL while this assignment is still open -- see file header
        // "conference_breakout_assignment is APPEND-ONLY per assignment".
        attribute(name = "recalledAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "recalled_at" }
        }
    }
}
