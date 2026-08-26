// Dunning domain -- Welle V1.2.7 "Automatisiertes Mahnwesen" (vault plan "Umsetzungsplan V1.2.7").
// Adds the configurable dunning-level ladder (`dunning_level`), the append-only per-contribution
// dunning history (`dunning_notice`), and a SEPA/Auktion-style disclaimer-acknowledgment gate
// table (`dunning_compliance_acknowledgment`).
//
// Older code comments (33-payments.kuml.kts/01-contribution.kuml.kts headers, CHANGELOG.md) named
// this feature "V1.2.3" -- that number was since assigned to the SMTP-mail-dispatch wave. This is
// the corrected, actually-implemented wave number; see this file's own "V1.2.7" naming throughout.
//
// **Scope.** `dunning_level`/`dunning_notice` never touch accounting -- no `AccountingService`/
// `ContributionPostingBridge`/`CashRegisterGuard` call anywhere in this domain's write path, same
// doctrine `SepaBatchPoller`'s own KDoc already established ("this poller never touches
// accounting"). `dunning_notice.fee_amount`/`amount_due` are captured for the printed letter only
// -- `contribution.amount_due` itself is never modified by this wave.
//
// Cross-domain stubs: minimal id-only Member/Contribution/Document/PostalDeliveryLog (each owned
// elsewhere -- 00-foundation.kuml.kts/01-contribution.kuml.kts/02-document.kuml.kts/
// 12-postal-mail.kuml.kts respectively), same pattern 33-payments.kuml.kts already establishes.
//
// `contribution.status`'s existing `DUNNABLE` set (`OVERDUE`/`RETURNED`/`IN_DUNNING`) and
// `organization_settings.dunning_enabled` (modelled in 11-organization-settings.kuml.kts, which
// owns that table) are the two runtime gates this wave's poller/service check -- see
// `network.lapis.cloud.server.payment.dunning.DunningPoller`/`DunningService` KDoc.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Dunning") {
    applyProfile(ermMappingProfile)

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val contribution = classOf(name = "Contribution") {
        stereotype("Entity") { "tableName" to "contribution"; "kotlinObjectName" to "ContributionTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val document = classOf(name = "Document") {
        stereotype("Entity") { "tableName" to "document"; "kotlinObjectName" to "DocumentTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val postalDeliveryLog = classOf(name = "PostalDeliveryLog") {
        stereotype("Entity") { "tableName" to "postal_delivery_log"; "kotlinObjectName" to "PostalDeliveryLogTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Terminal per-notice outcome. Longest literal CANCELLED (9) -> VARCHAR(9).
    val dunningNoticeStatus = enumOf(name = "DunningNoticeStatus") {
        literal(name = "ISSUED")
        literal(name = "SKIPPED")
        literal(name = "CANCELLED")
    }

    val dunningLevel = classOf(name = "DunningLevel") {
        stereotype("Entity") { "tableName" to "dunning_level"; "kotlinObjectName" to "DunningLevelTable" }
        stereotype("Index") {
            "columns" to listOf("level_number")
            "unique" to true
            "name" to "uq_dunning_level_number"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Sortier- und Eskalationsschluessel, e.g. 1, 2, 3. Unique across all levels (active or not).
        attribute(name = "levelNumber", type = "Int") {
            stereotype("Column") { "columnName" to "level_number" }
        }
        // e.g. "Zahlungserinnerung", "1. Mahnung" -- appears as the PDF heading.
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(100)" }
        }
        // Wait time BEFORE this level fires, counted from the reference date -- see
        // DunningIssuance KDoc "Faelligkeit" for the reference-date computation.
        attribute(name = "graceDays", type = "Int") {
            stereotype("Column") { "columnName" to "grace_days" }
        }
        // The payment deadline THIS notice sets on the letter (issuedOn + responseDays) -- kept
        // deliberately separate from graceDays, see file header/plan Teil 2.1 "Zwei getrennte
        // Tagesangaben" for why one field for both would couple PDF wording to escalation timing.
        attribute(name = "responseDays", type = "Int") {
            stereotype("Column") { "columnName" to "response_days" }
        }
        attribute(name = "feeAmount", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fee_amount"; "sqlType" to "DECIMAL(12,2)" }
        }
        attribute(name = "active", type = "Boolean") {
            defaultValue = "TRUE"
            stereotype("Column") { "columnName" to "active" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }

    // uq_dunning_notice_slot is the idempotency anchor: at most one notice per
    // (contribution, cycle, level). See DunningIssuance KDoc "cycle_number" for why a cycle counter
    // is used instead of a partial "WHERE status <> CANCELLED" index (H2 cannot express one).
    val dunningNotice = classOf(name = "DunningNotice") {
        stereotype("Entity") { "tableName" to "dunning_notice"; "kotlinObjectName" to "DunningNoticeTable" }
        stereotype("Index") {
            "columns" to listOf("contribution_id", "cycle_number", "level_number")
            "unique" to true
            "name" to "uq_dunning_notice_slot"
        }
        stereotype("Index") { "columns" to listOf("contribution_id"); "name" to "idx_dunning_notice_contribution" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_dunning_notice_status" }
        stereotype("Index") { "columns" to listOf("issued_at"); "name" to "idx_dunning_notice_issued_at" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "contributionId", type = "UUID") {
            stereotype("Column") { "columnName" to "contribution_id"; "fkEntity" to "Contribution" }
        }
        attribute(name = "dunningLevelId", type = "UUID") {
            stereotype("Column") { "columnName" to "dunning_level_id"; "fkEntity" to "DunningLevel" }
        }
        // Which dunning run this notice belongs to -- incremented on resetDunning. See
        // DunningIssuance KDoc "cycle_number".
        attribute(name = "cycleNumber", type = "Int") {
            defaultValue = "1"
            stereotype("Column") { "columnName" to "cycle_number" }
        }
        // Frozen from dunning_level at issuance time -- dunning_level itself remains editable.
        attribute(name = "levelNumber", type = "Int") {
            stereotype("Column") { "columnName" to "level_number" }
        }
        attribute(name = "levelName", type = "String") {
            stereotype("Column") { "columnName" to "level_name"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "feeAmount", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fee_amount"; "sqlType" to "DECIMAL(12,2)" }
        }
        // Frozen contribution.amount_due at the moment this notice was issued.
        attribute(name = "amountDue", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount_due"; "sqlType" to "DECIMAL(12,2)" }
        }
        attribute(name = "status", type = dunningNoticeStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.DunningNoticeStatus" }
        }
        attribute(name = "issuedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "issued_at" }
        }
        attribute(name = "respondBy", type = "LocalDate") {
            stereotype("Column") { "columnName" to "respond_by" }
        }
        // Archived PDF -- NULL for a SKIPPED notice, or transiently NULL between T2/T3 (self-healed
        // by DunningPoller Phase C, see that class's own KDoc).
        attribute(name = "documentId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "document_id"; "fkEntity" to "Document" }
        }
        // Set only if this notice was actually mailed via Letterxpress.
        attribute(name = "postalDeliveryLogId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "postal_delivery_log_id"; "fkEntity" to "PostalDeliveryLog" }
        }
        // NULL == the poller/System issued this notice -- same convention as
        // SepaBatchPoller/AuditLogRecorder.record(actorMemberId = null).
        attribute(name = "createdBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "cancelledAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cancelled_at" }
        }
        attribute(name = "cancellationReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cancellation_reason"; "sqlType" to "VARCHAR(500)" }
        }
    }

    // Exact structural mirror of sepa_compliance_acknowledgment (33-payments.kuml.kts) -- append-
    // only proof of who acknowledged which DunningComplianceDisclaimer version, never a bare
    // boolean flip. See DunningService.enableDunning KDoc.
    val dunningComplianceAcknowledgment = classOf(name = "DunningComplianceAcknowledgment") {
        stereotype("Entity") {
            "tableName" to "dunning_compliance_acknowledgment"
            "kotlinObjectName" to "DunningComplianceAcknowledgmentTable"
        }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "acknowledgedByMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "acknowledged_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "acknowledgedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "acknowledged_at" }
        }
        attribute(name = "disclaimerVersion", type = "String") {
            stereotype("Column") { "columnName" to "disclaimer_version"; "sqlType" to "VARCHAR(20)" }
        }
        attribute(name = "disclaimerSha256", type = "String") {
            stereotype("Column") { "columnName" to "disclaimer_sha256"; "sqlType" to "VARCHAR(64)" }
        }
    }
}
