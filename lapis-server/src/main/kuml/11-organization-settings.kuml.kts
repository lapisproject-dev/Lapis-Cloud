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
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "OrganizationSettings") {
    applyProfile(ermMappingProfile)

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
    }
}
