// Bank statement import domain (V1.4.5.1 "Kontoauszugs-Import (CSV/MT940)") --
// bank_statement_import/bank_statement_line (V20__bank_statement_import.sql).
//
// Scope-Cut this wave (see plan "Umsetzungsplan V1.4.5.1"): DATEV/lexoffice/sevDesk-Export
// (V1.4.5.2 ff.), camt.052/053/054-XML, FinTS/HBCI direct bank connectivity, Teilzahlung/Splitting
// (a bank line matched to more than one contribution), Rücklastschrift-Erkennung THROUGH this
// import path (SEPA returns already have their own dedicated path, `sepa_return`), and automatic
// donation posting (§25 PartG: a donor category cannot be derived from a bank line alone, see
// `BankStatementMatcher` KDoc "R4") are all explicitly OUT of scope.
//
// Why no `rawLine` column: the raw bank-statement line text is deliberately NEVER persisted here
// (DSGVO minimization) -- it surfaces transiently in a 422 rejection's HTTP response body only
// (`BankStatementImportService`), never written to the database or to any log line.
//
// Why no new idempotency index is needed for the BOOKING itself: `payment_transaction`'s existing
// `uq_payment_transaction_provider_event` (33-payments.kuml.kts, `PaymentProvider.MANUAL` +
// `fingerprint` as the `providerEventId`) already anchors that -- see `BankStatementImportService`
// KDoc. This file's OWN `uq_bank_statement_line_fingerprint` is a SEPARATE, earlier idempotency
// layer: it dedups a re-imported/overlapping statement file BEFORE matching ever runs, so a
// re-uploaded PDF-turned-CSV export of the same period does not even reach the matcher a second
// time, let alone the booking step.
//
// Why `counterpartyIban` is encrypted, not plaintext: mirrors `sepa_mandate.debtor_iban_ciphertext`
// exactly (33-payments.kuml.kts) -- `counterpartyIbanLast4` (a plaintext prefiltering column) plus
// `counterpartyIbanCiphertext` (SecretBox, AAD = this row's own id) rather than a plaintext IBAN
// column, so a counterparty's account number is never at rest in the clear. Without
// `LAPIS_SECRET_ENCRYPTION_KEY` configured, the IBAN is not stored at all (both columns stay
// `NULL`) and the R2 IBAN-matching rule in `BankStatementMatcher` is skipped -- never a silent
// plaintext fallback, same posture `SepaConfig` KDoc documents for the mandate path.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "BankStatementImport") {
    applyProfile(ermMappingProfile)

    // Cross-domain stubs — id-only, mirror the pattern established across every prior domain file.
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
    val paymentTransaction = classOf(name = "PaymentTransaction") {
        stereotype("Entity") { "tableName" to "payment_transaction"; "kotlinObjectName" to "PaymentTransactionTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order load-bearing (BankStatementSchemaDriftTest pins it against
    // network.lapis.cloud.shared.domain.BankStatementFormat). Longest literal MT940 (5) -> VARCHAR(6).
    val bankStatementFormat = enumOf(name = "BankStatementFormat") {
        literal(name = "CSV")
        literal(name = "MT940")
    }

    // Literal order load-bearing, same reason. Longest literal AMBIGUOUS (9) -> VARCHAR(10).
    val bankStatementLineStatus = enumOf(name = "BankStatementLineStatus") {
        literal(name = "UNMATCHED")
        literal(name = "SUGGESTED")
        literal(name = "AMBIGUOUS")
        literal(name = "POSTED")
        literal(name = "IGNORED")
    }

    val bankStatementImport = classOf(name = "BankStatementImport") {
        stereotype("Entity") { "tableName" to "bank_statement_import"; "kotlinObjectName" to "BankStatementImportTable" }
        stereotype("Index") { "columns" to listOf("uploaded_at"); "name" to "idx_bank_statement_import_uploaded" }
        // Review fix (MINOR, model drift): backs the 409 "already imported" check
        // (BankStatementImportService.import's own Phase 1) with a real constraint -- see
        // V20__bank_statement_import.sql's own "Review fix (MINOR)" comment on this same index. Was
        // added to the migration without ever being reflected here, so a reader treating this kUML
        // script as the design source of truth (ADR-0016) could not see that the 409 path is
        // load-bearing, not merely advisory. Same "unique" shape BankStatementLine's own
        // uq_bank_statement_line_fingerprint index below already establishes.
        stereotype("Index") {
            "columns" to listOf("file_digest")
            "unique" to true
            "name" to "uq_bank_statement_import_file_digest"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "format", type = bankStatementFormat) {
            stereotype("Column") { "columnName" to "format"; "enumType" to "network.lapis.cloud.shared.domain.BankStatementFormat" }
        }
        // Not an enum -- BankCsvDialect (server-internal) is a wider, growable catalogue than the
        // wire-level BankStatementFormat, and MT940 statements carry the fixed literal "MT940" here
        // too (there is only one MT940 dialect). VARCHAR(32) generously bounds any dialect name.
        attribute(name = "dialect", type = "String") {
            stereotype("Column") { "columnName" to "dialect"; "sqlType" to "VARCHAR(32)" }
        }
        attribute(name = "fileName", type = "String") {
            stereotype("Column") { "columnName" to "file_name"; "sqlType" to "VARCHAR(255)" }
        }
        attribute(name = "fileSizeBytes", type = "Long") {
            stereotype("Column") { "columnName" to "file_size_bytes" }
        }
        // SHA-256 hex of the raw uploaded bytes -- the whole-file idempotency anchor (409 on retry).
        attribute(name = "fileDigest", type = "String") {
            stereotype("Column") { "columnName" to "file_digest"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "accountIban", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "account_iban"; "sqlType" to "VARCHAR(34)" }
        }
        attribute(name = "statementFrom", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "statement_from" }
        }
        attribute(name = "statementTo", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "statement_to" }
        }
        // MT940-only (:60F:/:62F: opening/closing balance) -- always null for a CSV import, which
        // carries no statement-level balance at all.
        attribute(name = "openingBalance", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "opening_balance"; "sqlType" to "DECIMAL(14,2)" }
        }
        attribute(name = "closingBalance", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "closing_balance"; "sqlType" to "DECIMAL(14,2)" }
        }
        attribute(name = "lineCount", type = "Int") {
            stereotype("Column") { "columnName" to "line_count" }
        }
        attribute(name = "duplicateCount", type = "Int") {
            stereotype("Column") { "columnName" to "duplicate_count" }
        }
        attribute(name = "autoPostedCount", type = "Int") {
            stereotype("Column") { "columnName" to "auto_posted_count" }
        }
        attribute(name = "uploadedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "uploaded_by"; "fkEntity" to "Member" }
        }
        attribute(name = "uploadedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "uploaded_at" }
        }
    }

    val bankStatementLine = classOf(name = "BankStatementLine") {
        stereotype("Entity") { "tableName" to "bank_statement_line"; "kotlinObjectName" to "BankStatementLineTable" }
        stereotype("Index") {
            "columns" to listOf("fingerprint")
            "unique" to true
            "name" to "uq_bank_statement_line_fingerprint"
        }
        stereotype("Index") { "columns" to listOf("status", "booking_date"); "name" to "idx_bank_statement_line_status" }
        stereotype("Index") { "columns" to listOf("import_id", "line_ordinal"); "name" to "idx_bank_statement_line_import" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "importId", type = "UUID") {
            stereotype("Column") { "columnName" to "import_id"; "fkEntity" to "BankStatementImport" }
        }
        // SHA-256 hex over the normalized field tuple (BankStatementFingerprint) -- the per-line
        // idempotency anchor. See file header "Why no new idempotency index is needed" for how this
        // differs from payment_transaction's own booking-level anchor.
        attribute(name = "fingerprint", type = "String") {
            stereotype("Column") { "columnName" to "fingerprint"; "sqlType" to "VARCHAR(64)" }
        }
        // 0-based position within the uploaded file -- stable display/debug ordering, and the
        // occurrence-index tie-breaker input for BankStatementFingerprint (two otherwise-identical
        // standing-order lines on the same day get different fingerprints via this ordinal).
        attribute(name = "lineOrdinal", type = "Int") {
            stereotype("Column") { "columnName" to "line_ordinal" }
        }
        attribute(name = "bookingDate", type = "LocalDate") {
            stereotype("Column") { "columnName" to "booking_date" }
        }
        attribute(name = "valueDate", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "value_date" }
        }
        // Signed -- positive is a credit (Gutschrift), negative a debit (Lastschrift/Ausgang). A
        // non-positive amount is never matched against a contribution (BankStatementMatcher's own
        // "Vorregel") -- an outgoing payment is never a membership-fee receipt.
        attribute(name = "amount", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount"; "sqlType" to "DECIMAL(14,2)" }
        }
        attribute(name = "currency", type = "String") {
            stereotype("Column") { "columnName" to "currency"; "sqlType" to "VARCHAR(3)" }
        }
        attribute(name = "counterpartyName", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "counterparty_name"; "sqlType" to "VARCHAR(140)" }
        }
        // See file header "Why counterpartyIban is encrypted". Always both-null or both-set --
        // enforced by chk_bank_statement_line_iban_pair (SQL-only, cross-field CHECKs are not
        // modelled in kUML, same posture 38-crm.kuml.kts/39-events.kuml.kts already take).
        attribute(name = "counterpartyIbanLast4", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "counterparty_iban_last4"; "sqlType" to "VARCHAR(4)" }
        }
        attribute(name = "counterpartyIbanCiphertext", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "counterparty_iban_ciphertext"; "sqlType" to "VARCHAR(1024)" }
        }
        attribute(name = "purpose", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "purpose"; "sqlType" to "VARCHAR(2000)" }
        }
        attribute(name = "endToEndReference", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "end_to_end_reference"; "sqlType" to "VARCHAR(140)" }
        }
        attribute(name = "bookingText", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "booking_text"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "status", type = bankStatementLineStatus) {
            stereotype("Column") {
                "columnName" to "status"
                "enumType" to "network.lapis.cloud.shared.domain.BankStatementLineStatus"
            }
        }
        // Written by BankStatementMatcher BEFORE any booking happens -- persisted, never
        // reconstructed at read time (Design-Team requirement: the reasoning behind a match/
        // non-match must survive a later re-read of the same line).
        attribute(name = "matchExplanation", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "match_explanation"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "matchedContributionId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "matched_contribution_id"; "fkEntity" to "Contribution" }
        }
        attribute(name = "paymentTransactionId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "payment_transaction_id"; "fkEntity" to "PaymentTransaction" }
        }
        attribute(name = "resolvedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "resolved_by"; "fkEntity" to "Member" }
        }
        attribute(name = "resolvedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "resolved_at" }
        }
        attribute(name = "resolutionNote", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "resolution_note"; "sqlType" to "VARCHAR(500)" }
        }
    }

    // Deliberately NO association() for import_id -- bankStatementLine already declares it as an
    // explicit «Column».fkEntity attribute above (same idiom as matchedContributionId/
    // paymentTransactionId/resolvedBy/uploadedBy in this same file). Adding an association on TOP
    // of that explicit attribute makes UmlToErmTransformer ALSO auto-derive a second, redundant FK
    // column from the association's own default naming (`bank_statement_import_id`) alongside the
    // explicit one -- discovered by BankStatementSchemaDriftTest during this wave's implementation.
    // 01-contribution.kuml.kts's member_id/membership_tier_id use the OPPOSITE, association-only
    // idiom (no explicit attribute at all) -- the two mechanisms are alternatives, never combined
    // for the same column.
}
