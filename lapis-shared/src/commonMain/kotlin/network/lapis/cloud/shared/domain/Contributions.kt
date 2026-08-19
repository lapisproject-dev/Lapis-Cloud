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

    /** May be dunned. Deliberately excludes [DEBIT_IN_FLIGHT] -- a running debit collection is not (yet) a default. */
    val DUNNABLE: Set<ContributionStatus> = setOf(ContributionStatus.OVERDUE, ContributionStatus.RETURNED, ContributionStatus.IN_DUNNING)
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
)

@Serializable
data class MemberContributionSummaryDto(
    val memberId: String,
    val totalDue: Decimal,
    val totalPaid: Decimal,
    val totalOpen: Decimal,
    val contributions: List<ContributionDto>,
)
