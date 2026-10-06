// Welle V1.9.61 "Begegnungsraum" (Welle B1, Server und Datenschutz) -- see
// network.lapis.cloud.server.rpc.EncounterSpaceService, network.lapis.cloud.server.encounter.* and
// docs/architecture/encounter-space.adoc for the full rationale (V73__encounter_space.sql).
//
// **Three new tables.**
//   * `encounter_space` -- the permanent configuration of a gathering room (title, theme, who may enter). A SESSION of a space is an
//     ordinary `conference_room` row carrying `encounter_space_id` (see 27-conference.kuml.kts); the session is "open" while that row
//     has `ended_at IS NULL`. At most one session per space is open at a time -- enforced by a row lock on the space, so this model has
//     deliberately NO `opened_at` / `current_room_id` column (it would need a circular foreign key).
//   * `encounter_space_role` -- the OFFICE configuration: who is PULPIT / STEWARD of a space. Never the congregation. Composite primary
//     key (space_id, member_id): one role per member per space.
//   * `encounter_consent_acknowledgment` -- accountability proof of a NON-member's explicit consent (Art. 9(2)(a), Art. 7(1) GDPR).
//     Composite primary key (member_id, consent_version), NO room/space reference and NO timestamp (only a DATE), so it can never be
//     read as an attendance record.
//
// **DSGVO / Art. 9** (attending a church service reveals religious belief): no lasting attendance trace. The `conference_participation`
// rows of an encounter session are deleted on leave and on session end; `conference_guest_consent_acknowledgment` is never written for
// one. `state` columns are plain VARCHAR strings (no Exposed enumerations), same as every status column of this schema family since
// V1.9.57; their CHECK constraints are plain SQL in V73, not expressible in the ERM profile.
//
// **V76 (Welle V1.9.67).** `profile` (CHURCH_SERVICE | ASSEMBLY) and `reaction_set` (canonical CSV, HAND always first) describe the ROOM,
// never a person. `theme_key` is frozen at 'CHURCH' (old cached clients still decode EncounterTheme); the profile is the source of truth.
//
// Cross-domain stub: minimal id-only `Member` (owned by `00-foundation.kuml.kts`), same single-file-evaluation pattern every later
// domain file's own header documents.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "EncounterSpace") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val encounterSpace = classOf(name = "EncounterSpace") {
        stereotype("Entity") { "tableName" to "encounter_space"; "kotlinObjectName" to "EncounterSpaceTable" }
        stereotype("Index") { "columns" to listOf("archived_at"); "name" to "idx_encounter_space_archived_at" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "description", type = "String") {
            stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(1000)" }
        }
        // CHURCH
        attribute(name = "themeKey", type = "String") {
            stereotype("Column") { "columnName" to "theme_key"; "sqlType" to "VARCHAR(16)" }
        }
        // SERVICE
        attribute(name = "mode", type = "String") {
            stereotype("Column") { "columnName" to "mode"; "sqlType" to "VARCHAR(16)" }
        }
        // CHURCH_SERVICE | ASSEMBLY (V76); theme_key above stays frozen at CHURCH for old cached clients
        attribute(name = "profile", type = "String") {
            stereotype("Column") { "columnName" to "profile"; "sqlType" to "VARCHAR(16)" }
        }
        // canonical CSV of the allowed reactions, HAND first (V76)
        attribute(name = "reactionSet", type = "String") {
            stereotype("Column") { "columnName" to "reaction_set"; "sqlType" to "VARCHAR(64)" }
        }
        // MEMBERS_ONLY | MEMBERS_AND_GUESTS
        attribute(name = "guestPolicy", type = "String") {
            stereotype("Column") { "columnName" to "guest_policy"; "sqlType" to "VARCHAR(18)" }
        }
        // NULL = use the conference default; the service clamps to ConferenceConfig.maxParticipants.
        attribute(name = "maxParticipants", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "max_participants" }
        }
        // Free text shown while the room is closed ("Gottesdienst beginnt um 10:15 Uhr"); no time field on purpose.
        attribute(name = "closedNotice", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "closed_notice"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdByMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "archivedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "archived_at" }
        }
    }

    val encounterSpaceRole = classOf(name = "EncounterSpaceRole") {
        stereotype("Entity") { "tableName" to "encounter_space_role"; "kotlinObjectName" to "EncounterSpaceRoleTable" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_encounter_space_role_member" }

        // Composite primary key (space_id, member_id).
        attribute(name = "spaceId", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "space_id"; "fkEntity" to "EncounterSpace" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        // PULPIT | STEWARD
        attribute(name = "role", type = "String") {
            stereotype("Column") { "columnName" to "role"; "sqlType" to "VARCHAR(8)" }
        }
    }

    val encounterConsentAcknowledgment = classOf(name = "EncounterConsentAcknowledgment") {
        stereotype("Entity") {
            "tableName" to "encounter_consent_acknowledgment"; "kotlinObjectName" to "EncounterConsentAcknowledgmentTable"
        }

        // Composite primary key (member_id, consent_version).
        attribute(name = "memberId", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "consentVersion", type = "String") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "consent_version"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "consentSha256", type = "String") {
            stereotype("Column") { "columnName" to "consent_sha256"; "sqlType" to "VARCHAR(64)" }
        }
        // A DATE (class D), never a timestamp: the table must not reveal WHEN during the day a service was attended.
        attribute(name = "acknowledgedOn", type = "LocalDate") {
            stereotype("Column") { "columnName" to "acknowledged_on" }
        }
    }
}
