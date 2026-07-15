// Vote domain — vote/vote_option/vote_ballot
// (V8__meritokratische_voteen.sql). Technically still defined in the same hand-written
// GovernanceTables.kt file as governance (VoteTable/VoteOptionTable/
// VoteBallotTable), but modelled as its own .kuml.kts domain script here — per the
// retrofit plan's per-domain file layout (05-governance vs 06-vote as separate waves) —
// because it FK-depends on Motion and Meeting (both governance-owned) and Member
// (foundation-owned), and closes the cycle back into Resolution (governance-owned) via
// resolution_id. Treating it as a separate generation unit keeps the FK-dependency graph a clean
// DAG at the tooling level even though the two hand-written Kotlin files don't split this way.
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified against
// both the real Flyway-migrated H2 schema and the hand-written Exposed Table objects
// (network.lapis.cloud.server.db.tables.GovernanceTables.kt — VoteTable/
// VoteOptionTable/VoteBallotTable) by VoteSchemaDriftTest. Per ADR-0016's
// designModelStrategy option B, this is a verification-only artifact for now: the hand-written
// Table objects remain the actually-compiled/actually-imported-by-N-files source. See
// docs/architecture/domain-model.adoc and CLAUDE.md's kUML-Repo-Konventionen (vault) for the full
// rationale (enum-to-VARCHAR type-fidelity gap, Kotlin-object-naming-override gap).
//
// Cross-domain stubs: minimal id-only Motion, Meeting, Member (all governance-/foundation-owned)
// stubs, same pattern as prior domains' stubs — purely so UmlToErmTransformer can resolve this
// domain's real FK associations within this single-file evaluation. Resolution is ALSO stubbed
// (id-only) so vote.resolution_id -> resolution can be declared as a real, clean forward «FK»
// association from this side — see 05-governance.kuml.kts's own header comment: the governance
// script could not declare resolution.vote_id as an association back into this
// not-yet-modelled domain (would have been a genuine cycle), so it left it as a plain column and
// deferred the real association to this file instead, where the forward direction
// (vote -> resolution) has no such problem since Resolution already exists here as a stub.
//
// vote.motion_id -> motion (id), NOT NULL: association-derived default ("motion_id")
// matches the real column name exactly — modelled as a real UML association.
// vote.meeting_id -> meeting (id), NOT NULL: association-derived default ("meeting_id")
// matches the real column name exactly — modelled as a real UML association.
// vote.resolution_id -> resolution (id), nullable: association-derived default
// ("resolution_id") matches the real column name exactly — modelled as a real UML association.
// vote.opened_by -> member (id), NOT NULL: same naming-gap class already discovered in
// document/communication/dsgvo/governance — association-to-FK naming would derive "member_id",
// not the real schema's "opened_by". Modelled as a plain «Column» UUID attribute.
// vote.winner_option_id: plain nullable UUID «Column» attribute with NO FK constraint in the
// real schema (hand-written VoteTable.winnerOptionId has no .references() call either) —
// same circular-reference-avoidance workaround already used for document.current_version_id and
// resolution.vote_id/election_id: vote_option itself FK-references vote, so a real FK
// the other way would be circular. Modelled the same way here, as a plain attribute.
// vote_option.vote_id -> vote (id), NOT NULL: association-derived default
// matches — modelled as a real UML association.
// vote_ballot.vote_id -> vote (id), NOT NULL: association-derived default
// matches — modelled as a real UML association.
// vote_ballot.option_id -> vote_option (id), NOT NULL: association-derived default
// would be "vote_option_id", not the real schema's "option_id" — same naming-gap class,
// modelled as a plain «Column» UUID attribute.
// vote_ballot.member_id -> member (id), NOT NULL: association-derived default matches (no
// competing member-FK on this entity, unlike governance's meeting/attendance multi-FK cases) —
// modelled as a real UML association.
//
// VoteStatus is the sole enum column in this domain (OPEN/CLOSED/ABORTED, 11 chars
// max), modelled with an explicit «Column».sqlType="VARCHAR(30)" override — same
// mechanism/rationale as every prior domain's enum columns (real V8 schema has a plain VARCHAR(30)
// column, no CHECK constraint).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Vote") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern established by
    // contribution's/document's/communication's/dsgvo's/governance's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the vote.motion_id association can resolve.
    val motion = classOf(name = "Motion") {
        stereotype("Entity") { "tableName" to "motion"; "kotlinObjectName" to "MotionTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the vote.meeting_id association can resolve.
    val meeting = classOf(name = "Meeting") {
        stereotype("Entity") { "tableName" to "meeting"; "kotlinObjectName" to "MeetingTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the vote.resolution_id association can
    // resolve. This is the clean forward direction of the vote<->resolution cycle — see the
    // file header comment for why 05-governance.kuml.kts declares resolution.vote_id as a
    // plain column instead of the (circular, at that point) association.
    val resolution = classOf(name = "Resolution") {
        stereotype("Entity") { "tableName" to "resolution"; "kotlinObjectName" to "ResolutionTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val voteStatus = enumOf(name = "VoteStatus") {
        literal(name = "OPEN")
        literal(name = "CLOSED")
        literal(name = "ABORTED")
    }

    val vote = classOf(name = "Vote") {
        stereotype("Entity") { "tableName" to "vote"; "kotlinObjectName" to "VoteTable" }
        stereotype("Index") { "columns" to listOf("motion_id"); "name" to "idx_vote_motion" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_vote_status" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "status", type = voteStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.VoteStatus" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "opened_by".
        attribute(name = "openedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "opened_by"; "fkEntity" to "Member" }
        }
        attribute(name = "openedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "opened_at" }
        }
        attribute(name = "closedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "closed_at" }
        }
        // No FK constraint in the real schema either (see the file header comment: circular with
        // vote_option, which itself FK-references vote).
        attribute(name = "winnerOptionId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "winner_option_id" }
        }
        attribute(name = "secondPriceLtr", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "second_price_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
    }

    // vote.motion_id -> motion (id): association-derived default matches.
    association(source = motion, target = vote, id = "assoc-motion-vote") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "motionId" }
    }

    // vote.meeting_id -> meeting (id): association-derived default matches.
    association(source = meeting, target = vote, id = "assoc-meeting-vote") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "meetingId" }
    }

    // vote.resolution_id -> resolution (id): association-derived default matches. Nullable —
    // the clean forward direction of the vote<->resolution cycle (see file header comment).
    association(source = resolution, target = vote, id = "assoc-resolution-vote") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "resolutionId" }
    }

    val voteOption = classOf(name = "VoteOption") {
        stereotype("Entity") { "tableName" to "vote_option"; "kotlinObjectName" to "VoteOptionTable" }
        stereotype("Index") { "columns" to listOf("vote_id"); "name" to "idx_vote_option_vote" }

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
    }

    // vote_option.vote_id -> vote (id): association-derived default matches.
    association(source = vote, target = voteOption, id = "assoc-vote-option") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "voteId" }
    }

    val voteBallot = classOf(name = "VoteBallot") {
        stereotype("Entity") { "tableName" to "vote_ballot"; "kotlinObjectName" to "VoteBallotTable" }
        stereotype("Index") {
            "columns" to listOf("vote_id", "member_id")
            "unique" to true
            "name" to "uq_vote_ballot_member"
        }
        stereotype("Index") { "columns" to listOf("vote_id"); "name" to "idx_vote_ballot_vote" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_vote_ballot_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> vote_option (id), NOT NULL. Plain «Column» UUID attribute —
        // association-to-FK naming would derive "vote_option_id", not the real schema's
        // "option_id".
        attribute(name = "optionId", type = "UUID") {
            stereotype("Column") { "columnName" to "option_id"; "fkEntity" to "VoteOption" }
        }
        attribute(name = "stakeLtr", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "stake_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        attribute(name = "settledLtr", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "settled_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        attribute(name = "castAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "cast_at" }
        }
    }

    // vote_ballot.vote_id -> vote (id): association-derived default matches.
    association(source = vote, target = voteBallot, id = "assoc-vote-ballot") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "voteId" }
    }

    // vote_ballot.member_id -> member (id): association-derived default matches (no
    // competing member-FK on this entity).
    association(source = member, target = voteBallot, id = "assoc-member-vote-ballot") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }
}
