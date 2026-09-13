// Multi-Banking foundation, "mehrere Bankkonten" (Welle V1.4.14) -- bank_account
// (V32__bank_account.sql). Addendum in 40-bank-statement.kuml.kts (bank_statement_import
// .bank_account_id) and 14-audit-log.kuml.kts (BANK_ACCOUNT entity_type).
//
// SCOPE-CUT this wave (see docs/architecture/bank-account.adoc "Scope"): FinTS/HBCI direct bank
// connectivity is deliberately NOT modelled here. There is an open license decision
// (hbci4j-core is LGPL-2.1, no version-catalog precedent for that license in this codebase yet)
// and no bank-protocol implementation -- only the multi-account foundation (several bank_account
// rows, each file import attributed to exactly one of them). FinTS credential/live-status columns
// are a deliberately separate, later migration once that decision is made -- see
// docs/architecture/bank-account.adoc "Deferred: FinTS/HBCI live retrieval".
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
    }

    // Deliberately NO association() for created_by -- bankAccount already declares it as an
    // explicit «Column».fkEntity attribute above, same idiom 40-bank-statement.kuml.kts's own file
    // header documents (avoids UmlToErmTransformer deriving a second, redundant FK column).
}
