// Multi-Banking foundation, "mehrere Bankkonten" (Welle V1.4.14) -- bank_account
// (V32__bank_account.sql). Addendum in 40-bank-statement.kuml.kts (bank_statement_import
// .bank_account_id) and 14-audit-log.kuml.kts (BANK_ACCOUNT entity_type).
//
// Wave 2 "FinTS/HBCI-Live-Kontoabruf" (V33__bank_account_fints.sql): the license decision this
// file's header used to call "open" is now MADE -- hbci4j-core:4.0.0 (LGPL-2.1, Maven Central), see
// gradle/libs.versions.toml and docs/architecture/bank-account.adoc for the full rationale. Eleven
// new fintsXxx attributes below carry the per-account FinTS credentials/status; a NEW entity
// (BankAccountFinTsAcknowledgment) is the append-only per-account compliance receipt (structural
// twin of vat_compliance_acknowledgment). Status machine has EXACTLY three states -- see
// FinTsStatus (lapis-shared) and chk_bank_account_fints_status.
//
// Review-Fix Runde 3 (MEDIUM, V34__bank_account_fints_gap.sql): three more fintsGapXxx attributes
// -- the DURABLE, never-overwritten-by-an-ordinary-success record of the MOST RECENTLY detected
// fetch-window gap, deliberately separate from the transient/self-healing fintsLastErrorCode above
// -- see FinTsPoller.handleMt940 KDoc.
//
// Why `defaultMarker`: H2 in MODE=PostgreSQL (this codebase's test dialect) rejects a partial
// UNIQUE index (`WHERE is_default`) -- same shadow-column idiom V28's contribution_relief already
// establishes for an analogous "at most one row flagged" constraint.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "BankAccount") {
    applyProfile(ermMappingProfile)

    // Cross-domain stub — id-only, mirrors the pattern established across every prior domain file.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val bankAccount = classOf(name = "BankAccount") {
        stereotype("Entity") { "tableName" to "bank_account"; "kotlinObjectName" to "BankAccountTable" }
        stereotype("Index") { "columns" to listOf("iban"); "unique" to true; "name" to "uq_bank_account_iban" }
        stereotype("Index") { "columns" to listOf("default_marker"); "unique" to true; "name" to "uq_bank_account_default" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Free-text display name ("Hauptkonto", "Sparkonto Rücklage", ...) -- the ONLY way a
        // treasurer tells several accounts apart in a list, since IBAN alone is not memorable.
        attribute(name = "label", type = "String") {
            stereotype("Column") { "columnName" to "label"; "sqlType" to "VARCHAR(120)" }
        }
        attribute(name = "iban", type = "String") {
            stereotype("Column") { "columnName" to "iban"; "sqlType" to "VARCHAR(34)" }
        }
        attribute(name = "bic", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "bic"; "sqlType" to "VARCHAR(11)" }
        }
        attribute(name = "bankName", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "bank_name"; "sqlType" to "VARCHAR(140)" }
        }
        // Exactly one row has isDefault=true at any time (chk_bank_account_default_marker +
        // uq_bank_account_default together enforce this) -- its iban/bic are mirrored into
        // organization_settings.bank_iban/bank_bic by BankAccountStore, see that object's KDoc.
        attribute(name = "isDefault", type = "Boolean") {
            stereotype("Column") { "columnName" to "is_default" }
        }
        attribute(name = "defaultMarker", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "default_marker"; "sqlType" to "VARCHAR(1)" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }

        // ---------------------------------------------------------------------------------------
        // Wave 2 "FinTS/HBCI-Live-Kontoabruf" (V33__bank_account_fints.sql) -- eleven attributes.
        // ---------------------------------------------------------------------------------------
        attribute(name = "fintsBlz", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fints_blz"; "sqlType" to "VARCHAR(8)" }
        }
        attribute(name = "fintsUrl", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fints_url"; "sqlType" to "VARCHAR(2048)" }
        }
        // SecretBox-versiegelt (Wire-Format "v1:<iv>:<ct>") -- niemals im Klartext, siehe
        // FinTsClient.kt KDoc.
        attribute(name = "fintsUserIdCiphertext", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fints_user_id_ciphertext"; "sqlType" to "VARCHAR(1024)" }
        }
        attribute(name = "fintsPinCiphertext", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fints_pin_ciphertext"; "sqlType" to "VARCHAR(1024)" }
        }
        // Exactly three values -- NOT_CONFIGURED, ACTIVE, REAUTH_REQUIRED (chk_bank_account_fints_status).
        attribute(name = "fintsStatus", type = "String") {
            stereotype("Column") { "columnName" to "fints_status"; "sqlType" to "VARCHAR(16)" }
        }
        attribute(name = "fintsActivatedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fints_activated_by"; "fkEntity" to "Member" }
        }
        attribute(name = "fintsActivatedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fints_activated_at" }
        }
        attribute(name = "fintsPinSetAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fints_pin_set_at" }
        }
        attribute(name = "fintsLastSuccessAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fints_last_success_at" }
        }
        attribute(name = "fintsLastFetchTo", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fints_last_fetch_to" }
        }
        attribute(name = "fintsLastErrorCode", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fints_last_error_code"; "sqlType" to "VARCHAR(32)" }
        }

        // -----------------------------------------------------------------------------------
        // Review-Fix Runde 3 (MEDIUM, V34__bank_account_fints_gap.sql) -- three attributes.
        // -----------------------------------------------------------------------------------
        attribute(name = "fintsGapFrom", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fints_gap_from" }
        }
        attribute(name = "fintsGapTo", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fints_gap_to" }
        }
        attribute(name = "fintsGapDetectedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fints_gap_detected_at" }
        }
    }

    // Deliberately NO association() for created_by/fintsActivatedBy -- bankAccount already declares
    // both as explicit «Column».fkEntity attributes above, same idiom 40-bank-statement.kuml.kts's
    // own file header documents (avoids UmlToErmTransformer deriving a second, redundant FK column).

    // ---------------------------------------------------------------------------------------------
    // Wave 2 -- append-only per-account compliance receipt, structural twin of
    // vat_compliance_acknowledgment (43-vat.kuml.kts) / sepa_compliance_acknowledgment, extended by
    // bankAccountId because the receipt is PER ACCOUNT, not organization-wide.
    // ---------------------------------------------------------------------------------------------
    val bankAccountFinTsAcknowledgment = classOf(name = "BankAccountFinTsAcknowledgment") {
        stereotype("Entity") {
            "tableName" to "bank_account_fints_acknowledgment"
            "kotlinObjectName" to "BankAccountFinTsAcknowledgmentTable"
        }
        stereotype("Index") {
            "columns" to listOf("bank_account_id", "acknowledged_at")
            "unique" to false
            "name" to "idx_ba_fints_ack_account"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "bankAccountId", type = "UUID") {
            stereotype("Column") { "columnName" to "bank_account_id"; "fkEntity" to "BankAccount" }
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
