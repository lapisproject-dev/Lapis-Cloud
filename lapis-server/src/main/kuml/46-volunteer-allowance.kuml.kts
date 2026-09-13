// Volunteer-allowance domain — volunteer_allowance_payment/volunteer_allowance_self_declaration
// (V30__volunteer_allowance.sql), plus one new `organization_settings` column forward-declared
// here and carried for real in 11-organization-settings.kuml.kts (OrganizationSettings is that
// file's owning domain -- see that file's own file header convention for why the real declaration
// always lives with the owning entity, never here).
//
// Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" (§3 Nr. 26 / 26a EStG) --
// volunteer_allowance_payment tracks a self-service allowance-payment request
// (VolunteerAllowancePaymentStatus: DRAFT/REQUESTED/APPROVED/REJECTED/EXECUTED/WITHDRAWN),
// volunteer_allowance_self_declaration the RECEIVING member's own declaration that they have not
// exceeded the annual §3 Nr. 26/26a EStG cap elsewhere (VolunteerAllowanceDeclarationSource:
// IN_APP/ON_PAPER). See `network.lapis.cloud.server.rpc.VolunteerAllowanceService` KDoc for the
// full state machine and `docs/architecture/volunteer-allowance.adoc` for the fachlich rationale.
//
// No partial unique index (same portability constraint every other wave in this repo documents):
// the whole test suite runs H2 in MODE=PostgreSQL (DatabaseConfig.kt), which rejects
// `CREATE UNIQUE INDEX ... WHERE`. "At most one posted journal entry per payment" is instead
// enforced via a PLAIN (not partial) unique index directly on the nullable
// `posted_journal_entry_id` column, same property `uq_ter_posted_journal_entry` already relies on.
//
// No circular FK: `posted_journal_entry_id` FKs -> journal_entry, and journal_entry has no FK back
// to volunteer_allowance_payment -- no cycle for `OrganizationSchemaCatalog.restoreOrder`'s
// topological sort to resolve.
//
// Deliberately NO `association(...)` blocks -- every FK on these two entities is already generated
// from its own explicit «Column».fkEntity tag above, same reasoning 45-travel-expense.kuml.kts's
// own file header documents.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "VolunteerAllowance") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern every other domain
    // file establishes. Only exists here so UmlToErmTransformer can resolve
    // volunteer_allowance_payment.subject_member_id / .requested_by / .decided_by /
    // .cap_acknowledged_by and volunteer_allowance_self_declaration.member_id / .recorded_by
    // within this single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // 10-accounting.kuml.kts-owned stub — id-only. Only exists here so UmlToErmTransformer can
    // resolve volunteer_allowance_payment.posted_journal_entry_id's association target.
    val journalEntry = classOf(name = "JournalEntry") {
        stereotype("Entity") { "tableName" to "journal_entry"; "kotlinObjectName" to "JournalEntryTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order is load-bearing (VolunteerAllowanceSchemaDriftTest pins ErmDataType.Enum.values
    // against network.lapis.cloud.shared.domain.VolunteerAllowanceCategory in exactly this order)
    // -- append only, never reorder.
    val volunteerAllowanceCategory = enumOf(name = "VolunteerAllowanceCategory") {
        literal(name = "INSTRUCTOR")
        literal(name = "HONORARY")
    }

    // Literal order load-bearing, same reason as volunteerAllowanceCategory.
    val volunteerAllowancePaymentStatus = enumOf(name = "VolunteerAllowancePaymentStatus") {
        literal(name = "DRAFT")
        literal(name = "REQUESTED")
        literal(name = "APPROVED")
        literal(name = "REJECTED")
        literal(name = "EXECUTED")
        literal(name = "WITHDRAWN")
    }

    // Literal order load-bearing, same reason again.
    val volunteerAllowanceDeclarationSource = enumOf(name = "VolunteerAllowanceDeclarationSource") {
        literal(name = "IN_APP")
        literal(name = "ON_PAPER")
    }
    // NOTE: VolunteerAllowanceVerdict is deliberately NOT modelled here -- it is a pure
    // calculation result (network.lapis.cloud.shared.domain.VolunteerAllowanceVerdict), never a
    // column type, see that enum's own KDoc.

    val volunteerAllowancePayment = classOf(name = "VolunteerAllowancePayment") {
        stereotype("Entity") { "tableName" to "volunteer_allowance_payment"; "kotlinObjectName" to "VolunteerAllowancePaymentTable" }

        stereotype("Index") { "columns" to listOf("subject_member_id"); "name" to "idx_vap_subject" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_vap_status" }
        stereotype("Index") { "columns" to listOf("submitted_at"); "name" to "idx_vap_submitted_at" }
        stereotype("Index") {
            "columns" to listOf("subject_member_id", "category", "payment_date")
            "name" to "idx_vap_subject_cat_date"
        }
        // Buchungs-Idempotenz -- plain (nicht partieller) Unique-Index auf der nullable Spalte,
        // siehe Datei-Header.
        stereotype("Index") {
            "columns" to listOf("posted_journal_entry_id")
            "unique" to true
            "name" to "uq_vap_posted_journal_entry"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "subjectMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "subject_member_id"; "fkEntity" to "Member" }
        }
        // Auto-computed width: INSTRUCTOR (10) -> VARCHAR(10), matching V30__volunteer_allowance.sql.
        attribute(name = "category", type = volunteerAllowanceCategory) {
            stereotype("Column") {
                "columnName" to "category"
                "enumType" to "network.lapis.cloud.shared.domain.VolunteerAllowanceCategory"
            }
        }
        // Auto-computed width: REQUESTED/WITHDRAWN (9) -> VARCHAR(9), matching the migration.
        attribute(name = "status", type = volunteerAllowancePaymentStatus) {
            stereotype("Column") {
                "columnName" to "status"
                "enumType" to "network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus"
            }
        }
        attribute(name = "amount", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount"; "sqlType" to "NUMERIC(12,2)" }
        }
        attribute(name = "activityDescription", type = "String") {
            stereotype("Column") { "columnName" to "activity_description"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "paymentDate", type = "LocalDate") {
            stereotype("Column") { "columnName" to "payment_date" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "requestedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "requested_by"; "fkEntity" to "Member" }
        }
        attribute(name = "submittedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "submitted_at" }
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
        // Idempotenz-Anker -- non-null gdw. status == EXECUTED (chk_vap_posted_entry_state).
        attribute(name = "postedJournalEntryId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "posted_journal_entry_id"; "fkEntity" to "JournalEntry" }
        }
        attribute(name = "executionError", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "execution_error"; "sqlType" to "VARCHAR(500)" }
        }
        // Snapshots -- frozen at decision time (Duarte-Ruling), non-null once decided.
        attribute(name = "priorTotalSnapshot", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "prior_total_snapshot"; "sqlType" to "NUMERIC(12,2)" }
        }
        attribute(name = "freeAmountSnapshot", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "free_amount_snapshot"; "sqlType" to "NUMERIC(12,2)" }
        }
        attribute(name = "exceedingAmountSnapshot", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "exceeding_amount_snapshot"; "sqlType" to "NUMERIC(12,2)" }
        }
        // Board cap-acknowledgment, all-or-nothing (chk_vap_cap_ack_shape) -- see
        // VolunteerAllowanceCapDisclaimer KDoc.
        attribute(name = "capDisclaimerVersion", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cap_disclaimer_version"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "capDisclaimerSha256", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cap_disclaimer_sha256"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "capAcknowledgedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cap_acknowledged_by"; "fkEntity" to "Member" }
        }
        attribute(name = "capAcknowledgedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cap_acknowledged_at" }
        }
    }

    val volunteerAllowanceSelfDeclaration = classOf(name = "VolunteerAllowanceSelfDeclaration") {
        stereotype("Entity") {
            "tableName" to "volunteer_allowance_self_declaration"
            "kotlinObjectName" to "VolunteerAllowanceSelfDeclarationTable"
        }

        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_vasd_member" }
        stereotype("Index") {
            "columns" to listOf("member_id", "category", "calendar_year")
            "unique" to true
            "name" to "uq_vasd_member_category_year"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "category", type = volunteerAllowanceCategory) {
            stereotype("Column") {
                "columnName" to "category"
                "enumType" to "network.lapis.cloud.shared.domain.VolunteerAllowanceCategory"
            }
        }
        attribute(name = "calendarYear", type = "Int") {
            stereotype("Column") { "columnName" to "calendar_year" }
        }
        // Auto-computed width: ON_PAPER (8) -> VARCHAR(8), matching the migration.
        attribute(name = "source", type = volunteerAllowanceDeclarationSource) {
            stereotype("Column") {
                "columnName" to "source"
                "enumType" to "network.lapis.cloud.shared.domain.VolunteerAllowanceDeclarationSource"
            }
        }
        attribute(name = "declaredAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "declared_at" }
        }
        // ON_PAPER only -- non-null gdw. source == ON_PAPER (chk_vasd_source_shape).
        attribute(name = "signedOn", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "signed_on" }
        }
        attribute(name = "recordedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "recorded_by"; "fkEntity" to "Member" }
        }
    }
}
