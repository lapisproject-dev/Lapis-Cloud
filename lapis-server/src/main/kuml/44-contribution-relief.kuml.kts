// Contribution-relief domain — contribution_relief_request (V28__contribution_relief.sql), plus
// three new `member` columns forward-declared here and carried for real in
// 00-foundation.kuml.kts (member is Foundation's owning domain -- see that file's own file header
// convention for why the real declaration always lives with the owning entity, never here).
//
// Welle V1.4.10 "Beitragsvergünstigungen" (Stundung / Befreiung / Sozialermäßigung) --
// contribution_relief_request tracks a member's request for one of three reliefs
// (ContributionReliefKind: DEFERRAL/EXEMPTION/REDUCTION) and its board decision lifecycle
// (ContributionReliefStatus: REQUESTED/APPROVED/REJECTED/EXECUTED/WITHDRAWN). See
// `network.lapis.cloud.server.rpc.ContributionReliefService` KDoc for the full state machine and
// README.adoc's "Contribution relief" section for the fachlich rationale (there is no dedicated
// docs/architecture/contribution-relief.adoc -- this domain's rationale lives in the README).
//
// K-1 (no partial unique index): the whole test suite runs H2 in MODE=PostgreSQL
// (DatabaseConfig.kt), which rejects a partial unique index (`CREATE UNIQUE INDEX ... WHERE`) --
// verified empirically already for V8__sepa_mandates.sql/V9__dunning.sql/V25__accounting_export.sql.
// "One open request per (member, kind)" is instead enforced via the SAME application-maintained
// shadow-column idiom `event_registration.active_participant_key`/`accounting_export_run
// .active_key` already establish: a nullable `active_request_key` column plus a PLAIN (not
// partial) unique index -- multiple NULLs are allowed under a plain unique index on both H2 and
// real Postgres.
//
// K-4 (no circular FK): `member.contribution_exempt_request_id` (declared for real in
// 00-foundation.kuml.kts) deliberately carries NO «Column».fkEntity tag pointing back at this
// entity -- this entity's own `subject_member_id` already FKs -> member, and a reverse FK would
// create a cycle that `OrganizationSchemaCatalogTest`'s topological `restoreOrder` cannot resolve.
// Same treatment `member.reviewed_by` already establishes for itself (00-foundation.kuml.kts).
//
// K-5 (DEFERRABLE lives on the existing enum's status-set object): which `ContributionStatus`
// literals may be deferred is answered by `network.lapis.cloud.shared.domain
// .ContributionStatusSets.DEFERRABLE` (Contributions.kt), NOT a new object here -- that object's
// own KDoc doctrine ("the ONE place a 'which ContributionStatus literals may do X' question is
// answered") already covers this question; a second per-feature object would violate it. The
// REQUEST's own status-set lives in a SEPARATE object, `ContributionReliefStatusSets`
// (ContributionRelief.kt) -- a genuinely different enum, not a duplicate.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "ContributionRelief") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern every other domain
    // file establishes. Only exists here so UmlToErmTransformer can resolve
    // contribution_relief_request.subject_member_id / .requested_by / .decided_by within this
    // single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // 01-contribution.kuml.kts-owned stub — id-only. Only exists here so UmlToErmTransformer can
    // resolve contribution_relief_request.deferral_contribution_id's association target.
    val contribution = classOf(name = "Contribution") {
        stereotype("Entity") { "tableName" to "contribution"; "kotlinObjectName" to "ContributionTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // 01-contribution.kuml.kts-owned stub — id-only. Only exists here so UmlToErmTransformer can
    // resolve contribution_relief_request.reduction_target_tier_id's association target.
    val membershipTier = classOf(name = "MembershipTier") {
        stereotype("Entity") { "tableName" to "membership_tier"; "kotlinObjectName" to "MembershipTierTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order is load-bearing (ContributionReliefSchemaDriftTest pins ErmDataType.Enum.values
    // against network.lapis.cloud.shared.domain.ContributionReliefKind in exactly this order) --
    // append only, never reorder/insert among these three.
    val contributionReliefKind = enumOf(name = "ContributionReliefKind") {
        literal(name = "DEFERRAL")
        literal(name = "EXEMPTION")
        literal(name = "REDUCTION")
    }

    // Literal order load-bearing, same reason as contributionReliefKind.
    val contributionReliefStatus = enumOf(name = "ContributionReliefStatus") {
        literal(name = "REQUESTED")
        literal(name = "APPROVED")
        literal(name = "REJECTED")
        literal(name = "EXECUTED")
        literal(name = "WITHDRAWN")
    }

    // Literal order load-bearing, same reason as contributionReliefKind. Satzungs-Tatbestände,
    // keine Diagnosen -- Art.-9-Minimierung, siehe ContributionReliefService KDoc.
    val contributionReliefReason = enumOf(name = "ContributionReliefReason") {
        literal(name = "FINANCIAL_HARDSHIP")
        literal(name = "UNEMPLOYMENT")
        literal(name = "STUDENT_TRAINEE")
        literal(name = "ILLNESS_DISABILITY")
        literal(name = "PARENTAL_CARE")
        literal(name = "OTHER")
    }

    val contributionReliefRequest = classOf(name = "ContributionReliefRequest") {
        stereotype("Entity") { "tableName" to "contribution_relief_request"; "kotlinObjectName" to "ContributionReliefRequestTable" }

        // K-1: application-maintained shadow-column uniqueness, not a partial index -- see file
        // header. Plain (non-partial) unique index; multiple NULLs allowed on both H2 and Postgres.
        stereotype("Index") {
            "columns" to listOf("active_request_key")
            "unique" to true
            "name" to "uq_crr_active_request"
        }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_crr_status" }
        stereotype("Index") { "columns" to listOf("subject_member_id"); "name" to "idx_crr_subject" }
        stereotype("Index") { "columns" to listOf("review_due_on"); "name" to "idx_crr_review_due" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "subjectMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "subject_member_id"; "fkEntity" to "Member" }
        }
        // No «Column».sqlType override here -- an explicit sqlType on an enum-typed attribute
        // makes kUML emit an untyped ErmDataType.Varchar instead of the typed
        // ErmDataType.Enum+CHECK fallback (see ContributionSchemaDriftTest's own comment on
        // ContributionStatus for the precedent this mirrors). Width is auto-computed from the
        // longest literal: EXEMPTION (9) -> VARCHAR(9), matching V28__contribution_relief.sql.
        attribute(name = "kind", type = contributionReliefKind) {
            stereotype("Column") {
                "columnName" to "kind"
                "enumType" to "network.lapis.cloud.shared.domain.ContributionReliefKind"
            }
        }
        // Auto-computed width: REQUESTED/WITHDRAWN (9) -> VARCHAR(9), matching the migration.
        attribute(name = "status", type = contributionReliefStatus) {
            stereotype("Column") {
                "columnName" to "status"
                "enumType" to "network.lapis.cloud.shared.domain.ContributionReliefStatus"
            }
        }
        // Auto-computed width: ILLNESS_DISABILITY (18) -> VARCHAR(18), matching the migration.
        attribute(name = "reasonCategory", type = contributionReliefReason) {
            stereotype("Column") {
                "columnName" to "reason_category"
                "enumType" to "network.lapis.cloud.shared.domain.ContributionReliefReason"
            }
        }
        attribute(name = "reasonText", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reason_text"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "reasonRedactedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reason_redacted_at" }
        }
        // DEFERRAL payload -- both non-null iff kind == DEFERRAL (chk_crr_payload_shape).
        attribute(name = "deferralContributionId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "deferral_contribution_id"; "fkEntity" to "Contribution" }
        }
        attribute(name = "deferralNewDueDate", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "deferral_new_due_date" }
        }
        attribute(name = "deferralPreviousDueDate", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "deferral_previous_due_date" }
        }
        // EXEMPTION payload -- exemptionFrom non-null iff kind == EXEMPTION (chk_crr_payload_shape).
        attribute(name = "exemptionFrom", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "exemption_from" }
        }
        attribute(name = "exemptionUntil", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "exemption_until" }
        }
        // REDUCTION payload -- non-null iff kind == REDUCTION (chk_crr_payload_shape).
        attribute(name = "reductionTargetTierId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reduction_target_tier_id"; "fkEntity" to "MembershipTier" }
        }
        // Shared -- EXEMPTION/REDUCTION only, no automatic effect (see CHANGELOG "bewusste Grenze").
        attribute(name = "reviewDueOn", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "review_due_on" }
        }
        attribute(name = "requestedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "requested_at" }
        }
        attribute(name = "requestedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "requested_by"; "fkEntity" to "Member" }
        }
        attribute(name = "decidedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "decided_at" }
        }
        attribute(name = "decidedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "decided_by"; "fkEntity" to "Member" }
        }
        attribute(name = "decisionNote", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "decision_note"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "executedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "executed_at" }
        }
        attribute(name = "executionError", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "execution_error"; "sqlType" to "VARCHAR(500)" }
        }
        // K-1 shadow column -- see class-level «Index» above and file header.
        attribute(name = "activeRequestKey", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "active_request_key"; "sqlType" to "VARCHAR(64)" }
        }
    }

    // Deliberately NO `association(...)` blocks here. Every FK on this entity
    // (subjectMemberId/requestedBy/decidedBy/deferralContributionId/reductionTargetTierId) is
    // already generated from its own explicit «Column».fkEntity tag above -- exactly the
    // mechanism `crowdfunding_project.submitterMemberId`/`.reviewedBy` and
    // `contribution.sepaMandateId` (01-contribution.kuml.kts) already use for a FK with NO
    // matching association. A `association(source = member, target = contributionReliefRequest,
    // ...)` block here would NOT override that column's mapping -- it would ADD A SECOND,
    // independently-named FK column (e.g. `member_id`) alongside the already-declared
    // `subject_member_id`, since UmlToErmTransformer's association-to-FK synthesis and the
    // «Column».fkEntity tag are two INDEPENDENT column-generating mechanisms, never a single
    // attribute described twice (verified empirically: an earlier draft of this file declared
    // both and ContributionReliefSchemaDriftTest caught the resulting extra `member_id`/
    // `contribution_id`/`membership_tier_id` columns).
}
