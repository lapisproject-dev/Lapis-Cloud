// Governance domain — committee/committee_membership/meeting/agendaItem/attendance/
// resolution/motion (V6__governance.sql + V7__motionsverwaltung.sql; the resolution.resolution_mode/
// vote_id/election_id columns added by V8__meritokratische_voteen.sql /
// V9__demokratische_electionen.sql are also modelled here since they live on this domain's own
// `resolution` table — see the resolution entity's own comments below for why vote_id/
// election_id are plain columns, not associations).
//
// vote/vote_option/vote_ballot (V8) and election/* (V9) are OUT of scope for this
// file — they are later waves (06-vote.kuml.kts / 07-election.kuml.kts per the retrofit plan's
// per-domain file layout) even though they happen to live in the same hand-written
// GovernanceTables.kt file today (option B means the hand-written .kt file's own internal
// organisation is decoupled from this retrofit's per-domain .kuml.kts split).
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified
// against both the real Flyway-migrated H2 schema and the hand-written Exposed Table objects
// (network.lapis.cloud.server.db.tables.GovernanceTables.kt) by GovernanceSchemaDriftTest. Per
// ADR-0016's designModelStrategy option B, this is a verification-only artifact for now: the
// hand-written Table objects remain the actually-compiled/actually-imported-by-N-files source.
// See docs/architecture/domain-model.adoc and CLAUDE.md's kUML-Repo-Konventionen (vault) for the
// full rationale (enum-to-VARCHAR type-fidelity gap, Kotlin-object-naming-override gap).
//
// Cross-domain stubs: minimal id-only Member (Foundation-owned) and Document (Document-domain-
// owned) stubs, same pattern as prior domains' Member stubs — purely so UmlToErmTransformer can
// resolve this domain's real FK columns within this single-file evaluation. protocol_document_id
// (name mismatch vs. the association-derived default) is pinned via «Column».fkEntity against
// this Document stub rather than a UML association — see meeting's own comment below.
//
// N-way multi-role-FK-collision finding (this is the domain the retrofit plan's risk note was
// written for): meeting has FOUR independent FKs to member (called_by/chair_member_id/
// minute_taker_member_id/presenter_member_id — the last one is actually on agendaItem,
// not meeting, but is the same collision family). Empirically verified with a standalone
// reproduction script (four associations from one class to Member, each with a distinct role)
// against this exact kUML version before writing this file: UmlToErmTransformer's collision
// mechanism (`fkEntity.hasAttributeNamed(defaultBaseName)` in addForeignKey) does scale past
// N=2 — the 2nd/3rd/4th associations processed DO correctly disambiguate via their own role names
// once the plain "member_id" default already exists as an attribute. BUT the FIRST association
// processed for a given (fkClass, refClass) pair never collides (nothing named "member_id" exists
// yet at that point) and therefore always claims the bare "member_id" default regardless of its
// own role — confirmed empirically: with roles calledBy/chairMember/minuteTakerMember/
// presenterMember declared in that order, the resulting FK columns were
// ["member_id", "chair_member_id", "minute_taker_member_id", "presenter_member_id"], not
// ["called_by_id", ...]. Since none of the four real column names in this domain happen to equal
// the bare "member_id" default, every single one of them (not just 3 of 4) is modelled as a plain
// «Column» UUID attribute rather than a UML association, per the retrofit plan's own risk-note
// fallback strategy. Their real FK existence/target/nullability is still independently pinned via
// GovernanceSchemaDriftTest's information_schema introspection against the real migrated schema.
//
// Several further FK columns across this domain also fail to match the association-derived
// default name (same naming-gap class already discovered in document/communication/dsgvo) and are
// likewise modelled as plain «Column» UUID attributes: attendance.represented_by_member_id,
// resolution.recorded_by, motion.target_committee_id (default would be "committee_id", not
// "target_committee_id"), motion.submitter_member_id, motion.reviewed_by.
//
// FKs that DO match the association-derived default and are modelled as real UML associations:
// committee_membership.committee_id/member_id, meeting.committee_id, agendaItem.meeting_id,
// attendance.meeting_id/member_id (the first-declared member_id association — see attendance's
// own comment for why this one is safe despite the multi-FK-to-member pattern), resolution.
// meeting_id, motion.meeting_id/agenda_item_id/resolution_id.
//
// resolution.vote_id / resolution.election_id / resolution.systemic_consensus_id (added by V8/V9/V0.2.5
// respectively): modelled as plain nullable UUID «Column» attributes, NOT UML associations,
// because Vote/Election/SystemicConsensus entities don't exist in this domain's own script —
// exactly the same forward-reference-breaks-the-cycle workaround already used for
// document.current_version_id in the document wave. The vote/election/systemic_consensus domains'
// OWN scripts (06-vote.kuml.kts / 07-election.kuml.kts / 09-systemic-consensus.kuml.kts, later
// waves) declare the real «FK» association from their side instead (Vote.resolutionId ->
// Resolution, Election.resolutionId -> Resolution, SystemicConsensus.resolutionId -> Resolution), which is a
// clean forward reference with no cycle problem in that direction since Resolution already exists
// (as a stub) by then. Left as plain columns rather than pinned via «Column».fkEntity for the
// same reason as document.current_version_id: the real risk is genuine bidirectional Kotlin
// `object`-initializer circularity at the Exposed layer (resolution <-> vote, resolution <->
// election and resolution <-> systemic_consensus are all truly bidirectional), which «Column».fkEntity's
// later-pass resolution sidesteps at the script-evaluation level but not at the generated-Kotlin
// level. Consequence: the generated SQL/Flyway baseline also lacks these three FK constraints
// (present in the pre-swap hand-written V6/V8/V9 migrations, for the first two) — a deliberate,
// pre-existing trade-off, not a new regression.
//
// Eight enum columns in this domain, all modelled with only an «Column».enumType tag and no
// «Column».sqlType override (post-87563ff convention — see 06-vote.kuml.kts/
// 07-election.kuml.kts's own current attribute shape): committee.type, committee_membership.role,
// meeting.format, meeting.status, attendance.status, resolution.status, resolution.resolution_mode
// (V0.2.5 adds the ResolutionMode.SYSTEMIC_CONSENSUS literal, widening the generated VARCHAR
// column to fit its 20 characters — the widening is automatic, derived from the longest literal,
// no manual override needed), motion.status.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Governance") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern established by
    // contribution's/document's/communication's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Document-domain-owned stub — id-only, mirrors the Member stub above. Only exists here so
    // UmlToErmTransformer can resolve meeting.protocol_document_id's «Column».fkEntity target.
    val document = classOf(name = "Document") {
        stereotype("Entity") { "tableName" to "document"; "kotlinObjectName" to "DocumentTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val committeeType = enumOf(name = "CommitteeType") {
        literal(name = "EXECUTIVE_BOARD")
        literal(name = "WORKING_GROUP")
        literal(name = "COMMISSION")
        literal(name = "OTHER")
        literal(name = "GENERAL_ASSEMBLY")
    }

    val committeeRole = enumOf(name = "CommitteeRole") {
        literal(name = "CHAIR")
        literal(name = "DEPUTY_CHAIR")
        literal(name = "SECRETARY")
        literal(name = "MEMBER")
        literal(name = "ASSESSOR")
    }

    val meetingsFormat = enumOf(name = "MeetingFormat") {
        literal(name = "IN_PERSON")
        literal(name = "ONLINE")
        literal(name = "HYBRID")
    }

    val meetingsStatus = enumOf(name = "MeetingStatus") {
        literal(name = "PLANNED")
        literal(name = "HELD")
        literal(name = "CANCELLED")
    }

    val attendanceStatus = enumOf(name = "AttendanceStatus") {
        literal(name = "PRESENT")
        literal(name = "EXCUSED")
        literal(name = "UNEXCUSED")
        literal(name = "REPRESENTED")
    }

    val resolutionStatus = enumOf(name = "ResolutionStatus") {
        literal(name = "ADOPTED")
        literal(name = "REJECTED")
        literal(name = "POSTPONED")
    }

    val resolutionMode = enumOf(name = "ResolutionMode") {
        literal(name = "COMMITTEE_QUORUM")
        literal(name = "MERITOCRATIC")
        literal(name = "DEMOCRATIC")
        literal(name = "SYSTEMIC_CONSENSUS")
    }

    val motionStatus = enumOf(name = "MotionStatus") {
        literal(name = "SUBMITTED")
        literal(name = "REVIEWED")
        literal(name = "REJECTED_PRELIMINARY")
        literal(name = "SCHEDULED")
        literal(name = "RESOLVED")
        literal(name = "REJECTED")
        literal(name = "POSTPONED")
        literal(name = "WITHDRAWN")
    }

    val committee = classOf(name = "Committee") {
        stereotype("Entity") { "tableName" to "committee"; "kotlinObjectName" to "CommitteeTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "type", type = committeeType) {
            stereotype("Column") { "columnName" to "type"; "enumType" to "network.lapis.cloud.shared.domain.CommitteeType" }
        }
        attribute(name = "description", type = "String") {
            stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "active", type = "Boolean") {
            defaultValue = "TRUE"
            stereotype("Column") { "columnName" to "active" }
        }
        attribute(name = "quorumPercent", type = "Int") {
            defaultValue = "50"
            stereotype("Column") { "columnName" to "quorum_percent" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }

    val committeeMembership = classOf(name = "CommitteeMembership") {
        stereotype("Entity") { "tableName" to "committee_membership"; "kotlinObjectName" to "CommitteeMembershipTable" }
        stereotype("Index") { "columns" to listOf("committee_id"); "name" to "idx_committee_membership_committee" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_committee_membership_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "role", type = committeeRole) {
            stereotype("Column") { "columnName" to "role"; "enumType" to "network.lapis.cloud.shared.domain.CommitteeRole" }
        }
        attribute(name = "since", type = "LocalDate") {
            stereotype("Column") { "columnName" to "since" }
        }
        attribute(name = "until", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "until" }
        }
    }

    // committee_membership.committee_id -> committee (id): association-derived default matches.
    association(source = committee, target = committeeMembership, id = "assoc-committee-membership") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "committeeId" }
    }

    // committee_membership.member_id -> member (id): association-derived default matches.
    association(source = member, target = committeeMembership, id = "assoc-member-committee-membership") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val meeting = classOf(name = "Meeting") {
        stereotype("Entity") { "tableName" to "meeting"; "kotlinObjectName" to "MeetingTable" }
        stereotype("Index") { "columns" to listOf("committee_id"); "name" to "idx_meeting_committee" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "scheduledAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "scheduled_at" }
        }
        attribute(name = "location", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "location"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "format", type = meetingsFormat) {
            stereotype("Column") { "columnName" to "format"; "enumType" to "network.lapis.cloud.shared.domain.MeetingFormat" }
        }
        attribute(name = "status", type = meetingsStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.MeetingStatus" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute — see the file header
        // comment (N=4 multi-role-FK-collision case; the FIRST association processed for a given
        // (fkClass, refClass) pair always claims the bare "member_id" default regardless of its
        // own role, which does not match the real "called_by" column).
        attribute(name = "calledBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "called_by"; "fkEntity" to "Member" }
        }
        attribute(name = "calledAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "called_at" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute — same N=4 collision
        // family as calledBy above.
        attribute(name = "chairMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "chair_member_id"; "fkEntity" to "Member" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute — same N=4 collision
        // family as calledBy above.
        attribute(name = "minuteTakerMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "minute_taker_member_id"; "fkEntity" to "Member" }
        }
        // Real FK -> document (id), nullable. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "document_id", not the real schema's "protocol_document_id" (same
        // naming-gap class as document/communication/dsgvo's own mismatched FK columns).
        attribute(name = "protocolDocumentId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "protocol_document_id"; "fkEntity" to "Document" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }

    // meeting.committee_id -> committee (id): association-derived default matches.
    association(source = committee, target = meeting, id = "assoc-committee-meeting") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "committeeId" }
    }

    val agendaItem = classOf(name = "AgendaItem") {
        stereotype("Entity") { "tableName" to "agenda_item"; "kotlinObjectName" to "AgendaItemTable" }
        stereotype("Index") {
            "columns" to listOf("meeting_id", "position")
            "unique" to true
            "name" to "uq_agenda_item_position"
        }
        stereotype("Index") { "columns" to listOf("meeting_id"); "name" to "idx_agenda_item_meeting" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "position", type = "Int") {
            stereotype("Column") { "columnName" to "position" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        // Widened from VARCHAR(1000) to VARCHAR(4000) by V7__motionsverwaltung.sql.
        attribute(name = "description", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(4000)" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute — same naming-gap class
        // as meeting's member-referencing columns (default would be "member_id", not
        // "presenter_member_id").
        attribute(name = "presenterMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "presenter_member_id"; "fkEntity" to "Member" }
        }
    }

    // agendaItem.meeting_id -> meeting (id): association-derived default matches.
    association(source = meeting, target = agendaItem, id = "assoc-meeting-agenda_item") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "meetingId" }
    }

    val attendance = classOf(name = "Attendance") {
        stereotype("Entity") { "tableName" to "attendance"; "kotlinObjectName" to "AttendanceTable" }
        stereotype("Index") {
            "columns" to listOf("meeting_id", "member_id")
            "unique" to true
            "name" to "uq_attendance_member"
        }
        stereotype("Index") { "columns" to listOf("meeting_id"); "name" to "idx_attendance_meeting" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "status", type = attendanceStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.AttendanceStatus" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id" (already claimed by the memberId association below),
        // not the real schema's "represented_by_member_id".
        attribute(name = "representedByMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "represented_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "note", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "note"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "recordedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "recorded_at" }
        }
    }

    // attendance.meeting_id -> meeting (id): association-derived default matches.
    association(source = meeting, target = attendance, id = "assoc-meeting-attendance") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "meetingId" }
    }

    // attendance.member_id -> member (id): association-derived default matches. Safe despite
    // this entity ALSO having represented_by_member_id, because that second FK is modelled as a
    // plain «Column» attribute (see above), never as a competing association — so there is no
    // ordering-dependent collision to worry about here, unlike meeting's four-way case.
    association(source = member, target = attendance, id = "assoc-member-attendance") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val resolution = classOf(name = "Resolution") {
        stereotype("Entity") { "tableName" to "resolution"; "kotlinObjectName" to "ResolutionTable" }
        stereotype("Index") { "columns" to listOf("meeting_id"); "name" to "idx_resolution_meeting" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "number", type = "String") {
            stereotype("Column") { "columnName" to "number"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "text", type = "String") {
            stereotype("Column") { "columnName" to "text"; "sqlType" to "VARCHAR(4000)" }
        }
        attribute(name = "votesYes", type = "Int") {
            stereotype("Column") { "columnName" to "votes_yes" }
        }
        attribute(name = "votesNo", type = "Int") {
            stereotype("Column") { "columnName" to "votes_no" }
        }
        attribute(name = "votesAbstain", type = "Int") {
            stereotype("Column") { "columnName" to "votes_abstain" }
        }
        attribute(name = "quorumMet", type = "Boolean") {
            stereotype("Column") { "columnName" to "quorum_met" }
        }
        attribute(name = "status", type = resolutionStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.ResolutionStatus" }
        }
        attribute(name = "decidedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "decided_at" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "recorded_by".
        attribute(name = "recordedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "recorded_by"; "fkEntity" to "Member" }
        }
        attribute(name = "resolutionMode", type = resolutionMode) {
            defaultValue = "COMMITTEE_QUORUM"
            stereotype("Column") { "columnName" to "resolution_mode"; "enumType" to "network.lapis.cloud.shared.domain.ResolutionMode" }
        }
        // Forward reference into the (not-yet-modelled-in-this-file) vote domain — plain
        // nullable UUID «Column» attribute, NOT a UML association, exactly like
        // document.current_version_id's circular-reference workaround in the document wave. The
        // vote domain's own script declares the real «FK» association from its side
        // instead (Vote.resolutionId -> Resolution), a clean forward reference since Resolution
        // already exists (as a stub) by then. See the file header comment.
        attribute(name = "voteId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "vote_id" }
        }
        // Same forward-reference workaround as voteId above, but into the election domain.
        attribute(name = "electionId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "election_id" }
        }
        // Same forward-reference workaround as voteId/electionId above, but into the
        // systemic_consensus domain (V0.2.5, 09-systemic-consensus.kuml.kts).
        attribute(name = "systemicConsensusId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "systemic_consensus_id" }
        }
    }

    // resolution.meeting_id -> meeting (id): association-derived default matches.
    association(source = meeting, target = resolution, id = "assoc-meeting-resolution") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "meetingId" }
    }

    // resolution.agenda_item_id -> agendaItem (id): association-derived default
    // matches. Nullable on the resolution side (0..1 target multiplicity in
    // UmlToErmTransformer's role-based FK-nullability derivation).
    association(source = agendaItem, target = resolution, id = "assoc-agenda_item-resolution") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "agendaItemId" }
    }

    val motion = classOf(name = "Motion") {
        stereotype("Entity") { "tableName" to "motion"; "kotlinObjectName" to "MotionTable" }
        stereotype("Index") { "columns" to listOf("target_committee_id"); "name" to "idx_motion_target_committee" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_motion_status" }
        stereotype("Index") { "columns" to listOf("submitter_member_id"); "name" to "idx_motion_submitter" }
        stereotype("Index") { "columns" to listOf("meeting_id"); "name" to "idx_motion_meeting" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> committee (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "committee_id", not the real schema's "target_committee_id".
        attribute(name = "targetCommitteeId", type = "UUID") {
            stereotype("Column") { "columnName" to "target_committee_id"; "fkEntity" to "Committee" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "rationale", type = "String") {
            stereotype("Column") { "columnName" to "rationale"; "sqlType" to "VARCHAR(4000)" }
        }
        attribute(name = "text", type = "String") {
            stereotype("Column") { "columnName" to "text"; "sqlType" to "VARCHAR(4000)" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "submitter_member_id".
        attribute(name = "submitterMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "submitter_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "status", type = motionStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.MotionStatus" }
        }
        attribute(name = "submittedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "submitted_at" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "reviewed_by".
        attribute(name = "reviewedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reviewed_by"; "fkEntity" to "Member" }
        }
        attribute(name = "reviewedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reviewed_at" }
        }
        attribute(name = "reviewNote", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "review_note"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "withdrawnAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "withdrawn_at" }
        }
    }

    // motion.meeting_id -> meeting (id): association-derived default matches. Nullable.
    association(source = meeting, target = motion, id = "assoc-meeting-motion") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "meetingId" }
    }

    // motion.agenda_item_id -> agendaItem (id): association-derived default
    // matches. Nullable.
    association(source = agendaItem, target = motion, id = "assoc-agenda_item-motion") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "agendaItemId" }
    }

    // motion.resolution_id -> resolution (id): association-derived default matches. Nullable.
    association(source = resolution, target = motion, id = "assoc-resolution-motion") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "resolutionId" }
    }
}
