package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/** V1.9.200: [suggestStake] is pure and runs in whole cents over the decimal text -- never through `Double`. */
class StakeSuggestionTest {
    private fun weights(vararg values: String) = values.map { it.toDouble().toDecimal() }

    private fun suggest(
        top: List<dev.kilua.rpc.types.Decimal>?,
        balance: String?,
    ) = suggestStake(top, balance?.toDouble()?.toDecimal())

    @Test
    fun emptyTimeline_suggestsTheMinimumStake() {
        assertEquals(StakeSuggestion.Offer("0.01"), suggest(emptyList(), "5.00"))
    }

    @Test
    fun beatsTheHighestWeightOfAllPosts_notTheFirstRow() {
        assertEquals(StakeSuggestion.Offer("12.01"), suggest(weights("3.50", "12.00", "7.25"), "100"))
    }

    @Test
    fun moreThanTwoDecimals_roundUpToTheNextCent_thenOneStepMore() {
        // 1.231 -> 1.24 (up) + 0.01 = 1.25, which beats 1.231
        assertEquals(StakeSuggestion.Offer("1.25"), suggest(weights("1.231"), "10"))
        assertEquals(StakeSuggestion.Offer("0.02"), suggest(weights("0.001"), "10"))
        assertEquals(StakeSuggestion.Offer("12.36"), suggest(weights("12.345"), "20"))
    }

    @Test
    fun balanceExactlyTheStake_isStillAnOffer() {
        assertEquals(StakeSuggestion.Offer("12.01"), suggest(weights("12.00"), "12.01"))
    }

    @Test
    fun balanceJustBelowTheStake_isOutOfReach_withTheBalanceFlooredToCents() {
        assertEquals(StakeSuggestion.OutOfReach("12.01", "12.00"), suggest(weights("12.00"), "12.009"))
        assertEquals(StakeSuggestion.OutOfReach("12.01", "11.99"), suggest(weights("12.00"), "11.99"))
    }

    @Test
    fun notLoaded_orZeroBalance_isHidden() {
        assertEquals(StakeSuggestion.Hidden, suggest(null, "5"))
        assertEquals(StakeSuggestion.Hidden, suggest(weights("1"), null))
        assertEquals(StakeSuggestion.Hidden, suggest(weights("1"), "0"))
        assertEquals(StakeSuggestion.Hidden, suggest(weights("1"), "0.004"))
    }

    @Test
    fun noDoubleNoise_inLargeFigures() {
        assertEquals(StakeSuggestion.Offer("1234567.90"), suggest(weights("1234567.89"), "99999999"))
    }

    @Test
    fun figureFollowsTheUiLanguage() {
        assertEquals("1,24", stakeFigure("1.24", "de"))
        assertEquals("1.24", stakeFigure("1.24", "en"))
    }

    @Test
    fun theFigureTheButtonWritesIsReadBackByTheParser() {
        listOf("de", "en").forEach { language ->
            val written = stakeFigure("1234.50", language)
            assertEquals("1234.5", (parseAmountInput(written, language = language) as AmountInput.Valid).value.let { displayDigits(it) })
        }
    }
}
