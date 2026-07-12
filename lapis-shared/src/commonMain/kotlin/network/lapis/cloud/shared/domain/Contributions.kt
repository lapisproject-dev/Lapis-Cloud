package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
enum class BillingInterval { MONTHLY, QUARTERLY, YEARLY }

@Serializable
enum class ContributionStatus { OPEN, PAID, WAIVED, OVERDUE }

@Serializable
data class MembershipTierDto(
    val id: String,
    val name: String,
    val description: String,
    val contributionAmount: Decimal,
    val billingInterval: BillingInterval,
    val active: Boolean,
)

@Serializable
data class MembershipTierInput(
    val name: String,
    val description: String,
    val contributionAmount: Decimal,
    val billingInterval: BillingInterval,
    val active: Boolean = true,
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
)

@Serializable
data class MemberContributionSummaryDto(
    val memberId: String,
    val totalDue: Decimal,
    val totalPaid: Decimal,
    val totalOpen: Decimal,
    val contributions: List<ContributionDto>,
)
