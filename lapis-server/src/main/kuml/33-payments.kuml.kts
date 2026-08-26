// Payments domain -- Welle V1.2.1 "Zahlungs-Fundament", the first sub-wave of V1.2 "Zahlungsverkehr"
// (vault "Lapis Cloud V1.2 -- Zahlungsverkehr" plan, Teil 2/Teil 10 "V1.2.1"). See that plan's Teil 0
// "Befund B-1" for why this wave exists: `ContributionService.markContributionPaid` wrote a status
// field only -- no journal entry, no audit trail, ever -- and this domain plus
// `network.lapis.cloud.server.rpc.ContributionPostingBridge` are the fix.
//
// The generated `db/generated/*.kt` files ARE the compiled/imported-by-N-files source since
// 4756e69 -- this model is the versioned source of truth for schema *shape*, following
// 10-accounting.kuml.kts/32-social-network.kuml.kts's current framing (NOT 01-contribution.kuml.kts's
// original, now-corrected "verification-only artifact" framing -- see that file's own header).
//
// **Scope of this file.** `payment_transaction` (V1.2.1) is methodenneutral, PSP-logic-free (plan §
// 2.3's own description for the V1.2.1 shape of this table, as opposed to the fuller PSP-specific
// extension a later sub-wave adds). Explicitly OUT of scope for this file and NOT modelled anywhere
// in this repo yet:
//  - `dunning_level`/`dunning_notice` (automatisiertes Mahnwesen) -- V1.2.7, modelled in
//    34-dunning.kuml.kts, which owns those tables.
//  - `payment_checkout_session` and any PSP-webhook/HTTP-client-specific extension of
//    `payment_transaction` (e.g. real webhook ingestion writing rows here) -- V1.2.4. No code path
//    yet inserts into `payment_transaction`; it exists now as the schema skeleton later sub-waves
//    write into, same "table before its first writer" precedent `ledger_account.reserve_type`
//    (V0.3.4) already set in this codebase.
//
// Cross-domain stubs: minimal id-only Member/Contribution/JournalEntry/Document (each owned
// elsewhere -- 00-foundation.kuml.kts/01-contribution.kuml.kts/10-accounting.kuml.kts/
// 02-document.kuml.kts respectively), same pattern every other domain's own stub already
// establishes -- purely so UmlToErmTransformer can resolve this file's associations within its own
// single-file evaluation.
//
// No `AuditEntityType.PAYMENT_TRANSACTION` literal exists yet, and `audit_log_entry.entity_type`'s
// CHECK constraint was NOT widened by V1.2.1 for it. No code path ever writes an audit-log row
// referencing `payment_transaction` (`ContributionPostingBridge`'s own audit entry reuses the
// EXISTING `AuditEntityType.JOURNAL_ENTRY` literal, see that class's KDoc) -- adding an unused enum
// literal now would be exactly the kind of build-ahead-of-need this sub-wave's scope boundary
// otherwise avoids. `AuditEntityType` gains `PAYMENT_TRANSACTION` in V1.2.4.
//
// **Welle V1.2.2 "SEPA-Lastschriftmandate"** (vault "sepa_v1.2.2_plan.md") adds the four tables this
// file's own header previously named as out of scope: `sepa_mandate`, `sepa_debit_batch`,
// `sepa_debit_item`, `sepa_return` -- plus five new enums (`SepaMandateStatus`/`SepaSequenceType`/
// `SepaDebitBatchStatus`/`SepaDebitItemStatus`/`SepaReturnReason`). `AuditEntityType` gains exactly
// two literals for this wave, `SEPA_MANDATE`/`SEPA_DEBIT_BATCH`, appended LAST (see
// `AuditLog.kt`'s own KDoc) -- `PAYMENT_TRANSACTION` remains deliberately unadded (`DUNNING_NOTICE`
// was added in Welle V1.2.7, see 34-dunning.kuml.kts),
// same "no build-ahead-of-need" rule. `contribution.sepaMandateId` (a new FK column on the
// EXISTING `contribution` table) is modelled in `01-contribution.kuml.kts` -- that file owns
// `contribution`, not this one; see its own file header addendum. The three new
// `organization_settings` SEPA-configuration columns (`sepaCreditorId`/`sepaCreditorName`/
// `sepaPrenotificationDays`) are modelled in `11-organization-settings.kuml.kts`, which owns that
// table -- see its own file header addendum. The migration mirroring all of this idempotently is
// `V8__sepa_mandates.sql` -- NOT a further in-place edit of `V7__payments.sql`, because V7 is
// already merged and deployed (checksum consumed), see that migration's own header comment.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Payments") {
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

    val journalEntry = classOf(name = "JournalEntry") {
        stereotype("Entity") { "tableName" to "journal_entry"; "kotlinObjectName" to "JournalEntryTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order is load-bearing (PaymentsSchemaDriftTest pins ErmDataType.Enum.values against
    // network.lapis.cloud.shared.domain.PaymentProvider in exactly this order). Longest literal
    // STRIPE/PAYPAL/MANUAL are all 6 chars -> VARCHAR(8), matching plan § 2.3.
    val paymentProvider = enumOf(name = "PaymentProvider") {
        literal(name = "PAYPAL")
        literal(name = "STRIPE")
        literal(name = "MANUAL")
    }

    // Literal order is load-bearing, same reason as above -- matches
    // network.lapis.cloud.shared.domain.PaymentTransactionStatus. Longest literal DISPUTED/CAPTURED/
    // REFUNDED (8 chars) -> VARCHAR(9), matching plan § 2.3.
    val paymentTransactionStatus = enumOf(name = "PaymentTransactionStatus") {
        literal(name = "PENDING")
        literal(name = "CAPTURED")
        literal(name = "FAILED")
        literal(name = "REFUNDED")
        literal(name = "DISPUTED")
    }

    // Literal order is load-bearing, same reason as above -- matches
    // network.lapis.cloud.shared.domain.PaymentIntent. Longest literal CONTRIBUTION (12) ->
    // VARCHAR(12), matching plan § 2.3.
    val paymentIntent = enumOf(name = "PaymentIntent") {
        literal(name = "CONTRIBUTION")
        literal(name = "DONATION")
    }

    val paymentTransaction = classOf(name = "PaymentTransaction") {
        stereotype("Entity") { "tableName" to "payment_transaction"; "kotlinObjectName" to "PaymentTransactionTable" }
        // The idempotency anchor against webhook retries (plan § 3.4) -- a DB constraint, not the
        // in-memory FederationReplayGuard (documented "per-JVM-instance state", too weak for money).
        // No V1.2.1 code path writes this table yet, but the constraint belongs to the table's own
        // shape from the start.
        stereotype("Index") {
            "columns" to listOf("provider", "provider_event_id")
            "unique" to true
            "name" to "uq_payment_transaction_provider_event"
        }
        stereotype("Index") { "columns" to listOf("contribution_id"); "name" to "idx_payment_transaction_contribution" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_payment_transaction_member" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_payment_transaction_status" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "provider", type = paymentProvider) {
            stereotype("Column") { "columnName" to "provider"; "enumType" to "network.lapis.cloud.shared.domain.PaymentProvider" }
        }
        attribute(name = "providerEventId", type = "String") {
            stereotype("Column") { "columnName" to "provider_event_id"; "sqlType" to "VARCHAR(255)" }
        }
        attribute(name = "providerPaymentId", type = "String") {
            stereotype("Column") { "columnName" to "provider_payment_id"; "sqlType" to "VARCHAR(255)" }
        }
        attribute(name = "status", type = paymentTransactionStatus) {
            stereotype("Column") {
                "columnName" to "status"
                "enumType" to "network.lapis.cloud.shared.domain.PaymentTransactionStatus"
            }
        }
        attribute(name = "amount", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount"; "sqlType" to "DECIMAL(14,2)" }
        }
        // ISO-4217. Only EUR is ever booked (plan § 9.5) -- enforced at the service layer that reads
        // this table, not by a CHECK constraint here (no such service exists yet in V1.2.1).
        attribute(name = "currency", type = "String") {
            stereotype("Column") { "columnName" to "currency"; "sqlType" to "VARCHAR(3)" }
        }
        attribute(name = "feeAmount", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "fee_amount"; "sqlType" to "DECIMAL(14,2)" }
        }
        attribute(name = "intent", type = paymentIntent) {
            stereotype("Column") { "columnName" to "intent"; "enumType" to "network.lapis.cloud.shared.domain.PaymentIntent" }
        }
        // Real FK -> contribution (id), nullable (a DONATION-intent row has none).
        attribute(name = "contributionId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "contribution_id"; "fkEntity" to "Contribution" }
        }
        // Real FK -> member (id), nullable (set only once/if the payer could be matched to an
        // account).
        attribute(name = "memberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        // Pseudonymous PSP-side payer identifier ONLY -- NEVER an email/name/account number (plan §
        // 9.7). No V1.2.1 code path populates or reads this column yet.
        attribute(name = "payerReference", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "payer_reference"; "sqlType" to "VARCHAR(255)" }
        }
        attribute(name = "receivedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "received_at" }
        }
        attribute(name = "reconciledAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reconciled_at" }
        }
        // Real FK -> member (id), nullable -- who reconciled this transaction to a contribution/
        // donation.
        attribute(name = "reconciledBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reconciled_by"; "fkEntity" to "Member" }
        }
        // Real FK -> journal_entry (id), nullable -- the booking this transaction resulted in, once
        // reconciled and posted.
        attribute(name = "journalEntryId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "journal_entry_id"; "fkEntity" to "JournalEntry" }
        }
        attribute(name = "reconciliationNote", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reconciliation_note"; "sqlType" to "VARCHAR(2000)" }
        }
        // SHA-256 hex digest of the raw webhook payload -- proof without retention (plan § 9.7). The
        // raw payload itself (which can carry payer email/name/address) is NEVER persisted anywhere.
        attribute(name = "rawPayloadDigest", type = "String") {
            stereotype("Column") { "columnName" to "raw_payload_digest"; "sqlType" to "VARCHAR(64)" }
        }
    }

    // ============================================================================================
    // Compliance-gate acknowledgment tables -- mirror auction_compliance_acknowledgment
    // (21-auction.kuml.kts) EXACTLY, one per gate. See AuctionComplianceDisclaimer/AuctionService
    // .enableAuction KDoc for the full mechanism these back: append-only proof of who acknowledged
    // which disclaimer version, never a bare boolean flip.
    // ============================================================================================

    val sepaComplianceAcknowledgment = classOf(name = "SepaComplianceAcknowledgment") {
        stereotype("Entity") {
            "tableName" to "sepa_compliance_acknowledgment"
            "kotlinObjectName" to "SepaComplianceAcknowledgmentTable"
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
            stereotype("Column") { "columnName" to "disclaimer_version"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "disclaimerSha256", type = "String") {
            stereotype("Column") { "columnName" to "disclaimer_sha256"; "sqlType" to "VARCHAR(64)" }
        }
    }

    val paymentGatewayComplianceAcknowledgment = classOf(name = "PaymentGatewayComplianceAcknowledgment") {
        stereotype("Entity") {
            "tableName" to "payment_gateway_compliance_acknowledgment"
            "kotlinObjectName" to "PaymentGatewayComplianceAcknowledgmentTable"
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
            stereotype("Column") { "columnName" to "disclaimer_version"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "disclaimerSha256", type = "String") {
            stereotype("Column") { "columnName" to "disclaimer_sha256"; "sqlType" to "VARCHAR(64)" }
        }
        // Which provider was named at enablement time (PAYPAL/STRIPE) -- unlike SEPA there is a
        // provider choice to record.
        attribute(name = "provider", type = paymentProvider) {
            stereotype("Column") { "columnName" to "provider"; "enumType" to "network.lapis.cloud.shared.domain.PaymentProvider" }
        }
    }

    // ================================================================================================
    // SEPA-Lastschriftmandate -- Welle V1.2.2 (vault "sepa_v1.2.2_plan.md" Teil 3/4).
    // ================================================================================================

    // Document-owned stub — id-only, mirrors the Member/Contribution/JournalEntry stubs above. Only
    // exists here so UmlToErmTransformer can resolve sepa_debit_batch.generatedDocumentId/
    // prenotificationDocumentId's association targets within this single-file evaluation.
    val document = classOf(name = "Document") {
        stereotype("Entity") { "tableName" to "document"; "kotlinObjectName" to "DocumentTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Mandate state -- see SepaMandateStatus KDoc "mandate state machine". Longest literal
    // REVOKED/EXPIRED (7) -> VARCHAR(7).
    val sepaMandateStatus = enumOf(name = "SepaMandateStatus") {
        literal(name = "ACTIVE")
        literal(name = "REVOKED")
        literal(name = "EXPIRED")
    }

    // All literals exactly 4 chars (ISO-20022 SeqTp) -> VARCHAR(4). OOFF/FNAL never written this
    // wave -- exist so SepaPain008Writer can serialize them without a later wave widening this enum.
    val sepaSequenceType = enumOf(name = "SepaSequenceType") {
        literal(name = "FRST")
        literal(name = "RCUR")
        literal(name = "OOFF")
        literal(name = "FNAL")
    }

    // Batch lifecycle DRAFT -> NOTIFIED -> GENERATED -> SUBMITTED -> SETTLED, CANCELLED reachable
    // before SUBMITTED. Longest literal GENERATED/SUBMITTED/CANCELLED (9) -> VARCHAR(9).
    val sepaDebitBatchStatus = enumOf(name = "SepaDebitBatchStatus") {
        literal(name = "DRAFT")
        literal(name = "NOTIFIED")
        literal(name = "GENERATED")
        literal(name = "SUBMITTED")
        literal(name = "SETTLED")
        literal(name = "CANCELLED")
    }

    // Item lifecycle -- SETTLEABLE is the SepaBatchPoller-set intermediate state after the 8-week
    // return window elapses. Longest literal SETTLEABLE (10) -> VARCHAR(10).
    val sepaDebitItemStatus = enumOf(name = "SepaDebitItemStatus") {
        literal(name = "PENDING")
        literal(name = "SETTLEABLE")
        literal(name = "SETTLED")
        literal(name = "RETURNED")
        literal(name = "CANCELLED")
    }

    // ISO-20022 R-transaction codes this application knows -- closed list, OTHER is the catch-all.
    // Longest literal OTHER (5) -> VARCHAR(5).
    val sepaReturnReason = enumOf(name = "SepaReturnReason") {
        literal(name = "AC01")
        literal(name = "AC04")
        literal(name = "AC06")
        literal(name = "AC13")
        literal(name = "AG01")
        literal(name = "AM04")
        literal(name = "MD01")
        literal(name = "MD06")
        literal(name = "MD07")
        literal(name = "MS02")
        literal(name = "MS03")
        literal(name = "SL01")
        literal(name = "OTHER")
    }

    val sepaMandate = classOf(name = "SepaMandate") {
        stereotype("Entity") { "tableName" to "sepa_mandate"; "kotlinObjectName" to "SepaMandateTable" }
        stereotype("Index") {
            "columns" to listOf("mandate_reference")
            "unique" to true
            "name" to "uq_sepa_mandate_reference"
        }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_sepa_mandate_member" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_sepa_mandate_status" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "mandateReference", type = "String") {
            stereotype("Column") { "columnName" to "mandate_reference"; "sqlType" to "VARCHAR(35)" }
        }
        attribute(name = "debtorName", type = "String") {
            stereotype("Column") { "columnName" to "debtor_name"; "sqlType" to "VARCHAR(70)" }
        }
        // Sealed via SecretBox (AES-256-GCM), AAD = sepa_mandate.id. Same column-pair convention as
        // conference_stream_destination.stream_key_ciphertext/stream_key_set_at.
        attribute(name = "debtorIbanCiphertext", type = "String") {
            stereotype("Column") { "columnName" to "debtor_iban_ciphertext"; "sqlType" to "VARCHAR(1024)" }
        }
        attribute(name = "debtorIbanSetAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "debtor_iban_set_at" }
        }
        // Display fragment only ("DE.....1234") -- the full IBAN is never returned again, not even
        // to the member themselves.
        attribute(name = "debtorIbanLast4", type = "String") {
            stereotype("Column") { "columnName" to "debtor_iban_last4"; "sqlType" to "VARCHAR(4)" }
        }
        attribute(name = "debtorBic", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "debtor_bic"; "sqlType" to "VARCHAR(11)" }
        }
        attribute(name = "signatureDate", type = "LocalDate") {
            stereotype("Column") { "columnName" to "signature_date" }
        }
        attribute(name = "sequenceType", type = sepaSequenceType) {
            stereotype("Column") { "columnName" to "sequence_type"; "enumType" to "network.lapis.cloud.shared.domain.SepaSequenceType" }
        }
        attribute(name = "status", type = sepaMandateStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.SepaMandateStatus" }
        }
        attribute(name = "grantedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "granted_at" }
        }
        attribute(name = "revokedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "revoked_at" }
        }
        attribute(name = "revokedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "revoked_by"; "fkEntity" to "Member" }
        }
        attribute(name = "revocationReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "revocation_reason"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "lastUsedAt", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_used_at" }
        }
        // Basis of the E-7 rule (SepaPrenotificationCalculator) -- the amount most recently
        // collected via THIS mandate. Its own column, not reconstructed via MAX(sepa_debit_item.
        // amount), which would be an aggregation over a growing table AND semantically wrong (a
        // cancelled run would count).
        attribute(name = "lastDebitedAmount", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_debited_amount"; "sqlType" to "DECIMAL(12,2)" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
    }

    val sepaDebitBatch = classOf(name = "SepaDebitBatch") {
        stereotype("Entity") { "tableName" to "sepa_debit_batch"; "kotlinObjectName" to "SepaDebitBatchTable" }
        stereotype("Index") {
            "columns" to listOf("message_id")
            "unique" to true
            "name" to "uq_sepa_debit_batch_message_id"
        }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_sepa_debit_batch_status" }
        stereotype("Index") { "columns" to listOf("created_at"); "name" to "idx_sepa_debit_batch_created_at" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "messageId", type = "String") {
            stereotype("Column") { "columnName" to "message_id"; "sqlType" to "VARCHAR(35)" }
        }
        attribute(name = "paymentInfoId", type = "String") {
            stereotype("Column") { "columnName" to "payment_info_id"; "sqlType" to "VARCHAR(35)" }
        }
        attribute(name = "requestedCollectionDate", type = "LocalDate") {
            stereotype("Column") { "columnName" to "requested_collection_date" }
        }
        attribute(name = "sequenceType", type = sepaSequenceType) {
            stereotype("Column") { "columnName" to "sequence_type"; "enumType" to "network.lapis.cloud.shared.domain.SepaSequenceType" }
        }
        attribute(name = "status", type = sepaDebitBatchStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.SepaDebitBatchStatus" }
        }
        attribute(name = "itemCount", type = "Int") {
            stereotype("Column") { "columnName" to "item_count" }
        }
        attribute(name = "totalAmount", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "total_amount"; "sqlType" to "DECIMAL(14,2)" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "notifiedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "notified_at" }
        }
        attribute(name = "requiredNoticeDays", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "required_notice_days" }
        }
        attribute(name = "generatedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "generated_at" }
        }
        attribute(name = "generatedDocumentId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "generated_document_id"; "fkEntity" to "Document" }
        }
        attribute(name = "prenotificationDocumentId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "prenotification_document_id"; "fkEntity" to "Document" }
        }
        attribute(name = "submittedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "submitted_at" }
        }
        attribute(name = "submittedNote", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "submitted_note"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "settledAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "settled_at" }
        }
        attribute(name = "cancelledAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cancelled_at" }
        }
        attribute(name = "cancellationReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cancellation_reason"; "sqlType" to "VARCHAR(500)" }
        }
        // Security Round 1 (2026-08-20, MAJOR-4): the organization's SEPA creditor identity
        // (sepa_creditor_id/sepa_creditor_name/bank_iban/bank_bic on organization_settings) is
        // snapshotted onto the batch at createDebitBatch time instead of being read LIVE at
        // generateBatchFile/listMyPrenotifications time -- otherwise an ADMIN changing the org's
        // creditor identity during the mandatory notice window would silently debit members under a
        // DIFFERENT identity than the one they were legally pre-notified about. All four are
        // nullable: a batch may legitimately be created before the org's SEPA settings are fully
        // configured (generateBatchFile still rejects with an actionable error if the frozen value
        // is null at generation time, exactly mirroring the pre-fix live-read behavior for a missing
        // value).
        attribute(name = "creditorId", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "creditor_id"; "sqlType" to "VARCHAR(35)" }
        }
        attribute(name = "creditorName", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "creditor_name"; "sqlType" to "VARCHAR(70)" }
        }
        attribute(name = "creditorIban", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "creditor_iban"; "sqlType" to "VARCHAR(34)" }
        }
        attribute(name = "creditorBic", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "creditor_bic"; "sqlType" to "VARCHAR(11)" }
        }
    }

    // uq_sepa_debit_item_batch_contribution prevents the SAME contribution TWICE in the SAME batch.
    // It does NOT prevent the same contribution in two SIMULTANEOUSLY open batches -- that is the
    // SELECT ... FOR UPDATE + ContributionStatusSets.DEBIT_IN_FLIGHT check in createDebitBatch's own
    // job. A DB constraint cannot express that.
    val sepaDebitItem = classOf(name = "SepaDebitItem") {
        stereotype("Entity") { "tableName" to "sepa_debit_item"; "kotlinObjectName" to "SepaDebitItemTable" }
        stereotype("Index") {
            "columns" to listOf("batch_id", "contribution_id")
            "unique" to true
            "name" to "uq_sepa_debit_item_batch_contribution"
        }
        stereotype("Index") {
            "columns" to listOf("batch_id", "end_to_end_id")
            "unique" to true
            "name" to "uq_sepa_debit_item_batch_e2e"
        }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_sepa_debit_item_status" }
        stereotype("Index") { "columns" to listOf("mandate_id"); "name" to "idx_sepa_debit_item_mandate" }
        stereotype("Index") { "columns" to listOf("contribution_id"); "name" to "idx_sepa_debit_item_contribution" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "batchId", type = "UUID") {
            stereotype("Column") { "columnName" to "batch_id"; "fkEntity" to "SepaDebitBatch" }
        }
        attribute(name = "contributionId", type = "UUID") {
            stereotype("Column") { "columnName" to "contribution_id"; "fkEntity" to "Contribution" }
        }
        attribute(name = "mandateId", type = "UUID") {
            stereotype("Column") { "columnName" to "mandate_id"; "fkEntity" to "SepaMandate" }
        }
        attribute(name = "endToEndId", type = "String") {
            stereotype("Column") { "columnName" to "end_to_end_id"; "sqlType" to "VARCHAR(35)" }
        }
        attribute(name = "amount", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount"; "sqlType" to "DECIMAL(12,2)" }
        }
        attribute(name = "remittanceInformation", type = "String") {
            stereotype("Column") { "columnName" to "remittance_information"; "sqlType" to "VARCHAR(140)" }
        }
        attribute(name = "status", type = sepaDebitItemStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.SepaDebitItemStatus" }
        }
        attribute(name = "settleableAt", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "settleable_at" }
        }
        attribute(name = "journalEntryId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "journal_entry_id"; "fkEntity" to "JournalEntry" }
        }
    }

    // uq_sepa_return_debit_item: exactly ONE return per item. The idempotency anchor against a
    // double recordReturn -- a DB constraint, not only a service-level check.
    val sepaReturn = classOf(name = "SepaReturn") {
        stereotype("Entity") { "tableName" to "sepa_return"; "kotlinObjectName" to "SepaReturnTable" }
        stereotype("Index") {
            "columns" to listOf("debit_item_id")
            "unique" to true
            "name" to "uq_sepa_return_debit_item"
        }
        stereotype("Index") { "columns" to listOf("returned_at"); "name" to "idx_sepa_return_returned_at" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "debitItemId", type = "UUID") {
            stereotype("Column") { "columnName" to "debit_item_id"; "fkEntity" to "SepaDebitItem" }
        }
        attribute(name = "returnedAt", type = "LocalDate") {
            stereotype("Column") { "columnName" to "returned_at" }
        }
        attribute(name = "reasonCode", type = sepaReturnReason) {
            stereotype("Column") { "columnName" to "reason_code"; "enumType" to "network.lapis.cloud.shared.domain.SepaReturnReason" }
        }
        attribute(name = "reasonText", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reason_text"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "returnFee", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "return_fee"; "sqlType" to "DECIMAL(12,2)" }
        }
        attribute(name = "recordedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "recorded_by"; "fkEntity" to "Member" }
        }
        attribute(name = "recordedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "recorded_at" }
        }
    }
}
