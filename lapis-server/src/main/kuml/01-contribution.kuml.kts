// Contribution domain — membership_tier/contribution (V2__contributions.sql).
//
// The generated `db/generated/*.kt` files ARE the compiled/imported-by-N-files source since
// 4756e69 ("swap production persistence to kUML-generated Exposed tables") — this model is the
// versioned source of truth for schema *shape*, not a verification-only artifact pointing at a
// hand-written Table object (that earlier framing is stale, belonged to the pre-4756e69 era, and
// must not be copied into new domain files — see 10-accounting.kuml.kts/32-social-network.kuml.kts
// for the current framing this file now follows).
//
// MembershipTier is fully defined *here* (this is its owning domain, first introduced by
// V2__contributions.sql) — Foundation's 00-foundation.kuml.kts separately carries only a
// minimal id-only stub of it (forward reference for member.membership_tier_id). This is the
// first real instance of the cross-domain-stub pattern described in the retrofit plan: this
// file, symmetrically, carries a minimal id-only Member stub (owned by Foundation) purely so
// UmlToErmTransformer can resolve contribution.member_id's association target.
//
// Known, accepted gaps (see PaymentsSchemaDriftTest/ContributionSchemaDriftTest for the pinned
// assertions):
//  - membership_tier.active's `DEFAULT TRUE` and contribution.created_at's implicit
//    application-supplied default are not modelled via defaultValue here (SchemaDriftTest,
//    like foundation's, does not introspect column defaults — only name/nullable/FK shape) —
//    consistent with the established, minimal-scope drift-check pattern from foundation.
//
// **Welle V1.2.1 "Zahlungs-Fundament"** (see vault "Lapis Cloud V1.2 -- Zahlungsverkehr" plan §§
// 0.1/2.1/2.2/2.5) closes Befund B-1: a paid contribution never produced a journal entry or audit
// trail. This wave adds:
//  - Four new `ContributionStatus` literals (`DEBIT_SCHEDULED`/`DEBIT_SUBMITTED`/`RETURNED`/
//    `IN_DUNNING`) -- unused by any V1.2.1 code path (SEPA/Mahnwesen are later sub-waves V1.2.2/
//    V1.2.3), but the enum widening itself (VARCHAR(7)->VARCHAR(15) + CHECK) is a single atomic
//    schema change this wave makes once, rather than re-widening the CHECK constraint three more
//    times across the later sub-waves -- see plan § 2.1's "erwogene Alternative, verworfen".
//    `ContributionStatusSets` (network.lapis.cloud.shared.domain, mirrors `MemberStatusSets`) is
//    the ONE place a "which statuses may do X" question about these is answered.
//  - `contribution.dueDate`/`contribution.paymentMethod` (new `ContributionPaymentMethod` enum:
//    MANUAL/SEPA_DEBIT/GATEWAY) -- see plan § 2.2. `sepaMandateId` (plan § 2.2) is DELIBERATELY
//    NOT added here: it FKs to `sepa_mandate`, a table V1.2.2 introduces -- adding the column now
//    without its target table would leave a dangling reference. It arrives together with
//    `sepa_mandate` in V1.2.2's own migration/model edit.
//  - `membershipTier.paymentTermDays` (Int, NOT NULL, default 14) -- the "Zahlungsziel" in days
//    `ContributionService.generateContributionsForPeriod` now reads to compute a freshly generated
//    contribution's `dueDate` (`periodStart + paymentTermDays`). Existing tiers backfill to 14 in
//    `V7__payments.sql` (see that migration's comment for why 14, not 0).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Contribution") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern established by
    // Foundation's own MembershipTier stub. Only exists here so UmlToErmTransformer can resolve
    // contribution.member_id's association target within this single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val billingInterval = enumOf(name = "BillingInterval") {
        literal(name = "MONTHLY")
        literal(name = "QUARTERLY")
        literal(name = "YEARLY")
    }

    // Literal order is load-bearing (PaymentsSchemaDriftTest pins ErmDataType.Enum.values against
    // network.lapis.cloud.shared.domain.ContributionStatus in exactly this order) -- the four V1.2.1
    // additions are appended LAST, never reordered/inserted among the original four. See file header
    // "Welle V1.2.1" and network.lapis.cloud.shared.domain.ContributionStatusSets KDoc for what each
    // new literal means.
    val contributionStatus = enumOf(name = "ContributionStatus") {
        literal(name = "OPEN")
        literal(name = "PAID")
        literal(name = "WAIVED")
        literal(name = "OVERDUE")
        // V1.2.1 additions -- see file header. Unused by any V1.2.1 code path on purpose (SEPA/
        // Mahnwesen write these starting V1.2.2/V1.2.3); the widening happens once, here.
        literal(name = "DEBIT_SCHEDULED")
        literal(name = "DEBIT_SUBMITTED")
        literal(name = "RETURNED")
        literal(name = "IN_DUNNING")
    }

    // V1.2.1 (plan § 2.2). Longest literal SEPA_DEBIT (10) -> VARCHAR(12), matching
    // chk_contribution_payment_method in V7__payments.sql.
    val contributionPaymentMethod = enumOf(name = "ContributionPaymentMethod") {
        literal(name = "MANUAL")
        literal(name = "SEPA_DEBIT")
        literal(name = "GATEWAY")
    }

    val membershipTier = classOf(name = "MembershipTier") {
        stereotype("Entity") { "tableName" to "membership_tier"; "kotlinObjectName" to "MembershipTierTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "description", type = "String") {
            stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "contributionAmount", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "contribution_amount"; "sqlType" to "DECIMAL(12,2)" }
        }
        attribute(name = "billingInterval", type = billingInterval) {
            stereotype("Column") { "columnName" to "billing_interval"; "enumType" to "network.lapis.cloud.shared.domain.BillingInterval" }
        }
        attribute(name = "active", type = "Boolean") {
            defaultValue = "TRUE"
            stereotype("Column") { "columnName" to "active" }
        }
        // V1.2.1 (plan § 2.6/Teil 10). "Zahlungsziel" in days -- see file header "Welle V1.2.1".
        // NOT NULL, default 14 (a common German invoice payment term, and the same magnitude as
        // sepa_prenotification_days' own default -- no legal claim either way, just a sane default
        // an ADMIN can change per tier).
        attribute(name = "paymentTermDays", type = "Int") {
            defaultValue = "14"
            stereotype("Column") { "columnName" to "payment_term_days" }
        }
    }

    val contribution = classOf(name = "Contribution") {
        stereotype("Entity") { "tableName" to "contribution"; "kotlinObjectName" to "ContributionTable" }
        // Idempotenz-Garantie fuer generateContributionsForPeriod (see V2__contributions.sql).
        stereotype("Index") {
            "columns" to listOf("member_id", "membership_tier_id", "period_start", "period_end")
            "unique" to true
            "name" to "uq_contribution_member_tier_period"
        }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_contribution_member" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_contribution_status" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "periodStart", type = "LocalDate") {
            stereotype("Column") { "columnName" to "period_start" }
        }
        attribute(name = "periodEnd", type = "LocalDate") {
            stereotype("Column") { "columnName" to "period_end" }
        }
        attribute(name = "amountDue", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount_due"; "sqlType" to "DECIMAL(12,2)" }
        }
        attribute(name = "status", type = contributionStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.ContributionStatus" }
        }
        attribute(name = "paidAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "paid_at" }
        }
        attribute(name = "paidAmount", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "paid_amount"; "sqlType" to "DECIMAL(12,2)" }
        }
        attribute(name = "note", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "note"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        // V1.2.1 (plan § 2.2). NOT NULL -- backfilled from period_start for every pre-existing row
        // (see V7__payments.sql), computed as periodStart + membershipTier.paymentTermDays for every
        // newly generated row (ContributionService.generateContributionsForPeriod).
        attribute(name = "dueDate", type = "LocalDate") {
            stereotype("Column") { "columnName" to "due_date" }
        }
        // V1.2.1 (plan § 2.2). NOT NULL, default MANUAL -- which payment path THIS contribution
        // line is on (per-line, not a member-wide setting, see plan Entscheidungspunkt E-5).
        attribute(name = "paymentMethod", type = contributionPaymentMethod) {
            defaultValue = "MANUAL"
            stereotype("Column") {
                "columnName" to "payment_method"
                "enumType" to "network.lapis.cloud.shared.domain.ContributionPaymentMethod"
            }
        }
    }

    association(source = member, target = contribution, id = "assoc-member-contribution") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    association(source = membershipTier, target = contribution, id = "assoc-membership-tier-contribution") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "membershipTierId" }
    }
}
