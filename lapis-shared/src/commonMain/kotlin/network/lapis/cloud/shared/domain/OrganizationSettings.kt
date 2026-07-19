package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * The issuing association's own letterhead (V0.4.1 Serienbrief/PDF engine) -- name/address for
 * every letter template, bank details ([bankIban]/[bankBic]) for a Beitragsrechnung's payment
 * instructions, and the Gemeinnuetzigkeit tax-exemption reference ([taxExemptionAuthority]/
 * [taxExemptionDate] -- the issuing Finanzamt and date of the Freistellungsbescheid) required for
 * a legally complete Spendenbescheinigung.
 *
 * Exactly one row exists in this codebase, enforced by convention only (see
 * `network.lapis.cloud.server.db.generated.OrganizationSettingsTable` KDoc and
 * `lapis-server/src/main/kuml/11-organization-settings.kuml.kts` file header) -- there is no
 * create/delete RPC, only [network.lapis.cloud.shared.rpc.IOrganizationSettingsService.getOrganizationSettings]/
 * [network.lapis.cloud.shared.rpc.IOrganizationSettingsService.updateOrganizationSettings], both
 * always targeting that single seeded row. Every field except [id]/[name] is nullable -- a fresh
 * deployment is seeded with a placeholder [name] only, an ADMIN must configure the rest before a
 * legally complete Spendenbescheinigung/Beitragsrechnung can be generated (see
 * `network.lapis.cloud.server.routes.registerMailmergeRoutes` KDoc for the completeness guards
 * this enforces at generation time).
 *
 * [isPoliticalParty] (V0.4.1 fix wave) selects the Spendenbescheinigung's legal basis --
 * `false` (default, gemeinnuetziger Verein, § 10b EStG deduction) or `true` (political party,
 * § 34g EStG tax credit) -- see `network.lapis.cloud.server.pdf.SpendenbescheinigungPdfGenerator`
 * KDoc for why this branch exists and what remains an unverified simplification.
 */
@Serializable
data class OrganizationSettingsDto(
    val id: String,
    val name: String,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String?,
    val bankIban: String?,
    val bankBic: String?,
    val taxExemptionAuthority: String?,
    val taxExemptionDate: LocalDate?,
    val isPoliticalParty: Boolean = false,
)

/** Replaces every field of the single [OrganizationSettingsDto] row wholesale (no partial update). */
@Serializable
data class OrganizationSettingsInput(
    val name: String,
    val street: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val country: String? = null,
    val bankIban: String? = null,
    val bankBic: String? = null,
    val taxExemptionAuthority: String? = null,
    val taxExemptionDate: LocalDate? = null,
    val isPoliticalParty: Boolean = false,
)
