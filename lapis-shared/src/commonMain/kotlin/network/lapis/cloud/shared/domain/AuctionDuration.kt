package network.lapis.cloud.shared.domain

/**
 * V1.9.222 -- the single source of the auction duration bounds, used by the server (`AuctionService.createListing`) and by the create
 * form.
 * The bound itself (90 days) is unchanged from before; only its home moved here.
 */
object AuctionDurationRules {
    const val MIN_HOURS: Int = 1
    const val MAX_HOURS: Int = 24 * 90
}

/** The unit a person types a duration in. */
enum class DurationUnit(
    val hours: Int,
) {
    HOURS(1),
    DAYS(24),
    WEEKS(168),
}

/** The largest amount that fits the server maximum in [unit]: 2160 hours, 90 days, 12 weeks. */
fun maxAmount(unit: DurationUnit): Int = AuctionDurationRules.MAX_HOURS / unit.hours

/**
 * [amount] of [unit] in hours, or `null` when it is not in 1..[maxAmount] (zero, negative, above the server maximum, or an overflow).
 * Never clamps: a value over the maximum is refused, not shortened.
 */
fun durationToHours(
    amount: Int,
    unit: DurationUnit,
): Int? {
    if (amount <= 0 || amount > maxAmount(unit)) return null
    return amount * unit.hours
}
