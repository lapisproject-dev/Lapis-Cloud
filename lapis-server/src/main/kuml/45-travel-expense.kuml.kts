// Travel-expense domain — travel_expense_report/travel_expense_line/travel_expense_receipt
// (V29__travel_expense.sql; travel_expense_attachment: V113__travel_expense_attachment.sql), plus three new `organization_settings` columns forward-declared here
// and carried for real in 11-organization-settings.kuml.kts (OrganizationSettings is that file's
// owning domain -- see that file's own file header convention for why the real declaration always
// lives with the owning entity, never here).
//
// Welle V1.4.11 "Reisekostenabrechnung für Vorstand und Funktionsträger" -- travel_expense_report
// tracks a self-service reimbursement request (TravelExpenseReportStatus:
// DRAFT/REQUESTED/APPROVED/REJECTED/EXECUTED/WITHDRAWN), travel_expense_line one line of it
// (TravelExpenseLineKind: MILEAGE/PER_DIEM/RECEIPTED), travel_expense_receipt an uploaded proof
// file attached to a RECEIPTED (or any) line. See
// `network.lapis.cloud.server.rpc.TravelExpenseService` KDoc for the full state machine and
// `docs/architecture/travel-expenses.adoc` for the fachlich rationale (unlike V1.4.10
// "Beitragsvergünstigungen", this domain DOES get a dedicated architecture doc -- Design-Team
// decision, see that doc's own header).
//
// No partial unique index (same portability constraint every other wave in this repo documents):
// the whole test suite runs H2 in MODE=PostgreSQL (DatabaseConfig.kt), which rejects
// `CREATE UNIQUE INDEX ... WHERE`. "At most one posted journal entry per report" is instead
// enforced via a PLAIN (not partial) unique index directly on the nullable
// `posted_journal_entry_id` column -- multiple NULLs are allowed under a plain unique index on
// both H2 and real Postgres, same property `uq_crr_active_request` already relies on.
//
// No circular FK: `posted_journal_entry_id` FKs -> journal_entry, and journal_entry has no FK
// back to travel_expense_report -- no cycle for `OrganizationSchemaCatalog.restoreOrder`'s
// topological sort to resolve, unlike the K-4 case V28 had to work around.
//
// Deliberately NO `association(...)` blocks -- every FK on these three entities is already
// generated from its own explicit «Column».fkEntity tag above, exactly the mechanism
// 44-contribution-relief.kuml.kts's own file header documents for why a redundant
// `association(...)` block would synthesize a SECOND, independently-named FK column.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "TravelExpense") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern every other domain
    // file establishes. Only exists here so UmlToErmTransformer can resolve
    // travel_expense_report.subject_member_id / .requested_by / .decided_by and
    // travel_expense_receipt.uploaded_by within this single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // 10-accounting.kuml.kts-owned stub — id-only. Only exists here so UmlToErmTransformer can
    // resolve travel_expense_report.posted_journal_entry_id's association target.
    val journalEntry = classOf(name = "JournalEntry") {
        stereotype("Entity") { "tableName" to "journal_entry"; "kotlinObjectName" to "JournalEntryTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // 47-bank-account.kuml.kts-owned stub -- id-only. V1.9.123: travel_expense_sepa_batch.debtor_bank_account_id
    // (the account a SEPA credit-transfer file debits) resolves against it.
    val bankAccount = classOf(name = "BankAccount") {
        stereotype("Entity") { "tableName" to "bank_account"; "kotlinObjectName" to "BankAccountTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order is load-bearing (TravelExpenseSchemaDriftTest pins ErmDataType.Enum.values
    // against network.lapis.cloud.shared.domain.TravelExpenseReportStatus in exactly this order)
    // -- append only, never reorder/insert among these six.
    val travelExpenseReportStatus = enumOf(name = "TravelExpenseReportStatus") {
        literal(name = "DRAFT")
        literal(name = "REQUESTED")
        literal(name = "APPROVED")
        literal(name = "REJECTED")
        literal(name = "EXECUTED")
        literal(name = "WITHDRAWN")
    }

    // Literal order load-bearing, same reason as travelExpenseReportStatus.
    // V1.9.122 appended the five literals after RECEIPTED (append only).
    val travelExpenseLineKind = enumOf(name = "TravelExpenseLineKind") {
        literal(name = "MILEAGE")
        literal(name = "PER_DIEM")
        literal(name = "RECEIPTED")
        literal(name = "DAY_ALLOWANCE")
        literal(name = "PUBLIC_TRANSPORT")
        literal(name = "ACCOMMODATION")
        literal(name = "HOSPITALITY")
        literal(name = "OTHER")
    }

    // V1.9.122 -- literal order load-bearing, append only.
    val travelExpensePayoutChoice = enumOf(name = "TravelExpensePayoutChoice") {
        literal(name = "PAYOUT")
        literal(name = "DONATION_WAIVER")
    }

    val travelExpenseReport = classOf(name = "TravelExpenseReport") {
        stereotype("Entity") { "tableName" to "travel_expense_report"; "kotlinObjectName" to "TravelExpenseReportTable" }

        stereotype("Index") { "columns" to listOf("subject_member_id"); "name" to "idx_ter_subject" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_ter_status" }
        stereotype("Index") { "columns" to listOf("submitted_at"); "name" to "idx_ter_submitted_at" }
        // Buchungs-Idempotenz -- plain (nicht partieller) Unique-Index auf der nullable Spalte,
        // siehe Datei-Header.
        stereotype("Index") {
            "columns" to listOf("posted_journal_entry_id")
            "unique" to true
            "name" to "uq_ter_posted_journal_entry"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "subjectMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "subject_member_id"; "fkEntity" to "Member" }
        }
        // No «Column».sqlType override on the enum-typed attribute -- see
        // 44-contribution-relief.kuml.kts's own comment on `kind` for why. Auto-computed width:
        // REQUESTED/WITHDRAWN (9) -> VARCHAR(9), matching V29__travel_expense.sql.
        attribute(name = "status", type = travelExpenseReportStatus) {
            stereotype("Column") {
                "columnName" to "status"
                "enumType" to "network.lapis.cloud.shared.domain.TravelExpenseReportStatus"
            }
        }
        attribute(name = "purpose", type = "String") {
            stereotype("Column") { "columnName" to "purpose"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "travelFrom", type = "LocalDate") {
            stereotype("Column") { "columnName" to "travel_from" }
        }
        attribute(name = "travelTo", type = "LocalDate") {
            stereotype("Column") { "columnName" to "travel_to" }
        }
        attribute(name = "totalAmount", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "total_amount"; "sqlType" to "NUMERIC(12,2)" }
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
        // Idempotenz-Anker -- non-null gdw. status == EXECUTED (chk_ter_posted_entry_state).
        attribute(name = "postedJournalEntryId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "posted_journal_entry_id"; "fkEntity" to "JournalEntry" }
        }
        attribute(name = "executionError", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "execution_error"; "sqlType" to "VARCHAR(500)" }
        }
        // ── V1.9.122 (V97__travel_expense_pdv_form.sql) ──
        attribute(name = "advanceAmount", type = "BigDecimal") {
            defaultValue = "0"
            stereotype("Column") { "columnName" to "advance_amount"; "sqlType" to "NUMERIC(12,2)" }
        }
        // Mandatory at submit (application rule); NULL for drafts and pre-V97 reports.
        attribute(name = "payoutChoice", type = travelExpensePayoutChoice) {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") {
                "columnName" to "payout_choice"
                "enumType" to "network.lapis.cloud.shared.domain.TravelExpensePayoutChoice"
            }
        }
        // Frozen at submit; cleared (NULL) by the DSGVO redaction, like member.member_number.
        attribute(name = "membershipNumberSnapshot", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "membership_number_snapshot"; "sqlType" to "VARCHAR(16)" }
        }
        // JSON of TravelExpenseRateSet, frozen at submit.
        attribute(name = "ratesSnapshot", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "rates_snapshot"; "sqlType" to "VARCHAR(2000)" }
        }
        // ── V1.9.123 (V98__travel_expense_sepa_credit_transfer.sql) ──
        // SecretBox ciphertext of the refund IBAN, AAD = "travel_expense_report.refund_iban:<reportId>". The only copy of the IBAN.
        attribute(name = "refundIbanCiphertext", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "refund_iban_ciphertext"; "sqlType" to "VARCHAR(512)" }
        }
        // Masking without decrypting; cleared together with the ciphertext (chk_ter_refund_iban_pair).
        attribute(name = "refundIbanLast4", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "refund_iban_last4"; "sqlType" to "VARCHAR(4)" }
        }
        attribute(name = "refundAccountHolder", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "refund_account_holder"; "sqlType" to "VARCHAR(70)" }
        }
        attribute(name = "refundAccountChangedAfterSubmit", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "refund_account_changed_after_submit" }
        }
        // TRUE for every report that was already booked as a payout when V98 ran: refunded by hand, never offered for a SEPA file.
        attribute(name = "paidOutsideLapis", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "paid_outside_lapis" }
        }
        // The three sepa_export* columns are all NULL or all set (chk_ter_sepa_export_consistent).
        attribute(name = "sepaExportedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "sepa_exported_at" }
        }
        attribute(name = "sepaExportBatchId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "sepa_export_batch_id"; "fkEntity" to "TravelExpenseSepaBatch" }
        }
        attribute(name = "sepaExportedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "sepa_exported_by"; "fkEntity" to "Member" }
        }
        attribute(name = "sepaReexportCount", type = "Int") {
            defaultValue = "0"
            stereotype("Column") { "columnName" to "sepa_reexport_count" }
        }
    }

    val travelExpenseLine = classOf(name = "TravelExpenseLine") {
        stereotype("Entity") { "tableName" to "travel_expense_line"; "kotlinObjectName" to "TravelExpenseLineTable" }

        stereotype("Index") { "columns" to listOf("report_id"); "name" to "idx_tel_report" }
        // V1.9.122 -- deliberately NOT unique, see V97 header ("one day line per day" is an application rule).
        stereotype("Index") { "columns" to listOf("report_id", "day_date"); "name" to "idx_tel_report_day" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "reportId", type = "UUID") {
            stereotype("Column") { "columnName" to "report_id"; "fkEntity" to "TravelExpenseReport" }
        }
        // Auto-computed width: RECEIPTED (9) -> VARCHAR(9), matching the migration.
        attribute(name = "kind", type = travelExpenseLineKind) {
            stereotype("Column") {
                "columnName" to "kind"
                "enumType" to "network.lapis.cloud.shared.domain.TravelExpenseLineKind"
            }
        }
        attribute(name = "description", type = "String") {
            stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(200)" }
        }
        // MILEAGE payload -- non-null iff kind == MILEAGE (chk_tel_kind_shape).
        attribute(name = "kilometers", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "kilometers"; "sqlType" to "NUMERIC(10,2)" }
        }
        // PER_DIEM payload -- non-null iff kind == PER_DIEM (chk_tel_kind_shape).
        attribute(name = "days", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "days" }
        }
        // MILEAGE/PER_DIEM only -- frozen at submitReport time, see TravelExpenseService KDoc.
        attribute(name = "rateSnapshot", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "rate_snapshot"; "sqlType" to "NUMERIC(10,4)" }
        }
        attribute(name = "amount", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount"; "sqlType" to "NUMERIC(12,2)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        // ── V1.9.122 (V97__travel_expense_pdv_form.sql) ──
        attribute(name = "destination", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "destination"; "sqlType" to "VARCHAR(200)" }
        }
        // DAY_ALLOWANCE: the travel day. ACCOMMODATION: the first night.
        attribute(name = "dayDate", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "day_date" }
        }
        // See 57-carpool.kuml.kts: ErmDataType.Time always emits java.time.LocalTime.
        attribute(name = "timeFrom", type = "LocalTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "time_from" }
        }
        attribute(name = "timeTo", type = "LocalTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "time_to" }
        }
        attribute(name = "breakfastProvided", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "breakfast_provided" }
        }
        attribute(name = "lunchProvided", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "lunch_provided" }
        }
        attribute(name = "dinnerProvided", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "dinner_provided" }
        }
        // MILEAGE only.
        attribute(name = "passengerCount", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "passenger_count" }
        }
        // ACCOMMODATION only; the number of nights lives in the existing `days` column.
        attribute(name = "breakfastIncluded", type = "Boolean") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "breakfast_included" }
        }
        // DAY_ALLOWANCE only -- server-computed.
        attribute(name = "computedMinutes", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "computed_minutes" }
        }
        attribute(name = "baseAmount", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "base_amount"; "sqlType" to "NUMERIC(12,2)" }
        }
        attribute(name = "deductionAmount", type = "BigDecimal") {
            defaultValue = "0"
            stereotype("Column") { "columnName" to "deduction_amount"; "sqlType" to "NUMERIC(12,2)" }
        }
    }

    val travelExpenseReceipt = classOf(name = "TravelExpenseReceipt") {
        stereotype("Entity") { "tableName" to "travel_expense_receipt"; "kotlinObjectName" to "TravelExpenseReceiptTable" }

        stereotype("Index") { "columns" to listOf("line_id"); "name" to "idx_terc_line" }
        stereotype("Index") { "columns" to listOf("storage_key"); "unique" to true; "name" to "uq_terc_storage_key" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "lineId", type = "UUID") {
            stereotype("Column") { "columnName" to "line_id"; "fkEntity" to "TravelExpenseLine" }
        }
        // Server-generated only, never derived from a client-supplied file name -- see
        // TravelExpenseReceiptRoutes KDoc.
        attribute(name = "storageKey", type = "String") {
            stereotype("Column") { "columnName" to "storage_key"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "originalFilename", type = "String") {
            stereotype("Column") { "columnName" to "original_filename"; "sqlType" to "VARCHAR(255)" }
        }
        // Derived server-side from magic bytes, never the client-declared Content-Type -- see
        // TravelExpenseReceiptRoutes KDoc.
        attribute(name = "mimeType", type = "String") {
            stereotype("Column") { "columnName" to "mime_type"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "sizeBytes", type = "Long") {
            stereotype("Column") { "columnName" to "size_bytes" }
        }
        attribute(name = "sha256", type = "String") {
            stereotype("Column") { "columnName" to "sha256"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "uploadedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "uploaded_by"; "fkEntity" to "Member" }
        }
        attribute(name = "uploadedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "uploaded_at" }
        }
    }

    // V1.9.198 (V113): report-level attachments (not tied to a line); files stored like receipts.
    val travelExpenseAttachment = classOf(name = "TravelExpenseAttachment") {
        stereotype("Entity") { "tableName" to "travel_expense_attachment"; "kotlinObjectName" to "TravelExpenseAttachmentTable" }

        stereotype("Index") { "columns" to listOf("report_id"); "name" to "idx_tea_report" }
        stereotype("Index") { "columns" to listOf("storage_key"); "unique" to true; "name" to "uq_tea_storage_key" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "reportId", type = "UUID") {
            stereotype("Column") { "columnName" to "report_id"; "fkEntity" to "TravelExpenseReport" }
        }
        // Server-generated only, never derived from a client-supplied file name -- see
        // TravelExpenseAttachmentRoutes KDoc.
        attribute(name = "storageKey", type = "String") {
            stereotype("Column") { "columnName" to "storage_key"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "originalFilename", type = "String") {
            stereotype("Column") { "columnName" to "original_filename"; "sqlType" to "VARCHAR(255)" }
        }
        // Derived server-side from magic bytes, never the client-declared Content-Type -- see
        // TravelExpenseAttachmentRoutes KDoc.
        attribute(name = "mimeType", type = "String") {
            stereotype("Column") { "columnName" to "mime_type"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "sizeBytes", type = "Long") {
            stereotype("Column") { "columnName" to "size_bytes" }
        }
        attribute(name = "sha256", type = "String") {
            stereotype("Column") { "columnName" to "sha256"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "uploadedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "uploaded_by"; "fkEntity" to "Member" }
        }
        attribute(name = "uploadedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "uploaded_at" }
        }
    }

    // V1.9.199 (V114): organisation-wide documents (Reisekostenordnung, Beschluesse, Formulare) for ALL
    // members; uploaded by BOARD/ADMIN. Files stored on disk like receipts (server-generated storage key).
    val travelExpenseDocument = classOf(name = "TravelExpenseDocument") {
        stereotype("Entity") { "tableName" to "travel_expense_document"; "kotlinObjectName" to "TravelExpenseDocumentTable" }

        stereotype("Index") { "columns" to listOf("storage_key"); "unique" to true; "name" to "uq_ted_storage_key" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(120)" }
        }
        // Server-generated only -- see TravelExpenseDocumentRoutes KDoc.
        attribute(name = "storageKey", type = "String") {
            stereotype("Column") { "columnName" to "storage_key"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "originalFilename", type = "String") {
            stereotype("Column") { "columnName" to "original_filename"; "sqlType" to "VARCHAR(255)" }
        }
        // Derived server-side by TravelExpenseDocumentSniffer, never the client-declared Content-Type.
        attribute(name = "mimeType", type = "String") {
            stereotype("Column") { "columnName" to "mime_type"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "sizeBytes", type = "Long") {
            stereotype("Column") { "columnName" to "size_bytes" }
        }
        attribute(name = "sha256", type = "String") {
            stereotype("Column") { "columnName" to "sha256"; "sqlType" to "VARCHAR(64)" }
        }
        // Nullable: ON DELETE SET NULL (DSGVO erasure of the uploader keeps the organisation document).
        attribute(name = "uploadedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "uploaded_by"; "fkEntity" to "Member" }
        }
        attribute(name = "uploadedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "uploaded_at" }
        }
    }

    // V1.9.123 -- one generated pain.001 file. Neither the XML nor any member IBAN/name is stored here.
    val travelExpenseSepaBatch = classOf(name = "TravelExpenseSepaBatch") {
        stereotype("Entity") { "tableName" to "travel_expense_sepa_batch"; "kotlinObjectName" to "TravelExpenseSepaBatchTable" }

        stereotype("Index") { "columns" to listOf("created_at"); "name" to "idx_tesb_created_at" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // FK fk_tesb_debtor_bank_account blocks deleting a bank account that was ever used for a file (BankAccountStore.delete -> 409).
        attribute(name = "debtorBankAccountId", type = "UUID") {
            stereotype("Column") { "columnName" to "debtor_bank_account_id"; "fkEntity" to "BankAccount" }
        }
        attribute(name = "debtorAccountLabel", type = "String") {
            stereotype("Column") { "columnName" to "debtor_account_label"; "sqlType" to "VARCHAR(120)" }
        }
        attribute(name = "debtorIbanLast4", type = "String") {
            stereotype("Column") { "columnName" to "debtor_iban_last4"; "sqlType" to "VARCHAR(4)" }
        }
        attribute(name = "requestedExecutionDate", type = "LocalDate") {
            stereotype("Column") { "columnName" to "requested_execution_date" }
        }
        attribute(name = "transactionCount", type = "Int") {
            stereotype("Column") { "columnName" to "transaction_count" }
        }
        attribute(name = "controlSumCents", type = "Long") {
            stereotype("Column") { "columnName" to "control_sum_cents" }
        }
        attribute(name = "painVersion", type = "String") {
            stereotype("Column") { "columnName" to "pain_version"; "sqlType" to "VARCHAR(20)" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "isReexport", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "is_reexport" }
        }
        // Mandatory iff is_reexport (chk_tesb_reexport_reason); must never contain an account number (application check).
        attribute(name = "reason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reason"; "sqlType" to "VARCHAR(500)" }
        }
    }

    val travelExpenseSepaBatchItem = classOf(name = "TravelExpenseSepaBatchItem") {
        stereotype("Entity") { "tableName" to "travel_expense_sepa_batch_item"; "kotlinObjectName" to "TravelExpenseSepaBatchItemTable" }

        stereotype("Index") { "columns" to listOf("end_to_end_id"); "unique" to true; "name" to "uq_tesbi_end_to_end_id" }
        stereotype("Index") { "columns" to listOf("batch_id", "report_id"); "unique" to true; "name" to "uq_tesbi_batch_report" }
        stereotype("Index") { "columns" to listOf("report_id"); "name" to "idx_tesbi_report" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "batchId", type = "UUID") {
            stereotype("Column") { "columnName" to "batch_id"; "fkEntity" to "TravelExpenseSepaBatch" }
        }
        attribute(name = "reportId", type = "UUID") {
            stereotype("Column") { "columnName" to "report_id"; "fkEntity" to "TravelExpenseReport" }
        }
        attribute(name = "amountCents", type = "Long") {
            stereotype("Column") { "columnName" to "amount_cents" }
        }
        // EndToEndId = "TE" + 28 hex of the report id, plus "-R<n>" for a re-export; globally unique.
        attribute(name = "endToEndId", type = "String") {
            stereotype("Column") { "columnName" to "end_to_end_id"; "sqlType" to "VARCHAR(35)" }
        }
    }
}
