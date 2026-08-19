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
// **Scope of this file, deliberately narrow.** Only `payment_transaction` is modelled here --
// methodenneutral, PSP-logic-free (plan § 2.3's own description for the V1.2.1 shape of this table,
// as opposed to the fuller PSP-specific extension a later sub-wave adds). Explicitly OUT of scope
// for V1.2.1 and NOT modelled anywhere in this repo yet:
//  - `sepa_mandate`/`sepa_debit_batch`/`sepa_debit_item`/`sepa_return` (SEPA-Lastschriftmandate) --
//    V1.2.2.
//  - `dunning_level`/`dunning_notice` (automatisiertes Mahnwesen) -- V1.2.3.
//  - `payment_checkout_session` and any PSP-webhook/HTTP-client-specific extension of
//    `payment_transaction` (e.g. real webhook ingestion writing rows here) -- V1.2.4. No code path
//    in V1.2.1 inserts into this table yet; it exists now as the schema skeleton later sub-waves
//    write into, same "table before its first writer" precedent `ledger_account.reserve_type`
//    (V0.3.4) already set in this codebase.
//
// Cross-domain stubs: minimal id-only Member/Contribution/JournalEntry (each owned elsewhere --
// 00-foundation.kuml.kts/01-contribution.kuml.kts/10-accounting.kuml.kts respectively), same pattern
// every other domain's own stub already establishes -- purely so UmlToErmTransformer can resolve
// this file's associations within its own single-file evaluation.
//
// No `AuditEntityType.PAYMENT_TRANSACTION` literal exists yet, and `audit_log_entry.entity_type`'s
// CHECK constraint is NOT widened by this wave's migration -- deliberate deviation from the original
// plan draft, which proposed widening it now for all four new literals
// (SEPA_MANDATE/SEPA_DEBIT_BATCH/DUNNING_NOTICE/PAYMENT_TRANSACTION) at once. No V1.2.1 code path
// ever writes an audit-log row referencing this table (`ContributionPostingBridge`'s own audit entry
// reuses the EXISTING `AuditEntityType.JOURNAL_ENTRY` literal, see that class's KDoc) -- adding an
// unused enum literal now would be exactly the kind of build-ahead-of-need this sub-wave's scope
// boundary otherwise avoids. `AuditEntityType` gains `PAYMENT_TRANSACTION` in V1.2.4, when the
// webhook route actually starts writing rows here and auditing them.
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
}
