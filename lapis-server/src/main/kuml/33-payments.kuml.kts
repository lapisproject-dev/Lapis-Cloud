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
//
// **Welle V1.4.1b "Öffentliche Website-Integration -- anonymer Spenden-Pfad"** makes
// `payment_checkout_session.member_id` NULLABLE and adds `external_donor_id` (real FK ->
// external_donor, owned by 10-accounting.kuml.kts) plus `embed_origin` (the canonical embedding
// origin, set only on the external/anonymous path). A checkout session now has EXACTLY ONE donor
// identity -- member XOR external_donor, enforced by a CHECK constraint in
// V16__embed_anonymous_donation.sql, mirrored in Kotlin by DonationPostingBridge's own
// `require((donorMemberId == null) != (externalDonorId == null))`. `embed_origin` is non-null only
// together with `external_donor_id`, and whenever it is set `donor_category` is always ANONYMOUS
// (the widget offers no other category -- see docs/api/embed-widgets.adoc "Spenden-Widget"). A new
// `ExternalDonor` cross-domain stub (id-only, same pattern as Member/Contribution/JournalEntry/
// Document below) exists purely so UmlToErmTransformer can resolve this FK within this file's own
// single-file evaluation -- external_donor itself is owned by 10-accounting.kuml.kts.
//
// **Welle V1.2.8 "PSP-Checkout (Stripe)"** (GitHub Issue #6) delivers the "later sub-wave" this file's
// original header pointed at (previously tracked under the placeholder name "V1.2.4", which was
// renumbered away before that work started -- every such reference in this repo has been corrected
// to V1.2.8): `payment_checkout_session` (the server-authoritative record of what a member was
// SUPPOSED to pay, created before any redirect to Stripe -- the anchor against amount/currency
// tampering at webhook time) and `psp_webhook_event` (a forensic log, direct analogue of
// `federation_inbox_delivery_log` -- one row per delivery attempt, verified or not). Two new columns
// on `payment_transaction` (`checkoutSessionId`, `donorCategory`) and the new
// `PaymentCheckoutSessionStatus` enum are added below. `AuditEntityType` gains `PAYMENT_TRANSACTION`,
// appended LAST (see `AuditLog.kt`'s own KDoc) -- `ContributionPostingBridge`'s own audit entry
// still reuses the existing `AuditEntityType.JOURNAL_ENTRY` literal; `PAYMENT_TRANSACTION` is written
// by `PspWebhookIngestion` alongside it, see that class's KDoc for the exact ordering.
//
// Cross-domain stubs: minimal id-only Member/Contribution/JournalEntry/Document (each owned
// elsewhere -- 00-foundation.kuml.kts/01-contribution.kuml.kts/10-accounting.kuml.kts/
// 02-document.kuml.kts respectively), same pattern every other domain's own stub already
// establishes -- purely so UmlToErmTransformer can resolve this file's associations within its own
// single-file evaluation.
//
// V1.2.1 itself added no `AuditEntityType.PAYMENT_TRANSACTION` literal (build-ahead-of-need would
// have added an unused enum value -- no writer existed yet). `AuditEntityType` gains
// `PAYMENT_TRANSACTION` in V1.2.8, once `PspWebhookIngestion` becomes its first writer -- see the
// V1.2.8 addendum above.
//
// **Welle V1.4.3.1 "Veranstaltungen: Kernschleife + Anmeldegebuehren-Zahlung"** adds a THIRD payer
// identity to `payment_checkout_session`: `eventRegistrationId` (real FK -> event_registration,
// owned by 39-events.kuml.kts). The two-way XOR CHECK (member_id/external_donor_id) widens to a
// three-way "exactly one of member_id/external_donor_id/event_registration_id" CHECK in
// V18__events.sql -- see that migration's own header. `paymentIntent` gains a third literal,
// EVENT_FEE, appended LAST (load-bearing order, PaymentsSchemaDriftTest pins it) -- fits the
// existing VARCHAR(12) width (CONTRIBUTION, 12 chars, remains the longest literal). A new
// `EventRegistration` cross-domain stub (id-only, same pattern as ExternalDonor above) exists purely
// so UmlToErmTransformer can resolve this file's own FK within this file's single-file evaluation --
// event_registration itself is owned by 39-events.kuml.kts.
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

    // Accounting-owned stub — id-only, mirrors the Member/Contribution/JournalEntry stubs above.
    // Only exists here so UmlToErmTransformer can resolve
    // paymentCheckoutSession.externalDonorId's FK target within this file's own single-file
    // evaluation (Welle V1.4.1b). external_donor itself is owned by 10-accounting.kuml.kts.
    val externalDonor = classOf(name = "ExternalDonor") {
        stereotype("Entity") { "tableName" to "external_donor"; "kotlinObjectName" to "ExternalDonorTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // 39-events.kuml.kts-owned stub -- id-only. Resolves
    // paymentCheckoutSession.eventRegistrationId's FK target within this file's own single-file
    // evaluation (Welle V1.4.3.1).
    val eventRegistration = classOf(name = "EventRegistration") {
        stereotype("Entity") { "tableName" to "event_registration"; "kotlinObjectName" to "EventRegistrationTable" }
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
        // Welle V1.4.3.1. Appended LAST -- see network.lapis.cloud.shared.domain.PaymentIntent KDoc,
        // this literal order is load-bearing (PaymentsSchemaDriftTest).
        literal(name = "EVENT_FEE")
    }

    // Welle V1.2.8. Literal order is load-bearing, same reason as above -- matches
    // network.lapis.cloud.shared.domain.PaymentCheckoutSessionStatus. Longest literal COMPLETED (9)
    // -> VARCHAR(9).
    val paymentCheckoutSessionStatus = enumOf(name = "PaymentCheckoutSessionStatus") {
        literal(name = "CREATED")
        literal(name = "COMPLETED")
        literal(name = "EXPIRED")
        literal(name = "FAILED")
    }

    // Welle V1.2.8. Local re-declaration of network.lapis.cloud.shared.domain.DonorCategory -- same
    // single-file-evaluation reason 10-accounting.kuml.kts's own `donorCategory` enum exists (each
    // .kuml.kts file is evaluated independently by UmlToErmTransformer). Literal order is
    // load-bearing, matching that file's own copy exactly.
    val donorCategory = enumOf(name = "DonorCategory") {
        literal(name = "GERMAN_NATURAL_PERSON")
        literal(name = "EU_NATURAL_PERSON")
        literal(name = "NON_EU_FOREIGN_NATURAL_PERSON")
        literal(name = "GERMAN_COMPANY_OR_ORGANIZATION")
        literal(name = "PUBLIC_LAW_CORPORATION")
        literal(name = "OVER_25_PERCENT_STATE_OWNED_COMPANY")
        literal(name = "OTHER_PARTY_OR_PARLIAMENTARY_GROUP_ENTITY")
        literal(name = "PROFESSIONAL_OR_TRADE_ASSOCIATION")
        literal(name = "ANONYMOUS")
    }

    // Welle V1.2.8 -- the server-authoritative record of what a member was SUPPOSED to pay, created
    // before any redirect to Stripe. The anchor against amount/currency tampering at webhook time
    // (see PspWebhookIngestion KDoc): the webhook's own numbers are only ever compared against this
    // row's amount/currency, never trusted as the posting basis.
    val paymentCheckoutSession = classOf(name = "PaymentCheckoutSession") {
        stereotype("Entity") { "tableName" to "payment_checkout_session"; "kotlinObjectName" to "PaymentCheckoutSessionTable" }
        stereotype("Index") {
            "columns" to listOf("provider", "provider_session_id")
            "unique" to true
            "name" to "uq_payment_checkout_session_provider_session"
        }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_payment_checkout_session_member" }
        stereotype("Index") { "columns" to listOf("contribution_id"); "name" to "idx_payment_checkout_session_contribution" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_payment_checkout_session_status" }
        // Welle V1.4.1b.
        stereotype("Index") {
            "columns" to listOf("external_donor_id")
            "name" to "idx_payment_checkout_session_external_donor"
        }
        // Welle V1.4.3.1.
        stereotype("Index") {
            "columns" to listOf("event_registration_id")
            "name" to "idx_payment_checkout_session_event_registration"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "provider", type = paymentProvider) {
            stereotype("Column") { "columnName" to "provider"; "enumType" to "network.lapis.cloud.shared.domain.PaymentProvider" }
        }
        attribute(name = "providerSessionId", type = "String") {
            stereotype("Column") { "columnName" to "provider_session_id"; "sqlType" to "VARCHAR(255)" }
        }
        attribute(name = "status", type = paymentCheckoutSessionStatus) {
            stereotype("Column") {
                "columnName" to "status"
                "enumType" to "network.lapis.cloud.shared.domain.PaymentCheckoutSessionStatus"
            }
        }
        attribute(name = "intent", type = paymentIntent) {
            stereotype("Column") { "columnName" to "intent"; "enumType" to "network.lapis.cloud.shared.domain.PaymentIntent" }
        }
        // Real FK -> contribution (id), nullable -- set only for intent = CONTRIBUTION.
        attribute(name = "contributionId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "contribution_id"; "fkEntity" to "Contribution" }
        }
        // Real FK -> member (id). Nullable since Welle V1.4.1b -- an anonymous embed-widget
        // donation has no member at all, only an external_donor (see externalDonorId below). Was
        // NOT NULL through V1.2.8-V1.4.1a with the justification that a webhook-time system actor
        // needed SOME member to populate journal_entry.created_by (NOT NULL, FK -> member); that
        // role is now filled by lastPaymentGatewayComplianceAcknowledgerMemberIdOrNull() in
        // PaymentGatewayService.kt for the member-less path (see that function's own KDoc).
        attribute(name = "memberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        // Real FK -> external_donor (id), nullable -- set only for an anonymous embed-widget
        // donation (Welle V1.4.1b). Mutually exclusive with memberId (CHECK constraint in
        // V16__embed_anonymous_donation.sql, mirrored by DonationPostingBridge's own
        // `require((donorMemberId == null) != (externalDonorId == null))`).
        attribute(name = "externalDonorId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "external_donor_id"; "fkEntity" to "ExternalDonor" }
        }
        // The canonical (EmbedOriginAllowlist-resolved) embedding origin this session was created
        // from -- non-null only on the external/anonymous path (Welle V1.4.1b). Never the raw
        // request-supplied Origin header value.
        attribute(name = "embedOrigin", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "embed_origin"; "sqlType" to "VARCHAR(255)" }
        }
        // Real FK -> event_registration (id), nullable -- set only for intent = EVENT_FEE (Welle
        // V1.4.3.1). Mutually exclusive with memberId/externalDonorId (three-way CHECK in
        // V18__events.sql, mirrored by PspCheckoutSessions.create's own
        // `require(listOfNotNull(memberId, externalDonorId, eventRegistrationId).size == 1)`).
        attribute(name = "eventRegistrationId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "event_registration_id"; "fkEntity" to "EventRegistration" }
        }
        attribute(name = "amount", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount"; "sqlType" to "DECIMAL(14,2)" }
        }
        attribute(name = "currency", type = "String") {
            stereotype("Column") { "columnName" to "currency"; "sqlType" to "VARCHAR(3)" }
        }
        // Nullable -- required (validated at RPC level) only for intent = DONATION when
        // organization_settings.is_political_party is true.
        attribute(name = "donorCategory", type = donorCategory) {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") {
                "columnName" to "donor_category"
                "enumType" to "network.lapis.cloud.shared.domain.DonorCategory"
            }
        }
        attribute(name = "purpose", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "purpose"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "expiresAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "expires_at" }
        }
        attribute(name = "completedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "completed_at" }
        }
        // MINOR fix (code review, Welle V1.2.8): the Idempotency-Key actually sent to Stripe's
        // POST /v1/checkout/sessions for THIS row -- persisted for forensics only (matches
        // StripeCheckoutResult.Success.idempotencyKey's own KDoc). NOT reused across calls:
        // StripeCheckoutClient.createCheckoutSession mints a fresh random key
        // (randomIdempotencyKey()) on EVERY call and never reads a prior stored value back, so a
        // retried createContributionCheckout/createDonationCheckout call (e.g. a client-side
        // double-submit) does NOT dedupe via this key -- the corrected description below of what
        // the code actually does. See createContributionCheckout's own "reuses an existing
        // non-expired CREATED session" KDoc for how double-submits are ACTUALLY handled instead.
        attribute(name = "providerIdempotencyKey", type = "String") {
            stereotype("Column") { "columnName" to "provider_idempotency_key"; "sqlType" to "VARCHAR(64)" }
        }
        // Implementation-time addition (not in the original wave plan's column list) -- the
        // Stripe-hosted checkout URL, needed so createContributionCheckout's documented session-
        // REUSE path can still hand the client a redirect target without a second outbound Stripe
        // call. Not a secret -- a Stripe-hosted, expiring, single-use-by-design URL.
        attribute(name = "redirectUrl", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "redirect_url"; "sqlType" to "VARCHAR(2048)" }
        }
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
        // Welle V1.2.8. Real FK -> payment_checkout_session (id), nullable -- deliberately
        // ONE-DIRECTIONAL (no mirrored payment_checkout_session.payment_transaction_id column), same
        // reasoning that keeps sepa_debit_batch's two document FKs one-directional (see
        // SepaRoutes.kt NIT-5).
        attribute(name = "checkoutSessionId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "checkout_session_id"; "fkEntity" to "PaymentCheckoutSession" }
        }
        // Welle V1.2.8. Nullable SNAPSHOT of the effective DonorCategory at receipt time, for a
        // DONATION-intent row -- mirrors journal_entry.donorCategory's own "snapshot, not a live
        // FK" treatment (see 10-accounting.kuml.kts file header).
        attribute(name = "donorCategory", type = donorCategory) {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") {
                "columnName" to "donor_category"
                "enumType" to "network.lapis.cloud.shared.domain.DonorCategory"
            }
        }
    }

    // Welle V1.2.8 -- forensic log, direct analogue of federation_inbox_delivery_log (see
    // 24-federation.kuml.kts): one row per DELIVERY ATTEMPT, verified or not, accepted or rejected.
    // Deliberately NO unique constraint -- repeated deliveries must each leave a trace. Idempotency
    // lives exclusively on paymentTransaction's own uq_payment_transaction_provider_event unique
    // index above (see PspWebhookIngestion KDoc). No FK into member -- same "no PersonalDataContributor
    // coverage needed" reasoning federation_inbox_delivery_log's own file header gives.
    // outcome/rejectReason/eventType/providerEventId are plain Strings, not enums -- same treatment
    // FederationInboxDeliveryLogTable's own rejectReason/activityType already establish for a
    // forensic-log column drawn from a small, code-side vocabulary (network.lapis.cloud.server
    // .payment.psp.PspWebhookOutcome is a server-internal-only Kotlin enum, never sent over RPC, so
    // it is not modelled as a shared enumOf here).
    val pspWebhookEvent = classOf(name = "PspWebhookEvent") {
        stereotype("Entity") { "tableName" to "psp_webhook_event"; "kotlinObjectName" to "PspWebhookEventTable" }
        stereotype("Index") { "columns" to listOf("received_at"); "name" to "idx_psp_webhook_event_received_at" }
        stereotype("Index") {
            "columns" to listOf("provider", "provider_event_id")
            "name" to "idx_psp_webhook_event_provider_event"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "provider", type = paymentProvider) {
            stereotype("Column") { "columnName" to "provider"; "enumType" to "network.lapis.cloud.shared.domain.PaymentProvider" }
        }
        attribute(name = "providerEventId", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "provider_event_id"; "sqlType" to "VARCHAR(255)" }
        }
        attribute(name = "eventType", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "event_type"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "receivedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "received_at" }
        }
        attribute(name = "signatureVerified", type = "Boolean") {
            stereotype("Column") { "columnName" to "signature_verified" }
        }
        attribute(name = "rejectReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reject_reason"; "sqlType" to "VARCHAR(40)" }
        }
        attribute(name = "outcome", type = "String") {
            stereotype("Column") { "columnName" to "outcome"; "sqlType" to "VARCHAR(20)" }
        }
        // Real FK -> payment_transaction (id), nullable -- set only when this delivery actually
        // resulted in (or matched) a PaymentTransaction row.
        attribute(name = "paymentTransactionId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "payment_transaction_id"; "fkEntity" to "PaymentTransaction" }
        }
        attribute(name = "bodySha256", type = "String") {
            stereotype("Column") { "columnName" to "body_sha256"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "bodyByteSize", type = "Int") {
            stereotype("Column") { "columnName" to "body_byte_size" }
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
