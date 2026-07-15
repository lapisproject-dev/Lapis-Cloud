// Election domain — election/election_candidacy/election_option/election_board_member/election_eligible_voter/
// election_participation/election_tally_approval/election_ballot/election_ballot_selection
// (V9__demokratische_electionen.sql). Second-largest domain (202 lines of hand-written
// network.lapis.cloud.server.db.tables.ElectionTables.kt) — one-person-one-vote elections/ballots for
// personnel (SINGLE_CHOICE/MULTI_CHOICE/LIST_VOTE/RANKED_CHOICE) and Ja/Nein (YES_NO) decisions,
// structurally distinct from vote (06-vote.kuml.kts's LTR-weighted eBay/Vickrey
// basket auction).
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified against
// both the real Flyway-migrated H2 schema and the hand-written Exposed Table objects
// (network.lapis.cloud.server.db.tables.ElectionTables.kt) by ElectionSchemaDriftTest. Per ADR-0016's
// designModelStrategy option B, this is a verification-only artifact for now: the hand-written
// Table objects remain the actually-compiled/actually-imported-by-N-files source. See
// docs/architecture/domain-model.adoc and CLAUDE.md's kUML-Repo-Konventionen (vault) for the full
// rationale (enum-to-VARCHAR type-fidelity gap, Kotlin-object-naming-override gap).
//
// Cross-domain stubs: minimal id-only Member (foundation-owned), Motion/Meeting/Committee/Resolution
// (governance-owned) stubs — same pattern as prior domains' stubs, purely so
// UmlToErmTransformer can resolve this domain's real FK associations within this single-file
// evaluation. Resolution is stubbed so election.resolution_id -> resolution can be declared as a real,
// clean forward «FK» association from this side — mirrors 06-vote.kuml.kts's own
// resolution stub/association exactly: 05-governance.kuml.kts's resolution entity already declares
// resolution.election_id as a plain nullable UUID «Column» attribute (not an association) because Election
// didn't exist in that domain's own script — see that file's header comment for the full
// cycle-avoidance rationale. This file closes the second leg of that same two-domain cycle, the
// clean forward direction (election -> resolution), with no cycle problem in this direction since
// Resolution already exists here as a stub.
//
// Table-declaration order in this script deliberately mirrors V9__demokratische_electionen.sql's own
// dependency-driven (not alphabetical) creation order: election_candidacy is declared before
// election_option, because election_option.candidacy_id references election_candidacy (the migration's own
// file header calls this out explicitly: "election_candidacy must exist before election_option is
// created"). For SQL DDL generation this ordering would matter (irrelevant here since V9's SQL
// stays untouched — Flyway migrations are immutable history); for Exposed/Kotlin object
// generation top-level objects can reference each other regardless of declaration order. Kept the
// same order anyway for readability/consistency with the SQL and the hand-written
// ElectionTables.kt's own declaration order.
//
// Ballot secrecy (privacy rationale, meaningful business rule — do NOT "fix" election_ballot.
// member_id's nullability, a naive schema reader might assume it should be NOT NULL like most
// other member_id FKs in this file): election_ballot.member_id is nullable and always NULL for a
// secret (secret) Election — there is no FK-joinable link back to election_participation (the "this member
// voted" proof) in that case. This is a practical DB-level table split, not cryptography — no
// homomorphic encryption/mix-net/threshold-signature scheme exists in this codebase. One-member-
// one-vote enforcement differs between the two ballot paths on purpose:
// - Non-secret (secret = false): UNIQUE (election_id, member_id) on election_ballot is the backstop
//   (composite unique — accepted gap, pinned below, same as prior domains' composite uniques).
// - Secret (secret = true): election_ballot.member_id is always NULL, so that table's own unique
//   constraint is a no-op there. election_participation's own UNIQUE (election_id, member_id) is the real
//   DB-level backstop for the secret path instead — see ElectionTable KDoc / ElectionService.castElectionBallot.
//
// FK-column-naming-mismatch fallbacks (same gap class already discovered in document/
// communication/dsgvo/governance/vote — association-derived default name
// snake_case(singular(targetClass))+"_id" doesn't match the real column name), modelled as plain
// «Column» UUID attributes instead of UML associations:
// - election.target_committee_id -> committee (id), nullable: default would be "committee_id".
// - election.opened_by -> member (id), NOT NULL: default would be "member_id".
// - election_option.candidacy_id -> election_candidacy (id), nullable: default would be
//   "election_candidacy_id".
// - election_ballot_selection.ballot_id -> election_ballot (id), NOT NULL: default would be
//   "election_ballot_id".
// - election_ballot_selection.option_id -> election_option (id), NOT NULL: default would be
//   "election_option_id".
// Their real FK existence/target/nullability is still independently pinned via
// ElectionSchemaDriftTest's information_schema introspection against the real migrated schema.
//
// FKs that DO match the association-derived default and are modelled as real UML associations:
// election.motion_id, election.meeting_id, election.resolution_id (forward direction, see above),
// election_candidacy.election_id/member_id, election_option.election_id, election_board_member.election_id/member_id,
// election_eligible_voter.election_id/member_id, election_participation.election_id/member_id,
// election_tally_approval.election_id/member_id, election_ballot.election_id/member_id. None of these entities has
// more than one competing member-FK, so — unlike governance's meeting (N=4) / dsgvo's
// erasure_request (N=3) — the first-declared-association-claims-the-bare-default mechanism never
// causes a collision problem here.
//
// ElectionType and ElectionStatus are this domain's own enum types, modelled with explicit
// «Column».sqlType overrides (VARCHAR(20)/VARCHAR(30) respectively) — same mechanism/rationale as
// every prior domain's enum columns (real V9 schema has plain VARCHAR columns, no CHECK
// constraints). CommitteeRole is reused from governance (05-governance.kuml.kts) for
// election.target_role — kUML has no cross-file model-import (confirmed by every prior domain's own
// enum re-declarations), so the enum literal set is duplicated here too, exactly like the
// entity-stub pattern for shared entities.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Election") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern established by
    // every prior domain's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the election.motion_id association can resolve.
    val motion = classOf(name = "Motion") {
        stereotype("Entity") { "tableName" to "motion"; "kotlinObjectName" to "MotionTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the election.meeting_id association can resolve.
    val meeting = classOf(name = "Meeting") {
        stereotype("Entity") { "tableName" to "meeting"; "kotlinObjectName" to "MeetingTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only. election.target_committee_id is a plain «Column» attribute (name
    // mismatch, see file header), so this stub isn't the target of any real association here —
    // kept anyway for documentation/consistency, mirroring 05-governance.kuml.kts's own
    // unused-today Document stub.
    val committee = classOf(name = "Committee") {
        stereotype("Entity") { "tableName" to "committee"; "kotlinObjectName" to "CommitteeTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the election.resolution_id association can resolve.
    // This is the clean forward direction of the election<->resolution cycle — see the file header
    // comment for why 05-governance.kuml.kts declares resolution.election_id as a plain column instead
    // of the (circular, at that point) association. Mirrors 06-vote.kuml.kts's own
    // resolution stub/association exactly.
    val resolution = classOf(name = "Resolution") {
        stereotype("Entity") { "tableName" to "resolution"; "kotlinObjectName" to "ResolutionTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val electionType = enumOf(name = "ElectionType") {
        literal(name = "YES_NO")
        literal(name = "SINGLE_CHOICE")
        literal(name = "MULTI_CHOICE")
        literal(name = "LIST_VOTE")
        literal(name = "RANKED_CHOICE")
    }

    val electionStatus = enumOf(name = "ElectionStatus") {
        literal(name = "PREPARATION")
        literal(name = "CANDIDATE_LIST_RELEASED")
        literal(name = "OPEN")
        literal(name = "CLOSED")
        literal(name = "TALLIED")
        literal(name = "ABORTED")
    }

    // Reused from governance (05-governance.kuml.kts) — duplicated here since kUML has no
    // cross-file model-import (confirmed finding, every prior domain re-declares shared enums the
    // same way).
    val committeeRole = enumOf(name = "CommitteeRole") {
        literal(name = "CHAIR")
        literal(name = "DEPUTY_CHAIR")
        literal(name = "SECRETARY")
        literal(name = "MEMBER")
        literal(name = "ASSESSOR")
    }

    val election = classOf(name = "Election") {
        stereotype("Entity") { "tableName" to "election"; "kotlinObjectName" to "ElectionTable" }
        stereotype("Index") { "columns" to listOf("motion_id"); "name" to "idx_election_motion" }
        stereotype("Index") { "columns" to listOf("meeting_id"); "name" to "idx_election_meeting" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_election_status" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "electionType", type = electionType) {
            stereotype("Column") { "columnName" to "election_type"; "enumType" to "network.lapis.cloud.shared.domain.ElectionType" }
        }
        attribute(name = "secret", type = "Boolean") {
            stereotype("Column") { "columnName" to "secret" }
        }
        attribute(name = "seatCount", type = "Int") {
            stereotype("Column") { "columnName" to "seat_count" }
        }
        // Real FK -> committee (id), nullable. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "committee_id", not the real schema's "target_committee_id".
        attribute(name = "targetCommitteeId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "target_committee_id"; "fkEntity" to "Committee" }
        }
        attribute(name = "targetRole", type = committeeRole) {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "target_role"; "enumType" to "network.lapis.cloud.shared.domain.CommitteeRole" }
        }
        attribute(name = "requiredMajorityPercent", type = "Int") {
            stereotype("Column") { "columnName" to "required_majority_percent" }
        }
        attribute(name = "status", type = electionStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.ElectionStatus" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "opened_by".
        attribute(name = "openedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "opened_by"; "fkEntity" to "Member" }
        }
        attribute(name = "openedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "opened_at" }
        }
        attribute(name = "candidateListApprovedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "candidate_list_approved_at" }
        }
        attribute(name = "votingOpenedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "voting_opened_at" }
        }
        attribute(name = "votingClosedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "voting_closed_at" }
        }
        attribute(name = "tallyThreshold", type = "Int") {
            stereotype("Column") { "columnName" to "tally_threshold" }
        }
        attribute(name = "tallyRunAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "tally_run_at" }
        }
    }

    // election.motion_id -> motion (id): association-derived default matches.
    association(source = motion, target = election, id = "assoc-motion-election") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "motionId" }
    }

    // election.meeting_id -> meeting (id): association-derived default matches.
    association(source = meeting, target = election, id = "assoc-meeting-election") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "meetingId" }
    }

    // election.resolution_id -> resolution (id): association-derived default matches. Nullable — the
    // clean forward direction of the election<->resolution cycle (see file header comment).
    association(source = resolution, target = election, id = "assoc-resolution-election") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "resolutionId" }
    }

    // election_candidacy must be declared before election_option (see file header comment) —
    // election_option.candidacy_id references it.
    val electionCandidacy = classOf(name = "ElectionCandidacy") {
        stereotype("Entity") { "tableName" to "election_candidacy"; "kotlinObjectName" to "ElectionCandidacyTable" }
        stereotype("Index") { "columns" to listOf("election_id"); "name" to "idx_election_candidacy_election" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_election_candidacy_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "motivationText", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "motivation_text"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "submittedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "submitted_at" }
        }
        attribute(name = "withdrawnAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "withdrawn_at" }
        }
    }

    // election_candidacy.election_id -> election (id): association-derived default matches.
    association(source = election, target = electionCandidacy, id = "assoc-election-candidacy") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "electionId" }
    }

    // election_candidacy.member_id -> member (id): association-derived default matches (no competing
    // member-FK on this entity).
    association(source = member, target = electionCandidacy, id = "assoc-member-election-candidacy") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val electionOption = classOf(name = "ElectionOption") {
        stereotype("Entity") { "tableName" to "election_option"; "kotlinObjectName" to "ElectionOptionTable" }
        stereotype("Index") { "columns" to listOf("election_id"); "name" to "idx_election_option_election" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "label", type = "String") {
            stereotype("Column") { "columnName" to "label"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "position", type = "Int") {
            stereotype("Column") { "columnName" to "position" }
        }
        // Real FK -> election_candidacy (id), nullable. Plain «Column» UUID attribute —
        // association-to-FK naming would derive "election_candidacy_id", not the real schema's
        // "candidacy_id".
        attribute(name = "candidacyId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "candidacy_id"; "fkEntity" to "ElectionCandidacy" }
        }
    }

    // election_option.election_id -> election (id): association-derived default matches.
    association(source = election, target = electionOption, id = "assoc-election-option") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "electionId" }
    }

    val electionBoardMember = classOf(name = "ElectionBoardMember") {
        stereotype("Entity") { "tableName" to "election_board_member"; "kotlinObjectName" to "ElectionBoardMemberTable" }
        stereotype("Index") {
            "columns" to listOf("election_id", "member_id")
            "unique" to true
            "name" to "uq_election_board_member_member"
        }
        stereotype("Index") { "columns" to listOf("election_id"); "name" to "idx_election_board_member_election" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "appointedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "appointed_at" }
        }
    }

    // election_board_member.election_id -> election (id): association-derived default matches.
    association(source = election, target = electionBoardMember, id = "assoc-election-board") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "electionId" }
    }

    // election_board_member.member_id -> member (id): association-derived default matches (no
    // competing member-FK on this entity).
    association(source = member, target = electionBoardMember, id = "assoc-member-election-board") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val electionElectionberechtigt = classOf(name = "ElectionEligibleVoter") {
        stereotype("Entity") { "tableName" to "election_eligible_voter"; "kotlinObjectName" to "ElectionEligibleVoterTable" }
        stereotype("Index") {
            "columns" to listOf("election_id", "member_id")
            "unique" to true
            "name" to "uq_election_eligible_voter_member"
        }
        stereotype("Index") { "columns" to listOf("election_id"); "name" to "idx_election_eligible_voter_election" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // election_eligible_voter.election_id -> election (id): association-derived default matches.
    association(source = election, target = electionElectionberechtigt, id = "assoc-election-electionberechtigt") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "electionId" }
    }

    // election_eligible_voter.member_id -> member (id): association-derived default matches (no
    // competing member-FK on this entity).
    association(source = member, target = electionElectionberechtigt, id = "assoc-member-election-electionberechtigt") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val electionParticipation = classOf(name = "ElectionParticipation") {
        stereotype("Entity") { "tableName" to "election_participation"; "kotlinObjectName" to "ElectionParticipationTable" }
        stereotype("Index") {
            "columns" to listOf("election_id", "member_id")
            "unique" to true
            "name" to "uq_election_participation_member"
        }
        stereotype("Index") { "columns" to listOf("election_id"); "name" to "idx_election_participation_election" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "votedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "voted_at" }
        }
    }

    // election_participation.election_id -> election (id): association-derived default matches.
    association(source = election, target = electionParticipation, id = "assoc-election-participation") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "electionId" }
    }

    // election_participation.member_id -> member (id): association-derived default matches (no competing
    // member-FK on this entity).
    association(source = member, target = electionParticipation, id = "assoc-member-election-participation") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val electionTallyApproval = classOf(name = "ElectionTallyApproval") {
        stereotype("Entity") { "tableName" to "election_tally_approval"; "kotlinObjectName" to "ElectionTallyApprovalTable" }
        stereotype("Index") {
            "columns" to listOf("election_id", "member_id")
            "unique" to true
            "name" to "uq_election_tally_approval_member"
        }
        stereotype("Index") { "columns" to listOf("election_id"); "name" to "idx_election_tally_approval_election" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "approvedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "approved_at" }
        }
    }

    // election_tally_approval.election_id -> election (id): association-derived default matches.
    association(source = election, target = electionTallyApproval, id = "assoc-election-tally-approval") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "electionId" }
    }

    // election_tally_approval.member_id -> member (id): association-derived default matches (no competing
    // member-FK on this entity).
    association(source = member, target = electionTallyApproval, id = "assoc-member-election-tally-approval") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    // Ballot secrecy: memberId is nullable and always NULL for a secret (secret) Election — see file
    // header comment for the full privacy rationale. This is a meaningful business rule a naive
    // schema reader could "fix" by mistake (most other member_id FKs in this domain are NOT NULL).
    val electionBallot = classOf(name = "ElectionBallot") {
        stereotype("Entity") { "tableName" to "election_ballot"; "kotlinObjectName" to "ElectionBallotTable" }
        stereotype("Index") {
            "columns" to listOf("election_id", "member_id")
            "unique" to true
            "name" to "uq_election_ballot_member"
        }
        stereotype("Index") { "columns" to listOf("election_id"); "name" to "idx_election_ballot_election" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "receiptCode", type = "String") {
            stereotype("Column") { "columnName" to "receipt_code"; "sqlType" to "VARCHAR(40)" }
        }
        attribute(name = "castAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "cast_at" }
        }
    }

    // election_ballot.election_id -> election (id): association-derived default matches.
    association(source = election, target = electionBallot, id = "assoc-election-ballot") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "electionId" }
    }

    // election_ballot.member_id -> member (id), nullable: association-derived default matches
    // (no competing member-FK on this entity). Nullable for ballot secrecy — see file header.
    association(source = member, target = electionBallot, id = "assoc-member-election-ballot") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val electionBallotSelection = classOf(name = "ElectionBallotSelection") {
        stereotype("Entity") { "tableName" to "election_ballot_selection"; "kotlinObjectName" to "ElectionBallotSelectionTable" }
        stereotype("Index") {
            "columns" to listOf("ballot_id")
            "name" to "idx_election_ballot_selection_ballot"
        }
        stereotype("Index") { "columns" to listOf("option_id"); "name" to "idx_election_ballot_selection_option" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> election_ballot (id), NOT NULL. Plain «Column» UUID attribute —
        // association-to-FK naming would derive "election_ballot_id", not the real schema's
        // "ballot_id".
        attribute(name = "ballotId", type = "UUID") {
            stereotype("Column") { "columnName" to "ballot_id"; "fkEntity" to "ElectionBallot" }
        }
        // Real FK -> election_option (id), NOT NULL. Plain «Column» UUID attribute —
        // association-to-FK naming would derive "election_option_id", not the real schema's
        // "option_id".
        attribute(name = "optionId", type = "UUID") {
            stereotype("Column") { "columnName" to "option_id"; "fkEntity" to "ElectionOption" }
        }
    }
}
