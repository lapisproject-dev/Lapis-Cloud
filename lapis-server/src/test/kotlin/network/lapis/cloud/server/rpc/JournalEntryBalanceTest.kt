package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Pure tests of [JournalEntryBalance.validateBalanced] -- no DB access anywhere in this file, same
 * rationale as [ElectionTallyTest]/[VoteVickreyTest] give for their own pure-logic subjects.
 */
class JournalEntryBalanceTest :
    FunSpec({
        val accountA = Uuid.random().toString()
        val accountB = Uuid.random().toString()
        val accountC = Uuid.random().toString()

        fun posting(
            side: PostingSide,
            amount: String,
            accountId: String = accountA,
            sphere: GemeinnuetzigkeitSphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
        ) = PostingInput(ledgerAccountId = accountId, side = side, amount = BigDecimal(amount), sphere = sphere)

        test("a simple balanced two-line entry (100 debit / 100 credit) is balanced") {
            val result =
                JournalEntryBalance.validateBalanced(
                    listOf(
                        posting(PostingSide.DEBIT, "100.00", accountA),
                        posting(PostingSide.CREDIT, "100.00", accountB),
                    ),
                )
            result.balanced shouldBe true
            result.debitTotal.compareTo(BigDecimal("100.00")) shouldBe 0
            result.creditTotal.compareTo(BigDecimal("100.00")) shouldBe 0
        }

        test("an unbalanced two-line entry (100 debit / 90 credit) is rejected") {
            val result =
                JournalEntryBalance.validateBalanced(
                    listOf(
                        posting(PostingSide.DEBIT, "100.00", accountA),
                        posting(PostingSide.CREDIT, "90.00", accountB),
                    ),
                )
            result.balanced shouldBe false
            result.reason shouldBe "Journal entry not balanced: debits 100.00 != credits 90.00"
        }

        test("a balanced multi-line entry (1x300 debit vs 3x100 credit) is balanced") {
            val result =
                JournalEntryBalance.validateBalanced(
                    listOf(
                        posting(PostingSide.DEBIT, "300.00", accountA),
                        posting(PostingSide.CREDIT, "100.00", accountB),
                        posting(PostingSide.CREDIT, "100.00", accountC),
                        posting(PostingSide.CREDIT, "100.00", accountB),
                    ),
                )
            result.balanced shouldBe true
        }

        test("a zero-amount line is rejected") {
            val result =
                JournalEntryBalance.validateBalanced(
                    listOf(
                        posting(PostingSide.DEBIT, "0.00", accountA),
                        posting(PostingSide.CREDIT, "0.00", accountB),
                    ),
                )
            result.balanced shouldBe false
        }

        test("a negative amount is rejected") {
            val result =
                JournalEntryBalance.validateBalanced(
                    listOf(
                        posting(PostingSide.DEBIT, "-50.00", accountA),
                        posting(PostingSide.CREDIT, "50.00", accountB),
                    ),
                )
            result.balanced shouldBe false
        }

        test("a single-line entry is rejected (at least 2 lines required)") {
            val result = JournalEntryBalance.validateBalanced(listOf(posting(PostingSide.DEBIT, "50.00")))
            result.balanced shouldBe false
        }

        test("an all-debit entry is rejected (no credit side present)") {
            val result =
                JournalEntryBalance.validateBalanced(
                    listOf(
                        posting(PostingSide.DEBIT, "50.00", accountA),
                        posting(PostingSide.DEBIT, "50.00", accountB),
                    ),
                )
            result.balanced shouldBe false
        }

        test("an all-credit entry is rejected (no debit side present)") {
            val result =
                JournalEntryBalance.validateBalanced(
                    listOf(
                        posting(PostingSide.CREDIT, "50.00", accountA),
                        posting(PostingSide.CREDIT, "50.00", accountB),
                    ),
                )
            result.balanced shouldBe false
        }

        test("an empty posting list is rejected") {
            val result = JournalEntryBalance.validateBalanced(emptyList())
            result.balanced shouldBe false
        }

        test("scale edge: 100.00 vs 100.0 is balanced -- BigDecimal.compareTo, not equals") {
            // The classic double-entry bug: two BigDecimals with the same mathematical value but
            // different scale are NOT `equals()` but ARE `compareTo()`-equal. This test pins that
            // validateBalanced uses compareTo, not equals -- see JournalEntryBalance KDoc.
            val debit = BigDecimal("100.00")
            val credit = BigDecimal("100.0")
            debit.equals(credit) shouldBe false
            debit.compareTo(credit) shouldBe 0

            val result =
                JournalEntryBalance.validateBalanced(
                    listOf(
                        PostingInput(
                            ledgerAccountId = accountA,
                            side = PostingSide.DEBIT,
                            amount = debit,
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                        PostingInput(
                            ledgerAccountId = accountB,
                            side = PostingSide.CREDIT,
                            amount = credit,
                            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                        ),
                    ),
                )
            result.balanced shouldBe true
        }

        test("validateBalanced is sphere-agnostic -- a mixed-sphere balanced set still validates (balance and sphere are orthogonal)") {
            val result =
                JournalEntryBalance.validateBalanced(
                    listOf(
                        posting(PostingSide.DEBIT, "40.00", accountA, sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH),
                        posting(PostingSide.CREDIT, "40.00", accountB, sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB),
                    ),
                )
            result.balanced shouldBe true
        }

        test("a scale>2 amount is rejected even though the DB would round it to a balanced sum") {
            // Regression for the sub-cent rounding gap: DEBIT 10.005 + DEBIT 10.005 sums to exactly
            // 20.010 == CREDIT 20.01 at full precision, but PostingTable.amount is DECIMAL(15,2) --
            // persisting 10.005 would silently round to 10.01 (HALF_EVEN), yielding a stored total
            // of 20.02 vs 20.01: a permanently unbalanced, immutable POSTED entry. validateBalanced
            // must reject the scale>2 input outright, never sum it at full precision.
            val result =
                JournalEntryBalance.validateBalanced(
                    listOf(
                        posting(PostingSide.DEBIT, "10.005", accountA),
                        posting(PostingSide.DEBIT, "10.005", accountB),
                        posting(PostingSide.CREDIT, "20.01", accountC),
                    ),
                )
            result.balanced shouldBe false
            result.reason shouldBe
                "Every posting amount must have at most 2 fractional digits, got [10.005, 10.005]"
        }

        test("a scale>2 amount that would round down to 0.00 is rejected, not silently zeroed") {
            // Variant of the rounding gap: an amount like 0.004 rounds to 0.00 under DECIMAL(15,2)
            // persistence, which the strictly-positive check exists specifically to forbid -- but
            // only if it is evaluated against the value that actually gets stored.
            val result =
                JournalEntryBalance.validateBalanced(
                    listOf(
                        posting(PostingSide.DEBIT, "0.004", accountA),
                        posting(PostingSide.CREDIT, "0.004", accountB),
                    ),
                )
            result.balanced shouldBe false
        }

        test("a scale-3 amount with trailing zero (10.010) is rejected too -- scale, not significant digits") {
            val result =
                JournalEntryBalance.validateBalanced(
                    listOf(
                        posting(PostingSide.DEBIT, "10.010", accountA),
                        posting(PostingSide.CREDIT, "10.01", accountB),
                    ),
                )
            result.balanced shouldBe false
        }

        test("a 1.0E20 amount (scale -19, as a JSON double arrives) is rejected: scale check passes, upper bound stops it") {
            val huge = "1.0E20"
            BigDecimal(huge).scale() shouldBe -19
            val result =
                JournalEntryBalance.validateBalanced(
                    listOf(
                        posting(PostingSide.DEBIT, huge, accountA),
                        posting(PostingSide.CREDIT, huge, accountB),
                    ),
                )
            result.balanced shouldBe false
            result.reason shouldBe
                "Every posting amount must be at most 1000000000000.00, got [100000000000000000000, 100000000000000000000]"
        }

        test("the upper bound itself is accepted, one cent above is rejected") {
            JournalEntryBalance
                .validateBalanced(
                    listOf(
                        posting(PostingSide.DEBIT, "1000000000000.00", accountA),
                        posting(PostingSide.CREDIT, "1000000000000.00", accountB),
                    ),
                ).balanced shouldBe true
            JournalEntryBalance
                .validateBalanced(
                    listOf(
                        posting(PostingSide.DEBIT, "1000000000000.01", accountA),
                        posting(PostingSide.CREDIT, "1000000000000.01", accountB),
                    ),
                ).balanced shouldBe false
        }

        test("tooLargeAmounts finds only the oversized postings, independent of balance") {
            val postings =
                listOf(
                    posting(PostingSide.DEBIT, "1.0E20", accountA),
                    posting(PostingSide.CREDIT, "5.00", accountB),
                )
            JournalEntryBalance.tooLargeAmounts(postings) shouldBe listOf(postings[0])
        }
    })
