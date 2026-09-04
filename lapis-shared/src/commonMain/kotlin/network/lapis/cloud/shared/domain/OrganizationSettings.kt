package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
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
 *
 * [postalMailEnabled] (V0.4.2 Letterxpress postal-mail dispatch) is the explicit opt-in gate for
 * the whole postal-dispatch feature -- **defaults to `false`/off**. Postal dispatch sends a
 * member's postal address (PII) to Letterxpress, a third-party data processor: enabling this in
 * real operation requires the association/party to have a Data Processing Agreement
 * (Auftragsverarbeitungsvertrag/AVV) with Letterxpress **in place first** -- an organizational/
 * legal precondition this codebase cannot verify or enforce. ADMIN-only to set (same tier as every
 * other field, via [network.lapis.cloud.shared.rpc.IOrganizationSettingsService.updateOrganizationSettings]).
 * See `network.lapis.cloud.server.rpc.PostalMailService` KDoc for the runtime gate this backs.
 *
 * [politicianRankingEnabled] (V0.6.4 Politiker-Profile und Politiker-Ranking) is the explicit
 * opt-in gate for the whole feature -- **defaults to `false`/off**, same "independent flag, not
 * folded into [isPoliticalParty]" reasoning [postalMailEnabled] already established. ADMIN-only to
 * set, same tier as every other field. See `network.lapis.cloud.server.rpc.PoliticianService`
 * KDoc for the runtime gate this backs.
 *
 * [auctionEnabled]/[auctionMaxValueLtr] (V0.6.2 LTR-Auktion) are **READ-ONLY here** -- unlike
 * every field above, they are deliberately absent from [OrganizationSettingsInput] and can NEVER
 * be changed via [network.lapis.cloud.shared.rpc.IOrganizationSettingsService.updateOrganizationSettings].
 * [auctionEnabled] defaults to `false`/off and can only be flipped on via the auditable
 * disclaimer-acknowledgment flow
 * [network.lapis.cloud.shared.rpc.IAuctionService.enableAuction]/off via
 * [network.lapis.cloud.shared.rpc.IAuctionService.disableAuction] -- stronger than
 * [postalMailEnabled]/[politicianRankingEnabled]'s own opt-in gates, which the generic update path
 * CAN flip. [auctionMaxValueLtr] (nullable, default `null` = no cap) is set via
 * [network.lapis.cloud.shared.rpc.IAuctionService.setAuctionMaxValueLtr]. The acknowledgment
 * history itself (who/when/which disclaimer version) is NOT duplicated here -- see
 * [network.lapis.cloud.shared.domain.AuctionSettingsDto] for that. See
 * `network.lapis.cloud.server.rpc.AuctionService`/`21-auction.kuml.kts` file header for the full
 * rationale.
 *
 * [sepaDebitEnabled]/[paymentGatewayEnabled] (Welle V1.2.1 "Zahlungs-Fundament") are **READ-ONLY
 * here**, same treatment as [auctionEnabled] -- absent from [OrganizationSettingsInput], settable
 * ONLY via [network.lapis.cloud.shared.rpc.ISepaService.enableSepaDebit]/`disableSepaDebit` and
 * [network.lapis.cloud.shared.rpc.IPaymentGatewayService.enablePaymentGateway]/`disablePaymentGateway`
 * (both disclaimer-acknowledgment-gated). [paymentGatewayProvider] is set together with
 * [paymentGatewayEnabled] by `enablePaymentGateway`, same read-only tier.
 *
 * [paymentBankAccountId]/[paymentFeeAccountId]/[contributionIncomeAccountId] (Welle V1.2.1) are
 * ordinary, ADMIN-writable configuration -- part of [OrganizationSettingsInput] like every field
 * above [sepaDebitEnabled] -- which SKR42 `LedgerAccount`s a manually marked-paid contribution
 * books into (see `network.lapis.cloud.server.rpc.ContributionPostingBridge` KDoc). Any `null`
 * degrades the bridge to a no-op (no journal entry, contribution status still transitions) rather
 * than throwing -- so a fresh/unconfigured organization's `markContributionPaid` behaves exactly as
 * it did before this wave.
 *
 * [dunningEnabled] (Welle V1.2.7 "Automatisiertes Mahnwesen") is **READ-ONLY here**, same treatment
 * as [sepaDebitEnabled] -- absent from [OrganizationSettingsInput], settable ONLY via
 * [network.lapis.cloud.shared.rpc.IDunningService.enableDunning] (disclaimer-acknowledgment)/
 * `disableDunning`.
 *
 * [donationIncomeAccountId] (Welle V1.2.8 "PSP-Checkout (Stripe)") is a fourth ordinary,
 * ADMIN-writable configuration field -- same treatment as [paymentBankAccountId]/
 * [paymentFeeAccountId]/[contributionIncomeAccountId] above, part of [OrganizationSettingsInput].
 * Which SKR42 `LedgerAccount` `network.lapis.cloud.server.rpc.DonationPostingBridge` books a gateway
 * donation's brutto amount into. `null` degrades the bridge to a no-op, same "unconfigured mapping"
 * treatment as the other three.
 *
 * [eventIncomeAccountId]/[eventIncomeSphere] (Welle V1.4.3.1 "Veranstaltungen") are a fifth
 * ordinary, ADMIN-writable configuration pair, same treatment as [donationIncomeAccountId] above --
 * part of [OrganizationSettingsInput]. Which SKR42 `LedgerAccount`
 * `network.lapis.cloud.server.rpc.EventFeePostingBridge` books a confirmed participation-fee
 * payment's brutto amount into, and under which of the four §§51-68 AO Gemeinnuetzigkeit spheres
 * (default [GemeinnuetzigkeitSphere.ZWECKBETRIEB], mirroring `organization_settings
 * .event_income_sphere`'s own `DEFAULT` in `V18__events.sql`). `eventIncomeAccountId == null`
 * degrades the bridge to a no-op (a WARN-logged, unbooked payment_transaction), same "unconfigured
 * mapping" treatment as the other four -- **Review MAJOR fix**: these two columns existed in the
 * database since `V18__events.sql` but had no write path anywhere in this codebase before this fix
 * (`OrganizationSettingsInput` never carried them), so no ADMIN could ever configure them through
 * the application -- every confirmed event-fee payment was silently left unbooked.
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
    val postalMailEnabled: Boolean = false,
    val politicianRankingEnabled: Boolean = false,
    val auctionEnabled: Boolean = false,
    val auctionMaxValueLtr: Decimal? = null,
    val sepaDebitEnabled: Boolean = false,
    val paymentGatewayEnabled: Boolean = false,
    val paymentGatewayProvider: PaymentProvider? = null,
    val paymentBankAccountId: String? = null,
    val paymentFeeAccountId: String? = null,
    val contributionIncomeAccountId: String? = null,
    val dunningEnabled: Boolean = false,
    val donationIncomeAccountId: String? = null,
    val eventIncomeAccountId: String? = null,
    val eventIncomeSphere: GemeinnuetzigkeitSphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
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
    /** See [OrganizationSettingsDto.postalMailEnabled] KDoc -- AVV requirement applies here too. */
    val postalMailEnabled: Boolean = false,
    /** See [OrganizationSettingsDto.politicianRankingEnabled] KDoc. */
    val politicianRankingEnabled: Boolean = false,
    /** V1.2.1. See [OrganizationSettingsDto.paymentBankAccountId] KDoc. */
    val paymentBankAccountId: String? = null,
    /** V1.2.1. See [OrganizationSettingsDto.paymentFeeAccountId] KDoc. */
    val paymentFeeAccountId: String? = null,
    /** V1.2.1. See [OrganizationSettingsDto.contributionIncomeAccountId] KDoc. */
    val contributionIncomeAccountId: String? = null,
    /** V1.2.8. See [OrganizationSettingsDto.donationIncomeAccountId] KDoc. */
    val donationIncomeAccountId: String? = null,
    /** V1.4.3.1 (Review MAJOR fix). See [OrganizationSettingsDto.eventIncomeAccountId] KDoc. */
    val eventIncomeAccountId: String? = null,
    /** V1.4.3.1 (Review MAJOR fix). See [OrganizationSettingsDto.eventIncomeSphere] KDoc. */
    val eventIncomeSphere: GemeinnuetzigkeitSphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
)
