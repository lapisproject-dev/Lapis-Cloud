// Systemic Consensus domain (SK, V0.2.5) — systemicConsensus/systemic_consensus_option/
// systemic_consensus_eligible_voter/systemic_consensus_participation/systemic_consensus_ballot/
// systemic_consensus_resistance (generated into V1__baseline.sql alongside every other domain — see
// 87563ff, which replaced the 9 hand-written per-domain migrations with one generated baseline).
// Third, orthogonal counting logic hung off the same Motion/Meeting/resolution book governance
// spine as 06-vote.kuml.kts (LTR-weighted eBay/Vickrey basket auction) and
// 07-election.kuml.kts (one-person-one-vote elections): here the winner is the option with the
// LOWEST cumulative resistance (cumulative resistance / CR), not the highest stake or vote
// count. See `network.lapis.cloud.server.rpc.SystemicConsensusService` KDoc for the full lifecycle
// and `03 Bereiche/Lapis Cloud/Systemic Consensus.md` for the concept document this
// implements.
//
// Naming decision: the concept note's "SK-Vote/SK-Option/SK-Resistance value" collide in
// spirit (though not in table name) with the already-existing meritocratic `Vote`
// domain (06-vote.kuml.kts). To avoid confusion this aggregate is named `SystemicConsensus` —
// matching the single-noun-domain convention of `Election`/`Vote` — mapping SK-Vote ->
// SystemicConsensus/systemicConsensus, SK-Option -> SystemicConsensusOption/
// systemic_consensus_option, SK-Resistance value -> SystemicConsensusResistance/
// systemic_consensus_resistance (the per-ballot-per-option resistance row itself lives on
// systemic_consensus_resistance, one row per (ballot, option) pair — see that entity below).
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified
// against both the real Flyway-migrated H2 schema and the generated Exposed Table objects
// (network.lapis.cloud.server.db.generated.SystemicConsensus*Table.kt) by
// SystemicConsensusSchemaDriftTest. Per ADR-0016's designModelStrategy option B, this is a
// verification-only artifact for now — see docs/architecture/domain-model.adoc and CLAUDE.md's
// kUML-Repo-Konventionen (vault) for the full rationale.
//
// Cross-domain stubs: minimal id-only Member (foundation-owned), Motion/Meeting/Committee/Resolution
// (governance-owned) stubs, same pattern as 06-vote.kuml.kts/07-election.kuml.kts's own stubs
// — purely so UmlToErmTransformer can resolve this domain's real FK associations within this
// single-file evaluation. Committee is unused today (kept for documentation/consistency, mirroring
// 07-election.kuml.kts's own unused-today Committee stub) — SystemicConsensus has no targetCommittee concept,
// it never seats anyone.
//
// systemic_consensus.resolution_id -> resolution (id), nullable: association-derived default
// ("resolution_id") matches the real column name exactly — modelled as a real UML association,
// the clean forward direction of the systemicConsensus<->resolution cycle (Resolution already exists
// here as a stub, so no cycle problem in this direction). 05-governance.kuml.kts's own resolution
// entity declares resolution.systemic_consensus_id as a plain nullable UUID «Column» attribute instead
// of the (circular, at that point) association — exactly the same two-domain-cycle pattern
// already used for vote/election (see that file's header comment).
//
// systemic_consensus.winner_option_id: plain nullable UUID «Column» attribute with NO FK constraint —
// systemic_consensus_option itself FK-references systemicConsensus, so a real FK the other way would be
// circular. Same workaround already used for vote.winner_option_id/
// document.current_version_id.
//
// FK-column-naming-mismatch fallbacks (same gap class already discovered in every prior domain —
// association-derived default name snake_case(singular(targetClass))+"_id" doesn't match the
// real column name), modelled as plain «Column» UUID attributes instead of UML associations:
// - systemic_consensus.opened_by -> member (id), NOT NULL: default would be "member_id".
// - systemic_consensus_option.created_by -> member (id), NOT NULL: default would be "member_id".
// - systemic_consensus_resistance.ballot_id -> systemic_consensus_ballot (id), NOT NULL: default
//   would be "systemic_consensus_ballot_id".
// - systemic_consensus_resistance.option_id -> systemic_consensus_option (id), NOT NULL: default would be
//   "systemic_consensus_option_id".
// - systemic_consensus_option/eligible_voter/participation/ballot.systemic_consensus_id -> systemicConsensus
//   (id), NOT NULL: the association-derived default is normally "systemic_consensus_id" (matching
//   the real column) here too, but kUML's singularization of the SOURCE class name
//   ("SystemicConsensus") naively strips the trailing "s" ("SystemicConsensus" ->
//   "SystemicConsensu"), corrupting the derived column to "systemic_consensu_id". Discovered
//   while renaming this domain from German (the original "SystemicConsensus" name didn't end in a
//   bare "s" and never hit this). All four are therefore modelled as plain «Column» UUID
//   attributes with an explicit fkEntity/columnName override instead of UML associations.
// Their real FK existence/target/nullability is still independently pinned via
// SystemicConsensusSchemaDriftTest's information_schema introspection against the real migrated
// schema.
//
// FKs that DO match the association-derived default and are modelled as real UML associations:
// systemic_consensus.motion_id/meeting_id/resolution_id,
// systemic_consensus_eligible_voter.member_id, systemic_consensus_participation.member_id,
// systemic_consensus_ballot.member_id. None of these entities has more than one competing
// member-FK, so the first-declared-association-claims-the-bare-default mechanism never causes a
// collision problem here (same as 06-vote.kuml.kts/07-election.kuml.kts).
//
// Ballot secrecy (privacy rationale — do NOT "fix" systemic_consensus_ballot.member_id's
// nullability, a naive schema reader might assume it should be NOT NULL like most other
// member_id FKs in this file): systemic_consensus_ballot.member_id is nullable and always NULL
// for a secret (secret, the default) SystemicConsensus — there is no FK-joinable link back to
// systemic_consensus_participation (the "this member rated" proof) in that case. Exactly the same
// practical DB-level table split already used by election_ballot/election_participation (no
// cryptography anywhere in this codebase) — see 07-election.kuml.kts's own file header for the full
// rationale. One-member-one-vote-per-ratingRound enforcement:
// - Non-secret (secret = false): UNIQUE (systemic_consensus_id, member_id, round) on
//   systemic_consensus_ballot is the backstop.
// - Secret (secret = true): systemic_consensus_ballot.member_id is always NULL, so that table's
//   own unique constraint is a no-op there. systemic_consensus_participation's own UNIQUE
//   (systemic_consensus_id, member_id, round) is the real DB-level backstop for the secret path
//   instead.
// The `round` column (present on eligible_voter/participation/ballot, absent from election's
// equivalent tables) is this domain's one structural addition over the Election shape: it lets a
// reopened ratingRound (discussion + revote) keep prior rounds' rows for DSGVO
// retention while the tally counts only the current round.
//
// Four enum columns in this domain (SystemicConsensusStatus/SystemicConsensusAggregation/SystemicConsensusTiebreakRule/
// SystemicConsensusBindingness), modelled with only an «Column».enumType tag and NO «Column».sqlType
// override — post-87563ff convention (see 06-vote.kuml.kts/07-election.kuml.kts's own current
// attribute shape, NOT their stale header prose): kUML's enum-to-Enum+CHECK fallback path
// applies, and the generated VARCHAR width is derived automatically from the longest literal.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "SystemicConsensus") {
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

    // Governance-owned stub — id-only, purely so the systemic_consensus.motion_id association can
    // resolve.
    val motion = classOf(name = "Motion") {
        stereotype("Entity") { "tableName" to "motion"; "kotlinObjectName" to "MotionTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the systemic_consensus.meeting_id association can
    // resolve.
    val meeting = classOf(name = "Meeting") {
        stereotype("Entity") { "tableName" to "meeting"; "kotlinObjectName" to "MeetingTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only. Kept for documentation/consistency, mirroring
    // 07-election.kuml.kts's own unused-today Committee stub — SystemicConsensus has no targetCommittee concept.
    val committee = classOf(name = "Committee") {
        stereotype("Entity") { "tableName" to "committee"; "kotlinObjectName" to "CommitteeTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the systemic_consensus.resolution_id association can
    // resolve. This is the clean forward direction of the systemicConsensus<->resolution cycle — see
    // the file header comment for why 05-governance.kuml.kts declares resolution.systemic_consensus_id
    // as a plain column instead of the (circular, at that point) association.
    val resolution = classOf(name = "Resolution") {
        stereotype("Entity") { "tableName" to "resolution"; "kotlinObjectName" to "ResolutionTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val systemicConsensusStatus = enumOf(name = "SystemicConsensusStatus") {
        literal(name = "COLLECTION")
        literal(name = "RATING")
        literal(name = "CLOSED")
        literal(name = "EVALUATED")
        literal(name = "ABORTED")
    }

    val skAggregation = enumOf(name = "SystemicConsensusAggregation") {
        literal(name = "MEAN")
        literal(name = "SUM")
    }

    val skTiebreakRegel = enumOf(name = "SystemicConsensusTiebreakRule") {
        literal(name = "LOWEST_MAX_RESISTANCE")
        literal(name = "LOWEST_STD_DEV")
        literal(name = "REPEAT")
    }

    val skBindingness = enumOf(name = "SystemicConsensusBindingness") {
        literal(name = "ADVISORY")
        literal(name = "BINDING")
    }

    val systemicConsensus = classOf(name = "SystemicConsensus") {
        stereotype("Entity") { "tableName" to "systemic_consensus"; "kotlinObjectName" to "SystemicConsensusTable" }
        stereotype("Index") { "columns" to listOf("motion_id"); "name" to "idx_systemic_consensus_motion" }
        stereotype("Index") { "columns" to listOf("meeting_id"); "name" to "idx_systemic_consensus_meeting" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_systemic_consensus_status" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "status", type = systemicConsensusStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.SystemicConsensusStatus" }
        }
        attribute(name = "secret", type = "Boolean") {
            stereotype("Column") { "columnName" to "secret" }
        }
        attribute(name = "scaleMax", type = "Int") {
            stereotype("Column") { "columnName" to "scale_max" }
        }
        attribute(name = "aggregation", type = skAggregation) {
            stereotype("Column") { "columnName" to "aggregation"; "enumType" to "network.lapis.cloud.shared.domain.SystemicConsensusAggregation" }
        }
        attribute(name = "tiebreakRule", type = skTiebreakRegel) {
            stereotype("Column") { "columnName" to "tiebreak_rule"; "enumType" to "network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule" }
        }
        attribute(name = "groupConflictViableThreshold", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "group_conflict_viable_threshold"; "sqlType" to "DECIMAL(4,3)" }
        }
        attribute(name = "groupConflictWarnThreshold", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "group_conflict_warn_threshold"; "sqlType" to "DECIMAL(4,3)" }
        }
        attribute(name = "statusQuoOptionAuto", type = "Boolean") {
            stereotype("Column") { "columnName" to "status_quo_option_auto" }
        }
        attribute(name = "bindingness", type = skBindingness) {
            stereotype("Column") { "columnName" to "bindingness"; "enumType" to "network.lapis.cloud.shared.domain.SystemicConsensusBindingness" }
        }
        attribute(name = "maxRounds", type = "Int") {
            stereotype("Column") { "columnName" to "max_rounds" }
        }
        attribute(name = "round", type = "Int") {
            stereotype("Column") { "columnName" to "round" }
        }
        // No FK constraint (see file header comment: circular with systemic_consensus_option, which
        // itself FK-references systemicConsensus).
        attribute(name = "winnerOptionId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "winner_option_id" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "opened_by".
        attribute(name = "openedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "opened_by"; "fkEntity" to "Member" }
        }
        attribute(name = "openedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "opened_at" }
        }
        attribute(name = "ratingOpenedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "rating_opened_at" }
        }
        attribute(name = "ratingClosedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "rating_closed_at" }
        }
        attribute(name = "tallyRunAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "tally_run_at" }
        }
    }

    // systemic_consensus.motion_id -> motion (id): association-derived default matches.
    association(source = motion, target = systemicConsensus, id = "assoc-motion-systemic_consensus") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "motionId" }
    }

    // systemic_consensus.meeting_id -> meeting (id): association-derived default matches.
    association(source = meeting, target = systemicConsensus, id = "assoc-meeting-systemic_consensus") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "meetingId" }
    }

    // systemic_consensus.resolution_id -> resolution (id): association-derived default matches.
    // Nullable — the clean forward direction of the systemicConsensus<->resolution cycle (see file
    // header comment).
    association(source = resolution, target = systemicConsensus, id = "assoc-resolution-systemic_consensus") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "resolutionId" }
    }

    val systemicConsensusOption = classOf(name = "SystemicConsensusOption") {
        stereotype("Entity") { "tableName" to "systemic_consensus_option"; "kotlinObjectName" to "SystemicConsensusOptionTable" }
        stereotype("Index") { "columns" to listOf("systemic_consensus_id"); "name" to "idx_systemic_consensus_option_systemic_consensus" }

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
        attribute(name = "isStatusQuoOption", type = "Boolean") {
            stereotype("Column") { "columnName" to "is_status_quo_option" }
        }
        // Real FK -> systemicConsensus (id), NOT NULL. Plain «Column» UUID attribute, NOT a UML
        // association -- kUML's association-derived-default naming singularizes the source class
        // name by naively stripping a trailing "s" ("SystemicConsensus" -> "SystemicConsensu"),
        // which corrupts the derived column to "systemic_consensu_id" instead of the real
        // "systemic_consensus_id" (a naming-gap class specific to source class names ending in an
        // invariant "-us"/"-s" word; discovered while renaming this domain from German). Same
        // fallback mechanism the codebase already uses for genuine naming mismatches elsewhere.
        attribute(name = "systemicConsensusId", type = "UUID") {
            stereotype("Column") { "columnName" to "systemic_consensus_id"; "fkEntity" to "SystemicConsensus" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "created_by".
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
    }

    val systemicConsensusEligibleVoter = classOf(name = "SystemicConsensusEligibleVoter") {
        stereotype("Entity") {
            "tableName" to "systemic_consensus_eligible_voter"
            "kotlinObjectName" to "SystemicConsensusEligibleVoterTable"
        }
        stereotype("Index") {
            "columns" to listOf("systemic_consensus_id", "member_id", "round")
            "unique" to true
            "name" to "uq_systemic_consensus_eligible_voter_member_round"
        }
        stereotype("Index") { "columns" to listOf("systemic_consensus_id"); "name" to "idx_systemic_consensus_eligible_voter_systemic_consensus" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> systemicConsensus (id), NOT NULL. Plain «Column» UUID attribute, NOT a UML
        // association -- see systemic_consensus_option's own comment above for why (naive
        // singularization of "SystemicConsensus" corrupts the association-derived default).
        attribute(name = "systemicConsensusId", type = "UUID") {
            stereotype("Column") { "columnName" to "systemic_consensus_id"; "fkEntity" to "SystemicConsensus" }
        }
        attribute(name = "round", type = "Int") {
            stereotype("Column") { "columnName" to "round" }
        }
    }

    // systemic_consensus_eligible_voter.member_id -> member (id): association-derived default
    // matches (no competing member-FK on this entity).
    association(source = member, target = systemicConsensusEligibleVoter, id = "assoc-member-systemic-consensus-eligible-voter") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val systemicConsensusParticipation = classOf(name = "SystemicConsensusParticipation") {
        stereotype("Entity") { "tableName" to "systemic_consensus_participation"; "kotlinObjectName" to "SystemicConsensusParticipationTable" }
        stereotype("Index") {
            "columns" to listOf("systemic_consensus_id", "member_id", "round")
            "unique" to true
            "name" to "uq_systemic_consensus_participation_member_round"
        }
        stereotype("Index") { "columns" to listOf("systemic_consensus_id"); "name" to "idx_systemic_consensus_participation_systemic_consensus" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> systemicConsensus (id), NOT NULL. Plain «Column» UUID attribute, NOT a UML
        // association -- see systemic_consensus_option's own comment above for why (naive
        // singularization of "SystemicConsensus" corrupts the association-derived default).
        attribute(name = "systemicConsensusId", type = "UUID") {
            stereotype("Column") { "columnName" to "systemic_consensus_id"; "fkEntity" to "SystemicConsensus" }
        }
        attribute(name = "votedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "voted_at" }
        }
        attribute(name = "round", type = "Int") {
            stereotype("Column") { "columnName" to "round" }
        }
    }

    // systemic_consensus_participation.member_id -> member (id): association-derived default matches (no
    // competing member-FK on this entity).
    association(source = member, target = systemicConsensusParticipation, id = "assoc-member-systemic-consensus-participation") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    // Ballot secrecy: memberId is nullable and always NULL for a secret (secret) SystemicConsensus —
    // see file header comment for the full privacy rationale. This is a meaningful business rule
    // a naive schema reader could "fix" by mistake (most other member_id FKs in this domain are
    // NOT NULL).
    val systemicConsensusBallot = classOf(name = "SystemicConsensusBallot") {
        stereotype("Entity") { "tableName" to "systemic_consensus_ballot"; "kotlinObjectName" to "SystemicConsensusBallotTable" }
        stereotype("Index") {
            "columns" to listOf("systemic_consensus_id", "member_id", "round")
            "unique" to true
            "name" to "uq_systemic_consensus_ballot_member_round"
        }
        stereotype("Index") { "columns" to listOf("systemic_consensus_id"); "name" to "idx_systemic_consensus_ballot_systemic_consensus" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> systemicConsensus (id), NOT NULL. Plain «Column» UUID attribute, NOT a UML
        // association -- see systemic_consensus_option's own comment above for why (naive
        // singularization of "SystemicConsensus" corrupts the association-derived default).
        attribute(name = "systemicConsensusId", type = "UUID") {
            stereotype("Column") { "columnName" to "systemic_consensus_id"; "fkEntity" to "SystemicConsensus" }
        }
        attribute(name = "receiptCode", type = "String") {
            stereotype("Column") { "columnName" to "receipt_code"; "sqlType" to "VARCHAR(40)" }
        }
        attribute(name = "castAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "cast_at" }
        }
        attribute(name = "round", type = "Int") {
            stereotype("Column") { "columnName" to "round" }
        }
    }

    // systemic_consensus_ballot.member_id -> member (id), nullable: association-derived default
    // matches (no competing member-FK on this entity). Nullable for ballot secrecy — see file
    // header.
    association(source = member, target = systemicConsensusBallot, id = "assoc-member-systemic-consensus-ballot") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val systemicConsensusResistance = classOf(name = "SystemicConsensusResistance") {
        stereotype("Entity") { "tableName" to "systemic_consensus_resistance"; "kotlinObjectName" to "SystemicConsensusResistanceTable" }
        stereotype("Index") {
            "columns" to listOf("ballot_id")
            "name" to "idx_systemic_consensus_resistance_ballot"
        }
        stereotype("Index") { "columns" to listOf("option_id"); "name" to "idx_systemic_consensus_resistance_option" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // "value" is a reserved SQL keyword (unquoted in the generated DDL) -- named
        // "resistanceValue" instead, same reason other columns avoid raw-reserved names.
        attribute(name = "resistanceValue", type = "Int") {
            stereotype("Column") { "columnName" to "resistance_value" }
        }
        // Real FK -> systemic_consensus_ballot (id), NOT NULL. Plain «Column» UUID attribute —
        // association-to-FK naming would derive "systemic_consensus_ballot_id", not the real
        // schema's "ballot_id".
        attribute(name = "ballotId", type = "UUID") {
            stereotype("Column") { "columnName" to "ballot_id"; "fkEntity" to "SystemicConsensusBallot" }
        }
        // Real FK -> systemic_consensus_option (id), NOT NULL. Plain «Column» UUID attribute —
        // association-to-FK naming would derive "systemic_consensus_option_id", not the real schema's
        // "option_id".
        attribute(name = "optionId", type = "UUID") {
            stereotype("Column") { "columnName" to "option_id"; "fkEntity" to "SystemicConsensusOption" }
        }
    }
}
