package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Client-UI wave for GitHub Issue #5 -- covers `validateDunningLevelInput` (`DunningSettingsScreen.kt`),
 * the client-side pre-validation that mirrors `DunningService.validateLevelInput`
 * (`DunningService.kt:861-888`) exactly. Same DOM-free unit-test posture as [ValidationTest].
 */
class DunningLevelValidationTest {
    private fun err(
        levelNumber: Int? = 1,
        name: String = "Zahlungserinnerung",
        graceDays: Int? = 14,
        responseDays: Int? = 14,
        feeAmount: Double? = null,
    ) = validateDunningLevelInput(levelNumber, name, graceDays, responseDays, feeAmount?.toDecimal())

    // ── levelNumber ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun levelNumber_zeroIsRejected() {
        assertNotNull(err(levelNumber = 0))
    }

    @Test
    fun levelNumber_oneAndOneThousandAreAccepted() {
        assertNull(err(levelNumber = 1))
        assertNull(err(levelNumber = 1000, name = "Zweite Mahnung", feeAmount = 5.0))
    }

    @Test
    fun levelNumber_aboveOneThousandIsRejected() {
        assertNotNull(err(levelNumber = 1001))
    }

    @Test
    fun levelNumber_nullIsRejected() {
        assertNotNull(err(levelNumber = null))
    }

    // ── graceDays / responseDays ─────────────────────────────────────────────────────────────────

    @Test
    fun graceDays_zeroIsRejected_oneAndThreeSixtyFiveAreAccepted_366IsRejected() {
        assertNotNull(err(graceDays = 0))
        assertNull(err(graceDays = 1))
        assertNull(err(graceDays = 365))
        assertNotNull(err(graceDays = 366))
    }

    @Test
    fun responseDays_zeroIsRejected_oneAndThreeSixtyFiveAreAccepted_366IsRejected() {
        assertNotNull(err(responseDays = 0))
        assertNull(err(responseDays = 1))
        assertNull(err(responseDays = 365))
        assertNotNull(err(responseDays = 366))
    }

    // ── feeAmount (on a non-level-1 stage, so the level-1 fee ban doesn't also fire) ────────────

    @Test
    fun feeAmount_nullIsAccepted() {
        assertNull(err(levelNumber = 2, feeAmount = null))
    }

    @Test
    fun feeAmount_zeroAndTwentyFiveAreAccepted() {
        assertNull(err(levelNumber = 2, feeAmount = 0.0))
        assertNull(err(levelNumber = 2, feeAmount = 25.0))
    }

    @Test
    fun feeAmount_aboveTwentyFiveIsRejected() {
        assertNotNull(err(levelNumber = 2, feeAmount = 25.01))
    }

    @Test
    fun feeAmount_negativeIsRejected() {
        assertNotNull(err(levelNumber = 2, feeAmount = -0.01))
    }

    // ── the substantively most important rule: no fee on level 1 (§ 286 BGB) ───────────────────

    @Test
    fun feeAmount_onLevelOneIsAlwaysRejected() {
        assertNotNull(err(levelNumber = 1, feeAmount = 0.01))
        assertNull(err(levelNumber = 1, feeAmount = null))
    }

    // ── name ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun name_blankOrWhitespaceOnlyIsRejected() {
        assertNotNull(err(name = ""))
        assertNotNull(err(name = "   "))
    }

    // The server truncates an overlong name (`name.trim().take(MAX_LEVEL_NAME_LENGTH)`,
    // DunningService.kt:884) rather than rejecting it -- `DunningSettingsScreen.kt`'s form mirrors
    // that by truncating BEFORE calling this function (see its submit handler), so the pure
    // validator itself never sees -- and must never reject on -- length alone.
    @Test
    fun name_oneHundredCharsIsAccepted_oneHundredAndOneIsAlsoAcceptedByThePureValidator() {
        assertNull(err(name = "a".repeat(100)))
        assertNull(err(name = "a".repeat(101)))
    }
}
