package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.2.7 "Automatisiertes Mahnwesen" -- terminal outcome of one [DunningNoticeDto] row. See
 * `network.lapis.cloud.server.payment.dunning.DunningIssuance` KDoc for the state machine.
 * Additively extensible, literal order load-bearing (`DunningSchemaDriftTest` pins it against
 * `34-dunning.kuml.kts`'s `dunningNoticeStatus` enum) -- same "cheap to extend, expensive to
 * reorder" note every other domain enum in this codebase carries.
 */
@Serializable
enum class DunningNoticeStatus { ISSUED, SKIPPED, CANCELLED }

/** One configurable escalation step, e.g. "Zahlungserinnerung"/"1. Mahnung"/"2. Mahnung". */
@Serializable
data class DunningLevelDto(
    val id: String,
    val levelNumber: Int,
    val name: String,
    val graceDays: Int,
    val responseDays: Int,
    val feeAmount: Decimal?,
    val active: Boolean,
)

@Serializable
data class DunningLevelInput(
    val levelNumber: Int,
    val name: String,
    val graceDays: Int,
    val responseDays: Int,
    val feeAmount: Decimal? = null,
    val active: Boolean = true,
)

/** One issued/skipped/cancelled escalation step for a single contribution. */
@Serializable
data class DunningNoticeDto(
    val id: String,
    val contributionId: String,
    val cycleNumber: Int,
    val levelNumber: Int,
    val levelName: String,
    val feeAmount: Decimal?,
    val amountDue: Decimal,
    val status: DunningNoticeStatus,
    val issuedAt: LocalDateTime,
    val respondBy: LocalDate,
    val documentId: String?,
    /** Derived from `postal_delivery_log.status` -- `null` = never dispatched by post. */
    val postalDeliveryStatus: PostalDeliveryStatus?,
    val createdByMemberId: String?,
    val cancellationReason: String?,
)

/** One running dunning case = one Contribution + its dunning history. */
@Serializable
data class DunningCaseDto(
    val contributionId: String,
    val memberId: String,
    val memberDisplayName: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val amountDue: Decimal,
    val dueDate: LocalDate,
    val contributionStatus: ContributionStatus,
    val paymentMethod: ContributionPaymentMethod,
    val currentCycleNumber: Int,
    val highestLevelNumber: Int?,
    val lastNoticeIssuedAt: LocalDateTime?,
    val nextLevelNumber: Int?,
    val nextLevelDueOn: LocalDate?,
    val totalFeesCharged: Decimal,
)

@Serializable
data class DunningCaseDetailDto(
    val case: DunningCaseDto,
    val notices: List<DunningNoticeDto>,
)

@Serializable
data class DunningSettingsDto(
    val dunningEnabled: Boolean,
    /** Read-only reflection of `DunningConfig.pollerEnabled` (`LAPIS_DUNNING_POLLER_ENABLED`). */
    val pollerEnabled: Boolean,
    /** Read-only reflection of `DunningConfig.postalDispatchEnabled` (`LAPIS_DUNNING_POSTAL_DISPATCH_ENABLED`). */
    val postalDispatchEnabled: Boolean,
    val postalMailEnabled: Boolean,
    val activeLevelCount: Int,
    val lastDisclaimerVersion: String?,
    val lastAcknowledgedAt: LocalDateTime?,
)

@Serializable
data class DunningComplianceDisclaimerDto(
    val version: String,
    val text: String,
    val sha256: String,
)

@Serializable
data class DunningComplianceAcknowledgmentInput(
    val disclaimerVersion: String,
    val disclaimerSha256: String,
)
