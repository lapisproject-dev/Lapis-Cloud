package network.lapis.cloud.shared.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * V1.9.256 "Rechenschaftsbericht (PartG), R2" -- the state of one report cell. Strict: [NotEntered] is NEVER 0.
 * [Partial] carries the sum of what IS entered plus the LEAF codes (catalogue order) that are missing.
 */
@Serializable
sealed interface PartgCell {
    @Serializable
    @SerialName("v")
    data class Value(
        val cents: Long,
    ) : PartgCell

    @Serializable
    @SerialName("p")
    data class Partial(
        val cents: Long,
        val missing: List<String>,
    ) : PartgCell

    @Serializable
    @SerialName("n")
    data object NotEntered : PartgCell
}

/** A share of a total in tenths of a percent, or "not computable". */
sealed interface PartgPercent {
    /** 123 = 12.3 percent. */
    data class Tenths(
        val tenths: Long,
    ) : PartgPercent

    data object NotComputable : PartgPercent
}

/** The scope of a report: the whole party (all units) or exactly one unit. */
sealed interface PartgReportScope {
    data object All : PartgReportScope

    data class Single(
        val unit: PartgUnit,
    ) : PartgReportScope

    fun key(): String =
        when (this) {
            is All -> ALL_KEY
            is Single -> unit.key()
        }

    companion object {
        const val ALL_KEY = "ALL"

        /** Strict: `ALL` or a valid [PartgUnit] key, anything else is `null`. */
        fun parse(raw: String): PartgReportScope? {
            if (raw == ALL_KEY) return All
            return PartgUnit.parse(raw)?.let { Single(it) }
        }
    }
}

/**
 * The computing core of the report, shared by client and server. [PartgPositions.sums] stays the ONE formula for the sums; this object only
 * adds the "entered or not" bookkeeping on top and decides which inputs a sum depends on ([inputsOf], pinned against [PartgPositions.sums]
 * by `PartgInputsOfParityTest`). All amounts are whole cents, so nothing is ever rounded except the percentages.
 */
object PartgReportComputation {
    /** Federal level plus 100 regional chapters with two units each. Keeps every sum and the percentage division inside the Long range. */
    const val MAX_UNITS = 201

    private const val MAX_PERCENT_QUOTIENT = 1_000_000L

    private val leafCodesInOrder: List<String> = PartgPositions.ALL.filter { it.kind == PartgKind.LEAF }.map { it.code }

    private fun leavesOf(vararg sections: PartgSection): List<String> =
        PartgPositions.ALL.filter { it.kind == PartgKind.LEAF && it.section in sections }.map { it.code }

    private val inputs: Map<String, List<String>> =
        run {
            val income = leavesOf(PartgSection.INCOME)
            val material = PartgPositions.ALL.filter { it.parent == PartgPositions.EXPENSE_MATERIAL }.map { it.code }
            val expenses = leavesOf(PartgSection.EXPENSE)
            val assets = leavesOf(PartgSection.ASSET)
            val liabilities = leavesOf(PartgSection.LIABILITY)
            mapOf(
                PartgPositions.INCOME_TOTAL to income,
                PartgPositions.EXPENSE_MATERIAL to material,
                PartgPositions.EXPENSE_TOTAL to expenses,
                PartgPositions.RESULT to income + expenses,
                PartgPositions.ASSET_TOTAL to assets,
                PartgPositions.LIABILITY_TOTAL to liabilities,
                PartgPositions.NET to assets + liabilities,
            )
        }

    /** The LEAF codes (catalogue order) the sum position [sumCode] depends on; empty for an unknown code. */
    fun inputsOf(sumCode: String): List<String> = inputs[sumCode].orEmpty()

    /** All 42 codes to their cell for the entered values of ONE unit. */
    fun cells(entered: Map<String, Long>): Map<String, PartgCell> = aggregate(listOf(entered))

    /**
     * Leaf-wise sum over several units (whole party or one level). A leaf is a `Value` when every unit entered it, `NotEntered` when none did,
     * else `Partial` (missing = the leaf code itself). SUM cells are formed from the summed entered leaves via [PartgPositions.sums]; their
     * missing codes are the inputs that are not entered in every unit.
     */
    fun aggregate(perUnit: List<Map<String, Long>>): Map<String, PartgCell> {
        require(perUnit.size <= MAX_UNITS) { "Too many units: ${perUnit.size}" }
        val sums = LinkedHashMap<String, Long>()
        val full = HashSet<String>()
        val any = HashSet<String>()
        for (code in leafCodesInOrder) {
            var total = 0L
            var count = 0
            for (unit in perUnit) {
                val v = unit[code] ?: continue
                total += v
                count += 1
            }
            sums[code] = total
            if (count > 0) any += code
            if (perUnit.isNotEmpty() && count == perUnit.size) full += code
        }
        val result = LinkedHashMap<String, PartgCell>()
        val computed = PartgPositions.sums(sums)
        for (position in PartgPositions.ALL) {
            val code = position.code
            if (position.kind == PartgKind.LEAF) {
                result[code] =
                    when (code) {
                        in full -> PartgCell.Value(sums.getValue(code))
                        in any -> PartgCell.Partial(cents = sums.getValue(code), missing = listOf(code))
                        else -> PartgCell.NotEntered
                    }
            } else {
                val needed = inputsOf(code)
                val missing = needed.filter { it !in full }
                result[code] =
                    when {
                        missing.isEmpty() -> PartgCell.Value(computed.getValue(code))
                        needed.none { it in any } -> PartgCell.NotEntered
                        else -> PartgCell.Partial(cents = computed.getValue(code), missing = missing)
                    }
            }
        }
        return result
    }

    /**
     * The share of [numerator] in [base] in tenths of a percent, rounded HALF_UP (away from zero on a tie). Only when both are a `Value` and
     * the base is not 0; a share that would not fit is `NotComputable`. Pure integer long division: no Double, no BigDecimal.
     */
    fun percent(
        numerator: PartgCell,
        base: PartgCell,
    ): PartgPercent {
        if (numerator !is PartgCell.Value || base !is PartgCell.Value || base.cents == 0L) return PartgPercent.NotComputable
        val tenths = tenthsHalfUp(num = numerator.cents, base = base.cents) ?: return PartgPercent.NotComputable
        return PartgPercent.Tenths(tenths)
    }

    /** `null` when the quotient is out of the safe range (more than 1,000,000 times the base). */
    internal fun tenthsHalfUp(
        num: Long,
        base: Long,
    ): Long? {
        require(num != Long.MIN_VALUE && base != Long.MIN_VALUE && base != 0L)
        val negative = (num < 0) != (base < 0)
        val n = if (num < 0) -num else num
        val b = if (base < 0) -base else base
        val q = n / b
        if (q > MAX_PERCENT_QUOTIENT) return null
        var r = n % b
        var tenths = q * 1000
        var place = 100L
        repeat(3) {
            r *= 10
            val digit = r / b
            r %= b
            tenths += digit * place
            place /= 10
        }
        if (r * 2 >= b) tenths += 1
        return if (negative) -tenths else tenths
    }
}
