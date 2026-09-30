package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.MembershipTierDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.9.18 -- the pure, DOM-free helpers of `MembershipTiersScreen.kt`: field rules, prefill, ordering, labels, the
 * closed-tier dropdown options. (That every msgid is in all seven catalogs is proven by the server-side catalog tests, which
 * read the files -- `jsTest` has no file system.)
 */
class MembershipTierLabelsTest {
    private fun tier(
        id: String,
        name: String,
        active: Boolean = true,
        amount: Double = 10.0,
    ) = MembershipTierDto(id, name, "", amount.toDecimal(), BillingInterval.MONTHLY, active, 14)

    private fun invalid(check: FieldCheck): Boolean = check is FieldCheck.Invalid

    // ── amount ──

    @Test
    fun amount_acceptsZeroTheLimitAndTwoDecimals_withCommaOrPoint() {
        listOf("0", "0,00", "12,50", "12.50", "100000", "100000,00", "  7  ").forEach {
            assertEquals(FieldCheck.Ok, validateTierAmount(it), "'$it' must be valid")
        }
    }

    @Test
    fun amount_refusesNegativeTooLargeTooManyDecimalsAndGarbage() {
        listOf("-0,01", "-5", "100000,01", "100001", "1,234", "abc", "1.234,56", "1e3").forEach {
            assertTrue(invalid(validateTierAmount(it)), "'$it' must be refused")
        }
    }

    @Test
    fun amount_aMinusSignGetsItsOwnSentence_notTheShapeHint() {
        val result = validateTierAmount("-1") as FieldCheck.Invalid
        assertEquals("Der Betrag darf nicht negativ sein.", result.message)
        // and the shared open-items parser keeps refusing 0 (it must not have been changed for this screen)
        assertTrue(parseAmountInput("0") is AmountInput.Invalid)
        assertTrue(parseAmountInput("0", allowZero = true) is AmountInput.Valid)
    }

    @Test
    fun amount_emptyIsLeftToTheRequiredRule() {
        assertEquals(FieldCheck.Ok, validateTierAmount(""))
        assertEquals(FieldCheck.Ok, validateTierAmount(null))
    }

    // ── payment term ──

    @Test
    fun paymentTerm_isAWholeNumberFrom0To365() {
        listOf("0", "14", "365", " 30 ").forEach { assertEquals(FieldCheck.Ok, validatePaymentTermDays(it), "'$it'") }
        listOf("366", "-1", "1,5", "1.5", "+5", "abc", "", null, "99999999999").forEach {
            assertTrue(invalid(validatePaymentTermDays(it)), "'$it' must be refused")
        }
    }

    // ── name ──

    @Test
    fun name_isRefusedWhenBlankTooLongOrTakenByAnotherTier_butNotByItself() {
        val tiers = listOf(tier("a", "Standard"), tier("b", "Ermäßigt"))
        assertEquals(FieldCheck.Ok, tierNameCheck("Neu", tiers, ownId = null))
        assertTrue(invalid(tierNameCheck("   ", tiers, ownId = null)))
        assertTrue(invalid(tierNameCheck("x".repeat(101), tiers, ownId = null)))
        assertEquals(FieldCheck.Ok, tierNameCheck("x".repeat(100), tiers, ownId = null))
        assertTrue(invalid(tierNameCheck("  STANDARD  ", tiers, ownId = null)), "case and padding do not make a new name")
        assertTrue(invalid(tierNameCheck("standard", tiers, ownId = "b")), "another tier's name")
        assertEquals(FieldCheck.Ok, tierNameCheck("STANDARD", tiers, ownId = "a"), "its own name in another case is fine")
        assertTrue(invalid(tierNameCheck("ermäßigt", tiers, ownId = null)), "umlauts compare case-insensitively too")
    }

    @Test
    fun description_isLimitedTo1000Characters() {
        assertEquals(FieldCheck.Ok, tierDescriptionCheck("d".repeat(1000)))
        assertTrue(invalid(tierDescriptionCheck("d".repeat(1001))))
        assertEquals(FieldCheck.Ok, tierDescriptionCheck("  ${"d".repeat(1000)}  "), "the limit is checked after trimming, like the server")
    }

    // ── period ──

    @Test
    fun periodOrder_refusesAnEndBeforeTheStart_andLeavesIncompleteInputToRequired() {
        assertEquals(FieldCheck.Ok, periodOrderCheck("2027-01-01", "2027-01-31"))
        assertEquals(FieldCheck.Ok, periodOrderCheck("2027-01-31", "2027-01-31"), "a one-day period is fine")
        assertTrue(invalid(periodOrderCheck("2027-02-01", "2027-01-31")))
        assertEquals(FieldCheck.Ok, periodOrderCheck("", "2027-01-31"))
        assertEquals(FieldCheck.Ok, periodOrderCheck("2027-01-01", ""))
    }

    // ── prefill / input ──

    @Test
    fun formatAmountForInput_isDecimalCommaWithTwoDecimals() {
        assertEquals("12,50", formatAmountForInput(12.5.toDecimal()))
        assertEquals("10,00", formatAmountForInput(10.0.toDecimal()))
        assertEquals("0,00", formatAmountForInput(0.0.toDecimal()))
        assertEquals("19,99", formatAmountForInput(19.99.toDecimal()))
        // and the prefill is accepted by the amount rule again (the round trip)
        assertEquals(FieldCheck.Ok, validateTierAmount(formatAmountForInput(12.5.toDecimal())))
    }

    @Test
    fun tierInputFrom_normalizesAndParses_orReturnsNullForUnparsableText() {
        val input = tierInputFrom("  Neu   Stufe ", "  d  ", "12,50", BillingInterval.YEARLY, " 30 ", active = false)
        assertNotNull(input)
        assertEquals("Neu Stufe", input.name)
        assertEquals("d", input.description)
        assertEquals(BillingInterval.YEARLY, input.billingInterval)
        assertEquals(30, input.paymentTermDays)
        assertFalse(input.active)
        assertNull(tierInputFrom("n", "", "zwölf", BillingInterval.MONTHLY, "14", active = true))
        assertNull(tierInputFrom("n", "", "1", BillingInterval.MONTHLY, "vierzehn", active = true))
    }

    // ── display ──

    @Test
    fun sortTiersForDisplay_putsOpenTiersFirst_thenClosed_each_alphabetically() {
        val sorted =
            sortTiersForDisplay(
                listOf(
                    tier("1", "Zebra"),
                    tier("2", "Alt", active = false),
                    tier("3", "Äpfel"),
                    tier("4", "Berta"),
                    tier("5", "Aaa", active = false),
                ),
            )
        assertEquals(listOf("Äpfel", "Berta", "Zebra", "Aaa", "Alt"), sorted.map { it.name })
    }

    @Test
    fun billingIntervalLabel_hasAGermanLabelForEveryInterval() {
        assertEquals("Monatlich", billingIntervalLabel(BillingInterval.MONTHLY))
        assertEquals("Vierteljährlich", billingIntervalLabel(BillingInterval.QUARTERLY))
        assertEquals("Jährlich", billingIntervalLabel(BillingInterval.YEARLY))
    }

    @Test
    fun tierSelectOptions_offerOnlyOpenTiers_plusTheMembersOwnClosedOne_marked() {
        val tiers = listOf(tier("open", "Offen"), tier("closed", "Alt", active = false), tier("other-closed", "Weg", active = false))
        assertEquals(listOf("open" to "Offen"), tierSelectOptions(tiers, currentTierId = null))
        val withCurrent = tierSelectOptions(tiers, currentTierId = "closed")
        assertEquals(listOf("open", "closed"), withCurrent.map { it.first })
        assertEquals("Alt (geschlossen)", withCurrent.last().second)
    }

    @Test
    fun tierSelectOptions_sanitizeAForgedI18nMarkerInATierName() {
        val forged = KV_I18N_MARKER + "Ja"
        val options = tierSelectOptions(listOf(tier("x", forged)), currentTierId = null)
        assertFalse(options.single().second.contains(KV_I18N_MARKER), "the label goes through untrustedOptions")
    }
}
