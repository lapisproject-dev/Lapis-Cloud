package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import io.kvision.core.Container
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import io.kvision.i18n.tr

/**
 * V1.9.200: the stake needed to stand at the top of the timeline right now.
 *
 * Mirror of `SocialPostWeight.MIN_WEIGHT_LTR` (server): the smallest stake the server accepts, also the step by which a suggestion beats the
 * current top weight. A server test reads both files and requires the same value, so the mirror cannot drift silently.
 */
internal const val SOCIAL_POST_MIN_WEIGHT_LTR = "0.01"

/** What the compose form tells the author about the stake. */
internal sealed interface StakeSuggestion {
    /** [ltr] (dot-decimal, two places) is within the balance. */
    data class Offer(
        val ltr: String,
    ) : StakeSuggestion

    /** [needed] is more than the free [balance]; both dot-decimal, two places. */
    data class OutOfReach(
        val needed: String,
        val balance: String,
    ) : StakeSuggestion

    /** Timeline or balance not loaded yet, or the balance is 0 (the zero-balance notice speaks then). */
    data object Hidden : StakeSuggestion
}

private const val CENTS_PER_UNIT = 100L

/** Cents of a non-negative positional decimal string, [roundUp] = any further digit above zero adds one cent. `null` if it is no plain number. */
private fun centsOf(
    digits: String,
    roundUp: Boolean,
): Long? {
    if (digits.startsWith("-")) return 0L
    val whole = digits.substringBefore('.').ifEmpty { "0" }.toLongOrNull() ?: return null
    val fraction = digits.substringAfter('.', "")
    if (fraction.any { !it.isDigit() }) return null
    val twoPlaces = (fraction + "00").take(2).toLong()
    val rest = fraction.drop(2)
    val extra = if (roundUp && rest.any { it != '0' }) 1L else 0L
    return whole * CENTS_PER_UNIT + twoPlaces + extra
}

internal fun centsToPlain(cents: Long): String = "${cents / CENTS_PER_UNIT}.${(cents % CENTS_PER_UNIT).toString().padStart(2, '0')}"

/**
 * The stake that beats the highest of [topWeightsLtr] (the total current weight of EVERY loaded, visible post; an empty list = an empty
 * timeline) by the minimum step, rounded UP to whole cents. All arithmetic runs in whole cents over the decimal text -- never through `Double`.
 * [balance] `null` = not loaded: [StakeSuggestion.Hidden]; a balance of 0 or less: [StakeSuggestion.Hidden] as well.
 */
internal fun suggestStake(
    topWeightsLtr: List<Decimal>?,
    balance: Decimal?,
): StakeSuggestion {
    if (topWeightsLtr == null || balance == null) return StakeSuggestion.Hidden
    val balanceCents = centsOf(displayDigits(balance), roundUp = false) ?: return StakeSuggestion.Hidden
    if (balanceCents <= 0L) return StakeSuggestion.Hidden
    val topCents = topWeightsLtr.maxOfOrNull { centsOf(displayDigits(it), roundUp = true) ?: return StakeSuggestion.Hidden } ?: 0L
    val stepCents = centsOf(SOCIAL_POST_MIN_WEIGHT_LTR, roundUp = true) ?: return StakeSuggestion.Hidden
    val needed = topCents + stepCents
    return if (needed <= balanceCents) {
        StakeSuggestion.Offer(centsToPlain(needed))
    } else {
        StakeSuggestion.OutOfReach(centsToPlain(needed), centsToPlain(balanceCents))
    }
}

/** The amount WITH its unit in the language of the UI ("1,24 LTR"), through [formatLtrIn] -- the one place that writes the unit. */
internal fun stakeAmount(
    dotDecimal: String,
    language: String = I18n.language,
): String = formatLtrIn(language, dotDecimal.toDouble().toDecimal())

/** A plain LTR figure in the language of the UI, no unit ("1,24" / "1.24"). */
internal fun stakeFigure(
    dotDecimal: String,
    language: String = I18n.language,
): String = formatPlainAmountIn(language, dotDecimal.toDouble().toDecimal())

/**
 * The line under the stake field of the compose form. It is re-rendered whenever the timeline or the balance is (re)loaded; the field itself
 * is NEVER prefilled -- the button "Vorschlag übernehmen" writes the figure only on request.
 */
internal class StakeSuggestionHint(
    host: Container,
    private val onApply: (String) -> Unit,
) {
    private val box: Div =
        host.div {
            addCssClasses("text-muted small lapis-stake-suggestion")
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
        }
    private var weights: List<Decimal>? = null
    private var balance: Decimal? = null

    fun updateWeights(value: List<Decimal>?) {
        weights = value
        render()
    }

    fun updateBalance(value: Decimal?) {
        balance = value
        render()
    }

    internal fun render() {
        box.removeAll()
        when (val suggestion = suggestStake(weights, balance)) {
            StakeSuggestion.Hidden -> Unit
            is StakeSuggestion.Offer ->
                box.div(className = "d-flex flex-wrap align-items-center gap-2") {
                    span(gettext("Um aktuell ganz oben zu stehen: %1", stakeAmount(suggestion.ltr)))
                    button(tr("Vorschlag übernehmen"), style = ButtonStyle.LINK) {
                        addCssClasses("btn-sm p-0")
                        onClick { onApply(stakeFigure(suggestion.ltr)) }
                    }
                }
            is StakeSuggestion.OutOfReach ->
                box.span(
                    gettext("Um aktuell ganz oben zu stehen: %1", stakeAmount(suggestion.needed)) +
                        " " +
                        gettext("Ihr Guthaben reicht nicht, höchstens %1.", stakeAmount(suggestion.balance)),
                )
        }
    }
}
