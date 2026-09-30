package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
enum class BillingInterval { MONTHLY, QUARTERLY, YEARLY }

/**
 * Additively extensible -- literal order is load-bearing (`PaymentsSchemaDriftTest` pins it against
 * `01-contribution.kuml.kts`'s `contributionStatus` enum). `DEBIT_SCHEDULED`/`DEBIT_SUBMITTED`/
 * `RETURNED`/`IN_DUNNING` were appended in Welle V1.2.1 "Zahlungs-Fundament" -- unused by any
 * V1.2.1 code path (SEPA/Mahnwesen write these starting V1.2.2/V1.2.3), see
 * `01-contribution.kuml.kts` file header "Welle V1.2.1" for why the widening happens once, now.
 * See [ContributionStatusSets] for the one place a "which statuses may do X" question about these
 * eight literals is answered.
 */
@Serializable
enum class ContributionStatus {
    OPEN,
    PAID,
    WAIVED,
    OVERDUE,
    DEBIT_SCHEDULED,
    DEBIT_SUBMITTED,
    RETURNED,
    IN_DUNNING,
}

/**
 * Which payment path THIS one contribution line is on -- a per-LINE attribute, not a member-wide
 * setting (Welle V1.2.1 plan Entscheidungspunkt E-5: a member may hold a SEPA mandate and still pay
 * one specific open line by another route). Literal order load-bearing, same reason as
 * [ContributionStatus].
 */
@Serializable
enum class ContributionPaymentMethod { MANUAL, SEPA_DEBIT, GATEWAY }

/**
 * The ONE place a "which [ContributionStatus] literals may do X" question is answered -- mirrors
 * [MemberStatusSets]'s own KDoc rationale exactly (avoids the kind of per-call-site duplicated
 * fallthrough logic that KDoc names as the anti-pattern). Introduced in Welle V1.2.1
 * "Zahlungs-Fundament" alongside the four new [ContributionStatus] literals it partitions.
 */
object ContributionStatusSets {
    /** Money is still outstanding on this line -- the basis for both a future dunning run AND a future debit run. */
    val OUTSTANDING: Set<ContributionStatus> =
        setOf(ContributionStatus.OPEN, ContributionStatus.OVERDUE, ContributionStatus.RETURNED, ContributionStatus.IN_DUNNING)

    /** Finally settled, never to be touched again. */
    val SETTLED: Set<ContributionStatus> = setOf(ContributionStatus.PAID, ContributionStatus.WAIVED)

    /** Bound up in an in-flight SEPA debit run -- must not enter a second, concurrent run. */
    val DEBIT_IN_FLIGHT: Set<ContributionStatus> = setOf(ContributionStatus.DEBIT_SCHEDULED, ContributionStatus.DEBIT_SUBMITTED)

    /**
     * May be dunned. Deliberately excludes [DEBIT_IN_FLIGHT] -- a running debit collection is not
     * (yet) a default. Real writers as of Welle V1.2.7 "Automatisiertes Mahnwesen": `OPEN ->
     * OVERDUE` is written by `network.lapis.cloud.server.payment.dunning.DunningPoller`'s own
     * Phase A (purely time-derived, no audit entry -- see that class' own KDoc);
     * `network.lapis.cloud.server.payment.dunning.DunningIssuance` moves an [OVERDUE]/[RETURNED]/
     * already-[IN_DUNNING] contribution INTO [IN_DUNNING] on its first successful notice.
     */
    val DUNNABLE: Set<ContributionStatus> = setOf(ContributionStatus.OVERDUE, ContributionStatus.RETURNED, ContributionStatus.IN_DUNNING)

    /**
     * Welle V1.4.10 "Beitragsvergünstigungen" -- welche Zeilen gestundet werden dürfen. Bewusst NUR
     * OPEN/OVERDUE: [SETTLED] ist erledigt, [DEBIT_IN_FLIGHT] liegt als SEPA-Datei bei der Bank
     * (ein verschobenes Fälligkeitsdatum desynchronisiert nur unsere Sicht von der der Bank), und
     * IN_DUNNING/RETURNED brauchen zusätzlich eine Rücksetzung der Mahnstufe -- siehe CHANGELOG
     * "bewusste Auslassung". Kein Designprinzip, eine aufgeschobene Welle.
     */
    val DEFERRABLE: Set<ContributionStatus> = setOf(ContributionStatus.OPEN, ContributionStatus.OVERDUE)
}

@Serializable
data class MembershipTierDto(
    val id: String,
    val name: String,
    val description: String,
    val contributionAmount: Decimal,
    val billingInterval: BillingInterval,
    val active: Boolean,
    /** V1.2.1. "Zahlungsziel" in days, read by `generateContributionsForPeriod` to compute a new contribution's `dueDate`. */
    val paymentTermDays: Int = 14,
)

@Serializable
data class MembershipTierInput(
    val name: String,
    val description: String,
    val contributionAmount: Decimal,
    val billingInterval: BillingInterval,
    val active: Boolean = true,
    /** V1.2.1. See [MembershipTierDto.paymentTermDays]. */
    val paymentTermDays: Int = 14,
)

/**
 * Welle V1.9.18 "Verwaltung der Mitgliedschaftsstufen" -- the read model of the tier administration
 * screen (`IContributionService.listMembershipTierOverview`, TREASURER/ADMIN only).
 *
 * [memberCounts] maps a tier id to the number of **ACTIVE** members currently assigned to it (a tier
 * without any active member has no entry at all -- read it with `getOrElse(id) { 0 }`).
 * [activeMembersWithoutTier] counts ACTIVE members with `membership_tier_id IS NULL`: they are never
 * invoiced by `generateContributionsForPeriod`, which is exactly what the screen's hint is for.
 */
@Serializable
data class MembershipTierOverviewDto(
    val tiers: List<MembershipTierDto>,
    val memberCounts: Map<String, Int>,
    val activeMembersWithoutTier: Int,
)

/**
 * Welle V1.9.18 -- the ONE place the membership-tier input limits live (server validation in
 * `ContributionService` and client pre-check in `MembershipTiersScreen` both read it, so the two
 * can never drift). Amounts are compared in `Decimal` terms by each side (`commonMain` has no
 * `BigDecimal`), hence the textual/`Double` constants.
 */
object MembershipTierRules {
    const val NAME_MAX_LENGTH = 100
    const val DESCRIPTION_MAX_LENGTH = 1000
    const val MAX_PAYMENT_TERM_DAYS = 365
    const val MAX_AMOUNT_SCALE = 2

    /** Upper bound of [MembershipTierInput.contributionAmount], as text (server parses it into a `BigDecimal`). */
    const val MAX_CONTRIBUTION_AMOUNT_TEXT = "100000.00"

    /** The same bound as a `Double`, for the client's pre-check. */
    const val MAX_CONTRIBUTION_AMOUNT = 100_000.0

    /** Trims and collapses inner whitespace runs to a single space. */
    fun normalizeName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    /**
     * H2 cannot index an expression (`lower(name)`) -- this column-level key is what
     * `uq_membership_tier_name_key` enforces uniqueness on. `Locale.ROOT`-equivalent: Kotlin's
     * `lowercase()` has no platform-default-locale dependency on JVM or JS.
     */
    fun nameKey(normalized: String): String = normalized.lowercase()

    /**
     * Length and content check for an already [normalizeName]d name. The [nameKey] length check
     * guards against a `lowercase()` expansion (U+0130 lowercases to two characters) overflowing
     * the `name_key` column -- same reasoning as `RegionalChapterRules.isValidName`.
     */
    fun isValidName(normalized: String): Boolean =
        normalized.isNotEmpty() &&
            normalized.length <= NAME_MAX_LENGTH &&
            normalized.none { it.isISOControl() } &&
            nameKey(normalized).length <= NAME_MAX_LENGTH
}

@Serializable
data class ContributionDto(
    val id: String,
    val memberId: String,
    val memberDisplayName: String,
    val membershipTierId: String,
    val membershipTierName: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val amountDue: Decimal,
    val status: ContributionStatus,
    val paidAt: LocalDateTime?,
    val paidAmount: Decimal?,
    val note: String?,
    val createdAt: LocalDateTime,
    /** V1.2.1. Fälligkeit -- see `01-contribution.kuml.kts` file header "Welle V1.2.1". */
    val dueDate: LocalDate,
    /** V1.2.1. See [ContributionPaymentMethod]. */
    val paymentMethod: ContributionPaymentMethod = ContributionPaymentMethod.MANUAL,
    /**
     * V1.4.5.1 "Kontoauszugs-Import". `"LC-XXXXXX"` (see [PaymentReferenceCode]) -- allocated lazily
     * on first invoice generation, `null` for a contribution whose invoice was never printed/whose
     * period predates this wave. Declared with a default so this additive field never breaks an
     * older client's deserialization of an already-shipped DTO.
     */
    val paymentReference: String? = null,
)

@Serializable
data class MemberContributionSummaryDto(
    val memberId: String,
    val totalDue: Decimal,
    val totalPaid: Decimal,
    /**
     * Bugfix, Welle V1.4.4.1 "Beitragshistorie" (Befund B-1): Summe über
     * [ContributionStatusSets.OUTSTANDING] (`OPEN`/`OVERDUE`/`RETURNED`/`IN_DUNNING`), NICHT nur
     * über [ContributionStatus.OPEN] -- vorher erschien ein bereits gemahntes Mitglied hier als
     * schuldenfrei.
     */
    val totalOpen: Decimal,
    val contributions: List<ContributionDto>,
)
