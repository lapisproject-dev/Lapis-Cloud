// DSGVO domain — erasure_request/dsgvo_audit_log (V5__dsgvo.sql).
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified
// against both the real Flyway-migrated H2 schema and the hand-written Exposed Table objects
// (network.lapis.cloud.server.db.tables.DsgvoTables.kt) by SchemaDriftTest. Per ADR-0016's
// designModelStrategy option B, this is a verification-only artifact for now: the hand-written
// Table objects remain the actually-compiled/actually-imported-by-N-files source. See
// docs/architecture/domain-model.adoc and CLAUDE.md's kUML-Repo-Konventionen (vault) for the
// full rationale (enum-to-VARCHAR type-fidelity gap, Kotlin-object-naming-override gap).
//
// Every single Member-/ErasureRequest-referencing FK in this domain is modelled as a plain
// «Column» UUID attribute rather than a UML association — see the naming-derivation notes below —
// pinned instead via «Column».fkEntity, which is why this file, symmetrically, carries a minimal
// id-only Member stub (owned by Foundation); erasure_request itself is already declared within
// this same file, so dsgvo_audit_log.request_id needs no separate stub.
//
// V5__dsgvo.sql also ALTERs member to add member.anonymized_at — already modelled on Member in
// 00-foundation.kuml.kts (see that file's own header comment); not repeated here, this file only
// owns erasure_request/dsgvo_audit_log.
//
// erasure_request has THREE independent FKs to member (subject_member_id, requested_by,
// decided_by) — the multi-role-FK-collision case flagged in the retrofit plan (originally written
// for governance's N=4 case, first empirically confirmed at N=2 in the communication domain's
// direct_message.sender_id/recipient_id). UmlToErmTransformer.addForeignKey's collision-fallback
// mechanism (fkEntity.hasAttributeNamed(defaultBaseName) only triggers once the FIRST
// association's column already exists) means a real association pair/triple here would resolve to
// "member_id"/"<role>_id"/"<role>_id" at best — never "subject_member_id" AND "requested_by" AND
// "decided_by" together, since none of the three real column names is the bare "member_id"
// default. All three are therefore modelled as plain «Column» UUID attributes (subject_member_id
// and requested_by NOT NULL, decided_by nullable), per the retrofit plan's own risk-note fallback
// strategy. Their real FK existence/target/nullability is still independently pinned via
// DsgvoSchemaDriftTest's information_schema introspection against the live H2-migrated schema.
//
// dsgvo_audit_log has TWO more Member-referencing FKs (actor_member_id, nullable; subject_member_id,
// NOT NULL) — same collision situation as erasure_request's trio (neither real column name matches
// the "member_id" derived default), so both are plain «Column» UUID attributes too.
//
// dsgvo_audit_log.request_id -> erasure_request (id), nullable: even though this is the domain's
// only FK that does NOT target member, the association-derived default name would be
// "erasure_request_id" (snake_case(singular("ErasureRequest")) + "_id"), not the real schema's
// "request_id" — same naming-derivation gap already seen for document.folder_id/created_by,
// document_version.uploaded_by, mailing_list.created_by, mailing_message.sent_by. Modelled as a
// plain nullable «Column» UUID attribute rather than a UML association for the same reason (no
// DSL-level way to override the derived default name without an actual attribute-name collision).
//
// outcome_summary (both tables, unbounded `text` as of V0.6.6 -- widened VARCHAR(4000) ->
// VARCHAR(8000) -> `text`, see below): a JSON-encoded-as-string column (List<TableErasureOutcomeDto>,
// see DsgvoService/ErasureRequestTable/DsgvoAuditLogTable KDoc) — modelled explicitly as a plain
// String attribute with an explicit «Column».sqlType override, NOT relying on kUML's
// ErmDataType.Json fallback path. The emitter's Json-fallback comment ("ErmDataType.Json
// fallback") would be misleading here: this is intentionally opaque JSON-as-text (encoded/decoded
// in DsgvoService, never queried as JSON by the DB), not an unsupported-json-type workaround.
//
// Widened 4000 -> 8000 by 09-systemic-consensus.kuml.kts (V0.2.5, Systemic Consensus): every
// PersonalDataContributor.erase() call always returns one TableErasureOutcome per *covered*
// table (not just touched ones, see PersonalDataContributor KDoc), and DsgvoService.executeErasure
// concatenates ALL contributors' outcomes for the subject into this ONE shared column — a budget
// ElectionPersonalData's own KDoc already flagged as a live risk once "a 7-table contributor" landed.
// SystemicConsensusPersonalData is the 8th contributor (5 more tables), which pushed a
// comprehensive-erasure test (a member touching every domain) past 4000 chars even with
// maximally terse retentionReason strings — DsgvoServiceTest caught this immediately. The V0.2.5
// note that "8000 buys headroom for ... at least one more comparably-sized future wave" undersold
// how fast this grows: seven more contributors landed between V0.5.3 and V0.6.5 (AuditLog, Backup,
// DsgvoCompliance, Crowdfunding, PeerTransfer, Politician, PriceOracle), pushing a comprehensive
// erasure past 8000 chars (8670) and breaking DsgvoServiceTest again — exactly the failure mode
// 14-audit-log.kuml.kts's `before_snapshot`/`after_snapshot` KDoc already predicted for this exact
// column ("Originally VARCHAR(8000) (mirroring dsgvo_audit_log.outcome_summary), but that was
// falsified during review ... ANY fixed VARCHAR length is just a bigger deadline until the same
// failure recurs"). Fixed the same way that column was: `sqlType to "text"` (unbounded), matching
// `text("outcome_summary")` on both hand-maintained Table objects. A capped VARCHAR is structurally
// wrong for a column whose size scales with the number of registered PersonalDataContributors, which
// has no upper bound this codebase can predict in advance
// (e.g. per-contributor rows instead of one big JSON blob) rather than another width bump.
//
// dsgvo_audit_log is the append-only/no-update table flagged in the retrofit plan's per-domain
// notes: ERM has no "append-only"/"no delete/update" construct at all (no such tag exists in
// ErmProfileNames, and none would make sense at the ERM/SQL-DDL level — H2 has no built-in
// insert-only table constraint short of triggers, which are out of scope for this MDA pipeline).
// The write-once invariant is enforced purely at the application/service layer (DsgvoService never
// issues an update/deleteWhere against DsgvoAuditLogTable in non-test code) and verified
// structurally by PersonalDataCoverageTest's "no payload in the audit log" negative test — not by
// anything the generated schema can express. Documented here as an accepted ERM-model limitation,
// not attempted.
//
// DsgvoAuditAction (dsgvo_audit_log.action), ErasureMode (erasure_request.mode), ErasureStatus
// (erasure_request.status) are three of this domain's enum columns; actor_role reuses foundation's
// AccountRole enum (nullable here, unlike account.role). All four are modelled with explicit
// «Column».sqlType overrides, same mechanism/rationale as member.status/account.role in the prior
// domains (real V5__dsgvo.sql has plain VARCHAR columns, no CHECK constraints).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Dsgvo") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern established by
    // contribution's own Member stub. Only exists here so UmlToErmTransformer can resolve this
    // domain's Member-referencing «Column».fkEntity targets.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val erasureMode = enumOf(name = "ErasureMode") {
        literal(name = "ANONYMIZE")
        literal(name = "HARD_DELETE_WHERE_UNCONSTRAINED")
    }

    val erasureStatus = enumOf(name = "ErasureStatus") {
        literal(name = "REQUESTED")
        literal(name = "APPROVED")
        literal(name = "REJECTED")
        literal(name = "COMPLETED")
    }

    val dsgvoAuditAction = enumOf(name = "DsgvoAuditAction") {
        literal(name = "EXPORT")
        literal(name = "ERASURE_REQUESTED")
        literal(name = "ERASURE_APPROVED")
        literal(name = "ERASURE_REJECTED")
        literal(name = "ERASURE_EXECUTED")
    }

    // Reuses foundation's AccountRole literal set (00-foundation.kuml.kts) — modelled locally
    // here too since kUML has no cross-file model-import mechanism (each domain script is
    // evaluated independently; see the retrofit plan's designModelStrategy on cross-file
    // references). dsgvo_audit_log.actor_role is nullable, unlike account.role.
    val accountRole = enumOf(name = "AccountRole") {
        literal(name = "MEMBER")
        literal(name = "BOARD")
        literal(name = "TREASURER")
        literal(name = "ADMIN")
    }

    val erasureRequest = classOf(name = "ErasureRequest") {
        stereotype("Entity") { "tableName" to "erasure_request"; "kotlinObjectName" to "ErasureRequestTable" }
        stereotype("Index") { "columns" to listOf("subject_member_id"); "name" to "idx_erasure_request_subject" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_erasure_request_status" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — see the file header
        // comment (three-FKs-to-member collision case; association-to-FK naming would derive
        // "member_id" for at most one of the three, never all three real names together).
        attribute(name = "subjectMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "subject_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "requestedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "requested_at" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — see the file header
        // comment.
        attribute(name = "requestedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "requested_by"; "fkEntity" to "Member" }
        }
        attribute(name = "reason", type = "String") {
            stereotype("Column") { "columnName" to "reason"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "mode", type = erasureMode) {
            stereotype("Column") { "columnName" to "mode"; "enumType" to "network.lapis.cloud.shared.domain.ErasureMode" }
        }
        attribute(name = "status", type = erasureStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.ErasureStatus" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute — see the file header
        // comment.
        attribute(name = "decidedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "decided_by"; "fkEntity" to "Member" }
        }
        attribute(name = "decidedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "decided_at" }
        }
        attribute(name = "decisionNote", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "decision_note"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "executedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "executed_at" }
        }
        attribute(name = "legalHold", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "legal_hold" }
        }
        // JSON-encoded-as-string, not ErmDataType.Json — see the file header comment.
        attribute(name = "outcomeSummary", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "outcome_summary"; "sqlType" to "text" }
        }
    }

    val dsgvoAuditLog = classOf(name = "DsgvoAuditLog") {
        stereotype("Entity") { "tableName" to "dsgvo_audit_log"; "kotlinObjectName" to "DsgvoAuditLogTable" }
        stereotype("Index") { "columns" to listOf("subject_member_id"); "name" to "idx_dsgvo_audit_log_subject" }
        stereotype("Index") { "columns" to listOf("request_id"); "name" to "idx_dsgvo_audit_log_request" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "occurredAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "occurred_at" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute — see the file header
        // comment.
        attribute(name = "actorMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "actor_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "actorRole", type = accountRole) {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "actor_role"; "enumType" to "network.lapis.cloud.shared.domain.AccountRole" }
        }
        attribute(name = "action", type = dsgvoAuditAction) {
            stereotype("Column") { "columnName" to "action"; "enumType" to "network.lapis.cloud.shared.domain.DsgvoAuditAction" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — see the file header
        // comment.
        attribute(name = "subjectMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "subject_member_id"; "fkEntity" to "Member" }
        }
        // Real FK -> erasure_request (id), nullable. Plain «Column» UUID attribute — see the file
        // header comment (association-to-FK naming would derive "erasure_request_id", not the
        // real schema's "request_id").
        attribute(name = "requestId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "request_id"; "fkEntity" to "ErasureRequest" }
        }
        // JSON-encoded-as-string, not ErmDataType.Json — see the file header comment.
        attribute(name = "outcomeSummary", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "outcome_summary"; "sqlType" to "text" }
        }
        attribute(name = "legalBasis", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "legal_basis"; "sqlType" to "VARCHAR(500)" }
        }
    }
}
