// OrganizationSettings domain (V0.4.1, Serienbrief/PDF engine) -- the issuing association's own
// letterhead (name/address), bank details (for a Beitragsrechnung's payment instructions) and
// Gemeinnuetzigkeit tax-exemption reference (Freistellungsbescheid: issuing Finanzamt + date, for
// a Spendenbescheinigung) needed to mail-merge the three V0.4.1 letter templates. None of this
// data existed anywhere in the domain before this wave.
//
// Deliberately a genuinely new, independent, single-row entity -- NOT a general-purpose CMS/
// settings system. "Exactly one row" is enforced by convention only (same limitation as every
// other cross-row invariant in this codebase, e.g. the accounting balance check), not a DB
// constraint: the row is seeded once, unconditionally, directly in V1__baseline.sql with a fixed
// sentinel id (00000000-0000-0000-0000-0000000000f2) -- NOT via DevSeedData, which is opt-in/
// demo-gated (LAPIS_SEED_DEMO_DATA=true) and must never gate a real capability like letterhead
// data existing at all. `name` is the only NOT NULL field besides `id`, seeded with a placeholder
// so a fresh deployment never renders a blank letterhead; every other field is nullable until an
// ADMIN configures it via updateOrganizationSettings.
//
// No associations at all -- this entity has no FK to member or to any other table, so it needs
// neither a cross-domain Member stub nor a PersonalDataRegistry.noPersonalDataAllowlist entry
// (PersonalDataCoverageTest's information_schema walk only inspects FKs that reference
// member(id); this table has none).
//
// V0.4.2 (Letterxpress postal-mail dispatch) adds one more field: `postalMailEnabled` -- an
// explicit opt-in gate for the new postal-dispatch path, NOT NULL, defaults to FALSE. See that
// attribute's own comment below and `network.lapis.cloud.server.rpc.PostalMailService` KDoc for
// the runtime gate it backs.
//
// V0.6.4 (Politiker-Profile und Politiker-Ranking) adds one more field: `politicianRankingEnabled`
// -- an explicit opt-in gate for the whole feature, NOT NULL, defaults to FALSE. Same "independent
// flag, not folded into isPoliticalParty" reasoning `postalMailEnabled` already established --
// see that attribute's own comment below and `20-politician.kuml.kts`'s own file header addendum.
//
// V0.6.2 (LTR-Auktion) adds two more fields: `auctionEnabled` (opt-in gate, NOT NULL, defaults to
// FALSE) and `auctionMaxValueLtr` (nullable ADMIN-configurable per-auction value cap, no default =
// no cap). UNLIKE `postalMailEnabled`/`politicianRankingEnabled` above, `auctionEnabled` is
// deliberately NOT part of `OrganizationSettingsService.updateOrganizationSettings`'s writable
// column set -- it can only be flipped on via the auditable disclaimer-acknowledgment RPC
// `AuctionService.enableAuction` (see `21-auction.kuml.kts`'s own file header and
// `network.lapis.cloud.server.rpc.AuctionComplianceDisclaimer` KDoc) or off via
// `AuctionService.disableAuction`. `auctionMaxValueLtr` is set via
// `AuctionService.setAuctionMaxValueLtr`, also bypassing the generic update path -- both fields
// are therefore modelled here (this is still the single row that owns them) but read-only from
// `OrganizationSettingsService`'s own perspective; see that class's KDoc.
//
// **Welle V1.2.1 "Zahlungs-Fundament"** (vault "Lapis Cloud V1.2 -- Zahlungsverkehr" plan §§
// 2.4/3.5/6.1) adds two independent groups of fields:
//  - **Compliance-Gates** `sepaDebitEnabled`/`paymentGatewayEnabled` (+ `paymentGatewayProvider`,
//    which provider an enabled gateway gate names) -- SAME "opt-in gate, NOT part of the generic
//    `updateOrganizationSettings` write-set" treatment `auctionEnabled` already established above.
//    Settable ONLY via the new `ISepaService.enableSepaDebit`/`disableSepaDebit` and
//    `IPaymentGatewayService.enablePaymentGateway`/`disablePaymentGateway`
//    (`network.lapis.cloud.server.rpc.SepaComplianceDisclaimer`/`PaymentGatewayComplianceDisclaimer`
//    disclaimer-acknowledgment flow, mirroring `AuctionComplianceDisclaimer` exactly). Neither gate
//    has any real functionality behind it yet in V1.2.1 (SEPA mandates/PSP webhooks are V1.2.2/
//    V1.2.4) -- the gate exists now so those later sub-waves find it already built and reviewed.
//    `sepaCreditorId`/`sepaCreditorName`/`sepaPrenotificationDays` (plan § 2.4) were DELIBERATELY
//    NOT added in V1.2.1 -- they configure SEPA batch/pre-notification behaviour that did not exist
//    yet. Same reasoning for `dunningEnabled` (V1.2.3, still not added).
//  - **Welle V1.2.2 "SEPA-Lastschriftmandate"** (vault "sepa_v1.2.2_plan.md" Teil 1.2 D-4) adds
//    exactly those three fields now, alongside `sepa_mandate`/`sepa_debit_batch`/`sepa_debit_item`/
//    `sepa_return` in `33-payments.kuml.kts`. `sepaCreditorId` is nullable -- the Gläubiger-
//    Identifikationsnummer must be applied for at the Deutsche Bundesbank first (E-11); until it is
//    set, `ISepaService.generateBatchFile` refuses with an actionable message, everything else
//    (mandates, batch creation/notification) works. `sepaPrenotificationDays` is NOT NULL, default
//    14 (`SepaPrenotificationCalculator.FULL_NOTICE_DAYS`). All three are settable ONLY via
//    `ISepaService.updateSepaCreditorSettings`, never via the generic `updateOrganizationSettings`
//    -- same read-only-from-OrganizationSettingsService treatment as `sepaDebitEnabled` above.
//  - **Kontenzuordnung Zahlungsverkehr** `paymentBankAccountId`/`paymentFeeAccountId`/
//    `contributionIncomeAccountId` (plan § 3.5's "Konten-Zuordnung ist konfigurierbar, nicht
//    hartkodiert") -- which SKR42 `LedgerAccount`s `ContributionPostingBridge` books a manually
//    marked-paid contribution into. All three nullable; while any is unset the bridge is a no-op
//    (degrades, does not throw) and `markContributionPaid` behaves exactly as before this wave --
//    see `ContributionPostingBridge` KDoc. UNLIKE the two gates above, these ARE part of the
//    generic `updateOrganizationSettings` write-set (plain configuration, not a liability-relevant
//    feature toggle) -- see `OrganizationSettingsService` KDoc.
//
// **Welle V1.2.8 "PSP-Checkout (Stripe)"** (GitHub Issue #6) adds the fourth ledger-account mapping
// this file's header previously deferred under the placeholder name "V1.2.4" (renumbered away
// before that work started -- every such reference in this repo has been corrected to V1.2.8):
// `donationIncomeAccountId`, where `DonationPostingBridge` books a gateway donation's brutto
// amount as income. Same nullable/degrades-to-no-op/generic-write-set treatment as the three
// existing V1.2.1 mapping columns above.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "OrganizationSettings") {
    applyProfile(ermMappingProfile)

    // Accounting-owned stub — id-only, mirrors the cross-domain-stub pattern every other domain's
    // own Member stub already establishes (e.g. 01-contribution.kuml.kts). Only exists here so
    // UmlToErmTransformer can resolve the three V1.2.1 payment-account-mapping attributes' FK
    // target within this single-file evaluation.
    val ledgerAccount = classOf(name = "LedgerAccount") {
        stereotype("Entity") { "tableName" to "ledger_account"; "kotlinObjectName" to "LedgerAccountTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val organizationSettings = classOf(name = "OrganizationSettings") {
        stereotype("Entity") { "tableName" to "organization_settings"; "kotlinObjectName" to "OrganizationSettingsTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // NOT NULL -- see file header: seeded with a placeholder so a fresh deployment never
        // renders a blank letterhead.
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "street", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "street"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "postalCode", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "postal_code"; "sqlType" to "VARCHAR(20)" }
        }
        attribute(name = "city", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "city"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "country", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "country"; "sqlType" to "VARCHAR(100)" }
        }
        // Used to render a Beitragsrechnung's payment instructions.
        attribute(name = "bankIban", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "bank_iban"; "sqlType" to "VARCHAR(34)" }
        }
        attribute(name = "bankBic", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "bank_bic"; "sqlType" to "VARCHAR(11)" }
        }
        // §5 Abs.1 Nr.9 KStG / Gemeinnuetzigkeit: the Freistellungsbescheid-issuing Finanzamt --
        // required (together with taxExemptionDate) for a legally complete Spendenbescheinigung.
        attribute(name = "taxExemptionAuthority", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "tax_exemption_authority"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "taxExemptionDate", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "tax_exemption_date" }
        }
        // V0.4.1 (fix wave): Spendenbescheinigung legal basis differs materially between a
        // gemeinnuetziger Verein (§ 10b EStG, tax DEDUCTION) and a political party (§ 34g EStG,
        // tax CREDIT/"Steuerermaessigung", different official BMF Muster wording, different caps)
        // -- see SpendenbescheinigungPdfGenerator KDoc. NOT NULL, defaults to FALSE (association)
        // since that is the more common/default case and the pre-existing baseline seed row must
        // not suddenly render as a party receipt.
        attribute(name = "isPoliticalParty", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "is_political_party" }
        }
        // V0.4.2 Letterxpress postal-mail dispatch: explicit opt-in gate, NOT NULL, defaults to
        // FALSE. Postal dispatch sends a member's postal address (PII) to a third-party data
        // processor (Letterxpress) -- enabling this in real operation requires the association/
        // party to have a Data Processing Agreement (Auftragsverarbeitungsvertrag/AVV) with
        // Letterxpress in place FIRST (organizational/legal precondition, not something code can
        // enforce). ADMIN-only to set, same tier as every other OrganizationSettings field (via
        // updateOrganizationSettings). See network.lapis.cloud.server.rpc.PostalMailService
        // KDoc for the runtime gate this backs.
        attribute(name = "postalMailEnabled", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "postal_mail_enabled" }
        }
        // V0.6.4 Politiker-Profile und Politiker-Ranking: explicit opt-in gate, NOT NULL, defaults
        // to FALSE. Independent of isPoliticalParty -- see file header addendum above and
        // 20-politician.kuml.kts's own file header for the rationale. ADMIN-only to set, same tier
        // as every other OrganizationSettings field (via updateOrganizationSettings).
        attribute(name = "politicianRankingEnabled", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "politician_ranking_enabled" }
        }
        // V0.6.2 LTR-Auktion: explicit opt-in gate, NOT NULL, defaults to FALSE. Read-only from
        // OrganizationSettingsService's own update path -- see file header addendum above and
        // 21-auction.kuml.kts's own file header for the full rationale. Settable ONLY via
        // AuctionService.enableAuction (requires the disclaimer-acknowledgment flow)/disableAuction.
        attribute(name = "auctionEnabled", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "auction_enabled" }
        }
        // V0.6.2 LTR-Auktion: nullable, no default = no cap. ADMIN-configurable value ceiling (in
        // LTR, never Oracle-derived) applied only to a NEW listing's startingBidLtr/buyNowPriceLtr
        // -- see 21-auction.kuml.kts file header. Settable ONLY via
        // AuctionService.setAuctionMaxValueLtr, bypassing the generic update path -- see file
        // header addendum above.
        attribute(name = "auctionMaxValueLtr", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "auction_max_value_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        // V1.2.1 Zahlungs-Fundament: opt-in gate, NOT NULL, defaults to FALSE. Same read-only-from-
        // OrganizationSettingsService treatment as auctionEnabled above -- see file header addendum.
        // Settable ONLY via ISepaService.enableSepaDebit (disclaimer-acknowledgment)/disableSepaDebit.
        attribute(name = "sepaDebitEnabled", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "sepa_debit_enabled" }
        }
        // V1.2.1 Zahlungs-Fundament: opt-in gate, NOT NULL, defaults to FALSE. Same treatment as
        // sepaDebitEnabled above. Settable ONLY via IPaymentGatewayService.enablePaymentGateway
        // (disclaimer-acknowledgment)/disablePaymentGateway.
        attribute(name = "paymentGatewayEnabled", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "payment_gateway_enabled" }
        }
        // V1.2.1 Zahlungs-Fundament: nullable, which provider an ENABLED gateway gate names
        // (PAYPAL/STRIPE -- network.lapis.cloud.shared.domain.PaymentProvider). Set together with
        // paymentGatewayEnabled by IPaymentGatewayService.enablePaymentGateway, same read-only
        // treatment.
        attribute(name = "paymentGatewayProvider", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "payment_gateway_provider"; "sqlType" to "VARCHAR(8)" }
        }
        // V1.2.1 Zahlungs-Fundament (plan § 3.5 "Konten-Zuordnung ist konfigurierbar"). Nullable FK
        // -> ledger_account. Part of the GENERIC updateOrganizationSettings write-set (plain
        // configuration, not a liability-relevant feature toggle) -- see file header addendum.
        attribute(name = "paymentBankAccountId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "payment_bank_account_id"; "fkEntity" to "LedgerAccount" }
        }
        // V1.2.1 Zahlungs-Fundament. Nullable FK -> ledger_account -- where a PSP fee (if the
        // provider reports one) is booked as expense. Unused until fee_amount can ever be non-null
        // (V1.2.4), but configured alongside the other two account-mapping fields from the start.
        attribute(name = "paymentFeeAccountId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "payment_fee_account_id"; "fkEntity" to "LedgerAccount" }
        }
        // V1.2.1 Zahlungs-Fundament. Nullable FK -> ledger_account -- where a contribution's
        // brutto amount is booked as income (e.g. "40000 Echte Mitgliedsbeiträge"). See
        // ContributionPostingBridge KDoc for the full booking shape.
        attribute(name = "contributionIncomeAccountId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "contribution_income_account_id"; "fkEntity" to "LedgerAccount" }
        }
        // V1.2.8 PSP-Checkout (Stripe). Nullable FK -> ledger_account -- where a gateway donation's
        // brutto amount is booked as income. See DonationPostingBridge KDoc for the full booking
        // shape.
        attribute(name = "donationIncomeAccountId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "donation_income_account_id"; "fkEntity" to "LedgerAccount" }
        }
        // V1.2.2 SEPA-Lastschriftmandate (plan D-4/E-11). Nullable -- unset until an ADMIN applies
        // for and enters the Bundesbank Gläubiger-Identifikationsnummer. Settable ONLY via
        // ISepaService.updateSepaCreditorSettings -- see file header addendum.
        attribute(name = "sepaCreditorId", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "sepa_creditor_id"; "sqlType" to "VARCHAR(35)" }
        }
        // V1.2.2 SEPA-Lastschriftmandate. Nullable, the creditor's own name as it appears in the
        // pain.008 file's GrpHdr/InitgPty and PmtInf/Cdtr elements.
        attribute(name = "sepaCreditorName", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "sepa_creditor_name"; "sqlType" to "VARCHAR(70)" }
        }
        // V1.2.2 SEPA-Lastschriftmandate. NOT NULL, defaults to
        // SepaPrenotificationCalculator.FULL_NOTICE_DAYS (14) -- see that object's own KDoc for the
        // legal-status disclosure this default rests on.
        attribute(name = "sepaPrenotificationDays", type = "Int") {
            defaultValue = "14"
            stereotype("Column") { "columnName" to "sepa_prenotification_days" }
        }
        // V1.2.7 Automatisiertes Mahnwesen. Opt-in gate, NOT NULL, defaults to FALSE. Same read-
        // only-from-OrganizationSettingsService treatment as sepaDebitEnabled/auctionEnabled above
        // -- see 34-dunning.kuml.kts file header. Settable ONLY via
        // IDunningService.enableDunning (disclaimer-acknowledgment)/disableDunning.
        attribute(name = "dunningEnabled", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "dunning_enabled" }
        }
        // Welle V1.4.3.1 "Veranstaltungen". Nullable FK -> ledger_account -- where a confirmed
        // participation-fee payment's brutto amount is booked as income (see
        // network.lapis.cloud.server.rpc.EventFeePostingBridge). Part of the GENERIC
        // updateOrganizationSettings write-set, same treatment as paymentBankAccountId above.
        attribute(name = "eventIncomeAccountId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "event_income_account_id"; "fkEntity" to "LedgerAccount" }
        }
        // Welle V1.4.3.1. NOT NULL, defaults to ZWECKBETRIEB (a satzungsgemaesse Bildungs-/
        // Informationsveranstaltung, §65 AO) -- the organization-wide Gemeinnuetzigkeit sphere every
        // confirmed event-fee payment is booked under. Deliberately a plain String column here (type
        // "String", not a locally re-declared enum) rather than a per-event field -- see
        // 39-events.kuml.kts file header "OF-1"; the actual VALUES reuse
        // network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere's four literals (10-accounting
        // .kuml.kts), hand-wired onto OrganizationSettingsTable.eventIncomeSphere as an
        // enumerationByName<GemeinnuetzigkeitSphere> column -- same "type=String in the model,
        // enum-typed in the hand-edited generated Table" pattern this file's own paymentGatewayProvider
        // attribute already establishes.
        attribute(name = "eventIncomeSphere", type = "String") {
            defaultValue = "ZWECKBETRIEB"
            stereotype("Column") { "columnName" to "event_income_sphere"; "sqlType" to "VARCHAR(34)" }
        }
    }
}
