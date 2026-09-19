package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.21 -- pins [validateReceivableDunningLevelInput] (Spiegel `ReceivableDunningService
 * .validateLevelInput`) und seine bewussten Abweichungen von [validateDunningLevelInput].
 */
class ReceivableDunningLevelValidationTest {
    private fun err(
        levelNumber: Int? = 1,
        name: String = "Zahlungserinnerung",
        graceDays: Int? = 14,
        responseDays: Int? = 14,
        fee: Double? = null,
    ) = validateReceivableDunningLevelInput(levelNumber, name, graceDays, responseDays, fee?.toDecimal())

    @Test
    fun happyPath_isNull() {
        assertNull(err())
    }

    @Test
    fun levelNumber_missingOrBelowOne_isRejected() {
        assertNotNull(err(levelNumber = null))
        assertNotNull(err(levelNumber = 0))
        assertNotNull(err(levelNumber = -1))
        assertNull(err(levelNumber = 1))
    }

    @Test
    fun name_blankOrOverHundredChars_isRejected_notTruncated() {
        assertNotNull(err(name = ""))
        assertNotNull(err(name = "   "))
        assertNull(err(name = "a".repeat(100)))
        // Abgrenzung zum Beitrags-Mahnwesen: dort schneidet der Server ab, hier lehnt er ab.
        assertNotNull(err(name = "a".repeat(101)))
        assertNull(validateDunningLevelInput(1, "a".repeat(101), 14, 14, null))
    }

    @Test
    fun graceAndResponseDays_boundsAreOneToThreeSixtyFive() {
        assertNotNull(err(graceDays = 0))
        assertNull(err(graceDays = 1))
        assertNull(err(graceDays = 365))
        assertNotNull(err(graceDays = 366))
        assertNotNull(err(graceDays = null))
        assertNotNull(err(responseDays = 0))
        assertNull(err(responseDays = 1))
        assertNull(err(responseDays = 365))
        assertNotNull(err(responseDays = 366))
        assertNotNull(err(responseDays = null))
    }

    @Test
    fun fee_zeroToTwentyFiveIsAccepted_otherwiseRejected() {
        assertNull(err(levelNumber = 2, fee = 0.0))
        assertNull(err(levelNumber = 2, fee = 25.0))
        assertNotNull(err(levelNumber = 2, fee = 25.01))
        assertNotNull(err(levelNumber = 2, fee = -0.01))
        assertNull(err(levelNumber = 2, fee = null))
    }

    /**
     * Audit-Fund N2: das Gebührenfeld des Bildschirms geht jetzt durch [parseAmountInput]. Diese
     * Tests pinnen die Kombination, die der alte, lockerere Parser falsch machte -- sie gehören
     * hierher, weil erst Parser UND Validierung zusammen die Gebühr bestimmen.
     */
    @Test
    fun feeInput_goesThroughParseAmountInput_noSilentRoundingAndNoScientificNotation() {
        // Vorher: "12,999" -> 13,00 (still gerundet). Jetzt: abgelehnt, mit Begründung.
        assertTrue(parseAmountInput("12,999") is AmountInput.Invalid)
        // Vorher: "1e2" -> 100,00. Jetzt: abgelehnt (Formprüfung).
        assertTrue(parseAmountInput("1e2") is AmountInput.Invalid)
        // Leeres Feld heißt "keine Gebühr", nicht "0,00" und nicht "ungültig".
        assertEquals(AmountInput.Empty, parseAmountInput(""))
        assertEquals(AmountInput.Empty, parseAmountInput("   "))
        // Ein gültiger Betrag kommt unverändert an und besteht die Bereichsprüfung.
        val parsed = parseAmountInput("12,50")
        assertTrue(parsed is AmountInput.Valid)
        assertNull(err(levelNumber = 2, fee = (parsed as AmountInput.Valid).value.toDouble()))
        // Über der Server-Obergrenze bleibt die Ablehnung Sache der Validierung, nicht des Parsers.
        val tooHigh = parseAmountInput("25,01")
        assertTrue(tooHigh is AmountInput.Valid)
        assertNotNull(err(levelNumber = 2, fee = (tooHigh as AmountInput.Valid).value.toDouble()))
    }

    @Test
    fun feeOnLevelOne_isAllowed_unlikeTheContributionDunning() {
        // Keine § 286 BGB-Sperre für Forderungen an Dritte ...
        assertNull(err(levelNumber = 1, fee = 5.0))
        // ... während das Beitrags-Mahnwesen genau diese Kombination ablehnt.
        assertNotNull(validateDunningLevelInput(1, "Erinnerung", 14, 14, 5.0.toDecimal()))
    }
}
