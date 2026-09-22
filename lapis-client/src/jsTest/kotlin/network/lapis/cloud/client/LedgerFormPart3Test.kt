package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.4.30 (W4c): the pure rules of the posting lines -- no DOM. Expected values come from the server limits
 * (`JournalEntryBalance.MAX_AMOUNT_SCALE = 2`, `validateBalanced`) and from the old client behaviour, not from the implementation.
 */
class LedgerFormPart3Test {
    private fun posting(
        side: PostingSide,
        amount: Double,
    ) = PostingInput(ledgerAccountId = "a", side = side, amount = amount.toDecimal(), sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH)

    // ── FormRules.postingAmount ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun postingAmount_acceptsCommaAndDot_upToTwoDecimals() {
        assertEquals(FieldCheck.Ok, FormRules.postingAmount("1234,56"))
        assertEquals(FieldCheck.Ok, FormRules.postingAmount("1234.56"))
        assertEquals(FieldCheck.Ok, FormRules.postingAmount("  7  "))
        assertEquals(FieldCheck.Ok, FormRules.postingAmount("1000000000000"), "the journal bound itself is still fine")
        assertEquals(
            FieldCheck.Ok,
            FormRules.postingAmount("99999999999,99"),
            "the journal is not bound by the open-item sub-ledger limit of one billion",
        )
    }

    @Test
    fun postingAmount_rejectsWhatWouldOverflowTheDecimal15_2Column_insteadOfSendingItAsAJsonDouble() {
        // "99999999999999999999" went over the wire as 1.0E20 (scale -19), passed every server check and overflowed DECIMAL(15,2).
        for (tooBig in listOf("99999999999999999999", "1000000000000,01", "10000000000000")) {
            val check = FormRules.postingAmount(tooBig)
            assertTrue(check is FieldCheck.Invalid, "'$tooBig' must be rejected")
            assertTrue(check.message.contains("zu groß"), "the message says why: ${check.message}")
            assertFalse(check.message.contains("###KvI18nS###"), "resolved text, never a tr() marker: ${check.message}")
        }
    }

    @Test
    fun maxLength_mirrorsTheJournalColumnWidths_countingTheTrimmedText() {
        assertEquals(FieldCheck.Ok, FormRules.maxLength("d".repeat(MAX_JOURNAL_DESCRIPTION_LENGTH), MAX_JOURNAL_DESCRIPTION_LENGTH))
        assertEquals(FieldCheck.Ok, FormRules.maxLength("  " + "v".repeat(MAX_JOURNAL_VOUCHER_LENGTH) + "  ", MAX_JOURNAL_VOUCHER_LENGTH))
        // journal_entry.description varchar(500) / voucher_reference varchar(100): one more overflows the column server-side.
        val description = FormRules.maxLength("d".repeat(MAX_JOURNAL_DESCRIPTION_LENGTH + 1), MAX_JOURNAL_DESCRIPTION_LENGTH)
        assertTrue(description is FieldCheck.Invalid)
        assertTrue(description.message.contains("500"), description.message)
        val voucher = FormRules.maxLength("v".repeat(MAX_JOURNAL_VOUCHER_LENGTH + 1), MAX_JOURNAL_VOUCHER_LENGTH)
        assertTrue(voucher is FieldCheck.Invalid)
        assertTrue(voucher.message.contains("100"), voucher.message)
    }

    @Test
    fun returnFee_hasItsOwnBound_matchingTheDecimal12_2Column_notTheJournalBound() {
        assertEquals(FieldCheck.Ok, FormRules.returnFee(""))
        assertEquals(FieldCheck.Ok, FormRules.returnFee("3,00"))
        assertEquals(FieldCheck.Ok, FormRules.returnFee("1000000000"), "the fee bound itself is fine")
        // sepa_return.return_fee is DECIMAL(12,2): everything from 10^10 up overflows; the old journal bound (10^12) let this through.
        for (tooBig in listOf("1000000000,01", "10000000000", "99999999999", "1000000000000", "99999999999999999999")) {
            val check = FormRules.returnFee(tooBig)
            assertTrue(check is FieldCheck.Invalid, "'$tooBig' must be rejected")
            assertTrue(check.message.contains("zu groß"), "the message says why: ${check.message}")
            assertTrue(
                check.message.contains("1.000.000.000,00"),
                "names THIS field's bound: ${check.message}",
            )
            assertFalse(check.message.contains("1.000.000.000.000"), "not the journal bound: ${check.message}")
        }
        for (bad in listOf("0", "-1", "3,005", "abc")) {
            assertTrue(FormRules.returnFee(bad) is FieldCheck.Invalid, "'$bad' must be rejected")
        }
    }

    @Test
    fun postingAmount_rejectsWhatTheServerRejects_insteadOfRoundingIt() {
        for (bad in listOf("10,005", "1.234,56", "0", "0,00", "-5", "abc", "1e3")) {
            val check = FormRules.postingAmount(bad)
            assertTrue(check is FieldCheck.Invalid, "'$bad' must be rejected")
            assertFalse(check.message.contains("###KvI18nS###"), "the message is resolved text, never a tr() marker: ${check.message}")
        }
    }

    @Test
    fun postingAmount_leavesTheEmptyValueToTheRequiredCheck() {
        assertEquals(FieldCheck.Ok, FormRules.postingAmount(""))
        assertEquals(FieldCheck.Ok, FormRules.postingAmount("   "))
    }

    // ── journalPostingBalanceProblem ───────────────────────────────────────────────────────────────────────────

    @Test
    fun balanceProblem_needsTwoLines_aDebitAndACredit() {
        assertTrue(journalPostingBalanceProblem(emptyList()) != null)
        assertTrue(journalPostingBalanceProblem(listOf(posting(PostingSide.DEBIT, 10.0)))!!.contains("zwei Buchungszeilen"))
        val onlyDebits = journalPostingBalanceProblem(listOf(posting(PostingSide.DEBIT, 5.0), posting(PostingSide.DEBIT, 5.0)))!!
        assertTrue(onlyDebits.contains("Sollzeile") && onlyDebits.contains("Habenzeile"), onlyDebits)
        assertTrue(journalPostingBalanceProblem(listOf(posting(PostingSide.CREDIT, 5.0), posting(PostingSide.CREDIT, 5.0))) != null)
    }

    @Test
    fun balanceProblem_namesTheDifference_andCountsInWholeCents() {
        val unbalanced = journalPostingBalanceProblem(listOf(posting(PostingSide.DEBIT, 100.0), posting(PostingSide.CREDIT, 90.0)))!!
        assertTrue(unbalanced.contains("10,00$NBSP€"), unbalanced)
        // 33.33 + 33.33 + 33.34 = 100.00 -- a Double sum is 99.99999999999999 or 100.00000000000001, the cent sum is exact.
        val balanced =
            journalPostingBalanceProblem(
                listOf(
                    posting(PostingSide.DEBIT, 33.33),
                    posting(PostingSide.DEBIT, 33.33),
                    posting(PostingSide.DEBIT, 33.34),
                    posting(PostingSide.CREDIT, 100.0),
                ),
            )
        assertNull(balanced, "balanced to the cent")
        assertNull(
            journalPostingBalanceProblem(
                listOf(posting(PostingSide.DEBIT, 0.1), posting(PostingSide.DEBIT, 0.2), posting(PostingSide.CREDIT, 0.3)),
            ),
        )
    }

    // ── postingBalanceText ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun balanceText_sumsPerSide_andShowsTheAbsoluteDifference() {
        assertEquals(
            "Soll 1.200,00$NBSP€ · Haben 900,00$NBSP€ · Differenz 300,00$NBSP€",
            postingBalanceText(listOf("1200", "900"), listOf(PostingSide.DEBIT, PostingSide.CREDIT)),
        )
        assertEquals(
            "Soll 100,00$NBSP€ · Haben 250,50$NBSP€ · Differenz 150,50$NBSP€",
            postingBalanceText(listOf("100", "250,5"), listOf(PostingSide.DEBIT, PostingSide.CREDIT)),
            "the difference is never negative",
        )
    }

    @Test
    fun balanceText_showsDashesForAnyEmptyOrInvalidAmount_neverAnOldSum() {
        val dashes = "Soll — · Haben — · Differenz —"
        assertEquals(dashes, postingBalanceText(listOf("10", ""), listOf(PostingSide.DEBIT, PostingSide.CREDIT)))
        assertEquals(dashes, postingBalanceText(listOf("10", "abc"), listOf(PostingSide.DEBIT, PostingSide.CREDIT)))
        assertEquals(dashes, postingBalanceText(listOf("10", "10,005"), listOf(PostingSide.DEBIT, PostingSide.CREDIT)))
    }

    @Test
    fun balanceText_showsDashesForAnAmountAboveTheBound_insteadOfASaturatedMadeUpSum() {
        val dashes = "Soll — · Haben — · Differenz —"
        val sides = listOf(PostingSide.DEBIT, PostingSide.CREDIT)
        // 1.0E20 cents-saturated to Long.MAX_VALUE and read as a "balanced" 92233720368547760,00; 2000000000000 is just over the bound.
        assertEquals(dashes, postingBalanceText(listOf("99999999999999999999", "99999999999999999999"), sides))
        assertEquals(dashes, postingBalanceText(listOf("2000000000000", "2000000000000"), sides))
        assertEquals(dashes, postingBalanceText(listOf("10", "1000000000000,01"), sides))
        assertEquals(
            "Soll 1.000.000.000.000,00$NBSP€ · Haben 1.000.000.000.000,00$NBSP€ · Differenz 0,00$NBSP€",
            postingBalanceText(listOf("1000000000000", "1000000000000"), sides),
            "the bound itself is still summed",
        )
    }

    @Test
    fun balanceText_withoutAnyLine_showsDashes_notABalancedZero() {
        assertEquals("Soll — · Haben — · Differenz —", postingBalanceText(emptyList(), emptyList()))
    }

    @Test
    fun balanceText_addsInWholeCents_notInFloatingPoint() {
        assertEquals(
            "Soll 0,30$NBSP€ · Haben 0,00$NBSP€ · Differenz 0,30$NBSP€",
            postingBalanceText(listOf("0,1", "0,2"), listOf(PostingSide.DEBIT, PostingSide.DEBIT)),
        )
    }

    // ── buildJournalEntryInput ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun buildInput_trimsText_emptyVoucherIsNull_andAnInvalidDateOrBlankDescriptionIsNull() {
        val none = Triple<String?, String?, DonorCategory?>(null, null, null)
        val lines = listOf(posting(PostingSide.DEBIT, 1.0), posting(PostingSide.CREDIT, 1.0))
        val built = buildJournalEntryInput("  2026-03-14 ", "  Spende  ", "   ", lines, none)!!
        assertEquals(LocalDate(2026, 3, 14), built.entryDate)
        assertEquals("Spende", built.description)
        assertNull(built.voucherReference, "a blank voucher is null, never \"\"")
        assertEquals("B-1", buildJournalEntryInput("2026-03-14", "x", "  B-1 ", lines, none)!!.voucherReference)
        assertNull(buildJournalEntryInput("14.03.2026", "x", null, lines, none))
        assertNull(buildJournalEntryInput("2026-03-14", "   ", null, lines, none))
        val donor = buildJournalEntryInput("2026-03-14", "x", null, lines, Triple("m1", null, DonorCategory.GERMAN_NATURAL_PERSON))!!
        assertEquals("m1", donor.donorMemberId)
        assertNull(donor.externalDonorId)
        assertEquals(DonorCategory.GERMAN_NATURAL_PERSON, donor.donorCategory)
    }
}
