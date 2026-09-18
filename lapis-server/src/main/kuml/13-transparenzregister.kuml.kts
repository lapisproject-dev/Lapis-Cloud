// Transparenzregister domain (V0.5.2, §20 GwG beneficial-owner reminders) --
// board_membership/transparenzregister_reminder.
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified against
// both the real Flyway-migrated H2 schema and the hand-written Exposed Table objects
// (network.lapis.cloud.server.db.generated.BoardMembershipTable/TransparenzregisterReminderTable)
// by TransparenzregisterSchemaDriftTest. Per ADR-0016's designModelStrategy option B, this is a
// verification-only artifact for now: the hand-written Table objects remain the actually-
// compiled/actually-imported-by-N-files source. See docs/architecture/domain-model.adoc and the
// vault CLAUDE.md's kUML-Repo-Konventionen for the full rationale.
//
// NOT gated on isPoliticalParty (unlike V0.5.1's §25 PartG donation-duty check) -- §20 GwG applies
// to every Verein/Partei that has to report beneficial owners to the Transparenzregister, not just
// political parties. See IBoardMembershipService/BoardMembershipService KDoc.
//
// Reminder-only, no automated filing: this domain only records JOINED/LEFT events and lets a human
// acknowledge that they manually updated the real Transparenzregister (transparenzregister.de has
// no suitable public API, and this wave's own title is explicit about "automatische Erinnerung",
// not automated submission). resolveTransparenzregisterReminder is a manual acknowledgement, NOT
// a verification that the filing actually happened -- see TransparenzregisterReminderDto KDoc.
// The Meldefiktion exception (Vereinsregister-covered entities) is deliberately NOT modelled here,
// flagged for legal review instead -- see IBoardMembershipService KDoc.
//
// board_membership is a Transparenzregister-facing beneficial-owner roster, deliberately kept
// PARALLEL to (not a replacement of) committee_membership (05-governance.kuml.kts), which already
// seats the real Vorstand at ElectionService.tally time. Committee-agnostic (no committeeId
// column) -- the register only cares about person + role + dates, not which specific Committee.
//
// Cross-domain stub: minimal id-only Member (Foundation-owned) stub, same pattern as every prior
// domain's own Member stub, purely so UmlToErmTransformer can resolve this domain's real FK
// associations within this single-file evaluation.
//
// Enum re-declarations: CommitteeRole is reused from governance (05-governance.kuml.kts) --
// kUML has no cross-file model-import (confirmed finding, every prior domain re-declares shared
// enums the same way, e.g. 07-election.kuml.kts's own committeeRole). BoardChangeType is new to
// this domain.
//
// Two-member-FK collision workaround (transparenzregister_reminder has both member_id, the subject
// of the reminder, and resolved_by, the member who acknowledged it): same N-way collision family
// already documented in 05-governance.kuml.kts's meeting.called_by/chair_member_id/
// minute_taker_member_id -- the FIRST association declared for a (fkClass, refClass) pair always
// claims the bare "member_id" default regardless of its own role. Here member_id (the reminder's
// subject) is declared as the real UML association claiming that default, and resolved_by is
// modelled as a plain «Column» UUID attribute with «Column».fkEntity to avoid the collision. Its
// real FK existence/target/nullability is still independently pinned via
// TransparenzregisterSchemaDriftTest's information_schema introspection against the real migrated
// schema, exactly like every other plain-Column FK in this codebase.
//
// Two enum columns in this domain: board_membership.committee_role and
// transparenzregister_reminder.committee_role/change_type, all modelled with only a
// «Column».enumType tag and no «Column».sqlType override (post-87563ff convention, see
// 06-vote.kuml.kts/07-election.kuml.kts's own current attribute shape) -- the generator derives the
// VARCHAR width from the longest enum literal: CommitteeRole's longest literal
// (PRESS_SPOKESPERSON) is 18 characters (VARCHAR(20) chosen with a small buffer, matching the
// other three CommitteeRole columns in this codebase -- see committee_membership.role/
// election.target_role), BoardChangeType's longest literal (JOINED) is 6 characters.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Transparenzregister") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors the cross-domain-stub pattern established by
    // every prior domain's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Reused from governance (05-governance.kuml.kts) -- duplicated here since kUML has no
    // cross-file model-import (confirmed finding, every prior domain re-declares shared enums the
    // same way, e.g. 07-election.kuml.kts's own committeeRole).
    val committeeRole = enumOf(name = "CommitteeRole") {
        literal(name = "CHAIR")
        literal(name = "DEPUTY_CHAIR")
        literal(name = "SECRETARY")
        literal(name = "MEMBER")
        literal(name = "ASSESSOR")
        literal(name = "GENERAL_SECRETARY")
        literal(name = "PRESS_SPOKESPERSON")
        literal(name = "MANAGING_DIRECTOR")
    }

    val boardChangeType = enumOf(name = "BoardChangeType") {
        literal(name = "JOINED")
        literal(name = "LEFT")
    }

    val boardMembership = classOf(name = "BoardMembership") {
        stereotype("Entity") { "tableName" to "board_membership"; "kotlinObjectName" to "BoardMembershipTable" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_board_membership_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "committeeRole", type = committeeRole) {
            stereotype("Column") { "columnName" to "committee_role"; "enumType" to "network.lapis.cloud.shared.domain.CommitteeRole" }
        }
        attribute(name = "startedAt", type = "LocalDate") {
            stereotype("Column") { "columnName" to "started_at" }
        }
        attribute(name = "endedAt", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "ended_at" }
        }
    }

    // board_membership.member_id -> member (id): association-derived default matches (no
    // competing member-FK on this entity).
    association(source = member, target = boardMembership, id = "assoc-member-board-membership") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val transparenzregisterReminder = classOf(name = "TransparenzregisterReminder") {
        stereotype("Entity") {
            "tableName" to "transparenzregister_reminder"
            "kotlinObjectName" to "TransparenzregisterReminderTable"
        }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_transparenzregister_reminder_member" }
        stereotype("Index") { "columns" to listOf("resolved"); "name" to "idx_transparenzregister_reminder_resolved" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "triggeredAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "triggered_at" }
        }
        attribute(name = "committeeRole", type = committeeRole) {
            stereotype("Column") { "columnName" to "committee_role"; "enumType" to "network.lapis.cloud.shared.domain.CommitteeRole" }
        }
        attribute(name = "changeType", type = boardChangeType) {
            stereotype("Column") { "columnName" to "change_type"; "enumType" to "network.lapis.cloud.shared.domain.BoardChangeType" }
        }
        attribute(name = "resolved", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "resolved" }
        }
        attribute(name = "resolvedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "resolved_at" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute -- see file header
        // comment (two-member-FK collision: member_id already claims the bare association default
        // below, so this second FK is modelled as a plain column, same workaround as governance's
        // meeting.called_by family).
        attribute(name = "resolvedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "resolved_by"; "fkEntity" to "Member" }
        }
    }

    // transparenzregister_reminder.member_id -> member (id): association-derived default matches
    // (the FIRST association declared for this (entity, Member) pair -- safely claims the bare
    // default; resolved_by above is the second FK and is modelled as a plain column instead).
    association(source = member, target = transparenzregisterReminder, id = "assoc-member-transparenzregister-reminder") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }
}
